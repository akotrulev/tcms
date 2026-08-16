package com.tcms.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a run was <strong>asked</strong> for — the contract's {@code RunScopeRequest} (B7).
 *
 * <h2>Why this is on the wire at all</h2>
 *
 * <p>A run's contents are its executions, materialised once and pinned to a case version. That is
 * the law and this record does not touch it. What the law left unanswerable is <em>why</em> a case
 * is on the checklist: named directly, filed in a suite that was named, or carrying a tag that was.
 * {@code test_run} kept the plan id and nothing else, so the question had no answer at all, and a
 * clone could only re-run the same case ids because the arms were gone.
 *
 * <h2>Null and empty are different answers</h2>
 *
 * <p>The whole record is <strong>null</strong> when nothing was recorded — every run created before
 * {@code V61}, and every run opened by ingestion, which materialises from a report rather than from
 * a request. It is present with empty arms only if somebody genuinely asked for nothing, which the
 * create refuses, so in practice a present record has at least one arm. A client must render the
 * absence as <em>not recorded</em>: showing it as "no scope" would state that a run covering two
 * hundred cases was asked for nothing.
 *
 * <h2>It is the request, not the result</h2>
 *
 * <p>A suite named here may since have been deleted and a tag may now match nothing. Nothing
 * re-resolves it and nothing repairs it — the value of the record is that it says what was asked on
 * the day it was asked, which is exactly what makes the difference from the checklist visible.
 */
public record RunScopeRequestView(
        UUID planId,
        List<UUID> caseIds,
        List<UUID> suiteIds,
        List<String> tags) {

    /**
     * Parses the stored document, or null when there is none.
     *
     * <p>Tolerant of a missing key and of a null — the column is written by one service and read
     * by this one, and a run stored before a later arm existed must still render rather than 500.
     * Anything it cannot read at all is treated as not recorded, for the same reason: a provenance
     * field that can break a run's detail page is worse than a provenance field that is absent.
     */
    static RunScopeRequestView parse(ObjectMapper json, String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(stored);
            return new RunScopeRequestView(
                    node.hasNonNull("planId") ? UUID.fromString(node.get("planId").asText()) : null,
                    ids(node.get("caseIds")),
                    ids(node.get("suiteIds")),
                    texts(node.get("tags")));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            return null;
        }
    }

    private static List<UUID> ids(JsonNode array) {
        List<UUID> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> values.add(UUID.fromString(item.asText())));
        }
        return List.copyOf(values);
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> values.add(item.asText()));
        }
        return List.copyOf(values);
    }
}
