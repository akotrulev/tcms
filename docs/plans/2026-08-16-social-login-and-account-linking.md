# Social Login (GitHub · GitLab · Google) and Account Linking — Implementation Plan

**Date:** 2026-08-16 · **Written by:** `ep-writing-plans` · **Revised same day:** invitation-time
social acceptance cut at the user's direction — see Appendix A for the deferred design and the
complexity comparison that decided it.

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a person link GitHub, GitLab and/or Google to their existing TCMS account from a
settings screen, and thereafter sign in with any linked identity — without touching the
invitation/provisioning flow at all.

**Architecture:** Three *instance-level* social providers configured by the operator via
environment variables (no database row per provider), riding the §223/§6r OIDC machinery for
GitLab and Google (both are real OIDC issuers) and a small purpose-built adapter for GitHub
(which is plain OAuth2 — no `id_token`). Two new instance-level tables (`user_social_identity`,
`social_login_attempt`) join the ERD §0.1 exception list. Policy stays in `identity`
(no network), protocol stays in `com.tcms.auth` — the same split `FederatedIdentityService` /
`SsoLoginService` already draws. Accounts are never created by this feature: linking is the
only on-ramp, and every account still arrives through the existing paths.

**Tech Stack:** Spring Boot 3.5.6 / Java 21, Flyway V58, Postgres 17 via Testcontainers;
React 19 + TanStack Query on the SPA; `openapi.yaml` is the contract and drives both generated
`schema.d.ts` copies.

---

## §0 Design decisions (read before any task)

These are the decisions the tasks implement. Each has a reason; do not silently re-decide one.

1. **Social sign-in never creates an account, and no account-creation path changes.** This
   instance provisions companies by operator CLI and admits people by invitation (or
   company-IdP JIT, §6r). The complete set of ways in after this plan:

   | Path | Creates an account? | Changed by this plan? |
   |---|---|---|
   | `provision-company` CLI | yes (the first admin) | untouched |
   | Invitation + password | yes | untouched |
   | Company-IdP SSO with JIT on | yes | untouched |
   | **Social login (button on sign-in page)** | **never** — resolves an already-linked identity only | **new** |

   That table is the whole non-collision argument: the feature is purely additive — a
   linked-credential layer over accounts that all arrive through the existing paths.
   ~~An "invitation + social identity" row (accept an invitation with GitHub instead of a
   password)~~ → **cut in the same-day revision**: it was the plan's only modification to
   `InvitationService`/`AppUser` and its only cross-aggregate transaction; Appendix A holds
   the design should the friction it saves ever matter.

2. **The on-ramp is: accept the invitation with a password (backend unchanged), be signed in
   immediately, and land on the security page.** When acceptance *created* the account's
   credential (`preview.passwordRequired` was true), the SPA — which at that moment holds both
   halves of a valid credential, the invitee's email from the preview and the password just
   chosen — follows the 204 with `POST /auth/login` itself and, when at least one social
   provider is configured, lands the new session on `/account/security?welcome=1`, where the
   page opens in a first-run mode ("want to sign in with GitHub next time?") with an explicit
   skip (Tasks 12–13). **Social is an optional extra, stated by the user 2026-08-16**: the
   welcome page is an offer, its skip is one click, and nothing anywhere in the app is gated on
   having a social identity — password sign-in remains fully sufficient forever. With no
   providers configured the auto-login still runs and lands on the app's default page — the
   login-after-register hop is removed either way.
   Three boundaries hold: the acceptance endpoint still answers **204 with no session** (an
   invitation link must never double as a credential — the §223 decision stands untouched;
   composing accept+login in the SPA even preserves its stated rationale, since the login
   round-trip is what proves the password was stored as intended); a failed auto-login falls
   back to the sign-in page carrying `/account/security?welcome=1` as the post-login
   destination rather than dead-ending; and an existing user accepting a second-company
   invitation typed no password, so there is nothing to auto-login with — they keep the
   existing behaviour and are never first-run.

3. **Social providers are instance-level, not `identity_provider` rows.** The §6r providers are
   a company's own issuers, discovered by corporate email domain, RLS-owned by the company.
   GitHub.com/GitLab.com/Google are authorities for *people*, not companies; a `gmail.com`
   domain row would be claimable by every company at once, and per-company GitHub OAuth apps
   would put registration UX on every client admin. So: operator-configured client id/secret in
   Spring properties, present ⇒ button shown. `IdentityProviderKind` is untouched.

4. **No email auto-linking for social identities — linking is always an explicit act**, from
   the security page while signed in. The §6r auto-link exists because a company admin vouches
   for their own issuer; nobody vouches for all of GitHub. A social sign-in whose
   `(provider, subject)` is unknown gets the same single refusal as every other cause.
   (Consequence to accept: a user who never linked cannot sign in with Google "because the
   email matches". That is the point.)

5. **The identity key is `(provider, subject)`, never email** — same rule as `user_identity`
   (§6r). `subject` is GitHub's numeric `id`, GitLab's `sub`, Google's `sub`.

6. **Two new tables are instance-level and join the ERD §0.1 exception list** (a decision, per
   `SchemaConventionTest`): `user_social_identity` (a person's GitHub-ness is theirs across all
   their companies — a company-scoped copy per membership would need the RLS gymnastics V53
   needed, for no isolation gain since login runs pre-context anyway) and `social_login_attempt`
   (pre-context by construction, like `sso_login_attempt`, which is already exception #13).

7. **Every refusal is the same refusal** — `AuthenticationService`'s rule, applied to every new
   surface: one opaque `#error=` marker for the browser legs, and the link-refusal causes
   (subject taken, provider already linked) collapsed into one outcome.

8. **Session issuance is `SessionTokens.issue`, exactly as SSO does it** — same claims, same
   `pv` epoch, default company via `memberships.findCompanyIdsForUser` (the V7/V8 SECURITY
   DEFINER function), token handed to the SPA in the URL **fragment** (never the query string).
   The provider's token stops at the backend and never reaches the SPA.

9. **Link/unlink endpoints live under `/me`, not `/auth`** — `SecurityConfiguration` opens
   `/api/v1/auth/**` entirely (`permitAll`), and linking requires a live session. `POST
   /me/social-identities/{provider}` answers `{ url }` for the SPA to navigate to (a top-level
   navigation cannot carry the `Authorization` header, so the authenticated leg must be an API
   call that *returns* the redirect rather than a 302).

10. **Unlink refuses to strand an account** (409): a user with no password hash and only one
    social identity may not remove it. Passwordless accounts still exist — company-IdP JIT
    (§6r) creates them, and one of those may link a social identity — so the guard is not dead
    code under this revision. It deliberately does **not** count company-IdP `user_identity`
    rows: those are RLS-protected per company and unreadable across companies from one session,
    so the check would be incomplete; failing closed (refusing an unlink that might have been
    safe) is the correct direction. State the rule in the 409 detail.

11. **Intent travels in the attempt row, not the URL.** One callback endpoint
    (`GET /auth/social/callback`) serves both intents — `LOGIN` and `LINK` — because `state`
    already binds the callback to an attempt row that records the intent, the provider, and
    (for LINK) the user. A caller-supplied copy of any of those could only be ignored or
    trusted (`SsoController#callback`'s argument, verbatim).

12. **No new permission keys.** Managing your own sign-in identities is self-service on your
    own account, like changing your password — gated by authentication, not by the catalogue.
    The permission matrix, §A3, and both copies stay byte-identical.

13. **GitHub email is fetched but only recorded** (`email_at_link` provenance) — it is never a
    key and never a linking trigger (decisions 4–5), so the `/user/emails` verified-primary
    lookup is best-effort.

Files whose *authority* this touches, to update in the same slice as the code:
`docs/specs/2026-07-30-tcms-erd.md` §0.1 (exception list +2),
`docs/specs/openapi.yaml` (Task 1), the handoff (new `docs/handoff/` file + index row),
`docs/agent-context/be-java.md` + `fe-react.md`, and both verification queues (§65 — rows only,
run nothing).

**Gates run on shanks** (`~/bin/tcms-remote …`), per the standing directive. A few targeted
tests may run locally for debugging only.

---

### Task 1: The contract — `openapi.yaml` + both generated copies

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Regenerate: `frontend/src/api/schema.d.ts` and `packages/client/src/…` copy (via the two `gen:api` scripts)

- [ ] **Step 1: Add the paths and schemas to `openapi.yaml`.**

Add under `paths:`, next to the existing `/auth/*` family (keep the file's ordering — auth paths
live together near the top):

```yaml
  /auth/social/providers:
    get:
      operationId: listSocialProviders
      summary: Which social sign-in providers this instance has configured
      description: >-
        Instance-level configuration, identical for every caller — it names no company and no
        account, so answering it publicly discloses nothing (§6r's oracle rule does not bite).
      security: []
      tags: [auth]
      responses:
        '200':
          description: The configured providers, possibly none.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SocialProviderList'
  /auth/social/{provider}/authorize:
    get:
      operationId: socialAuthorize
      summary: Begin a social sign-in (browser navigation, not an XHR)
      security: []
      tags: [auth]
      parameters:
        - $ref: '#/components/parameters/SocialProviderKey'
      responses:
        '302':
          description: >-
            To the provider's authorization endpoint — or to the SPA's failure landing with one
            opaque marker, for any reason at all.
  /auth/social/callback:
    get:
      operationId: socialCallback
      summary: Complete a social round-trip (browser navigation, not an XHR)
      security: []
      tags: [auth]
      parameters:
        - name: state
          in: query
          required: false
          schema: { type: string }
        - name: code
          in: query
          required: false
          schema: { type: string }
      responses:
        '302':
          description: >-
            To the SPA. Success carries the outcome in the URL fragment (a session for LOGIN, a
            linked marker for LINK); every failure carries the same opaque error marker.
  /me/social-identities:
    get:
      operationId: listMySocialIdentities
      summary: The social identities linked to the calling account
      tags: [me]
      responses:
        '200':
          description: All linked identities, possibly none.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SocialIdentityList'
  /me/social-identities/{provider}:
    post:
      operationId: startSocialLink
      summary: Begin linking a social identity to the calling account
      tags: [me]
      parameters:
        - $ref: '#/components/parameters/SocialProviderKey'
      responses:
        '200':
          description: Where to send the browser.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SocialRedirect'
        '404':
          description: The provider is not configured on this instance.
    delete:
      operationId: unlinkSocialIdentity
      summary: Remove a linked social identity from the calling account
      tags: [me]
      parameters:
        - $ref: '#/components/parameters/SocialProviderKey'
      responses:
        '204':
          description: Unlinked (or was never linked — the end state is the same).
        '409':
          description: >-
            Refused: this identity is the account's only way to sign in (no password and no
            other linked identity).
          content:
            application/problem+json:
              schema:
                $ref: '#/components/schemas/Problem'
```

Under `components.parameters:` (create the entry beside existing shared parameters):

```yaml
    SocialProviderKey:
      name: provider
      in: path
      required: true
      schema:
        $ref: '#/components/schemas/SocialProvider'
```

Under `components.schemas:` (every object carries its `required` array — the working
agreement's rule, including nested objects):

```yaml
    SocialProvider:
      type: string
      enum: [GITHUB, GITLAB, GOOGLE]
    SocialProviderList:
      type: object
      required: [providers]
      properties:
        providers:
          type: array
          items:
            $ref: '#/components/schemas/SocialProvider'
    SocialRedirect:
      type: object
      required: [url]
      properties:
        url:
          type: string
          description: The provider's authorization URL; navigate the browser to it.
    SocialIdentity:
      type: object
      required: [provider, linkedAt]
      properties:
        provider:
          $ref: '#/components/schemas/SocialProvider'
        linkedAt:
          type: string
          format: date-time
        lastLoginAt:
          type: string
          format: date-time
          nullable: true
        emailAtLink:
          type: string
          nullable: true
          description: What the provider asserted at link time. Provenance, never a key.
    SocialIdentityList:
      type: object
      required: [identities]
      properties:
        identities:
          type: array
          items:
            $ref: '#/components/schemas/SocialIdentity'
```

(If the existing spec spells the `Problem` schema or the tags differently, match the file — the
existing `/auth/*` entries are the template.)

- [ ] **Step 2: Regenerate BOTH `schema.d.ts` copies.**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

- [ ] **Step 3: Run the contract gates on shanks.**

```bash
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run test:run --workspaces
~/bin/tcms-remote frontend npm run build
```

Expected: green — `schemaFreshness.test.ts` proves both copies match the changed contract; no
consumer references the new operations yet.

- [ ] **Step 4: Commit.**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/api/schema.d.ts
git commit -m "social login: contract — provider discovery, authorize/callback, link/unlink"
```

(Confirm the packages copy's exact path with `git status` before staging — stage only the two
generated files and the spec, never `-A`.)

---

### Task 2: Migration V58 — two instance-level tables

**Files:**
- Create: `backend/src/main/resources/db/migration/V58__social_identities.sql`
- Modify: `backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java` (`INSTANCE_LEVEL_TABLES`)
- Modify: `docs/specs/2026-07-30-tcms-erd.md` (§0.1 exception list)

- [ ] **Step 1: Write the migration.** V58 is the next free number (V57 is the highest on
disk — re-verify with `ls backend/src/main/resources/db/migration | sort -V | tail -1` before
writing; a concurrent lane taking V58 means renumbering in landing order).

```sql
-- V58: social identities — GitHub, GitLab, Google as instance-level sign-in authorities.
--
-- Source: the 2026-08-16 social-login plan (docs/plans/), building on §6r's split: protocol in
-- com.tcms.auth, policy in identity. These authorities are for PEOPLE, not companies — a
-- gmail.com domain row would be claimable by every company at once — so unlike V53's
-- identity_provider there is no provider table at all (operator configuration lives in Spring
-- properties) and both tables here are INSTANCE-LEVEL: ERD §0.1 exceptions, no company_id, no
-- RLS. user_social_identity because a person's social identity is theirs across every company
-- they belong to; social_login_attempt because, like V53's sso_login_attempt (exception #13),
-- it is read before any session or company context can exist.
--
-- Identities are made by LINKING only (a signed-in act on the security page). No row here is
-- ever created during account creation — the plan's Appendix A records the invitation-time
-- variant that was considered and deferred.

CREATE TABLE user_social_identity (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES app_user (id),
    provider      TEXT NOT NULL,
    subject       TEXT NOT NULL,
    email_at_link TEXT,
    linked_at     TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ,
    CONSTRAINT ck_user_social_identity_provider
        CHECK (provider IN ('GITHUB', 'GITLAB', 'GOOGLE')),
    -- One TCMS account per external identity: the login key, and the linking race's backstop.
    CONSTRAINT uq_user_social_identity_subject UNIQUE (provider, subject),
    -- One identity per provider per account: "link all three" is the ceiling.
    CONSTRAINT uq_user_social_identity_user UNIQUE (provider, user_id)
);

COMMENT ON TABLE user_social_identity IS
    'Instance-level (ERD §0.1): a person''s social identity spans their companies. The key is '
    '(provider, subject), NEVER email — §6r''s rule, same reason as user_identity.';
COMMENT ON COLUMN user_social_identity.email_at_link IS
    'What the provider asserted when the link was made. Provenance only; never read as a key.';

CREATE INDEX user_social_identity_user_idx ON user_social_identity (user_id);

CREATE TABLE social_login_attempt (
    id            UUID PRIMARY KEY,
    provider      TEXT NOT NULL,
    intent        TEXT NOT NULL,
    user_id       UUID REFERENCES app_user (id),
    state_hash    TEXT NOT NULL,
    code_verifier TEXT NOT NULL,
    nonce         TEXT NOT NULL,
    redirect_uri  TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed_at   TIMESTAMPTZ,
    CONSTRAINT ck_social_login_attempt_provider
        CHECK (provider IN ('GITHUB', 'GITLAB', 'GOOGLE')),
    CONSTRAINT ck_social_login_attempt_intent
        CHECK (intent IN ('LOGIN', 'LINK')),
    -- The intent decides the binding, exactly: a LINK belongs to a user, a LOGIN to nobody
    -- yet. ck_identity_provider_shape's pattern.
    CONSTRAINT ck_social_login_attempt_intent_shape
        CHECK ((intent = 'LINK') = (user_id IS NOT NULL)),
    CONSTRAINT uq_social_login_attempt_state UNIQUE (state_hash)
);

COMMENT ON TABLE social_login_attempt IS
    'Instance-level (ERD §0.1): consulted before any session exists, like sso_login_attempt. '
    'state_hash is the single-use binding; consumed_at is the anti-replay claim.';
```

- [ ] **Step 2: Run `SchemaConventionTest` to watch it fail** — the two tables are not on the
exception list yet, so the build must refuse them:

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tenancy.SchemaConventionTest'
```

Expected: FAIL — `every table not on the exception list has company_id NOT NULL` names
`user_social_identity` and `social_login_attempt`.

- [ ] **Step 3: Add both tables to `INSTANCE_LEVEL_TABLES`** in `SchemaConventionTest`, with a
comment in the list's established style:

```java
// V58: a person's social identity (GitHub/GitLab/Google) spans their companies, and the
// attempt row is read pre-session like sso_login_attempt. Exceptions #14 and #15 on ERD §0.1.
"user_social_identity",
"social_login_attempt",
```

(Adjust the `#14/#15` ordinals to whatever the list actually holds when you edit it — count,
don't trust this plan. The RLS policy-count literal in `isolationPoliciesUseTheOnePredicate`
does **not** move: neither table gets a policy.)

- [ ] **Step 4: Re-run and verify green**, all three directions (exception check, no-RLS check,
stale-entry check):

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tenancy.SchemaConventionTest'
```

Expected: PASS.

- [ ] **Step 5: Update ERD §0.1's exception list** in
`docs/specs/2026-07-30-tcms-erd.md` — append the two tables with a one-line reason each,
mirroring the wording of the `sso_login_attempt` entry.

- [ ] **Step 6: Commit.**

```bash
git add backend/src/main/resources/db/migration/V58__social_identities.sql \
        backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java \
        docs/specs/2026-07-30-tcms-erd.md
git commit -m "social login: V58 — user_social_identity and social_login_attempt, instance-level by decision"
```

---

### Task 3: Entities and repositories (`identity` + `auth`)

**Files:**
- Create: `backend/src/main/java/com/tcms/identity/SocialProviderKey.java`
- Create: `backend/src/main/java/com/tcms/identity/UserSocialIdentity.java`
- Create: `backend/src/main/java/com/tcms/identity/UserSocialIdentityRepository.java`
- Create: `backend/src/main/java/com/tcms/auth/SocialLoginAttempt.java`
- Create: `backend/src/main/java/com/tcms/auth/SocialLoginAttemptRepository.java`

- [ ] **Step 1: The enum** (in `identity`, because the entity lives there and `auth` already
depends on `identity`):

```java
package com.tcms.identity;

/** Matches {@code ck_user_social_identity_provider} (V58). Instance-level authorities for
 * people — deliberately not a member of {@link IdentityProviderKind}, which enumerates a
 * company's own issuers. */
public enum SocialProviderKey {
    GITHUB,
    GITLAB,
    GOOGLE
}
```

- [ ] **Step 2: The identity entity.** Instance-level: **no** `@Filter`, unlike `UserIdentity`
— that is the visible consequence of decision §0.6 and worth its own javadoc line.

```java
package com.tcms.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One social identity (GitHub, GitLab, Google), bound to one TCMS account.
 *
 * <p>The key is {@code (provider, subject)} and never email — {@link UserIdentity}'s rule for
 * the same reason (§6r). Unlike {@code user_identity} this table is <strong>instance-level</strong>
 * (ERD §0.1): a person's social identity is theirs across every company they belong to, and the
 * login path reads it before any company context can exist. No company filter, no RLS.
 */
@Entity
@Table(name = "user_social_identity")
public class UserSocialIdentity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private SocialProviderKey provider;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "email_at_link", updatable = false)
    private String emailAtLink;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** JPA only. */
    protected UserSocialIdentity() {
    }

    public static UserSocialIdentity link(
            UUID id,
            UUID userId,
            SocialProviderKey provider,
            String subject,
            String emailAtLink,
            Instant now) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(
                    "A social identity needs the provider's subject — linking on email is the "
                            + "account-takeover vector §6r exists to close");
        }
        UserSocialIdentity identity = new UserSocialIdentity();
        identity.id = id;
        identity.userId = userId;
        identity.provider = provider;
        identity.subject = subject;
        identity.emailAtLink = emailAtLink;
        identity.linkedAt = now;
        return identity;
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public SocialProviderKey getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmailAtLink() {
        return emailAtLink;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
```

- [ ] **Step 3: Its repository.** `@Transactional(readOnly = true)` on the interface is
mandatory here like everywhere — `RepositoryTransactionTest` derives the list from the live
context and fails the build otherwise:

```java
package com.tcms.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface UserSocialIdentityRepository extends JpaRepository<UserSocialIdentity, UUID> {

    Optional<UserSocialIdentity> findByProviderAndSubject(SocialProviderKey provider, String subject);

    Optional<UserSocialIdentity> findByProviderAndUserId(SocialProviderKey provider, UUID userId);

    List<UserSocialIdentity> findByUserIdOrderByLinkedAt(UUID userId);

    long countByUserId(UUID userId);
}
```

- [ ] **Step 4: The attempt entity + repository** (in `auth`, beside `SsoLoginAttempt` — copy
its shape: `begin(...)` factory, `isUsable(now)`, and a conditional-`UPDATE` `consume` in the
repository that claims the row before the token exchange):

```java
package com.tcms.auth;

import com.tcms.identity.SocialProviderKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One in-flight social round-trip. {@code SsoLoginAttempt}'s twin, minus the company (there is
 * none until somebody signs in) and plus the {@link Intent} — which travels here, server-side,
 * bound to the presented {@code state}, and never in the URL (the callback argument
 * {@code SsoController#callback} makes, applied to intents).
 */
@Entity
@Table(name = "social_login_attempt")
public class SocialLoginAttempt {

    /** What completing this callback is allowed to do. The shape check in V58 enforces the
     * binding: LINK ⇔ a user, LOGIN ⇔ nobody yet. */
    public enum Intent {
        LOGIN,
        LINK
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private SocialProviderKey provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", nullable = false, updatable = false)
    private Intent intent;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "state_hash", nullable = false, updatable = false)
    private String stateHash;

    @Column(name = "code_verifier", nullable = false, updatable = false)
    private String codeVerifier;

    @Column(name = "nonce", nullable = false, updatable = false)
    private String nonce;

    @Column(name = "redirect_uri", nullable = false, updatable = false)
    private String redirectUri;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** JPA only. */
    protected SocialLoginAttempt() {
    }

    public static SocialLoginAttempt begin(
            UUID id,
            SocialProviderKey provider,
            Intent intent,
            UUID userId,
            String stateHash,
            String codeVerifier,
            String nonce,
            String redirectUri,
            Instant now,
            Instant expiresAt) {

        SocialLoginAttempt attempt = new SocialLoginAttempt();
        attempt.id = id;
        attempt.provider = provider;
        attempt.intent = intent;
        attempt.userId = userId;
        attempt.stateHash = stateHash;
        attempt.codeVerifier = codeVerifier;
        attempt.nonce = nonce;
        attempt.redirectUri = redirectUri;
        attempt.createdAt = now;
        attempt.expiresAt = expiresAt;
        return attempt;
    }

    public boolean isUsable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public SocialProviderKey getProvider() {
        return provider;
    }

    public Intent getIntent() {
        return intent;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getStateHash() {
        return stateHash;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public String getNonce() {
        return nonce;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }
}
```

```java
package com.tcms.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface SocialLoginAttemptRepository extends JpaRepository<SocialLoginAttempt, UUID> {

    Optional<SocialLoginAttempt> findByStateHash(String stateHash);

    /** The single-use claim: 1 when this call consumed the attempt, 0 when somebody else did. */
    @Modifying
    @Transactional
    @Query("UPDATE SocialLoginAttempt a SET a.consumedAt = :now "
            + "WHERE a.id = :id AND a.consumedAt IS NULL")
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("DELETE FROM SocialLoginAttempt a WHERE a.expiresAt < :cutoff")
    int purgeExpired(@Param("cutoff") Instant cutoff);
}
```

(Before writing `consume`/`purgeExpired`, open `SsoLoginAttemptRepository` and copy its exact
annotations and JPQL style — if it differs from the above, the existing file wins.)

- [ ] **Step 5: Extend the purge.** Open `backend/src/main/java/com/tcms/auth/SsoLoginAttemptPurge.java`,
add a `SocialLoginAttemptRepository` constructor parameter, and call its `purgeExpired` with the
same cutoff on the same schedule, logging the two counts separately.

- [ ] **Step 6: Run the schema-validation gate** — booting the context validates the entities
against V58 (`ddl-auto: validate`), and `RepositoryTransactionTest` picks up both repositories:

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.tenancy.RepositoryTransactionTest' \
    --tests 'com.tcms.tenancy.SchemaConventionTest'
```

Expected: PASS.

- [ ] **Step 7: Commit.**

```bash
git add backend/src/main/java/com/tcms/identity/SocialProviderKey.java \
        backend/src/main/java/com/tcms/identity/UserSocialIdentity.java \
        backend/src/main/java/com/tcms/identity/UserSocialIdentityRepository.java \
        backend/src/main/java/com/tcms/auth/SocialLoginAttempt.java \
        backend/src/main/java/com/tcms/auth/SocialLoginAttemptRepository.java \
        backend/src/main/java/com/tcms/auth/SsoLoginAttemptPurge.java
git commit -m "social login: entities, repositories, attempt purge"
```

---

### Task 4: Operator configuration — `SocialProperties`

**Files:**
- Create: `backend/src/main/java/com/tcms/auth/SocialProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/tcms/auth/SocialPropertiesTest.java`

- [ ] **Step 1: Write the failing test** — pure unit, no Spring context:

```java
package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.identity.SocialProviderKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SocialPropertiesTest {

    @Test
    @DisplayName("a provider is configured only when both client id and secret are present")
    void configuredNeedsBothHalves() {
        SocialProperties properties = new SocialProperties(
                new SocialProperties.Client("id", "secret"),
                new SocialProperties.Client("id", null),
                null,
                "https://gitlab.com");

        assertThat(properties.configured(SocialProviderKey.GITHUB)).isTrue();
        assertThat(properties.configured(SocialProviderKey.GITLAB)).isFalse();
        assertThat(properties.configured(SocialProviderKey.GOOGLE)).isFalse();
        assertThat(properties.configuredProviders())
                .containsExactly(SocialProviderKey.GITHUB);
    }

    @Test
    @DisplayName("blank strings count as absent — an empty env var must not half-enable a provider")
    void blankIsAbsent() {
        SocialProperties properties = new SocialProperties(
                new SocialProperties.Client("", " "), null, null, "https://gitlab.com");
        assertThat(properties.configuredProviders()).isEmpty();
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** (compile error — the class does not exist):

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.auth.SocialPropertiesTest'
```

- [ ] **Step 3: Implement.**

```java
package com.tcms.auth;

import com.tcms.identity.SocialProviderKey;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The operator's social sign-in configuration — instance-level by design (plan §0 decision 3):
 * GitHub, GitLab and Google are authorities for people, not companies, so their relying-party
 * credentials belong to the deployment, not to any {@code identity_provider} row.
 *
 * <p>Absent means off. There is no {@code enabled} flag to disagree with the credentials: a
 * provider is a live sign-in route exactly when both halves are present, and the
 * {@code /auth/social/providers} endpoint answers from this and nothing else.
 */
@ConfigurationProperties(prefix = "tcms.auth.social")
public record SocialProperties(Client github, Client gitlab, Client google, String gitlabBaseUrl) {

    public record Client(String clientId, String clientSecret) {

        boolean configured() {
            return present(clientId) && present(clientSecret);
        }
    }

    public SocialProperties {
        if (gitlabBaseUrl == null || gitlabBaseUrl.isBlank()) {
            gitlabBaseUrl = "https://gitlab.com";
        }
        gitlabBaseUrl = gitlabBaseUrl.replaceAll("/+$", "");
    }

    public boolean configured(SocialProviderKey provider) {
        Client client = clientFor(provider);
        return client != null && client.configured();
    }

    public Client clientFor(SocialProviderKey provider) {
        return switch (provider) {
            case GITHUB -> github;
            case GITLAB -> gitlab;
            case GOOGLE -> google;
        };
    }

    public List<SocialProviderKey> configuredProviders() {
        return Arrays.stream(SocialProviderKey.values()).filter(this::configured).toList();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
```

Register it where the other auth `@ConfigurationProperties` records are enabled — grep for
`@EnableConfigurationProperties` in `com.tcms.auth` (likely `OidcConfiguration` or
`SecurityConfiguration`) and add `SocialProperties.class` to that list.

- [ ] **Step 4: Wire `application.yml`.** **`${VAR}` with no colon** is the required-property
form and `${VAR:-x}` means nothing to Spring — but these are *optional* features, so the correct
spelling here is a plain absent-by-default mapping, and `ConfigConventionTest` must stay green:

```yaml
tcms:
  auth:
    social:
      github:
        client-id: ${TCMS_SOCIAL_GITHUB_CLIENT_ID:}
        client-secret: ${TCMS_SOCIAL_GITHUB_CLIENT_SECRET:}
      gitlab:
        client-id: ${TCMS_SOCIAL_GITLAB_CLIENT_ID:}
        client-secret: ${TCMS_SOCIAL_GITLAB_CLIENT_SECRET:}
        # base-url is separate below so a self-managed GitLab works
      google:
        client-id: ${TCMS_SOCIAL_GOOGLE_CLIENT_ID:}
        client-secret: ${TCMS_SOCIAL_GOOGLE_CLIENT_SECRET:}
      gitlab-base-url: ${TCMS_SOCIAL_GITLAB_BASE_URL:}
```

Before committing, read `ConfigConventionTest` and confirm an empty-default (`:}`) placeholder
is acceptable for an optional secret there; if the test constrains the spelling, follow the
test — it is the authority on the text (§108).

- [ ] **Step 5: Run the tests.**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.auth.SocialPropertiesTest' \
    --tests '*ConfigConventionTest'
```

Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add backend/src/main/java/com/tcms/auth/SocialProperties.java \
        backend/src/test/java/com/tcms/auth/SocialPropertiesTest.java \
        backend/src/main/resources/application.yml
git commit -m "social login: operator configuration, absent means off"
```

---

### Task 5: The GitHub adapter — OAuth2 without an id_token

GitHub is the one of the three that is not OIDC: no discovery document, no `id_token`, no
nonce. Identity comes from exchanging the code and calling its user API. GitLab and Google go
through the existing `OidcDiscovery`/`OidcClient`/`OidcIdTokenVerifier` unchanged.

**Files:**
- Create: `backend/src/main/java/com/tcms/auth/GithubUserClient.java`
- Test: `backend/src/test/java/com/tcms/auth/GithubUserClientTest.java`

- [ ] **Step 1: Write the failing test.** Look at how the §223 tests for
`OidcClient`/`OidcDiscovery` stub HTTP — reuse the same harness (MockWebServer/WireMock or a
stubbed `RestClient.Builder`). The behaviours to pin:

```java
package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GithubUserClientTest {

    // Use the same HTTP-stubbing harness as the existing OidcClient tests. The four cases:

    @Test
    @DisplayName("a happy exchange yields the numeric id as subject, plus name and verified primary email")
    void happyPath() {
        // stub POST /login/oauth/access_token -> {"access_token":"gho_x","token_type":"bearer"}
        // stub GET /user -> {"id": 583231, "login": "octocat", "name": "The Octocat"}
        // stub GET /user/emails -> [{"email":"octocat@github.com","primary":true,"verified":true}]
        // assert subject "583231", name "The Octocat", email "octocat@github.com", verified true
    }

    @Test
    @DisplayName("an error body from the token endpoint is one empty result — GitHub answers 200 with {\"error\":...}")
    void errorBodyIsRefusal() {
        // GitHub's token endpoint answers HTTP 200 with {"error":"bad_verification_code"} —
        // assert Optional.empty(), not an exception and not a half-built identity
    }

    @Test
    @DisplayName("a failed /user/emails read degrades to no email, not to failure")
    void emailLookupIsBestEffort() {
        // 403 on /user/emails (scope withheld) -> identity still returned, email null
    }

    @Test
    @DisplayName("a missing id refuses — the subject is the key and there is no fallback to login or email")
    void noIdNoIdentity() {
    }
}
```

- [ ] **Step 2: Run it, watch it fail to compile.**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.auth.GithubUserClientTest'
```

- [ ] **Step 3: Implement.**

```java
package com.tcms.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * GitHub's half of the social flow — the one provider of the three that is not OIDC: no
 * discovery, no {@code id_token}, no nonce. Identity is the answer of {@code GET /user} made
 * with the token the code exchange yields, and the subject is the account's immutable numeric
 * {@code id} — never {@code login}, which is a rename away from binding somebody else.
 *
 * <p>Protocol only, per §6r trap 5: this class maps HTTP to a record and decides nothing.
 * Every refusal is {@link Optional#empty()}; the caller has one refusal for everything.
 */
@Component
public class GithubUserClient {

    private static final Logger log = LoggerFactory.getLogger(GithubUserClient.class);

    static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String API_BASE = "https://api.github.com";

    /** What GitHub asserted. Email is best-effort provenance (plan §0 decision 13). */
    public record GithubIdentity(String subject, String name, String email, boolean emailVerified) {
    }

    private final RestClient rest;

    GithubUserClient(RestClient.Builder builder) {
        this.rest = builder.build();
    }

    public Optional<GithubIdentity> identityFor(
            String clientId, String clientSecret, String code, String redirectUri) {

        Optional<String> token = exchange(clientId, clientSecret, code, redirectUri);
        return token.flatMap(this::fetchUser);
    }

    private Optional<String> exchange(
            String clientId, String clientSecret, String code, String redirectUri) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            form.add("code", code);
            form.add("redirect_uri", redirectUri);

            Map<?, ?> body = rest.post()
                    .uri(TOKEN_URL)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            // GitHub reports failure as HTTP 200 with an "error" member, so the status alone
            // proves nothing — the token's presence is the success signal.
            Object accessToken = body == null ? null : body.get("access_token");
            if (accessToken instanceof String value && !value.isBlank()) {
                return Optional.of(value);
            }
            log.info("GitHub token exchange refused: {}",
                    body == null ? "empty body" : body.get("error"));
            return Optional.empty();
        } catch (RuntimeException refused) {
            log.info("GitHub token exchange failed: {}", refused.getMessage());
            return Optional.empty();
        }
    }

    private Optional<GithubIdentity> fetchUser(String accessToken) {
        Map<?, ?> user;
        try {
            user = rest.get()
                    .uri(API_BASE + "/user")
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException refused) {
            log.info("GitHub /user read failed: {}", refused.getMessage());
            return Optional.empty();
        }
        Object id = user == null ? null : user.get("id");
        if (id == null) {
            return Optional.empty();
        }
        String name = user.get("name") instanceof String text && !text.isBlank()
                ? text
                : user.get("login") instanceof String login ? login : null;

        VerifiedEmail email = primaryEmail(accessToken);
        return Optional.of(new GithubIdentity(
                String.valueOf(id), name, email.address(), email.verified()));
    }

    private record VerifiedEmail(String address, boolean verified) {
    }

    /** Best-effort: a refusal here (scope withheld, API hiccup) degrades to "no email". */
    private VerifiedEmail primaryEmail(String accessToken) {
        try {
            List<?> emails = rest.get()
                    .uri(API_BASE + "/user/emails")
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(List.class);
            if (emails != null) {
                for (Object entry : emails) {
                    if (entry instanceof Map<?, ?> row
                            && Boolean.TRUE.equals(row.get("primary"))
                            && row.get("email") instanceof String address) {
                        return new VerifiedEmail(address, Boolean.TRUE.equals(row.get("verified")));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            log.info("GitHub /user/emails read failed; proceeding without an email");
        }
        return new VerifiedEmail(null, false);
    }
}
```

(Match the `RestClient`-vs-`RestTemplate`-vs-`WebClient` choice and the timeout configuration to
whatever `OidcClient` actually uses — the existing file is the template, including how it gets
its builder and its connect/read timeouts. If `OidcClient` uses raw `java.net.http.HttpClient`,
write this class the same way.)

- [ ] **Step 4: Run the test, verify PASS.**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.auth.GithubUserClientTest'
```

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/java/com/tcms/auth/GithubUserClient.java \
        backend/src/test/java/com/tcms/auth/GithubUserClientTest.java
git commit -m "social login: GitHub adapter — subject is the numeric id, email is provenance"
```

---

### Task 6: Policy — `SocialIdentityService` (identity module, no network)

**Files:**
- Create: `backend/src/main/java/com/tcms/identity/SocialIdentityService.java`
- Test: `backend/src/test/java/com/tcms/identity/SocialIdentityServiceTest.java` (integration, `IntegrationTestBase`)

- [ ] **Step 1: Write the failing tests.** Integration tests against the real Postgres, in the
style of the `FederatedIdentityService` tests (find them under
`backend/src/test/java/com/tcms/identity/` and copy their fixture-building). The behaviours:

```java
// resolveLogin:
//  - a linked (provider, subject) on an ACTIVE user resolves to that user and stamps
//    last_login_at on both the identity and the user
//  - an unknown subject refuses; a DEACTIVATED user refuses; an INVITED user refuses —
//    all with the one Refused value
//  - email plays no part: a linked identity resolves even when the asserted email now
//    belongs to a different account
// link:
//  - links (provider, subject) to the given user and answers Linked
//  - a subject already bound to ANOTHER user refuses (unique constraint surfaced as policy,
//    not as a 500)
//  - the same subject already bound to the SAME user is idempotent success
//  - a second identity from the same provider for the same user refuses (uq (provider, user))
// unlink:
//  - removes the identity when the account keeps another way in (password, or a second
//    social identity)
//  - refuses (LastCredential) when the account has no password and this is its only
//    social identity — the deliberately conservative guard from plan §0 decision 10
//    (fixture: an AppUser.federate(...) account, which is how company-IdP JIT creates one)
// identitiesOf: lists the caller's identities oldest-first
```

Write them as real `@Test` methods with real fixture users (an `AppUser.invite(...)` +
`activate(...)` for the passworded one, `AppUser.federate(...)` for the passwordless one),
asserting on the repository state after each call.

- [ ] **Step 2: Run them, watch them fail to compile.**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.identity.SocialIdentityServiceTest'
```

- [ ] **Step 3: Implement.**

```java
package com.tcms.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an authenticated social assertion may become — {@link FederatedIdentityService}'s twin
 * for the instance-level providers, and deliberately narrower: it may resolve an existing link
 * or make one for a signed-in caller, and it may <strong>never provision</strong>. Account
 * creation stays with the invitation flow and company-IdP JIT (plan §0 decision 1); a social
 * subject nobody linked is one more cause inside the one refusal.
 *
 * <p>Pure policy over the database — no HTTP client, per §6r trap 5. The protocol half
 * ({@code SocialLoginService}) has verified the assertion before anything here runs.
 *
 * <p>Runs with <strong>no company context</strong>, which is only possible because both tables
 * are instance-level (ERD §0.1, decided in V58).
 */
@Service
public class SocialIdentityService {

    private static final Logger log = LoggerFactory.getLogger(SocialIdentityService.class);

    /** What the provider asserted. Email is provenance, never a key (§6r). */
    public record Claims(String subject, String email, String name) {
    }

    /** Who signed in, or that nobody did — one refusal for every cause. */
    public sealed interface LoginResolution {
        record Refused() implements LoginResolution {
        }
        record Resolved(UUID userId) implements LoginResolution {
        }
    }

    /** The outcome of a link attempt. */
    public sealed interface LinkOutcome {
        record Linked() implements LinkOutcome {
        }
        /** The subject belongs to a different account, or this account already has one from
         * this provider. One value: told apart, a linker could probe whose GitHub is whose. */
        record Refused() implements LinkOutcome {
        }
    }

    /** The outcome of an unlink attempt. */
    public sealed interface UnlinkOutcome {
        record Unlinked() implements UnlinkOutcome {
        }
        /** Removing it would leave the account with no way to sign in. */
        record LastCredential() implements UnlinkOutcome {
        }
    }

    private final UserSocialIdentityRepository identities;
    private final AppUserRepository users;
    private final Clock clock;

    public SocialIdentityService(
            UserSocialIdentityRepository identities, AppUserRepository users, Clock clock) {
        this.identities = identities;
        this.users = users;
        this.clock = clock;
    }

    /** Resolves a verified assertion to the account holding it, or refuses. Never creates. */
    @Transactional
    public LoginResolution resolveLogin(SocialProviderKey provider, Claims claims) {
        if (claims == null || isBlank(claims.subject())) {
            return new LoginResolution.Refused();
        }
        Instant now = clock.instant();

        Optional<UserSocialIdentity> linked =
                identities.findByProviderAndSubject(provider, claims.subject());
        if (linked.isEmpty()) {
            return refuse(provider, "no account holds this subject");
        }
        AppUser user = users.findById(linked.get().getUserId()).orElse(null);
        if (user == null || !user.isActive()) {
            return refuse(provider, "the linked account may not sign in");
        }
        linked.get().recordLogin(now);
        user.recordLogin(now);
        return new LoginResolution.Resolved(user.getId());
    }

    /** Binds {@code (provider, subject)} to {@code userId}. Idempotent for the same pair. */
    @Transactional
    public LinkOutcome link(UUID userId, SocialProviderKey provider, Claims claims) {
        if (claims == null || isBlank(claims.subject())) {
            return new LinkOutcome.Refused();
        }
        Optional<UserSocialIdentity> bySubject =
                identities.findByProviderAndSubject(provider, claims.subject());
        if (bySubject.isPresent()) {
            return bySubject.get().getUserId().equals(userId)
                    ? new LinkOutcome.Linked()
                    : refuseLink(provider, "the subject belongs to a different account");
        }
        if (identities.findByProviderAndUserId(provider, userId).isPresent()) {
            return refuseLink(provider, "the account already has an identity from this provider");
        }
        try {
            identities.saveAndFlush(UserSocialIdentity.link(
                    UUID.randomUUID(),
                    userId,
                    provider,
                    claims.subject(),
                    normalised(claims.email()),
                    clock.instant()));
        } catch (DataIntegrityViolationException raced) {
            // Two callbacks racing past the reads above: the unique constraints decide, and the
            // loser gets the same refusal it would have gotten a millisecond later.
            return refuseLink(provider, "a concurrent link won the race");
        }
        log.info("Linked user {} to social provider {}", userId, provider);
        return new LinkOutcome.Linked();
    }

    /**
     * Removes the link — unless it is the account's only credential. The guard counts the
     * password and the other social identities and deliberately NOT company-IdP identities:
     * {@code user_identity} is RLS-protected per company, so a cross-company count from here
     * would be structurally incomplete, and the safe direction is to refuse (plan §0
     * decision 10).
     */
    @Transactional
    public UnlinkOutcome unlink(UUID userId, SocialProviderKey provider) {
        Optional<UserSocialIdentity> linked = identities.findByProviderAndUserId(provider, userId);
        if (linked.isEmpty()) {
            return new UnlinkOutcome.Unlinked();
        }
        AppUser user = users.findById(userId).orElse(null);
        boolean hasPassword = user != null && user.getPasswordHash() != null;
        if (!hasPassword && identities.countByUserId(userId) <= 1) {
            return new UnlinkOutcome.LastCredential();
        }
        identities.delete(linked.get());
        log.info("Unlinked user {} from social provider {}", userId, provider);
        return new UnlinkOutcome.Unlinked();
    }

    @Transactional(readOnly = true)
    public List<UserSocialIdentity> identitiesOf(UUID userId) {
        return identities.findByUserIdOrderByLinkedAt(userId);
    }

    private LoginResolution refuse(SocialProviderKey provider, String because) {
        log.info("Social sign-in refused against {}: {}", provider, because);
        return new LoginResolution.Refused();
    }

    private LinkOutcome refuseLink(SocialProviderKey provider, String because) {
        log.info("Social link refused against {}: {}", provider, because);
        return new LinkOutcome.Refused();
    }

    private static String normalised(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

- [ ] **Step 4: Run the tests, verify PASS.**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.identity.SocialIdentityServiceTest'
```

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/java/com/tcms/identity/SocialIdentityService.java \
        backend/src/test/java/com/tcms/identity/SocialIdentityServiceTest.java
git commit -m "social login: policy — resolve, link with race backstop, last-credential unlink guard"
```

---

### Task 7: Orchestration — `SocialLoginService` (auth module)

**Files:**
- Create: `backend/src/main/java/com/tcms/auth/SocialLoginService.java`
- Test: `backend/src/test/java/com/tcms/auth/SocialLoginServiceTest.java`

- [ ] **Step 1: Write the failing tests.** Follow the `SsoLoginService` test's structure (stub
the network collaborators — `OidcDiscovery`, `OidcClient`, `OidcIdTokenVerifier`,
`GithubUserClient` — real database for the rest). The behaviours:

```java
// authorize(provider) [LOGIN intent]:
//  - unconfigured provider -> empty
//  - GOOGLE/GITLAB: builds the issuer authorize URL with response_type/scope/state/nonce/
//    PKCE S256, and persists an attempt row with intent LOGIN and the state HASHED
//  - GITHUB: builds https://github.com/login/oauth/authorize with client_id, redirect_uri,
//    scope=read:user user:email, state; attempt persisted the same way
// beginLink(userId, provider): attempt row carries intent LINK and the user id
// callback:
//  - unknown state / expired / second presentation of the same state -> empty (consume
//    claims the row exactly once)
//  - LOGIN + linked subject -> SignedIn with a session; default company resolved like
//    AuthenticationService (memberships.findCompanyIdsForUser)
//  - LOGIN + unknown subject -> Failed (never a provision)
//  - LINK -> Linked; subject already on another account -> Failed
```

- [ ] **Step 2: Run, watch them fail to compile.**

- [ ] **Step 3: Implement.** The class mirrors `SsoLoginService` shape-for-shape; the parts
that differ are shown in full:

```java
package com.tcms.auth;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.InvitationProperties;
import com.tcms.identity.SocialIdentityService;
import com.tcms.identity.SocialProviderKey;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The social authorization-code flow — {@code SsoLoginService}'s twin for the instance-level
 * providers, with two structural differences. First, two intents share the callback: LOGIN and
 * LINK, told apart by the attempt row the {@code state} resolves, never by the URL. Second,
 * GitHub is not OIDC — no discovery, no id_token, no nonce — so its leg exchanges the code and
 * asks its user API instead ({@link GithubUserClient}); GitLab and Google ride the §6r OIDC
 * machinery unchanged.
 *
 * <p>Every refusal is the same refusal, and the browser legs land on one opaque marker.
 */
@Service
public class SocialLoginService {

    private static final Logger log = LoggerFactory.getLogger(SocialLoginService.class);

    private static final int STATE_BYTES = 32;
    private static final int VERIFIER_BYTES = 32;
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";

    /** A session, exactly as local sign-in produces one. */
    public record SignedIn(UUID userId, UUID companyId, SessionTokens.Session session) {
    }

    /** The callback's answer — which landing to build. */
    public sealed interface Outcome {
        record LoggedIn(SignedIn signedIn) implements Outcome {
        }
        record Linked(SocialProviderKey provider) implements Outcome {
        }
        record Failed() implements Outcome {
        }
    }

    private final SocialProperties properties;
    private final SocialLoginAttemptRepository attempts;
    private final SocialIdentityService identities;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final OidcDiscovery discovery;
    private final OidcClient oidc;
    private final OidcIdTokenVerifier verifier;
    private final GithubUserClient github;
    private final SessionTokens tokens;
    private final OidcProperties oidcProperties;
    private final InvitationProperties spa;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SocialLoginService(
            SocialProperties properties,
            SocialLoginAttemptRepository attempts,
            SocialIdentityService identities,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            OidcDiscovery discovery,
            OidcClient oidc,
            OidcIdTokenVerifier verifier,
            GithubUserClient github,
            SessionTokens tokens,
            OidcProperties oidcProperties,
            InvitationProperties spa,
            TransactionTemplate transactions,
            Clock clock) {
        this.properties = properties;
        this.attempts = attempts;
        this.identities = identities;
        this.users = users;
        this.memberships = memberships;
        this.discovery = discovery;
        this.oidc = oidc;
        this.verifier = verifier;
        this.github = github;
        this.tokens = tokens;
        this.oidcProperties = oidcProperties;
        this.spa = spa;
        this.transactions = transactions;
        this.clock = clock;
    }

    public List<SocialProviderKey> configuredProviders() {
        return properties.configuredProviders();
    }

    /** The login leg — anyone on the internet may start one. */
    public Optional<String> authorize(SocialProviderKey provider) {
        return begin(provider, SocialLoginAttempt.Intent.LOGIN, null);
    }

    /** The link leg — the controller has authenticated {@code userId} already. */
    public Optional<String> beginLink(SocialProviderKey provider, UUID userId) {
        return begin(provider, SocialLoginAttempt.Intent.LINK, userId);
    }

    private Optional<String> begin(
            SocialProviderKey provider, SocialLoginAttempt.Intent intent, UUID userId) {

        if (!properties.configured(provider)) {
            log.info("Social authorize refused: {} is not configured", provider);
            return Optional.empty();
        }
        String state = randomToken(STATE_BYTES);
        String nonce = randomToken(STATE_BYTES);
        String verifierValue = randomToken(VERIFIER_BYTES);
        String redirectUri = oidcProperties.socialRedirectUri();
        Instant now = clock.instant();

        Optional<String> url = switch (provider) {
            case GITHUB -> githubAuthorizeUrl(state, redirectUri);
            case GITLAB, GOOGLE -> oidcAuthorizeUrl(provider, state, nonce, verifierValue, redirectUri);
        };
        if (url.isEmpty()) {
            return Optional.empty();
        }
        transactions.executeWithoutResult(status -> attempts.saveAndFlush(SocialLoginAttempt.begin(
                UUID.randomUUID(),
                provider,
                intent,
                userId,
                digest(state),
                verifierValue,
                nonce,
                redirectUri,
                now,
                now.plus(oidcProperties.attemptValidity()))));
        return url;
    }

    private Optional<String> githubAuthorizeUrl(String state, String redirectUri) {
        SocialProperties.Client client = properties.clientFor(SocialProviderKey.GITHUB);
        return Optional.of(GithubUserClient.AUTHORIZE_URL
                + "?client_id=" + encoded(client.clientId())
                + "&redirect_uri=" + encoded(redirectUri)
                + "&scope=" + encoded("read:user user:email")
                + "&state=" + encoded(state));
    }

    private Optional<String> oidcAuthorizeUrl(
            SocialProviderKey provider, String state, String nonce, String verifierValue,
            String redirectUri) {

        return discovery.forIssuer(issuerOf(provider)).map(document ->
                document.authorizationEndpoint()
                        + "?response_type=code"
                        + "&client_id=" + encoded(properties.clientFor(provider).clientId())
                        + "&redirect_uri=" + encoded(redirectUri)
                        + "&scope=" + encoded("openid email profile")
                        + "&state=" + encoded(state)
                        + "&nonce=" + encoded(nonce)
                        + "&code_challenge=" + encoded(challengeFor(verifierValue))
                        + "&code_challenge_method=S256");
    }

    private String issuerOf(SocialProviderKey provider) {
        return switch (provider) {
            case GOOGLE -> GOOGLE_ISSUER;
            case GITLAB -> properties.gitlabBaseUrl();
            case GITHUB -> throw new IllegalArgumentException("GitHub is not an OIDC issuer");
        };
    }

    /** One callback for both intents — the attempt row decides which (plan §0 decision 11). */
    public Outcome callback(String state, String code) {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            return new Outcome.Failed();
        }
        Instant now = clock.instant();
        SocialLoginAttempt attempt = transactions.execute(status ->
                attempts.findByStateHash(digest(state)).orElse(null));
        if (attempt == null || !attempt.isUsable(now)) {
            log.info("Social callback refused: no usable attempt for the presented state");
            return new Outcome.Failed();
        }
        Integer claimed = transactions.execute(status -> attempts.consume(attempt.getId(), now));
        if (claimed == null || claimed != 1) {
            log.info("Social callback refused: attempt {} was already consumed", attempt.getId());
            return new Outcome.Failed();
        }

        Optional<SocialIdentityService.Claims> claims = assertIdentity(attempt, code);
        if (claims.isEmpty()) {
            return new Outcome.Failed();
        }
        return switch (attempt.getIntent()) {
            case LOGIN -> completeLogin(attempt.getProvider(), claims.get());
            case LINK -> completeLink(attempt, claims.get());
        };
    }

    /** The protocol step: code in, verified claims out. Provider-shaped, policy-free. */
    private Optional<SocialIdentityService.Claims> assertIdentity(
            SocialLoginAttempt attempt, String code) {

        SocialProperties.Client client = properties.clientFor(attempt.getProvider());
        if (client == null || !client.configured()) {
            return Optional.empty();
        }
        if (attempt.getProvider() == SocialProviderKey.GITHUB) {
            return github.identityFor(
                            client.clientId(), client.clientSecret(), code, attempt.getRedirectUri())
                    .map(identity -> new SocialIdentityService.Claims(
                            identity.subject(), identity.email(), identity.name()));
        }
        String issuer = issuerOf(attempt.getProvider());
        Optional<OidcDiscoveryDocument> document = discovery.forIssuer(issuer);
        if (document.isEmpty()) {
            return Optional.empty();
        }
        Optional<OidcTokenResponse> exchanged = oidc.exchange(
                document.get().tokenEndpoint(),
                client.clientId(),
                client.clientSecret(),
                code,
                attempt.getRedirectUri(),
                attempt.getCodeVerifier());
        if (exchanged.isEmpty()) {
            return Optional.empty();
        }
        // providerId is only a log handle in Expected; a stable name-based UUID keeps the log
        // lines attributable without inventing a database row for an instance-level provider.
        Optional<Jwt> verified = verifier.verify(
                new OidcIdTokenVerifier.Expected(
                        UUID.nameUUIDFromBytes(("social:" + attempt.getProvider())
                                .getBytes(StandardCharsets.UTF_8)),
                        issuer,
                        client.clientId(),
                        document.get().jwksUri(),
                        attempt.getNonce()),
                exchanged.get().idToken());
        return verified.map(idToken -> new SocialIdentityService.Claims(
                idToken.getSubject(),
                idToken.getClaimAsString("email"),
                idToken.getClaimAsString("name")));
    }

    private Outcome completeLogin(SocialProviderKey provider, SocialIdentityService.Claims claims) {
        SocialIdentityService.LoginResolution resolution = identities.resolveLogin(provider, claims);
        if (!(resolution instanceof SocialIdentityService.LoginResolution.Resolved resolved)) {
            return new Outcome.Failed();
        }
        SignedIn signedIn = transactions.execute(status -> issueFor(resolved.userId()));
        return signedIn == null ? new Outcome.Failed() : new Outcome.LoggedIn(signedIn);
    }

    private Outcome completeLink(SocialLoginAttempt attempt, SocialIdentityService.Claims claims) {
        SocialIdentityService.LinkOutcome outcome =
                identities.link(attempt.getUserId(), attempt.getProvider(), claims);
        return outcome instanceof SocialIdentityService.LinkOutcome.Linked
                ? new Outcome.Linked(attempt.getProvider())
                : new Outcome.Failed();
    }

    /** Default company exactly as {@code AuthenticationService} resolves it — server-side. */
    private SignedIn issueFor(UUID userId) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        UUID companyId = memberships.findCompanyIdsForUser(userId).stream().findFirst().orElse(null);
        log.info("Signed in user {} via social with active company {}", userId, companyId);
        return new SignedIn(userId, companyId,
                tokens.issue(userId, companyId, user.getPermissionVersion(), false));
    }

    /** One destination and one opaque marker for every cause. */
    public String failureUrl() {
        return spaOrigin() + oidcProperties.spaCallbackPath() + "#error=sso_failed";
    }

    /** The fragment, never the query string — {@code SsoLoginService#landingUrl}'s reasoning. */
    public String landingUrl(SignedIn signedIn) {
        return spaOrigin() + oidcProperties.spaCallbackPath()
                + "#token=" + encoded(signedIn.session().token())
                + "&expiresAt=" + encoded(signedIn.session().expiresAt().toString());
    }

    /** Where a completed LINK lands: back on the security page, marker in the fragment. */
    public String linkedUrl(SocialProviderKey provider) {
        return spaOrigin() + oidcProperties.spaCallbackPath()
                + "#linked=" + encoded(provider.name());
    }

    private String spaOrigin() {
        return spa.baseUrl().toString().replaceAll("/+$", "");
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String challengeFor(String verifier) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String digest(String value) {
        StringBuilder hex = new StringBuilder();
        for (byte b : sha256(value.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", impossible);
        }
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
```

Supporting change this class assumes — make it in the same task:

**`OidcProperties.socialRedirectUri()`** — the social callback's absolute URL, sibling of the
existing `redirectUri()`. Derive it from the same base the existing one uses, path
`/api/v1/auth/social/callback` (read `OidcProperties` first and copy how `redirectUri` is
built/configured; if it is a full configured URL rather than derived, add a
`tcms.auth.oidc.social-redirect-uri` property beside it with the same spelling rules).

- [ ] **Step 4: Run the tests, verify PASS.**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.auth.SocialLoginServiceTest'
```

- [ ] **Step 5: Commit.**

```bash
git add backend/src/main/java/com/tcms/auth/SocialLoginService.java \
        backend/src/test/java/com/tcms/auth/SocialLoginServiceTest.java \
        backend/src/main/java/com/tcms/auth/OidcProperties.java \
        backend/src/main/resources/application.yml
git commit -m "social login: orchestration — two intents, one callback, one refusal"
```

---

### Task 8: Controllers + endpoint access

**Files:**
- Create: `backend/src/main/java/com/tcms/auth/SocialController.java`
- Create: `backend/src/main/java/com/tcms/auth/SocialIdentityController.java`
- Modify: `backend/src/test/java/com/tcms/auth/EndpointAccessTest.java` (find its exact path with
  `grep -r "EndpointAccessTest" backend/src/test --include='*.java' -l`)
- Test: `backend/src/test/java/com/tcms/auth/SocialControllerTest.java`

- [ ] **Step 1: Write the failing endpoint tests** — MockMvc/integration in the style of the
existing `SsoController` tests:

```java
// GET  /api/v1/auth/social/providers        -> 200 {"providers":[...]} (empty when unconfigured)
// GET  /api/v1/auth/social/GITHUB/authorize -> 302 to github.com when configured,
//                                              302 to the failure landing when not
// GET  /api/v1/auth/social/callback         -> 302 always (failure marker on garbage)
// GET  /api/v1/me/social-identities         -> 401 unauthenticated; 200 list authenticated
// POST /api/v1/me/social-identities/GITHUB  -> 401 unauthenticated; 200 {"url":...} authed;
//                                              404 when the provider is unconfigured
// DELETE /api/v1/me/social-identities/GITHUB -> 204; 409 problem body for the last credential
// An invalid {provider} path segment        -> 404, not 500 (enum conversion)
```

- [ ] **Step 2: Run, watch them fail.**

- [ ] **Step 3: Implement the public controller:**

```java
package com.tcms.auth;

import com.tcms.identity.SocialProviderKey;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public social surfaces — reachable with no session, which is the point, and each route
 * added to {@code EndpointAccessTest}'s public set as the deliberate act that widening the
 * attack surface requires ({@link SsoController}'s rule).
 *
 * <p>{@code /providers} may answer publicly because it names instance configuration only —
 * no company, no account — so §6r's oracle concern does not reach it. The browser legs answer
 * 302 in every case, one opaque marker for every failure.
 */
@RestController
@RequestMapping("/api/v1/auth/social")
public class SocialController {

    private final SocialLoginService social;

    public SocialController(SocialLoginService social) {
        this.social = social;
    }

    public record SocialProviderList(List<SocialProviderKey> providers) {
    }

    public record SocialRedirect(String url) {
    }

    @GetMapping("/providers")
    public ResponseEntity<SocialProviderList> providers() {
        return ResponseEntity.ok(new SocialProviderList(social.configuredProviders()));
    }

    @GetMapping("/{provider}/authorize")
    public ResponseEntity<Void> authorize(@PathVariable SocialProviderKey provider) {
        return redirectTo(social.authorize(provider).orElseGet(social::failureUrl));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "code", required = false) String code) {

        return redirectTo(switch (social.callback(state, code)) {
            case SocialLoginService.Outcome.LoggedIn loggedIn -> social.landingUrl(loggedIn.signedIn());
            case SocialLoginService.Outcome.Linked linked -> social.linkedUrl(linked.provider());
            case SocialLoginService.Outcome.Failed ignored -> social.failureUrl();
        });
    }

    private static ResponseEntity<Void> redirectTo(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
```

(Mind the mapping order: `/providers` and `/callback` are literal segments that must win over
`/{provider}/authorize` — Spring resolves literal-over-variable, but the invalid-enum 404 test
in Step 1 is what proves it.)

- [ ] **Step 4: Implement the authenticated controller.** Read `MeController` first and copy
exactly how it obtains the caller (`CurrentUserService`) and how other controllers shape a 409
`Problem`:

```java
package com.tcms.auth;

import com.tcms.api.Problem;
import com.tcms.identity.SocialIdentityService;
import com.tcms.identity.SocialProviderKey;
import com.tcms.identity.UserSocialIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's own linked social identities — under {@code /me} and not {@code /auth}, because
 * {@code /api/v1/auth/**} is permitAll and everything here requires the session it manages.
 * No permission key gates any of it: managing your own sign-in identities is self-service on
 * your own account, like changing a password (plan §0 decision 12).
 *
 * <p>The link leg is a POST answering {@code {url}} rather than a 302: a top-level browser
 * navigation cannot carry the Authorization header, so the authenticated step must be an API
 * call and the navigation happens after, holding only the single-use {@code state}.
 */
@RestController
@RequestMapping("/api/v1/me/social-identities")
public class SocialIdentityController {

    private final SocialIdentityService identities;
    private final SocialLoginService social;
    private final CurrentUserService currentUser;

    public SocialIdentityController(
            SocialIdentityService identities,
            SocialLoginService social,
            CurrentUserService currentUser) {
        this.identities = identities;
        this.social = social;
        this.currentUser = currentUser;
    }

    public record SocialIdentityView(
            SocialProviderKey provider, Instant linkedAt, Instant lastLoginAt, String emailAtLink) {

        static SocialIdentityView of(UserSocialIdentity identity) {
            return new SocialIdentityView(
                    identity.getProvider(),
                    identity.getLinkedAt(),
                    identity.getLastLoginAt(),
                    identity.getEmailAtLink());
        }
    }

    public record SocialIdentityList(List<SocialIdentityView> identities) {
    }

    @GetMapping
    public ResponseEntity<SocialIdentityList> list() {
        return ResponseEntity.ok(new SocialIdentityList(
                identities.identitiesOf(currentUser.requiredUserId()).stream()
                        .map(SocialIdentityView::of)
                        .toList()));
    }

    @PostMapping("/{provider}")
    public ResponseEntity<SocialController.SocialRedirect> beginLink(
            @PathVariable SocialProviderKey provider) {

        Optional<String> url = social.beginLink(provider, currentUser.requiredUserId());
        return url.map(value -> ResponseEntity.ok(new SocialController.SocialRedirect(value)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Problem> unlink(@PathVariable SocialProviderKey provider) {
        return switch (identities.unlink(currentUser.requiredUserId(), provider)) {
            case SocialIdentityService.UnlinkOutcome.Unlinked ignored ->
                    ResponseEntity.noContent().build();
            case SocialIdentityService.UnlinkOutcome.LastCredential ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(Problem.conflict(
                            "This identity cannot be removed",
                            "It is this account's only way to sign in. Set a password or link "
                                    + "another provider first."));
        };
    }
}
```

(`currentUser.requiredUserId()` and `Problem.conflict(...)` — verify the real method names in
`CurrentUserService` and `Problem` and use those; the shapes above are the intent.)

- [ ] **Step 5: Register the new public routes in `EndpointAccessTest`'s public set** —
`/api/v1/auth/social/providers`, `/api/v1/auth/social/{provider}/authorize`,
`/api/v1/auth/social/callback` — in whatever form that test enumerates them, and confirm the
`/me/social-identities` routes land in the authenticated set (they should by default; the test
failing-first tells you).

- [ ] **Step 6: Run the controller + access tests, verify PASS.**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.auth.SocialControllerTest' \
    --tests 'com.tcms.auth.EndpointAccessTest'
```

- [ ] **Step 7: Run the whole backend gate.**

```bash
~/bin/tcms-remote backend ./gradlew build
```

Expected: green — `-Werror`, ArchUnit (identity gained no network dependency), Modulith, and
the full suite.

- [ ] **Step 8: Commit.**

```bash
git add backend/src/main/java/com/tcms/auth/SocialController.java \
        backend/src/main/java/com/tcms/auth/SocialIdentityController.java \
        backend/src/test/java/com/tcms/auth/SocialControllerTest.java \
        backend/src/test/java/com/tcms/auth/EndpointAccessTest.java
git commit -m "social login: controllers — public auth surface, /me link management, access test entries"
```

---

### Task 9: SPA data layer — hooks in `api/queries/identity.ts`

**Files:**
- Modify: `frontend/src/api/queries/identity.ts`
- Modify: `frontend/src/api/queries/keys.ts` (two new keys)
- Modify: `frontend/src/test/fixtures.ts` (factories for the new schemas)

- [ ] **Step 1: Add fixture factories** to `frontend/src/test/fixtures.ts` in its established
style (complete objects, every required member):

```ts
export function socialProviderList(
  overrides: Partial<SocialProviderList> = {},
): SocialProviderList {
  return { providers: ['GITHUB', 'GITLAB', 'GOOGLE'], ...overrides };
}

export function socialIdentity(overrides: Partial<SocialIdentity> = {}): SocialIdentity {
  return {
    provider: 'GITHUB',
    linkedAt: '2026-08-16T10:00:00Z',
    lastLoginAt: null,
    emailAtLink: 'octocat@github.com',
    ...overrides,
  };
}

export function socialIdentityList(
  overrides: Partial<SocialIdentityList> = {},
): SocialIdentityList {
  return { identities: [socialIdentity()], ...overrides };
}

export function socialRedirect(overrides: Partial<SocialRedirect> = {}): SocialRedirect {
  return { url: 'https://github.com/login/oauth/authorize?client_id=x&state=y', ...overrides };
}
```

(Import the types from `../api/types` or wherever the file sources generated names — match its
imports.)

- [ ] **Step 2: Add the hooks** to `frontend/src/api/queries/identity.ts`, matching the file's
conventions exactly (the `useSsoProviders` block at line ~132 is the template):

```ts
/** Which social sign-in buttons this instance offers. Instance configuration — cache freely. */
export function useSocialProviders(): UseQueryResult<SocialProviderList, Error> {
  return useQuery({
    queryKey: keys.socialProviders,
    queryFn: ({ signal }) => api.get<SocialProviderList>('/auth/social/providers', { signal }),
    staleTime: Infinity,
  });
}

/** The calling account's linked identities, for the security page. */
export function useMySocialIdentities(): UseQueryResult<SocialIdentityList, Error> {
  return useQuery({
    queryKey: keys.mySocialIdentities,
    queryFn: ({ signal }) => api.get<SocialIdentityList>('/me/social-identities', { signal }),
  });
}

/** Begins linking: the answer is a URL to NAVIGATE to (top-level), not to fetch. */
export function useStartSocialLink(): UseMutationResult<SocialRedirect, Error, SocialProvider> {
  return useMutation({
    mutationFn: (provider: SocialProvider) =>
      api.post<SocialRedirect>(`/me/social-identities/${provider}`),
  });
}

export function useUnlinkSocialIdentity(): UseMutationResult<void, Error, SocialProvider> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (provider: SocialProvider) =>
      api.delete<void>(`/me/social-identities/${provider}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.mySocialIdentities }),
  });
}
```

Add to `keys.ts` in its factory style: `socialProviders` and `mySocialIdentities`.
(If `api.post` requires an explicit body argument, pass `undefined` — copy the nearest bodyless
POST in the codebase. Match `api.delete`'s real name — grep the client.)

- [ ] **Step 3: Run the frontend gates on shanks** — the contract harness (§197–§199) now
checks every mocked exchange against the new operations:

```bash
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
```

Expected: green (no consumer yet renders the hooks; fixtures compile against the generated
types, which proves the contract shapes).

- [ ] **Step 4: Commit.**

```bash
git add frontend/src/api/queries/identity.ts frontend/src/api/queries/keys.ts \
        frontend/src/test/fixtures.ts
git commit -m "social login: SPA data layer — providers, identities, link/unlink"
```

---

### Task 10: Sign-in page buttons

**Files:**
- Modify: `frontend/src/features/auth/LoginPage.tsx`
- Modify: `frontend/src/lib/labels.ts`
- Test: `frontend/src/features/auth/LoginPage.test.tsx` (extend)

- [ ] **Step 1: Write the failing tests** (mock `/auth/social/providers` through the standard
harness; anchor path-keyed mock arms so `/auth/social/providers` is not swallowed by a
`/auth/providers` prefix arm — use `/^\/auth\/social\/providers$/`):

```tsx
// - three configured providers -> three buttons, labelled "Continue with GitHub" / GitLab /
//   Google, each an <a> whose href is `${BASE}/auth/social/${provider}/authorize`
// - an empty providers list -> no social section at all (no divider, no heading)
// - clicking a social button remembers the SSO return path (same rememberSsoReturnPath call
//   the company-SSO buttons make) — assert via the module mock
```

- [ ] **Step 2: Run, watch them fail:**

```bash
~/bin/tcms-remote frontend npm run test:run -- LoginPage
```

- [ ] **Step 3: Implement.** In `LoginPage.tsx`, call `useSocialProviders()` at the top of the
component; render below the existing email-discovery-driven provider section (line ~407),
sharing its button styling. The essential shape (adapt markup to the page's existing MUI
idiom — the company-SSO button at line ~443 is the template, including how it builds `BASE` and
calls `rememberSsoReturnPath`):

```tsx
const social = useSocialProviders();
const socialProviders = social.data?.providers ?? [];
```

```tsx
{socialProviders.length > 0 && (
  <Stack spacing={1.5} sx={{ mt: 3 }}>
    <Divider>or</Divider>
    {socialProviders.map((provider) => (
      <Button
        key={provider}
        component="a"
        variant="outlined"
        fullWidth
        href={`${BASE}/auth/social/${provider}/authorize`}
        onClick={() => rememberSsoReturnPath(location)}
      >
        Continue with {SOCIAL_LABELS[provider]}
      </Button>
    ))}
  </Stack>
)}
```

Put the label map in `lib/labels.ts` (the shared-layer rule: enum display text lives there, and
`satisfies` makes a new enum member a compile error until labeled):

```ts
export const SOCIAL_LABELS = {
  GITHUB: 'GitHub',
  GITLAB: 'GitLab',
  GOOGLE: 'Google',
} satisfies Record<SocialProvider, string>;
```

- [ ] **Step 4: Run the page tests + gates, verify PASS.**

```bash
~/bin/tcms-remote frontend npm run test:run -- LoginPage
~/bin/tcms-remote frontend npm run build && ~/bin/tcms-remote frontend npm run lint
```

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/features/auth/LoginPage.tsx frontend/src/features/auth/LoginPage.test.tsx \
        frontend/src/lib/labels.ts
git commit -m "social login: sign-in buttons for the configured providers"
```

---

### Task 11: Callback landing — the `#linked=` marker

**Files:**
- Modify: `frontend/src/features/auth/SsoCallbackPage.tsx`
- Test: `frontend/src/features/auth/SsoCallbackPage.test.tsx` (extend)

- [ ] **Step 1: Write the failing tests:**

```tsx
// - a fragment of "#linked=GITHUB" navigates to /account/security with state
//   { linked: 'GITHUB' } and does NOT touch the session
// - "#error=sso_failed" keeps its existing behaviour (regression pin — the social flow
//   reuses the same marker so login failures render the existing message)
// - "#token=...&expiresAt=..." keeps its existing behaviour (regression pin — social login
//   lands here exactly like company SSO)
```

- [ ] **Step 2: Run, watch the new one fail.**

- [ ] **Step 3: Implement** — in the page's fragment-parsing effect, before the token branch:

```tsx
const linked = params.get('linked');
if (linked) {
  navigate('/account/security', { replace: true, state: { linked } });
  return;
}
```

(Match how the file already parses the fragment — it has a `URLSearchParams` over
`location.hash.slice(1)` or equivalent; reuse it.)

- [ ] **Step 4: Run tests + gates, verify PASS. Commit.**

```bash
git add frontend/src/features/auth/SsoCallbackPage.tsx \
        frontend/src/features/auth/SsoCallbackPage.test.tsx
git commit -m "social login: callback landing routes a completed link to the security page"
```

---

### Task 12: Auto-login after acceptance — register → signed in → security page

The acceptance endpoint answers **204 with no session** (deliberate — an invitation link must
never double as a credential) and stays that way. The hop is removed in the SPA instead: on the
204 for a *new* account, the page holds a complete valid credential — `preview.email` plus the
password the person just typed — so it signs them in itself with the ordinary
`POST /auth/login`, then lands the session on the security page's first-run mode. The sign-in
page remains only as the fallback for a failed auto-login.

**Files:**
- Modify: `frontend/src/features/auth/AcceptInvitationPage.tsx`
- Modify: `frontend/src/features/auth/LoginPage.tsx` (only if it lacks a post-login
  destination seam — see Step 3; needed for the fallback leg)
- Test: `frontend/src/features/auth/AcceptInvitationPage.test.tsx` and
  `frontend/src/features/auth/LoginPage.test.tsx` (extend)

- [ ] **Step 1: Read the two seams this reuses.** (a) The sign-in mutation and the
session-establishment call `LoginPage` makes on success — grep `LoginPage.tsx` for the login
hook (in `api/queries/identity.ts`) and for how the returned `AuthResult` reaches the session
context; the accept page must go through the *same* calls, not a private copy. (b) How
`LoginPage` decides where a successful sign-in lands (a `location.state`, a remembered return
path, or a default) — the fallback leg uses it; only if local sign-in has *no* destination seam
do you add one (a `location.state.next` honoured after successful password sign-in, validated
to be a same-app path — starts with `/`, not `//` — so the sign-in page cannot become an open
redirector).

- [ ] **Step 2: Write the failing tests:**

```tsx
// AcceptInvitationPage — the auto-login leg (preview.passwordRequired true):
// - after the 204, the page POSTs /auth/login with preview.email and the typed password,
//   establishes the session through the same seam LoginPage uses, and navigates to
//   /account/security?welcome=1 when providers are configured
// - with NO providers configured: same auto-login, but navigation goes to the app's default
//   landing — the extra hop is gone either way
// - auto-login FAILS (mock /auth/login refusing): the page falls back to the sign-in route
//   with the post-login destination set to /account/security?welcome=1 (providers configured)
//   and shows no scarier state than the normal "please sign in" — the account exists and the
//   acceptance succeeded, so the copy must not suggest otherwise
// - NOT preview.passwordRequired (existing user, second company): no auto-login attempt at
//   all (there is no password to send), existing success behaviour (regression pin)
// LoginPage:
// - a successful password sign-in with a pending destination lands on it; without one, the
//   existing default landing (regression pin)
```

- [ ] **Step 3: Implement.** In `AcceptInvitationPage`'s success handler (the branch that runs
on a 204 from accept), when `preview.passwordRequired`:

```tsx
const destination =
  socialProviders.length > 0 ? '/account/security?welcome=1' : DEFAULT_LANDING;

login.mutate(
  { email: preview.email, password: typedPassword },
  {
    onSuccess: (auth) => {
      establishSession(auth); // the exact seam LoginPage uses — Step 1(a)
      navigate(destination, { replace: true });
    },
    onError: () => {
      navigate('/login', { replace: true, state: { next: destination } });
    },
  },
);
```

(`DEFAULT_LANDING`, the login hook's real name, `establishSession`'s real form and the sign-in
route are whatever Step 1 found — the block above is the control flow, the seams are the
page's own. Keep the typed password in component scope only; it is already there for the form.)
In `LoginPage`, honour the fallback destination in the password sign-in success path only,
guarded:

```tsx
const next = navState?.next;
const target = next && next.startsWith('/') && !next.startsWith('//') ? next : DEFAULT_LANDING;
navigate(target, { replace: true });
```

- [ ] **Step 4: Run tests + gates, verify PASS. Commit.**

```bash
~/bin/tcms-remote frontend npm run test:run -- AcceptInvitationPage LoginPage
git add frontend/src/features/auth/AcceptInvitationPage.tsx \
        frontend/src/features/auth/AcceptInvitationPage.test.tsx \
        frontend/src/features/auth/LoginPage.tsx \
        frontend/src/features/auth/LoginPage.test.tsx
git commit -m "social login: auto-login after acceptance, straight to the security page"
```

---

### Task 13: The security page — link and unlink

**Files:**
- Create: `frontend/src/features/account/AccountSecurityPage.tsx`
- Create: `frontend/src/features/account/AccountSecurityPage.test.tsx`
- Modify: `frontend/src/app/router.tsx` (route `/account/security`)
- Modify: `frontend/src/app/AppShell.tsx` (entry in the user/account menu — find where "sign
  out" lives and add "Security" beside it)

- [ ] **Step 1: Write the failing tests:**

```tsx
// - renders one row per configured provider: linked rows show linkedAt (via lib/format)
//   and an Unlink button; unlinked rows show a Link button
// - Link: POSTs /me/social-identities/{provider}, navigates to the returned url
// - Unlink: confirmation dialog first (the app's existing confirm pattern), then DELETE,
//   then the list refetches
// - a 409 on unlink surfaces the Problem detail through the standard error presentation
//   (drivingAFailedWrite() hatch + say why, per §202, if the test asserts the refusal)
// - arriving with location.state.linked = 'GITHUB' shows a success notification
// - arriving with ?welcome=1 (the first-run route from Task 12) renders the welcome intro
//   ("Want to sign in with GitHub next time? Link it now — or skip") and a Skip button that
//   navigates to the app's default landing; without the param, no intro and no Skip
//   (filter state in the URL per the house rule — useSearchParams, not component state)
// - NOTE the param does NOT survive the OAuth round-trip: the backend's link landing carries
//   only #linked=, so returning from a completed link shows the success notification without
//   the intro — that is correct behaviour, pin it (the person continues via the app shell)
// - no providers configured -> explains that no social providers are configured
```

- [ ] **Step 2: Run, watch them fail.**

- [ ] **Step 3: Implement the page** with `useSocialProviders`, `useMySocialIdentities`,
`useStartSocialLink`, `useUnlinkSocialIdentity`; `DataRegion` for the load states;
`SOCIAL_LABELS` from `lib/labels.ts`; dates through `lib/format.ts`; the first-run mode read
from `useSearchParams().get('welcome')` (URL state, per the house rule), rendering the intro
copy and a Skip button that navigates to the app's default landing. Wire the route in
`router.tsx` (an authenticated, non-project-scoped route — put it beside whatever other
session-level routes exist) and the menu entry in `AppShell.tsx`.

- [ ] **Step 4: Run the full frontend gates, verify PASS.**

```bash
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
```

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/features/account/ frontend/src/app/router.tsx frontend/src/app/AppShell.tsx
git commit -m "social login: account security page — linked identities, link and unlink"
```

---

### Task 14: Deployment wiring + docs + verification-queue rows

**Files:**
- Modify: `.env.example`, `compose.yaml` (backend service env passthrough)
- Modify: `docs/verification/frontend-browser-checks.md`, `docs/verification/backend-mutation-checks.md`
- Create: `docs/handoff/NN-social-login-and-account-linking.md` (next free § number — check the index)
- Modify: `docs/HANDOFF.md` (one index row), `docs/agent-context/be-java.md`, `docs/agent-context/fe-react.md`

- [ ] **Step 1: Compose + env.** Add the seven optional variables to `.env.example` (commented,
with a one-line note that absent means the button does not exist) and pass them through the
backend service's `environment:` in `compose.yaml` using the **Compose** optional form
(`TCMS_SOCIAL_GITHUB_CLIENT_ID: ${TCMS_SOCIAL_GITHUB_CLIENT_ID:-}`) — the `:-` spelling is
correct in compose files and wrong in `application*.yml` (§108; Task 4 handled the Spring side).

- [ ] **Step 2: Verification-queue rows — write, run nothing (§65).** Frontend queue, in the
lane whose state each needs, stable ids quoted from the handoff section:

```
- [ ] social-1: with all three providers configured, the sign-in page shows three "Continue
      with" buttons; with none, no social section renders and a fresh acceptance lands on the
      app's default page, not the security page.
- [ ] social-1b: a brand-new invitee (password chosen at accept) is signed in immediately —
      no manual login — and lands on /account/security?welcome=1 with the intro and a working
      one-click Skip; an existing user accepting a second-company invitation gets neither the
      auto-login nor the welcome page.
- [ ] social-1c: with the backend refusing /auth/login once (tcmsFaults.expire() armed after
      the accept form loads, or equivalent), acceptance still succeeds and the page falls back
      to the sign-in form with the welcome destination preserved after manual sign-in.
- [ ] social-2: a full GitHub round-trip against a real GitHub OAuth app: link from
      /account/security, sign out, sign back in with the GitHub button, land on the
      remembered return path. Needs: an operator-created GitHub OAuth app pointing at the
      stack's callback; not creatable by any lane's fixtures — if absent, record
      "not driven: no OAuth app", and drive social-5 instead.
- [ ] social-3: linking a second provider from /account/security round-trips and the row
      flips to Unlink; unlinking it flips back.
- [ ] social-4: unlinking the only identity of a passwordless (JIT-provisioned) account is
      refused with the explanatory message.
- [ ] social-5: a callback with a garbage state lands on the sign-in page with the single
      SSO failure message (drive by pasting the callback URL directly).
```

Backend queue:

```
- [ ] social-b1: replaying a consumed social callback state answers the failure redirect and
      writes nothing (attempt consumed_at unchanged, no session).
- [ ] social-b2: two concurrent LINK callbacks for the same subject against two different
      accounts: exactly one user_social_identity row exists afterwards.
```

- [ ] **Step 3: The handoff section file** — what shipped, the §0 decision table (especially
the non-collision table, the "no auto-link" decision, and the Appendix A cut with its
comparison), what is built-and-unverified (the queue rows), and the GitHub-adapter caveat
(error-in-200-body). One index row in `docs/HANDOFF.md`. Update both agent-context caches in
this same commit (the seam list gains: social protocol/policy split, the instance-level
exception pair, the intent-in-the-attempt-row pattern).

- [ ] **Step 4: Full gates, all three stacks, on shanks:**

```bash
~/bin/tcms-remote backend ./gradlew build
~/bin/tcms-remote frontend npm run build && ~/bin/tcms-remote frontend npm run lint \
    && ~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run build && ~/bin/tcms-remote packages npm run lint --workspaces \
    && ~/bin/tcms-remote packages npm run test:run --workspaces
```

Expected: all green.

- [ ] **Step 5: Commit, then push** (per the push-after-each-lane agreement):

```bash
git add .env.example compose.yaml docs/verification/ docs/handoff/ docs/HANDOFF.md docs/agent-context/
git commit -m "social login: deployment env, verification rows, handoff §NN"
git push
```

---

## Appendix A — Deferred: invitation-time social acceptance

The first draft of this plan (same day) let an invitee redeem the emailed link **with** a
social identity instead of choosing a password: an "or accept with GitHub" button on
`/accept-invitation`, a `POST /auth/invitations/{token}/social/{provider}` begin leg, a third
`INVITE` attempt intent carrying the invitation id, and — the load-bearing piece — **one
transaction** that consumed the invitation, activated the account passwordless
(`AppUser.activateFederated`, a hash-less `activate`), linked the identity, and minted the
session, so that a failed link rolled the acceptance back (a consumed invitation whose link
failed would otherwise be an active passwordless account nobody can sign in to).

**Cut because the complexity concentrated exactly there.** The comparison that decided it:

| | Invite-with-social | Accept-with-password, link later (this plan) |
|---|---|---|
| `InvitationService` / `AppUser` | 3 new methods, incl. a second redemption arm | untouched |
| Attempt model | 3 intents + `invitation_id` + a 3-way shape constraint | 2 intents |
| Cross-aggregate transaction | required, with rollback tests | none |
| Public attack surface | +1 endpoint on the invitation family | none |
| Contract / SPA | +1 path; `AcceptInvitationPage` reworked | one nudge line (Task 12) |
| User loses | — | passwordless-by-choice; one-step join |

The loss is small because passwordless accounts remain available where an organisation actually
wants them — company-IdP JIT (§6r) — and the friction is softened by Task 12: a new
registration is auto-signed-in by the SPA (accept + `POST /auth/login` composed client-side,
so the backend's 204-no-session decision stands) and lands on the security page's first-run
offer, skippable in one click — social is an optional extra, never a gate. The cut also
upgrades the non-collision claim from "does not collide with" to "does not touch" the
invitation *backend* — the accept page's only change is what its success does next.

**If revived:** the trust argument was sound (possession of the single-use invitation link
decides who the account becomes — the same trust the password path extends), the email asserted
by the provider deliberately did **not** have to match the invited address (the subject is the
key; the assertion is logged), and the one-transaction rule above is the part that must not be
lost. The V58 in this plan ships without `invitation_id`; reviving means a follow-up migration
adding the column and widening the two check constraints, not a rewrite.

## Self-review notes (already applied)

- **Spec coverage:** login (Tasks 5/6/7/8/10), link/unlink (Tasks 6/7/8/9/13), register — by
  decision the backend invitation flow is unchanged; a first registration is auto-signed-in by
  the SPA and lands on the security page's skippable first-run offer (Tasks 12–13, decision
  §0.2, Appendix A); social is optional everywhere, gating nothing; non-collision (decision
  §0.1's table; provisioning and invitation backend code untouched by every task).
- **Type consistency:** `SocialProviderKey` (Java) ↔ `SocialProvider` (contract/TS);
  `SocialRedirect { url }` is the one begin-leg answer everywhere; `Intent` has exactly the two
  members V58's check names.
- **Known verify-at-execution points** (flagged inline, not placeholders): the HTTP-stub harness
  Task 5 borrows, `OidcProperties`' redirect-URI construction, `CurrentUserService`/`Problem`
  method names, `EndpointAccessTest`'s enumeration form, the exception-list ordinals, and the
  packages copy's `schema.d.ts` path. Each task says to open the named file first; the existing
  file always wins over this plan's guess.
- **Not in scope, by decision:** invitation-time social acceptance (Appendix A), CLI/MCP
  surfaces (`@tcms/*` — session auth is out of their lane; tokens stay `TCMS_TOKEN`),
  IdP-initiated flows, refresh tokens from providers (nothing is stored beyond the subject),
  avatar/profile sync, and any change to the permission matrix.
