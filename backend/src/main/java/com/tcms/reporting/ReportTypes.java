package com.tcms.reporting;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The one refusal for "this server cannot produce that report", shared by the two endpoints that
 * take a report type.
 *
 * <h2>Why it is not two copies of four lines</h2>
 *
 * <p>{@link ReportJobService} has refused an unknown type since §87 and {@link
 * ReportDefinitionService} has to refuse the same thing for a different reason — a saved report of a
 * type nothing can compute is a button that fails every time it is pressed, and it fails at the
 * moment it is <em>used</em> rather than at the moment it is wrong, which is the worst available
 * time to find out. Two copies of the check would drift in the way §58's E1 describes: the sentence
 * would be edited on one endpoint when a report is added, and the other would go on naming a shorter
 * catalogue.
 *
 * <p>The list of what this server can produce is asked of {@link ReportComputations} rather than
 * written beside the check, so a report added in R6 becomes both requestable and saveable by
 * existing — the argument that registry's own header makes.
 */
@Component
class ReportTypes {

    private final ReportComputations computations;

    ReportTypes(ReportComputations computations) {
        this.computations = computations;
    }

    /**
     * Why this type cannot be used, or empty if it can.
     *
     * <p>Absent and unknown are two sentences and not one, because they name different fixes: one
     * caller forgot a field, the other sent a value this deployment does not have. Both end with
     * what it <em>does</em> have, which is the only actionable half.
     */
    Optional<String> refusal(String type) {
        if (type == null || type.isBlank()) {
            return Optional.of("Say which report this is. This server can produce: "
                    + catalogue() + ".");
        }
        if (computations.find(type).isEmpty()) {
            return Optional.of("This server has no report called '" + type
                    + "'. It can produce: " + catalogue() + ".");
        }
        return Optional.empty();
    }

    private String catalogue() {
        return String.join(", ", computations.supported());
    }
}
