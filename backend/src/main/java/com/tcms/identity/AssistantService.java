package com.tcms.identity;

import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own connected assistants — <strong>a self-service view over the {@code api_token}
 * rows they own carrying the Agent role (D26), and not a table.</strong>
 *
 * <h2>Why this needs no permission at all</h2>
 *
 * <p>{@code POST /api-tokens} costs {@code token:issue} because it mints a credential carrying a
 * role the issuer names, for the company, and the escalation ladder that opens is real. None of
 * that is true here: the role is fixed to {@link Role#AGENT_ID}, the owner is the caller, and
 * {@code PermissionEvaluator} resolves a token as the <em>intersection</em> of its role and its
 * owner's own grants. An assistant can therefore reach nothing its owner could not reach by
 * signing in and clicking, so requiring a company-administration key to connect one would refuse
 * the developer the feature is for while stopping nobody.
 *
 * <p>What that argument rests on is that <em>nothing on the request widens it</em> — see
 * {@link AssistantInput}. The moment a field here could name a role, an owner or an unbounded
 * expiry, this class needs a gate.
 *
 * <h2>The name collides company-wide, and that is the schema's answer rather than this one's</h2>
 *
 * <p>{@code api_token_name_idx} is unique on {@code (company_id, name)} among live rows, so two
 * developers cannot both call theirs "claude-code". {@code ApiTokenService}'s validation turns that
 * into a field error naming the collision instead of a duplicate-key 500, and the message is the
 * same one the administrative surface gives because the constraint is the same one.
 */
@Service
public class AssistantService {

    /**
     * How long a connected assistant lives.
     *
     * <p>An Agent token <em>must</em> expire ({@code ApiTokenService}'s per-role rule) and this
     * surface does not ask, so the value is chosen here. Ninety days is long enough that nobody
     * re-connects mid-task and short enough that a credential forgotten on a laptop somebody left a
     * company with stops working within a quarter — which is the failure that rule exists for.
     */
    private static final Duration LIFETIME = Duration.ofDays(90);

    private final ApiTokenService tokens;
    private final ApiTokenRepository rows;
    private final ProjectRepository projects;
    private final Clock clock;

    public AssistantService(
            ApiTokenService tokens,
            ApiTokenRepository rows,
            ProjectRepository projects,
            Clock clock) {

        this.tokens = tokens;
        this.rows = rows;
        this.projects = projects;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** A project this session cannot reach, or an assistant the caller does not own. */
        record NotFound() implements Outcome {
        }

        /** Field name to messages, for the form to land them on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** Connected — 201, and the one response carrying the secret. */
        record Connected(IssuedAssistantView view) implements Outcome {
        }

        /** Revoked, or already revoked. Both are 204. */
        record Revoked() implements Outcome {
        }

        /** The caller's own assistants — 200. */
        record Listed(List<AssistantView> views) implements Outcome {
        }
    }

    /** The caller's assistants, newest first, revoked ones included. */
    @Transactional(readOnly = true)
    public Outcome list(UUID callerId) {
        List<ApiToken> owned =
                rows.findByOwnerIdAndRoleIdOrderByCreatedAtDesc(callerId, Role.AGENT_ID);
        Map<UUID, String> keys = projectKeys(owned);
        return new Outcome.Listed(owned.stream()
                .map(token -> AssistantView.of(token, keys.get(token.getProjectId())))
                .toList());
    }

    /**
     * Connects one. The role, the owner and the expiry are not the caller's to choose — see
     * {@link AssistantInput}.
     */
    @Transactional
    public Outcome connect(UUID callerId, AssistantInput input) {
        if (input == null) {
            return new Outcome.Invalid(Map.of("name", List.of("Give the assistant a name.")));
        }

        ApiTokenService.Outcome minted = tokens.mintOwned(callerId, new ApiTokenIssueRequest(
                input.name(), Role.AGENT_ID, input.projectId(),
                clock.instant().plus(LIFETIME)));

        return switch (minted) {
            case ApiTokenService.Outcome.NotFound ignored -> new Outcome.NotFound();
            case ApiTokenService.Outcome.Invalid invalid -> new Outcome.Invalid(invalid.errors());
            case ApiTokenService.Outcome.Issued issued -> new Outcome.Connected(
                    new IssuedAssistantView(
                            AssistantView.of(issued.view().token(),
                                    projectKeyOf(issued.view().token().projectId())),
                            issued.view().secret()));
            // The gate arm and the two read arms cannot arrive from mintOwned, which runs no gate
            // and neither lists nor revokes. Exhaustive rather than a default, so a new outcome on
            // that sealed type is a compile error here instead of a silent 500.
            case ApiTokenService.Outcome.Forbidden ignored ->
                    throw new IllegalStateException(
                            "mintOwned answered Forbidden, and it asks no permission");
            case ApiTokenService.Outcome.Revoked ignored ->
                    throw new IllegalStateException("mintOwned answered Revoked");
            case ApiTokenService.Outcome.Listed ignored ->
                    throw new IllegalStateException("mintOwned answered Listed");
        };
    }

    /** Withdraws one of the caller's own. Idempotent; somebody else's is a 404. */
    @Transactional
    public Outcome revoke(UUID callerId, UUID assistantId) {
        return switch (tokens.revokeOwned(callerId, assistantId)) {
            case ApiTokenService.Outcome.NotFound ignored -> new Outcome.NotFound();
            case ApiTokenService.Outcome.Revoked ignored -> new Outcome.Revoked();
            case ApiTokenService.Outcome.Forbidden ignored ->
                    throw new IllegalStateException(
                            "revokeOwned answered Forbidden, and it asks no permission");
            case ApiTokenService.Outcome.Invalid ignored ->
                    throw new IllegalStateException("revokeOwned answered Invalid");
            case ApiTokenService.Outcome.Issued ignored ->
                    throw new IllegalStateException("revokeOwned answered Issued");
            case ApiTokenService.Outcome.Listed ignored ->
                    throw new IllegalStateException("revokeOwned answered Listed");
        };
    }

    private String projectKeyOf(UUID projectId) {
        return projectId == null ? null
                : projects.findScopedById(projectId).map(Project::getKey).orElse(null);
    }

    /**
     * The scoped projects' keys, in one read.
     *
     * <p>A {@link HashMap} and not {@code Map.of()}: every caller looks up a nullable
     * {@code projectId} and {@code Map.of().get(null)} throws by contract — the trap
     * {@code PeopleDirectory}'s javadoc records as a 500.
     */
    private Map<UUID, String> projectKeys(List<ApiToken> owned) {
        Map<UUID, String> keys = new HashMap<>();
        for (ApiToken token : owned) {
            UUID projectId = token.getProjectId();
            if (projectId != null && !keys.containsKey(projectId)) {
                Optional<Project> project = projects.findScopedById(projectId);
                project.ifPresent(found -> keys.put(projectId, found.getKey()));
            }
        }
        return keys;
    }
}
