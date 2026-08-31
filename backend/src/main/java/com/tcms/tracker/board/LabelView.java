package com.tcms.tracker.board;

import java.util.UUID;

/**
 * The contract's {@code Label}.
 *
 * <p>{@link #color} holds a design-token name ({@code label-1} … {@code label-10}), never a hex
 * value: label colour is data the palette owns, and a stored {@code #RRGGBB} is invisible in the
 * other colour scheme. <strong>The property and the column are both {@code color}</strong> and were
 * {@code colorToken} for about an hour — {@code responseCredentials.test.ts} reddens on any response
 * property whose name contains {@code token}, and exempting a <em>design</em> token would have put a
 * non-credential into a list whose every entry is a decision about disclosure (D-i).
 */
public record LabelView(UUID id, String name, String color) {
}
