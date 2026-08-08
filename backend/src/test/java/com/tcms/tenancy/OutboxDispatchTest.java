package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox end to end — the writer, the lease, the retry ladder and the two terminal states
 * (decision round 3 #52, phase 1).
 *
 * <h2>The context is this class's own, and both reasons matter</h2>
 *
 * <p>It registers a test {@link OutboxHandler}, which is the only way to observe a delivery at all
 * — {@link OutboxDispatcher} builds its handler map from the beans present at construction, and
 * phase 1 ships no production handler on purpose (phase 2 is the first producer and consumer). And
 * it shortens the ladder: {@code max-attempts=2} with a zero backoff makes exhaustion two
 * dispatches rather than eight spread over seven hours, which is the same arithmetic without a
 * clock fixture.
 *
 * <h2>Two companies, and the fixture is deliberately hostile</h2>
 *
 * <p>{@code RlsIsolationTest}'s rule, one table over: both companies record an event of the
 * <strong>same type with the same payload</strong>, so a dispatcher that crossed the boundary would
 * deliver something that looks exactly right. Distinct payloads would let a leak pass as a
 * coincidence.
 */
@DisplayName("Outbox — record, claim, deliver, retry, exhaust (round 3 #52)")
@Import(OutboxDispatchTest.RecordingHandlerConfiguration.class)
@TestPropertySource(properties = {
        "tcms.outbox.enabled=false",
        "tcms.outbox.interval=PT1M",
        "tcms.outbox.lease=PT1M",
        "tcms.outbox.max-attempts=2",
        "tcms.outbox.backoff=PT0S"
})
class OutboxDispatchTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("0b000000-0000-4000-8000-000000000001");
    private static final UUID GLOBEX = UUID.fromString("0b000000-0000-4000-8000-000000000002");
    private static final UUID SUBJECT = UUID.fromString("0b000000-0000-4000-8000-000000000010");

    private static final String HANDLED = "test.handled";
    private static final String UNCLAIMED = "test.unclaimed";

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private OutboxRepository entries;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private OutboxLedger ledger;

    @Autowired
    private RecordingHandler handler;

    @Autowired
    private CountingHandler second;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seedTwoCompanies() {
        handler.reset();
        second.reset();
        // Companies are instance-level, so this runs with no company context.
        tx.executeWithoutResult(status -> {
            if (companies.findById(ACME).isEmpty()) {
                companies.save(Company.provision(ACME, "obx-acme", "Outbox Acme", Instant.now()));
            }
            if (companies.findById(GLOBEX).isEmpty()) {
                companies.save(Company.provision(GLOBEX, "obx-globex", "Outbox Globex", Instant.now()));
            }
        });
        clear(ACME);
        clear(GLOBEX);
    }

    @Test
    @DisplayName("recording outside a transaction is refused — the guarantee would be silently gone")
    void recordOutsideATransactionIsRefused() {
        // The whole pattern is that the event and the work commit together. Called outside a
        // transaction the row still saves and every assertion about delivery still passes, so
        // there is no symptom to find later — which is why this is a refusal and not a comment.
        try (var ignored = CompanyContext.open(ACME)) {
            assertThatThrownBy(() -> outbox.record("case", SUBJECT, HANDLED, payload("x")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("inside the producing transaction");
        }
    }

    @Test
    @DisplayName("a rolled-back producer leaves no event — that is the whole point of the table")
    void aRolledBackProducerLeavesNoEvent() {
        try (var ignored = CompanyContext.open(ACME)) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                outbox.record("case", SUBJECT, HANDLED, payload("doomed"));
                throw new IllegalStateException("the producer failed after recording");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(rows(ACME))
                    .as("no notice may survive work that did not happen")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("a delivered event reaches its handler with its payload and goes SENT")
    void aDeliveredEventGoesSent() {
        record(ACME, HANDLED, "hello");

        int delivered = dispatch(ACME);

        assertThat(delivered).isEqualTo(1);
        assertThat(handler.received).hasSize(1);
        OutboxMessage message = handler.received.get(0);
        assertThat(message.companyId()).isEqualTo(ACME);
        assertThat(message.aggregateType()).isEqualTo("case");
        assertThat(message.aggregateId()).isEqualTo(SUBJECT);
        assertThat(message.payload()).contains("hello");

        OutboxEntry entry = only(ACME);
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError()).isNull();
    }

    @Test
    @DisplayName("a delivered event is not delivered again by the next tick")
    void aDeliveredEventIsNotRedelivered() {
        record(ACME, HANDLED, "once");

        dispatch(ACME);
        int second = dispatch(ACME);

        assertThat(second).isZero();
        assertThat(handler.received).hasSize(1);
    }

    @Test
    @DisplayName("a failing handler leaves the row PENDING with an attempt and an error, not FAILED")
    void aFailingHandlerSchedulesARetry() {
        // PENDING is the load-bearing part: outbox_pending_idx is a PARTIAL index on
        // status = 'PENDING', so a retrying row parked in any other status is invisible to the
        // only index the schema has for finding it.
        handler.failWith(new IllegalStateException("the relay is down"));
        record(ACME, HANDLED, "retry me");

        int delivered = dispatch(ACME);

        assertThat(delivered).isZero();
        OutboxEntry entry = only(ACME);
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError())
                .as("the class as well as the text — a bare getMessage() on an NPE is 'null'")
                .contains("IllegalStateException")
                .contains("the relay is down");
    }

    @Test
    @DisplayName("the attempt ceiling stops the ladder at FAILED, not DEAD")
    void theCeilingStopsAtFailed() {
        handler.failWith(new IllegalStateException("still down"));
        record(ACME, HANDLED, "doomed");

        dispatch(ACME);
        dispatch(ACME);

        OutboxEntry entry = only(ACME);
        assertThat(entry.getStatus())
                .as("transient exhaustion is FAILED — an operator may replay it once the cause is "
                        + "fixed; DEAD means retrying cannot help")
                .isEqualTo(OutboxStatus.FAILED);
        assertThat(entry.getAttempts()).isEqualTo(2);
        assertThat(entry.getNextAttemptAt())
                .as("a terminal row must not stay on the due index")
                .isNull();

        assertThat(dispatch(ACME)).as("and it is not picked up again").isZero();
    }

    @Test
    @DisplayName("an event no handler claims is DEAD — never SENT")
    void anUnclaimedEventTypeIsDead() {
        // SENT would be a claim with no evidence behind it, rendering identically to a delivered
        // one. A deployment with the consuming module disabled is what this looks like, and it is
        // a fact worth being able to see.
        record(ACME, UNCLAIMED, "nobody wants this");

        int delivered = dispatch(ACME);

        assertThat(delivered).isZero();
        OutboxEntry entry = only(ACME);
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(entry.getLastError()).contains(UNCLAIMED);
        assertThat(entry.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("dispatching one company delivers nothing of another's, with identical events on both")
    void dispatchIsScopedToTheOpenCompany() {
        record(ACME, HANDLED, "identical");
        record(GLOBEX, HANDLED, "identical");

        int delivered = dispatch(ACME);

        assertThat(delivered).isEqualTo(1);
        assertThat(handler.received)
                .as("only Acme's — and the payloads are identical, so a leak cannot pass as a match")
                .singleElement()
                .extracting(OutboxMessage::companyId)
                .isEqualTo(ACME);
        assertThat(only(GLOBEX).getStatus())
                .as("Globex's event is untouched")
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("a claimed row is leased — a second claim at the same instant gets nothing")
    void aClaimedRowIsLeased() {
        // The lease is what replaces holding a lock across the handler: visible to every other
        // worker the moment the claim commits, and it expires on its own if this process dies.
        // Asserted on the ledger directly because the window it protects is, by construction,
        // the inside of somebody else's delivery.
        record(ACME, HANDLED, "leased");
        UUID id = only(ACME).getId();

        try (var ignored = CompanyContext.open(ACME)) {
            Instant now = Instant.now();
            Optional<OutboxMessage> first = ledger.claim(id, now);
            Optional<OutboxMessage> second = ledger.claim(id, now);

            assertThat(first).isPresent();
            assertThat(second).as("the lease is already held").isEmpty();
        }

        OutboxEntry entry = only(ACME);
        assertThat(entry.getStatus())
                .as("PENDING throughout the attempt — a SENDING status is one only a live process "
                        + "can leave, and every such row is stuck for ever after a crash")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getNextAttemptAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("every handler claiming the type receives the event — the fan-out is the feature")
    void allClaimingHandlersReceiveTheEvent() {
        // Two modules genuinely care about one fact and neither may call the other, so one row
        // telling both is the only shape available. Asserted here because the alternative — one
        // handler silently winning by bean-definition order — is what an earlier draft of the
        // dispatcher would have shipped.
        record(ACME, HANDLED, "for both");

        dispatch(ACME);

        assertThat(handler.received).hasSize(1);
        assertThat(second.count).isEqualTo(1);
        assertThat(only(ACME).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("a coalesced event is recorded once while an undelivered one is already waiting")
    void coalescedEventsCollapseWhileUndelivered() {
        // Fifty verdicts in a run must not be fifty whole-company recomputes. Two notices that a
        // company's results have moved are one notice.
        coalesce(ACME);
        coalesce(ACME);
        coalesce(ACME);

        assertThat(rows(ACME)).hasSize(1);
        assertThat(only(ACME).getAggregateId())
                .as("a coalesced event stands for every change since the last delivery, so it "
                        + "cannot truthfully name one of them")
                .isNull();
    }

    @Test
    @DisplayName("a coalesced event does NOT fold into one that is already being delivered")
    void coalescedEventsDoNotFoldIntoAClaimedRow() {
        // The window that would otherwise lose a change: a handler that has already read the
        // database cannot stand for a write that committed afterwards, so the second notice needs
        // a row of its own. attempts = 0 is what tells the two apart.
        coalesce(ACME);
        UUID inFlight = only(ACME).getId();
        try (var ignored = CompanyContext.open(ACME)) {
            ledger.claim(inFlight, Instant.now());
        }

        coalesce(ACME);

        assertThat(rows(ACME)).hasSize(2);
    }

    @Test
    @DisplayName("dispatching inside a transaction is refused — it would collapse the three into one")
    void dispatchInsideATransactionIsRefused() {
        try (var ignored = CompanyContext.open(ACME)) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> dispatcher.dispatchDue()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not run inside a transaction");
        }
    }

    // ---- fixture helpers -------------------------------------------------------------------

    private static Payload payload(String note) {
        return new Payload(note);
    }

    private void record(UUID company, String eventType, String note) {
        try (var ignored = CompanyContext.open(company)) {
            tx.executeWithoutResult(status ->
                    outbox.record("case", SUBJECT, eventType, payload(note)));
        }
    }

    private void coalesce(UUID company) {
        try (var ignored = CompanyContext.open(company)) {
            tx.executeWithoutResult(status -> outbox.recordCoalesced(HANDLED));
        }
    }

    private int dispatch(UUID company) {
        try (var ignored = CompanyContext.open(company)) {
            return dispatcher.dispatchDue();
        }
    }

    private List<OutboxEntry> rows(UUID company) {
        try (var ignored = CompanyContext.open(company)) {
            return tx.execute(status -> entries.findAll());
        }
    }

    private OutboxEntry only(UUID company) {
        return rows(company).stream()
                .reduce((first, second) -> {
                    throw new IllegalStateException("expected one event, found more");
                })
                .orElseThrow(() -> new IllegalStateException("expected one event, found none"));
    }

    private void clear(UUID company) {
        try (var ignored = CompanyContext.open(company)) {
            tx.executeWithoutResult(status -> entries.deleteAll(entries.findAll()));
        }
    }

    /** The event body — a record, so the column gets a document rather than a toString. */
    record Payload(String note) {
    }

    /**
     * A handler that remembers what it was given and fails on demand.
     *
     * <p>Mutable and reset per test, which is the one place a shared test bean is simpler than a
     * fresh context per case — the alternative is eight application contexts for eight assertions
     * about one mechanism.
     */
    static class RecordingHandler implements OutboxHandler {

        private final List<OutboxMessage> received = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public String eventType() {
            return HANDLED;
        }

        @Override
        public void handle(OutboxMessage message) {
            if (failure != null) {
                throw failure;
            }
            received.add(message);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void reset() {
            received.clear();
            failure = null;
        }
    }

    /** A second claimant of the same type — the fan-out two modules need. */
    static class CountingHandler implements OutboxHandler {

        private int count;

        @Override
        public String eventType() {
            return HANDLED;
        }

        @Override
        public void handle(OutboxMessage message) {
            count++;
        }

        void reset() {
            count = 0;
        }
    }

    @TestConfiguration
    static class RecordingHandlerConfiguration {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }

        @Bean
        CountingHandler countingHandler() {
            return new CountingHandler();
        }
    }
}
