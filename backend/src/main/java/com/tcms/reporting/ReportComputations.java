package com.tcms.reporting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Which report types this deployment can compute — the same derived-catalogue argument
 * {@link ReportRenderers} makes for formats, and for the same defect.
 *
 * <p>§6e's catalogue names ten reports and R3 ships one, so nine of the ten strings a client could
 * send name nothing. That has to be a <strong>400 at request time</strong> — a job accepted for a
 * type nothing can compute is a row that goes {@code FAILED} an hour later with "unknown type" in a
 * column the requester has to poll to see. {@link #find} is what the validation asks, so a report
 * added in R6 becomes requestable by existing, not by an edit to a list somewhere else.
 *
 * <p>Duplicate types are refused at startup for {@link ReportRenderers}' reason: two beans claiming
 * one type is a silent choice of which report a client gets.
 */
@Component
class ReportComputations {

    private final Map<String, ReportComputation> byType;

    ReportComputations(List<ReportComputation> computations) {
        Map<String, ReportComputation> registry = new LinkedHashMap<>();
        for (ReportComputation computation : computations) {
            ReportComputation existing = registry.put(computation.type(), computation);
            if (existing != null) {
                throw new IllegalStateException("Two report computations claim '"
                        + computation.type() + "': " + existing.getClass().getName() + " and "
                        + computation.getClass().getName());
            }
        }
        this.byType = Map.copyOf(registry);
    }

    Optional<ReportComputation> find(String type) {
        return type == null ? Optional.empty() : Optional.ofNullable(byType.get(type));
    }

    /**
     * The renderer for a type already known to exist.
     *
     * @throws IllegalStateException when it does not — reached only if a job was accepted for a type
     *     {@link #find} could not place, which is a bug in the validation rather than something a
     *     caller can cause
     */
    ReportComputation require(String type) {
        return find(type).orElseThrow(() -> new IllegalStateException(
                "No computation for report type '" + type + "', yet a job was accepted for it"));
    }

    /** Every type this deployment can compute, sorted, for the message that says what it can. */
    List<String> supported() {
        return byType.keySet().stream().sorted().toList();
    }
}
