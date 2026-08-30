package com.tcms.identity;

import java.util.List;

/**
 * The answer to "who in this company can grant this?" — the key echoed back, and the names.
 *
 * <p>The key is echoed so a cached or raced answer cannot be rendered under a different one. The
 * screen this feeds prints a permission id and a person in one sentence, and the two arriving from
 * different requests is exactly the way that sentence becomes wrong while looking right.
 *
 * <p>An empty list is an ordinary answer and the screen has to be able to tell it from a pending
 * one: nobody in this company holds the key, so the refusal must not promise anybody to ask.
 */
public record GranteeListView(String permission, List<GranteeView> grantees) {
}
