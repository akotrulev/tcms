package com.tcms.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The one question {@code TcmsApplication} asks before the context exists: is this a CLI run?
 *
 * <p>A plain unit test, no container — the §150 lane. It is here because the decision this guard
 * makes cannot be observed from inside a running application: answer {@code false} for a real
 * command and the jar starts a servlet container, does the operator's work correctly, and then
 * <em>never exits</em>. The operator sees a command that succeeded and hung, at whatever hour they
 * are running it.
 *
 * <p><strong>It replaces a test of the same shape that pinned the wrong method</strong> (§153).
 * {@code ProvisionCompanyCommand.isRequestedBy} carried six assertions — including the prefix-match
 * trap below — while the guard {@code TcmsApplication} actually calls had none, and the two verbs
 * added after it had never been asserted to switch the jar to non-web at all. The seam had moved
 * and the test had not, which is invisible precisely because the old test keeps passing.
 */
@DisplayName("operator commands — the non-web decision")
class OperatorCommandsTest {

    @Nested
    @DisplayName("recognises a CLI run")
    class Recognises {

        @Test
        @DisplayName("every verb, not only the first one written")
        void everyVerb() {
            // Derived from the set rather than listed, so a fourth verb is covered by the day it
            // is added — which is the property OperatorCommands exists for, asserted rather than
            // assumed. A literal list here would reintroduce exactly the chain it replaced.
            assertThat(OperatorCommands.ALL)
                    .as("the verb set must not be empty, or every case below passes vacuously")
                    .isNotEmpty();
            assertThat(OperatorCommands.ALL)
                    .allSatisfy(verb -> assertThat(OperatorCommands.isRequestedBy(new String[] {verb}))
                            .as("%s does not switch the jar to non-web, so it would hang after "
                                    + "doing its work", verb)
                            .isTrue());
        }

        @Test
        @DisplayName("past the framework arguments that precede it")
        void afterOptions() {
            assertThat(OperatorCommands.isRequestedBy(
                    new String[] {"--server.port=9000", OperatorCommands.PROVISION_COMPANY}))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("leaves a server start alone")
    class LeavesServerStarts {

        @Test
        @DisplayName("no arguments at all")
        void noArguments() {
            assertThat(OperatorCommands.isRequestedBy(new String[] {})).isFalse();
        }

        @Test
        @DisplayName("options only")
        void optionsOnly() {
            assertThat(OperatorCommands.isRequestedBy(new String[] {"--spring.profiles.active=prod"}))
                    .isFalse();
        }

        @Test
        @DisplayName("a verb that merely starts with one — not a prefix match")
        void notAPrefixMatch() {
            // A future "provision-company-dry-run" must not be mistaken for the real verb and
            // silently provision. Set membership is exact; a startsWith would not be, and the
            // failure would be a company created by a command whose name says it creates nothing.
            for (String verb : OperatorCommands.ALL) {
                assertThat(OperatorCommands.isRequestedBy(new String[] {verb + "-dry-run"}))
                        .as("%s-dry-run is being read as %s", verb, verb)
                        .isFalse();
            }
        }
    }
}
