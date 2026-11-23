package org.openpnp.gui.support;

import java.awt.Component;
import java.awt.Container;
import java.awt.HeadlessException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;

/**
 * Exercises every user-editable component on a configuration panel and records what happens:
 * whether the Apply button becomes enabled, whether the model changes immediately, and whether
 * clicking Apply actually stores the edit.
 * <p>
 * Each component is tested on a freshly created panel and model so edits do not interfere with
 * each other.
 */
public class WizardApplyHarness {

    /** What happened after the user edited one component. */
    public enum Outcome {
        /** Apply became enabled and clicking it changed the model. The expected behavior. */
        APPLY_SAVES,
        /** Apply became enabled but clicking it did not change the model. */
        APPLY_DOES_NOT_SAVE,
        /** Apply became enabled, but the model had already changed before Apply was clicked. */
        APPLY_BUT_ALREADY_LIVE,
        /** Apply stayed disabled; the model changed immediately (Reset cannot undo it). */
        LIVE_NO_APPLY,
        /** Apply stayed disabled and the model did not change: the edit is silently lost. */
        NOTHING,
        /** The component could not be edited in a meaningful way (e.g. single-item combo). */
        SKIPPED,
        /** An exception was thrown while editing or applying. */
        ERROR
    }

    /** The result of editing one component. */
    public static class Result {
        public final String panel;
        public final int index;
        public final String component;
        public final String edit;
        public final Outcome outcome;
        public final String detail;

        Result(String panel, int index, String component, String edit, Outcome outcome,
                String detail) {
            this.panel = panel;
            this.index = index;
            this.component = component;
            this.edit = edit;
            this.outcome = outcome;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return String.format("%s #%d %s [%s] -> %s%s", panel, index, component, edit, outcome,
                    detail == null ? "" : " (" + detail + ")");
        }
    }

    /** A freshly created panel together with the means to observe its model. */
    public interface Fixture {
        /** The panel exactly as it would be shown to the user, already initialised. */
        JPanel getPanel();

        /** A serialized snapshot of all model state the panel could affect. */
        String snapshotModel() throws Exception;

        /** Release anything the fixture registered (e.g. remove a feeder from the machine). */
        default void dispose() {
        }
    }

    private static final WizardContainer NO_OP_CONTAINER = new WizardContainer() {
        @Override
        public void wizardCompleted(Wizard wizard) {
        }

        @Override
        public void wizardCancelled(Wizard wizard) {
        }
    };

    /**
     * Initialises a panel the same way the Feeders tab does before showing it.
     */
    public static void initialise(JPanel panel) {
        if (panel instanceof AbstractConfigurationWizard) {
            ((AbstractConfigurationWizard) panel).setWizardContainer(NO_OP_CONTAINER);
        }
    }

    /**
     * Edits every editable component of the panel produced by the factory, one at a time, each
     * on a fresh fixture.
     *
     * @param panelName name used in the results
     * @param factory creates a fresh, initialised fixture for each component
     */
    public static List<Result> survey(String panelName, Supplier<Fixture> factory) {
        List<Result> results = new ArrayList<>();
        int count;
        Fixture first = factory.get();
        try {
            count = findEditableComponents(first.getPanel()).size();
        }
        finally {
            first.dispose();
        }
        for (int i = 0; i < count; i++) {
            Fixture fixture = factory.get();
            try {
                results.add(exercise(panelName, i, fixture));
            }
            catch (Throwable t) {
                results.add(new Result(panelName, i, "?", "?", Outcome.ERROR, describe(t)));
            }
            finally {
                fixture.dispose();
            }
        }
        return results;
    }

    private static Result exercise(String panelName, int index, Fixture fixture) throws Exception {
        JPanel panel = fixture.getPanel();
        List<Component> editables = findEditableComponents(panel);
        if (index >= editables.size()) {
            return new Result(panelName, index, "?", "?", Outcome.ERROR,
                    "component list differs between fresh panels");
        }
        Component component = editables.get(index);
        String name = describeComponent(panel, component);
        AbstractConfigurationWizard wizard = panel instanceof AbstractConfigurationWizard
                ? (AbstractConfigurationWizard) panel
                : null;

        String before = fixture.snapshotModel();
        boolean applyBefore = wizard != null && wizard.applyAction.isEnabled();
        String edit;
        try {
            edit = edit(component);
        }
        catch (Throwable t) {
            return new Result(panelName, index, name, "edit", Outcome.ERROR, describe(t));
        }
        if (edit == null) {
            return new Result(panelName, index, name, "-", Outcome.SKIPPED,
                    "no alternative value to choose");
        }
        String afterEdit = fixture.snapshotModel();
        boolean liveChange = !before.equals(afterEdit);
        boolean applyEnabled = wizard != null && !applyBefore && wizard.applyAction.isEnabled();

        if (!applyEnabled) {
            String detail = applyBefore ? "Apply was already enabled before the edit" : null;
            if (wizard == null) {
                detail = "panel is not an AbstractConfigurationWizard";
            }
            return new Result(panelName, index, name, edit,
                    liveChange ? Outcome.LIVE_NO_APPLY : Outcome.NOTHING, detail);
        }
        if (liveChange) {
            return new Result(panelName, index, name, edit, Outcome.APPLY_BUT_ALREADY_LIVE, null);
        }
        String applyDetail = null;
        try {
            wizard.applyAction.actionPerformed(null);
        }
        catch (HeadlessException e) {
            // Apply showed a message box (typically a validation warning after saving). In the
            // app the user would see and dismiss it; the save itself has already happened.
            applyDetail = "Apply showed a dialog";
        }
        catch (Throwable t) {
            return new Result(panelName, index, name, edit, Outcome.ERROR,
                    "on apply: " + describe(t));
        }
        String afterApply = fixture.snapshotModel();
        return new Result(panelName, index, name, edit,
                before.equals(afterApply) ? Outcome.APPLY_DOES_NOT_SAVE : Outcome.APPLY_SAVES,
                applyDetail);
    }

    /**
     * Returns the components a user can change, in a stable depth-first order. Buttons that
     * perform actions are excluded; check boxes, toggles and radio buttons are included.
     */
    public static List<Component> findEditableComponents(Container root) {
        List<Component> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(Container container, List<Component> result) {
        for (int i = 0; i < container.getComponentCount(); i++) {
            Component c = container.getComponent(i);
            if (!c.isVisible()) {
                continue;
            }
            if (c instanceof JComboBox || c instanceof JSpinner || c instanceof JSlider) {
                if (c.isEnabled()) {
                    result.add(c);
                }
                // Do not descend: their inner editors are handled through the parent.
                continue;
            }
            if (c instanceof JTextComponent) {
                JTextComponent text = (JTextComponent) c;
                if (text.isEnabled() && text.isEditable()) {
                    result.add(c);
                }
                continue;
            }
            if (c instanceof AbstractButton && !(c instanceof JButton)) {
                if (c.isEnabled()) {
                    result.add(c);
                }
                continue;
            }
            if (c instanceof JTabbedPane) {
                // Every tab is reachable by the user, so search all of them.
                JTabbedPane tabs = (JTabbedPane) c;
                for (int t = 0; t < tabs.getTabCount(); t++) {
                    Component tab = tabs.getComponentAt(t);
                    if (tab instanceof Container) {
                        collect((Container) tab, result);
                    }
                }
                continue;
            }
            if (c instanceof Container) {
                collect((Container) c, result);
            }
        }
    }

    private static final Pattern NUMBER = Pattern.compile("^(\\s*-?)(\\d*)(\\.(\\d+))?(.*)$");

    /**
     * Makes a user-like edit to the component. Returns a description of the edit, or null if
     * there is nothing different to change it to.
     */
    static String edit(Component c) {
        if (c instanceof JComboBox) {
            JComboBox<?> combo = (JComboBox<?>) c;
            Object current = combo.getSelectedItem();
            int count = combo.getItemCount();
            int start = Math.max(combo.getSelectedIndex(), 0);
            // Pick the next real item that differs from the current one; skip blank entries.
            for (int step = 1; step < count; step++) {
                int next = (start + step) % count;
                Object item = combo.getItemAt(next);
                if (item == null || item.toString().trim().isEmpty() || item.equals(current)) {
                    continue;
                }
                combo.setSelectedIndex(next);
                return "select '" + item + "'";
            }
            return null;
        }
        if (c instanceof JSpinner) {
            JSpinner spinner = (JSpinner) c;
            Object next = spinner.getNextValue();
            if (next == null) {
                next = spinner.getPreviousValue();
            }
            if (next == null) {
                return null;
            }
            spinner.setValue(next);
            return "spin to " + next;
        }
        if (c instanceof JSlider) {
            JSlider slider = (JSlider) c;
            int value = slider.getValue() < slider.getMaximum() ? slider.getValue() + 1
                    : slider.getValue() - 1;
            slider.setValue(value);
            return "slide to " + value;
        }
        if (c instanceof AbstractButton) {
            AbstractButton button = (AbstractButton) c;
            boolean wasSelected = button.isSelected();
            button.doClick(0);
            if (button.isSelected() == wasSelected) {
                // e.g. clicking the already-selected radio button of a group changes nothing.
                return null;
            }
            return "click (now " + button.isSelected() + ")";
        }
        if (c instanceof JTextComponent) {
            JTextComponent text = (JTextComponent) c;
            String newText = changedText(text.getText());
            // Replace the whole text the way a user selecting all and typing would.
            text.selectAll();
            text.replaceSelection(newText);
            if (text instanceof JTextField) {
                // Many fields commit on Enter or focus loss; Enter is the user-visible way.
                ((JTextField) text).postActionEvent();
            }
            return "type '" + newText + "'";
        }
        return null;
    }

    /** Produces a different, still plausible value: numbers are increased, text is extended. */
    static String changedText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "1";
        }
        Matcher m = NUMBER.matcher(text);
        if (m.matches() && (!m.group(2).isEmpty() || m.group(4) != null)) {
            String sign = m.group(1);
            String integer = m.group(2).isEmpty() ? "0" : m.group(2);
            String fraction = m.group(4);
            String suffix = m.group(5);
            long whole = Long.parseLong(integer) + 1;
            return sign + whole + (fraction == null ? "" : "." + fraction) + suffix;
        }
        return text + "X";
    }

    /**
     * Names a component by the panel field that holds it and by its label, so results can be
     * traced back to the source.
     */
    static String describeComponent(JPanel panel, Component component) {
        String field = findFieldName(panel, component);
        String label = findLabel(component);
        String type = component.getClass().getSimpleName();
        StringBuilder sb = new StringBuilder();
        sb.append(field != null ? field : type);
        if (label != null) {
            sb.append(" \"").append(label).append('"');
        }
        return sb.toString();
    }

    private static String findFieldName(Object owner, Component component) {
        for (Class<?> cls = owner.getClass(); cls != null && cls != JPanel.class;
                cls = cls.getSuperclass()) {
            for (Field f : cls.getDeclaredFields()) {
                if (!Component.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (f.get(owner) == component) {
                        return f.getName();
                    }
                }
                catch (Exception e) {
                    // Inaccessible field; try the next one.
                }
            }
        }
        return null;
    }

    private static String findLabel(Component component) {
        Container parent = component.getParent();
        if (parent == null) {
            return null;
        }
        for (Component sibling : parent.getComponents()) {
            if (sibling instanceof JLabel && ((JLabel) sibling).getLabelFor() == component) {
                return ((JLabel) sibling).getText();
            }
        }
        // Fall back to the closest label placed before the component in the same container.
        String label = null;
        for (Component sibling : parent.getComponents()) {
            if (sibling == component) {
                break;
            }
            if (sibling instanceof JLabel) {
                String text = ((JLabel) sibling).getText();
                if (text != null && !text.trim().isEmpty()) {
                    label = text.trim();
                }
            }
        }
        return label;
    }

    private static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        StackTraceElement top = null;
        for (StackTraceElement e : root.getStackTrace()) {
            if (e.getClassName().startsWith("org.openpnp")) {
                top = e;
                break;
            }
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage()
                + (top == null ? "" : " at " + top.getClassName().replace("org.openpnp.", "")
                        + ":" + top.getLineNumber());
    }
}
