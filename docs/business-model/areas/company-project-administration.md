# AREA-002 — Company & Project Administration

**Created:** 2026-08-03 · **Owned by:** `be-shared-business-documentation`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03

Covers: `provisioning` module (company setup) and `project` module's `ProjectController` /
`ProjectSettingsController` (project lifecycle, vocabularies, per-project review policy).
Reviews themselves are AREA-005.

## What this area is for

Stands a client company up, gives it its first project and administrator, and lets that
company shape its own configuration afterwards — what a "test type" means to them, how their
statuses are named, and whether/how peer review is enforced per project.

## Actors

- **Operator (Instance Admin)** — runs `provision-company` from a terminal against the deployed
  server. `#observed`
- **Company Admin** — creates further projects, edits company-wide vocabulary, edits per-project
  settings. `#observed`
- **Test Lead** — can also edit their own project's settings (`project:settings`). `#observed`

## Capabilities

1. **Provision a company.** One CLI command creates: the company row, its classification
   vocabularies and display-key counters, an optional first project, and an invited (not yet
   active) first Company Admin. `ProvisionCompanyCommand.java:14-25` `#observed`.
2. **Re-run provisioning safely.** Idempotent — re-running against an existing slug tops up
   whatever is missing (e.g., vocabulary added by a later release, a repaired baseline grant)
   and never renames or overwrites what already exists. `ProvisionCompanyCommand.java:84-95`
   `#observed`.
3. **List and view projects visible to the caller.** `GET /projects`, `GET /projects/{id}` —
   `ProjectController.java:86-138`. An empty list is a normal, valid state (a member invited
   but not yet staffed on anything). `#observed`.
4. **Create a project.** `POST /projects` — `ProjectController.java:91-129`. The *only* write in
   the whole application whose permission gate does not depend on a target the caller already
   has to be able to see, because there's no target yet — it's gated purely on the caller
   holding `project:create` in the open company. `#observed`.
5. **View and extend a project's classification vocabulary** (Test Type / Test Level / Execution
   Phase — company-wide; Feature — project-owned) — `GET /projects/{id}/classification`,
   `POST/PUT/DELETE .../classification/values`. `ProjectController.java:140-226` `#observed`.
6. **View a project's configurable execution statuses.** `GET /projects/{id}/statuses` —
   seeded with six defaults per project, extensible. `ProjectController.java:228-242` `#observed`.
7. **Read and change a project's review policy** (four settings — see AREA-005 for what they
   control): `GET/PUT /projects/{id}/settings` — `ProjectSettingsController.java`.

## Business rules

- **A project can only be created by someone who can act company-wide** (`project:create`),
  never by a role scoped to an existing project — creating a project cannot be authorized by
  power over a *different* target. `ProjectController.java:91-101` `#inferred` (the code's own
  rationale, high confidence).
- **A project's classification vocabulary is split by ownership on purpose.** Test
  Type/Level/Phase belong to the *company* (so a client's several projects stay comparable to
  each other); the Feature tree belongs to the *project* (two different client products
  legitimately have different feature breakdowns). `ProjectController.java:140-150` `#observed`,
  matches `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` B3.
- **The permission needed to add a classification value depends on which vocabulary it belongs
  to, not on a fixed key**: the company-wide axes need `company:vocabulary` at company scope;
  the project's own Feature tree needs `project:settings`. `ProjectController.java:161-168`
  `#observed`. The 403 message deliberately does not say which of the two was missing, so as
  not to disclose which axis belongs to whom. `ProjectController.java:296-310` `#observed`.
- **A classification value cannot be retired while it still has live children** — refused with a
  count of how many. `ProjectController.java:191,312-329` `#observed`.
- **Execution statuses and shared/library content are project-scoped, not company-scoped** —
  each project's own vocabulary. `ProjectController.java:228-234` `#observed`.
- **Project settings are readable by anyone who can see the project, but writable only by
  `project:settings`.** Intent: a screen that shows "review is off" is a fact every viewer of
  the project needs in order to render the right UI, not a privileged fact.
  `ProjectSettingsController.java:42-49` `#observed`.
- **Provisioning never emails a link it cannot also hand to the operator directly** — the
  activation link is printed to the console *in addition to* being emailed, because mail
  delivery can fail after the company is already committed, and the console is the only fallback
  that doesn't require re-provisioning. `ProvisionCompanyCommand.java:219-231` `#observed`.
- **Two administrators creating a project with the same key at the same instant** both see "no
  conflict" and one loses at the database constraint — handled as the same field-level 400 the
  loser would have seen a moment earlier, not a 500. `ProjectController.java:244-264` `#observed`.

## Failure behavior

| Situation | Response | Evidence |
|---|---|---|
| Caller lacks `project:create` | 403 (never 404 — there's no target to conceal) | `ProjectController.java:95-101,287-295` `#observed` |
| Project key already taken (incl. a race) | 400, field error on `key` | `ProjectController.java:244-264` `#observed` |
| Unreachable project on any read/write | 404, no body | `ProjectController.java:49-56` `#observed` |
| Retiring a classification value with children | 409, with the child count | `ProjectController.java:312-329` `#observed` |
| Settings PUT without `project:settings` | 403 | `ProjectSettingsController.java:59,71-72` `#observed` |

## Boundaries / not built

- No self-service company creation — the operator CLI is the one path, by design.
- No UI/API path found for renaming a company or changing its slug — provisioning states both
  are immutable once set (per `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` B3).
  `#inferred`, worth confirming as intentional.

## Open questions for the business

1. Is there ever a supported way to deprovision or merge two client companies, or is "suspend,
   never delete" (named in the roles spec as instance-level policy) the complete lifecycle? No
   suspend/delete endpoint was found in this trace. `#inferred`
2. Should a Company Admin ever be able to rename or re-key a project after creation? Not found
   in this trace — worth confirming this is deliberate immutability rather than a gap.
   `#inferred`
