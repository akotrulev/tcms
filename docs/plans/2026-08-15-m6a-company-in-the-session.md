# M6 Plan A — The Catalogue and the Session Learn About Companies

**Date:** 2026-08-15 · **Written by:** `ep-writing-plans` · **Master plan:** `docs/plans/2026-08-15-m6-multi-company-activation.md`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Checkpoint after every task** —
> that is the cadence chosen for this milestone, not the per-phase default of the repo's commit rule.
> Backend work also loads `/be-java` and the `be-java-database-code` skill; frontend work loads
> `/fe-react`. `ep-code-comments` governs every comment written here.

**Goal:** Add the one permission the operator console needs, and make the *session* able to name,
list and change its active company — so that Plans B, C, D and E all read one already-decided
`CurrentUser` shape instead of each inventing one.

**Architecture:** Nothing here changes how isolation works. `CompanyContextFilter` keeps resolving
the company from the signed `co` claim and from nothing else; the switch endpoint does not *set* a
company, it **mints a new token** whose `co` claim is the new company, after proving ACTIVE
membership. `SessionTokens` stays the only place a claim set is assembled. The one genuinely new
read is "which companies may this account open a session in", which already exists as
`tcms_companies_for_user` and has exactly one caller.

**Tech Stack:** Spring Boot 3.5.6 · Java 21 · Flyway (**V53**, pre-assigned) · React 19 · TanStack
Query · Vitest + RTL

**Fixture UUID prefix for this plan:** `d0000000-…`. **Slug namespace for new tests:** `m6a-`.

---

## Preconditions

- `docker compose up` at the repo root — every backend test boots a real Postgres 17 via
  Testcontainers. There is no H2 lane and there will not be one.
- Working in `.claude/worktrees/m6-multi-company` on `worktree-m6-multi-company`. Other agents are
  live in the primary checkout; **stage only the paths each task names**, never `git add -A`.
- `git log --oneline -1` should show `fdfa846` (the master plan) or later.

## Gates for every task that touches code

```bash
cd backend  && ./gradlew build                                    # -Werror + every test
cd frontend && npm run build && npm run lint && npm run test:run
cd packages && npm run build && npm run lint --workspaces && npm run test:run --workspaces
```

After **any** `openapi.yaml` edit, both copies:

```bash
cd frontend        && npm run gen:api
cd packages/client && npm run gen:api
```

`schemaFreshness.test.ts` compares them byte for byte; regenerating one is a red build.

---

## File Structure

**Backend — create**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V53__instance_read.sql` | The 56th permission. |
| `backend/src/main/java/com/tcms/identity/MembershipDisplayNames.java` | The one implementation of §6u's "the membership's name wins, the canonical name is the fallback" rule. |
| `backend/src/main/java/com/tcms/tenancy/CompanyRef.java` | `(id, slug, displayName, status)` — the company as the wire sees it. |
| `backend/src/main/java/com/tcms/auth/SessionCompanyService.java` | Lists the caller's companies; switches the active one. |
| `backend/src/main/java/com/tcms/auth/SessionCompanyController.java` | `GET /api/v1/me/companies`, `POST /api/v1/auth/session/company`. |

**Backend — modify**

`auth/CurrentUserService.java` (the record grows `company`), `auth/AuthController.java` (unchanged
shape, new nested type reused), `identity/IdentityPeopleDirectory.java`, `work/BoardPeople.java`,
`documentation/ArtifactPeople.java` (all three delegate to `MembershipDisplayNames`),
`docs/specs/openapi.yaml`, `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`, `CLAUDE.md`,
`README.md`, `frontend/src/lib/permissions.ts`.

**Backend — test**

`backend/src/test/java/com/tcms/schema/SeedDataTest.java` (existing rules must go green with no new
exemption), `backend/src/test/java/com/tcms/auth/SessionCompanyEndpointTest.java` (new),
`backend/src/test/java/com/tcms/identity/MembershipDisplayNamesTest.java` (new),
`backend/src/test/java/com/tcms/architecture/ArchitectureRulesTest.java` (the fourth-copy rule).

**Frontend — modify**

`src/api/types.ts`, `src/api/queries/keys.ts`, `src/api/queries/session.ts` (or the existing auth
query module — check `src/api/queries/index.ts` for where `useLogin` lives), `src/auth/authContextValue.ts`,
`src/test/fixtures.ts`. **No screen changes in this plan** — the switcher UI is Plan D.

---

## Task 1: `instance:read` reaches the spec, the seed and both catalogues

The catalogue has been 55 keys since `V3` at M0. This is the first key added. Several documents say
"55" in prose and every one of them becomes false, so the edit is wide and mechanical — and the
order matters: **the spec is the authority, so it is edited first and everything else is made to
agree with it.**

**Files:**
- Modify: `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` (§A3 SYSTEM table at `:277-285`, and the change log at `:592`)
- Create: `backend/src/main/resources/db/migration/V53__instance_read.sql`
- Modify: `docs/specs/openapi.yaml` (the `Permission` enum, around `:10833`)
- Modify: `CLAUDE.md` (the `SYSTEM` scope sentence in "The permission matrix")
- Modify: `README.md` (the same copy)
- Modify: `frontend/src/lib/permissions.ts`
- Test: `backend/src/test/java/com/tcms/schema/SeedDataTest.java` (existing rules; no new test needed — see Step 6)

- [ ] **Step 1: Edit the spec's SYSTEM table**

In `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`, in the `#### System — SYSTEM scope`
table, add the row in catalogue order (it reads best directly under `system:settings`):

```markdown
| `instance:read` | ✓ | Read the instance operator console — the company roster and a company's detail |
```

- [ ] **Step 2: Add the change-log row**

At the end of the dated table around `:592`, in the established form:

```markdown
| 2026-08-15 | **`instance:read` — the first permission added since M0 (§A3).** The catalogue goes 55 → 56. `V53` inserts it at `'system'` scope and grants it to nobody, because Instance Admin's grants are *derived from the catalogue* (`V3:167-172`), so it is held the moment it exists — asserted in the migration header rather than assumed. It exists because the instance operator console needs a gate for its **reads**, and `docs/plans/2026-08-07-instance-operator-console.md:84` proposed gating them on `company:create` instead, calling that "the smaller lie". **That is superseded rather than wrong**: it was the correct call for a plan that did not want to touch this document, and the decision on 2026-08-15 was to pay for the key. No permission moved between roles, so `SeedDataTest.testEngineerToTestLeadBoundary` and every qualifier rule are untouched. Handoff §TBD. |
```

> Replace `§TBD` with the real section number when the slice's handoff file is written (Plan A's last
> task). Do not leave `TBD` in a commit that closes the plan.

- [ ] **Step 3: Write the migration**

`backend/src/main/resources/db/migration/V53__instance_read.sql`:

```sql
-- =============================================================================
-- V53 — instance:read, the 56th permission and the first added since M0
--
-- WHY A NEW KEY AT ALL
--
-- The instance operator console (docs/plans/2026-08-07-instance-operator-console.md)
-- gates eight endpoints. Its writes name what they do -- company:create, company:suspend,
-- user:manage -- and its READS had no key of their own. That plan proposed reusing
-- company:create, i.e. gating "may look at the roster" on "may provision a company",
-- and called it the smaller lie. Decided on 2026-08-15 to add the key instead.
--
-- NOBODY IS GRANTED IT HERE, AND THAT IS NOT AN OMISSION
--
-- V3:167-172 seeds Instance Admin by SELECTing the whole catalogue:
--     INSERT INTO role_permission (role_id, permission_key)
--     SELECT '1000...0001', key FROM permission;
-- so a key inserted into `permission` is held by Instance Admin from the moment it
-- exists. A grant row here would be a SECOND source of truth for that role and would
-- disagree with the derivation the day somebody deleted one of them.
--
-- The scope is 'system'. A SYSTEM-scope key is answered by
-- PermissionEvaluator.evaluateInstanceWide from instance_role_assignment, which is a
-- separate instance-level table precisely because a nullable company_id under RLS is
-- permanently unreachable (ERD §0.1).
-- =============================================================================

INSERT INTO permission (key, scope, area, description)
VALUES ('instance:read', 'system', 'system',
        'Read the instance operator console: the company roster and a company''s detail');
```

> Check `V3__seed_permissions_and_roles.sql`'s own `INSERT INTO permission` column list before
> running this — if it carries columns this statement omits, match it exactly. Do not guess the
> shape; open the file.

- [ ] **Step 4: Add it to the contract**

In `docs/specs/openapi.yaml`, find the `Permission` enum (the list containing `'company:vocabulary'`
around `:10833`) and add `- 'instance:read'` in the same order the spec's tables use. Then:

```bash
cd frontend        && npm run gen:api
cd ../packages/client && npm run gen:api
```

- [ ] **Step 5: Fix the two matrix copies and the SPA catalogue**

In `CLAUDE.md` and `README.md`, the `SYSTEM` scope line currently reads seven keys. It becomes:

```markdown
`SYSTEM` scope, Instance Admin only: `company:create` · `company:delete` · `company:suspend` ·
`user:manage` · `role:manage` · `system:settings` · `instance:audit` · `instance:read`.
```

In `frontend/src/lib/permissions.ts`, add `'instance:read': 'system',` to `permissionAreas` and its
description wherever the file keeps descriptions. **This file will not compile until you do** — the
`satisfies Record<PermissionKey, …>` clause is the mechanism, working as designed.

- [ ] **Step 6: Run every check that reads the catalogue, and add none**

```bash
cd backend && ./gradlew test --tests 'com.tcms.schema.SeedDataTest'
cd packages && npm run test:run --workspaces
```

Expected: PASS. `SeedDataTest.theSeededMatrixIsTheSpecsMatrix` now compares 392 cells instead of 385;
`areasMatchTheSpa` compares `permission.area` against `permissions.ts`; `matrixCopies.test.ts` reads
both copies and all three scopes of §A3.

> **If any of them needs a new exemption to go green, stop and report it.** Every one of these rules
> is designed to be satisfiable by making the documents agree. An exemption here would mean the edit
> above is wrong somewhere, and the exemption would hide it — which is precisely the shape §162
> named (*"an ignore list naming the wrong mechanism reads as coverage and is the absence of it"*).

- [ ] **Step 7: Full gates**

```bash
cd backend  && ./gradlew build
cd frontend && npm run build && npm run lint && npm run test:run
cd packages && npm run build && npm run lint --workspaces && npm run test:run --workspaces
```

- [ ] **Step 8: Commit**

```bash
git add docs/specs/2026-07-30-tcms-roles-permissions-naming.md \
        docs/specs/openapi.yaml \
        backend/src/main/resources/db/migration/V53__instance_read.sql \
        frontend/src/api/schema.d.ts packages/client/src/api/schema.d.ts \
        frontend/src/lib/permissions.ts CLAUDE.md README.md
git commit -m "feat(identity): add instance:read, the 56th permission

The first key added since V3 seeded the catalogue at M0. It gates the instance
operator console's READS; its writes already have keys that name them. Granted to
nobody, because Instance Admin's grants are derived from the catalogue.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

> The `schema.d.ts` paths above are the two the repo actually uses — confirm them with
> `git status --short` before staging; if `packages/client`'s copy lives elsewhere, stage where it is.

---

## Task 2: One implementation of the per-membership display name

**This task removes a defect that is currently three identical correct copies.** §6u's rule — the
company membership's `display_name` wins, `app_user.canonical_name` is the fallback for somebody
offboarded since — is implemented at three sites with the same line and near-identical comments:

- `identity/IdentityPeopleDirectory.java:64`
- `work/BoardPeople.java:101`
- `documentation/ArtifactPeople.java:76`

Three copies of a resolution rule that §6u calls *"threaded through every surface that shows a
person"* is three chances for the fourth site to get it wrong, and no way to notice.

**Files:**
- Create: `backend/src/main/java/com/tcms/identity/MembershipDisplayNames.java`
- Modify: `backend/src/main/java/com/tcms/identity/IdentityPeopleDirectory.java`, `backend/src/main/java/com/tcms/work/BoardPeople.java`, `backend/src/main/java/com/tcms/documentation/ArtifactPeople.java`
- Test: `backend/src/test/java/com/tcms/identity/MembershipDisplayNamesTest.java`
- Test: `backend/src/test/java/com/tcms/architecture/ArchitectureRulesTest.java`

- [ ] **Step 1: Write the failing architecture rule**

Add to `backend/src/test/java/com/tcms/architecture/ArchitectureRulesTest.java`. Open that file
first and match its existing idiom — it already declares ArchUnit rules and at least one
`*_BY_DECISION` map, and `everyExemptionStillExemptsSomething` (§162) will police whatever you add,
so the exemption set must be both-ways.

```java
/**
 * §6u's display-name rule has exactly one implementation.
 *
 * <p>"The membership's name wins; the canonical name is the fallback for somebody offboarded
 * since" was written out three times — {@code IdentityPeopleDirectory}, {@code BoardPeople},
 * {@code ArtifactPeople} — identically and correctly. Three correct copies is not the same
 * property as one implementation: the rule is the resolution §6u calls "threaded through every
 * surface that shows a person", and the fourth surface is the one that renders
 * {@code canonicalName} to a client that may not see it.
 *
 * <p>The two exempt sites do not RENDER a name — {@code MembershipInviteService} composes the
 * initial membership name from it, and {@code GrantInstanceAdminService} prints the operator
 * directory, which is the one audience {@code canonical_name} exists for.
 */
@Test
void onlyOneSiteResolvesADisplayName() {
    Set<String> allowed = Set.of(
            "com.tcms.identity.MembershipDisplayNames",
            "com.tcms.identity.AppUser",
            "com.tcms.identity.MembershipInviteService",
            "com.tcms.identity.UserInvitationRequest",
            "com.tcms.provisioning.GrantInstanceAdminService");

    ArchRuleDefinition.noClasses()
            .that(JavaClass.Predicates.resideInAPackage("com.tcms.."))
            .and(new DescribedPredicate<JavaClass>("not an allowed resolver") {
                @Override
                public boolean test(JavaClass input) {
                    return !allowed.contains(input.getFullName());
                }
            })
            .should().callMethod(AppUser.class, "getCanonicalName")
            .because("§6u's resolution rule has one implementation: MembershipDisplayNames")
            .check(productionClasses());
}
```

> `productionClasses()` is a placeholder for whatever this test class already uses as its
> `JavaClasses` source — **open the file and use its real name.** Every other rule in it has one;
> do not import a second one.

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.architecture.ArchitectureRulesTest'
```

Expected: FAIL, naming three classes — `IdentityPeopleDirectory`, `BoardPeople`, `ArtifactPeople`.
That list is the task.

- [ ] **Step 3: Write the resolver**

`backend/src/main/java/com/tcms/identity/MembershipDisplayNames.java`:

```java
package com.tcms.identity;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * §6u's resolution rule, once.
 *
 * <p>A person's user-facing name is an attribute of their <em>company membership</em>, not of
 * their account: a contractor may be "A. Kotrulev" to one client and "Aleks (Vendor)" to another,
 * and neither client may see or overwrite the other's. {@code app_user.canonical_name} exists for
 * the Instance Admin's directory and is the fallback only when there is no membership to resolve
 * through — somebody offboarded from this company since they authored the row being rendered.
 * Their work stays; a blank name would read as data loss rather than as a person having left.
 *
 * <p>This existed three times, identically and correctly, before §M6A. The property that matters
 * is not "each copy is right" but "there is one copy", because the rule reaches every surface that
 * shows a person and the next such surface is the one that gets it wrong.
 * {@code ArchitectureRulesTest.onlyOneSiteResolvesADisplayName} is what keeps that true.
 */
@Component
public class MembershipDisplayNames {

    /**
     * @param membershipNames the display names already read for the active company, keyed by
     *                        user id — the caller batches that read, because resolving one name
     *                        per rendered row is how a 200-row page becomes 200 queries
     */
    public String resolve(AppUser user, Map<UUID, String> membershipNames) {
        return membershipNames.getOrDefault(user.getId(), user.getCanonicalName());
    }
}
```

- [ ] **Step 4: Write the resolver's own test**

`backend/src/test/java/com/tcms/identity/MembershipDisplayNamesTest.java` — a plain JUnit test, no
Spring context, per `be-java-unit-testing`:

```java
package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("§6u's display-name resolution")
class MembershipDisplayNamesTest {

    private final MembershipDisplayNames names = new MembershipDisplayNames();

    @Test
    @DisplayName("prefers the membership's name over the account's canonical one")
    void membershipNameWins() {
        UUID id = UUID.fromString("d0000000-0000-0000-0000-000000000001");
        AppUser user = AppUserTestFactory.withCanonicalName(id, "A. Kotrulev");

        assertThat(names.resolve(user, Map.of(id, "Aleks (Vendor)")))
                .isEqualTo("Aleks (Vendor)");
    }

    @Test
    @DisplayName("falls back to the canonical name for somebody offboarded since")
    void canonicalNameIsTheFallback() {
        UUID id = UUID.fromString("d0000000-0000-0000-0000-000000000002");
        AppUser user = AppUserTestFactory.withCanonicalName(id, "A. Kotrulev");

        assertThat(names.resolve(user, Map.of()))
                .as("their authored work stays; a blank name reads as data loss")
                .isEqualTo("A. Kotrulev");
    }
}
```

> `AppUserTestFactory` is a placeholder. **Look for how the existing tests build an `AppUser`** —
> `grep -rn "new AppUser(" backend/src/test | head` — and use that. If there is no factory and the
> entity has no accessible constructor, construct it the way `AppUserRepository`-backed tests do and
> make this an `IntegrationTestBase` test instead. Do not add a constructor to the entity for a test.

- [ ] **Step 5: Delegate from all three sites**

In each of `IdentityPeopleDirectory.java:64`, `BoardPeople.java:101` and `ArtifactPeople.java:76`,
inject `MembershipDisplayNames` the way each class injects its other collaborators and replace

```java
displayNames.getOrDefault(user.getId(), user.getCanonicalName()),
```

with

```java
names.resolve(user, displayNames),
```

**Delete the three comment blocks that explained the rule** — `ep-code-comments`: a comment
explaining a rule that now lives behind a named method is stale the moment the method exists. The
reasoning moves to `MembershipDisplayNames`' javadoc, where it is written once.

- [ ] **Step 6: Run the rule and the suite**

```bash
cd backend && ./gradlew test --tests 'com.tcms.architecture.ArchitectureRulesTest' \
                            --tests 'com.tcms.identity.MembershipDisplayNamesTest'
```
Expected: PASS.

```bash
cd backend && ./gradlew build
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/tcms/identity/MembershipDisplayNames.java \
        backend/src/main/java/com/tcms/identity/IdentityPeopleDirectory.java \
        backend/src/main/java/com/tcms/work/BoardPeople.java \
        backend/src/main/java/com/tcms/documentation/ArtifactPeople.java \
        backend/src/test/java/com/tcms/identity/MembershipDisplayNamesTest.java \
        backend/src/test/java/com/tcms/architecture/ArchitectureRulesTest.java
git commit -m "refactor(identity): §6u's display-name rule has one implementation

It had three, identical and correct. The property that matters is that there is
one, because the rule reaches every surface that shows a person and the next such
surface is the one that gets it wrong. An ArchUnit rule now fails the fourth copy.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: `CompanyRef`, and `CurrentUser` learns which company it is in

Today `CurrentUser` is `UserRef + isSystemAdmin + teams` and carries **no company at all** — not an
id, not a slug, not a name. The SPA therefore cannot render "you are in Acme", cannot build a
`/c/{slug}` URL, and cannot tell a suspended company from an active one. Every later plan needs
this, so it is defined once here.

**Files:**
- Create: `backend/src/main/java/com/tcms/tenancy/CompanyRef.java`
- Modify: `backend/src/main/java/com/tcms/auth/CurrentUserService.java`
- Modify: `docs/specs/openapi.yaml` (`CompanyRef` schema; `CurrentUser`'s inner object)
- Test: `backend/src/test/java/com/tcms/auth/AuthEndpointTest.java` (extend — it owns the `auth-` slug namespace)

- [ ] **Step 1: Write the failing test**

Add to `AuthEndpointTest`'s `UsingTheSession` nested class. It uses that file's real helpers —
`activated(slug, displayName)`, `tokenFor(fixture)`, `json`, `mvc` — which are private methods on the
class; read `:363-400` before writing.

```java
@Test
@DisplayName("/me names the active company, so the SPA can build a /c/{slug} URL")
void meNamesTheActiveCompany() throws Exception {
    Fixture fixture = activated("auth-co", "Co User");

    JsonNode me = json.readTree(mvc.perform(get("/api/v1/me")
                    .header("Authorization", "Bearer " + tokenFor(fixture)))
            .andReturn().getResponse().getContentAsString());

    assertThat(me.get("company").get("slug").asText()).isEqualTo("auth-co");
    assertThat(me.get("company").get("displayName").asText()).isEqualTo("Auth auth-co");
    assertThat(me.get("company").get("status").asText()).isEqualTo("ACTIVE");
    assertThat(me.get("company").get("id").asText()).isNotBlank();
}
```

> `"Auth auth-co"` is what `activated` provisions as the display name — it passes
> `"Auth " + slug`. Read the helper rather than trusting this sentence.

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.AuthEndpointTest'
```
Expected: FAIL with a `NullPointerException` on `me.get("company")` — the property does not exist.

- [ ] **Step 3: Write `CompanyRef`**

`backend/src/main/java/com/tcms/tenancy/CompanyRef.java`:

```java
package com.tcms.tenancy;

import java.util.UUID;

/**
 * A company as the wire sees it.
 *
 * <p>Four fields and no more. {@code slug} is here because it is the URL segment (§6s:
 * {@code /c/{slug}}) and {@code status} because the SPA must be able to say "this company is
 * suspended" rather than rendering a dead shell — but nothing about branding, settings, quota or
 * member counts belongs on a reference type. Those are reads of their own, gated on their own
 * permissions.
 *
 * <p>Lives in {@code tenancy} because {@code tenancy} owns the company and is depended on by
 * everything while depending on nothing.
 */
public record CompanyRef(UUID id, String slug, String displayName, CompanyStatus status) {

    public static CompanyRef of(Company company) {
        return new CompanyRef(
                company.getId(), company.getSlug(), company.getDisplayName(), company.getStatus());
    }
}
```

> `Company` maps `displayName` today? **Check `Company.java:30-50`** — the master plan records that
> the *branding* columns are unmapped; `display_name` should be mapped, but verify rather than
> assume. If it is not, map it here (a plain `@Column` on an existing NOT NULL column, no migration).

- [ ] **Step 4: Grow `CurrentUser`**

In `CurrentUserService.java`, add the component and populate it. The record becomes:

```java
public record CurrentUser(
        UUID id,
        String displayName,
        String email,
        String avatarUrl,
        boolean isSystemAdmin,
        CompanyRef company,
        List<TeamRef> teams) {
}
```

In `describe`, the company-less branch keeps returning `null` for it —

```java
if (companyId == null) {
    // An operator who belongs to no company. Null is the honest answer and the SPA
    // branches on it; an empty CompanyRef would be a company that does not exist.
    return new CurrentUser(user.getId(), user.getCanonicalName(), user.getEmail(),
            null, systemAdmin, null, List.of());
}
```

— and the company branch resolves it. Inject `CompanyRepository` (it is in `tenancy`, which `auth`
may depend on) and read the row:

```java
CompanyRef company = companies.findById(companyId)
        .map(CompanyRef::of)
        .orElseThrow(() -> new IllegalStateException(
                "Session company " + companyId + " does not exist. The company id comes from a "
                        + "signed claim this instance minted, so a missing row means a company "
                        + "was deleted — which policy says never happens (§6s: suspend, never "
                        + "delete)."));
```

> **`company` carries no RLS policy** — it is one of ERD §0.1's deliberate instance-level tables, and
> `V8`'s function header says so in as many words (*"company is instance-level and carries no
> policy"*). So this read works with no company context open, which is what makes Task 4 possible at
> all.

- [ ] **Step 5: Add it to the contract**

In `docs/specs/openapi.yaml`, add the schema beside the other `*Ref` types:

```yaml
    CompanyRef:
      type: object
      required: [id, slug, displayName, status]
      properties:
        id: { type: string, format: uuid }
        slug:
          type: string
          description: |
            Lowercase kebab, unique instance-wide, immutable. This is the URL segment:
            `/c/{slug}/...` and `/{slug}/login`. It is **validated against the session's
            company, never trusted as its source** — no request parameter, header or path
            segment ever selects a company (ERD §0.1 point 2).
        displayName: { type: string }
        status:
          type: string
          enum: [ACTIVE, SUSPENDED]
          description: |
            `SUSPENDED` refuses all non-Instance-Admin access. The SPA renders the state
            rather than an empty shell; the refusal itself is server-side.
```

Then in `CurrentUser`'s inner object, add `company` to `properties` and **leave it out of
`required`** — it is genuinely absent for an operator with no membership:

```yaml
            company:
              allOf: [{ $ref: '#/components/schemas/CompanyRef' }]
              description: |
                The session's active company. **Absent for an operator who belongs to none** —
                an Instance Admin before they have provisioned anything. Such a session is
                company-context-less and resolves SYSTEM permissions only, which is a correct
                state rather than a failure.
```

Regenerate both copies:

```bash
cd frontend && npm run gen:api && cd ../packages/client && npm run gen:api
```

- [ ] **Step 6: Run the test**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.AuthEndpointTest'
```
Expected: PASS.

- [ ] **Step 7: Full gates, and expect frontend fallout**

```bash
cd backend  && ./gradlew build
cd frontend && npm run build && npm run test:run
cd packages && npm run build && npm run test:run --workspaces
```

**Expect `frontend/src/test/fixtures.ts` and `packages/client/src/test/fixtures.ts` to fail**, and
possibly `contractBodies`-driven tests in both stacks: since §197/§203 every body a double serves is
compared against `openapi.yaml`, and `CurrentUser` fixtures now serve a body missing an optional
property — which is legal — but a fixture that hard-codes the whole object may need `company` added
to be *useful*. Add it to the factories; do not add a `drivingTheTransport()` hatch, which is for
tests whose subject is a header, a timeout or a cancellation.

- [ ] **Step 8: Commit**

```bash
git add docs/specs/openapi.yaml \
        backend/src/main/java/com/tcms/tenancy/CompanyRef.java \
        backend/src/main/java/com/tcms/auth/CurrentUserService.java \
        backend/src/test/java/com/tcms/auth/AuthEndpointTest.java \
        frontend/src/api/schema.d.ts packages/client/src/api/schema.d.ts \
        frontend/src/test/fixtures.ts packages/client/src/test/fixtures.ts
git commit -m "feat(auth): CurrentUser names its active company

CurrentUser carried no company at all, so the SPA could not say which client it
was in, could not build a /c/{slug} URL and could not tell suspended from active.
Absent rather than empty for an operator who belongs to no company.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: `GET /api/v1/me/companies` — the list that exists and is exposed to nobody

`CompanyMembershipRepository.findCompanyIdsForUser` already answers "which companies may this
account open a session in", through `tcms_companies_for_user` with RLS fully in force. Its **only**
caller is `AuthenticationService.defaultCompanyOf`, which takes `.findFirst()` and throws the rest
away. This task gives the list a reader.

**Two properties of the existing function that this endpoint inherits and must not paper over:**

1. It filters `WHERE status = 'ACTIVE'` on the *company* (`V8`'s loop) — so **a suspended company
   simply is not in the list**, and a member of only-suspended companies gets an empty array. That is
   correct and it is also why Plan B's `SuspendedCompanyFilter` is still needed: a token minted
   *before* the suspension still carries that `co` claim.
2. It returns **ids only**, deliberately. Names are read afterwards from `company`, which carries no
   RLS policy.

**Files:**
- Create: `backend/src/main/java/com/tcms/auth/SessionCompanyService.java`
- Create: `backend/src/main/java/com/tcms/auth/SessionCompanyController.java`
- Modify: `docs/specs/openapi.yaml`
- Test: `backend/src/test/java/com/tcms/auth/SessionCompanyEndpointTest.java`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/tcms/auth/SessionCompanyEndpointTest.java`. It owns the **`m6a-`** slug
namespace (the container is shared across the whole suite — handoff §14 — so a test that provisions
a company must own its slug prefix).

```java
package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The session's companies: listing them, and changing which one is active.
 *
 * <p>Owns the {@code m6a-} slug namespace.
 */
@AutoConfigureMockMvc
@DisplayName("The session's companies")
class SessionCompanyEndpointTest extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("lists every company the caller is an ACTIVE member of, named")
    void listsTheCallersCompanies() throws Exception {
        String token = tokenForAMemberOfTwoCompanies();

        JsonNode body = json.readTree(mvc.perform(get("/api/v1/me/companies")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());

        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("slug").asText()).startsWith("m6a-");
        assertThat(body.get(0).get("displayName").asText()).isNotBlank();
    }

    @Test
    @DisplayName("does not list a company the caller was only INVITED to")
    void anUnacceptedInvitationIsNotAMembership() throws Exception {
        // An INVITED membership must convey nothing, or an unaccepted invitation becomes a
        // way to look at a client's data before agreeing to anything.
        String token = tokenForSomeoneWithOneAcceptedAndOnePendingInvitation();

        JsonNode body = json.readTree(mvc.perform(get("/api/v1/me/companies")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());

        assertThat(body).hasSize(1);
    }
}
```

> **The two `tokenFor…` helpers are yours to write in this file**, modelled on
> `AuthEndpointTest.activated`/`tokenFor` (`:363-400`): provision with
> `CompanyProvisioningService`, accept the invitation with `InvitationService.accept`, log in over
> `POST /api/v1/auth/login` and take `token` from the body. For the second company, use
> `MembershipInviteService` to invite the *same* email into a second company and accept it. Read
> `AuthEndpointTest` first and copy its shape — do not invent a new fixture idiom.

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.SessionCompanyEndpointTest'
```
Expected: FAIL with 404 — the path does not exist.

- [ ] **Step 3: Write the service**

`backend/src/main/java/com/tcms/auth/SessionCompanyService.java`:

```java
package com.tcms.auth;

import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.tenancy.CompanyRef;
import com.tcms.tenancy.CompanyRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which companies a session may run in, and how it moves between them.
 *
 * <h2>The list already existed and had no reader</h2>
 *
 * <p>{@code CompanyMembershipRepository.findCompanyIdsForUser} goes through
 * {@code tcms_companies_for_user} — the one query in the codebase that reads across companies,
 * with RLS fully in force (it opens each ACTIVE company in turn and asks the ordinary policed
 * question; {@code V7} tried a {@code SECURITY DEFINER} bypass and returned nothing in production
 * because {@code FORCE ROW LEVEL SECURITY} covers the owner too, and {@code V8} is the fix). Its
 * only caller was the login path's default-company pick, which took the first id and discarded
 * the rest.
 *
 * <p>Two inherited properties, both load-bearing:
 * <ul>
 *   <li><strong>A SUSPENDED company is not in the list</strong>, because the function's loop
 *       filters on {@code company.status = 'ACTIVE'}. A token minted before a suspension still
 *       carries that company, which is why {@code SuspendedCompanyFilter} exists separately.</li>
 *   <li><strong>Ids only.</strong> The names below are read from {@code company}, which is
 *       instance-level and carries no RLS policy (ERD §0.1) — which is exactly why a company the
 *       caller is not currently in can be named at all.</li>
 * </ul>
 */
@Service
public class SessionCompanyService {

    private final CompanyMembershipRepository memberships;
    private final CompanyRepository companies;
    private final SessionTokens tokens;
    private final com.tcms.identity.AppUserRepository users;

    public SessionCompanyService(
            CompanyMembershipRepository memberships,
            CompanyRepository companies,
            SessionTokens tokens,
            com.tcms.identity.AppUserRepository users) {
        this.memberships = memberships;
        this.companies = companies;
        this.tokens = tokens;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<CompanyRef> companiesFor(UUID userId) {
        return memberships.findCompanyIdsForUser(userId).stream()
                .flatMap(id -> companies.findById(id).stream())
                .map(CompanyRef::of)
                .toList();
    }
}
```

- [ ] **Step 4: Write the controller**

`backend/src/main/java/com/tcms/auth/SessionCompanyController.java`:

```java
package com.tcms.auth;

import com.tcms.tenancy.CompanyRef;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The companies this session may run in.
 *
 * <p>Reads the caller from the <strong>token</strong>, never from a parameter — the same rule
 * {@link MeController} states: a {@code userId} parameter here would answer "which companies does
 * <em>that</em> person belong to", and §6s makes that a question nobody below Instance Admin may
 * ask about anybody.
 */
@RestController
@RequestMapping("/api/v1/me")
public class SessionCompanyController {

    private final SessionCompanyService companies;

    public SessionCompanyController(SessionCompanyService companies) {
        this.companies = companies;
    }

    @GetMapping("/companies")
    public List<CompanyRef> myCompanies(@AuthenticationPrincipal Jwt jwt) {
        return companies.companiesFor(UUID.fromString(jwt.getSubject()));
    }
}
```

- [ ] **Step 5: Add the path to the contract**

In `docs/specs/openapi.yaml`, beside the other `/me` paths:

```yaml
  /me/companies:
    get:
      operationId: listMyCompanies
      summary: The companies this session may run in
      description: |
        Every company the caller is an **ACTIVE** member of, named. An `INVITED` membership
        conveys nothing and is not listed — an unaccepted invitation must not be a way to look
        at a client's data before agreeing to anything.

        A **SUSPENDED** company is not listed either: it cannot be switched into, and a token
        minted before the suspension is refused at the request filter rather than here.

        The caller is read from the token. There is deliberately no way to ask this about
        anybody else: a user's company list is theirs alone, and no administrator below
        Instance Admin may see which other companies a person belongs to (§6s).
      tags: [Session]
      responses:
        '200':
          description: The caller's companies, ordered by the membership they accepted first.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/CompanyRef' }
        '401': { $ref: '#/components/responses/Unauthorized' }
```

> Check the file's existing `responses:` component names before writing `Unauthorized` — use whatever
> the neighbouring paths use. Then regenerate both `schema.d.ts` copies.

- [ ] **Step 6: Run the test, then the gates**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.SessionCompanyEndpointTest'
cd backend && ./gradlew build
cd frontend && npm run build && npm run lint && npm run test:run
cd packages && npm run build && npm run test:run --workspaces
```

- [ ] **Step 7: Commit**

```bash
git add docs/specs/openapi.yaml \
        backend/src/main/java/com/tcms/auth/SessionCompanyService.java \
        backend/src/main/java/com/tcms/auth/SessionCompanyController.java \
        backend/src/test/java/com/tcms/auth/SessionCompanyEndpointTest.java \
        frontend/src/api/schema.d.ts packages/client/src/api/schema.d.ts
git commit -m "feat(auth): GET /me/companies

tcms_companies_for_user has answered this since V7 and its only caller took the
first id and threw the rest away. INVITED conveys nothing; SUSPENDED is not
switchable and is therefore not listed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: `POST /api/v1/auth/session/company` — the switch

The switch **does not set a company**. It proves ACTIVE membership and mints a new token whose `co`
claim is the new company; `CompanyContextFilter` then does what it has always done. That is the
whole design: no request input ever selects a company, and this endpoint is not an exception to that
rule — it is a *login-shaped* operation that happens to skip the password.

**The refusal is 404, not 403.** §6s: *"A request whose path company ≠ session company either
switches (if the user is a member) or 404s — 404, not 403, because a 403 confirms the company
exists."* An unknown id and a company the caller is not in must be **byte-identical**.

**Files:**
- Modify: `backend/src/main/java/com/tcms/auth/SessionCompanyService.java`
- Modify: `backend/src/main/java/com/tcms/auth/SessionCompanyController.java`
- Modify: `docs/specs/openapi.yaml`
- Test: `backend/src/test/java/com/tcms/auth/SessionCompanyEndpointTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `SessionCompanyEndpointTest`:

```java
@Test
@DisplayName("switching re-mints the token with the new company and keeps the login")
void switchingReMintsTheToken() throws Exception {
    TwoCompanies fixture = twoCompanies();

    JsonNode switched = json.readTree(mvc.perform(post("/api/v1/auth/session/company")
                    .header("Authorization", "Bearer " + fixture.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"companyId\":\"" + fixture.secondCompanyId() + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());

    assertThat(switched.get("token").asText()).isNotBlank();
    assertThat(switched.get("user").get("company").get("id").asText())
            .isEqualTo(fixture.secondCompanyId().toString());

    // And the new token actually works, resolving permissions in the NEW company.
    JsonNode me = json.readTree(mvc.perform(get("/api/v1/me")
                    .header("Authorization", "Bearer " + switched.get("token").asText()))
            .andReturn().getResponse().getContentAsString());
    assertThat(me.get("company").get("id").asText())
            .isEqualTo(fixture.secondCompanyId().toString());
}

@Test
@DisplayName("answers a company the caller is not in and one that does not exist identically")
void aForeignCompanyAndAnUnknownOneAreIndistinguishable() throws Exception {
    TwoCompanies fixture = twoCompanies();
    UUID foreign = aCompanyTheCallerIsNotAMemberOf();
    UUID doesNotExist = UUID.fromString("d0000000-0000-0000-0000-0000000000ff");

    MockHttpServletResponse toForeign = switchTo(fixture.token(), foreign);
    MockHttpServletResponse toNothing = switchTo(fixture.token(), doesNotExist);

    assertThat(toForeign.getStatus()).isEqualTo(404);
    assertThat(toNothing.getStatus()).isEqualTo(404);
    assertThat(toForeign.getContentAsString())
            .as("a 403 confirms the company exists, which publishes the client roster "
                    + "one guessed uuid at a time (§6s)")
            .isEqualTo(toNothing.getContentAsString());
}

@Test
@DisplayName("refuses a company the caller is only INVITED to, identically again")
void anInvitedMembershipCannotBeSwitchedInto() throws Exception {
    Pending fixture = oneAcceptedOnePending();

    assertThat(switchTo(fixture.token(), fixture.pendingCompanyId()).getStatus())
            .isEqualTo(404);
}
```

> `TwoCompanies`, `Pending`, `twoCompanies()`, `oneAcceptedOnePending()`,
> `aCompanyTheCallerIsNotAMemberOf()` and `switchTo(token, companyId)` are private helpers you write
> in this file, on top of the ones Task 4 added. Keep them private and keep them here — the fixture
> idiom in this repo is per-test-class, not a shared builder.

- [ ] **Step 2: Run them and watch them fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.SessionCompanyEndpointTest'
```
Expected: FAIL with 404 on the path itself (which is confusingly the same status the last two tests
want — check the failure is "no handler", not a passing assertion).

- [ ] **Step 3: Add the switch to the service**

Append to `SessionCompanyService`:

```java
/**
 * Re-mints the session for another company.
 *
 * <p><strong>This does not set a company.</strong> It proves ACTIVE membership and issues a new
 * token whose {@code co} claim is the new company; {@link CompanyContextFilter} then opens the
 * context from that signed claim exactly as it does for a token from the login path. So the rule
 * that no request input ever selects a company is not weakened here — the request names a
 * candidate, the server decides, and the decision is carried in a signature.
 *
 * <p><strong>Empty for every refusal, and the caller renders 404.</strong> A company the caller is
 * not a member of, a company that does not exist, and a company they are only {@code INVITED} to
 * must be indistinguishable: a 403 on the first would confirm the company exists, publishing the
 * client roster one guessed uuid at a time (§6s). Note there is nothing to time-equalize here, as
 * there is on the login path — all three branches are the same indexed lookup.
 *
 * <p>The permission cache is keyed including the company id, so nothing needs clearing: an entry
 * from the old company cannot answer a question about the new one. The permission VERSION is
 * carried over unchanged — switching companies is not a revocation event, and bumping it would sign
 * the caller out of every other tab.
 */
@Transactional(readOnly = true)
public Optional<SessionTokens.Session> switchTo(UUID userId, UUID companyId, boolean rememberMe) {
    boolean member = memberships.existsByCompanyIdAndUserIdAndStatus(
            companyId, userId, com.tcms.identity.MembershipStatus.ACTIVE);
    if (!member) {
        return Optional.empty();
    }
    return users.findById(userId)
            .map(user -> tokens.issue(userId, companyId, user.getPermissionVersion(), rememberMe));
}
```

> **`existsByCompanyIdAndUserIdAndStatus` is an RLS-protected read on `company_membership`** and the
> caller's context is still the *old* company at this point, so it will return **false for the
> company being switched into**. That is the trap this task exists around. Resolve it by opening the
> target company's context for the check — `try (var ignored = CompanyContext.open(companyId))` — or
> by checking against `findCompanyIdsForUser(userId)`, which is the cross-company reader that already
> exists and is already audited for exactly this. **Prefer `findCompanyIdsForUser`**: it is one call,
> it already excludes SUSPENDED companies and non-ACTIVE memberships, and it does not open a context
> for a company the caller has not yet been admitted to. Write it that way:

```java
    boolean member = memberships.findCompanyIdsForUser(userId).contains(companyId);
```

> **Run the test both ways if you are unsure** — the first form failing is the finding, not a
> mistake, and it is worth seeing.

- [ ] **Step 4: Add the endpoint**

Append to `SessionCompanyController`. Note it is mapped under `/api/v1/auth`, not `/api/v1/me`, so it
needs its own `@RequestMapping` — either split the class or use a full path on the method. **Use a
full path on the method** rather than a second controller; the two operations are one concept.

```java
public record SwitchCompanyRequest(@NotNull UUID companyId, Boolean rememberMe) {
}

/**
 * Change the session's active company.
 *
 * <p>Returns the same shape the login path returns, deliberately: the SPA's session-replacement
 * code is then one function rather than two, and the response carries the new {@code CurrentUser}
 * so a switch needs no follow-up {@code /me}.
 */
@PostMapping("/api/v1/auth/session/company")
public ResponseEntity<AuthController.LoginResponse> switchCompany(
        @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SwitchCompanyRequest request) {

    UUID userId = UUID.fromString(jwt.getSubject());
    return companies.switchTo(userId, request.companyId(),
                    Boolean.TRUE.equals(request.rememberMe()))
            .map(session -> ResponseEntity.ok(new AuthController.LoginResponse(
                    session.token(),
                    session.expiresAt(),
                    currentUser.describe(userId, request.companyId()))))
            .orElseGet(() -> ResponseEntity.notFound().build());
}
```

> `currentUser.describe(userId, request.companyId())` runs **before** any context for the new company
> is open, which is exactly the case its javadoc already documents (*"the login path calls this
> before a context is open"*). Its membership read will therefore hit RLS the same way — check
> whether `describe` needs the context opened around it, and if it does, open it with
> `try (var ignored = CompanyContext.open(request.companyId()))`, which is legitimate here because
> membership has just been proved. `CompanyContext` is a scope guard and never a setter.

- [ ] **Step 5: Add the path to the contract**

```yaml
  /auth/session/company:
    post:
      operationId: switchCompany
      summary: Change the session's active company
      description: |
        Re-mints the session token with a new active company. **It does not switch a context** —
        the new company travels in the signed `co` claim of the returned token, so the rule that
        no request parameter, header or path segment ever selects a company is intact (ERD §0.1
        point 2). It does not clear the login.

        **Refusals are 404 and are identical.** A company the caller is not a member of, a
        company that does not exist, and a company they hold only an `INVITED` membership in all
        answer the same empty 404 — a 403 on the first would confirm the company exists, which
        publishes the client roster one guessed uuid at a time (§6s).

        The permission version is carried over unchanged: switching companies is not a
        revocation event, and bumping it would sign the caller out of every other tab.
      tags: [Session]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [companyId]
              properties:
                companyId: { type: string, format: uuid }
                rememberMe:
                  type: boolean
                  description: As on sign-in; governs the new token's lifetime.
      responses:
        '200':
          description: The new session, in the same shape sign-in returns.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/LoginResponse' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404':
          description: |
            Not a company this session may open. Deliberately indistinguishable from a company
            that does not exist.
```

> `LoginResponse` — check the real component name in `openapi.yaml` for what `POST /auth/login`
> returns and reuse it rather than declaring a second shape.

Regenerate both `schema.d.ts` copies.

- [ ] **Step 6: Run the tests, then the gates**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.SessionCompanyEndpointTest'
cd backend && ./gradlew build
cd frontend && npm run build && npm run lint && npm run test:run
cd packages && npm run build && npm run test:run --workspaces
```

- [ ] **Step 7: Commit**

```bash
git add docs/specs/openapi.yaml \
        backend/src/main/java/com/tcms/auth/SessionCompanyService.java \
        backend/src/main/java/com/tcms/auth/SessionCompanyController.java \
        backend/src/test/java/com/tcms/auth/SessionCompanyEndpointTest.java \
        frontend/src/api/schema.d.ts packages/client/src/api/schema.d.ts
git commit -m "feat(auth): POST /auth/session/company re-mints the session

It does not switch a context: the new company travels in a signed claim, so no
request input ever selects a company. Every refusal is the same empty 404,
because a 403 confirms the company exists.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: The SPA's session knows its company — types and queries only

**No screens change in this plan.** The switcher control, the company badge in the app bar and the
`/c/{slug}` route tree are Plan D. What lands here is the data being *available*, so Plan D is a UI
task rather than a UI-and-plumbing task.

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/queries/keys.ts`
- Modify: `frontend/src/api/queries/` — the module holding `useLogin` (find it: `grep -rn "useLogin" frontend/src/api/queries/`)
- Modify: `frontend/src/auth/authContextValue.ts`
- Modify: `frontend/src/test/fixtures.ts`
- Test: a `.test.tsx` beside the query module, matching how the neighbouring query modules are tested

- [ ] **Step 1: Export the two new types**

In `frontend/src/api/types.ts`, beside the other contract-derived aliases:

```ts
export type CompanyRef = components['schemas']['CompanyRef'];
```

> Match the file's existing idiom exactly — open it and see whether it aliases through
> `components['schemas'][...]` or through a `paths[...]` response type. Do not introduce a second
> style.

- [ ] **Step 2: Add the query key**

In `frontend/src/api/queries/keys.ts`, in the `keys` factory:

```ts
  myCompanies: () => ['me', 'companies'] as const,
```

- [ ] **Step 3: Write the failing hook test**

Beside the query module, following the shape of its neighbours. Use `createTestQueryClient` from
`src/test/` — **never build your own `QueryClient`**: only that helper's has the caches the
render-failure and fetch-failure checks read, and `renderFailures.test.tsx` sweeps every test source
to keep that true.

```tsx
it('lists the companies this session may run in', async () => {
  stubFetch({ '/me/companies': [companyRefFixture({ slug: 'acme' })] });

  const { result } = renderHook(() => useMyCompanies(), { wrapper });

  await waitFor(() => expect(result.current.isSuccess).toBe(true));
  expect(result.current.data?.[0].slug).toBe('acme');
});
```

> `stubFetch` and the fixture factory are the existing seams — `src/test/fixtures.ts` holds a
> complete factory per resource and every body a double serves is compared against `openapi.yaml`
> since §197, values included since §198. **Add `companyRefFixture` to that file**; do not inline a
> partial object, which will fail the comparison for the properties it omits.

- [ ] **Step 4: Write the hook and the mutation**

```ts
export function useMyCompanies() {
  return useQuery({
    queryKey: keys.myCompanies(),
    queryFn: () => api.get('/me/companies'),
  });
}

/**
 * Switching companies replaces the session token and therefore invalidates every cached
 * server read: the whole cache is scoped to a company that is no longer active. `clear()`
 * rather than `invalidateQueries()` — an invalidation refetches with the OLD token still in
 * flight, and a partially-refetched cache mixing two clients' rows is the exact failure the
 * company boundary exists to make impossible.
 */
export function useSwitchCompany() { /* … */ }
```

> Fill in `useSwitchCompany` against the neighbouring mutation hooks — the per-endpoint timeout
> budgets, the `api.post` signature and the session-replacement call all follow local convention.
> The comment above is the part that is not convention and must be written.

- [ ] **Step 5: Widen the auth context**

`frontend/src/auth/authContextValue.ts` — `AuthValue` gains the active company and the switch, so no
screen reads them from a query directly:

```ts
export interface AuthValue {
  user: CurrentUser | null;
  /** The session's active company. `null` for an operator who belongs to none. */
  company: CompanyRef | null;
  login: (…) => Promise<void>;
  logout: () => void;
  switchCompany: (companyId: string) => Promise<void>;
}
```

> Keep the existing signatures of `login`/`logout` exactly as they are — copy them from the file.

- [ ] **Step 6: Gates**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
```

Expect fallout in any test that constructs an `AuthValue` by hand. `TestProviders.tsx` is the shared
one; fix it there rather than in each test.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/api/queries/ \
        frontend/src/auth/authContextValue.ts frontend/src/test/
git commit -m "feat(web): the session carries its company, and can change it

Types, keys, hooks and context only — no screen changes. The switcher UI, the app
bar badge and the /c/{slug} tree are Plan D, and land as UI on top of this.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: Write the slice down

The repo's rule: **finishing a slice means adding a file under `docs/handoff/NN-slug.md` and one
index row in `docs/HANDOFF.md` — never appending to `HANDOFF.md`.** Highest section on disk at
planning time is **§216**, so this is **§217** unless another lane landed one meanwhile.

- [ ] **Step 1: Check the number**

```bash
ls docs/handoff/ | sed 's/-.*//' | sort -n | tail -1
```
Use that + 1. A concurrent lane may have taken it — **re-check rather than assuming §217.**

- [ ] **Step 2: Write the section file**

`docs/handoff/NNN-<slug>.md`. Match the shape of `docs/handoff/216-the-key-that-still-started-the-application.md`:
what the slice did, what it found, what it decided and **what is NOT done**. The things this slice
found that are worth writing down:

- The §6u display-name rule existed **three times**, identically and correctly — and correctness of
  each copy is not the property that matters.
- `tcms_companies_for_user` has answered "which companies" since `V7`/`V8` and its only caller threw
  the answer away.
- `tcms_companies_for_user` filters `company.status = 'ACTIVE'`, so **suspension is already partly
  enforced at login** — and a token minted before a suspension is not, which is precisely the gap
  Plan B closes.
- The switch endpoint's membership check cannot be the ordinary RLS-protected derived query, because
  the caller's context is still the *old* company when it runs.

- [ ] **Step 3: Add the index row**

One row at the end of the table in `docs/HANDOFF.md`, in the established form. Update the **Latest
slice:** line at the top of that file too — §193 corrected it once for exactly this reason.

- [ ] **Step 4: Update both orientation caches**

`docs/agent-context/be-java.md` and `docs/agent-context/fe-react.md` — the seam list gains
`MembershipDisplayNames`, `CompanyRef`, `SessionCompanyService`, and the `CurrentUser` shape change.
Update the `Baseline commit:` header. **In the slice's own commit**, per `ep-agent-orientation-cache`.

- [ ] **Step 5: Add rows to the verification queues**

Browser passes and backend mutation checks are **deferred and batched** (§65, standing policy) —
so **write the rows and run nothing.**

- `docs/verification/frontend-browser-checks.md` — a row for switching companies once Plan D ships
  the control; note it needs a fixture with a user in two companies, which **no lane currently
  creates**, and say so rather than skipping silently.
- `docs/verification/backend-mutation-checks.md` — rows for: switching into a company you are only
  INVITED to; switching into a company that does not exist; and confirming the two responses are
  byte-identical.

- [ ] **Step 6: Commit**

```bash
git add docs/handoff/ docs/HANDOFF.md docs/agent-context/ docs/verification/
git commit -m "docs(m6a): the handoff section, the index row, both caches and the queues

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-review

**Spec coverage against the master plan's Plan A row:**

| Master plan says Plan A does | Task |
|---|---|
| `instance:read` and its nine-site ripple, as one commit | 1 |
| `CurrentUser` grows the active company | 3 |
| The membership list, which already exists and has no reader | 4 |
| `POST /auth/session/company`, 404 not 403, cache keyed by company | 5 |
| §6u's per-membership display name as **one** resolution rule with a build-failing check | 2 |

**Placeholder scan.** Four places name something the implementer must look up rather than invent,
and each says so explicitly and says where to look: `AppUserTestFactory` (Task 2 Step 4),
`productionClasses()` (Task 2 Step 1), the `Unauthorized`/`LoginResponse` component names (Tasks 4–5),
and the module holding `useLogin` (Task 6). These are **not** "TBD" — the surrounding code is
complete and the lookup is a one-line grep given in the step. The one real deferral is the handoff
section number, which cannot be known until the slice lands and Task 7 Step 1 resolves it.

**Type consistency.** `CompanyRef(id, slug, displayName, status)` is declared in Task 3 and used
under that exact name and shape in Tasks 4, 5 and 6, in Java, in `openapi.yaml` and in TypeScript.
`SessionCompanyService` gains `companiesFor` in Task 4 and `switchTo` in Task 5 — both used by
`SessionCompanyController`, which is created in Task 4 and extended in Task 5, never re-declared.
`MembershipDisplayNames.resolve(AppUser, Map<UUID, String>)` is declared in Task 2 Step 3 and called
with that signature in Step 5.

**One risk I am naming rather than resolving.** Task 5's membership check is the only place in this
plan where the obvious implementation is wrong for a non-obvious reason (RLS answers about the *old*
company). The step gives both forms and says which to prefer and why. If the executing agent writes
the derived query and the test goes green, **that is a finding to stop on**, not a pass — it would
mean `existsByCompanyIdAndUserIdAndStatus` is reading without a company context, which would be a
much larger defect than this task.
