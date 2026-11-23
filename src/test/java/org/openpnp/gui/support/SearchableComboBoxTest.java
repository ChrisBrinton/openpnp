package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.jdesktop.beansbinding.AutoBinding.UpdateStrategy;
import org.jdesktop.beansbinding.BeanProperty;
import org.jdesktop.beansbinding.Bindings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;

/**
 * A part combo box set up the way the feeder panels do it, made searchable.
 */
public class SearchableComboBoxTest {
    private static Part r100k;
    private static Part r10k;
    private static Part c100n;

    private JComboBox<Part> comboBox;
    private Holder holder;

    /** Stands in for a feeder: a bean with a bound part property. */
    public static class Holder extends AbstractModelObject {
        private Part part;

        public Part getPart() {
            return part;
        }

        public void setPart(Part part) {
            Part old = this.part;
            this.part = part;
            firePropertyChange("part", old, part);
        }
    }

    @BeforeAll
    static void setUpConfiguration() {
        System.setProperty("java.awt.headless", "true");
        Configuration.initialize();
        r100k = addPart("R0402-100K-1%");
        r10k = addPart("R0402-10K-1%");
        c100n = addPart("C0402-100nF-16V");
    }

    private static Part addPart(String id) {
        Part part = new Part(id);
        Configuration.get().addPart(part);
        return part;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void createComboBox() throws Exception {
        onEdt(() -> {
            comboBox = new JComboBox<>();
            comboBox.setModel(new PartsComboBoxModel());
            comboBox.setRenderer(new IdentifiableListCellRenderer<Part>());
            SearchableComboBox.installForParts(comboBox);
            holder = new Holder();
            holder.setPart(c100n);
            Bindings.createAutoBinding(UpdateStrategy.READ_WRITE, holder,
                    BeanProperty.create("part"), comboBox, BeanProperty.create("selectedItem"))
                    .bind();
        });
    }

    @Test
    void showsThePartIdNotItsToString() throws Exception {
        onEdt(() -> assertEquals("C0402-100nF-16V", editor().getText()));
    }

    @Test
    void typingNarrowsTheListWithoutChangingTheSelection() throws Exception {
        type("100");
        onEdt(() -> {
            List<String> ids = itemIds();
            assertTrue(ids.contains("R0402-100K-1%"), ids.toString());
            assertTrue(ids.contains("C0402-100nF-16V"), ids.toString());
            assertTrue(!ids.contains("R0402-10K-1%"), ids.toString());
            assertSame(c100n, comboBox.getSelectedItem());
            assertSame(c100n, holder.getPart());
            assertEquals("100", editor().getText(), "the typed text is kept");
        });
    }

    @Test
    void searchIgnoresCase() throws Exception {
        type("r0402-1");
        onEdt(() -> assertEquals(2, comboBox.getItemCount()));
    }

    @Test
    void enterSelectsTheOnlyMatch() throws Exception {
        type("100k");
        onEdt(() -> {
            editor().postActionEvent();
            assertSame(r100k, comboBox.getSelectedItem());
            assertSame(r100k, holder.getPart(), "the binding sees a Part");
        });
    }

    @Test
    void enterSelectsAnExactIdIgnoringCase() throws Exception {
        type("r0402-10k-1%");
        onEdt(() -> {
            editor().postActionEvent();
            assertSame(r10k, holder.getPart());
        });
    }

    @Test
    void textThatMatchesNothingNeverBecomesTheSelection() throws Exception {
        type("no such part");
        onEdt(() -> {
            editor().postActionEvent();
            assertSame(c100n, comboBox.getSelectedItem());
            assertSame(c100n, holder.getPart());
        });
    }

    @Test
    void ambiguousTextDoesNotChangeTheSelection() throws Exception {
        type("R0402");
        onEdt(() -> {
            editor().postActionEvent();
            assertSame(c100n, holder.getPart());
        });
    }

    @Test
    void leavingTheFieldPutsBackTheSelectedPart() throws Exception {
        type("R04");
        onEdt(() -> {
            editorOf().revert();
            assertEquals("C0402-100nF-16V", editor().getText());
            assertEquals(3, comboBox.getItemCount(), "all parts listed again");
        });
    }

    @Test
    void leavingTheFieldWithACompleteIdSelectsThatPart() throws Exception {
        type("r0402-10k-1%");
        onEdt(() -> {
            editorOf().revert();
            assertSame(r10k, holder.getPart());
            assertEquals("R0402-10K-1%", editor().getText());
        });
    }

    @Test
    void pickingFromTheNarrowedListSelectsThatPart() throws Exception {
        // The drop down list selects by index within the narrowed list.
        type("10K");
        onEdt(() -> {
            assertEquals(1, comboBox.getItemCount());
            comboBox.setSelectedIndex(0);
            assertSame(r10k, holder.getPart());
            assertEquals("R0402-10K-1%", editor().getText());
        });
    }

    @Test
    void selectionFromTheModelUpdatesTheText() throws Exception {
        onEdt(() -> {
            holder.setPart(r100k);
            assertSame(r100k, comboBox.getSelectedItem());
            assertEquals("R0402-100K-1%", editor().getText());
        });
    }

    @Test
    void partsAddedLaterAppear() throws Exception {
        onEdt(() -> {
            Part added = addPart("R0402-4K7-1%");
            try {
                assertTrue(itemIds().contains("R0402-4K7-1%"), itemIds().toString());
            }
            finally {
                Configuration.get().removePart(added);
            }
        });
    }

    @Test
    void typingSettlesWithoutQueueingMoreWork() throws Exception {
        // Filtering must not change the text in a way that queues another round of filtering.
        int[] edits = new int[1];
        onEdt(() -> editor().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                edits[0]++;
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                edits[0]++;
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        }));
        type("R04");
        int settled = edits[0];
        for (int i = 0; i < 10; i++) {
            onEdt(() -> {
            });
        }
        assertEquals(settled, edits[0]);
        onEdt(() -> assertEquals("R04", editor().getText()));
    }

    @Test
    void showsItIsSearchable() throws Exception {
        onEdt(() -> {
            assertTrue(hasSearchIcon(), String.valueOf(editor().getBorder()));
            assertTrue(editor().getInsets().left > 0, "room for the icon");
            assertTrue(comboBox.getToolTipText() != null);
            assertEquals(comboBox.getToolTipText(), editor().getToolTipText());
        });
    }

    @Test
    void keepsTheIconWhenTheLookAndFeelChanges() throws Exception {
        onEdt(() -> SwingUtilities.updateComponentTreeUI(comboBox));
        // The icon is added again after the change.
        onEdt(() -> {
        });
        onEdt(() -> assertTrue(hasSearchIcon(), String.valueOf(editor().getBorder())));
    }

    @Test
    void keepsAnExistingToolTip() throws Exception {
        onEdt(() -> {
            JComboBox<Part> other = new JComboBox<>(new PartsComboBoxModel());
            other.setToolTipText("The part to feed");
            SearchableComboBox.installForParts(other);
            assertEquals("The part to feed", other.getToolTipText());
            assertEquals("The part to feed",
                    ((JComponent) other.getEditor().getEditorComponent()).getToolTipText());
        });
    }

    private boolean hasSearchIcon() {
        return editor().getBorder() instanceof SearchableComboBox.SearchIconBorder
                || (editor().getBorder() instanceof CompoundBorder
                        && ((CompoundBorder) editor().getBorder())
                                .getInsideBorder() instanceof SearchableComboBox.SearchIconBorder);
    }

    private JTextField editor() {
        return (JTextField) comboBox.getEditor().getEditorComponent();
    }

    private SearchableComboBox.SearchEditor editorOf() {
        return (SearchableComboBox.SearchEditor) comboBox.getEditor();
    }

    private List<String> itemIds() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < comboBox.getItemCount(); i++) {
            ids.add(comboBox.getItemAt(i).getId());
        }
        return ids;
    }

    /** Types like a user: one character at a time, then lets the deferred filtering run. */
    private void type(String text) throws Exception {
        onEdt(() -> {
            editor().selectAll();
            editor().replaceSelection("");
            for (char c : text.toCharArray()) {
                editor().replaceSelection(String.valueOf(c));
            }
        });
        // Run the filtering queued with invokeLater.
        onEdt(() -> {
        });
    }

    private interface EdtTask {
        void run() throws Exception;
    }

    private static void onEdt(EdtTask task) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                task.run();
            }
            catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] instanceof Error) {
            throw (Error) failure[0];
        }
        if (failure[0] != null) {
            throw (Exception) failure[0];
        }
    }
}
