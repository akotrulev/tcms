package com.tcms.execution;

import java.util.UUID;

/**
 * {@code AutomationMappingInput} in {@code docs/specs/openapi.yaml} — <em>"this test name means this
 * case"</em>.
 *
 * @param testName the test as a report names it. Sent raw and normalized server-side by
 *                 {@link IngestedResult#normalize}: the client must not normalize, because the rule
 *                 has to agree exactly with the one the parser applies and a second implementation
 *                 of it is how every mapping silently stops matching. The unmatched list on an
 *                 ingestion response carries {@code normalizedName} precisely so a client can paste
 *                 back a value that is already in the canonical form — but sending the raw name
 *                 works, which is what makes the endpoint usable from a person's own reading of a
 *                 CI log.
 * @param caseId   the case results under that name should land on.
 */
public record AutomationMappingRequest(String testName, UUID caseId) {
}
