package com.tcms.integration.tracker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.api.UserRef;
import com.tcms.integration.OutboundUrlGuard;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectMemberDirectory;
import com.tcms.project.ProjectService;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's tracker bindings, and who on the project has connected to the hosts they name.
 *
 * <h2>{@code integration:manage}, for both halves, and the catalogue takes no new key</h2>
 *
 * <p>004 D12, answered by A2 + A3 rather than deferred: TCMS-side visibility of an issue link is
 * <em>project assignment</em>, which is already how this product works; tracker-side visibility of
 * its <em>content</em> is the viewer's own credential. There is nothing left for a 56th key to
 * decide, and {@code doc:review} (005 S9b) is the 56th, uncontested.
 *
 * <p>Configuring a binding is the same act as configuring a notification channel — deciding where
 * this project talks to — so it costs the key that already gates that: Instance Admin, Company Admin,
 * Test Lead. The <em>connected-members</em> read costs it too, which is D19's whole subject and is
 * argued at {@link TrackerConnectedMemberView}.
 *
 * <h2>Never zero and never two defaults</h2>
 *
 * <p>{@code tracker_binding_default_idx} enforces <em>at most one</em>. The rest is here: the first
 * binding becomes the default whether or not it asked to, a later {@code isDefault: true} moves the
 * flag, {@code isDefault: false} on the current default is refused with a message that says what to
 * do instead, and deleting the default promotes the oldest remaining binding. A project with a
 * binding and no default would make "where does a defect get filed" unanswerable, on the one screen
 * where the answer has to be immediate.
 *
 * <h2>The guard runs before the row is written</h2>
 *
 * <p>Under per-user credentials, a {@code base_url} that resolves somewhere unintended exfiltrates
 * <em>every connected person's</em> token to that host. So {@link OutboundUrlGuard} is consulted at
 * the write and again on every call ({@link TrackerClient}), and the write's refusal lands on
 * {@code errors.baseUrl} where the person typing it can see it.
 */
@Service
public class TrackerBindingService {

    /** Roles spec §A3 — project configuration, held by Test Lead and up. Seeded by {@code V3}. */
    static final String MANAGE = "integration:manage";

    private final ProjectService projects;
    private final TrackerBindingRepository bindings;
    private final TrackerIdentityRepository identities;
    private final ProjectMemberDirectory members;
    private final OutboundUrlGuard guard;
    private final ObjectMapper json;
    private final Clock clock;

    TrackerBindingService(
            ProjectService projects,
            TrackerBindingRepository bindings,
            TrackerIdentityRepository identities,
            ProjectMemberDirectory members,
            OutboundUrlGuard guard,
            ObjectMapper json,
            Clock clock) {

        this.projects = projects;
        this.bindings = bindings;
        this.identities = identities;
        this.members = members;
        this.guard = guard;
        this.json = json;
        this.clock = clock;
    }

    /** What a write did. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, or a binding in a sibling project — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code integration:manage} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** 400, on the fields. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The {@code If-Match} the caller read is not the version on the row. */
        record Stale() implements Outcome {
        }

        /** This project is already bound to that tracker project on that host. */
        record Duplicate() implements Outcome {
        }

        /** Created ({@code created}) or patched. */
        record Saved(TrackerBindingView view, boolean created) implements Outcome {
        }

        /** Unbound. */
        record Deleted() implements Outcome {
        }
    }

    @Transactional(readOnly = true)
    GateResult<List<TrackerBindingView>> list(UUID userId, UUID projectId) {
        return projects.readGranted(userId, projectId, MANAGE, project ->
                bindings.findByProjectIdOrderByDefaultBindingDescCreatedAtAsc(project.getId())
                        .stream()
                        .map(this::toView)
                        .toList());
    }

    @Transactional(readOnly = true)
    GateResult<TrackerBindingView> find(UUID userId, UUID projectId, UUID bindingId) {
        return projects.readGranted(userId, projectId, MANAGE, project ->
                bindings.findByIdAndProjectId(bindingId, project.getId())
                        .map(this::toView)
                        .orElse(null));
    }

    /**
     * Every project member, and their state per bound host (D19).
     *
     * <p>Built from {@link TrackerIdentityRepository#findByProviderAndBaseUrlIn}, which is the one
     * finder in this feature that does not name a user — see its own comment. What makes that safe
     * is not the query: it is that the only thing derived from a row here is a
     * {@link TrackerConnectionState}, and {@link TrackerConnectedMemberView} has no component that
     * could carry anything else.
     *
     * <p>Every member is listed, including the ones with no connection to anything, because the
     * question being answered is "why can nobody file defects" and an absent name would read as
     * "not on this project".
     */
    @Transactional(readOnly = true)
    GateResult<List<TrackerConnectedMemberView>> connectedMembers(UUID userId, UUID projectId) {
        return projects.readGranted(userId, projectId, MANAGE, project -> {
            Instant now = clock.instant();

            // Distinct hosts, in binding order, so the columns on the screen are stable between
            // reads. A project bound twice to one Jira site through two projects has ONE host here:
            // an identity is per host, so listing it twice would ask the same question twice and
            // let the two answers look independent.
            Map<String, TrackerConnectedMemberView.HostState> hosts = new LinkedHashMap<>();
            Map<String, Map<UUID, TrackerIdentity>> byHost = new LinkedHashMap<>();
            for (TrackerBinding binding : bindings.findByProjectIdOrderByDefaultBindingDescCreatedAtAsc(
                    project.getId())) {
                String host = binding.getProvider().name() + " " + binding.getBaseUrl();
                if (hosts.containsKey(host)) {
                    continue;
                }
                hosts.put(host, new TrackerConnectedMemberView.HostState(
                        binding.getProvider(), binding.getBaseUrl(), TrackerConnectionState.NOT_CONNECTED));
                Map<UUID, TrackerIdentity> owners = new LinkedHashMap<>();
                for (TrackerIdentity identity : identities.findByProviderAndBaseUrlIn(
                        binding.getProvider(), Set.of(binding.getBaseUrl()))) {
                    owners.put(identity.getUserId(), identity);
                }
                byHost.put(host, owners);
            }

            List<TrackerConnectedMemberView> rows = new ArrayList<>();
            for (UserRef person : members.membersOf(project.getId())) {
                List<TrackerConnectedMemberView.HostState> states = new ArrayList<>();
                hosts.forEach((host, blank) -> {
                    TrackerIdentity identity = byHost.get(host).get(person.id());
                    states.add(new TrackerConnectedMemberView.HostState(
                            blank.provider(),
                            blank.baseUrl(),
                            identity == null
                                    ? TrackerConnectionState.NOT_CONNECTED
                                    : identity.needsAttention(now)
                                            ? TrackerConnectionState.NEEDS_ATTENTION
                                            : TrackerConnectionState.CONNECTED));
                });
                rows.add(new TrackerConnectedMemberView(person, states));
            }
            return rows;
        });
    }

    @Transactional
    public Outcome create(UUID userId, UUID projectId, TrackerBindingInput input) {
        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, MANAGE, project -> {
            Map<String, List<String>> errors = validate(input);
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }
            TrackerProvider provider = TrackerProvider.valueOf(input.provider().trim());
            String baseUrl = normalise(input.baseUrl());
            String externalProject = input.externalProject().trim();

            if (bindings.findByProjectIdAndProviderAndBaseUrlAndExternalProject(
                    project.getId(), provider, baseUrl, externalProject).isPresent()) {
                return new Outcome.Duplicate();
            }

            Instant now = clock.instant();
            boolean first = bindings.findByProjectIdAndDefaultBindingIsTrue(project.getId()).isEmpty();
            boolean isDefault = first || Boolean.TRUE.equals(input.isDefault());
            if (isDefault && !first) {
                demoteCurrentDefault(project.getId(), now);
            }

            TrackerBinding created = TrackerBinding.create(
                    project.getId(),
                    provider,
                    baseUrl,
                    externalProject,
                    write(input.config()),
                    isDefault,
                    !Boolean.FALSE.equals(input.enabled()),
                    userId,
                    now);
            bindings.saveAndFlush(created);
            return new Outcome.Saved(toView(created), true);
        });
        return unwrap(gate);
    }

    @Transactional
    public Outcome update(
            UUID userId, UUID projectId, UUID bindingId, Long ifMatch, TrackerBindingPatch patch) {

        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, MANAGE, project -> {
            Optional<TrackerBinding> found = bindings.findByIdAndProjectId(bindingId, project.getId());
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            TrackerBinding binding = found.get();
            if (ifMatch != null && ifMatch != binding.getLockVersion()) {
                return new Outcome.Stale();
            }

            Map<String, List<String>> errors = new LinkedHashMap<>();
            if (patch.externalProject() != null && patch.externalProject().isBlank()) {
                errors.put("externalProject", List.of("Name the tracker project this maps to."));
            }
            if (Boolean.FALSE.equals(patch.isDefault()) && binding.isDefaultBinding()) {
                // Refused rather than obeyed, because obeying it leaves the project with no default
                // and the next "File defect" with nowhere to go. The remedy is in the message: make
                // another binding the default, which moves the flag in one act.
                errors.put("isDefault", List.of(
                        "Make another binding the default instead — a project always has one."));
            }
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }

            Instant now = clock.instant();
            if (Boolean.TRUE.equals(patch.isDefault()) && !binding.isDefaultBinding()) {
                demoteCurrentDefault(project.getId(), now);
            }
            binding.apply(
                    patch.externalProject() == null ? null : patch.externalProject().trim(),
                    patch.config() == null ? null : write(patch.config()),
                    patch.isDefault(),
                    patch.enabled(),
                    userId,
                    now);
            try {
                bindings.saveAndFlush(binding);
            } catch (OptimisticLockException | OptimisticLockingFailureException lost) {
                return new Outcome.Stale();
            }
            return new Outcome.Saved(toView(binding), false);
        });
        return unwrap(gate);
    }

    /**
     * Unbinds.
     *
     * <p><strong>Issue links made through this binding survive</strong> (004 D18). {@code doc_link}
     * carries the host and never a {@code binding_id}, so nothing cascades: the chip on the case
     * stops previewing, keeps its href, and says so. That is somebody's traceability record and not
     * the deleting request's business.
     */
    @Transactional
    public Outcome delete(UUID userId, UUID projectId, UUID bindingId) {
        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, MANAGE, project -> {
            Optional<TrackerBinding> found = bindings.findByIdAndProjectId(bindingId, project.getId());
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            TrackerBinding binding = found.get();
            boolean wasDefault = binding.isDefaultBinding();
            bindings.delete(binding);
            bindings.flush();

            if (wasDefault) {
                bindings.findByProjectIdOrderByDefaultBindingDescCreatedAtAsc(project.getId())
                        .stream()
                        .findFirst()
                        .ifPresent(promoted -> promoted.setDefault(true, clock.instant()));
            }
            return new Outcome.Deleted();
        });
        return unwrap(gate);
    }

    private void demoteCurrentDefault(UUID projectId, Instant now) {
        bindings.findByProjectIdAndDefaultBindingIsTrue(projectId)
                .ifPresent(current -> current.setDefault(false, now));
        // Flushed before the promotion so the partial unique index sees one default at a time — an
        // insert-then-demote order violates tracker_binding_default_idx inside the transaction.
        bindings.flush();
    }

    private Map<String, List<String>> validate(TrackerBindingInput input) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        if (input.provider() == null || parse(input.provider()) == null) {
            errors.put("provider", List.of("Choose Jira Cloud, Jira Data Center or Azure DevOps."));
        }
        if (input.externalProject() == null || input.externalProject().isBlank()) {
            errors.put("externalProject", List.of("Name the tracker project this maps to."));
        }
        List<String> url = baseUrlErrors(input.baseUrl());
        if (!url.isEmpty()) {
            errors.put("baseUrl", url);
        }
        return errors;
    }

    /**
     * {@code https} only, and a destination this instance is willing to connect to.
     *
     * <p>The messages are different on purpose. A scheme mistake is the person's typing; a private
     * destination is an operator decision with a named setting behind it, and a message that said
     * "invalid URL" for it would send somebody looking for a typo in an address that is correct.
     */
    private List<String> baseUrlErrors(String submitted) {
        if (submitted == null || submitted.isBlank()) {
            return List.of("Give the tracker's address.");
        }
        return switch (guard.check(normalise(submitted))) {
            case ALLOWED -> List.of();
            case NOT_HTTPS -> List.of("The address must start with https://.");
            case MALFORMED -> List.of("That is not a valid address.");
            case UNRESOLVABLE -> List.of("That address does not resolve to a server.");
            case PRIVATE_DESTINATION -> List.of(
                    "This instance will not connect to a private address. An operator can allow it "
                            + "with tcms.integration.outbound.allow-private-hosts.");
        };
    }

    private static TrackerProvider parse(String value) {
        try {
            return TrackerProvider.valueOf(value.trim());
        } catch (IllegalArgumentException notAProvider) {
            return null;
        }
    }

    /** Trailing slashes off, so {@code https://x/} and {@code https://x} are one host to an identity. */
    private static String normalise(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String write(Map<String, Object> config) {
        try {
            return json.writeValueAsString(config == null ? Map.of() : config);
        } catch (JsonProcessingException impossible) {
            // A Map<String, Object> that Jackson cannot write would have to hold something a
            // request body could not have contained.
            throw new IllegalStateException("A binding's config could not be serialized", impossible);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String config) {
        try {
            return json.readValue(config, Map.class);
        } catch (JsonProcessingException unreadable) {
            // The column is written by this class alone, so this is a row edited outside the
            // application — loud rather than rendered as an empty config, which would look like a
            // binding somebody had configured and would silently drop their JQL scope.
            throw new IllegalStateException("A binding's config is not readable JSON", unreadable);
        }
    }

    private TrackerBindingView toView(TrackerBinding binding) {
        return new TrackerBindingView(
                binding.getId(),
                binding.getProjectId(),
                binding.getProvider(),
                binding.getBaseUrl(),
                binding.getExternalProject(),
                read(binding.getConfig()),
                binding.isDefaultBinding(),
                binding.isEnabled(),
                binding.getCreatedAt(),
                binding.getUpdatedAt(),
                binding.getLockVersion());
    }

    /** The gate's three states as one outcome — the switch every writer here ends with. */
    private static Outcome unwrap(GateResult<Outcome> gate) {
        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }
}
