package com.tcms.billing;

import java.math.BigDecimal;

/**
 * A monetary figure and the currency it is in, <strong>always together</strong> (019 D-l).
 *
 * <p>There is no bare amount anywhere on this module's wire surface. A company with a EUR client and
 * a CHF client has no single total, and a naked number is the field that eventually gets summed
 * across two of them — which is a figure that is wrong in both currencies and looks right in
 * neither. Every company-level total on this surface is therefore an <em>array</em> of these, one
 * entry per currency.
 *
 * <p>The currency is determined by the client's rate card and is never chosen on a form, which is
 * what makes a cross-client invoice structurally impossible rather than merely refused.
 *
 * <p><strong>Not in {@code com.tcms.api}.</strong> One module serves this shape and the api package
 * is for shapes two modules must agree on without importing each other — {@code ClientRef}'s own
 * header states the test. If {@code time} or {@code staffing} ever served an amount this would move;
 * neither may, and 016/017 both say so in their package comments.
 */
public record Money(BigDecimal amount, String currency) {
}
