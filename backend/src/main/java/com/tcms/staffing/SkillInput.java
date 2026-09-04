package com.tcms.staffing;

/**
 * {@code POST /staffing/skills} — a name, and nothing else.
 *
 * <p>Find-or-create against a {@code citext} column, so "Playwright" typed a second time as
 * "playwright" answers the skill that is already there rather than a second row. A repeated name is
 * a {@code 409}, which is why the operation needs no {@code Idempotency-Key}: the retry that header
 * guards against is already harmless here.
 */
public record SkillInput(String name) {
}
