package org.openpnp.gui.support;

import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.WizardApplyHarness.Fixture;
import org.openpnp.gui.support.WizardApplyHarness.Outcome;
import org.openpnp.gui.support.WizardApplyHarness.Result;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.spi.PropertySheetHolder.PropertySheet;
import org.simpleframework.xml.Serializer;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Survey of every feeder configuration panel: edits each component and reports whether Apply is
 * enabled and the edit is saved. Report only; run explicitly with
 * {@code mvn test -Dtest=FeederWizardApplySurveyTest -Dopenpnp.wizardSurvey=true}.
 */
class FeederWizardApplySurveyTest {

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("openpnp.wizardSurvey"),
                "survey only runs with -Dopenpnp.wizardSurvey=true");
        System.setProperty("java.awt.headless", "true");
        File workingDirectory = Files.createTempDirectory("wizard-survey").toFile();
        Configuration.initialize(new File(workingDirectory, ".openpnp"));
        Configuration.get().load();
    }

    @Test
    void surveyFeederPanels() throws Exception {
        List<Class<? extends Feeder>> feederClasses = findFeederClasses();
        List<Result> results = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (Class<? extends Feeder> feederClass : feederClasses) {
                int sheetCount;
                try {
                    Feeder probe = createFeeder(feederClass);
                    sheetCount = probe.getPropertySheets().length;
                    Configuration.get().getMachine().removeFeeder(probe);
                }
                catch (Throwable t) {
                    problems.add(feederClass.getSimpleName() + ": cannot create: " + t);
                    continue;
                }
                for (int s = 0; s < sheetCount; s++) {
                    final int sheetIndex = s;
                    String[] title = new String[1];
                    try {
                        results.addAll(WizardApplyHarness.survey(
                                feederClass.getSimpleName() + "/" + s,
                                () -> createFixture(feederClass, sheetIndex, title)));
                    }
                    catch (Throwable t) {
                        problems.add(feederClass.getSimpleName() + " sheet " + s + ": " + t);
                    }
                }
            }
        });
        writeReport(results, problems);
    }

    private static Fixture createFixture(Class<? extends Feeder> feederClass, int sheetIndex,
            String[] title) {
        try {
            Feeder feeder = createFeeder(feederClass);
            PropertySheet sheet = feeder.getPropertySheets()[sheetIndex];
            title[0] = sheet.getPropertySheetTitle();
            JPanel panel = sheet.getPropertySheetPanel();
            WizardApplyHarness.initialise(panel);
            Machine machine = Configuration.get().getMachine();
            return new Fixture() {
                @Override
                public JPanel getPanel() {
                    return panel;
                }

                @Override
                public String snapshotModel() throws Exception {
                    Serializer serializer = Configuration.createSerializer();
                    StringWriter writer = new StringWriter();
                    serializer.write(machine, writer);
                    return writer.toString();
                }

                @Override
                public void dispose() {
                    if (panel instanceof AbstractConfigurationWizard) {
                        ((AbstractConfigurationWizard) panel).dispose();
                    }
                    machine.removeFeeder(feeder);
                }
            };
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a feeder configured the way a user would have it when editing it: added to the
     * machine and assigned a part.
     */
    private static Feeder createFeeder(Class<? extends Feeder> feederClass) throws Exception {
        Feeder feeder = feederClass.getDeclaredConstructor().newInstance();
        List<Part> parts = Configuration.get().getParts();
        if (!parts.isEmpty()) {
            feeder.setPart(parts.get(0));
        }
        if (feeder instanceof PhotonFeeder) {
            // Photon panels are only shown, and slot fields only enabled, for a feeder that has
            // been discovered and placed in a slot.
            PhotonFeeder photon = (PhotonFeeder) feeder;
            photon.setHardwareId("00112233445566778899AABB");
            photon.setSlotAddress(1);
        }
        Configuration.get().getMachine().addFeeder(feeder);
        return feeder;
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends Feeder>> findFeederClasses() {
        List<Class<? extends Feeder>> classes = new ArrayList<>();
        try (ScanResult scan = new ClassGraph().enableClassInfo().acceptPackages("org.openpnp")
                .rejectPackages("org.openpnp.gui").scan()) {
            for (ClassInfo info : scan.getClassesImplementing(Feeder.class.getName())) {
                if (info.isAbstract() || info.isInterface() || info.isInnerClass()) {
                    continue;
                }
                Class<?> cls = info.loadClass();
                if (!Modifier.isPublic(cls.getModifiers())) {
                    continue;
                }
                classes.add((Class<? extends Feeder>) cls);
            }
        }
        classes.sort((a, b) -> a.getSimpleName().compareTo(b.getSimpleName()));
        return classes;
    }

    private static void writeReport(List<Result> results, List<String> problems)
            throws Exception {
        Map<Outcome, Integer> totals = new EnumMap<>(Outcome.class);
        StringBuilder sb = new StringBuilder();
        sb.append("# Feeder panel Apply survey\n\n");
        for (Result r : results) {
            totals.merge(r.outcome, 1, Integer::sum);
        }
        sb.append("| Outcome | Count |\n|---|---|\n");
        for (Map.Entry<Outcome, Integer> e : totals.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        if (!problems.isEmpty()) {
            sb.append("\n## Panels that could not be surveyed\n\n");
            for (String p : problems) {
                sb.append("- ").append(p).append('\n');
            }
        }
        sb.append("\n## All results\n\n| Panel | # | Component | Edit | Outcome | Detail |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Result r : results) {
            sb.append("| ").append(r.panel).append(" | ").append(r.index).append(" | ")
                    .append(r.component.replace("|", "/")).append(" | ")
                    .append(r.edit.replace("|", "/")).append(" | ").append(r.outcome)
                    .append(" | ").append(r.detail == null ? "" : r.detail.replace("|", "/"))
                    .append(" |\n");
        }
        File report = new File("target/feeder-wizard-apply-survey.md");
        Files.write(report.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Survey report written to " + report.getAbsolutePath());
        System.out.println(totals);
    }
}
