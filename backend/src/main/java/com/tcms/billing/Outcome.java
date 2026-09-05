package com.tcms.billing;

import java.util.List;
import java.util.Map;

/**
 * The outcome of a billing write, once the gate has already let the caller through.
 *
 * <p>Sealed so every controller {@code switch} over it is exhaustive at compile time — the failure
 * that guards against is a sixth outcome added later and handled in three of the eight endpoints,
 * with the others falling through to whatever their {@code else} branch happened to be. Same shape
 * and same argument as {@code crm.ClientService.Outcome} and
 * {@code staffing.ProfileService.Outcome}.
 *
 * <p><strong>Top-level rather than nested in a service</strong>, which is the one difference from
 * those two: this module has four write-bearing services and they answer one set of outcomes to one
 * controller shape. Nesting it in the first of them would make the other three import a peer's
 * inner type to describe their own results.
 *
 * @param <T> what the write produced, present only in {@link Written}
 */
public sealed interface Outcome<T> {

    /**
     * A field the caller can fix — an unknown client, a {@code MONEY} budget with no currency, a
     * blank expense description.
     */
    record Invalid<T>(Map<String, List<String>> errors) implements Outcome<T> {
    }

    /** No such card, budget, project or expense here. The 404. */
    record Missing<T>() implements Outcome<T> {
    }

    /**
     * The 409 — the target exists, the caller may write it, and the refusal is not a field they can
     * fix. Today that is a second rate card for a client that already has one
     * ({@code uq_rate_card_client}); with the invoice surface it is also a discard of something
     * already issued.
     */
    record Conflict<T>(String title, String detail) implements Outcome<T> {
    }

    record Written<T>(T value) implements Outcome<T> {
    }

    record Removed<T>() implements Outcome<T> {
    }
}
