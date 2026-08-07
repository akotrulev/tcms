package com.tcms.execution;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a posted report has to satisfy before anything is parsed — {@code RunEdit}'s shape.
 *
 * <p>Validation is a static method with no dependencies for the reason every {@code *Edit} class
 * here gives: it is the half of a write that can be reasoned about without a database, so it is the
 * half that gets tested exhaustively.
 *
 * <h2>Every error at once</h2>
 *
 * <p>The caller is a CI job with a fixed configuration, and a build that fails, gets one field
 * corrected, and fails again on the next field is two wasted builds. Errors are gathered.
 */
final class IngestionEdit {

    /**
     * The cap on a posted report, in bytes of UTF-8.
     *
     * <p>1 MiB, which is a few thousand JUnit test cases — comfortably more than a real suite emits
     * and far less than a directory dump. <strong>Unbounded, this is the one endpoint in the product
     * that accepts an arbitrary blob straight into a {@code JSONB} column</strong>, and a job posting
     * a 400 MB Allure directory would be an out-of-memory fault rather than a refusal, taking the
     * instance down for every other company on it.
     *
     * <p>Counted in bytes rather than characters: the limit that matters is what the column and the
     * heap see, and a report of CJK test names is three times its character count.
     */
    static final int MAX_REPORT_BYTES = 1024 * 1024;

    /** {@code RunCreateService}'s cap, for its reason. */
    static final int MAX_IDEMPOTENCY_KEY = 200;

    private IngestionEdit() {
    }

    /**
     * Field errors, empty when the request may proceed.
     *
     * @param idempotencyKey the {@code Idempotency-Key} header, or null when the caller omitted it.
     *                       <strong>Required here where {@code RunCreateService} makes it
     *                       optional</strong> — {@code ingestion_job.idempotency_key} is
     *                       {@code NOT NULL}, and the one caller of this endpoint is the one caller
     *                       in the product that retries automatically, whose retry re-posts a whole
     *                       report rather than one row. Optional, and that caller would be the one
     *                       opting out of the constraint written for them.
     */
    static Map<String, List<String>> validate(IngestionRequest request, String idempotencyKey) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            errors.put("Idempotency-Key", List.of(
                    "Send an Idempotency-Key header — it is what stops a CI retry posting the "
                            + "same report twice."));
        } else if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
            errors.put("Idempotency-Key", List.of(
                    "Send a key of at most " + MAX_IDEMPOTENCY_KEY + " characters."));
        }

        if (request == null) {
            errors.put("report", List.of("Send a report."));
            return errors;
        }

        if (request.format() == null) {
            // Jackson answers an unknown enum value with null on a lenient mapper, so this one
            // message covers "absent" and "JUNIT5", which are the same mistake from the caller's
            // side: the field does not name a format this server reads.
            errors.put("format", List.of(
                    "Name the report format — one of JUNIT, TESTNG, ALLURE or CUCUMBER."));
        }

        List<String> reportErrors = new ArrayList<>();
        if (request.report() == null || request.report().isBlank()) {
            reportErrors.add("Send the report file's contents.");
        } else if (request.report().getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) {
            // The cap is named in the message so the caller can split the report rather than
            // guessing at what "too large" means and retrying at random sizes.
            reportErrors.add("That report is larger than the "
                    + (MAX_REPORT_BYTES / 1024) + " KB limit. Post it in parts, one run per part.");
        }
        if (!reportErrors.isEmpty()) {
            errors.put("report", List.copyOf(reportErrors));
        }

        return errors;
    }
}
