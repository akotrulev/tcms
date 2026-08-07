package com.tcms.documentation;

import java.util.List;
import java.util.UUID;

/**
 * {@code DocBlockInput} in {@code docs/specs/openapi.yaml} — one submitted block.
 *
 * <p>Four of the six block types carry a payload beyond {@link #text}, and every one of those
 * payload fields is <strong>refused</strong> on the types that do not have it rather than ignored.
 * That is one rule stated four ways, and it is {@link #level}'s: a field on the wrong type means the
 * client believes it is sending something else, and honouring the type it named while dropping the
 * field renders that belief with no complaint anywhere.
 *
 * @param id      present ⇒ edit the block with this id <strong>in place</strong>, keeping it;
 *                absent ⇒ a new block, whose id the server issues. This is the field that makes
 *                traceability survive editing (plan §6i/§6m) — see {@link PageContent}.
 * @param level   heading depth, or a list bullet's indent depth. Required on {@code heading},
 *                optional on {@code list}, refused on everything else.
 * @param text    the words. Empty is allowed on most types — an empty paragraph is a spacer
 *                somebody typed — null is not. On an {@code image} it is the alt text and blank is
 *                refused.
 * @param rows    a {@code table}'s cells, row-major, first row the header. Required on
 *                {@code table}, refused everywhere else.
 * @param attachmentId an {@code image}'s file, which must already be attached to this page.
 *                Required on {@code image}, refused everywhere else.
 */
public record DocBlockInput(
        UUID id,
        DocBlockType blockType,
        Integer level,
        String text,
        List<List<String>> rows,
        UUID attachmentId) {
}
