package com.tcms.documentation;

import java.util.UUID;

/**
 * {@code DocBlockInput} in {@code docs/specs/openapi.yaml} — one submitted block.
 *
 * @param id      present ⇒ edit the block with this id <strong>in place</strong>, keeping it;
 *                absent ⇒ a new block, whose id the server issues. This is the field that makes
 *                traceability survive editing (plan §6i/§6m) — see {@link PageContent}.
 * @param level   heading depth. Required on {@code heading} and refused on everything else, rather
 *                than ignored: a level on a paragraph means the client believes it is sending a
 *                heading, and honouring the type while dropping the depth would render that belief
 *                as a paragraph with no complaint.
 * @param text    the words. Empty is allowed — an empty paragraph is a spacer somebody typed —
 *                null is not.
 */
public record DocBlockInput(UUID id, DocBlockType blockType, Integer level, String text) {
}
