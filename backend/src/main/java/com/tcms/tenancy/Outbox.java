package com.tcms.tenancy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The producer side: record that something happened, so that somebody else can be told about it
 * later without this transaction depending on them.
 *
 * <h2>The call must be inside the producing transaction, and this refuses if it is not</h2>
 *
 * <p>That is the entire pattern. The event row and the work it describes commit together or neither
 * does — no window where a result was recorded and its notice was lost, none where a notice went out
 * for a result that rolled back. Called <em>outside</em> a transaction the row still saves, the code
 * still reads correctly, every test still passes, and the guarantee is simply gone: this is a defect
 * with no symptom, so it is a refusal rather than a comment.
 *
 * <p>It also refuses in the useful direction. A caller who genuinely wants a fire-and-forget
 * notification is asking for something the outbox is the wrong tool for, and the exception says so
 * at the call site instead of leaving a row that looks transactional.
 *
 * <h2>Serialization is here rather than at each call site</h2>
 *
 * <p>One {@link ObjectMapper} — the injected one, never {@code new ObjectMapper()}, which would be a
 * second serialization configuration whose disagreements with the first show up as a field that is
 * present on one path and absent on another ({@code CaseDraftService}'s rule). A caller hands an
 * object; the column gets a document.
 *
 * <p><strong>A payload that cannot be serialized fails the producer's transaction</strong>, loudly
 * and immediately. The alternative — catching it and writing {@code {}} — turns a programming error
 * into an event that is delivered, handled, and means nothing, which is worse than the transaction
 * everybody notices.
 *
 * @see OutboxEntry why this table, and why the payload crosses as bytes rather than as a type
 */
@Service
public class Outbox {

    private final OutboxRepository entries;
    private final ObjectMapper json;
    private final Clock clock;

    Outbox(OutboxRepository entries, ObjectMapper json, Clock clock) {
        this.entries = entries;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Records one event in the active company, for delivery to whichever module handles its type.
     *
     * <h2>NOTHING IN THE PRODUCT CALLS THIS, and that is a decision rather than an oversight</h2>
     *
     * <p>§131 built both arms of the outbox and the product has only ever used
     * {@link #recordCoalesced}. Every caller of this one is {@code OutboxDispatchTest}, which
     * {@code ArchitectureRulesTest.everyPublicBeanMethodIsExercisedInProduction} names explicitly
     * so that the fact cannot drift back into being an assumption. What that means concretely:
     * this method's serialization, its aggregate naming, and a handler receiving a non-empty
     * payload have never once run outside a test. They are known to work; they are not known to be
     * <em>right</em>, which is a different thing and is the state §152 named at the repository
     * seam.
     *
     * <p><strong>Why it is kept.</strong> Deleting it would make {@code outbox.aggregate_type},
     * {@code aggregate_id} and {@code payload} write-constant — {@code "company"}, {@code NULL},
     * {@code "{}"} — which is precisely the writerless-column shape §116, §117, §132 and §151 each
     * found and each treated as a defect. The trade is one recorded defect shape for the other, and
     * the columns are {@code V1}'s rather than this method's. The one candidate producer §131
     * examined — the invitation email — it refused on grounds that do not expire: only a digest of
     * the token is stored, so a payload carrying the accept URL would put a live credential at rest.
     *
     * <p><strong>What the first real caller owes.</strong> Not just a handler: a consumer-side test
     * pinning the payload shape, because {@link OutboxMessage#payload()} says the compiler cannot
     * see across that boundary and a test on each side is what replaces it. Half of that pair does
     * not exist yet.
     *
     * <p>No {@code @Transactional} of its own, deliberately: this method has no transaction to
     * manage, it <em>joins</em> the caller's — and an annotation here would let it start one when
     * there is none, which is precisely the case the guard exists to refuse.
     *
     * @param aggregateType a stable lowercase noun — {@code run}, {@code case}
     * @param aggregateId   the thing this is about, or null for a company-level event
     * @param eventType     the dotted verb an {@link OutboxHandler} claims by name
     * @param payload       serialized to the {@code JSONB} column. <strong>Never a credential</strong>
     *                      — see {@link OutboxEntry#getPayload()}
     * @throws IllegalStateException if there is no active transaction, or the payload cannot be
     *                               serialized
     */
    public void record(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Outbox.record must be called inside the producing transaction — the event and "
                            + "the work it describes have to commit together or not at all. Event: "
                            + eventType);
        }
        entries.save(OutboxEntry.pending(
                aggregateType, aggregateId, eventType, serialize(eventType, payload),
                clock.instant()));
    }

    /**
     * Records that <em>something of this kind has changed in this company</em>, at most once per
     * undelivered notice — for events whose meaning is a state rather than an occurrence.
     *
     * <h2>Why this exists, in one number</h2>
     *
     * <p>A tester recording fifty verdicts in a run produces fifty "results changed" events, and
     * every handler for that type is a whole-company recompute ({@code ExecutionFactRefreshService},
     * {@code CaseHealthRecomputeService} — both take no arguments by design). Fifty identical
     * full-table projections a minute is not a cost worth paying to say one thing fifty times. Two
     * notices that a company's results have moved <strong>are</strong> one notice.
     *
     * <h2>It takes no aggregate and no payload, and that is a refusal rather than an omission</h2>
     *
     * <p>A coalesced event stands for every change since the last delivery, so a payload naming the
     * first of them would be a fact about one result presented as the reason for all of them — the
     * handler would read {@code runId} and quietly scope its work to that run. There is nothing
     * truthful to put in the payload, so there is no parameter for it.
     *
     * <h2>The race is real and its cost is one extra recompute</h2>
     *
     * <p>Two producers committing at once both see no unclaimed row and both write one. Nothing
     * prevents that short of a unique index this table does not have, and the outcome is a
     * duplicate delivery of an idempotent recompute — the same thing the lease already requires
     * handlers to survive. Suppressing it would cost a constraint that also forbids the legitimate
     * second row described in {@link OutboxRepository#hasUnclaimed}.
     *
     * @param eventType the dotted verb, which by convention for this form names a state that moved
     *                  ({@code execution.results_changed}) rather than a thing that happened
     */
    public void recordCoalesced(String eventType) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Outbox.recordCoalesced must be called inside the producing transaction — the "
                            + "event and the work it describes have to commit together or not at "
                            + "all. Event: " + eventType);
        }
        if (entries.hasUnclaimed(eventType)) {
            return;
        }
        // "company" rather than a domain noun: the subject of a coalesced event is the company,
        // which is the only thing every change it stands for has in common.
        entries.save(OutboxEntry.pending("company", null, eventType, "{}", clock.instant()));
    }

    private String serialize(String eventType, Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Outbox payload for " + eventType + " cannot be serialized", failure);
        }
    }
}
