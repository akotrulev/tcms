package com.tcms.repository;

import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Refreshes this company's mirror of its documentation links — and therefore the two signals
 * derived from it — as soon as a link or a linked page moves, instead of waiting up to an hour for
 * {@link CaseHealthScheduler} (§167).
 *
 * <h2>The first event type in the vocabulary that is not {@code execution}'s</h2>
 *
 * <p>{@code documentation.links_changed} is the second name in {@link OutboxEvents} and this is its
 * only handler — unlike {@link HealthRecomputeOnResultsChanged}, which shares its event with
 * {@code reporting}. The single claimant is not a reason to skip the outbox and call across: this
 * module may not import {@code documentation} and that module may not import this one, so a row is
 * the only shape available regardless of how many modules are listening.
 *
 * <h2>Why the whole recompute rather than the mirror alone</h2>
 *
 * <p>{@link CaseHealthRecomputeService#recompute()} refreshes {@code case_doc_link} and derives
 * every signal in one transaction, and calling only the first half is the edit to refuse. The
 * traceability panel's per-link <em>stale</em> markers and the case's {@code OUTDATED_SPEC_CHANGED}
 * chip are rendered on one screen a panel apart; refreshing the rows without re-deriving the signal
 * is precisely how those two start disagreeing. The extra cost is the four statements this event
 * did not strictly need, on a job that already runs hourly.
 *
 * <p>Idempotent for its sibling's reason — the recompute rewrites the mirror and every signal from
 * the current facts — so at-least-once delivery costs a repeated statement and never a wrong answer.
 *
 * <p>Shares the scheduler's switch, and <strong>that switch now decides more than it did</strong>:
 * with {@code tcms.case-health.enabled=false} the traceability panel stops updating as well as the
 * chips, which is stated at {@code CaseHealthProperties.enabled} and is the same consequence §166
 * gave the Last result column. One switch with one meaning — "the derived half of a case does not
 * move" — beats two that can be set to disagree.
 */
@Component
@ConditionalOnProperty(prefix = "tcms.case-health", name = "enabled", matchIfMissing = true)
class HealthRecomputeOnLinksChanged implements OutboxHandler {

    private final CaseHealthRecomputeService recompute;

    HealthRecomputeOnLinksChanged(CaseHealthRecomputeService recompute) {
        this.recompute = recompute;
    }

    @Override
    public String eventType() {
        return OutboxEvents.DOC_LINKS_CHANGED;
    }

    @Override
    public void handle(OutboxMessage message) {
        recompute.recompute();
    }
}
