package com.tcms.tracker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;

/**
 * The codec for {@code issue.acceptance_criteria}, and the only place that column's shape is known.
 *
 * <p>Its own class rather than a pair of methods on {@link IssueService} because {@link IssueEdit}
 * is pure and still has to produce the stored form — a codec with no Spring in it can be called from
 * both, and from a unit test with no container.
 *
 * <p>Its {@link ObjectMapper} is private and unconfigured on purpose: this reads and writes one
 * closed shape that this module defines, so nothing about the application's mapper — its modules,
 * its naming strategy, its unknown-property handling — is allowed to change what a stored row means.
 */
final class AcceptanceCriteria {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final TypeReference<List<AcceptanceCriterion>> SHAPE = new TypeReference<>() {
    };

    private AcceptanceCriteria() {
    }

    /**
     * The stored JSON as a list.
     *
     * @throws IllegalStateException naming the column, because the value is written only by
     *     {@link #toJson} and the column is {@code jsonb} — an unreadable one means somebody edited
     *     the row by hand, and answering an empty list would silently drop their criteria
     */
    static List<AcceptanceCriterion> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(stored, SHAPE);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "issue.acceptance_criteria does not hold this module's shape", unreadable);
        }
    }

    /** The list as the stored JSON. Never null: an issue with no criteria stores {@code []}. */
    static String toJson(List<AcceptanceCriterion> criteria) {
        try {
            return JSON.writeValueAsString(criteria == null ? List.of() : criteria);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unwritable) {
            throw new IllegalStateException("acceptance criteria could not be written", unwritable);
        }
    }

    /** The create form's plain strings, numbered and given ids. */
    static List<AcceptanceCriterion> fromText(List<String> texts) {
        if (texts == null) {
            return List.of();
        }
        List<AcceptanceCriterion> criteria = new java.util.ArrayList<>();
        for (String text : texts) {
            if (text != null && !text.isBlank()) {
                criteria.add(new AcceptanceCriterion(
                        UUID.randomUUID(), text.strip(), false, criteria.size()));
            }
        }
        return List.copyOf(criteria);
    }
}
