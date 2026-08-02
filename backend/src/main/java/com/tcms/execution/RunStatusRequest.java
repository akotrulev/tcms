package com.tcms.execution;

/**
 * The body of {@code PUT /projects/{projectId}/runs/{runId}/status} — {@code RunStatusInput}.
 *
 * <p>A {@code String} rather than a {@link RunStatus}, deliberately, and the same call
 * {@code DocPageStatusInput} makes: Jackson's failure on an unknown enum constant is a 400 raised in
 * the message converter, before any handler runs, with a body that names the Java type and the
 * valid constants. Parsed here instead, the same mistake is a field error on {@code status} that the
 * form can land on — and it stays a field error rather than becoming an existence oracle, because
 * the caller has already been gated by the time it is reported.
 *
 * @param status one of {@code NOT_STARTED}, {@code IN_PROGRESS}, {@code COMPLETED}, {@code ABORTED}.
 *               The two live spellings both mean "reopen" and {@link RunLifecycle} normalizes
 *               between them — see its header for why the server derives that rather than trusting
 *               it.
 */
public record RunStatusRequest(String status) {
}
