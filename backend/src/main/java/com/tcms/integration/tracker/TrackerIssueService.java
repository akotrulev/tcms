package com.tcms.integration.tracker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.integration.SecretRedaction;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and filing issues in somebody else's tracker, <strong>as the person asking</strong>.
 *
 * <h2>Two gates, side by side, and neither is a new catalogue key</h2>
 *
 * <p>{@code project:read} decides whether the <em>feature</em> is visible: that is project
 * assignment, which is already how this product works. The caller's own {@link TrackerIdentity}
 * decides whether the <em>content</em> is. 004 A2 and A3 side by side, and D12's answer: there is
 * nothing left for a 56th permission to decide.
 *
 * <h2>NO IDENTITY IS A 200, NEVER A 401</h2>
 *
 * <p>The viewer is authenticated to TCMS perfectly well; what they lack is a credential for
 * somebody else's server. A 401 would trip the SPA's session-recovery path
 * ({@code app/queryClient.ts}) for a condition that has nothing to do with their TCMS session — a
 * specific, easy defect that the plan spends a paragraph preventing, and one this class is the only
 * place that could commit.
 *
 * <p>The create is the exception and it is the contract's: a create cannot answer "unavailable" the
 * way a read can, so an unconnected caller is a {@code 409 tracker-not-connected} and a tracker that
 * refuses is a {@code 502 tracker-unavailable}. Both are refusals of an act, not degraded renders of
 * a fact.
 *
 * <h2>Every read goes through {@link TrackerCache}, and the key starts with the caller</h2>
 *
 * <p>See that class. It is the single most likely defect in this feature and the only one whose
 * absence is invisible in a green suite.
 */
@Service
public class TrackerIssueService {

    /** Roles spec §A1 — the baseline every project member holds. */
    private static final String READ = "project:read";

    /** Roles spec §A5 — Test Engineer and up, unqualified. */
    private static final String CREATE_DEFECT = "defect:create";

    /** A typeahead's page. A person picking an issue refines the query; they do not page a dropdown. */
    private static final int SEARCH_LIMIT = 20;

    /** {@code ReportJobService}'s ceiling, and the other nine's — {@code IdempotencyKeyCapTest}. */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    private final ProjectService projects;
    private final TrackerBindingRepository bindings;
    private final TrackerIdentityService credentials;
    private final TrackerIssueCreationRepository ledger;
    private final TrackerAdapters adapters;
    private final TrackerCache cache;
    private final Outbox outbox;
    private final ObjectMapper json;
    private final Clock clock;

    TrackerIssueService(
            ProjectService projects,
            TrackerBindingRepository bindings,
            TrackerIdentityService credentials,
            TrackerIssueCreationRepository ledger,
            TrackerAdapters adapters,
            TrackerCache cache,
            Outbox outbox,
            ObjectMapper json,
            Clock clock) {

        this.projects = projects;
        this.bindings = bindings;
        this.credentials = credentials;
        this.ledger = ledger;
        this.adapters = adapters;
        this.cache = cache;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    /** What a create did. The reads answer a state and never an outcome — see the class header. */
    public sealed interface Outcome {

        /** Unreachable project, or a binding this project does not have. */
        record NotFound() implements Outcome {
        }

        /** {@code defect:create} is not held here. */
        record Forbidden() implements Outcome {
        }

        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The caller has no identity for the binding's host — 409, {@code tracker-not-connected}. */
        record NotConnected() implements Outcome {
        }

        /** The tracker refused or did not answer — 502, {@code tracker-unavailable}. */
        record Unavailable(String detail) implements Outcome {
        }

        /** Filed ({@code created}), or replayed from an {@code Idempotency-Key} already used. */
        record Filed(TrackerIssueCreatedView view, boolean created) implements Outcome {
        }
    }

    @Transactional(readOnly = true)
    GateResult<TrackerIssueSearchView> search(
            UUID userId, UUID projectId, String query, UUID bindingId) {

        return projects.readGranted(userId, projectId, READ, project -> {
            Optional<TrackerBinding> binding = select(project.getId(), bindingId);
            if (binding.isEmpty()) {
                // A project with no binding, or a binding id naming nothing here. Not a 404: the
                // page renders, the panel says the tracker is not set up, and nothing about the
                // case is unreachable.
                return TrackerIssueSearchView.unavailable(TrackerIssueAvailability.UNAVAILABLE);
            }
            TrackerBindingTarget target = targetOf(binding.get());
            Optional<TrackerCredential> credential = credentials.credentialFor(
                    userId, binding.get().getProvider(), binding.get().getBaseUrl());
            if (credential.isEmpty()) {
                return TrackerIssueSearchView.unavailable(TrackerIssueAvailability.NOT_CONNECTED);
            }
            try {
                return new TrackerIssueSearchView(
                        TrackerIssueAvailability.AVAILABLE,
                        adapters.forProvider(target.provider())
                                .search(credential.get(), target, query, SEARCH_LIMIT));
            } catch (TrackerUnavailableException refused) {
                // One state for four conditions (D16). The message is NOT returned: it would say
                // which of them it was, which is the oracle this endpoint must not be.
                return TrackerIssueSearchView.unavailable(TrackerIssueAvailability.UNAVAILABLE);
            }
        });
    }

    @Transactional(readOnly = true)
    GateResult<TrackerIssueLookupView> lookup(
            UUID userId, UUID projectId, String issueId, UUID bindingId) {

        return projects.readGranted(userId, projectId, READ, project -> {
            Optional<TrackerBinding> binding = select(project.getId(), bindingId);
            if (binding.isEmpty()) {
                return TrackerIssueLookupView.unavailable(TrackerIssueAvailability.UNAVAILABLE);
            }
            TrackerBindingTarget target = targetOf(binding.get());
            Optional<TrackerCredential> credential = credentials.credentialFor(
                    userId, binding.get().getProvider(), binding.get().getBaseUrl());
            if (credential.isEmpty()) {
                return TrackerIssueLookupView.unavailable(TrackerIssueAvailability.NOT_CONNECTED);
            }
            try {
                Optional<TrackerIssue> issue = cache.get(
                        userId, target.bindingId(), issueId, clock.instant(),
                        () -> adapters.forProvider(target.provider())
                                .get(credential.get(), target, issueId));
                return issue
                        .map(found -> new TrackerIssueLookupView(
                                TrackerIssueAvailability.AVAILABLE, found))
                        .orElseGet(() -> TrackerIssueLookupView.unavailable(
                                TrackerIssueAvailability.UNAVAILABLE));
            } catch (TrackerUnavailableException refused) {
                return TrackerIssueLookupView.unavailable(TrackerIssueAvailability.UNAVAILABLE);
            }
        });
    }

    /**
     * Files an issue, with the caller's credential, and records the backstop event.
     *
     * <h2>Four things happen in one transaction, and the order is the design</h2>
     *
     * <ol>
     *   <li>The ledger row is written {@code PENDING} <strong>before the call</strong>, so an
     *       attempt this process died in the middle of is visible as one that never finished rather
     *       than as one that never happened.</li>
     *   <li>The tracker is called. This is the one place in the product where an outbound HTTP call
     *       happens inside a transaction, and it is deliberate: the ledger and the event have to
     *       describe the same attempt, and a call outside the transaction would have to be
     *       reconciled with a row written before or after it.</li>
     *   <li>The ledger row is completed.</li>
     *   <li>{@link OutboxEvents#TRACKER_ISSUE_CREATED} is recorded — the backstop for the SPA's
     *       follow-up never arriving. It commits with the row or not at all, which is what the
     *       outbox is for.</li>
     * </ol>
     *
     * <p><strong>An {@code Idempotency-Key} replay answers 200 with the issue that was already
     * filed.</strong> A double-clicked "File defect" is the failure people actually hit, and a
     * duplicate here is not a duplicate row — it is a second issue in somebody else's tracker, which
     * this product cannot withdraw.
     */
    @Transactional
    public Outcome file(
            UUID userId, UUID projectId, TrackerIssueCreateInput input, String idempotencyKey) {

        GateResult<Outcome> gate = projects.writeGranted(
                userId, projectId, CREATE_DEFECT, project -> {

            Map<String, List<String>> errors = new LinkedHashMap<>();
            if (input.title() == null || input.title().isBlank()) {
                errors.put("title", List.of("Give the issue a title."));
            }
            if (idempotencyKey != null && idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
                errors.put("Idempotency-Key", List.of(
                        "The key must be at most " + MAX_IDEMPOTENCY_KEY + " characters."));
            }
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }

            if (idempotencyKey != null) {
                Optional<TrackerIssueCreation> replay = ledger
                        .findByProjectIdAndUserIdAndIdempotencyKey(
                                project.getId(), userId, idempotencyKey)
                        .filter(TrackerIssueCreation::isCreated);
                if (replay.isPresent()) {
                    TrackerIssueCreation done = replay.get();
                    return new Outcome.Filed(new TrackerIssueCreatedView(
                            TrackerProvider.valueOf(done.getProvider()),
                            done.getExternalId(),
                            done.getExternalKey(),
                            done.getUrl(),
                            done.getBindingId()), false);
                }
            }

            Optional<TrackerBinding> found = select(project.getId(), input.bindingId());
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            TrackerBinding binding = found.get();
            Optional<TrackerCredential> credential = credentials.credentialFor(
                    userId, binding.getProvider(), binding.getBaseUrl());
            if (credential.isEmpty()) {
                return new Outcome.NotConnected();
            }

            Instant now = clock.instant();
            TrackerIssueCreation attempt = TrackerIssueCreation.begin(
                    project.getId(), binding.getId(), userId, input.executionId(),
                    binding.getProvider(), idempotencyKey, now);
            ledger.saveAndFlush(attempt);

            TrackerBindingTarget target = targetOf(binding);
            TrackerIssue issue;
            try {
                issue = adapters.forProvider(binding.getProvider()).create(
                        credential.get(), target,
                        new TrackerDraft(
                                input.title().trim(),
                                input.description(),
                                target.setting("issueType"),
                                target.setting("areaPath")));
            } catch (TrackerUnavailableException refused) {
                String message = SecretRedaction.apply(refused.getMessage(), null);
                attempt.failed(message, clock.instant());
                ledger.saveAndFlush(attempt);
                // The person's own connections page is where a dead token gets noticed, so a real
                // call's failure updates the same columns `…/test` does.
                credentials.recordFailure(
                        userId, binding.getProvider(), binding.getBaseUrl(), message);
                return new Outcome.Unavailable(message);
            }

            attempt.succeeded(issue, clock.instant());
            ledger.saveAndFlush(attempt);

            outbox.record("tracker_issue", attempt.getId(), OutboxEvents.TRACKER_ISSUE_CREATED,
                    new TrackerIssueCreatedEvent(
                            UUID.randomUUID(),
                            CompanyContext.require(),
                            project.getId(),
                            input.executionId(),
                            binding.getId(),
                            binding.getProvider().name(),
                            issue.id(),
                            issue.key(),
                            issue.url(),
                            userId));

            return new Outcome.Filed(new TrackerIssueCreatedView(
                    binding.getProvider(), issue.id(), issue.key(), issue.url(), binding.getId()),
                    true);
        });

        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }

    /** The named binding, or the project's default — the one place that fallback is spelled. */
    private Optional<TrackerBinding> select(UUID projectId, UUID bindingId) {
        Optional<TrackerBinding> binding = bindingId == null
                ? bindings.findByProjectIdAndDefaultBindingIsTrue(projectId)
                : bindings.findByIdAndProjectId(bindingId, projectId);
        // A disabled binding is skipped by search, preview and filing — the switch on the settings
        // screen means what it says, rather than meaning "hidden from the list".
        return binding.filter(TrackerBinding::isEnabled);
    }

    private TrackerBindingTarget targetOf(TrackerBinding binding) {
        return new TrackerBindingTarget(
                binding.getId(),
                binding.getProvider(),
                binding.getBaseUrl(),
                binding.getExternalProject(),
                config(binding));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(TrackerBinding binding) {
        try {
            return json.readValue(binding.getConfig(), Map.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("A binding's config is not readable JSON", unreadable);
        }
    }
}
