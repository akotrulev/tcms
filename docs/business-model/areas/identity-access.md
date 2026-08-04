# AREA-001 — Identity & Access

**Created:** 2026-08-03 · **Owned by:** `be-shared-business-documentation`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03

Covers: `auth` module (sign-in, session, invitations, `/me`) and `identity` module (company
membership, roles/permissions, teams, the admin directory).

## What this area is for

Controls who can get into the product at all, which client company they land in, and — once
in — precisely what they may see and do. Everything else in the system asks this area a
yes/no question before doing anything.

## Actors

- **Instance Admin** — the operator running the deployment. Provisions companies, is not a
  member of any company by default. `#observed` (`docs/specs/2026-07-30-tcms-roles-permissions-naming.md`)
- **Company Admin** — runs one client company: staffs it, creates projects, manages teams and
  vocabulary. `#observed`
- **Test Lead / Test Engineer / Automation Service / Viewer** — project-scoped roles, see the
  permission catalogue (`docs/specs/2026-07-30-tcms-roles-permissions-naming.md`, authoritative;
  referenced here rather than restated).
- **An invitee** — a person with an emailed link and no account yet, or an existing account
  being added to a second company.

## Capabilities

1. **Sign in with email + password.** `POST /auth/login` — `AuthController.java:39`. One
   session token and the caller's default company come back.
2. **"Log out" — client-side only.** `POST /auth/logout` returns 204 and revokes nothing
   server-side; the token is stateless and keeps working until it expires.
   `AuthController.java:73-76` `#observed`. The one lever that actually invalidates every
   session for an account is a permission-version bump, triggered by an authorization-relevant
   change (activation, membership change), not by this endpoint. `#inferred` — a real "sign out
   everywhere" or "revoke this device" feature is not present; whether the business considers
   that a gap is an open question.
3. **See who I am and what I can do.** `GET /me`, `GET /me/permissions?projectId=` —
   `MeController.java:58-103`. Returns held permissions **and the row-scope qualifier** (ALL /
   OWN / PLAN_SCOPE) per permission, specifically so the client can disable a control a
   qualified grant can't fully use rather than let the server reject the submit.
   `MeController.java:44-53` `#observed`.
4. **Redeem an invitation with no account at all.** `GET/POST /auth/invitations/{token}`,
   `InvitationController.java`. The only two endpoints reachable pre-authentication.
5. **Operator provisions a company and its first Company Admin.** CLI only (`provision-company`),
   never HTTP — `ProvisionCompanyCommand.java`. Idempotent: re-running tops up what's missing
   (vocabularies, key counters, a repaired `company:read` baseline) and never renames or
   overwrites anything that exists. `#observed` (`ProvisionCompanyCommand.java:84-95`).
6. **Grant/revoke a role**, scoped to one project or to the whole company —
   `RoleGrantService.java`, `POST/DELETE /role-assignments`. A COMPANY-scope grant needs
   `company:members`; a PROJECT-scope grant needs `project:members` **on that project**. A
   PROJECT-scope role may also be granted with no project at all, meaning "this role on every
   project in the company unless a project overrides it downward" — `RoleGrantService.java:396-427`.
7. **List and manage teams**, and staff them — `TeamController.java`, `TeamWriteService.java`.
8. **See who can be assigned work in a project** — `GET /projects/{id}/members`,
   `ProjectMembershipService.membersOf` (`ProjectMembershipService.java:79-116`).
9. **Company Admin manages the user directory**: invite, deactivate, reissue an invitation link
   — `AdminController.java` (`/users`, `/role-assignments`).

## Business rules

- **Every sign-in failure looks and takes the same time**, whatever the real cause (no account,
  wrong password, not yet activated, deactivated). A dummy password hash is checked even when
  no account exists, specifically so a timing difference can't be used to tell "no such account"
  from "wrong password". `AuthenticationService.java:15-33,77-94` `#observed`. Business intent:
  email is unique across the whole instance, so any finer signal becomes a directory of every
  client's users. `#inferred`, high confidence (stated directly in the code's own rationale).
- **A stranger can never distinguish "no such project/case/company" from "exists, but you're
  not allowed to see it."** Every unreachable target returns 404 with no body, never 403 —
  confirmed across `ProjectController.java:49-56`, `CaseController.java:38-44`, and repeated
  verbatim in every other module's controllers read this session. `#observed`. A 403 only ever
  appears *after* a caller has already proven they can see the target, refusing a specific
  action on it. `#observed`.
- **Company membership grants login and zero project visibility by itself.** A second gate — an
  explicit grant naming the project (directly, via a team, company-wide, or inherited) — is
  required before any project's content is visible. `ProjectMembershipService.java:14-57`
  `#observed`, matches the roles spec §A1.
- **A PROJECT-scope grant on a specific project always wins over an inherited company-wide
  one, even when it grants less** — a company-wide Test Lead can be knocked down to Viewer on
  one sensitive project by a single grant. `RoleGrantService.java:396-427` `#observed`. Intent:
  lets an administrator staff a whole portfolio in one grant while still walling off one
  sensitive project. `#inferred`, stated directly in the code.
- **Nobody can revoke the grant that is their own last route to revoking things.** Deleting a
  role assignment is rolled back if it would leave the caller unable to re-grant it.
  `RoleGrantService.java:174-200,277-327` `#observed`. Prevents a company from being locked out
  with no administrator and no repair short of the operator CLI.
- **The baseline "Company Member" role (`company:read`) can never be granted or revoked through
  this API.** It's a side effect of membership, not a decision about a person.
  `RoleGrantService.java:60-68,286-292` `#observed`.
- **SYSTEM-scope roles (Instance Admin) cannot be granted over HTTP at all** — only via the
  operator CLI. `RoleGrantService.java:45-58` `#observed`.
- **An invitation token, once redeemed, is unrecoverable if never delivered** — only a hash of
  it is stored. Re-issuing replaces the old link outright (old one dies); a routine "top-up"
  provisioning run leaves an outstanding, still-valid invitation alone rather than invalidating
  a link already in someone's inbox. `InvitationService.java:189-237` `#observed`.
- **Accepting an invitation never overwrites an existing password.** If the account already has
  one, the invitation only adds the new company membership, and a password submitted anyway is
  refused (422) rather than silently ignored or applied. `InvitationService.java:390-404`
  `#observed`. Intent: holding an invitation to a second company must not become a way to take
  over someone else's existing account. `#inferred`, stated directly in the code.
- **Redemption failure is one deliberately coarse answer** ("not redeemable") covering five very
  different causes (unknown token, expired, already used, membership since removed, account
  deactivated) — so a stranger holding a token-shaped string can't learn whether an address
  exists on the instance. `InvitationService.java:16-38` `#observed`.
- **Assigning work to someone requires them to be an *active* company member AND separately
  hold visibility on that project** — an invited-but-not-yet-accepted colleague, or a member of
  the company with no grant on the project, cannot be assigned. `ProjectMembershipService.java:118-136`
  `#observed`.
- **Only one path can ever create a company: the operator CLI, driven by a human running a
  script against the server.** No self-service company signup exists. `#observed`
  (`ProvisionCompanyCommand.java`, no controller endpoint anywhere creates a `Company`).

## Failure behavior

| Situation | Response | Evidence |
|---|---|---|
| Wrong password / unknown account / inactive account | 401, no detail | `AuthController.java:44-49` `#observed` |
| Reading `/me/permissions` for a project you can't see | Empty permission set (not an error) | `MeController.java:64-77` `#observed` |
| Invitation token unknown/expired/reused | 404, no body | `InvitationController.java:52-57` `#observed` |
| Password rejected by policy on accept | 422 with the specific reason | `InvitationController.java:79-88` `#observed` |
| Grant request naming an unreachable project | 404 (before permission is even checked) | `RoleGrantService.java:212-225` `#observed` |
| Grant request the caller may not make | 403 | `RoleGrantService.java:223-225` `#observed` |
| Re-granting an identical role | 200 with the existing grant (not an error, not a duplicate) | `RoleGrantService.java:69-78,232-235` `#observed` |
| Revoking the grant that authorizes the revoke itself | Rolled back entirely, including its own audit row | `RoleGrantService.java:174-200,305-309` `#observed` |

## Boundaries / not built

- No self-service "sign up your company" — deliberate, per the provisioning model.
- No real session revocation ("log out this device") beyond a permission-version bump on
  authorization-relevant changes. `#inferred` — open question below.
- No password-reset flow was found in this trace (only invitation-driven activation). `#inferred`
  — open question below.

## Open questions for the business

1. Is "logout" only ever expected to mean "the client forgets the token," or does the product
   need a real server-side session revocation (e.g., a stolen laptop)? `#inferred`
2. Is there an intended self-service "forgot my password" flow, or is password reset always an
   administrator/operator action? No such endpoint was found in this trace. `#inferred`
3. Should there be a supported way to change your own password once you're already active
   (not via invitation)? Not found in this trace. `#inferred`
