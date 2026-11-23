package org.openpnp.machine.photon.sheets.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Insets;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.Wizard;
import org.openpnp.gui.support.WizardContainer;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/**
 * The Photon slot location and part offset are applied as you type, without Apply. These tests
 * drive the real feeder panel and check that the user is told so, that each change is confirmed
 * and can be undone, and that text which cannot be applied is flagged.
 */
public class LocationFeedbackTest {
    private static final Location SLOT_LOCATION =
            new Location(LengthUnit.Millimeters, 10, 20, -5, 90);
    private static final Location OFFSET = new Location(LengthUnit.Millimeters, 0.1, 0, -10, 0);

    private PhotonFeeder feeder;
    private FeederConfigurationWizard wizard;

    @BeforeAll
    static void setUpConfiguration() throws Exception {
        System.setProperty("java.awt.headless", "true");
        File directory = Files.createTempDirectory("slot-location-feedback").toFile();
        Configuration.initialize(new File(directory, ".openpnp"));
        Configuration.get().load();
    }

    @BeforeEach
    void createWizard() throws Exception {
        onEdt(() -> {
            feeder = new PhotonFeeder();
            feeder.setHardwareId("00112233445566778899AABB");
            Configuration.get().getMachine().addFeeder(feeder);
            feeder.setSlotAddress(7);
            feeder.getSlot().setLocation(SLOT_LOCATION);
            feeder.setOffset(OFFSET);
            wizard = new FeederConfigurationWizard(feeder);
            wizard.setWizardContainer(new WizardContainer() {
                @Override
                public void wizardCompleted(Wizard wizard) {}

                @Override
                public void wizardCancelled(Wizard wizard) {}
            });
        });
    }

    @AfterEach
    void disposeWizard() throws Exception {
        onEdt(() -> {
            wizard.dispose();
            Configuration.get().getMachine().removeFeeder(feeder);
        });
    }

    @Test
    void explainsThatTheLocationAppliesAsYouType() throws Exception {
        onEdt(() -> {
            assertTrue(status().isVisible());
            assertTrue(status().getText().contains("as you type"), status().getText());
            assertTrue(status().getText().contains("slot 7"), status().getText());
            assertFalse(undo().isVisible());
        });
    }

    @Test
    void typingAppliesImmediatelyAndIsConfirmedWhenTypingPauses() throws Exception {
        onEdt(() -> {
            String hint = status().getText();
            type(field("xSlotTf"), "12.5");

            // Applied live, exactly as before this change.
            assertEquals(12.5, feeder.getSlot().getLocation().getX(), 1e-9);
            // No confirmation for every keystroke.
            assertEquals(hint, status().getText());

            feedback().settle();
            assertTrue(status().getText().contains("Slot 7 location applied"), status().getText());
            assertTrue(undo().isVisible());
            assertTrue(feedback().isHighlighted(field("xSlotTf")));
            assertFalse(feedback().isHighlighted(field("ySlotTf")));
        });
    }

    @Test
    void confirmationAppearsByItselfAfterAPause() throws Exception {
        onEdt(() -> type(field("ySlotTf"), "21"));
        TimeUnit.MILLISECONDS.sleep(LocationFeedback.SETTLE_DELAY_MILLIS * 3);
        onEdt(() -> assertTrue(status().getText().contains("applied"), status().getText()));
    }

    @Test
    void highlightIsVisibleWithoutMovingTheTextAndEndsCleanly() throws Exception {
        onEdt(() -> {
            JTextField z = field("zSlotTf");
            Color background = z.getBackground();
            Border border = z.getBorder();
            Insets insets = z.getInsets();
            type(z, "-4");
            feedback().settle();

            // An outline is drawn even where the look and feel ignores the background (GTK).
            assertNotSame(border, z.getBorder());
            assertEquals(insets, z.getInsets());

            feedback().endHighlight();
            assertFalse(feedback().isHighlighted(z));
            assertEquals(background, z.getBackground());
            assertSame(border, z.getBorder());
        });
    }

    @Test
    void undoRestoresEveryFieldChangedSinceTheLastPause() throws Exception {
        onEdt(() -> {
            // Like a capture button: several fields change at once.
            type(field("xSlotTf"), "11");
            type(field("ySlotTf"), "22");
            type(field("rotSlotTf"), "45");
            feedback().settle();

            undo().doClick(0);

            assertEquals(SLOT_LOCATION, feeder.getSlot().getLocation());
            assertEquals(10.0, Double.parseDouble(field("xSlotTf").getText()), 1e-9);
            assertEquals(20.0, Double.parseDouble(field("ySlotTf").getText()), 1e-9);
            assertEquals(90.0, Double.parseDouble(field("rotSlotTf").getText()), 1e-9);
            assertTrue(status().getText().contains("Slot 7 location restored"), status().getText());
            assertFalse(undo().isVisible());
        });
    }

    @Test
    void undoOnlyGoesBackOneConfirmedChange() throws Exception {
        onEdt(() -> {
            type(field("xSlotTf"), "11");
            feedback().settle();
            type(field("xSlotTf"), "12");
            feedback().settle();

            undo().doClick(0);

            assertEquals(11.0, feeder.getSlot().getLocation().getX(), 1e-9);
        });
    }

    @Test
    void textThatCannotBeAppliedIsFlaggedUntilCorrected() throws Exception {
        onEdt(() -> {
            // "12" is applied on the way to the typo "12-"; "12-" itself is not.
            type(field("xSlotTf"), "12-");
            feedback().settle();

            assertEquals(12.0, feeder.getSlot().getLocation().getX(), 1e-9);
            assertTrue(feedback().isMarkedInvalid(field("xSlotTf")));
            assertTrue(status().getText().contains("Invalid value in X"), status().getText());
            assertTrue(status().getText().contains("last valid value"), status().getText());
            assertTrue(undo().isVisible());

            type(field("xSlotTf"), "13");
            assertFalse(feedback().isMarkedInvalid(field("xSlotTf")));
            feedback().settle();
            assertEquals(13.0, feeder.getSlot().getLocation().getX(), 1e-9);
            assertTrue(status().getText().contains("applied"), status().getText());

            undo().doClick(0);
            assertEquals(12.0, feeder.getSlot().getLocation().getX(), 1e-9);
        });
    }

    @Test
    void movingTheFeederToAnotherSlotIsNotReportedAsAnEdit() throws Exception {
        onEdt(() -> {
            type(field("xSlotTf"), "11");
            feedback().settle();

            feeder.setSlotAddress(8);
            feedback().settle();

            assertTrue(status().getText().contains("slot 8"), status().getText());
            assertFalse(status().getText().contains("applied"), status().getText());
            assertFalse(undo().isVisible());
        });
    }

    @Test
    void firstLocationForANewSlotCannotBeUndone() throws Exception {
        onEdt(() -> {
            feeder.setSlotAddress(9);
            assertNull(feeder.getSlot().getLocation());
            type(field("xSlotTf"), "5");
            feedback().settle();

            assertTrue(status().getText().contains("Slot 9 location applied"), status().getText());
            assertFalse(undo().isVisible());
        });
    }

    @Test
    void feedbackIsHiddenWhenTheFeederHasNoSlot() throws Exception {
        onEdt(() -> {
            feeder.setSlotAddress(null);
            assertFalse(status().isVisible());
            assertFalse(undo().isVisible());
        });
    }

    @Test
    void partOffsetAppliesAsYouTypeAndCanBeUndone() throws Exception {
        onEdt(() -> {
            assertTrue(offsetStatus().getText().contains("Used only by this feeder"),
                    offsetStatus().getText());

            type(field("xOffsetTf"), "0.25");
            // No Apply needed.
            assertEquals(0.25, feeder.getOffset().getX(), 1e-9);

            offsetFeedback().settle();
            assertTrue(offsetStatus().getText().contains("Part offset applied"),
                    offsetStatus().getText());
            assertTrue(offsetFeedback().isHighlighted(field("xOffsetTf")));
            // The slot location row is not affected.
            assertFalse(status().getText().contains("applied"), status().getText());

            offsetUndo().doClick(0);
            assertEquals(OFFSET, feeder.getOffset());
            assertEquals(0.1, Double.parseDouble(field("xOffsetTf").getText()), 1e-9);
            assertTrue(offsetStatus().getText().contains("Part offset restored"),
                    offsetStatus().getText());
        });
    }

    @Test
    void partOffsetFieldsFollowChangesMadeElsewhere() throws Exception {
        onEdt(() -> {
            // E.g. Pick Offset Correction folding a correction into the offset.
            feeder.setOffset(new Location(LengthUnit.Millimeters, 0.3, 0.2, -10, 0));
            assertEquals(0.3, Double.parseDouble(field("xOffsetTf").getText()), 1e-9);
            assertEquals(0.2, Double.parseDouble(field("yOffsetTf").getText()), 1e-9);
        });
    }

    @Test
    void invalidPartOffsetIsFlagged() throws Exception {
        onEdt(() -> {
            type(field("yOffsetTf"), "0.-");
            offsetFeedback().settle();
            assertTrue(offsetFeedback().isMarkedInvalid(field("yOffsetTf")));
            assertTrue(offsetStatus().getText().contains("Invalid value in Y"),
                    offsetStatus().getText());
        });
    }

    /** Types text the way a user does: select all, then one character at a time. */
    private static void type(JTextField textField, String text) {
        textField.selectAll();
        textField.replaceSelection("");
        for (char c : text.toCharArray()) {
            textField.replaceSelection(String.valueOf(c));
        }
    }

    private JTextField field(String name) {
        return (JTextField) wizardField(name);
    }

    private JLabel status() {
        return (JLabel) wizardField("slotLocationStatusLabel");
    }

    private JButton undo() {
        return (JButton) wizardField("slotLocationUndoButton");
    }

    private LocationFeedback feedback() {
        return (LocationFeedback) wizardField("slotLocationFeedback");
    }

    private JLabel offsetStatus() {
        return (JLabel) wizardField("offsetStatusLabel");
    }

    private JButton offsetUndo() {
        return (JButton) wizardField("offsetUndoButton");
    }

    private LocationFeedback offsetFeedback() {
        return (LocationFeedback) wizardField("offsetFeedback");
    }

    private Object wizardField(String name) {
        try {
            Field field = FeederConfigurationWizard.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(wizard);
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private interface EdtTask {
        void run() throws Exception;
    }

    private static void onEdt(EdtTask task) throws Exception {
        Exception[] failure = new Exception[1];
        Error[] error = new Error[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                task.run();
            }
            catch (Exception e) {
                failure[0] = e;
            }
            catch (Error e) {
                error[0] = e;
            }
        });
        if (error[0] != null) {
            throw error[0];
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }
}
