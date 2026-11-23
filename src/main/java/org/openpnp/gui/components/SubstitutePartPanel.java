package org.openpnp.gui.components;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.JTextComponent;

import org.openpnp.Translations;
import org.openpnp.gui.support.IdentifiableListCellRenderer;
import org.openpnp.gui.support.PartSubstitution;
import org.openpnp.gui.support.PartSubstitution.Usage;
import org.openpnp.gui.support.PartsComboBoxModel;
import org.openpnp.gui.support.SearchableComboBox;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/**
 * Lists the parts used by a set of placements, grouped by part and comments, and replaces the
 * selected ones with another part.
 */
@SuppressWarnings("serial")
public class SubstitutePartPanel extends JPanel {
    private final Collection<Placement> placements;
    private final Runnable onSubstituted;
    private final UsageTableModel tableModel = new UsageTableModel();
    private final TableRowSorter<UsageTableModel> sorter = new TableRowSorter<>(tableModel);
    private final JTextField searchField = new JTextField(20);
    private final JTable table = new JTable(tableModel);
    private final PartsComboBoxModel partsModel = new PartsComboBoxModel();
    @SuppressWarnings("unchecked")
    private final JComboBox<Part> replacementCombo = new JComboBox<>(partsModel);
    private final JButton replaceButton = new JButton();
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$

    /**
     * @param placements the placements to work on. On the Job tab these are the instances shown
     *        in the table; their definitions are changed.
     * @param onSubstituted called after placements have been changed, to refresh other views.
     */
    public SubstitutePartPanel(Collection<Placement> placements, Runnable onSubstituted) {
        this.placements = placements;
        this.onSubstituted = onSubstituted;
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        searchPanel.add(new JLabel(Translations.getString("SubstitutePartPanel.Search"))); //$NON-NLS-1$
        searchPanel.add(searchField);
        searchField.setToolTipText(
                Translations.getString("SubstitutePartPanel.Search.ToolTip")); //$NON-NLS-1$
        searchField.getDocument().addDocumentListener(onChange(this::search));
        add(searchPanel, BorderLayout.NORTH);

        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> updateReplaceButton());
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(380);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(700, 300));
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.PAGE_AXIS));
        JPanel replacePanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        replacePanel.add(new JLabel(Translations.getString("SubstitutePartPanel.ReplaceWith"))); //$NON-NLS-1$
        replacementCombo.setRenderer(new IdentifiableListCellRenderer<Part>());
        replacementCombo.setPrototypeDisplayValue(
                new Part("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXX")); //$NON-NLS-1$
        replacementCombo.setToolTipText(
                Translations.getString("SubstitutePartPanel.ReplaceWith.ToolTip")); //$NON-NLS-1$
        SearchableComboBox.installForParts(replacementCombo);
        // No replacement until the user picks one.
        replacementCombo.setSelectedItem(null);
        // Only reads the text, so it cannot set off further edits.
        replacementEditor().getDocument().addDocumentListener(onChange(this::updateReplaceButton));
        replacePanel.add(replacementCombo);
        replaceButton.addActionListener(e -> substitute());
        replacePanel.add(replaceButton);
        bottom.add(replacePanel);
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 4));
        statusPanel.add(statusLabel);
        bottom.add(statusPanel);
        add(bottom, BorderLayout.SOUTH);

        tableModel.setUsages(PartSubstitution.summarize(placements));
        updateReplaceButton();
    }

    JTextField getSearchField() {
        return searchField;
    }

    JTable getTable() {
        return table;
    }

    /** Stops following changes to the parts list; call when the panel is closed. */
    public void dispose() {
        partsModel.dispose();
    }

    JComboBox<Part> getReplacementCombo() {
        return replacementCombo;
    }

    JButton getReplaceButton() {
        return replaceButton;
    }

    JLabel getStatusLabel() {
        return statusLabel;
    }

    private JTextComponent replacementEditor() {
        return (JTextComponent) replacementCombo.getEditor().getEditorComponent();
    }

    private void search() {
        String text = searchField.getText().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
        }
        else {
            // Plain text, not a regular expression: comments are full of characters such as
            // parentheses and percent signs.
            sorter.setRowFilter(new RowFilter<UsageTableModel, Integer>() {
                @Override
                public boolean include(Entry<? extends UsageTableModel, ? extends Integer> entry) {
                    Usage usage = tableModel.getUsage(entry.getIdentifier());
                    return usage.getPartId().toLowerCase(Locale.ROOT).contains(text)
                            || usage.getComments().toLowerCase(Locale.ROOT).contains(text);
                }
            });
        }
        updateReplaceButton();
    }

    /**
     * The part named in the Replace with box, or null if there is no such part. Read from the text
     * so the Replace button follows what is typed, before it is picked from the list.
     */
    Part getReplacement() {
        String id = replacementEditor().getText().trim();
        return id.isEmpty() ? null : Configuration.get().getPart(id);
    }

    List<Usage> getSelectedUsages() {
        List<Usage> selected = new ArrayList<>();
        for (int viewRow : table.getSelectedRows()) {
            selected.add(tableModel.getUsage(table.convertRowIndexToModel(viewRow)));
        }
        return selected;
    }

    private int countAffected(List<Usage> usages, Part replacement) {
        int count = 0;
        for (Usage usage : usages) {
            if (usage.getPart() != replacement) {
                count += usage.getCount();
            }
        }
        return count;
    }

    private void updateReplaceButton() {
        List<Usage> selected = getSelectedUsages();
        Part replacement = getReplacement();
        int count = countAffected(selected, replacement);
        replaceButton.setText(Translations.format("SubstitutePartPanel.Replace", count)); //$NON-NLS-1$
        replaceButton.setEnabled(replacement != null && count > 0);
    }

    void substitute() {
        List<Usage> selected = getSelectedUsages();
        Part replacement = getReplacement();
        if (replacement == null || selected.isEmpty()) {
            return;
        }
        int changed = PartSubstitution.substitute(placements, selected, replacement);
        tableModel.setUsages(PartSubstitution.summarize(placements));
        table.clearSelection();
        statusLabel.setText(Translations.format("SubstitutePartPanel.Replaced", changed, //$NON-NLS-1$
                replacement.getId()));
        updateReplaceButton();
        if (changed > 0 && onSubstituted != null) {
            onSubstituted.run();
        }
    }

    private static DocumentListener onChange(Runnable runnable) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                runnable.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                runnable.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                runnable.run();
            }
        };
    }

    private static class UsageTableModel extends AbstractTableModel {
        private final String[] columnNames = {
                Translations.getString("SubstitutePartPanel.Column.Part"), //$NON-NLS-1$
                Translations.getString("SubstitutePartPanel.Column.Comments"), //$NON-NLS-1$
                Translations.getString("SubstitutePartPanel.Column.Count"), //$NON-NLS-1$
        };
        private List<Usage> usages = new ArrayList<>();

        void setUsages(List<Usage> usages) {
            this.usages = usages;
            fireTableDataChanged();
        }

        Usage getUsage(int row) {
            return usages.get(row);
        }

        @Override
        public int getRowCount() {
            return usages.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 2 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Usage usage = usages.get(row);
            switch (column) {
                case 0:
                    return usage.getPartId();
                case 1:
                    return usage.getComments();
                default:
                    return usage.getCount();
            }
        }
    }
}
