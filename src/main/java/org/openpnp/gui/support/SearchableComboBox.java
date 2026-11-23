package org.openpnp.gui.support;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javax.swing.AbstractListModel;
import javax.swing.ComboBoxEditor;
import javax.swing.ComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;

import org.openpnp.Translations;
import org.openpnp.model.Part;

/**
 * Makes an existing {@link JComboBox} searchable: typing in it narrows the drop down list to the
 * items whose text contains what was typed, ignoring case.
 * <p>
 * The selected item is always one of the combo box's items (or null), never the typed text, so
 * code and bindings that use {@code selectedItem} keep working unchanged. Enter selects the item
 * whose text matches exactly, or the only item left in the list. Leaving the field selects the
 * item whose text matches exactly; any other text is replaced by the selected item again.
 * <p>
 * A magnifying glass in the field and a tool tip show that the list can be searched.
 * <p>
 * Call {@link #install} after the combo box has its model, e.g.
 * <pre>
 * comboBoxPart.setModel(new PartsComboBoxModel());
 * comboBoxPart.setRenderer(new IdentifiableListCellRenderer&lt;Part&gt;());
 * SearchableComboBox.installForParts(comboBoxPart);
 * </pre>
 */
public final class SearchableComboBox {
    private SearchableComboBox() {
    }

    /** Makes a combo box of {@link Part}s searchable by part id. */
    public static void installForParts(JComboBox<?> comboBox) {
        install(comboBox, item -> item instanceof Part ? ((Part) item).getId() : null);
    }

    /**
     * Makes the combo box searchable.
     *
     * @param toText the text shown for, and searched in, each item.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void install(JComboBox comboBox, Function<Object, String> toText) {
        FilteredModel model = new FilteredModel(comboBox.getModel(), toText);
        SearchEditor editor = new SearchEditor(comboBox, model, comboBox.getEditor(), toText);
        comboBox.setModel(model);
        comboBox.setEditor(editor);
        comboBox.setEditable(true);
        editor.setItem(model.getSelectedItem());
        String hint = Translations.getString("SearchableComboBox.ToolTip"); //$NON-NLS-1$
        if (comboBox.getToolTipText() == null) {
            comboBox.setToolTipText(hint);
        }
        JComponent field = (JComponent) editor.getEditorComponent();
        if (field.getToolTipText() == null) {
            field.setToolTipText(comboBox.getToolTipText());
        }
        // Shown by FlatLaf when the field is empty; other looks and feels ignore it.
        field.putClientProperty("JTextField.placeholderText", //$NON-NLS-1$
                Translations.getString("SearchableComboBox.Placeholder")); //$NON-NLS-1$
        SearchIconBorder.install(field);
        // A look and feel change gives the field a new border, so add the icon again.
        field.addPropertyChangeListener("UI", //$NON-NLS-1$
                e -> SwingUtilities.invokeLater(() -> SearchIconBorder.install(field)));
    }

    /**
     * Paints a small magnifying glass at the start of the field, inside the look and feel's own
     * border, so the field reads as a search box in any look and feel.
     */
    static class SearchIconBorder extends AbstractBorder {
        static void install(JComponent field) {
            Border border = field.getBorder();
            if (border instanceof CompoundBorder
                    && ((CompoundBorder) border).getInsideBorder() instanceof SearchIconBorder) {
                return;
            }
            field.setBorder(border == null ? new SearchIconBorder()
                    : new CompoundBorder(border, new SearchIconBorder()));
        }

        /** The icon's size, following the font so it scales with it. */
        private static int size(Component c) {
            return Math.max(8, Math.round(c.getFont().getSize() * 0.8f));
        }

        private static int gap(Component c) {
            return Math.max(2, size(c) / 3);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            int width = gap(c) / 2 + size(c) + gap(c);
            boolean leftToRight = c.getComponentOrientation().isLeftToRight();
            insets.top = 0;
            insets.bottom = 0;
            insets.left = leftToRight ? width : 0;
            insets.right = leftToRight ? 0 : width;
            return insets;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            int size = size(c);
            int pad = gap(c) / 2;
            int left = c.getComponentOrientation().isLeftToRight() ? x + pad
                    : x + width - pad - size;
            int top = y + (height - size) / 2;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = UIManager.getColor("TextField.inactiveForeground"); //$NON-NLS-1$
                g2.setColor(color != null ? color : c.getForeground());
                float stroke = Math.max(1.2f, size / 8f);
                g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                int lens = Math.round(size * 0.65f);
                g2.drawOval(left, top, lens, lens);
                int handle = left + lens - Math.round(lens * 0.15f);
                g2.drawLine(handle, top + lens - Math.round(lens * 0.15f), left + size - 1,
                        top + size - 1);
            }
            finally {
                g2.dispose();
            }
        }
    }

    /**
     * Shows the items of another model that match a filter, and passes the selection through to
     * it, so the selection does not change when the filter does.
     */
    static class FilteredModel extends AbstractListModel<Object>
            implements ComboBoxModel<Object>, ListDataListener {
        private final ComboBoxModel<Object> source;
        private final Function<Object, String> toText;
        private final List<Object> visible = new ArrayList<>();
        private String filter = ""; //$NON-NLS-1$

        FilteredModel(ComboBoxModel<Object> source, Function<Object, String> toText) {
            this.source = source;
            this.toText = toText;
            source.addListDataListener(this);
            refilter();
        }

        void setFilter(String filter) {
            String normalized = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
            if (!normalized.equals(this.filter)) {
                this.filter = normalized;
                refilter();
            }
        }

        String getFilter() {
            return filter;
        }

        private void refilter() {
            visible.clear();
            for (int i = 0; i < source.getSize(); i++) {
                Object item = source.getElementAt(i);
                String text = toText.apply(item);
                if (filter.isEmpty()
                        || (text != null && text.toLowerCase(Locale.ROOT).contains(filter))) {
                    visible.add(item);
                }
            }
            fireContentsChanged(this, 0, Integer.MAX_VALUE);
        }

        /** The item whose text is exactly the given text (ignoring case), from all items. */
        Object findExact(String text) {
            String wanted = text.trim();
            for (int i = 0; i < source.getSize(); i++) {
                Object item = source.getElementAt(i);
                String itemText = toText.apply(item);
                if (itemText != null && itemText.equalsIgnoreCase(wanted)) {
                    return item;
                }
            }
            return null;
        }

        @Override
        public int getSize() {
            return visible.size();
        }

        @Override
        public Object getElementAt(int index) {
            return visible.get(index);
        }

        @Override
        public void setSelectedItem(Object item) {
            source.setSelectedItem(item);
        }

        @Override
        public Object getSelectedItem() {
            return source.getSelectedItem();
        }

        @Override
        public void contentsChanged(ListDataEvent e) {
            if (e.getIndex0() == -1 && e.getIndex1() == -1) {
                // Selection changed in the source.
                fireContentsChanged(this, -1, -1);
            }
            else {
                refilter();
            }
        }

        @Override
        public void intervalAdded(ListDataEvent e) {
            refilter();
        }

        @Override
        public void intervalRemoved(ListDataEvent e) {
            refilter();
        }
    }

    /**
     * Wraps the look and feel's editor, so the field looks the same as any other combo box. The
     * text is the search; the item it returns is always a real item.
     */
    static class SearchEditor implements ComboBoxEditor {
        private final JComboBox<?> comboBox;
        private final FilteredModel model;
        private final ComboBoxEditor delegate;
        private final Function<Object, String> toText;
        private final JTextComponent textComponent;
        /** True while the text is set from the selection rather than typed. */
        private boolean settingText;
        private boolean filterPending;

        SearchEditor(JComboBox<?> comboBox, FilteredModel model, ComboBoxEditor delegate,
                Function<Object, String> toText) {
            this.comboBox = comboBox;
            this.model = model;
            this.delegate = delegate;
            this.toText = toText;
            this.textComponent = (JTextComponent) delegate.getEditorComponent();
            textComponent.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    typed();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    typed();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                }
            });
            textComponent.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    textComponent.selectAll();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    if (!e.isTemporary()) {
                        revert();
                    }
                }
            });
            comboBox.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    // Show everything again next time, unless the user is still typing.
                    if (!textComponent.hasFocus()) {
                        SwingUtilities.invokeLater(() -> clearFilter());
                    }
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                }
            });
        }

        private void typed() {
            if (settingText || filterPending) {
                return;
            }
            // The look and feel updates the editor when the model changes, which is not allowed
            // while the document is notifying, so filter afterwards.
            filterPending = true;
            SwingUtilities.invokeLater(this::applyFilter);
        }

        /** Narrows the list to the typed text and shows it. */
        void applyFilter() {
            filterPending = false;
            String text = textComponent.getText();
            boolean wasShowing = comboBox.isPopupVisible();
            settingText = true;
            try {
                model.setFilter(text);
            }
            finally {
                settingText = false;
            }
            if (comboBox.isShowing() && textComponent.hasFocus()) {
                if (wasShowing) {
                    // Resize the popup to the new number of items.
                    comboBox.hidePopup();
                }
                if (model.getSize() > 0) {
                    comboBox.showPopup();
                }
            }
        }

        /** Shows all items again, and the selected item's text. */
        void clearFilter() {
            settingText = true;
            try {
                model.setFilter(""); //$NON-NLS-1$
            }
            finally {
                settingText = false;
            }
        }

        /**
         * When leaving the field: selects the item the typed text names exactly, as Enter would,
         * otherwise puts back the selected item's text.
         */
        void revert() {
            Object selected = model.getSelectedItem();
            Object exact = model.findExact(textComponent.getText());
            if (exact != null && exact != selected) {
                comboBox.setSelectedItem(exact);
            }
            else if (exact != selected) {
                setItem(selected);
            }
            clearFilter();
        }

        @Override
        public Component getEditorComponent() {
            return delegate.getEditorComponent();
        }

        @Override
        public void setItem(Object item) {
            if (settingText) {
                // The look and feel refreshing the editor while we filter: keep the typed text.
                return;
            }
            settingText = true;
            try {
                String text = item == null ? "" : toText.apply(item); //$NON-NLS-1$
                if (!textComponent.getText().equals(text)) {
                    textComponent.setText(text == null ? "" : text); //$NON-NLS-1$
                }
            }
            finally {
                settingText = false;
            }
        }

        /**
         * The item to select for the typed text: the item with exactly that text, otherwise the
         * only item left in the list, otherwise the current selection (so the selection is never
         * the typed text itself).
         */
        @Override
        public Object getItem() {
            String text = textComponent.getText();
            Object exact = model.findExact(text);
            if (exact != null) {
                return exact;
            }
            if (!model.getFilter().isEmpty() && model.getSize() == 1) {
                return model.getElementAt(0);
            }
            return model.getSelectedItem();
        }

        @Override
        public void selectAll() {
            delegate.selectAll();
        }

        @Override
        public void addActionListener(ActionListener l) {
            delegate.addActionListener(l);
        }

        @Override
        public void removeActionListener(ActionListener l) {
            delegate.removeActionListener(l);
        }
    }
}
