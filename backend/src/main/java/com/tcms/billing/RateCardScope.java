package com.tcms.billing;

/**
 * How specific a rate-card entry is, and the order the ladder walks (019 D-d).
 *
 * <p>Most specific first, each as of the hour's own {@code spentOn}: a {@link #PERSON} entry naming
 * the person who logged it, then a {@link #ROLE} entry naming a role that person actually holds
 * <strong>on that project</strong>, then {@link #ALL_ROLES}.
 *
 * <p>Where two {@link #ROLE} entries both match, the <strong>higher rate wins</strong> — see
 * {@link PricingService}, which is the only place in this product that walks the ladder.
 *
 * <p>{@code ck_rate_card_entry_scope} and {@code ck_rate_card_entry_shape} hold the member list and
 * the id each member requires; this enum is the application's copy of the first and
 * {@link RateCardService} validates the second.
 */
public enum RateCardScope {

    /** Names the person who logged the hour. Beats everything below it. */
    PERSON,

    /** Names a role. Matches only a role the person is staffed with on the hour's project. */
    ROLE,

    /** The card's floor: what an hour costs when nothing more specific applies. */
    ALL_ROLES
}
