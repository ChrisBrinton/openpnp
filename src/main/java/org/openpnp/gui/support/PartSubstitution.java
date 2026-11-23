package org.openpnp.gui.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/**
 * Replaces the part used by a group of placements with another part, e.g. to switch every
 * placement imported as {@code CRCW0402100KF} to the {@code R0402-100K-1%} part that is set up
 * in a feeder.
 * <p>
 * Placements are grouped by part and comments, because the comments imported from the E-CAD
 * often describe the component better than the part id does.
 */
public final class PartSubstitution {
    private PartSubstitution() {
    }

    /**
     * A part, as used by one or more placements that have the same comments.
     */
    public static final class Usage {
        private final Part part;
        private final String comments;
        private int count;

        Usage(Part part, String comments) {
            this.part = part;
            this.comments = comments;
        }

        /** The part, or null for placements that have no part assigned. */
        public Part getPart() {
            return part;
        }

        public String getPartId() {
            return part == null ? "" : part.getId(); //$NON-NLS-1$
        }

        /** The placements' comments, or an empty string. */
        public String getComments() {
            return comments;
        }

        /** The number of placements with this part and these comments. */
        public int getCount() {
            return count;
        }

        boolean matches(Placement placement) {
            return placement.getPart() == part && comments.equals(commentsOf(placement));
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Usage)) {
                return false;
            }
            Usage other = (Usage) obj;
            return part == other.part && comments.equals(other.comments);
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(part), comments);
        }
    }

    /**
     * Groups the placements by part and comments, sorted by part id and then comments.
     */
    public static List<Usage> summarize(Collection<Placement> placements) {
        Map<Usage, Usage> usages = new LinkedHashMap<>();
        for (Placement placement : placements) {
            Usage key = new Usage(placement.getPart(), commentsOf(placement));
            usages.computeIfAbsent(key, k -> k).count++;
        }
        List<Usage> result = new ArrayList<>(usages.values());
        result.sort(Comparator.comparing(Usage::getPartId, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Usage::getComments, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Sets the part of every placement that matches one of the usages to the replacement.
     * <p>
     * When a placement is an instance of a placement defined elsewhere (as in the Job tab), the
     * defining placement is changed, the same as editing the Part cell in the placements table.
     *
     * @return the number of placements changed.
     */
    public static int substitute(Collection<Placement> placements, Collection<Usage> usages,
            Part replacement) {
        int changed = 0;
        for (Placement placement : placements) {
            if (placement.getPart() == replacement) {
                continue;
            }
            for (Usage usage : usages) {
                if (usage.matches(placement)) {
                    Placement definition = placement.getDefinition();
                    if (definition == null) {
                        definition = placement;
                    }
                    definition.setPart(replacement);
                    changed++;
                    break;
                }
            }
        }
        return changed;
    }

    private static String commentsOf(Placement placement) {
        String comments = placement.getComments();
        return comments == null ? "" : comments.trim(); //$NON-NLS-1$
    }
}
