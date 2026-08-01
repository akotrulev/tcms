package com.tcms.documentation;

import java.util.List;
import java.util.UUID;

/**
 * {@code DocPageInput} in {@code docs/specs/openapi.yaml} — the create and edit payload.
 *
 * <p>{@code status} is deliberately not a field. §6g's rule, and the third application of it: a
 * state an ordinary create could set is an approval its author granted themselves. Publication moves
 * through {@code PUT .../status} and {@code doc:publish}.
 *
 * @param blocks {@code null} and {@code []} mean different things and the difference is
 *               load-bearing. Null is "this request is not about the blocks" — a retitle — and the
 *               page's content is left alone; empty is "this page now has no blocks", which deletes
 *               them. A record component cannot express that distinction any other way, and
 *               collapsing it would make forgetting a field the way to empty somebody's
 *               specification.
 */
public record DocPageInput(String title, UUID parentId, List<DocBlockInput> blocks) {
}
