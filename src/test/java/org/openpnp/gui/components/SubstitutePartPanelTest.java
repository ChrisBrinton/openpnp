package org.openpnp.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/**
 * Drives the Substitute Part panel headlessly: search, multi-select, the replacement box and the
 * Replace button.
 */
public class SubstitutePartPanelTest {
    private static Part crcw100k;
    private static Part crcw49k9;
    private static Part r100k;

    private Board board;
    private SubstitutePartPanel panel;
    private int refreshes;

    @BeforeAll
    static void setUpConfiguration() {
        System.setProperty("java.awt.headless", "true");
        Configuration.initialize();
        crcw100k = addPart("CRCW0402100KF");
        crcw49k9 = addPart("CRCW040249K9FKEDC");
        r100k = addPart("R0402-100K-1%");
        addPart("R0402-10K-1%");
    }

    private static Part addPart(String id) {
        Part part = new Part(id);
        Configuration.get().addPart(part);
        return part;
    }

    @BeforeEach
    void createPanel() throws Exception {
        onEdt(() -> {
            board = new Board();
            board.addPlacement(placement("R1", crcw100k, "RES Thick Film, 100kO, 1% (0402)"));
            board.addPlacement(placement("R2", crcw100k, "RES Thick Film, 100kO, 1% (0402)"));
            board.addPlacement(placement("R27", crcw49k9, "SMD Chip Resistor, 49.9k"));
            refreshes = 0;
            panel = new SubstitutePartPanel(board.getPlacements(), () -> refreshes++);
        });
    }

    private static Placement placement(String id, Part part, String comments) {
        Placement placement = new Placement(id);
        placement.setPart(part);
        placement.setComments(comments);
        return placement;
    }

    @Test
    void listsEachPartAndCommentsWithItsCount() throws Exception {
        onEdt(() -> {
            JTable table = panel.getTable();
            assertEquals(2, table.getRowCount());
            assertEquals("CRCW0402100KF", table.getValueAt(0, 0));
            assertEquals(2, table.getValueAt(0, 2));
            assertEquals("CRCW040249K9FKEDC", table.getValueAt(1, 0));
        });
    }

    @Test
    void searchMatchesCommentsAsPlainText() throws Exception {
        onEdt(() -> {
            // Parentheses and percent signs would break a regular expression.
            panel.getSearchField().setText("1% (0402");
            assertEquals(1, panel.getTable().getRowCount());
            assertEquals("CRCW0402100KF", panel.getTable().getValueAt(0, 0));

            panel.getSearchField().setText("49.9k");
            assertEquals(1, panel.getTable().getRowCount());
            assertEquals("CRCW040249K9FKEDC", panel.getTable().getValueAt(0, 0));
        });
    }

    @Test
    void searchMatchesPartIds() throws Exception {
        onEdt(() -> {
            panel.getSearchField().setText("crcw");
            assertEquals(2, panel.getTable().getRowCount());
            panel.getSearchField().setText("100KF");
            assertEquals(1, panel.getTable().getRowCount());
        });
    }

    @Test
    void replaceButtonNeedsASelectionAndAKnownPart() throws Exception {
        onEdt(() -> {
            assertFalse(panel.getReplaceButton().isEnabled());

            panel.getTable().selectAll();
            assertFalse(panel.getReplaceButton().isEnabled(), "no replacement yet");

            editor().setText("R0402-100K");
            assertFalse(panel.getReplaceButton().isEnabled(), "not a complete part id");

            editor().setText("r0402-100k-1%");
            assertTrue(panel.getReplaceButton().isEnabled(), "part ids are case insensitive");
            assertTrue(panel.getReplaceButton().getText().contains("3"),
                    panel.getReplaceButton().getText());
        });
    }

    @Test
    void pickingFromTheListEnablesReplace() throws Exception {
        onEdt(() -> {
            panel.getTable().selectAll();
            panel.getReplacementCombo().setSelectedItem(r100k);
            assertEquals("R0402-100K-1%", editor().getText());
            assertTrue(panel.getReplaceButton().isEnabled());
        });
    }

    @Test
    void startsWithNoReplacement() throws Exception {
        onEdt(() -> {
            assertEquals(null, panel.getReplacementCombo().getSelectedItem());
            assertEquals("", editor().getText());
        });
    }

    @Test
    void typingSettlesWithoutQueueingMoreWork() throws Exception {
        // The first version of this panel re-filtered in a loop that never ended.
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
        onEdt(() -> {
            for (char c : "100K".toCharArray()) {
                editor().replaceSelection(String.valueOf(c));
            }
        });
        int settled = edits[0];
        for (int i = 0; i < 10; i++) {
            onEdt(() -> {
            });
        }
        assertEquals(settled, edits[0]);
        onEdt(() -> assertEquals("100K", editor().getText()));
    }

    @Test
    void replacementListNarrowsAsYouType() throws Exception {
        onEdt(() -> editor().setText("100K"));
        // The list is narrowed on a later event.
        onEdt(() -> {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < panel.getReplacementCombo().getItemCount(); i++) {
                items.add(panel.getReplacementCombo().getItemAt(i).getId());
            }
            assertTrue(items.contains("CRCW0402100KF"), items.toString());
            assertTrue(items.contains("R0402-100K-1%"), items.toString());
            assertFalse(items.contains("R0402-10K-1%"), items.toString());
            assertEquals("100K", editor().getText(), "what was typed is kept");
        });
    }

    @Test
    void replacesTheSelectedRowsAndRefreshes() throws Exception {
        onEdt(() -> {
            panel.getSearchField().setText("100kO");
            panel.getTable().selectAll();
            editor().setText("R0402-100K-1%");

            panel.getReplaceButton().doClick(0);

            assertSame(r100k, board.getPlacements().get(0).getPart());
            assertSame(r100k, board.getPlacements().get(1).getPart());
            assertSame(crcw49k9, board.getPlacements().get(2).getPart());
            assertTrue(panel.getStatusLabel().getText().contains("2"),
                    panel.getStatusLabel().getText());
            assertTrue(panel.getStatusLabel().getText().contains("R0402-100K-1%"),
                    panel.getStatusLabel().getText());
            assertEquals(1, refreshes);
            // The list now shows the new part for those placements.
            panel.getSearchField().setText("");
            assertEquals("R0402-100K-1%", panel.getTable().getValueAt(1, 0));
            assertFalse(panel.getReplaceButton().isEnabled(), "selection cleared");
        });
    }

    private JTextComponent editor() {
        return (JTextComponent) panel.getReplacementCombo().getEditor().getEditorComponent();
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
