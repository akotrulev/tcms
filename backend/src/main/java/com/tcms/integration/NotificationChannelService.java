package com.tcms.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.OutboxEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's notification channels — the write path, and the reads a screen needs.
 *
 * <h2>The permission is {@code integration:manage} and this slice adds none</h2>
 *
 * <p>It has been seeded since {@code V3} and held by Test Lead and up, which is what makes this a
 * feature rather than a catalogue change: {@code V52} decided that the Test Engineer → Test Lead
 * line is approvals and <em>project configuration</em>, and a channel is project configuration.
 * Reading is gated on project visibility alone, for {@code ProjectSettingsService}'s reason —
 * everyone who can see a project can already see whether its notices go anywhere, by receiving
 * one.
 *
 * <h2>Every method here is package-private, and the reason is a rule this repository enforces</h2>
 *
 * <p>{@code ArchitectureRulesTest.everyPublicBeanMethodIsExercisedInProduction} refuses a public
 * bean method whose only callers are tests: a public method is something the application offers to
 * do, and if only a test calls it the product has never done it. The controller that will call
 * these lands with the contract in the next phase and lives in this package, so package-private is
 * both what the rule wants and what the visibility actually is.
 *
 * <h2>Subscriptions are reconciled, not merged</h2>
 *
 * <p>An edit sends the complete set and this replaces it, because removal is the ordinary case: a
 * channel that stops wanting run completions has to be able to say so, and an upsert-only shape
 * can add and change a subscription and has no way to take one away. {@code V46}'s mirror makes
 * the same call for the same reason.
 */
@Service
public class NotificationChannelService {

    /** Roles spec §A3 — project configuration, held by Test Lead and up. Seeded by {@code V3}. */
    private static final String MANAGE = "integration:manage";

    /** How much of a channel's history a screen gets. Newest first; older is a query nobody asked for. */
    private static final int HISTORY_LIMIT = 50;

    /** The event types a subscription may name — the vocabulary, checked rather than trusted. */
    private static final Set<String> DELIVERABLE_EVENTS =
            Set.of(OutboxEvents.RUN_COMPLETED, OutboxEvents.ITEM_ASSIGNED);

    private final ProjectService projects;
    private final NotificationChannelRepository channels;
    private final NotificationSubscriptionRepository subscriptions;
    private final NotificationDeliveryRepository deliveries;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final Clock clock;

    NotificationChannelService(
            ProjectService projects,
            NotificationChannelRepository channels,
            NotificationSubscriptionRepository subscriptions,
            NotificationDeliveryRepository deliveries,
            SecretCipher cipher,
            ObjectMapper json,
            Clock clock) {

        this.projects = projects;
        this.channels = channels;
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
    }

    /** What a write did. Sealed, so a controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, or a channel in a sibling project — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code integration:manage} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** 400, on the fields. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** Created or updated, with the channel as it now stands — never with its credential. */
        record Saved(NotificationChannelView view) implements Outcome {
        }

        /** Gone. */
        record Deleted() implements Outcome {
        }
    }

    // ------------------------------------------------------------------------ reads ----

    /** One project's channels, credentials excluded — see {@link NotificationChannelView}. */
    @Transactional(readOnly = true)
    Optional<List<NotificationChannelView>> list(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId, project ->
                channels.findInProject(project.getId()).stream().map(this::viewOf).toList());
    }

    /**
     * One channel's delivery history, newest first — what an operator opens when a webhook looks
     * dead.
     *
     * <p>Empty for an unreachable project <em>and</em> for a channel in a sibling project, which
     * are the same 404 by §6t: a caller who cannot see the project must not be able to learn which
     * channel ids exist by the shape of the refusal.
     */
    @Transactional(readOnly = true)
    Optional<List<NotificationDeliveryView>> history(UUID userId, UUID projectId, UUID channelId) {
        return projects.<List<NotificationDeliveryView>>readVisible(userId, projectId, project ->
                channels.findInProject(project.getId(), channelId)
                        .map(channel -> deliveries
                                .findForChannel(channel.getId(), Limit.of(HISTORY_LIMIT))
                                .stream()
                                .map(NotificationChannelService::viewOf)
                                .toList())
                        .orElse(null));
    }

    // ----------------------------------------------------------------------- writes ----

    /** A new channel, with its subscriptions. */
    @Transactional
    Outcome create(UUID userId, UUID projectId, NotificationChannelInput input) {
        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, MANAGE, project -> {
            Map<String, List<String>> errors = validate(input, true);
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }
            Instant now = clock.instant();
            NotificationChannel channel = channels.saveAndFlush(NotificationChannel.create(
                    project.getId(),
                    input.kind(),
                    input.name().trim(),
                    input.enabled(),
                    configDocument(input.recipients()),
                    encrypt(input.secret()),
                    SecretHint.of(input.secret()),
                    now));
            reconcile(channel.getId(), input.subscriptions());
            return new Outcome.Saved(viewOf(channel));
        });
        return unwrap(gated);
    }

    /**
     * Everything but the transport.
     *
     * <p>A null {@code secret} leaves the stored one alone — {@link NotificationChannelInput}'s
     * header states why that inverts this codebase's usual convention, and it is the single most
     * consequential line in this class.
     */
    @Transactional
    Outcome update(UUID userId, UUID projectId, UUID channelId, NotificationChannelInput input) {
        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, MANAGE, project -> {
            Optional<NotificationChannel> found =
                    channels.findInProject(project.getId(), channelId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            Map<String, List<String>> errors = validate(input, false);
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }
            NotificationChannel channel = found.get();
            Instant now = clock.instant();
            channel.rename(
                    input.name().trim(), input.enabled(), configDocument(input.recipients()), now);
            if (input.secret() != null && !input.secret().isBlank()) {
                channel.replaceSecret(encrypt(input.secret()), SecretHint.of(input.secret()), now);
            }
            channels.saveAndFlush(channel);
            reconcile(channel.getId(), input.subscriptions());
            return new Outcome.Saved(viewOf(channel));
        });
        return unwrap(gated);
    }

    /**
     * Removes a channel and everything hanging off it.
     *
     * <p>A hard delete, and the delivery history goes with it. The alternative — keeping the log
     * of a channel that no longer exists — is a history nothing can be replayed into and a row
     * naming a credential nobody can rotate, which is a worse thing to keep than to lose.
     * {@code V54}'s cascades do the work.
     */
    @Transactional
    Outcome delete(UUID userId, UUID projectId, UUID channelId) {
        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, MANAGE, project -> {
            Optional<NotificationChannel> found =
                    channels.findInProject(project.getId(), channelId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            channels.delete(found.get());
            return new Outcome.Deleted();
        });
        return unwrap(gated);
    }

    // ------------------------------------------------------------------- the details ----

    private static Outcome unwrap(GateResult<Outcome> gated) {
        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * Replaces this channel's subscriptions with the set given.
     *
     * <p>Delete-then-insert rather than an upsert, for the reason in the class header. The set is
     * a handful of rows, so nothing is gained by computing a diff and the diff is where a
     * reconcile goes wrong.
     */
    private void reconcile(UUID channelId, List<NotificationChannelInput.SubscriptionInput> wanted) {
        subscriptions.deleteAll(subscriptions.findForChannel(channelId));
        subscriptions.flush();
        if (wanted == null) {
            return;
        }
        List<NotificationSubscription> rows = new ArrayList<>();
        for (NotificationChannelInput.SubscriptionInput subscription : wanted) {
            rows.add(NotificationSubscription.of(
                    channelId, subscription.eventType(), Math.max(subscription.minFailures(), 0)));
        }
        subscriptions.saveAll(rows);
    }

    /**
     * The field errors, or empty.
     *
     * @param creating whether a secret is required — on update its absence means "unchanged", so
     *                 the same emptiness is a refusal on one path and the ordinary case on the
     *                 other
     */
    private static Map<String, List<String>> validate(
            NotificationChannelInput input, boolean creating) {

        Map<String, List<String>> errors = new LinkedHashMap<>();
        if (input.name() == null || input.name().isBlank()) {
            errors.put("name", List.of("Give the channel a name."));
        }
        if (input.kind() == null) {
            errors.put("kind", List.of("Choose a kind: SLACK, TEAMS or EMAIL."));
        } else if (input.kind() == NotificationChannelKind.EMAIL) {
            if (input.recipients() == null || input.recipients().isEmpty()) {
                errors.put("recipients", List.of("Add at least one recipient."));
            }
        } else if (creating && (input.secret() == null || input.secret().isBlank())) {
            errors.put("secret", List.of("Paste the incoming webhook URL."));
        }
        if (input.subscriptions() != null) {
            List<String> unknown = input.subscriptions().stream()
                    .map(NotificationChannelInput.SubscriptionInput::eventType)
                    .filter(eventType -> !DELIVERABLE_EVENTS.contains(eventType))
                    .toList();
            if (!unknown.isEmpty()) {
                // Named rather than ignored: a subscription to an event nothing records is a
                // channel somebody configured that will never fire, and silence is the one
                // outcome they cannot debug.
                errors.put("subscriptions",
                        List.of("Not an event this instance sends: " + String.join(", ", unknown)));
            }
        }
        return errors;
    }

    private byte[] encrypt(String secret) {
        return secret == null || secret.isBlank() ? null : cipher.encrypt(secret.trim());
    }

    /** The non-credential half of a channel's configuration, as the column holds it. */
    private String configDocument(List<String> recipients) {
        try {
            return json.writeValueAsString(
                    Map.of("recipients", recipients == null ? List.of() : recipients));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("A recipient list could not be serialized", impossible);
        }
    }

    private NotificationChannelView viewOf(NotificationChannel channel) {
        return new NotificationChannelView(
                channel.getId(),
                channel.getProjectId(),
                channel.getKind(),
                channel.getName(),
                channel.isEnabled(),
                ChannelConfig.recipientsOf(channel.getConfig(), json),
                channel.getSecretHint(),
                subscriptions.findForChannel(channel.getId()).stream()
                        .map(subscription -> new NotificationSubscriptionView(
                                subscription.getId(),
                                subscription.getEventType(),
                                subscription.getMinFailures()))
                        .toList(),
                channel.getCreatedAt(),
                channel.getUpdatedAt(),
                channel.getLockVersion());
    }

    private static NotificationDeliveryView viewOf(NotificationDelivery delivery) {
        return new NotificationDeliveryView(
                delivery.getId(),
                delivery.getChannelId(),
                delivery.getEventId(),
                delivery.getEventType(),
                delivery.getStatus(),
                delivery.getAttempt(),
                delivery.getError(),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt());
    }
}
