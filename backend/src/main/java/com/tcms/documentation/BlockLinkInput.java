package com.tcms.documentation;

import java.util.UUID;

/**
 * {@code BlockLinkInput} in {@code docs/specs/openapi.yaml} — the body of a link create.
 *
 * <p>Strings rather than enums for both vocabularies, deliberately. Jackson binding an unknown enum
 * value produces its own 400 describing a Java type, and this endpoint's whole job is to be told
 * about a case somebody chose in a picker — a mistyped kind is a field error naming the two valid
 * ones, which {@link BlockLinkService} produces after parsing.
 *
 * @param relationship null means {@code VERIFIES}, which is both the contract's default and the
 *                     column's. Absent rather than required because a case linked to a scenario
 *                     means "verifies" in every case anybody has asked for so far, and making the
 *                     author state it each time is a field they will stop reading.
 */
public record BlockLinkInput(String sourceType, UUID sourceId, String relationship) {
}
