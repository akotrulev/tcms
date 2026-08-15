package com.tcms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Microsoft Teams, over an incoming webhook, as a MessageCard.
 *
 * <h2>How much it disagrees with Slack is the argument for the port existing</h2>
 *
 * <p>Same transport, same credential shape, and almost nothing else in common: two reserved
 * {@code @}-prefixed keys, a {@code summary} that is mandatory rather than a nicety, facts as
 * {@code name}/{@code value} objects instead of pre-formatted markdown strings, and no notion of a
 * header block at all. Written against Slack alone, {@link NotificationChannelPort} would have
 * taken a list of blocks and this adapter would have had to un-render them.
 *
 * <h2>{@code summary} is required by Teams, and omitting it fails in the worst way</h2>
 *
 * <p>Teams rejects a card without one — but only on some connectors, and it is the field that
 * becomes the notification's title in Outlook. So it carries the notice's title, which is the
 * value that makes the toast readable, rather than the notice's summary text.
 *
 * <p>MessageCard rather than Adaptive Card: Microsoft has deprecated the format and the
 * replacement is Power Automate Workflows, which is not an incoming webhook and is a different
 * channel kind rather than a different document. Recorded so the next reader knows this is a
 * decision with a known expiry rather than an old copy-paste.
 */
@Component
class TeamsWebhookAdapter implements NotificationChannelPort {

    private final WebhookPoster poster;
    private final ObjectMapper json;

    TeamsWebhookAdapter(WebhookPoster poster, ObjectMapper json) {
        this.poster = poster;
        this.json = json;
    }

    @Override
    public NotificationChannelKind kind() {
        return NotificationChannelKind.TEAMS;
    }

    @Override
    public void deliver(RenderedNotification notification, ChannelConfig config) {
        poster.post(config.webhookUrl(), body(notification, json));
    }

    /** The document, separated from the POST — {@link SlackWebhookAdapter#body}'s reason. */
    static String body(RenderedNotification notification, ObjectMapper json) {
        ObjectNode root = json.createObjectNode();
        root.put("@type", "MessageCard");
        root.put("@context", "https://schema.org/extensions");
        root.put("summary", notification.title());
        root.put("title", notification.title());
        root.put("text", notification.summary());

        ArrayNode sections = root.putArray("sections");
        ObjectNode section = sections.addObject();
        ArrayNode facts = section.putArray("facts");
        notification.facts().forEach(fact -> facts.addObject()
                .put("name", fact.label())
                .put("value", fact.value()));
        return root.toString();
    }
}
