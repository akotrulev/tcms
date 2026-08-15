# Instance Operator Console Implementation Plan

**Date:** 2026-08-07 · **Written by:** `ep-writing-plans`
**Milestone: M6 — do not start before M6 begins.**

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` (inline, the default) to implement this plan task-by-task. Only if delegation clearly pays for itself and the user asks for it, use `ep-delegate-to-subagents` (cost-aware). Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **This plan is scheduled, not queued.** It was written during M3 and deliberately deferred — see "Scheduling" below. Do not begin executing it because it is the newest file in `docs/plans/`.
>
> **M6 STARTED 2026-08-15. READ `docs/plans/2026-08-15-m6e-operator-console-revalidation.md` FIRST.**
> This plan's own `:30` asks for a re-grep before execution; that file is it. Five of six Orientation
> findings hold, one is incomplete, and six amendments change what gets built — including **Task 1
> moving out of this plan entirely** (it is `docs/plans/2026-08-15-m6b-company-lifecycle-and-branding.md`
> Task 1), **the second sign-off at `:84` being overturned** (`instance:read` is added to the
> catalogue, so the read gate is not `company:create`), and **the next free Flyway version being V56,
> not V39**. This plan is Plan E of the master plan,
> `docs/plans/2026-08-15-m6-multi-company-activation.md`.

**Goal:** Move company provisioning and user invitation out of the operator CLI and into an Instance-Admin-only web console, and give a fresh instance its first operator by emailing a one-shot activation link to the instance's own configured sender address on first boot.

---

## Scheduling — why this waits for M6

**Folded into M6 on 2026-08-07, at the user's direction.** It was written mid-M3 (recent slices: result ingestion, ingestion history, drift detection, flake quarantine, API tokens) and executing it then would have built the last milestone's UI surface before the milestones before it were finished.

About a third of it is M6 work being *pulled forward* rather than new scope:

| Task | Already M6? |
|---|---|
| Task 1 — suspension enforcement | **Yes** — §6s's lifecycle table lists it as "M6 (the check is trivial)" |
| Task 10 — invite a person into a company | **Yes** — M6's "invitation & membership flow" |
| Tasks 2–9, 11–14 — the console, the bootstrap, the SPA | **No** — §6s explicitly excluded a company management UI |

That exclusion was load-bearing: §6s:2055 says M6 is "genuinely a week of surface, not a phase" *because* companies are provisioned by script. Reversing it roughly doubles M6, from 1.5–2 weeks to 3–4. That cost is recorded in the milestone table and the decision log of `docs/plans/2026-07-30-tcms-high-level-plan.md`, struck through with the reversal, per the working agreement.

**One thing to re-check when M6 starts, because it is a latent defect rather than a feature gap:** `Company.suspend()` has had a writer and **no reader anywhere in `src/main`** since V1, so setting the column does nothing. Nothing exposes suspension today, so nothing is broken — but Task 1 must land *before or with* Task 6's suspend button, never after it. A suspend control over an unenforced column is a security affordance that is believed and does nothing.

**Re-validate before executing.** This plan's Orientation section records the state of the code on 2026-08-07. M6 is several milestones away, so treat every claim in it as needing a re-grep — particularly the `V3` permission catalogue, `PermissionEvaluator.evaluateCompanyWide`'s SYSTEM-term behaviour, and the next free Flyway version (**V39** at time of writing, certainly higher by M6).

**Architecture:** A new `/api/v1/instance/**` path family, gated on the SYSTEM-scope permissions that **already exist in the `V3` catalogue**, served to a company-context-less operator session that `CompanyContextFilter` and `PermissionEvaluator.evaluateInstanceWide` already support. Each endpoint is a thin renderer over the existing `provisioning` and `identity` services — `CompanyProvisioningService` and `MembershipInviteService` — which were deliberately split from their CLI commands, or gated in a way that already admits an operator, for exactly this reuse. The SPA gains a second route tree outside `/projects/:projectId`, mounted under `/instance`, gated on the `isSystemAdmin` flag `CurrentUserService` already returns and nothing currently consumes.

### Two permission surfaces, and the line between them

There are now **two** places a person's access is administered, and they are not duplicates:

| | **Company → Teams & Roles** (existing, `src/features/admin/`) | **Instance → People** (new, `src/features/instance/`) |
|---|---|---|
| Who | Company Admin, and an Instance Admin acting inside a company | Instance Admin only |
| Gate | `company:members` / `project:members`, COMPANY scope | `user:manage`, SYSTEM scope |
| Company | From the session context, never from the payload | **From the payload** — a dropdown, because the operator has no active company |
| Invites | Into *this* company | Into *any* company |
| Can mint an operator | **No** — `RoleGrantService` refuses SYSTEM-scope with a 400 | **No.** Nothing in the UI can — see below |

The company screen is unchanged by this plan. That is deliberate: it is the screen a client's own administrator uses, and it must stay unable to reach across the client boundary or mint an operator.

### Operators are created on the CLI, never in the UI

**No authenticated user action creates an Instance Admin.** Two paths mint one, and neither is reachable from a browser:

- **First boot, when zero operators exist** — `InstanceBootstrapService` emails a one-shot activation link to the instance's own configured sender address (`tcms.invitation.from-address`). Whoever controls that mailbox sets a password and becomes the first operator. This exists because an instance with *zero* operators cannot be administered at all.
- **Every operator after that** — `grant-instance-admin --email=…` over an account that already exists. Deliberately CLI-only: minting an operator should require a shell on the box and the database password, which is a different threat model from a browser session.

The invitation is **bound to an account**, not a bearer token: an `app_user` is created `INVITED` at the sender address before the token is minted, mirroring `membership_invitation` minus the company. An identity-less token would make whoever held the string the operator under any address they chose — not worth it to save typing an address the instance already knows.

This is worth stating as an invariant rather than an omission, because three plausible-looking features would each quietly break it and two were in earlier drafts of this plan: a `POST /instance/admins`, an "Instance Admin" option in the invite dropdown, and a SYSTEM-scope role in the company role-assignment screen. `RoleGrantService`'s existing 400 already blocks the third, and its header comment — *"there is no HTTP path to a SYSTEM grant … and there should not be one until an instance-admin surface exists to hold it"* — **stays true and stays untouched by this plan.** Task 10 carries a test whose only job is to fail if the second ever comes back.

**This is the one exception to "all actions happen only via the UI"**, and it is the exception on purpose. Recorded as such in the high-level plan's decision log.

**The invite dropdown chooses a destination, not a role.** This matters because `UserInvitationRequest`'s header records a decision that looks like it forbids this: *"There is no `companyId` and no `roleId` … inviting somebody and deciding what they may do are two acts with two different gates … folding them together would let one permission do the work of both."* Both halves survive intact here:

- **`companyId` is a target, not a discriminator.** ERD §0.1 point 2 forbids reading the *tenancy discriminator* from client input. `MembershipInvitation.companyId` is already documented as "a target, exactly like `InstanceAuditEvent.companyId`, not a discriminator", and the same is true of the `companyId` path variable on every endpoint in this console. The safeguard is that the gate is SYSTEM-scope: only an operator can name a company they are not in.
- **`roleId` is still absent.** A company invitation from this console grants **no role** — the invitee lands as a member with zero project visibility, exactly as §43 specifies for the existing path. The dropdown chooses *which company*, and nothing else; roles are still granted afterwards, on that company's own Teams & Roles screen, under a different gate.

**Tech Stack:** Spring Boot 3.5.6 · Java 21 · Flyway (next free version: **V39**) · React 19 · TanStack Query · MUI v7 · React Router 7 · Vitest + RTL

---

## Orientation — what already exists (read before Task 1)

Five findings from the pre-plan survey change what this work is. Do not re-derive them.

1. **The SYSTEM permissions already exist.** `V3__seed_permissions_and_roles.sql:99-105` seeds `company:create`, `company:delete`, `company:suspend`, `user:manage`, `role:manage`, `system:settings`, `instance:audit`, all at `'system'` scope. Instance Admin's grants are **derived from the catalogue** (`V3:167-172`: `SELECT '1000…0001', key FROM permission`), so it already holds all seven. **No migration is needed to gate this console.**
2. **The operator session already works.** `CurrentUserService.describe(userId, null)` returns `isSystemAdmin` for a company-less session; `AuthenticationService` and `MeController:89` resolve SYSTEM permissions via `evaluateInstanceWide`; `CompanyContextFilter:48` documents the context-less operator as valid and fail-closed. `isSystemAdmin` reaches `schema.d.ts:5958` and is consumed by **nothing** in `src/` except a test fixture.
3. **The services are already CLI-independent.** `GrantInstanceAdminService.grant(email, actorUserId)` takes the actor as a parameter and documents `null` as exactly this plan's case — "the bootstrapped first Instance Admin", which is why `granted_by` is nullable. The bootstrap path in Task 9 is its only caller besides the CLI. `ProvisionCompanyResult` already reports per-part idempotency flags.
4. **`SUSPENDED` is enforced nowhere.** `grep` over `src/main` finds `CompanyStatus.SUSPENDED` only in `Company.suspend()` — whose own comment says "must be honoured in code: refuse all … the status column alone enforces nothing." Shipping a suspend button before Task 3 would ship a lie.
5. **`membership_invitation.company_id` is `NOT NULL`.** Neither the bootstrap operator nor an Instance-Admin invitee belongs to a company, so the existing invitation table cannot carry either link. Task 8 adds a sibling instance-level table — `instance_bootstrap_invitation` — rather than making that column nullable.
6. **An Instance Admin already passes `company:members` in any company.** `PermissionEvaluator.evaluateCompanyWide` starts from `systemTermIfAccountActive(userId)` and the company gate only *adds* to it, so the SYSTEM term survives a company in which the operator holds no membership. This is why inviting into a company from the instance console can open a `CompanyContext` and call `MembershipInviteService` **unchanged** — no second invite path, no second implementation of the "already a member / invitation outstanding" rules.

### Two decisions this plan makes that need your sign-off before Task 8

- **A new `instance_bootstrap_invitation` table goes on the ERD §0.1 RLS exception list.** It is instance-level for the same reason `membership_invitation` is (the accept path runs before authentication, so no company context can exist). Precedent exists and the reasoning is identical, but §0.1 is authoritative and `SchemaConventionTest` derives from it. One table serves both callers — the first-boot bootstrap and an operator inviting another operator — because they differ only in who issued them, which is one nullable column, not a second table.
- **No `instance:read` permission is added.** The console's reads are gated on `company:create` — the permission that already means "may provision a company", held only by Instance Admin. The semantically cleaner option is a new `instance:read` key, but that is a change to the roles spec, which has authority over the catalogue. Gating reads on an existing write permission is the smaller lie and needs no migration.

### Preconditions

- The working tree currently carries the **uncommitted §121 flake-detection slice** (28 modified/untracked files across both stacks). Commit or stash it before Task 1 — this plan's phases stage their own paths, and `git add <paths>` over a dirty tree is how another slice's work gets interleaved.
- `docker compose up` must be running for every backend test (Testcontainers) and for `npm run dev`.

### Gates for every phase

Backend: `cd backend && ./gradlew build`. Frontend: `cd frontend && npm run build && npm run lint && npm run test:run`. Contract: `npm run gen:api` after **any** `openapi.yaml` edit. CI runs none of the backend tests — running `./gradlew build` locally is the only gate.

---

## File Structure

**Backend — create:**

| File | Responsibility |
|---|---|
| `backend/src/main/java/com/tcms/provisioning/InstanceCompanyController.java` | The `/api/v1/instance/companies` path family. Renders only. |
| `backend/src/main/java/com/tcms/provisioning/InstanceInviteController.java` | The `/api/v1/instance/invitations` path. |
| `backend/src/main/java/com/tcms/provisioning/InstanceOperatorGate.java` | The one place a SYSTEM permission is demanded, so eight endpoints cannot disagree. |
| `backend/src/main/java/com/tcms/provisioning/InstanceCompanyService.java` | List / detail / suspend / reinstate over `CompanyRepository`. |
| `backend/src/main/java/com/tcms/provisioning/InstanceCompanyView.java` | Roster row. |
| `backend/src/main/java/com/tcms/provisioning/InstanceCompanyDetailView.java` | Detail, incl. member and project counts. |
| `backend/src/main/java/com/tcms/provisioning/InstanceInviteRequest.java` | The invite payload; validates that `companyId` is present. |
| `backend/src/main/java/com/tcms/provisioning/CompanySuspensionService.java` | The status write, and the instance audit event beside it. |
| `backend/src/main/java/com/tcms/auth/SuspendedCompanyFilter.java` | Honours `SUSPENDED` on every authenticated request. |
| `backend/src/main/java/com/tcms/provisioning/BootstrapInvitation.java` | Entity for the one-shot bootstrap link. |
| `backend/src/main/java/com/tcms/provisioning/BootstrapInvitationRepository.java` | Its repository (`@Transactional(readOnly = true)` — non-negotiable, see CLAUDE.md). |
| `backend/src/main/java/com/tcms/provisioning/InstanceBootstrapService.java` | First-boot detection, minting, and acceptance. |
| `backend/src/main/java/com/tcms/provisioning/InstanceBootstrapRunner.java` | The `ApplicationRunner` that fires it on boot. |
| `backend/src/main/java/com/tcms/provisioning/BootstrapController.java` | The unauthenticated accept path, shared by both invitation kinds. |
| `backend/src/main/java/com/tcms/provisioning/InstanceInviteService.java` | Invites a person into a named company from outside it. |
| `backend/src/main/resources/db/migration/V39__instance_bootstrap_invitation.sql` | The table. |

**Backend — modify:** `auth/SecurityConfiguration.java` (permit the bootstrap accept path), `identity/InvitationMailer.java` + `SmtpInvitationMailer.java` (a second message shape), `tenancy/CompanyRepository.java` (roster query), `tenancy/SchemaConventionTest`'s exception list, `docs/specs/openapi.yaml`.

**Frontend — create:** `src/features/instance/` — `InstanceCompaniesPage.tsx`, `InstanceCompanyDetailPage.tsx`, `InstancePeoplePage.tsx`, `CreateCompanyDialog.tsx`, `SuspendCompanyDialog.tsx`, `InstanceInviteDialog.tsx`, `ReseedCompanyDialog.tsx`, `RequireInstanceAdmin.tsx`, plus `.test.tsx` beside each dialog. `src/api/queries/instance.ts`. `src/features/auth/BootstrapAcceptPage.tsx`.

**Frontend — modify:** `src/app/router.tsx`, `src/app/AppShell.tsx`, `src/api/queries/keys.ts`, `src/api/queries/index.ts`, `src/api/types.ts`, `src/lib/labels.ts`.

---

## Phase 1 — Suspension actually means something

### Task 1: `SuspendedCompanyFilter` refuses a suspended company

**Files:**
- Create: `backend/src/main/java/com/tcms/auth/SuspendedCompanyFilter.java`
- Modify: `backend/src/main/java/com/tcms/auth/SecurityConfiguration.java`
- Test: `backend/src/test/java/com/tcms/auth/SuspendedCompanyFilterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class SuspendedCompanyFilterTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired CompanyRepository companies;

    @Test
    void suspendedCompanyRefusesAnOrdinaryMembersRequest() throws Exception {
        // fixtureCompanyId()/memberToken() come from IntegrationTestBase — check the exact
        // helper names there and use them; do not invent new fixtures for this test.
        Company company = companies.findById(fixtureCompanyId()).orElseThrow();
        company.suspend();
        companies.save(company);

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/projects")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN.value()));
    }

    @Test
    void suspendedCompanyStillAdmitsAnInstanceAdmin() throws Exception {
        Company company = companies.findById(fixtureCompanyId()).orElseThrow();
        company.suspend();
        companies.save(company);

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/instance/companies")
                        .header("Authorization", "Bearer " + instanceAdminToken()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isEqualTo(HttpStatus.OK.value()));
    }
}
```

> **Note for the implementer:** `IntegrationTestBase` already provides company fixtures and token helpers. Open it and use its real helper names; the three used above are placeholders for whatever it actually calls them, and this is the **only** place in this plan where you must substitute. The second test will not compile until Task 4 — comment it out, and re-enable it in Task 4 Step 5.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.auth.SuspendedCompanyFilterTest'`
Expected: FAIL — the first test gets `200`, because nothing reads the status.

- [ ] **Step 3: Write the filter**

```java
package com.tcms.auth;

import com.tcms.tenancy.CompanyRepository;
import com.tcms.tenancy.CompanyStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Honours {@code company.status = SUSPENDED}, which until now nothing did.
 *
 * <p>{@code Company.suspend()} has carried the sentence "must be honoured in code: refuse
 * all non-Instance-Admin access … the status column alone enforces nothing" since V1, and
 * the column had no reader anywhere in {@code src/main}. Adding a suspend button to the
 * operator console without this filter would ship an affordance that changes a column and
 * nothing else — the worst kind of security control, the one that is believed.
 *
 * <p><strong>Instance Admins are exempt, deliberately.</strong> Suspension is the
 * offboarding answer (plan §6s) and the operator is who reverses it; locking them out of a
 * company they just suspended would make the state unrecoverable through the very console
 * this exists to serve.
 *
 * <p>Runs <em>after</em> {@link CompanyContextFilter}, so the active company is already
 * resolved from the session rather than from request input (ERD §0.1 point 2).
 */
@Component
public class SuspendedCompanyFilter extends OncePerRequestFilter {

    private final CompanyRepository companies;
    private final CurrentUserService users;

    public SuspendedCompanyFilter(CompanyRepository companies, CurrentUserService users) {
        this.companies = companies;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        UUID companyId = com.tcms.tenancy.CompanyContext.currentOrNull();
        if (companyId == null) {
            // An operator session with no company open, or an unauthenticated path. Nothing
            // to suspend, and the instance console must keep working.
            chain.doFilter(request, response);
            return;
        }

        boolean suspended = companies.findById(companyId)
                .map(c -> c.getStatus() == CompanyStatus.SUSPENDED)
                .orElse(false);

        if (suspended && !isInstanceAdmin(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Company suspended","status":403,\
                    "detail":"This company is suspended. Its data is intact; contact your \
                    instance operator to have access restored."}""");
            return;
        }
        chain.doFilter(request, response);
    }
}
```

> `isInstanceAdmin(HttpServletRequest)` resolves the caller from the JWT principal and asks
> `CurrentUserService.describe(userId, null).isSystemAdmin()`. Implement it against whatever
> accessor `CompanyContext` actually exposes for "current or null" — if there is no such
> method, add one beside `open(...)` rather than widening the guard into a getter.

- [ ] **Step 4: Register it after `CompanyContextFilter`**

In `SecurityConfiguration.java`, find the filter chain around line 99 (the comment beginning "already authenticated by the time the session machinery looks at it; pv next"). Add `.addFilterAfter(suspendedCompanyFilter, CompanyContextFilter.class)` in that same chain, and inject the bean into the configuration exactly as the neighbouring filters are.

- [ ] **Step 5: Run the test**

Run: `cd backend && ./gradlew test --tests 'com.tcms.auth.SuspendedCompanyFilterTest'`
Expected: PASS on the first test (second still commented out).

- [ ] **Step 6: Run the whole suite** — this filter sits in front of every authenticated request, so a mistake here fails broadly and you want to know now.

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/tcms/auth/SuspendedCompanyFilter.java \
        backend/src/main/java/com/tcms/auth/SecurityConfiguration.java \
        backend/src/test/java/com/tcms/auth/SuspendedCompanyFilterTest.java
git commit -m "feat(tenancy): honour company suspension on every authenticated request

The status column has had a writer since V1 and no reader anywhere in src/main.
Instance Admins stay exempt so a suspension remains reversible from the console."
```

---

## Phase 2 — The instance read surface

### Task 2: `InstanceOperatorGate` — one place a SYSTEM permission is demanded

**Files:**
- Create: `backend/src/main/java/com/tcms/provisioning/InstanceOperatorGate.java`
- Test: `backend/src/test/java/com/tcms/provisioning/InstanceOperatorGateTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tcms.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

class InstanceOperatorGateTest extends IntegrationTestBase {

    @Autowired InstanceOperatorGate gate;

    @Test
    void anInstanceAdminPasses() {
        assertThat(gate.holds(instanceAdminUserId(), "company:create")).isTrue();
    }

    @Test
    void aCompanyAdminDoesNot() {
        // Company Admin holds NO system-scope permission by construction (V3's grant list).
        assertThat(gate.holds(companyAdminUserId(), "company:create")).isFalse();
    }

    @Test
    void demandThrowsForANonOperator() {
        assertThatThrownBy(() -> gate.demand(companyAdminUserId(), "company:suspend"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnknownPermissionKeyThrowsRatherThanQuietlyDenying() {
        assertThatThrownBy(() -> gate.holds(instanceAdminUserId(), "company:invented"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.InstanceOperatorGateTest'`
Expected: FAIL — `InstanceOperatorGate` does not exist.

- [ ] **Step 3: Write it**

```java
package com.tcms.provisioning;

import com.tcms.identity.PermissionEvaluator;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The single place the instance console demands a SYSTEM-scope permission.
 *
 * <p>Eight endpoints across two controllers answer to seven permission keys. Each one
 * asking {@link PermissionEvaluator} directly is how two of them end up disagreeing about
 * what "operator" means — the bug class plan §6b exists to prevent, one tier up from the
 * project gates it usually describes.
 *
 * <p>{@code evaluateInstanceWide} is the right evaluator and the only one that works here:
 * it resolves the SYSTEM term with <strong>no company context open</strong>, which is the
 * state every request to this console is in. The company-scoped evaluator would return an
 * empty set and every operator would be refused.
 *
 * <p>{@code PermissionSet.has} throws on a key the catalogue does not contain, so a typo in
 * a permission name is a loud failure rather than a permanent silent denial. That property
 * is why this class does not defend against unknown keys itself.
 */
@Component
public class InstanceOperatorGate {

    private final PermissionEvaluator permissions;

    public InstanceOperatorGate(PermissionEvaluator permissions) {
        this.permissions = permissions;
    }

    /** Whether this account holds a SYSTEM-scope permission. */
    public boolean holds(UUID userId, String permissionKey) {
        return permissions.evaluateInstanceWide(userId).has(permissionKey);
    }

    /**
     * Demands it, or refuses with 403.
     *
     * <p>403 rather than 404: unlike a project, whose existence is itself confidential, the
     * instance console is a fixed path family. Hiding it behind a 404 would tell a curious
     * Company Admin nothing they cannot learn from the login page, and would cost the
     * operator a clear message the day a grant is missing.
     */
    public void demand(UUID userId, String permissionKey) {
        if (!holds(userId, permissionKey)) {
            throw new AccessDeniedException(
                    "This endpoint requires the SYSTEM-scope permission '" + permissionKey
                            + "', held only by an Instance Admin.");
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.InstanceOperatorGateTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/tcms/provisioning/InstanceOperatorGate.java \
        backend/src/test/java/com/tcms/provisioning/InstanceOperatorGateTest.java
git commit -m "feat(provisioning): one gate for the instance console's SYSTEM permissions"
```

---

### Task 3: The company roster contract

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Modify: `frontend/src/api/schema.d.ts` (generated — never hand-edited)

- [ ] **Step 1: Add the schemas**

Add to `components.schemas` in `docs/specs/openapi.yaml`. **Every object gets a `required` array, including the nested one** — `required` does not descend, and without it every generated property is optional and no consumer can use it (working agreement).

```yaml
    InstanceCompany:
      type: object
      description: >
        One company as the instance operator sees it. This is the only shape in the
        contract that crosses the client boundary: every other company-scoped response is
        already narrowed to the caller's own company by RLS. It carries no client content —
        counts, not names of things inside.
      properties:
        id: { type: string, format: uuid }
        slug:
          type: string
          description: Immutable after provisioning; it appears in every URL (plan §6t).
        displayName: { type: string }
        status: { $ref: '#/components/schemas/CompanyStatus' }
        createdAt: { type: string, format: date-time }
        memberCount: { type: integer, minimum: 0 }
        projectCount: { type: integer, minimum: 0 }
      required: [id, slug, displayName, status, createdAt, memberCount, projectCount]

    CompanyStatus:
      type: string
      enum: [ACTIVE, SUSPENDED]

    ProvisionCompanyInput:
      type: object
      description: >
        Mirrors ProvisionCompanyRequest's validation exactly. The formats are the database's
        own constraints (ck_company_slug_format, ck_project_key_format) restated so a bad
        value is refused before a transaction opens.
      properties:
        slug: { type: string, pattern: '^[a-z0-9]+(-[a-z0-9]+)*$' }
        displayName: { type: string, minLength: 1 }
        adminEmail: { type: string, format: email }
        adminName: { type: string, minLength: 1 }
        firstProject:
          type: object
          nullable: true
          properties:
            key: { type: string, pattern: '^[A-Z0-9]{2,10}$' }
            name: { type: string, minLength: 1 }
          required: [key, name]
      required: [slug, displayName, adminEmail, adminName]

    ProvisionCompanyOutcome:
      type: object
      description: >
        What the run did, per part — not one boolean. Provisioning is idempotent and
        re-running it to top up a company after a release is a normal use, so "it succeeded"
        does not tell the operator whether anything changed. Mirrors ProvisionCompanyResult.
      properties:
        company: { $ref: '#/components/schemas/InstanceCompany' }
        companyCreated: { type: boolean }
        adminUserCreated: { type: boolean }
        membershipCreated: { type: boolean }
        adminGrantCreated: { type: boolean }
        baselineGrantsCreated: { type: integer, minimum: 0 }
        projectCreated: { type: boolean }
        invitationLink:
          type: string
          nullable: true
          description: >
            Present ONLY when this run minted the token. Absent when an unexpired invitation
            was already outstanding — its secret is unrecoverable, since only a digest is
            stored. Absent also when no SMTP relay is configured AND the link was returned
            in-band instead; see invitationDelivered.
        invitationExpiry: { type: string, format: date-time, nullable: true }
        invitationDelivered:
          type: boolean
          description: >
            Whether the activation link was emailed. False means the operator must carry it
            by hand — the console shows it once and never again.
      required:
        [company, companyCreated, adminUserCreated, membershipCreated, adminGrantCreated,
         baselineGrantsCreated, projectCreated, invitationDelivered]
```

- [ ] **Step 2: Add the paths**

```yaml
  /instance/companies:
    get:
      tags: [instance]
      summary: Every company on this instance
      description: >
        Requires `company:create`, a SYSTEM-scope permission held only by Instance Admin.
        Gating a read on a write permission is deliberate: the catalogue has no
        `instance:read` key, and adding one is a change to the roles spec, which has
        authority here. See the plan's "decisions needing sign-off".
      responses:
        '200':
          description: The roster, newest first.
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/InstanceCompany' }
        '403': { $ref: '#/components/responses/Forbidden' }
    post:
      tags: [instance]
      summary: Provision a company
      description: >
        Requires `company:create`. Idempotent by slug — re-running against an existing
        company tops up vocabularies and display-key counters a later release added, and
        never updates what is already there.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/ProvisionCompanyInput' }
      responses:
        '201':
          description: The company was created by this call.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ProvisionCompanyOutcome' }
        '200':
          description: >
            The slug already existed. The body reports what, if anything, this run topped up.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ProvisionCompanyOutcome' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '403': { $ref: '#/components/responses/Forbidden' }

  /instance/companies/{companyId}:
    parameters:
      - name: companyId
        in: path
        required: true
        schema: { type: string, format: uuid }
    get:
      tags: [instance]
      summary: One company
      responses:
        '200':
          description: The company.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/InstanceCompany' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /instance/companies/{companyId}/suspension:
    parameters:
      - name: companyId
        in: path
        required: true
        schema: { type: string, format: uuid }
    put:
      tags: [instance]
      summary: Suspend the company
      description: >
        Requires `company:suspend`. Data is untouched; all non-Instance-Admin access is
        refused and ingestion stops. This is the offboarding answer, and it is reversible —
        deletion is not, which is why `company:delete` exists in the catalogue and has no
        endpoint here.
      responses:
        '200':
          description: Suspended. Idempotent — suspending a suspended company is a 200.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/InstanceCompany' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
    delete:
      tags: [instance]
      summary: Reinstate the company
      responses:
        '200':
          description: Active again.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/InstanceCompany' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
```

Reuse the existing `#/components/responses/*` names — check what `openapi.yaml` actually calls them (`Forbidden`, `BadRequest`, `NotFound`) and match, rather than defining new ones.

- [ ] **Step 3: Regenerate the client types**

Run: `cd frontend && npm run gen:api`
Expected: `src/api/schema.d.ts` gains `InstanceCompany`, `ProvisionCompanyInput`, `ProvisionCompanyOutcome` and the four paths. **Do not hand-edit the output.**

- [ ] **Step 4: Verify the frontend still compiles**

Run: `cd frontend && npm run build`
Expected: PASS — nothing consumes the new types yet.

- [ ] **Step 5: Commit**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts
git commit -m "contract: the instance console's company surface"
```

---

### Task 4: `InstanceCompanyService` + `InstanceCompanyController` (reads)

**Files:**
- Create: `backend/src/main/java/com/tcms/provisioning/InstanceCompanyView.java`
- Create: `backend/src/main/java/com/tcms/provisioning/InstanceCompanyService.java`
- Create: `backend/src/main/java/com/tcms/provisioning/InstanceCompanyController.java`
- Modify: `backend/src/main/java/com/tcms/tenancy/CompanyRepository.java`
- Test: `backend/src/test/java/com/tcms/provisioning/InstanceCompanyEndpointTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tcms.provisioning;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcms.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class InstanceCompanyEndpointTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    @Test
    void anInstanceAdminSeesEveryCompanyIncludingOnesTheyAreNotAMemberOf() throws Exception {
        mvc.perform(get("/api/v1/instance/companies")
                        .header("Authorization", "Bearer " + instanceAdminToken()))
                .andExpect(status().isOk())
                // The two-company fixture harness has run since M0, so there is more than one.
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(1)))
                .andExpect(jsonPath("$[0].slug").exists())
                .andExpect(jsonPath("$[0].memberCount").exists());
    }

    @Test
    void aCompanyAdminIsRefused() throws Exception {
        mvc.perform(get("/api/v1/instance/companies")
                        .header("Authorization", "Bearer " + companyAdminToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousCallerIsRefused() throws Exception {
        mvc.perform(get("/api/v1/instance/companies"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.InstanceCompanyEndpointTest'`
Expected: FAIL — 404 on all three, the path does not exist.

- [ ] **Step 3: Write the view**

```java
package com.tcms.provisioning;

import com.tcms.tenancy.CompanyStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One company, as the instance operator sees it.
 *
 * <p><strong>Counts, never contents.</strong> This is the only response shape in the
 * product that crosses the client boundary — every other company-scoped read has already
 * been narrowed to the caller's own company by RLS before it reaches a view. An operator
 * needs to know a company exists and roughly how live it is; putting project names or
 * member addresses here would turn the roster into a client-list disclosure surface for a
 * screen whose whole job is administrative.
 */
public record InstanceCompanyView(
        UUID id,
        String slug,
        String displayName,
        CompanyStatus status,
        Instant createdAt,
        long memberCount,
        long projectCount) {
}
```

- [ ] **Step 4: Add the roster query**

In `backend/src/main/java/com/tcms/tenancy/CompanyRepository.java` — the interface already carries `@Transactional(readOnly = true)`; confirm it does and add it if not, because a derived method without it runs outside a transaction and returns zero rows (CLAUDE.md).

```java
    /**
     * Every company with its member and project counts, newest first.
     *
     * <p>Left joins so a freshly provisioned company with no projects appears with a zero
     * rather than vanishing — an inner join here would hide exactly the company an operator
     * just created and is looking for.
     *
     * <p>{@code company} is on the ERD §0.1 RLS exception list, so this crosses no policy and
     * needs no context. That is the property the whole instance console rests on.
     */
    @Query("""
            SELECT new com.tcms.provisioning.InstanceCompanyView(
                     c.id, c.slug, c.displayName, c.status, c.createdAt,
                     (SELECT COUNT(m) FROM CompanyMembership m WHERE m.companyId = c.id),
                     (SELECT COUNT(p) FROM Project p WHERE p.companyId = c.id))
              FROM Company c
             ORDER BY c.createdAt DESC
            """)
    List<InstanceCompanyView> findAllForOperator();
```

> Correlated subqueries rather than `LEFT JOIN … GROUP BY`: two independent one-to-many joins in one query multiply each other's rows, and the counts would both come back as their product. Verify the entity names — `CompanyMembership` and `Project` — and their `companyId` field names against the actual entities before running.

- [ ] **Step 5: Write the service and controller**

```java
package com.tcms.provisioning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads across every company. See {@link InstanceCompanyView} on why it returns counts. */
@Service
public class InstanceCompanyService {

    private final com.tcms.tenancy.CompanyRepository companies;

    public InstanceCompanyService(com.tcms.tenancy.CompanyRepository companies) {
        this.companies = companies;
    }

    @Transactional(readOnly = true)
    public List<InstanceCompanyView> list() {
        return companies.findAllForOperator();
    }

    @Transactional(readOnly = true)
    public Optional<InstanceCompanyView> find(UUID companyId) {
        return companies.findAllForOperator().stream()
                .filter(c -> c.id().equals(companyId))
                .findFirst();
    }
}
```

> `find` filters the roster rather than adding a second query. The company count on a
> self-hosted instance is small and bounded by design (plan §6s: "no self-service signup …
> keeps company count small and bounded"), and one query with one shape cannot drift from
> itself. Revisit only if the roster ever needs pagination.

```java
package com.tcms.provisioning;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The instance operator's company surface — the screens that replace {@code
 * provision-company} at a terminal.
 *
 * <h2>Why this lives in {@code provisioning} and not {@code tenancy}</h2>
 *
 * <p>Every write here is a provisioning act, and {@link CompanyProvisioningService} is
 * already the one path that may call the seed functions. A controller in {@code tenancy}
 * would either duplicate that rule or reach across a module boundary to borrow it.
 *
 * <h2>No company context, and that is the point</h2>
 *
 * <p>Every request to this controller arrives on a session with no active company, which
 * {@code CompanyContextFilter} treats as legitimate and which
 * {@code PermissionEvaluator.evaluateInstanceWide} is built to answer. {@code company} is on
 * the ERD §0.1 RLS exception list, so these reads cross no policy. A {@code companyId} path
 * variable here names a <em>target</em>, exactly as {@code MembershipInvitation.companyId}
 * does — it never selects the tenancy discriminator.
 */
@RestController
@RequestMapping("/api/v1/instance")
public class InstanceCompanyController {

    private final InstanceCompanyService companies;
    private final InstanceOperatorGate gate;

    public InstanceCompanyController(InstanceCompanyService companies, InstanceOperatorGate gate) {
        this.companies = companies;
        this.gate = gate;
    }

    @GetMapping("/companies")
    public List<InstanceCompanyView> list(@AuthenticationPrincipal Jwt jwt) {
        gate.demand(caller(jwt), "company:create");
        return companies.list();
    }

    @GetMapping("/companies/{companyId}")
    public ResponseEntity<InstanceCompanyView> one(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID companyId) {
        gate.demand(caller(jwt), "company:create");
        return companies.find(companyId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
```

> `caller(Jwt)` must match how `AdminController` extracts the subject — open it and copy that
> helper exactly rather than assuming `getSubject()` is the claim used.

- [ ] **Step 6: Re-enable Task 1's second test** — uncomment `suspendedCompanyStillAdmitsAnInstanceAdmin` in `SuspendedCompanyFilterTest`; the path it calls now exists.

- [ ] **Step 7: Run both test classes**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.InstanceCompanyEndpointTest' --tests 'com.tcms.auth.SuspendedCompanyFilterTest'`
Expected: PASS, all five tests.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/tcms/provisioning/InstanceCompany*.java \
        backend/src/main/java/com/tcms/tenancy/CompanyRepository.java \
        backend/src/test/java/com/tcms/provisioning/InstanceCompanyEndpointTest.java \
        backend/src/test/java/com/tcms/auth/SuspendedCompanyFilterTest.java
git commit -m "feat(provisioning): the instance company roster over HTTP"
```

---

## Phase 3 — The instance write surface

### Task 5: `POST /instance/companies` — provision from the console

**Files:**
- Modify: `backend/src/main/java/com/tcms/provisioning/InstanceCompanyController.java`
- Create: `backend/src/main/java/com/tcms/provisioning/ProvisionCompanyInput.java`
- Create: `backend/src/main/java/com/tcms/provisioning/ProvisionCompanyOutcomeView.java`
- Test: `backend/src/test/java/com/tcms/provisioning/InstanceProvisionEndpointTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tcms.provisioning;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcms.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class InstanceProvisionEndpointTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    private static final String BODY = """
            {"slug":"console-made","displayName":"Console Made Ltd",
             "adminEmail":"admin@console-made.test","adminName":"C. Admin"}
            """;

    @Test
    void provisioningACompanyReturns201AndReportsWhatItCreated() throws Exception {
        mvc.perform(post("/api/v1/instance/companies")
                        .header("Authorization", "Bearer " + instanceAdminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyCreated").value(true))
                .andExpect(jsonPath("$.company.slug").value("console-made"))
                .andExpect(jsonPath("$.company.status").value("ACTIVE"))
                .andExpect(jsonPath("$.adminUserCreated").value(true));
    }

    @Test
    void reProvisioningTheSameSlugReturns200AndReportsNoCreation() throws Exception {
        mvc.perform(post("/api/v1/instance/companies")
                .header("Authorization", "Bearer " + instanceAdminToken())
                .contentType(MediaType.APPLICATION_JSON).content(BODY));

        mvc.perform(post("/api/v1/instance/companies")
                        .header("Authorization", "Bearer " + instanceAdminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyCreated").value(false));
    }

    @Test
    void aMalformedSlugIs400NotA500FromTheDriver() throws Exception {
        mvc.perform(post("/api/v1/instance/companies")
                        .header("Authorization", "Bearer " + instanceAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("console-made", "Console Made")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCompanyAdminIsRefused() throws Exception {
        mvc.perform(post("/api/v1/instance/companies")
                        .header("Authorization", "Bearer " + companyAdminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.InstanceProvisionEndpointTest'`
Expected: FAIL — 405/404, there is no POST handler.

- [ ] **Step 3: Write the request and response DTOs**

```java
package com.tcms.provisioning;

import java.util.Optional;

/**
 * The wire shape of a provisioning request.
 *
 * <p>Separate from {@link ProvisionCompanyRequest} on purpose. That record validates in its
 * canonical constructor and throws {@link IllegalArgumentException} — correct for a CLI, and
 * a 500 for a controller, because Jackson constructs it during deserialization, before any
 * handler or {@code @ExceptionHandler} of ours can turn the failure into a 400. This one
 * deserializes without judgement; {@link #toRequest()} is where validation happens, inside
 * the handler, where the exception can become a ProblemDetail.
 */
public record ProvisionCompanyInput(
        String slug,
        String displayName,
        String adminEmail,
        String adminName,
        FirstProjectInput firstProject) {

    public record FirstProjectInput(String key, String name) {
    }

    public ProvisionCompanyRequest toRequest() {
        return firstProject == null
                ? ProvisionCompanyRequest.of(slug, displayName, adminEmail, adminName)
                : ProvisionCompanyRequest.withFirstProject(
                        slug, displayName, adminEmail, adminName,
                        firstProject.key(), firstProject.name());
    }
}
```

```java
package com.tcms.provisioning;

import java.time.Instant;

/**
 * {@link ProvisionCompanyResult} rendered for the console, plus the one fact the CLI never
 * needed: whether the activation link was actually delivered.
 *
 * <p>An operator at a terminal reads the link off their own screen. An operator in a browser
 * cannot, unless it is in this body — and it is in this body <em>only</em> when the mailer is
 * unconfigured, because a link that was emailed should not also be sitting in a browser's
 * memory, a proxy log, and a query cache. {@code invitationDelivered} is what lets the UI
 * decide which of the two things to say.
 */
public record ProvisionCompanyOutcomeView(
        InstanceCompanyView company,
        boolean companyCreated,
        boolean adminUserCreated,
        boolean membershipCreated,
        boolean adminGrantCreated,
        int baselineGrantsCreated,
        boolean projectCreated,
        String invitationLink,
        Instant invitationExpiry,
        boolean invitationDelivered) {
}
```

- [ ] **Step 4: Add the handler**

Add to `InstanceCompanyController`, injecting `CompanyProvisioningService`, `InstanceCompanyService` and `InvitationMailer`:

```java
    /**
     * Provisions a company, or tops up one that exists.
     *
     * <p>201 when this call created the company, 200 when the slug was already taken —
     * including by a re-run that repaired vocabularies a later release added. Both are
     * successes and the body says which, because {@link ProvisionCompanyResult} was designed
     * around exactly that distinction.
     */
    @PostMapping("/companies")
    public ResponseEntity<ProvisionCompanyOutcomeView> provision(
            @AuthenticationPrincipal Jwt jwt, @RequestBody ProvisionCompanyInput input) {

        gate.demand(caller(jwt), "company:create");

        ProvisionCompanyResult result = provisioning.provision(input.toRequest());
        InstanceCompanyView company = companies.find(result.companyId()).orElseThrow(
                () -> new IllegalStateException(
                        "Provisioning reported company " + result.companyId()
                                + " but the roster query cannot see it."));

        boolean delivered = mailer.isConfigured() && result.invitationLink().isPresent();
        ProvisionCompanyOutcomeView body = new ProvisionCompanyOutcomeView(
                company,
                result.companyCreated(),
                result.adminUserCreated(),
                result.membershipCreated(),
                result.adminGrantCreated(),
                result.baselineGrantsCreated(),
                result.projectCreated(),
                // Withheld when it was emailed: see ProvisionCompanyOutcomeView's header.
                delivered ? null : result.invitationLink().orElse(null),
                result.invitationExpiry().orElse(null),
                delivered);

        return result.companyCreated()
                ? ResponseEntity.status(HttpStatus.CREATED).body(body)
                : ResponseEntity.ok(body);
    }

    /**
     * Turns the request record's own validation into a 400.
     *
     * <p>{@link ProvisionCompanyRequest} throws {@link IllegalArgumentException} with a
     * message written for a human — "slug must be lower-case alphanumerics…". That message is
     * the most useful thing this endpoint can say, so it is passed through rather than
     * replaced with a generic one.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail malformed(IllegalArgumentException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid provisioning request");
        return problem;
    }
```

Confirm the exact method name on `CompanyProvisioningService` (it may be `provision`, `provisionCompany`, or `execute`) and match it.

- [ ] **Step 5: Run the tests**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.InstanceProvisionEndpointTest'`
Expected: PASS, all four.

- [ ] **Step 6: Full backend gate**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/tcms/provisioning/ \
        backend/src/test/java/com/tcms/provisioning/InstanceProvisionEndpointTest.java
git commit -m "feat(provisioning): provision a company over HTTP

Re-runs report per-part idempotency, and the activation link is returned in-band
only when no relay is configured to deliver it."
```

---

### Task 6: Suspend / reinstate, and the `/seeds` re-run

**Files:**
- Create: `backend/src/main/java/com/tcms/provisioning/CompanySuspensionService.java`
- Modify: `backend/src/main/java/com/tcms/provisioning/InstanceCompanyController.java`
- Test: `backend/src/test/java/com/tcms/provisioning/CompanySuspensionEndpointTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tcms.provisioning;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcms.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class CompanySuspensionEndpointTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    @Test
    void suspendThenReinstateRoundTrips() throws Exception {
        String path = "/api/v1/instance/companies/" + fixtureCompanyId() + "/suspension";

        mvc.perform(put(path).header("Authorization", "Bearer " + instanceAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mvc.perform(delete(path).header("Authorization", "Bearer " + instanceAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void suspendingASuspendedCompanyIsStill200() throws Exception {
        String path = "/api/v1/instance/companies/" + fixtureCompanyId() + "/suspension";
        mvc.perform(put(path).header("Authorization", "Bearer " + instanceAdminToken()));
        mvc.perform(put(path).header("Authorization", "Bearer " + instanceAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    void aCompanyAdminCannotSuspendTheirOwnCompany() throws Exception {
        mvc.perform(put("/api/v1/instance/companies/" + fixtureCompanyId() + "/suspension")
                        .header("Authorization", "Bearer " + companyAdminToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendingAnUnknownCompanyIs404() throws Exception {
        mvc.perform(put("/api/v1/instance/companies/"
                        + java.util.UUID.randomUUID() + "/suspension")
                        .header("Authorization", "Bearer " + instanceAdminToken()))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.CompanySuspensionEndpointTest'`
Expected: FAIL — 404/405.

- [ ] **Step 3: Write the service**

```java
package com.tcms.provisioning;

import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyRepository;
import com.tcms.tenancy.InstanceAuditEvent;
import com.tcms.tenancy.InstanceAuditEventRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suspends and reinstates a company.
 *
 * <p>Both directions are idempotent and both write an instance audit event. Suspension is
 * the offboarding answer (plan §6s) — "far safer than deletion", and reversible, which is
 * why this service has no delete counterpart even though {@code company:delete} is in the
 * catalogue. The catalogue's own description of that key reads "Purge a company — policy is
 * suspend, never delete"; giving it an endpoint would make the policy a suggestion.
 *
 * <p>The refusal itself lives in {@link com.tcms.auth.SuspendedCompanyFilter}. This class
 * only moves the column, and that separation is the point: a status column with no reader is
 * what this codebase had until that filter landed.
 */
@Service
public class CompanySuspensionService {

    private static final Logger log = LoggerFactory.getLogger(CompanySuspensionService.class);

    private final CompanyRepository companies;
    private final InstanceAuditEventRepository auditEvents;
    private final Clock clock;

    public CompanySuspensionService(
            CompanyRepository companies,
            InstanceAuditEventRepository auditEvents,
            Clock clock) {
        this.companies = companies;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    @Transactional
    public Optional<Company> suspend(UUID companyId, UUID actorUserId) {
        return transition(companyId, actorUserId, true);
    }

    @Transactional
    public Optional<Company> reinstate(UUID companyId, UUID actorUserId) {
        return transition(companyId, actorUserId, false);
    }

    private Optional<Company> transition(UUID companyId, UUID actorUserId, boolean suspending) {
        return companies.findById(companyId).map(company -> {
            if (suspending) {
                company.suspend();
            } else {
                company.reinstate();
            }
            companies.save(company);

            // INFO, and in the instance trail rather than the company's: the company's own
            // administrators are precisely the people who cannot see this happen, and the
            // question it answers ("who cut us off, and when") is an operator question.
            log.info("Company {} ({}) {} by user {}",
                    company.getSlug(), companyId,
                    suspending ? "SUSPENDED" : "reinstated", actorUserId);
            auditEvents.save(InstanceAuditEvent.of(
                    UUID.randomUUID(),
                    suspending ? "company.suspended" : "company.reinstated",
                    companyId, actorUserId, clock.instant()));
            return company;
        });
    }
}
```

> Check `InstanceAuditEvent`'s actual factory signature before writing this — the argument
> order above is a reasonable guess at a shape the class already has, and it must match.

- [ ] **Step 4: Add the handlers**

```java
    @PutMapping("/companies/{companyId}/suspension")
    public ResponseEntity<InstanceCompanyView> suspend(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID companyId) {
        gate.demand(caller(jwt), "company:suspend");
        return suspension.suspend(companyId, caller(jwt))
                .flatMap(c -> companies.find(companyId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/companies/{companyId}/suspension")
    public ResponseEntity<InstanceCompanyView> reinstate(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID companyId) {
        gate.demand(caller(jwt), "company:suspend");
        return suspension.reinstate(companyId, caller(jwt))
                .flatMap(c -> companies.find(companyId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

- [ ] **Step 5: Run the tests**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.CompanySuspensionEndpointTest'`
Expected: PASS, all four.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/tcms/provisioning/ \
        backend/src/test/java/com/tcms/provisioning/CompanySuspensionEndpointTest.java
git commit -m "feat(provisioning): suspend and reinstate a company from the console"
```

> **The `/seeds` re-run needs no new endpoint.** `POST /instance/companies` with an existing
> slug *is* the re-run — that is what `CompanyProvisioningService`'s idempotency is for, and
> `ProvisionCompanyOutcome.baselineGrantsCreated` is exactly the "what did the top-up repair"
> signal. The console surfaces it as a "Re-run seeds" button on the detail page that POSTs the
> existing company's slug, name and admin. No second path, no second implementation of the
> seed rule.

---

## Phase 4 — Bootstrapping the operator, and inviting people

> **Read before starting:** this phase is why the plan asks for sign-off on an ERD §0.1
> exception. It also rests on two properties confirmed during planning:
>
> - `MailConfiguration.UnconfiguredInvitationMailer` **deliberately does not log invitation
>   links**, because a link is a credential and only a digest of the token is stored — so an
>   undelivered invitation is unrecoverable and cannot be reprinted. The bootstrap therefore
>   refuses to mint one at all unless a relay is configured (Task 8), rather than minting a
>   link nobody can read and leaving the instance with no operator.
> - `app_user.email` and `canonical_name` are both `NOT NULL` and `AppUser.invite` requires
>   both, so the bootstrap **creates the account before it mints the token**, at the configured
>   sender address. The invitation is bound to that account, exactly as `membership_invitation`
>   is bound to a membership — never a bearer token whose holder picks their own identity.

### Task 7: The bootstrap invitation table

**Files:**
- Create: `backend/src/main/resources/db/migration/V39__instance_bootstrap_invitation.sql`
- Modify: `backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java`
- Modify: `docs/specs/2026-07-30-tcms-erd.md` (§0.1 exception list)

- [ ] **Step 1: Write the migration**

```sql
-- =============================================================================
-- V39 — the instance bootstrap invitation
-- =============================================================================
--
-- The one-shot link that activates an instance's FIRST Instance Admin.
-- Minted by exactly one caller — the first-boot runner, on a boot where no
-- operator exists — and never by a user action: there is no UI and no endpoint
-- that creates an Instance Admin, by decision. Operators after the first come from
-- grant-instance-admin on the CLI. See "Operators are created on the CLI".
--
-- Bound to an account, exactly as membership_invitation is. The operator's
-- address and name are supplied when the link is minted, the app_user row is
-- created INVITED at the same moment, and the link admits nobody else: an
-- identity-less token would be a bearer credential that made whoever held it the
-- operator under any address they chose. This is the same shape every other
-- invitation in the product has, and it is deliberately not novel.
--
-- INSTANCE-LEVEL, and therefore on the ERD §0.1 RLS exception list, for exactly
-- the reason membership_invitation is (decided in V6): the accept path runs
-- BEFORE authentication, so no company context can exist when the token is
-- redeemed, and a company-filtered lookup would find nothing.
--
-- Why not reuse membership_invitation: its company_id is NOT NULL and names the
-- company the holder is admitted TO. An Instance Admin is admitted to no company
-- — that is what makes them an operator rather than a member — so there is no
-- value that column could honestly hold. Making it nullable would put "is this a
-- member invite or an operator invite" into a null check on every reader of the
-- busier table, and RLS-exempt the whole of it.
--
-- There is no created_by column either. Every row here is minted by the instance
-- itself with nobody to attribute it to — the same reason
-- instance_role_assignment.granted_by is nullable and is written NULL by the
-- bootstrap path.

CREATE TABLE instance_bootstrap_invitation (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES app_user (id),
    token_hash   TEXT        NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL,
    lock_version BIGINT      NOT NULL DEFAULT 0
);

COMMENT ON TABLE instance_bootstrap_invitation IS
    'The one-shot link that activates an instance''s FIRST Instance Admin. '
    'Instance-level: no company_id, no RLS, no company filter (ERD §0.1). Mirrors '
    'membership_invitation exactly, minus the company — the accept path runs '
    'before authentication, and the invitation is bound to one account.';

COMMENT ON COLUMN instance_bootstrap_invitation.user_id IS
    'The account this admits. Created INVITED at the configured sender address '
    '(tcms.invitation.from-address) when the link is minted. Binding the link to '
    'an account is what stops it being a bearer credential.';

COMMENT ON COLUMN instance_bootstrap_invitation.token_hash IS
    'A digest, never the secret. The link is emailed once and is unrecoverable '
    'afterwards — it cannot be reprinted from this table, which is why the bootstrap '
    'refuses to run at all without a configured relay.';

-- At most ONE unconsumed invitation may exist. A partial unique index over a
-- constant rather than a service-level check, because two application instances
-- booting together is exactly when the check-then-mint race happens and exactly
-- when nobody is watching.
CREATE UNIQUE INDEX instance_bootstrap_invitation_one_pending_idx
    ON instance_bootstrap_invitation ((TRUE))
    WHERE consumed_at IS NULL;
```

- [ ] **Step 2: Add it to the schema test's exception list**

In `SchemaConventionTest`, find the set of instance-level tables exempt from the `company_id` rule (it will already name `company`, `app_user`, `membership_invitation`, `instance_role_assignment`). Add `instance_bootstrap_invitation` with a one-line comment pointing at V39's header. The test derives RLS expectations from `pg_class.relrowsecurity`, so this table must be asserted **not** to have RLS, exactly as `membership_invitation` is.

- [ ] **Step 3: Record the decision in the ERD**

In `docs/specs/2026-07-30-tcms-erd.md` §0.1, add `instance_bootstrap_invitation` to the exception list with its reason (pre-authentication accept path, no company to belong to). §0.1 is authoritative; a table on the list without a written reason is how the list stops meaning anything.

- [ ] **Step 4: Run the schema tests**

Run: `cd backend && ./gradlew test --tests 'com.tcms.tenancy.SchemaConventionTest' --tests 'com.tcms.tenancy.RlsIsolationTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V39__instance_bootstrap_invitation.sql \
        backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java \
        docs/specs/2026-07-30-tcms-erd.md
git commit -m "feat(provisioning): the instance invitation table

Admits its holder to the instance rather than to a company. Instance-level for
membership_invitation's reason: the accept path runs before authentication, so no
company context can exist. ERD §0.1 updated."
```

---

### Task 8: Mint the bootstrap invitation on first boot and email it to the sender address

**Files:**
- Create: `backend/src/main/java/com/tcms/provisioning/BootstrapInvitation.java`, `BootstrapInvitationRepository.java`, `InstanceBootstrapService.java`, `InstanceBootstrapRunner.java`
- Modify: `backend/src/main/java/com/tcms/identity/InvitationMailer.java`, `SmtpInvitationMailer.java`, `MailConfiguration.java`
- Test: `backend/src/test/java/com/tcms/provisioning/InstanceBootstrapServiceTest.java`

> **Read before starting.** `MailConfiguration.UnconfiguredInvitationMailer` **deliberately
> does not log the link** — it is a credential, and only a digest of the token is stored, so an
> undelivered invitation is unrecoverable and cannot be reprinted. A bootstrap that minted one
> with no relay configured would consume the instance's one-shot for nothing and leave it with
> no operator. This service therefore refuses outright unless `InvitationMailer.isConfigured()`
> is true, and says so at ERROR with the fix in the message.

- [ ] **Step 1: Write the failing test**

```java
package com.tcms.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class InstanceBootstrapServiceTest extends IntegrationTestBase {

    @Autowired InstanceBootstrapService bootstrap;

    @Test
    void doesNothingWhenAnInstanceAdminAlreadyExists() {
        // The fixture instance has one, so this is the steady state on every boot but the first.
        assertThat(bootstrap.bootstrapIfEmpty())
                .isEqualTo(InstanceBootstrapService.Outcome.OPERATOR_ALREADY_EXISTS);
    }

    @Test
    void refusesWithNoRelayRatherThanMintingAnUndeliverableLink() {
        clearInstanceAdmins();
        assertThat(bootstrap.bootstrapIfEmpty())
                .isEqualTo(InstanceBootstrapService.Outcome.NO_MAIL_RELAY);
        assertThat(bootstrap.pendingCount()).isZero();
    }

    @Test
    void mintsExactlyOneLinkAndIsIdempotentAcrossBoots() {
        clearInstanceAdmins();
        withConfiguredMailer(() -> {
            assertThat(bootstrap.bootstrapIfEmpty())
                    .isEqualTo(InstanceBootstrapService.Outcome.INVITATION_SENT);
            assertThat(bootstrap.bootstrapIfEmpty())
                    .isEqualTo(InstanceBootstrapService.Outcome.INVITATION_ALREADY_PENDING);
            assertThat(bootstrap.pendingCount()).isEqualTo(1);
        });
    }

    @Test
    void theInvitationIsBoundToAnAccountAtTheSenderAddress() {
        clearInstanceAdmins();
        withConfiguredMailer(() -> {
            bootstrap.bootstrapIfEmpty();
            // Bound to a real INVITED account, exactly as membership_invitation is — not a
            // bearer token that would make whoever held it the operator.
            assertThat(invitedOperatorEmail()).isEqualTo(configuredFromAddress());
        });
    }

    @Test
    void theLinkIsNotRecoverableFromTheDatabase() {
        clearInstanceAdmins();
        withConfiguredMailer(() -> {
            bootstrap.bootstrapIfEmpty();
            String secret = lastSentLink().substring(lastSentLink().indexOf("token=") + 6);
            assertThat(rawBootstrapTokenHashes()).noneMatch(h -> h.equals(secret));
        });
    }
}
```

> `clearInstanceAdmins()` deletes from `instance_role_assignment` via the container's superuser
> datasource `IntegrationTestBase` already exposes for ground truth. `withConfiguredMailer`
> swaps a recording `InvitationMailer` whose `isConfigured()` returns true and whose sent
> messages `lastSentLink()` reads — use `@MockitoBean` or a `@TestConfiguration`, following
> whatever pattern the existing invitation tests already use.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.InstanceBootstrapServiceTest'`
Expected: FAIL — `InstanceBootstrapService` does not exist.

- [ ] **Step 3: Write the service**

```java
package com.tcms.provisioning;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.InstanceRoleAssignmentRepository;
import com.tcms.identity.InvitationMailer;
import com.tcms.identity.InvitationProperties;
import com.tcms.identity.InvitationTokens;
import com.tcms.identity.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gives a fresh instance its first Instance Admin by emailing an activation link to the
 * instance's own configured sender address.
 *
 * <h2>Why the sender address, and not an argument</h2>
 *
 * <p>Whoever controls the mailbox {@code tcms.invitation.from-address} sends from is already
 * trusted by this deployment — they receive every bounce and every reset the instance will
 * ever send. Making that mailbox the bootstrap target means the first operator is proven by
 * possession of a resource the deployment already depends on, with nothing new to configure
 * and no secret in the environment to leak into a process listing.
 *
 * <h2>The invitation is bound to an account</h2>
 *
 * <p>An {@code app_user} is created {@code INVITED} at the sender address before the token is
 * minted, and the invitation points at it — the same shape {@code membership_invitation} has,
 * minus the company. The alternative, an identity-less token whose redeemer picks their own
 * address, would be a bearer credential: whoever held the string would become the operator
 * under any address they chose. Not worth it to save typing an address the instance already
 * knows.
 *
 * <h2>Why it refuses without a relay, loudly</h2>
 *
 * <p>{@code MailConfiguration.UnconfiguredInvitationMailer} deliberately does not log the link
 * — it is a credential, and a log file is read by more people and kept longer than a terminal.
 * Only a digest of the token is stored, so an undelivered link is unrecoverable. Minting one
 * without a relay would leave an instance with a consumed one-shot and no operator: bricked,
 * silently, at the worst possible moment. So this refuses and names the two ways forward —
 * configure a relay, or use {@code grant-instance-admin}.
 *
 * <h2>This is the FIRST operator, not the only one</h2>
 *
 * <p>Additional Instance Admins are provisioned with {@code grant-instance-admin} over an
 * account that already exists. That is a deliberate CLI-only capability: there is no HTTP path
 * and no UI control that mints an operator, which is what {@code RoleGrantService}'s standing
 * 400 on SYSTEM-scope roles protects. This service is only the answer to "an instance with
 * <em>zero</em> operators cannot be administered at all", and it fires only in that state.
 *
 * <p>Not a race: {@code token_hash} is UNIQUE, the partial unique index in V39 allows only one
 * unconsumed row, and the whole check-then-mint runs in one transaction — so two application
 * instances booting together produce one invitation and one loser reading
 * {@link Outcome#INVITATION_ALREADY_PENDING}.
 */
@Service
public class InstanceBootstrapService {

    public enum Outcome {
        /** Somebody already holds Instance Admin. The overwhelmingly common case. */
        OPERATOR_ALREADY_EXISTS,
        /** A link was minted and handed to the mailer. */
        INVITATION_SENT,
        /** An unconsumed, unexpired link already exists. Nothing was minted. */
        INVITATION_ALREADY_PENDING,
        /** No SMTP relay, so nothing was minted. See the class header. */
        NO_MAIL_RELAY
    }

    private static final Logger log = LoggerFactory.getLogger(InstanceBootstrapService.class);

    private final InstanceRoleAssignmentRepository instanceGrants;
    private final BootstrapInvitationRepository invitations;
    private final AppUserRepository users;
    private final InvitationMailer mailer;
    private final InvitationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // constructor omitted here — inject all seven, final, no field injection

    public Outcome bootstrapIfEmpty() {
        return transactions.execute(status -> doBootstrap());
    }

    private Outcome doBootstrap() {
        if (instanceGrants.existsByRoleId(Role.INSTANCE_ADMIN_ID)) {
            return Outcome.OPERATOR_ALREADY_EXISTS;
        }
        Instant now = clock.instant();
        if (invitations.existsPending(now)) {
            return Outcome.INVITATION_ALREADY_PENDING;
        }
        if (!mailer.isConfigured()) {
            log.error("""
                    This instance has NO Instance Admin and no SMTP relay is configured, so \
                    the bootstrap activation link cannot be delivered. Nothing was minted — \
                    a link that cannot be delivered also cannot be recovered, since only a \
                    digest of its token is stored, and minting one would consume the \
                    instance's one-shot bootstrap for nothing. Either set spring.mail.host \
                    and tcms.invitation.from-address to a real relay and restart, or create \
                    an operator directly with the CLI: provision-company followed by \
                    grant-instance-admin.""");
            return Outcome.NO_MAIL_RELAY;
        }

        String to = properties.fromAddress();
        AppUser operator = users.findByEmail(to).orElseGet(() -> users.save(
                AppUser.invite(UUID.randomUUID(), to, "Instance Operator", now)));

        var token = InvitationTokens.mint();
        Instant expiresAt = now.plus(properties.validity());
        invitations.save(BootstrapInvitation.issue(
                UUID.randomUUID(), operator.getId(), token.hash(), expiresAt, now));

        // The fact, never the link. See the class header.
        log.warn("This instance has no Instance Admin. An activation link has been emailed to "
                + "{} (the configured sender address) and expires at {}. Whoever redeems it "
                + "becomes this instance's first operator.", to, expiresAt);

        mailer.sendBootstrap(to, properties.acceptUrl(token.secret()), expiresAt);
        return Outcome.INVITATION_SENT;
    }
}
```

> Three things to check against the real classes: `InvitationTokens`' minting API (secret +
> hash pair), whether `InvitationProperties.acceptUrl` builds the right path or the bootstrap
> needs its own pointing at `/bootstrap-admin`, and whether `InstanceRoleAssignmentRepository`
> has `existsByRoleId` — add it if not.

- [ ] **Step 4: Write the runner**

```java
package com.tcms.provisioning;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs {@link InstanceBootstrapService} once per boot.
 *
 * <p>Inert when an operator command was requested: {@code provision-company} and its siblings
 * run non-web and exit, and a bootstrap email fired during a CLI run would surprise an
 * operator who asked for something else entirely. {@link OperatorCommands#isRequestedBy} is
 * the same check every command uses to stay inert.
 */
@Component
public class InstanceBootstrapRunner implements ApplicationRunner {

    private final InstanceBootstrapService bootstrap;

    public InstanceBootstrapRunner(InstanceBootstrapService bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (OperatorCommands.isRequestedBy(args.getSourceArgs())) {
            return;
        }
        bootstrap.bootstrapIfEmpty();
    }
}
```

**This runner fires in every `@SpringBootTest`.** The fixture instance has an Instance Admin,
so it returns `OPERATOR_ALREADY_EXISTS` and does nothing — verify that is true of
`IntegrationTestBase`'s fixtures before relying on it, and seed one there if it is not.

- [ ] **Step 5: Add `sendBootstrap` to the mailer**

Add `sendBootstrap(String to, String acceptUrl, Instant expiresAt)` to `InvitationMailer`,
implemented in `SmtpInvitationMailer` with a subject like "Activate your TCMS instance" and a
body naming the instance's base URL, and in `UnconfiguredInvitationMailer` as a no-op that logs
at ERROR — it can only be reached by a caller that ignored `isConfigured()`, which is a bug
worth seeing.

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.InstanceBootstrapServiceTest'`
Expected: PASS, all five.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/tcms/provisioning/ \
        backend/src/main/java/com/tcms/identity/InvitationMailer.java \
        backend/src/main/java/com/tcms/identity/SmtpInvitationMailer.java \
        backend/src/main/java/com/tcms/identity/MailConfiguration.java \
        backend/src/test/java/com/tcms/provisioning/InstanceBootstrapServiceTest.java
git commit -m "feat(provisioning): email a first-boot activation link to the sender address

Refuses to mint without a relay: only a digest is stored, so an undelivered link
cannot be recovered and would leave the instance with no operator. Bound to an
INVITED account rather than being a bearer token."
```

---


### Task 9: Redeeming the bootstrap link

**Files:**
- Create: `backend/src/main/java/com/tcms/provisioning/BootstrapController.java`
- Modify: `backend/src/main/java/com/tcms/auth/SecurityConfiguration.java`
- Modify: `docs/specs/openapi.yaml`, then regenerate
- Test: `backend/src/test/java/com/tcms/provisioning/BootstrapAcceptEndpointTest.java`

- [ ] **Step 1: Add the contract**

`GET /bootstrap/{token}` returns `{ "email": string, "valid": boolean }` (required: both) —
the address the link was issued to, so the redeemer can confirm it is the mailbox they opened
it from, exactly as the company invitation's preview does.

`POST /bootstrap/{token}` takes `{ "password": string, "name": string }` (required: both) and
returns the same session shape `InvitationController`'s accept returns. **No `email` field**:
the invitation is bound to an account that already exists at the sender address, so the
address is not the redeemer's to choose. `name` is offered because the minted account carries
the placeholder `"Instance Operator"` and a real person should be able to replace it.

Mirror `InvitationController`'s existing contract entries — open them and copy the response
shapes rather than inventing new ones.

- [ ] **Step 2: Write the failing test**

```java
package com.tcms.provisioning;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcms.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class BootstrapAcceptEndpointTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    private static final String BODY =
            """
            {"password":"a-long-enough-password","name":"First Op"}""";

    @Test
    void redeemingActivatesTheAccountAndGrantsInstanceAdmin() throws Exception {
        String secret = mintBootstrapInvitation();   // helper: clears admins, mints, returns secret

        mvc.perform(post("/api/v1/bootstrap/" + secret)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        // The address came from the invitation, which came from the sender address.
        assertThat(instanceAdminEmails()).containsExactly(configuredFromAddress());
    }

    @Test
    void theOneShotIsSpentAfterOneRedemption() throws Exception {
        String secret = mintBootstrapInvitation();
        mvc.perform(post("/api/v1/bootstrap/" + secret)
                .contentType(MediaType.APPLICATION_JSON).content(BODY));

        mvc.perform(post("/api/v1/bootstrap/" + secret)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isGone());
        assertThat(instanceAdminCount()).isEqualTo(1);
    }

    @Test
    void anInvalidTokenIsRefusedWithoutSayingWhy() throws Exception {
        mvc.perform(post("/api/v1/bootstrap/not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isGone());
    }

    @Test
    void aTokenIsRefusedOnceAnOperatorExists() throws Exception {
        String secret = mintBootstrapInvitation();
        grantSomeoneInstanceAdmin();     // helper over GrantInstanceAdminService

        // The race the one-shot cannot prevent alone: the link was valid when minted and the
        // instance acquired an operator by another route — the break-glass CLI — before it
        // was redeemed. A leaked link is inert from this moment on.
        mvc.perform(post("/api/v1/bootstrap/" + secret)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.BootstrapAcceptEndpointTest'`
Expected: FAIL — the path does not exist.

- [ ] **Step 4: Implement acceptance**

Add `accept(String secret, String name, String password)` to `InstanceBootstrapService`, in
one transaction:

1. Hash the secret and look up an unconsumed, unexpired row; **410 Gone** when there is none — the same answer for expired, consumed and never-existed, because distinguishing them tells an unauthenticated caller which guesses were close.
2. Re-check `instanceGrants.existsByRoleId(Role.INSTANCE_ADMIN_ID)` and return **409 Conflict** if one now exists. The link is minted when the instance is empty; this is the check that it is *still* empty at redemption, and it makes a leaked link inert once an operator was created another way.
3. Load the bound `AppUser`, set its canonical name from `name`, set the hashed password and mark it `ACTIVE` — reusing whatever password encoder `InvitationService.accept` uses. Do not introduce a second.
4. `grants.grant(user.getEmail(), null)` — `null` actor, which is exactly what `GrantInstanceAdminService` documents as "the bootstrapped first Instance Admin", and why `granted_by` is nullable.
5. `invitation.consume(now)`.
6. Issue a session the same way `InvitationService.accept`'s caller does, with **no active company** — this operator belongs to none.

Then write `BootstrapController` over it, and add `.requestMatchers("/api/v1/bootstrap/**").permitAll()` to `SecurityConfiguration` beside the existing `"/api/v1/auth/**"` matcher at line 82.

- [ ] **Step 5: Run the tests, then the full gate**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.BootstrapAcceptEndpointTest' && ./gradlew build`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/tcms/provisioning/ \
        backend/src/main/java/com/tcms/auth/SecurityConfiguration.java \
        docs/specs/openapi.yaml frontend/src/api/schema.d.ts \
        backend/src/test/java/com/tcms/provisioning/BootstrapAcceptEndpointTest.java
git commit -m "feat(provisioning): redeem the bootstrap link into the first Instance Admin"
```

---

### Task 10: `POST /instance/invitations` — invite a person into any company

**Files:**
- Create: `backend/src/main/java/com/tcms/provisioning/InstanceInviteService.java`
- Create: `backend/src/main/java/com/tcms/provisioning/InstanceInviteRequest.java`
- Create: `backend/src/main/java/com/tcms/provisioning/InstanceInviteController.java`
- Modify: `docs/specs/openapi.yaml`, then regenerate
- Test: `backend/src/test/java/com/tcms/provisioning/InstanceInviteEndpointTest.java`

- [ ] **Step 1: Add the contract**

```yaml
    InstanceInviteInput:
      type: object
      description: >
        An invitation issued by an operator into a company they need not belong to. The
        invitee becomes a member of that company with **no role** and zero project visibility
        (§43), exactly as the company screen's own invite does — this endpoint reuses that
        service rather than reimplementing it.

        `companyId` is REQUIRED. There is no instance-scoped invitation: an instance has
        its FIRST Instance Admin from the bootstrap and every later one from the CLI; no
        user action creates one.

        This is the one invite payload that carries a companyId. That is legitimate here and
        nowhere else: it names a target, exactly as MembershipInvitation.companyId does, and
        the gate above it is SYSTEM-scope — only an operator can name a company they are not
        in. The company screen's POST /users still takes the company from the session context
        (ERD §0.1 point 2) and must keep doing so.
      properties:
        email: { type: string, format: email }
        displayName:
          type: string
          minLength: 1
          description: >
            The per-membership display name (§6u), which may differ from the same person's
            name in another client. Never the account's canonical name.
        companyId: { type: string, format: uuid }
      required: [email, displayName, companyId]

    InstanceInviteOutcome:
      type: object
      properties:
        outcome:
          type: string
          enum: [INVITED, ALREADY_PENDING, ALREADY_ACTIVE, NO_SUCH_COMPANY]
        invitationLink:
          type: string
          nullable: true
          description: >
            Returned in-band ONLY when no SMTP relay is configured. See
            ProvisionCompanyOutcome.invitationLink for why it is withheld otherwise.
        invitationExpiry: { type: string, format: date-time, nullable: true }
        invitationDelivered: { type: boolean }
      required: [outcome, invitationDelivered]
```

```yaml
  /instance/invitations:
    post:
      tags: [instance]
      summary: Invite a person into any company
      description: >
        Requires `user:manage`. This is the company screen's own invite performed from
        outside the company — same service, same rules, same absence of a role grant. It
        cannot create an Instance Admin; nothing reachable from the UI can.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/InstanceInviteInput' }
      responses:
        '201':
          description: An activation link was minted.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/InstanceInviteOutcome' }
        '200':
          description: >
            Nothing was minted — the person is already an active member, or an unexpired
            invitation is outstanding and was deliberately left alone.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/InstanceInviteOutcome' }
        '404':
          description: No company on this instance has that id.
        '403': { $ref: '#/components/responses/Forbidden' }
```

- [ ] **Step 2: Regenerate**

Run: `cd frontend && npm run gen:api && npm run build`
Expected: PASS.

- [ ] **Step 3: Write the failing test**

```java
package com.tcms.provisioning;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcms.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class InstanceInviteEndpointTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    private ResultActions invite(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/instance/invitations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void invitingIntoACompanyTheOperatorIsNotAMemberOfSucceeds() throws Exception {
        // The operator holds no membership in the SECOND fixture company. The SYSTEM term is
        // what carries company:members through evaluateCompanyWide — see orientation finding 6.
        invite(instanceAdminToken(), """
                {"email":"newcomer@acme.test","displayName":"New Comer",
                 "companyId":"%s"}""".formatted(secondFixtureCompanyId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("INVITED"));
    }

    @Test
    void aCompanyInviteGrantsNoRole() throws Exception {
        invite(instanceAdminToken(), """
                {"email":"norole@acme.test","displayName":"No Role",
                 "companyId":"%s"}""".formatted(fixtureCompanyId()));

        // Zero role assignments for the new account: inviting and granting stay two acts.
        assertThat(roleAssignmentsFor("norole@acme.test")).isEmpty();
    }

    @Test
    void omittingTheCompanyIs400AndCreatesNoOperator() throws Exception {
        invite(instanceAdminToken(), """
                {"email":"operator2@ops.test","displayName":"Second Operator"}""")
                .andExpect(status().isBadRequest());

        // The invariant this whole endpoint is shaped around: no user action mints an
        // Instance Admin. If this ever passes, an instance-scoped branch has crept back in.
        assertThat(instanceAdminCount()).isEqualTo(1);
    }

    @Test
    void anUnknownCompanyIs404() throws Exception {
        invite(instanceAdminToken(), """
                {"email":"nobody@acme.test","displayName":"Nobody",
                 "companyId":"%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCompanyAdminCannotInviteIntoAnotherCompany() throws Exception {
        invite(companyAdminToken(), """
                {"email":"crosser@acme.test","displayName":"Crosser",
                 "companyId":"%s"}""".formatted(secondFixtureCompanyId()))
                .andExpect(status().isForbidden());
    }

}
```

`aCompanyAdminCannotInviteIntoAnotherCompany` and `omittingTheCompanyIs400AndCreatesNoOperator`
are the two that matter most: the first fails if the SYSTEM gate is ever weakened to a company
one, the second if an instance-scoped destination is ever added back.

- [ ] **Step 4: Run it and watch it fail**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.InstanceInviteEndpointTest'`
Expected: FAIL — the path does not exist.

- [ ] **Step 5: Write the service**

```java
package com.tcms.provisioning;

import com.tcms.identity.InvitationMailer;
import com.tcms.identity.InvitationProperties;
import com.tcms.identity.MembershipInviteService;
import com.tcms.identity.UserInvitationRequest;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The operator's invitation path — <strong>the EXISTING company invite, performed from
 * outside the company</strong>.
 *
 * <p>It resolves the target company, opens a {@link CompanyContext} on it, and calls
 * {@link MembershipInviteService} unchanged. Every rule that path already owns — already an
 * active member, an unexpired invitation left alone, the §6u display name on the membership,
 * <strong>and the deliberate absence of any role grant</strong> — is therefore obeyed by
 * construction rather than by a second implementation that agrees with it today.
 *
 * <p>That the operator holds no membership in the target company does not matter:
 * {@code PermissionEvaluator.evaluateCompanyWide} starts from the SYSTEM term and the company
 * gate only adds to it, so an Instance Admin passes {@code company:members} in a company they
 * have never joined. This is the same property that makes the whole console possible.
 *
 * <p>Opening the context here is the {@link IssueInvitationService} pattern, not a hole in
 * ERD §0.1 point 2: the company was resolved from a SYSTEM-gated payload naming a target,
 * then opened deliberately — it was never read from the request as a tenancy discriminator by
 * a company-scoped endpoint.
 *
 * <h2>There is no instance-scoped branch here, and there must not be one</h2>
 *
 * <p>An instance gets its first Instance Admin from the bootstrap, minted at first boot by
 * {@link InstanceBootstrapService} and by nothing else. This service can only add people to
 * companies. A second destination here would be the one code path that turns an authenticated
 * user action into an operator, which is precisely what the design excludes — see the plan's
 * "Operators are created on the CLI, never in the UI".
 */
@Service
public class InstanceInviteService {

    private final CompanyRepository companies;
    private final MembershipInviteService memberships;

    // constructor omitted here — inject both, final

    public InstanceInviteOutcome invite(InstanceInviteRequest request, UUID actorUserId) {
        if (!companies.existsById(request.companyId())) {
            return InstanceInviteOutcome.noSuchCompany();
        }
        try (var ignored = CompanyContext.open(request.companyId())) {
            return InstanceInviteOutcome.from(memberships.invite(
                    new UserInvitationRequest(request.email(), request.displayName()),
                    actorUserId));
        }
    }
}
```

Match `MembershipInviteService`'s real method name and result type, and map its outcome enum
onto `InstanceInviteOutcome`'s four cases. `InstanceInviteRequest` validates that `companyId`
is non-null in its canonical constructor, so a payload without one is a 400 through the same
`@ExceptionHandler(IllegalArgumentException.class)` Task 5 added — not a silent operator
invitation.

- [ ] **Step 6: Write `InstanceInviteController`**

A separate controller from `InstanceCompanyController` because it answers to a different
permission (`user:manage`, not `company:create`) and a different service. Give it the same
`@RequestMapping("/api/v1/instance")` root and the same `caller(Jwt)` helper.

```java
    @PostMapping("/invitations")
    public ResponseEntity<InstanceInviteOutcome> invite(
            @AuthenticationPrincipal Jwt jwt, @RequestBody InstanceInviteRequest request) {

        UUID actor = UUID.fromString(jwt.getSubject());
        gate.demand(actor, "user:manage");

        InstanceInviteOutcome outcome = invites.invite(request, actor);
        return switch (outcome.outcome()) {
            case INVITED -> ResponseEntity.status(HttpStatus.CREATED).body(outcome);
            case ALREADY_PENDING, ALREADY_ACTIVE -> ResponseEntity.ok(outcome);
            case NO_SUCH_COMPANY -> ResponseEntity.notFound().build();
        };
    }
```

- [ ] **Step 7: Run the tests, then the full gate**

Run: `cd backend && ./gradlew test --tests 'com.tcms.provisioning.InstanceInviteEndpointTest' && ./gradlew build`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts \
        backend/src/main/java/com/tcms/provisioning/ \
        backend/src/test/java/com/tcms/provisioning/InstanceInviteEndpointTest.java
git commit -m "feat(provisioning): invite a person into any company from the console

Reuses MembershipInviteService inside an opened CompanyContext, so it grants no role
and obeys every rule that path already owns. It cannot mint an operator."
```

---

## Phase 5 — The console

### Task 11: Query hooks and keys

**Files:**
- Create: `frontend/src/api/queries/instance.ts`
- Modify: `frontend/src/api/queries/keys.ts`, `frontend/src/api/queries/index.ts`, `frontend/src/api/types.ts`

- [ ] **Step 1: Add the keys**

In `keys.ts`, beside `me` and `projects`:

```ts
  /**
   * The instance console's keys, deliberately rooted at `instance` rather than under
   * `projects` or `me`. Nothing here is company-scoped, so an invalidation that walks the
   * company subtree must not reach it — and, more importantly, the reverse: switching
   * company must never invalidate the operator's roster.
   */
  instanceCompanies: ['instance', 'companies'] as const,
  instanceCompany: (id: string) => ['instance', 'companies', id] as const,
```

- [ ] **Step 2: Add the types** — in `types.ts`, re-export the generated schema types following the file's existing pattern: `InstanceCompany`, `ProvisionCompanyInput`, `ProvisionCompanyOutcome`, `InstanceInviteInput`, `InstanceInviteOutcome`.

- [ ] **Step 3: Write the hooks**

```ts
/**
 * The instance operator's surface — the companies on this instance and the people who
 * operate it.
 *
 * Every hook here answers on a session with NO active company, which is the state an
 * operator's session is in. That is why none of these keys are company-scoped and why none
 * of them invalidate anything under `projects`: an operator suspending company A cannot
 * have changed what the operator can see inside company B, because they are not inside one.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api } from '../client';
import type {
  InstanceCompany,
  InstanceInviteOutcome,
  ProvisionCompanyInput,
  ProvisionCompanyOutcome,
} from '../types';
import { keys } from './keys';

export function useInstanceCompanies(
  enabled: boolean,
): UseQueryResult<InstanceCompany[]> {
  return useQuery({
    queryKey: keys.instanceCompanies,
    queryFn: ({ signal }) => api.get<InstanceCompany[]>('/instance/companies', { signal }),
    // Gated rather than always-on: a non-operator would get a 403 on every mount, which
    // the global error handler would raise as a notification on a screen they cannot see.
    enabled,
    staleTime: 30_000,
  });
}

/**
 * Provisions a company, or tops one up.
 *
 * `meta: { handled: true }` because the dialog renders the outcome itself — including the
 * activation link, which is the whole point of the screen and must not be reduced to a
 * toast that disappears.
 */
export function useProvisionCompany(): UseMutationResult<
  ProvisionCompanyOutcome,
  Error,
  ProvisionCompanyInput
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ProvisionCompanyInput) =>
      api.post<ProvisionCompanyOutcome>('/instance/companies', input),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.instanceCompanies });
    },
  });
}

export function useSetCompanySuspension(): UseMutationResult<
  InstanceCompany,
  Error,
  { companyId: string; suspended: boolean }
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ companyId, suspended }) =>
      suspended
        ? api.put<InstanceCompany>(`/instance/companies/${companyId}/suspension`, {})
        : api.delete<InstanceCompany>(`/instance/companies/${companyId}/suspension`),
    meta: { handled: true },
    onSuccess: (_data, { companyId }) => {
      void client.invalidateQueries({ queryKey: keys.instanceCompanies });
      void client.invalidateQueries({ queryKey: keys.instanceCompany(companyId) });
    },
  });
}

/**
 * Invites a person into a company.
 *
 * `companyId` is required — there is no instance-scoped invitation, because an instance has
 * its first Instance Admin from the first-boot bootstrap and every later one from
 * {@code grant-instance-admin} on the CLI. Nothing reachable from this client creates one.
 *
 * Invalidates the roster because `memberCount` on the target company has changed. It does not
 * invalidate anything under `projects`: an invitation grants no role and confers no project
 * visibility (§43), so nothing the operator can currently see inside a project has moved.
 */
export function useInstanceInvite(): UseMutationResult<
  InstanceInviteOutcome,
  Error,
  { email: string; displayName: string; companyId: string }
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input) => api.post<InstanceInviteOutcome>('/instance/invitations', input),
    meta: { handled: true },
    onSuccess: (_data, input) => {
      void client.invalidateQueries({ queryKey: keys.instanceCompany(input.companyId) });
      void client.invalidateQueries({ queryKey: keys.instanceCompanies });
    },
  });
}
```

Check `api.delete`'s real signature in `src/api/client.ts` — it may not take a body, and may
be named differently.

- [ ] **Step 4: Re-export from `index.ts`** following that file's existing pattern.

- [ ] **Step 5: Gate**

Run: `cd frontend && npm run build && npm run lint`
Expected: PASS, 0 warnings.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/queries/instance.ts frontend/src/api/queries/keys.ts \
        frontend/src/api/queries/index.ts frontend/src/api/types.ts
git commit -m "feat(fe): query hooks for the instance console"
```

---

### Task 12: `RequireInstanceAdmin` and the route tree

**Files:**
- Create: `frontend/src/features/instance/RequireInstanceAdmin.tsx`
- Test: `frontend/src/features/instance/RequireInstanceAdmin.test.tsx`
- Modify: `frontend/src/app/router.tsx`, `frontend/src/app/AppShell.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '../../test/render';   // use the real helper name
import { RequireInstanceAdmin } from './RequireInstanceAdmin';

describe('RequireInstanceAdmin', () => {
  it('renders the console for an instance admin', () => {
    renderWithProviders(<RequireInstanceAdmin />, {
      session: { isSystemAdmin: true },
      route: '/instance/companies',
    });
    expect(screen.getByTestId('Instance.Outlet')).toBeInTheDocument();
  });

  it('sends a non-operator to the dashboard rather than showing a 403 screen', () => {
    renderWithProviders(<RequireInstanceAdmin />, {
      session: { isSystemAdmin: false },
      route: '/instance/companies',
    });
    expect(screen.queryByTestId('Instance.Outlet')).not.toBeInTheDocument();
  });
});
```

Adapt to `src/test/`'s actual render helper and its session-override API — open it first.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd frontend && npm run test:run -- RequireInstanceAdmin`
Expected: FAIL — module not found.

- [ ] **Step 3: Write it**

```tsx
import { Navigate, Outlet } from 'react-router-dom';
import { useSession } from '../../auth/useSession';   // match the real hook name

/**
 * The route guard for `/instance/**`.
 *
 * **This hides, it does not protect.** Every endpoint behind these screens independently
 * demands a SYSTEM permission through `InstanceOperatorGate`, and that server-side check is
 * the security boundary. `isSystemAdmin` reaches the client from `CurrentUserService`, which
 * derives it from holding a SYSTEM-scope grant rather than from a role NAME — code checks
 * permissions, never names (plan §6b).
 *
 * A redirect rather than a 403 page: a user who is not an operator has no business being
 * told this route family exists, and landing them on their dashboard is what a mistyped URL
 * deserves.
 */
export function RequireInstanceAdmin() {
  const session = useSession();

  if (!session.user?.isSystemAdmin) {
    return <Navigate to="/" replace />;
  }
  return <div data-testid="Instance.Outlet"><Outlet /></div>;
}
```

- [ ] **Step 4: Mount the routes**

In `router.tsx`, inside the `<RequireAuth>` → `<AppShell>` nesting and beside the existing
top-level routes (`/my-work`, `/teams/:teamId/board`):

```tsx
          <Route path="/instance" element={<RequireInstanceAdmin />}>
            <Route index element={<Navigate to="/instance/companies" replace />} />
            <Route
              path="companies"
              element={
                <Suspense fallback={<RouteFallback />}>
                  <InstanceCompaniesPage />
                </Suspense>
              }
            />
            <Route
              path="companies/:companyId"
              element={
                <Suspense fallback={<RouteFallback />}>
                  <InstanceCompanyDetailPage />
                </Suspense>
              }
            />
            <Route
              path="people"
              element={
                <Suspense fallback={<RouteFallback />}>
                  <InstancePeoplePage />
                </Suspense>
              }
            />
          </Route>
```

Lazy-import the three pages exactly as the neighbouring routes do.

Add `/bootstrap-admin` as a **sibling of `/login`**, outside `<RequireAuth>` — the whole
point is that nobody is signed in yet:

```tsx
      <Route path="/bootstrap-admin" element={<BootstrapAcceptPage />} />
```

- [ ] **Step 5: Add the nav entry**

In `AppShell.tsx`, add an "Instance" nav section with two items — "Companies"
(`/instance/companies`) and "People" (`/instance/people`) — rendered only when
`session.user?.isSystemAdmin`. This is the first consumer of that flag in the app.

Keep it visually separate from the company navigation above it. The two permission surfaces
being one click apart in an undifferentiated list is precisely how an operator ends up
administering the wrong one.

- [ ] **Step 6: Run the test, then the gate**

Run: `cd frontend && npm run test:run -- RequireInstanceAdmin && npm run build && npm run lint`
Expected: PASS, PASS, 0 warnings.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/instance/ frontend/src/app/router.tsx frontend/src/app/AppShell.tsx
git commit -m "feat(fe): the /instance route tree, gated on isSystemAdmin

First consumer of a flag CurrentUserService has returned since the session was built."
```

---

### Task 13: The screens

**Files:**
- Create: `InstanceCompaniesPage.tsx`, `InstanceCompanyDetailPage.tsx`, `InstancePeoplePage.tsx`, `CreateCompanyDialog.tsx` + test, `SuspendCompanyDialog.tsx` + test, `InstanceInviteDialog.tsx` + test, `ReseedCompanyDialog.tsx`, all under `frontend/src/features/instance/`
- Modify: `frontend/src/lib/labels.ts`

- [ ] **Step 1: Add the status label**

In `labels.ts`, following the file's `satisfies Record<Union, string>` convention so a new
enum member is a compile error until it is labeled:

```ts
export const companyStatusLabels = {
  ACTIVE: 'Active',
  SUSPENDED: 'Suspended',
} satisfies Record<CompanyStatus, string>;
```

- [ ] **Step 2: Write `CreateCompanyDialog`'s failing test**

```tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '../../test/render';
import { CreateCompanyDialog } from './CreateCompanyDialog';

describe('CreateCompanyDialog', () => {
  it('shows the activation link exactly when the server could not deliver it', async () => {
    renderWithProviders(<CreateCompanyDialog open onClose={() => {}} />, {
      handlers: {
        'POST /instance/companies': {
          company: { id: 'c1', slug: 'acme', displayName: 'Acme', status: 'ACTIVE',
                     createdAt: '2026-08-07T00:00:00Z', memberCount: 1, projectCount: 0 },
          companyCreated: true, adminUserCreated: true, membershipCreated: true,
          adminGrantCreated: true, baselineGrantsCreated: 1, projectCreated: false,
          invitationLink: 'https://tcms.test/accept-invitation?token=abc',
          invitationDelivered: false,
        },
      },
    });

    await userEvent.type(screen.getByLabelText(/slug/i), 'acme');
    await userEvent.type(screen.getByLabelText(/name/i), 'Acme');
    await userEvent.type(screen.getByLabelText(/admin email/i), 'a@acme.test');
    await userEvent.type(screen.getByLabelText(/admin name/i), 'A Admin');
    await userEvent.click(screen.getByRole('button', { name: /provision/i }));

    await waitFor(() =>
      expect(screen.getByText(/https:\/\/tcms.test\/accept-invitation/)).toBeInTheDocument(),
    );
    expect(screen.getByText(/carry this link/i)).toBeInTheDocument();
  });

  it('reports a re-run as a top-up rather than as a creation', async () => {
    renderWithProviders(<CreateCompanyDialog open onClose={() => {}} />, {
      handlers: {
        'POST /instance/companies': {
          company: { id: 'c1', slug: 'acme', displayName: 'Acme', status: 'ACTIVE',
                     createdAt: '2026-08-07T00:00:00Z', memberCount: 3, projectCount: 2 },
          companyCreated: false, adminUserCreated: false, membershipCreated: false,
          adminGrantCreated: false, baselineGrantsCreated: 0, projectCreated: false,
          invitationDelivered: false,
        },
      },
    });
    /* …fill and submit as above… */
    await waitFor(() =>
      expect(screen.getByText(/already existed/i)).toBeInTheDocument(),
    );
  });
});
```

Adapt `handlers` to however `src/test/`'s render helper stubs `fetch` — there is no MSW any
more, so it will be a `fetch` stub. Open the helper first and follow it.

- [ ] **Step 3: Run it and watch it fail**

Run: `cd frontend && npm run test:run -- CreateCompanyDialog`
Expected: FAIL — module not found.

- [ ] **Step 4: Build the four dialogs and three pages**

Follow `src/features/admin/`'s existing dialogs closely — `InviteUserDialog.tsx` and
`GrantRoleDialog.tsx` are the two nearest models. Specifically:

- **`CreateCompanyDialog`** — fields for slug, display name, admin email, admin name, and an optional first project (key + name). Client-side patterns mirroring `ProvisionCompanyInput`'s: slug `^[a-z0-9]+(-[a-z0-9]+)*$`, project key `^[A-Z0-9]{2,10}$`. On success it switches to a result view rather than closing, because the activation link is shown **once and never again** — render it with `CopyField`, and say plainly which of the two states the operator is in: "emailed to a@acme.test" or "no relay is configured — carry this link to them now, it cannot be shown again."
- **`SuspendCompanyDialog`** — a confirmation naming the company and stating what suspension does (all non-operator access refused, ingestion stops, data untouched, reversible). Reinstating needs no dialog.
- **`InstanceInviteDialog`** — the screen your design turns on. Email, display name, and a **required company select** listing every company from `useInstanceCompanies` by display name. No blank default and no "none" option: the select opens with nothing chosen and the submit button stays disabled until one is, because an invite with no destination is not a thing this endpoint can express. Beneath the select, one line of copy: "They will join *Acme* as a member with no project access. Grant them roles from that company's Teams & Roles screen." — which is what stops an operator expecting this to be a role assignment. On success, show the same once-only link panel `CreateCompanyDialog` uses, driven by `invitationDelivered`. Map `ALREADY_ACTIVE` and `ALREADY_PENDING` to plain sentences rather than errors: both are 200s and neither is a failure.
- **`ReseedCompanyDialog`** — re-POSTs the company's existing slug, name and admin address, and renders `baselineGrantsCreated` and the `*Created` flags as "what this run repaired", falling back to "nothing needed topping up" when every flag is false.
- **`InstanceCompaniesPage`** — a table over `useInstanceCompanies`, wrapped in `DataRegion` for the loading/loaded/failed/empty/stale matrix, `Identifier` for the slug, `StatusChip` for the status, `PageHeader` for the title and the "Provision a company" action.
- **`InstanceCompanyDetailPage`** — the same view plus the suspend/reinstate and re-seed actions.
- **`InstancePeoplePage`** (route `/instance/people`) — **the second of the two permission surfaces.** One action, "Invite a person" (`InstanceInviteDialog`), and two standing notes. The first draws the line the whole design rests on: *this page invites people into companies. A company's own roles and teams are administered inside that company, on its Teams & Roles screen.* The second states the operator rule plainly: *Instance Admins are created on the server, not here — the first when the instance was set up, any others with `grant-instance-admin`. See the runbook.* Say it on the screen rather than only in the docs, because "why can't I make my colleague an admin" is the question this page will otherwise generate forever, and an answer that names the actual command saves the support round-trip.

Do not write a second implementation of anything `lib/labels.ts`, `lib/format.ts` or
`DataRegion` already owns.

- [ ] **Step 5: Write `BootstrapAcceptPage`** in `src/features/auth/`, modelled on
`AcceptInvitationPage.tsx`: read the token from the query string, `GET /bootstrap/{token}`
for the preview, collect name + password, `POST`, then land the new operator on
`/instance/companies`. Both failure answers — 410 and 409 — get plain copy: the link is spent
or expired, and this instance already has an operator.

- [ ] **Step 6: Run the tests and the gate**

Run: `cd frontend && npm run test:run && npm run build && npm run lint`
Expected: all tests pass (954 + the new ones), build passes, 0 lint warnings.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/instance/ frontend/src/features/auth/BootstrapAcceptPage.tsx \
        frontend/src/lib/labels.ts
git commit -m "feat(fe): the instance operator console"
```

---

## Phase 6 — Documentation and the verification queues

### Task 14: Write the reversals down

**Files:**
- Modify: `docs/plans/2026-07-30-tcms-high-level-plan.md`, `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`, `CLAUDE.md`
- Create: `docs/handoff/NNN-the-instance-operator-console.md` + one index row in `docs/HANDOFF.md`
- Modify: `docs/agent-context/be-java.md`, `docs/agent-context/fe-react.md`
- Modify: `docs/verification/frontend-browser-checks.md`, `docs/verification/backend-mutation-checks.md`

- [x] **Step 1: Strike through the superseded decisions — ALREADY DONE, 2026-08-07**

> **Done when this plan was folded into M6, not deferred with the rest of it.** A scope
> reversal that lives only in an unexecuted plan file is a reversal the next reader of the
> high-level plan will not find. `docs/plans/2026-07-30-tcms-high-level-plan.md` already
> carries all of the following — **verify rather than repeat**, and extend if M6 has moved on:
>
> - The **M6 milestone row**: ~~1.5–2 weeks~~ → 3–4 weeks, plus a row describing this console and pointing here.
> - The **decision log**: "companies are provisioned by an operator script … not through an admin UI" struck through, with the cost named — that exclusion is what made M6 "a week rather than a phase".
> - A **new decision row**: Instance Admins are created on the CLI, never in the UI.
> - The **§6s lifecycle table**: `Create / provision` gains the UI path; `Suspend` records that the column has had a writer and no reader since V1.
> - The **§6s exclusions list**: "no company management UI" struck; **"no self-service signup" explicitly kept**.

Two places were *not* touched and still need it when this ships:

- `high-level-plan.md:127` and `:180` — "No company management UI and no switcher — those are the last phase." Both halves are now M6 work; note that they ship together rather than the UI half being absent.
- `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` — check whether it repeats the "no company management UI" framing anywhere, and strike it there too if so.

- [ ] **Step 2: Record the remaining decisions**

The three scope decisions are already in the log (Step 1). Add the two implementation-level
ones once the code exists:

- The console's reads are gated on `company:create` rather than a new `instance:read`, because the roles spec has authority over the catalogue and this avoided changing it. **Needs sign-off before Task 4.**
- `instance_bootstrap_invitation` joins the ERD §0.1 RLS exception list. **Needs sign-off before Task 7.**

Also note in `docs/plans/2026-07-30-tcms-hetzner-deployment-runbook.md` and
`docs/plans/2026-08-06-shanks-deployment.md` that a deployment **must** configure
`spring.mail.host` and `TCMS_MAIL_FROM` before first boot, or the instance cannot bootstrap an
operator at all. That is a new hard requirement those runbooks do not currently state.

`RoleGrantService`'s header sentence — *"there is no HTTP path to a SYSTEM grant … and there
should not be one"* — **stays exactly as written.** It is still true. An earlier draft of this
plan amended it; do not.

- [ ] **Step 3: Update `CLAUDE.md`**

Its Architecture section states "Companies are created by exactly one path:
`CompanyProvisioningService` … driven by `java -jar tcms.jar provision-company`." That is now
two paths over one service. Rewrite to say the service is still the only path *in code*, and
that it has two callers — the console and the break-glass CLI.

- [ ] **Step 4: Write the handoff section** — a new `docs/handoff/NNN-the-instance-operator-console.md` (take the next free NN) plus **one row** in `docs/HANDOFF.md`'s index. Never append prose to `HANDOFF.md` itself. Cover: what shipped, the five orientation findings, the two sign-off decisions, and the known gaps below.

- [ ] **Step 5: Update the orientation caches** — `docs/agent-context/be-java.md` and `fe-react.md` in **this slice's own commit**, per CLAUDE.md. Add the new seams: `InstanceOperatorGate`, `SuspendedCompanyFilter`, the `/api/v1/instance` family, `RequireInstanceAdmin`, `queries/instance.ts`.

- [ ] **Step 6: Append the verification rows — write them, run nothing**

§65 is standing policy and was restated on 2026-08-02: **deferred means all of them.** Do not
run "just the cheap one". Add to `docs/verification/frontend-browser-checks.md`, in the lane
whose *state* they need, with stable ids:

- Sign in as an Instance Admin → the "Instance" nav entry is present; sign in as a Company Admin → it is absent, and a typed `/instance/companies` redirects to the dashboard.
- Provision a company with no relay configured → the activation link is shown once, `CopyField` copies it, and reloading the page does not show it again.
- Re-run seeds against that company → the dialog reports "nothing needed topping up".
- Suspend a company, then sign in as one of *its* members → refused with the suspension copy, not a generic 403. Reinstate → they get back in.
- Invite a person into a company from `/instance/people` → they appear on **that company's** Teams & Roles screen as a member with no roles, and the roster's `memberCount` for that company goes up.
- In the invite dialog, confirm the company select has no blank or "none" option and that submit stays disabled until a company is chosen.
- Confirm `/instance/people` states on screen that Instance Admins are created on the server and names `grant-instance-admin`, and that no control anywhere in the console offers to create one.
- As a Company Admin, confirm the company Teams & Roles screen still offers no way to reach another company or to select a SYSTEM-scope role.
- On a **fresh** instance with a real relay: boot, confirm the link arrives at the sender address, redeem it, and confirm a second boot mints nothing. Then on a fresh instance with **no** relay: confirm boot logs the ERROR naming both fixes and mints nothing at all.
- Run `grant-instance-admin` for a second operator, sign in as them, and confirm they see the same console — the CLI path and the bootstrap path produce the same thing.

And to `docs/verification/backend-mutation-checks.md`: `POST /instance/companies` (create and
re-run), `PUT`/`DELETE .../suspension`, `POST /instance/invitations` (valid company, unknown
company, missing `companyId` → 400, and a Company Admin refused), and the bootstrap
mint-and-redeem against a real relay including the second-boot no-op.

- [ ] **Step 7: Commit**

```bash
git add docs/
git commit -m "docs: record the instance console and the §6s decisions it reverses"
```

---

## Known gaps this plan deliberately leaves open

1. **Operator recovery is CLI-only, and the runbook must say so.** An instance whose only operator loses their password has no in-product route back — the bootstrap cannot help, since it fires only when *zero* operators exist. Recovery is `grant-instance-admin` over SSH. That is an acceptable answer for self-hosted software, but it is an answer somebody has to know: **the Hetzner and shanks runbooks must state it**, and provisioning a second operator at setup time is the cheap insurance. Consider a shared team mailbox for the sender address rather than one person's.
2. **No revoke, and no "last admin" protection.** There is no UI, no endpoint and no CLI verb that removes a SYSTEM grant, so demoting an operator needs a `DELETE FROM instance_role_assignment`. The roles spec's "the last Instance Admin cannot be removed or demoted" rule is unimplemented and unenforced — and since operators can now be added freely on the CLI, a plural set of them with no way to remove one is a real gap rather than a theoretical one. A `revoke-instance-admin` CLI verb enforcing the last-admin rule is the smallest honest fix.
3. **No `company:delete` endpoint**, by design — the catalogue's own description says "policy is suspend, never delete".
4. **The bootstrap needs a working SMTP relay.** An instance with no relay must use the break-glass CLI. This is a hard refusal with a loud log line, not a silent degradation.
5. **`instance:audit`, `role:manage` and `system:settings` remain unreachable** — three of the seven SYSTEM permissions still have no surface. The instance audit log in particular has a table and no reader.
6. **There is no cross-company user directory.** The console can invite a person into a company but cannot answer "which companies does this person already belong to", so an operator can invite someone into a company they are already in and learn it only from the `ALREADY_ACTIVE` outcome. Not an oversight to fix casually — plan §6s states a user's company list is theirs alone and no admin below Instance Admin may see it. An operator *may*, so such a read would be legitimate; it is simply not built here.
7. **A person invited from the console shows no trace of who invited them.** `MembershipInviteService` records the caller as `created_by`, so the data is there, but neither surface displays it. Worth having the day an operator adds someone to the wrong company.
8. **The company switcher is still not built.** An operator who is also a member of a company has no way to move between the instance console and that company in one session; they hold SYSTEM permissions with no company open. M6 owns this — and since this plan is now *inside* M6, the two should be sequenced together: the switcher is what makes "jump into the company I just provisioned" work.
9. **The console shows no operator roster.** `GET /instance/admins` was cut when the design was "exactly one operator"; now that there can be several, a read-only list on `/instance/people` — who operates this instance, and has each accepted their invitation yet — is worth adding back. One read, no new permission, no new table.

---

## Self-review

**Spec coverage.** UI company creation → Tasks 3, 5, 13. Company list + detail → Tasks 3, 4, 13. Suspend/reactivate → Tasks 1, 6, 13. Re-run seeds → Task 6's note + Task 13. First-boot bootstrap emailing the sender address → Tasks 7, 8, 9, 13 Step 5. Additional operators via CLI, none via UI → no task adds a grant surface; Task 14 Step 2 records the decision. Invite a user and assign them to a company via a dropdown → Tasks 10, 11, 13. Two distinct permission surfaces → the Architecture table, Task 12 Step 5's nav separation, Task 13's `InstancePeoplePage` standing notes; no task modifies `src/features/admin/`, which is how the existing surface stays as it is. CLI kept as break-glass → no task removes it; Task 14 Step 3 documents it.

**Consistency.** `InstanceCompanyView` is the single roster shape and every endpoint that returns a company returns it. `InstanceOperatorGate.demand` is the only permission call in all three controllers. `keys.instanceCompanies` / `keys.instanceCompany` are the only new query keys and `instance.ts` the only module using them. `ProvisionCompanyOutcomeView` mirrors `ProvisionCompanyResult` field-for-field plus `invitationDelivered`. `instance_bootstrap_invitation` has exactly **one** writer (`InstanceBootstrapService`) and one reader (`BootstrapController`).

**The invariant to re-check after every task.** Exactly one code path in `src/main` writes `instance_role_assignment`, and it is `GrantInstanceAdminService`, called from exactly two places: the break-glass CLI command and `InstanceBootstrapService.accept`. If a third caller appears, or if `InstanceInviteService` grows a branch that does not require `companyId`, the "operators are created on the CLI, never in the UI" rule has been broken. Task 10's `omittingTheCompanyIs400AndCreatesNoOperator` is the regression test for it.

**Milestone.** This plan is **M6** scope, deferred on 2026-08-07 — see Scheduling at the top. Nothing here should be executed during M3–M5, and the whole Orientation section needs re-grepping when M6 starts.

**Naming to carry through.** The page is `InstancePeoplePage` at `/instance/people`. There is no `InstanceAdminsPage`, no `GrantInstanceAdminDialog`, and no `/instance/admins` endpoint in either direction — an earlier draft had all three and they were removed by decision, not by oversight.

**Places the implementer must check rather than trust this plan.** Marked inline at each: `IntegrationTestBase`'s fixture and token helper names; `CompanyContext`'s "current or null" accessor; `InstanceAuditEvent`'s factory signature; `CompanyProvisioningService`'s method name; `AdminController`'s JWT-subject helper; `api.delete`'s signature; `src/test/`'s render helper and its fetch-stubbing API; `InvitationTokens`' minting API; the `CompanyMembership`/`Project` entity field names in the roster query.
