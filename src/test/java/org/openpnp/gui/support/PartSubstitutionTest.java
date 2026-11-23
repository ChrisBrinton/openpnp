package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.PartSubstitution.Usage;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

public class PartSubstitutionTest {
    private static Part crcw100k;
    private static Part generic0402;
    private static Part r100k;

    private Board board;

    @BeforeAll
    static void setUpConfiguration() {
        Configuration.initialize();
        crcw100k = new Part("CRCW0402100KF");
        generic0402 = new Part("R0402");
        r100k = new Part("R0402-100K-1%");
    }

    @BeforeEach
    void createBoard() {
        board = new Board();
        board.addPlacement(placement("R1", crcw100k, "RES 100k 1%"));
        board.addPlacement(placement("R2", crcw100k, "RES 100k 1%"));
        board.addPlacement(placement("R3", crcw100k, null));
        // One generic part used for different values: only the comments tell them apart.
        board.addPlacement(placement("R4", generic0402, "100k"));
        board.addPlacement(placement("R5", generic0402, "4.7k"));
        board.addPlacement(placement("R6", null, "100k (no part assigned)"));
        board.setDirty(false);
    }

    private static Placement placement(String id, Part part, String comments) {
        Placement placement = new Placement(id);
        placement.setPart(part);
        placement.setComments(comments);
        return placement;
    }

    private Usage usage(String partId, String comments) {
        for (Usage usage : PartSubstitution.summarize(board.getPlacements())) {
            if (usage.getPartId().equals(partId) && usage.getComments().equals(comments)) {
                return usage;
            }
        }
        throw new AssertionError("no usage " + partId + " / " + comments);
    }

    private Placement placement(String id) {
        for (Placement placement : board.getPlacements()) {
            if (placement.getId().equals(id)) {
                return placement;
            }
        }
        throw new AssertionError(id);
    }

    @Test
    void groupsByPartAndCommentsWithCounts() {
        List<Usage> usages = PartSubstitution.summarize(board.getPlacements());

        assertEquals(6, usages.stream().mapToInt(Usage::getCount).sum());
        assertEquals(5, usages.size());
        assertEquals(2, usage("CRCW0402100KF", "RES 100k 1%").getCount());
        assertEquals(1, usage("CRCW0402100KF", "").getCount());
        assertEquals(1, usage("R0402", "100k").getCount());
        assertEquals(1, usage("R0402", "4.7k").getCount());
        assertNull(usage("", "100k (no part assigned)").getPart());
    }

    @Test
    void sortsByPartIdThenComments() {
        List<Usage> usages = PartSubstitution.summarize(board.getPlacements());
        assertEquals("", usages.get(0).getPartId());
        assertEquals("CRCW0402100KF", usages.get(1).getPartId());
        assertEquals("", usages.get(1).getComments());
        assertEquals("RES 100k 1%", usages.get(2).getComments());
        assertEquals("R0402", usages.get(3).getPartId());
        assertEquals("100k", usages.get(3).getComments());
        assertEquals("4.7k", usages.get(4).getComments());
    }

    @Test
    void replacesOnlyTheSelectedPartAndComments() {
        int changed = PartSubstitution.substitute(board.getPlacements(),
                Arrays.asList(usage("CRCW0402100KF", "RES 100k 1%"), usage("R0402", "100k")),
                r100k);

        assertEquals(3, changed);
        assertSame(r100k, placement("R1").getPart());
        assertSame(r100k, placement("R2").getPart());
        assertSame(r100k, placement("R4").getPart());
        // Same part, different comments: untouched.
        assertSame(crcw100k, placement("R3").getPart());
        assertSame(generic0402, placement("R5").getPart());
        assertNull(placement("R6").getPart());
    }

    @Test
    void canAssignAPartToPlacementsThatHaveNone() {
        int changed = PartSubstitution.substitute(board.getPlacements(),
                Collections.singletonList(usage("", "100k (no part assigned)")), r100k);
        assertEquals(1, changed);
        assertSame(r100k, placement("R6").getPart());
    }

    @Test
    void marksTheBoardModifiedSoItIsSaved() {
        PartSubstitution.substitute(board.getPlacements(),
                Collections.singletonList(usage("R0402", "4.7k")), r100k);
        assertTrue(board.isDirty());
    }

    @Test
    void tellsBothPartsTheirPlacementCountChanged() {
        // The Parts tab's Placements column refreshes on these events.
        List<Part> notified = new ArrayList<>();
        PropertyChangeListener listener = e -> notified.add((Part) e.getSource());
        crcw100k.addPropertyChangeListener("placementCount", listener);
        r100k.addPropertyChangeListener("placementCount", listener);
        try {
            PartSubstitution.substitute(board.getPlacements(),
                    Collections.singletonList(usage("CRCW0402100KF", "RES 100k 1%")), r100k);
        }
        finally {
            crcw100k.removePropertyChangeListener("placementCount", listener);
            r100k.removePropertyChangeListener("placementCount", listener);
        }
        assertTrue(notified.contains(crcw100k), notified.toString());
        assertTrue(notified.contains(r100k), notified.toString());
    }

    @Test
    void changesTheDefinitionOfAJobInstancePlacement() {
        // On the Job tab the table shows copies of the board's placements; edits must go to the
        // board's own placement (its definition), which then updates the copy.
        Placement definition = placement("R1");
        Placement instance = new Placement(definition);
        instance.setDefinition(definition);
        List<Placement> instances = Collections.singletonList(instance);

        int changed = PartSubstitution.substitute(instances,
                PartSubstitution.summarize(instances), r100k);

        assertEquals(1, changed);
        assertSame(r100k, definition.getPart());
        assertSame(r100k, instance.getPart());
    }
}
