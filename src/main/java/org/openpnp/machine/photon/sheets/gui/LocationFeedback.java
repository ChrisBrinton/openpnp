package org.openpnp.machine.photon.sheets.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import java.beans.PropertyChangeListener;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.Border;

import org.jdesktop.beansbinding.AbstractBindingListener;
import org.jdesktop.beansbinding.Binding;
import org.jdesktop.beansbinding.Binding.SyncFailure;
import org.openpnp.Translations;
import org.openpnp.gui.support.Icons;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.Location;

/**
 * Feedback for a row of location fields on the Photon feeder panel that applies as you type.
 * <p>
 * The slot location and the part offset are written to the model as soon as the text is a valid
 * value, without pressing Apply. This class makes that visible:
 * <ul>
 * <li>a status line under the fields says that the row applies as you type;</li>
 * <li>once typing pauses, the change is confirmed with the time and an Undo button, and the
 * fields that changed are briefly highlighted;</li>
 * <li>a field whose text is not a valid value, and so has not been applied, is marked until it is
 * corrected.</li>
 * </ul>
 */
class LocationFeedback {
    static final int SETTLE_DELAY_MILLIS = 700;
    static final int HIGHLIGHT_MILLIS = 1000;

    private static final Color APPLIED_FALLBACK = new Color(0x59A869);
    private static final Color INVALID_FALLBACK = new Color(0xEDA200);

    private final SlotProxy slotProxy;
    private final AbstractModelObject bean;
    private final String property;
    private final Supplier<Location> getter;
    private final Consumer<Location> setter;
    private final String messagePrefix;
    private final JLabel statusLabel;
    private final JButton undoButton;
    private final List<Field> fields = new ArrayList<>();
    private final Set<Field> invalidFields = new LinkedHashSet<>();
    private final Timer settleTimer;
    private final Timer highlightTimer;
    private final PropertyChangeListener slotListener = e -> slotChanged();
    private final PropertyChangeListener locationListener = e -> locationChanged(
            (Location) e.getOldValue(), (Location) e.getNewValue());

    /** Location shown in the fields, used to tell edits apart from a change of slot. */
    private Location knownLocation;
    /** True between the first change of an edit and the moment typing pauses. */
    private boolean editing;
    /** Location before the current edit started. */
    private Location editStartLocation;
    /** Location the Undo button restores, or null when there is nothing to undo. */
    private Location undoLocation;
    /** Last confirmation shown, or null to show the hint. */
    private String message;
    private boolean messageIsUndo;
    private final Set<Field> highlightedFields = new LinkedHashSet<>();

    private static class Field {
        final JTextField textField;
        final String name;
        final Function<Location, Object> value;
        /** The look and feel's background and border, restored after highlighting. */
        final Color background;
        final Border border;

        Field(JTextField textField, String name, Function<Location, Object> value) {
            this.textField = textField;
            this.name = name;
            this.value = value;
            this.background = textField.getBackground();
            this.border = textField.getBorder();
        }
    }

    /**
     * @param slotProxy the feeder's slot: feedback is shown only while the feeder is in a slot,
     *        and moving to another slot clears it.
     * @param bean the object that holds the location, and fires changes to it as {@code property}.
     * @param messagePrefix translation key prefix for the Hint, Applied and Restored messages.
     */
    LocationFeedback(SlotProxy slotProxy, AbstractModelObject bean, String property,
            Supplier<Location> getter, Consumer<Location> setter, String messagePrefix,
            JLabel statusLabel, JButton undoButton) {
        this.slotProxy = slotProxy;
        this.bean = bean;
        this.property = property;
        this.getter = getter;
        this.setter = setter;
        this.messagePrefix = messagePrefix;
        this.statusLabel = statusLabel;
        this.undoButton = undoButton;
        statusLabel.setToolTipText(
                Translations.getString("FeederConfigurationWizard.LocationFeedback.toolTipText")); //$NON-NLS-1$
        undoButton.setText(
                Translations.getString("FeederConfigurationWizard.LocationFeedback.Undo")); //$NON-NLS-1$
        undoButton.setIcon(Icons.undo);
        undoButton.addActionListener(e -> undo());

        settleTimer = new Timer(SETTLE_DELAY_MILLIS, e -> settle());
        settleTimer.setRepeats(false);
        highlightTimer = new Timer(HIGHLIGHT_MILLIS, e -> endHighlight());
        highlightTimer.setRepeats(false);

        knownLocation = getter.get();
        slotProxy.addPropertyChangeListener("slot", slotListener); //$NON-NLS-1$
        bean.addPropertyChangeListener(property, locationListener);
        refresh();
    }

    /**
     * Registers one of the row's fields and the binding that writes it to the model, so that text
     * which cannot be applied is reported.
     */
    void addField(JTextField textField, String name, Function<Location, Object> value,
            Binding<?, ?, ?, ?> binding) {
        Field field = new Field(textField, name, value);
        fields.add(field);
        binding.addBindingListener(new AbstractBindingListener() {
            @Override
            public void syncFailed(@SuppressWarnings("rawtypes") Binding binding,
                    SyncFailure failure) {
                invalidFields.add(field);
                settleTimer.restart();
            }

            @Override
            public void synced(@SuppressWarnings("rawtypes") Binding binding) {
                if (invalidFields.remove(field)) {
                    unmark(field);
                    refresh();
                }
            }
        });
    }

    void dispose() {
        settleTimer.stop();
        highlightTimer.stop();
        slotProxy.removePropertyChangeListener("slot", slotListener); //$NON-NLS-1$
        bean.removePropertyChangeListener(property, locationListener);
    }

    private void slotChanged() {
        // The feeder was moved to another slot (or removed from one): the slot location fields now
        // show that slot's location, which is not an edit and cannot be undone from here.
        settleTimer.stop();
        endHighlight();
        for (Field field : invalidFields) {
            unmark(field);
        }
        invalidFields.clear();
        editing = false;
        undoLocation = null;
        message = null;
        knownLocation = getter.get();
        refresh();
    }

    private void locationChanged(Location oldLocation, Location newLocation) {
        if (Objects.equals(newLocation, knownLocation)) {
            // Echo of a slot change or of our own undo.
            return;
        }
        knownLocation = newLocation;
        if (!editing) {
            editing = true;
            editStartLocation = oldLocation;
        }
        settleTimer.restart();
    }

    /** Called once typing has paused; confirms what was applied. */
    void settle() {
        settleTimer.stop();
        if (editing) {
            editing = false;
            Location current = getter.get();
            if (!Objects.equals(editStartLocation, current)) {
                // A slot that had no location yet has nothing to go back to.
                undoLocation = editStartLocation;
                message = Translations.format(
                        messagePrefix + ".Applied", //$NON-NLS-1$
                        slotProxy.getSlotAddress(), now());
                messageIsUndo = false;
                highlight(editStartLocation, current);
            }
        }
        for (Field field : invalidFields) {
            mark(field, color("Actions.Yellow", INVALID_FALLBACK)); //$NON-NLS-1$
        }
        refresh();
    }

    /** Restores the location from before the last confirmed change. */
    void undo() {
        if (undoLocation == null) {
            return;
        }
        settle();
        Location current = getter.get();
        Location restore = undoLocation;
        undoLocation = null;
        knownLocation = restore;
        setter.accept(restore);
        message = Translations.format(messagePrefix + ".Restored", //$NON-NLS-1$
                slotProxy.getSlotAddress(), now());
        messageIsUndo = true;
        highlight(current, restore);
        refresh();
    }

    private void refresh() {
        boolean hasSlot = slotProxy.isEnabled();
        statusLabel.setVisible(hasSlot);
        undoButton.setVisible(hasSlot && undoLocation != null);
        if (!hasSlot) {
            return;
        }
        if (!invalidFields.isEmpty() && !editing) {
            List<String> names = new ArrayList<>();
            for (Field field : invalidFields) {
                names.add(field.name);
            }
            statusLabel.setIcon(Icons.errorAlert);
            statusLabel.setText(Translations.format(
                    "FeederConfigurationWizard.LocationFeedback.NotApplied", //$NON-NLS-1$
                    String.join(", ", names))); //$NON-NLS-1$
        }
        else if (message != null) {
            statusLabel.setIcon(messageIsUndo ? Icons.undo : Icons.accept);
            statusLabel.setText(message);
        }
        else {
            statusLabel.setIcon(Icons.info);
            statusLabel.setText(Translations.format(
                    messagePrefix + ".Hint", //$NON-NLS-1$
                    slotProxy.getSlotAddress()));
        }
    }

    private void highlight(Location before, Location after) {
        endHighlight();
        for (Field field : fields) {
            if (invalidFields.contains(field)) {
                continue;
            }
            Object a = before == null ? null : field.value.apply(before.convertToUnits(
                    after == null ? before.getUnits() : after.getUnits()));
            Object b = after == null ? null : field.value.apply(after);
            if (!Objects.equals(a, b)) {
                highlightedFields.add(field);
                mark(field, color("Actions.Green", APPLIED_FALLBACK)); //$NON-NLS-1$
            }
        }
        if (!highlightedFields.isEmpty()) {
            highlightTimer.restart();
        }
    }

    void endHighlight() {
        highlightTimer.stop();
        for (Field field : highlightedFields) {
            if (!invalidFields.contains(field)) {
                unmark(field);
            }
        }
        highlightedFields.clear();
    }

    boolean isHighlighted(JTextField textField) {
        return highlightedFields.stream().anyMatch(f -> f.textField == textField);
    }

    boolean isMarkedInvalid(JTextField textField) {
        return !editing && invalidFields.stream().anyMatch(f -> f.textField == textField);
    }

    /**
     * Marks a field with a coloured outline, plus a background tint where the look and feel
     * paints one (the GTK look and feel ignores text field backgrounds, but draws borders).
     */
    private static void mark(Field field, Color accent) {
        field.textField.setBackground(tint(field.textField, accent));
        field.textField.setBorder(new OutlineBorder(field.border, accent));
    }

    private static void unmark(Field field) {
        field.textField.setBackground(field.background);
        field.textField.setBorder(field.border);
    }

    /**
     * Paints a two pixel outline inside the original border's insets, so marking a field does
     * not change its size or move the text.
     */
    private static class OutlineBorder implements Border {
        private final Border original;
        private final Color color;

        OutlineBorder(Border original, Color color) {
            this.original = original;
            this.color = color;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            if (original != null) {
                original.paintBorder(c, g, x, y, width, height);
            }
            g.setColor(color);
            g.drawRect(x, y, width - 1, height - 1);
            g.drawRect(x + 1, y + 1, width - 3, height - 3);
        }

        @Override
        public Insets getBorderInsets(Component c) {
            if (original != null) {
                return original.getBorderInsets(c);
            }
            return new Insets(2, 2, 2, 2);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }

    private static Color tint(JTextField textField, Color accent) {
        Color base = UIManager.getColor("TextField.background"); //$NON-NLS-1$
        if (base == null) {
            base = textField.getBackground();
        }
        double f = 0.35;
        return new Color(
                (int) Math.round(base.getRed() * (1 - f) + accent.getRed() * f),
                (int) Math.round(base.getGreen() * (1 - f) + accent.getGreen() * f),
                (int) Math.round(base.getBlue() * (1 - f) + accent.getBlue() * f));
    }

    private static Color color(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    private static String now() {
        return DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
    }
}
