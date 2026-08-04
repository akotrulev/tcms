package com.tcms.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code parameters} JSONB a report was asked for, and the one place a bad one is refused.
 *
 * <h2>Why a type rather than a {@code JsonNode} passed around</h2>
 *
 * <p>Every report in §6e's catalogue takes different parameters — a run id here, a milestone plus a
 * date range plus an aggregation policy in R6 — so the column is deliberately untyped and the
 * checking has to live somewhere. Putting it here rather than in each report means one wording for
 * "you did not send a run id" and one for "what you sent is not a uuid", which matters because these
 * messages are what a client sees on a 400 and a product whose refusals are each phrased differently
 * reads as several products.
 *
 * <h2>{@link IllegalArgumentException}, and the layer that turns it into a 400</h2>
 *
 * <p>This module computes; it does not know about HTTP. R3 phase 5's controller is what maps these
 * to a problem response, and the message is written to be shown: it names the parameter, not the
 * JSON path, and never echoes the whole body back.
 *
 * <p>The mapper is private and read-only. It is not {@link JsonReportRenderer}'s — that one is
 * configured for the bytes of a stored artifact and this one only ever parses — and it is not the
 * web layer's, for the reason that file states at length.
 */
record ReportParameters(JsonNode node) {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * Parses the raw JSONB text stored on the job row.
     *
     * @throws IllegalArgumentException if it is not a JSON object. {@code V27} defaults the column
     *     to {@code '{}'} and its CHECK does not constrain the shape, so an array or a bare string
     *     is storable — and a report reading fields off one would find none of them and compute a
     *     report of nothing rather than refusing.
     */
    static ReportParameters parse(String json) {
        try {
            JsonNode parsed = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!parsed.isObject()) {
                throw new IllegalArgumentException(
                        "Report parameters must be a JSON object, not " + parsed.getNodeType());
            }
            return new ReportParameters(parsed);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Report parameters are not valid JSON", e);
        }
    }

    /** For tests and for a report that takes none. */
    static ReportParameters none() {
        return new ReportParameters(MAPPER.createObjectNode());
    }

    /**
     * A saved definition's parameters with a request's written over them, as the JSON text a job is
     * stored with.
     *
     * <p><strong>Shallow, key by key at the top level</strong>, and that is a decision rather than
     * the easy implementation. {@code parameters} is untyped by design — every report takes
     * different ones — so a deep merge would have no schema to decide whether a nested object is a
     * <em>set</em> of filters to combine or one value to replace, and it would guess differently per
     * report. Half-overriding a saved selection is invisible in the output: the report renders, the
     * numbers are wrong, and nothing says which half won.
     *
     * <p>The result is what the job stores and therefore what
     * {@code report_artifact.filter_snapshot} freezes, because what has to be reproducible is what
     * the report was computed over and not what the caller typed.
     *
     * @param override may be null or blank — regenerating a saved report unchanged is the ordinary
     *     case, and it must not need an empty object sent to mean "as saved"
     */
    static String merge(String base, String override) {
        JsonNode merged = parse(base).node().deepCopy();
        for (Map.Entry<String, JsonNode> field : parse(override).node().properties()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) merged)
                    .set(field.getKey(), field.getValue());
        }
        return merged.toString();
    }

    /**
     * A required id.
     *
     * <p>Absent, null and non-textual are one refusal on purpose: from the caller's side they are
     * the same mistake — the parameter is not there in a usable form — and three different messages
     * would suggest three different fixes.
     */
    UUID requireUuid(String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw new IllegalArgumentException("This report needs a '" + name + "' parameter");
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + name + "' is not a valid id", e);
        }
    }

    /**
     * Every parameter that was sent, in the order the request wrote them — the header's selection
     * block (§6e), assembled here because this type owns the node.
     *
     * <p>A scalar prints as its text and a nested object or array as its own compact JSON. Nothing
     * is filtered and nothing is renamed: {@link ReportHeaders} states at length why the selection
     * has to be what the client sent rather than what the report understood.
     */
    List<ReportModel.Entry> entries() {
        List<ReportModel.Entry> entries = new ArrayList<>();
        // properties(), not the deprecated fields(): both iterate in insertion order, which is what
        // makes the selection read in the order the client wrote it.
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            JsonNode value = field.getValue();
            entries.add(new ReportModel.Entry(
                    field.getKey(),
                    value.isValueNode() ? value.asText() : value.toString()));
        }
        return List.copyOf(entries);
    }

    /** An optional string parameter, empty when absent, null or blank. */
    Optional<String> optionalText(String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.asText());
    }
}
