package com.tcms.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The JSON half of {@link ReportXml} — one place both JSON parsers read a tree through.
 *
 * <p>There is no security hardening to concentrate here, which is the difference between the two
 * files: Jackson resolves no external references and a JSON document cannot reach the filesystem.
 * What this exists for instead is the {@code null}-safety that both parsers would otherwise repeat
 * at every field access. Jackson's {@link JsonNode#path} never returns null, so a missing field
 * reads as a missing node rather than an NPE fifteen lines later — but {@code asText()} on a missing
 * node answers the empty string, and an empty test name is exactly the value that must not be
 * mistaken for a present one.
 */
final class ReportJson {

    private ReportJson() {
    }

    /**
     * Reads {@code report} as a tree.
     *
     * @throws ReportXml.MalformedReportException for anything that is not JSON — the same exception
     *                                            type as the XML side, because the caller's mistake
     *                                            and the caller's remedy are the same in both cases
     */
    static JsonNode parse(ObjectMapper json, String report) {
        try {
            return json.readTree(report);
        } catch (Exception cause) {
            throw new ReportXml.MalformedReportException(
                    "That report could not be read as JSON.", cause);
        }
    }

    /** A field's text, or null when absent, null, or blank — the three are one case here. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /** A field's integral value, or null when absent or not a number. */
    static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }
}
