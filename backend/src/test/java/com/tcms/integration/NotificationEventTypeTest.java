package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.contract.OpenApiContract;
import com.tcms.tenancy.OutboxEvents;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wire's event vocabulary against the server's — the check {@code NotificationEventType} has
 * instead of a server enum.
 *
 * <p>{@code ContractEnumTest.OWNERS} names the server type behind every other named enum in the
 * contract, and this one has none: the outbox's names are {@code String} constants on
 * {@link OutboxEvents}, in a foundation module that must not learn what any consumer does with
 * them. An enum there would be a type every producer <em>and</em> every consumer compiled against,
 * which is the coupling the outbox exists to avoid — so the schema sits in that file's
 * {@code NO_SERVER_ENUM} map, and this is what it names as the check instead.
 *
 * <p><strong>Both directions, and each is a different defect.</strong> A member the contract
 * declares and the server refuses is a subscription a client can compose, offer in a picker, and
 * watch 400 — a value the generated union says is legal. A constant the server accepts and the
 * contract does not declare is worse in the direction §170 describes: no generated type can name
 * it, so nothing a client compiles against can subscribe to an event this instance really sends.
 *
 * <p>No Spring context and no container: two documents on disk, in milliseconds. It lives beside
 * the module it is about rather than in {@code com.tcms.contract}, because the set it compares
 * against is this package's and widening it further to reach a test elsewhere would be a
 * production change made for a test.
 */
@DisplayName("Notification event types — the contract's vocabulary is the server's")
class NotificationEventTypeTest {

    private static final String SCHEMA = "NotificationEventType";

    @Test
    @DisplayName("the schema's members are exactly the events a channel may subscribe to")
    void theVocabulariesAgree() {
        List<String> declared = OpenApiContract.load().enumSchemas().get(SCHEMA);

        assertThat(declared)
                .as("the contract declares no %s enum — it was renamed or deleted and this rule "
                        + "would pass over nothing", SCHEMA)
                .isNotNull();

        assertThat(new TreeSet<>(declared))
                .as("""
                        The contract's event vocabulary and the server's disagree.

                        CONTRACT-ONLY is a value the generated union says is legal and the server
                        answers 400 for. SERVER-ONLY is an event this instance really sends that no
                        client can name, because no generated type carries it.

                        Add the member to both, or take it out of both.""")
                .isEqualTo(new TreeSet<>(NotificationChannelService.DELIVERABLE_EVENTS));
    }

    @Test
    @DisplayName("every declared member is a name the outbox actually records")
    void everyMemberIsAnOutboxName() {
        Set<String> recorded = Set.of(OutboxEvents.RUN_COMPLETED, OutboxEvents.ITEM_ASSIGNED);

        assertThat(new TreeSet<>(OpenApiContract.load().enumSchemas().get(SCHEMA)))
                .as("""
                        A declared event type is not one of the occurrence names the outbox
                        records.

                        This is the arm the comparison above cannot make: both sides could agree on
                        a name that is a typo of the constant a producer writes, and the only
                        symptom would be a channel that never fires — which is the one outcome an
                        administrator cannot debug.""")
                .isEqualTo(new TreeSet<>(recorded));
    }
}
