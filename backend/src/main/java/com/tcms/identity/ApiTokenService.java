package com.tcms.identity;

import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
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
 * Issuing, listing and revoking API tokens — <strong>{@code api_token}'s first writer, and
 * {@code token:issue}'s first consumer.</strong>
 *
 * <p>The table has been in the schema since {@code V1}, {@code V3} seeded the permission and
 * granted it to Company Admin, and {@code V16} repaired the Automation Service role so it would not
 * 404 on every project-scoped path. All three were correct and none of them was reachable: nothing
 * could issue a credential, so the role M3's ingestion depends on could not be held by anything.
 * That is {@code company:teams} before §54, {@code case:approve} before §62, {@code library:manage}
 * before §64 and {@code company:vocabulary} before §69 — the fifth permission to be right in the
 * catalogue and inert in the product.
 *
 * <h2>The secret exists for the length of one method</h2>
 *
 * <p>{@link ApiTokenSecrets#generate} mints it, {@code api_token_lookup} stores its digest, and
 * {@link IssuedApiTokenView} carries the plaintext out. Nothing in between writes it anywhere: not
 * a log line, and specifically <strong>not the audit row</strong>, whose own contract says the
 * {@code changes} document must not contain a credential because the client's own admins can read
 * it. The audit row records the prefix, which is what a reader needs to match the event to a row.
 *
 * <h2>Two rows, one transaction, and the order is the safe one</h2>
 *
 * <p>The policed {@link ApiToken} is written first and the {@link ApiTokenLookup} second, so if
 * anything fails between them the surviving row is the one that grants nothing — a token record
 * with no resolvable digest is inert, while a digest resolving to a company with no token behind it
 * is a credential the filter cannot place. They are in one transaction, so neither happens; the
 * ordering is what makes the impossible case the harmless one anyway. This is
 * {@code ReportJobRunner}'s bytes-first argument with the sign flipped, and the difference is that
 * here the orphan would be a <em>capability</em> rather than a file.
 *
 * <h2>Every gate is {@code token:issue}, including the read</h2>
 *
 * <p>The list is not filtered down to what a caller may see, the way {@code GET /role-assignments}
 * is: it is refused. There is no partial-visibility story for credentials — a token belongs to the
 * company rather than to a project — so an empty array would be a claim that this company has no
 * tokens, told to somebody who simply may not ask.
 */
@Service
public class ApiTokenService {

    /**
     * Roles spec §A3, Company table: <em>"API / CI tokens"</em>. COMPANY scope, so Company Admin
     * and Instance Admin. <strong>Its first consumer in Java</strong> — see the class header.
     */
    static final String ISSUE_TOKENS = "token:issue";

    /** Long enough for "Nightly regression — staging"; short enough to render in a list column. */
    private static final int MAX_NAME = 120;

    private static final String AUDIT_ISSUED = "api_token.issued";
    private static final String AUDIT_REVOKED = "api_token.revoked";
    private static final String AUDIT_TARGET = "api_token";

    private final AdminDirectoryService directory;
    private final ApiTokenRepository tokens;
    private final ApiTokenLookupRepository lookups;
    private final ApiTokenSecrets secrets;
    private final ProjectRepository projects;
    private final RoleRepository roles;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public ApiTokenService(
            AdminDirectoryService directory,
            ApiTokenRepository tokens,
            ApiTokenLookupRepository lookups,
            ApiTokenSecrets secrets,
            ProjectRepository projects,
            RoleRepository roles,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.directory = directory;
        this.tokens = tokens;
        this.lookups = lookups;
        this.secrets = secrets;
        this.projects = projects;
        this.roles = roles;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** The caller does not hold {@code token:issue} in this company. */
        record Forbidden() implements Outcome {
        }

        /** No such token here, or a project that is not reachable from this session (§6t). */
        record NotFound() implements Outcome {
        }

        /** Field name to messages, for the form to land them on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** Issued — 201, and the one response carrying the secret. */
        record Issued(IssuedApiTokenView view) implements Outcome {
        }

        /** Revoked, or already revoked. Both are 204. */
        record Revoked() implements Outcome {
        }

        /** The company's tokens — 200. */
        record Listed(List<ApiTokenView> views) implements Outcome {
        }
    }

    /**
     * The company's tokens, newest first, revoked ones included.
     *
     * @see ApiTokenView#revokedAt() for why a revoked row stays in the list
     */
    @Transactional(readOnly = true)
    public Outcome list(UUID callerId) {
        if (!directory.reachOf(callerId).issueTokens()) {
            return new Outcome.Forbidden();
        }
        Map<UUID, String> roleNames = roleNames();
        return new Outcome.Listed(tokens.findAllByOrderByCreatedAtDesc().stream()
                .map(token -> viewOf(token, roleNames))
                .toList());
    }

    /**
     * Mints a credential.
     *
     * <p><strong>The gate runs before the payload is examined</strong>, {@code RoleGrantService}'s
     * rule: the validation below reports whether a role id names a role and whether a name is
     * already taken in this company, and running it for a caller who may not issue tokens turns a
     * 403 endpoint into a probe. The one thing read first is {@code projectId}, and only to decide
     * between 404 and 403 — an unreachable project must not be distinguishable from a
     * non-existent one by the status code (§6t).
     */
    @Transactional
    public Outcome issue(UUID callerId, ApiTokenIssueRequest request) {
        if (request.projectId() != null && projects.findScopedById(request.projectId()).isEmpty()) {
            return new Outcome.NotFound();
        }
        if (!directory.reachOf(callerId).issueTokens()) {
            return new Outcome.Forbidden();
        }
        return mint(callerId, request);
    }

    /**
     * The same mint with <strong>no {@code token:issue} gate</strong> — {@link AssistantService}'s
     * path, and the one credential in the product a caller issues for themselves (D26).
     *
     * <p>It is safe precisely because it is not general: that service fixes the role to
     * {@link Role#AGENT_ID}, the owner to the caller and the expiry server-side, and
     * {@code PermissionEvaluator} caps a token at the intersection of its role and its owner's own
     * grants. So an assistant can reach nothing its owner cannot already reach by signing in, which
     * is why issuing one is not a privileged act and why {@code /api-tokens}' gate would be the
     * wrong question rather than a missing one.
     *
     * <p>Package-private and unannotated: the caller is already inside its own transaction, and a
     * {@code @Transactional} on a non-public method is an annotation the proxy may not honour —
     * worse than none, because it reads as a boundary that is there.
     */
    Outcome mintOwned(UUID callerId, ApiTokenIssueRequest request) {
        if (request.projectId() != null && projects.findScopedById(request.projectId()).isEmpty()) {
            return new Outcome.NotFound();
        }
        return mint(callerId, request);
    }

    private Outcome mint(UUID callerId, ApiTokenIssueRequest request) {
        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();
        Optional<Map<String, List<String>>> invalid = validate(request, now);
        if (invalid.isPresent()) {
            return new Outcome.Invalid(invalid.get());
        }

        ApiTokenSecrets.NewSecret secret = secrets.generate();
        UUID id = UUID.randomUUID();
        ApiToken token = tokens.save(ApiToken.issue(
                id,
                companyId,
                request.name().trim(),
                secret.prefix(),
                callerId,
                request.roleId(),
                request.projectId(),
                request.expiresAt(),
                now));

        // Second, deliberately — see the class header on why the surviving row in an impossible
        // failure is the inert one.
        lookups.save(ApiTokenLookup.of(secret.hash(), id, companyId));

        auditEvents.save(AuditEvent.record(
                request.projectId(), callerId, AUDIT_ISSUED, AUDIT_TARGET, List.of(id),
                issuedChanges(token), now));

        return new Outcome.Issued(
                new IssuedApiTokenView(viewOf(token, roleNames()), secret.secret()));
    }

    /**
     * Withdraws a credential. Idempotent: revoking an already-revoked token is 204 and does not
     * move {@code revoked_at} — see {@link ApiToken#revoke}.
     *
     * <p>The lookup row is deliberately left in place; {@link ApiTokenLookupRepository}'s header
     * says why one enforcement point beats two.
     */
    @Transactional
    public Outcome revoke(UUID callerId, UUID tokenId) {
        Optional<ApiToken> found = tokens.findScopedById(tokenId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        if (!directory.reachOf(callerId).issueTokens()) {
            return new Outcome.Forbidden();
        }

        return withdraw(found.get(), callerId);
    }

    /**
     * Withdrawing one of the caller's <strong>own</strong> assistants — {@link AssistantService}'s
     * path, gated on ownership instead of on {@code token:issue} for {@link #mintOwned}'s reason.
     *
     * <p>A token the caller does not own, or one that is not an assistant, is
     * {@link Outcome.NotFound} rather than {@link Outcome.Forbidden}: the profile surface is a view
     * over the caller's own rows, so anything outside it does not exist there, and a 403 would
     * report that somebody else's credential is real.
     */
    Outcome revokeOwned(UUID callerId, UUID tokenId) {
        Optional<ApiToken> found = tokens.findScopedById(tokenId)
                .filter(token -> callerId.equals(token.getOwnerId()))
                .filter(token -> Role.AGENT_ID.equals(token.getRoleId()));
        return found.isEmpty() ? new Outcome.NotFound() : withdraw(found.get(), callerId);
    }

    private Outcome withdraw(ApiToken token, UUID actorId) {
        if (token.getRevokedAt() != null) {
            // Already gone. No second audit row: the trail records the moment the credential
            // stopped working, and a row per click would make it look like it stopped twice.
            return new Outcome.Revoked();
        }

        Instant now = clock.instant();
        token.revoke(now);
        auditEvents.save(AuditEvent.record(
                token.getProjectId(), actorId, AUDIT_REVOKED, AUDIT_TARGET, List.of(token.getId()),
                null, now));
        return new Outcome.Revoked();
    }

    /**
     * The rules, in one method.
     *
     * <p>{@code roleId} is refused for a {@link RoleScope#SYSTEM} role for
     * {@link RoleGrantService}'s reason — those grants live in {@code instance_role_assignment} and
     * a company-bound credential carrying one would be a tier the model does not have — and for
     * {@link Role#COMPANY_MEMBER_ID}, which is a consequence of membership rather than a role
     * anybody chooses.
     *
     * <p>{@link Role#AGENT_ID} is accepted here and refused by {@link RoleGrantService} — see
     * {@code V40} §2 — and it is the one role for which {@code expiresAt} is <em>required</em>.
     */
    private Optional<Map<String, List<String>>> validate(ApiTokenIssueRequest request, Instant now) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            errors.put("name", List.of("Give the token a name, so it can be told apart when it "
                    + "has to be revoked."));
        } else if (name.length() > MAX_NAME) {
            errors.put("name", List.of("Keep the name to " + MAX_NAME + " characters or fewer."));
        } else if (tokens.existsByNameAndRevokedAtIsNull(name)) {
            errors.put("name", List.of("This company already has a live token called \"" + name
                    + "\". Revoke it first, or choose another name."));
        }

        if (request.roleId() == null) {
            errors.put("roleId", List.of("Choose the role this token carries. It is not your own "
                    + "role: a token holds one role and nothing else."));
        } else if (Role.COMPANY_MEMBER_ID.equals(request.roleId())) {
            // DO NOT "CLEAN THIS UP" TO ASK Role.isAssignable(). V40 added that flag and set it
            // false for two rows — this one and Agent — and Agent is the one role in the product
            // that exists ONLY to be carried by a token. Reading the flag here makes an Agent token
            // unissuable, which is the single thing the role was seeded for, and nothing else in
            // the suite would look wrong: every other role would keep working.
            //
            // This check is about the membership BASELINE, not about assignability. They coincided
            // while there was one non-assignable role. See V40 §2.
            errors.put("roleId", List.of("That is the membership baseline every member holds, not "
                    + "a role a token can carry."));
        } else {
            Optional<Role> role = roles.findById(request.roleId());
            if (role.isEmpty()) {
                errors.put("roleId", List.of("No such role."));
            } else if (role.get().getScope() == RoleScope.SYSTEM) {
                errors.put("roleId", List.of("Instance-wide roles are not granted to tokens. They "
                        + "are held by people, through the operator CLI."));
            }
        }

        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            errors.put("expiresAt", List.of("The expiry is in the past, so the token would be "
                    + "unusable the moment it was issued."));
        } else if (request.expiresAt() == null && Role.AGENT_ID.equals(request.roleId())) {
            // THE FIRST PER-ROLE RULE IN THIS PATH, and it inverts the default deliberately rather
            // than by omission. An open-ended expiry is permitted for every other role for the
            // reason ApiToken.issue records: a CI credential that dies mid-release is worse than
            // one an operator revokes on purpose.
            //
            // An agent token is the opposite case. It sits on a developer's laptop, inside an MCP
            // client's configuration or a shell profile, where the realistic end is that nobody
            // ever revokes it because nobody remembers it exists — and an optional field is a field
            // nobody fills in. So the credential with the widest write surface in the product is
            // the one that must state when it stops working.
            //
            // Here and not in the form (§2.5): the tcms_ bearer authenticates against /api/*, so a
            // rule a client enforces is a rule `curl` does not have. The form pre-fills a default
            // so this is a nudge rather than an obstacle.
            errors.put("expiresAt", List.of("An agent token has to expire. It lives on somebody's "
                    + "machine rather than in a pipeline, so choose a date you would be willing to "
                    + "let it stop working on."));
        }

        return errors.isEmpty() ? Optional.empty() : Optional.of(errors);
    }

    /**
     * The audit document. <strong>Prefix, never secret</strong> — {@code AuditEvent.record}'s rule,
     * and the prefix is what lets a reader match the event to the row it is about.
     */
    private static String issuedChanges(ApiToken token) {
        StringBuilder changes = new StringBuilder("{")
                .append("\"name\":").append(AuditEvent.jsonString(token.getName()))
                .append(",\"tokenPrefix\":").append(AuditEvent.jsonString(token.getTokenPrefix()))
                .append(",\"roleId\":").append(AuditEvent.jsonString(token.getRoleId().toString()));
        if (token.getProjectId() != null) {
            changes.append(",\"projectId\":")
                    .append(AuditEvent.jsonString(token.getProjectId().toString()));
        }
        if (token.getExpiresAt() != null) {
            changes.append(",\"expiresAt\":")
                    .append(AuditEvent.jsonString(token.getExpiresAt().toString()));
        }
        return changes.append("}").toString();
    }

    /**
     * Role names by id, read once per call.
     *
     * <p>{@code role} is instance-level reference data of six rows, so this is a full read rather
     * than a lookup per token — the alternative is N queries for a table that fits in a sentence.
     */
    private Map<UUID, String> roleNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Role role : roles.findAll()) {
            names.put(role.getId(), role.getName());
        }
        return names;
    }

    private static ApiTokenView viewOf(ApiToken token, Map<UUID, String> roleNames) {
        return new ApiTokenView(
                token.getId(),
                token.getName(),
                token.getTokenPrefix(),
                token.getOwnerId(),
                token.getRoleId(),
                roleNames.get(token.getRoleId()),
                token.getProjectId(),
                token.getExpiresAt(),
                token.getLastUsedAt(),
                token.getRevokedAt(),
                token.getCreatedAt());
    }
}
