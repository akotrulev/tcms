package com.tcms.repository;

/**
 * {@code AutomationStatus} in {@code docs/specs/openapi.yaml} — where a case sits on the road to
 * being automated (plan §6c).
 *
 * <p>{@link #NOT_AUTOMATABLE} is the one that carries a database constraint of its own:
 * {@code ck_not_automatable_reason} refuses the row unless {@code not_automatable_reason} is
 * filled in. That is what makes automation coverage honestly denominated — a case parked here
 * without a reason is indistinguishable from one nobody has got to yet, and the coverage
 * percentage silently improves by excluding it.
 */
public enum AutomationStatus {
    MANUAL,
    TO_BE_AUTOMATED,
    AUTOMATED,
    NOT_AUTOMATABLE
}
