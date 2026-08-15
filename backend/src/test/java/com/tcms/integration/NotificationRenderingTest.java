package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.CompanyContext;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * One model, three renderers — and the threshold that decides whether any of them runs.
 *
 * <h2>No database and no Spring context</h2>
 *
 * <p>§150's pure lane. A document is a pure function of a {@link RenderedNotification}, which is
 * why {@code body(...)} exists on the two webhook adapters as a static separate from the POST: a
 * test that had to stand up an HTTP server to find out what Slack would receive would be a test
 * nobody runs.
 *
 * <h2>What the three adapters are here to disagree about</h2>
 *
 * <p>The port would have been shaped wrong if it had been written against one transport, and these
 * assertions are what says so concretely: Slack's header block refuses markdown, Teams requires a
 * {@code summary} and takes facts as objects rather than as pre-formatted strings, and an email has
 * no room at all — only recipients. Every one of those would have leaked into a single-implementation
 * interface.
 */
@DisplayName("Notification rendering — one model, three transports, and the threshold")
class NotificationRenderingTest {

    private static final UUID COMPANY = UUID.fromString("f1000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("f1000000-0000-4000-8000-000000000002");

    private final ObjectMapper json = new ObjectMapper();

    private static RunCompletedPayload run(int passed, int failed, int blocked, int total) {
        return new RunCompletedPayload(
                UUID.randomUUID(), UUID.randomUUID(), "RUN-14", "Regression 4.3",
                PROJECT, "COMPLETED", passed, failed, blocked, total);
    }

    @Nested
    @DisplayName("The model — what a notice says before any transport sees it")
    class Model {

        @Test
        @DisplayName("a failing run says so in the title, which is often all anybody reads")
        void theVerdictIsInTheTitle() {
            RenderedNotification notice =
                    NotificationTexts.runCompleted(run(9, 3, 1, 15), "Online Banking");

            assertThat(notice.title()).isEqualTo("RUN-14 completed — 3 failures");
            assertThat(notice.summary()).isEqualTo("Regression 4.3");
            assertThat(notice.facts())
                    .extracting(RenderedNotification.Fact::label)
                    .containsExactly("Project", "Passed", "Failed", "Blocked", "Executions");
            assertThat(notice.facts())
                    .extracting(RenderedNotification.Fact::value)
                    .containsExactly("Online Banking", "9", "3", "1", "15");
        }

        @Test
        @DisplayName("a clean run says all green, and one failure is not \"1 failures\"")
        void thePluralIsRight() {
            assertThat(NotificationTexts.runCompleted(run(15, 0, 0, 15), "Banking").title())
                    .isEqualTo("RUN-14 completed — all green");
            assertThat(NotificationTexts.runCompleted(run(14, 1, 0, 15), "Banking").title())
                    .isEqualTo("RUN-14 completed — 1 failure");
        }

        @Test
        @DisplayName("an assignee who no longer has an account is not named as somebody")
        void anUnresolvedAssigneeIsNotInvented() {
            ItemAssignedPayload payload = new ItemAssignedPayload(
                    UUID.randomUUID(), UUID.randomUUID(), "Write the migration", PROJECT,
                    UUID.randomUUID());

            RenderedNotification notice =
                    NotificationTexts.itemAssigned(payload, "Online Banking", null);

            assertThat(notice.title())
                    .as("attributing work to the wrong person is the one thing this must not do")
                    .isEqualTo("Task assigned to someone who is no longer on the instance");
            assertThat(notice.summary()).isEqualTo("Write the migration");
        }
    }

    @Nested
    @DisplayName("Slack — Block Kit")
    class Slack {

        @Test
        @DisplayName("the document carries text AND blocks — text is the phone's lock screen")
        void bothHalvesArePresent() throws Exception {
            JsonNode body = json.readTree(SlackWebhookAdapter.body(
                    NotificationTexts.runCompleted(run(9, 3, 1, 15), "Online Banking"), json));

            assertThat(body.get("text").asText())
                    .as("blocks with no text pushes as \"This content can't be displayed\"")
                    .isEqualTo("RUN-14 completed — 3 failures");

            JsonNode blocks = body.get("blocks");
            assertThat(blocks.get(0).get("type").asText()).isEqualTo("header");
            assertThat(blocks.get(0).get("text").get("type").asText())
                    .as("Slack refuses mrkdwn in a header block outright")
                    .isEqualTo("plain_text");
            assertThat(blocks.get(1).get("text").get("text").asText()).isEqualTo("Regression 4.3");
            assertThat(blocks.get(2).get("fields")).hasSize(5);
            assertThat(blocks.get(2).get("fields").get(1).get("text").asText())
                    .isEqualTo("*Passed*\n9");
        }

        @Test
        @DisplayName("a quotation mark in a run name produces valid JSON, not a broken payload")
        void userInputIsEscaped() throws Exception {
            RenderedNotification notice = new RenderedNotification(
                    "RUN-1 completed", "Release \"quick\" fix", List.of());

            JsonNode body = json.readTree(SlackWebhookAdapter.body(notice, json));

            assertThat(body.get("blocks").get(1).get("text").get("text").asText())
                    .isEqualTo("Release \"quick\" fix");
        }
    }

    @Nested
    @DisplayName("Teams — MessageCard")
    class Teams {

        @Test
        @DisplayName("the reserved keys and the mandatory summary are all present")
        void theCardIsWellFormed() throws Exception {
            JsonNode body = json.readTree(TeamsWebhookAdapter.body(
                    NotificationTexts.runCompleted(run(9, 3, 1, 15), "Online Banking"), json));

            assertThat(body.get("@type").asText()).isEqualTo("MessageCard");
            assertThat(body.get("@context").asText()).isEqualTo("https://schema.org/extensions");
            assertThat(body.get("summary").asText())
                    .as("Teams rejects a card without one, and it becomes the Outlook subject")
                    .isEqualTo("RUN-14 completed — 3 failures");
            assertThat(body.get("text").asText()).isEqualTo("Regression 4.3");
        }

        @Test
        @DisplayName("facts are name/value objects, not Slack's pre-formatted strings")
        void theFactsAreShapedDifferentlyFromSlacks() throws Exception {
            JsonNode facts = json.readTree(TeamsWebhookAdapter.body(
                            NotificationTexts.runCompleted(run(9, 3, 1, 15), "Online Banking"), json))
                    .get("sections").get(0).get("facts");

            assertThat(facts).hasSize(5);
            assertThat(facts.get(1).get("name").asText()).isEqualTo("Passed");
            assertThat(facts.get(1).get("value").asText()).isEqualTo("9");
        }
    }

    @Nested
    @DisplayName("Email — the kind with no room")
    class Email {

        @Test
        @DisplayName("both parts are composed and both say the same thing")
        void theMessageIsMultipart() throws Exception {
            JavaMailSender sender = mock(JavaMailSender.class);
            MimeMessage message =
                    new MimeMessage(Session.getInstance(new Properties()));
            when(sender.createMimeMessage()).thenReturn(message);

            adapter(sender).deliver(
                    NotificationTexts.runCompleted(run(9, 3, 1, 15), "Online Banking"),
                    new ChannelConfig(NotificationChannelKind.EMAIL, null,
                            List.of("lead@acme.test", "ci@acme.test")));

            verify(sender).send(any(MimeMessage.class));
            assertThat(message.getSubject()).isEqualTo("RUN-14 completed — 3 failures");
            assertThat(message.getAllRecipients()).hasSize(2);
            assertThat(message.getContent()).isInstanceOf(jakarta.mail.Multipart.class);
        }

        @Test
        @DisplayName("markup in a run name arrives as characters, not as markup")
        void theHtmlPartIsEscaped() throws Exception {
            JavaMailSender sender = mock(JavaMailSender.class);
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            when(sender.createMimeMessage()).thenReturn(message);

            adapter(sender).deliver(
                    new RenderedNotification("Done", "<b>now</b>", List.of()),
                    new ChannelConfig(NotificationChannelKind.EMAIL, null, List.of("a@b.test")));

            jakarta.mail.Multipart parts = (jakarta.mail.Multipart) message.getContent();
            String html = readAllParts(parts);
            assertThat(html).contains("&lt;b&gt;now&lt;/b&gt;");
        }

        @Test
        @DisplayName("no relay is a failure the delivery log can name, never a silent success")
        void noRelayThrows() {
            // identity.SmtpInvitationMailer deliberately swallows a dead relay, because the
            // company it announced is already provisioned. Nothing of that applies here: a
            // notification has no other route, and swallowing would write SENT for a message that
            // does not exist.
            @SuppressWarnings("unchecked")
            ObjectProvider<JavaMailSender> absent = mock(ObjectProvider.class);
            when(absent.getIfAvailable()).thenReturn(null);

            assertThatThrownBy(() -> new EmailNotificationAdapter(
                    absent, new NotificationMailProperties("tcms@localhost"))
                    .deliver(new RenderedNotification("t", "s", List.of()),
                            new ChannelConfig(NotificationChannelKind.EMAIL, null,
                                    List.of("a@b.test"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.mail.host");
        }

        private EmailNotificationAdapter adapter(JavaMailSender sender) {
            @SuppressWarnings("unchecked")
            ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(sender);
            return new EmailNotificationAdapter(
                    provider, new NotificationMailProperties("tcms@localhost"));
        }

        private static String readAllParts(jakarta.mail.Multipart parts) throws Exception {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < parts.getCount(); i++) {
                Object content = parts.getBodyPart(i).getContent();
                if (content instanceof jakarta.mail.Multipart nested) {
                    text.append(readAllParts(nested));
                } else {
                    text.append(content);
                }
            }
            return text.toString();
        }
    }

    @Nested
    @DisplayName("The threshold — a predicate on the event, not an event of its own")
    class Threshold {

        @Test
        @DisplayName("min_failures 3 wants three failures and not two")
        void itFiltersOnTheCount() {
            try (var ignored = CompanyContext.open(COMPANY)) {
                NotificationSubscription noisy = NotificationSubscription.of(
                        UUID.randomUUID(), "execution.run_completed", 0);
                NotificationSubscription picky = NotificationSubscription.of(
                        UUID.randomUUID(), "execution.run_completed", 3);

                assertThat(noisy.wants(0)).isTrue();
                assertThat(picky.wants(0)).isFalse();
                assertThat(picky.wants(2)).isFalse();
                assertThat(picky.wants(3)).isTrue();
                assertThat(picky.wants(9)).isTrue();
            }
        }

        @Test
        @DisplayName("an event with no failure count matches every threshold, including a strict one")
        void anEventWithoutCountsIsNotSilenced() {
            // The alternative reading — a threshold nobody set on a count nobody has silences the
            // channel — is indistinguishable from a channel that never fires, which is the one
            // failure an administrator cannot debug.
            try (var ignored = CompanyContext.open(COMPANY)) {
                NotificationSubscription picky = NotificationSubscription.of(
                        UUID.randomUUID(), "work.item_assigned", 3);

                assertThat(picky.wants(null)).isTrue();
            }
        }
    }
}
