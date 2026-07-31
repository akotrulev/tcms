# §43. The `company:read` scope repair — a baseline grant, not a wider evaluator (2026-08-01)

Run through the **`be-java`** skill (Mode B — extend), with **`be-java-database-code`** for the JPA
side and **`data-migrations`** for `V15`. **Lane B** of the third three-lane parallel run; **not**
the contract holder, and it needed no contract change — see "What the integrator has to know".

Closes the gap §39 found and deliberately did not fix. Roles spec §A3 grants `company:read` to
*"every member"*, and `V3` seeds it onto five of the six built-in roles saying exactly that in its
own comment. **Four of those five are PROJECT-scope**, and `PermissionEvaluator.evaluateCompanyWide`
answers from the SYSTEM and COMPANY terms only — by design since §34, because "held on any project"
would make a Test Lead's `project:settings` read as a company-wide capability. So a permission the
spec says every member holds was **invisible to the only question that asks about the company**, for
everyone below Company Admin.

| Path | Purpose |
|---|---|
| `V15__company_read_baseline_grant.sql` | The seventh `role` row, the grant statement as a function, and the backfill. |
| `identity/Role.java` | `COMPANY_MEMBER_ID`, and why it is not one of the six. |
| `identity/AdminDirectoryService.java` | `reachOf` asks the direct question; the baseline is filtered out of the catalogue and the assignment list. |
| `identity/RoleGrantService.java` | The baseline is refused in both directions — 400 on grant, 403 on revoke. |
| `identity/InvitationService.java` · `provisioning/CompanyProvisioningService.java` | The two join paths, both calling the one statement. |
| `provisioning/ProvisionCompanyResult.java` · `ProvisionCompanyCommand.java` | `baselineGrantsCreated`, so a top-up that repaired something does not report a no-op. |
| `identity/CompanyReadBaselineTest.java` | **New, 6 tests** — the catalogue-derived check and the join paths. |
| `docs/specs/…-roles-permissions-naming.md` | §A2 and §A3 corrected; this document is authoritative and was this lane's. |

### The decision, and the repair that was refused

**Repair (a), taken:** every company member receives an explicit **COMPANY-scope grant of a minimal
built-in role holding exactly `company:read`**, seeded on joining. The evaluator's scope model is
**unchanged** — `evaluateCompanyWide` is still SYSTEM + COMPANY.

**Repair (b), rejected — recorded here so it is not re-proposed:** letting `evaluateCompanyWide`
answer *"held anywhere in this company"*. It repairs this one permission and breaks **every other
one**: `case:delete` held on a single project would read true for the whole company, and every
company-scope gate in the product would then be answering a question about one project. Scope is
precisely what makes a Test Lead the lead of *one* project. The tempting part of (b) is that it is
one line and needs no migration; the cost is that the line is in the class that *is* the
authorization gate.

### A seventh role, and why nothing existing could carry it

The one thing this slice had to work out for itself. **No existing role can hold the baseline:**

- the four PROJECT-scope roles cannot be granted at COMPANY scope at all — `scopeError` refuses the
  mismatch, and it should: **Viewer granted company-wide would confer `project:read` and `case:read`
  over every project present and future**, which is the exact boundary §A1 draws when it says company
  membership grants authentication and *zero* project visibility;
- Company Admin is COMPANY-scope and confers everything.

So: **`Company Member`**, COMPANY scope, `built_in = true`, holding exactly `company:read`.

**It is not the seventh role §39 anticipated.** That one was a company-wide read-only *stakeholder*,
who reads every project; this one grants **nothing about any project**, which is what makes it safe
to hold universally. Both could exist. Conflating them would turn a baseline into a visibility
grant, which is the failure the whole slice exists to avoid.

**It is not one of the six, and that is enforced rather than described.** It is filtered out of
`GET /roles`, refused by `POST /role-assignments` (400 on `roleId`, as an unknown role — telling a
caller that a role they cannot see exists is the disclosure this endpoint avoids elsewhere), refused
by `DELETE /role-assignments/{id}` (403), and its grants are filtered out of `GET /role-assignments`.

That last one is not tidiness. **A listed baseline carries a Revoke, and revoking it takes away a
colleague's sight of the company with no repair short of the operator CLI** — and it is the §31
defect in its purest form, a button whose server answer is "no". The mutation that removed the list
filter proved a second consequence nobody predicted: `RoleAssignment.role` is `required` in the
contract, and the view assembler resolves role names from the *filtered* catalogue, so a baseline row
reached the wire with **`role: null`** and the test died on an NPE rather than an assertion.

### The orientation pass found one join path, not two — and the ceiling is elsewhere

Grepped rather than assumed. **`CompanyMembership.invite` has exactly one caller in `main/`:**
`CompanyProvisioningService`. Invitation acceptance *activates* a membership; it does not create one,
and there is no invite-a-colleague endpoint yet (§39 "no membership management"). So the honest
statement is: one path creates memberships today, and the baseline is granted there.

The grant is nonetheless also called from `InvitationService.redeem`, deliberately — accepting is the
moment a person actually **joins**, it is idempotent and writes nothing on the normal path, and it
repairs a membership row created by a path that forgot (the invite endpoint that is next, a restore
tool, a hand-written row). Its test removes the grant first, so it asserts the repair and not the
no-op.

`V13`'s uniqueness was already sufficient: `uq_role_assignment_user` is partial on `user_id` with
`NULLS NOT DISTINCT`, and `project_id IS NULL` **is** the COMPANY scope — so the baseline is exactly
the shape §39 wrote that index for. No DDL was needed for idempotence.

### Why the grant is a plpgsql function and not three Java call sites

`tcms_grant_company_read_baseline(company_id) RETURNS integer` — one statement, three callers: the
migration's backfill, provisioning, and acceptance. The argument is V2 §4's and V5's, verbatim: these
are **company-owned rows under `FORCE ROW LEVEL SECURITY`**, so an insert needs `app.current_company`
set *including for the migration role*, which is why the backfill is a loop that sets the GUC per
company rather than one `INSERT … SELECT`. `company` is instance-level (ERD §0.1), so the loop can
see every company to iterate over. `SECURITY INVOKER`, per V8.

Three copies of "who gets the baseline" would drift, and the copy that drifts silently is the one
that decides whether a client can see their own company. The function returns its row count, which is
what lets `provision-company` report a repair: a top-up run that granted 3 baselines is **not** a
no-op, and `wasNoOp()` says so.

`granted_by` is **NULL** on every baseline row: nobody decided it. Attributing it to the operator or
the inviter would put a person's name on a row they did not choose to create, in the table an audit
reader consults to answer "who gave them that".

### The workaround is gone, and the equivalence is pinned rather than read

`AdminDirectoryService.reachOf` asked *"held company-wide **or** on any project"*. It now asks the
direct question. The two are **equivalent for every member the product creates** — which is exactly
why removing the workaround **killed nothing in 746 tests**, and why that survival is evidence rather
than reassurance.

The persona that distinguishes them is a member holding a **PROJECT-scope grant and no baseline**:
the workaround answers yes, the repaired code answers no. `CompanyReadBaselineTest` now seeds that
persona and asserts both the evaluator's answer and `reachOf`'s, so reintroducing the workaround —
here or in the next surface to gate on a company-scope permission — is a red test.

The behaviour change is real and is in the fail-closed direction: a membership row written **by hand**
now answers "no" to `company:read` until `provision-company` is re-run. That is the correct direction
for an authorization gate, and the repair is one documented command.

### Mutation evidence

Eight narrow edits, one at a time, full suite each. Seven killed exactly what they should; one
survived and was then killed by a test written for it.

| Mutation | Tests failed |
|---|---|
| Provisioning stops granting the baseline | **3** — including the top-up repair and the idempotence row count |
| The membership-baseline filter dropped from `GET /role-assignments` | **3** — including `role: null` reaching the wire, and EAST seeing WEST's grant count |
| The baseline is grantable over HTTP | *(same run)* — 200 where the contract needs a 400 |
| `status <> 'REMOVED'` dropped from the grant | **1** — offboarding topped back up |
| The baseline excluded from `GET /roles` no longer | **1** — a seventh name on a closed six-value enum |
| The revoke refusal disabled | **1** — a Company Admin removing a colleague's `company:read` |
| The role seeded at PROJECT scope instead of COMPANY | **2**, and *only* `SeedDataTest` — see below |
| `reachOf` reverted to the workaround | **0 → then 1**, see below |

**The scope mutation is the interesting one.** Changing the role's `scope` column to `PROJECT` broke
no behaviour at all, because `role_assignment` does not enforce scope: what makes a grant
company-wide is `project_id IS NULL` **on the grant row** (`RoleAssignment`'s own encoding), not the
role's metadata. The two `SeedDataTest` assertions are therefore the only thing standing between the
catalogue and a role whose declared scope lies about what its grants do — which is also why
`RoleGrantService.scopeError` exists, and why §39 could note that the "stakeholder Viewer over the
whole company" fixture is seeded rather than grantable.

### Gate evidence

`./gradlew build` clean with `-Werror`, **746 tests** (733 + 13), against Testcontainers Postgres 17
with the non-superuser owner and RLS in force. `find . -name "* 2.*"` — nothing.

**The suite caught this lane writing §19's defect a second time.** `CompanyReadBaselineTest` was
written against the `b1…` id prefix, which `CaseCreateEndpointTest` already owns — so a test named
*"a REMOVED membership is not granted one"* offboarded **that** class's Test Engineer, and five
case-creation tests failed with a 404 that reads like a broken project fixture. Green alone, red in
the suite, and the failing class was innocent. Fixed by moving to `8c…`, chosen after grepping every
`UUID.fromString` in the suite — which is what should have happened the first time, and is written
into the class header so the next author does the grep.

### What the integrator has to know

1. **`V15` is pre-assigned and expects renumbering** in landing order. It is a new file, never an
   edit, so renumbering is free — but `spring.flyway.out-of-order` is unset, so landing it *after* a
   higher number on an already-migrated database is refused.
2. **No contract change was needed, and one is now owed as a follow-up.** `openapi.yaml`'s
   `Role.name` is a closed six-value enum and `Role`'s description says "one of exactly six built-in
   roles" — both still true of the wire, because the baseline never reaches it. If a future slice
   ever wants to *show* the baseline (an "access" tab that explains why someone can see the company
   at all), the contract has to say so first. The SPA needed no change and none was made.
3. **No permission was added, removed or moved between roles**, so `lib/permissions.ts`,
   `schema.d.ts` and `PermissionKey` are all untouched and `SeedDataTest`'s three-way comparison
   still passes unchanged.
4. **`ProvisionCompanyResult` gained a field** (`baselineGrantsCreated`, an `int`). It is a CLI-side
   record, not a wire type.

### Deliberately not done

- **No `company_membership` trigger.** A trigger would make "every join path grants it" structural
  rather than disciplined, and it was seriously considered. Refused because it hides an
  authorization write from the Java layer entirely, has no sensible `granted_by`, and needs a second
  half for `DELETE`/offboarding — and because the discipline is currently cheap: one path creates
  memberships. The moment there are three, revisit this rather than adding a third call site.
- **No audit row per baseline grant.** `role_assignment.granted`/`revoked` events record a decision
  somebody made; this is a consequence of membership, and the membership creation is already
  audited. The provisioning trail carries `baselineGrantsCreated`, which is the fact an auditor would
  actually ask for ("did that upgrade repair anything").
- **The baseline is not exposed as "why can I see this company".** The SPA gates on
  `company:read` and now gets a truthful answer everywhere; nothing renders the grant itself.
- **`AdminDirectoryService` still runs N evaluations per admin read.** Unchanged trade, and it
  improves for free when the evaluator's cache lands — the same note §39 leaves.
- **No membership-removal path was added**, so nothing yet deletes a baseline grant when someone is
  offboarded. `tcms_grant_company_read_baseline` refuses to re-grant one for a `REMOVED` membership
  (pinned by a test), so the invariant holds from this direction; the delete belongs with the
  `company:members` write surface §39 named as next.
- **The migration's own backfill loop ran over zero companies** in the test harness, because
  Testcontainers starts empty. What is covered is the function it calls — by the top-up test, which
  deletes every baseline in a company and re-runs the CLI, which is the same statement over the same
  predicate. Stated rather than implied.

### Next

1. **Teams and memberships** — still §39's next item, and now with one more reason: the
   invite-a-colleague endpoint is the second membership-creating path, and it must call
   `tcms_grant_company_read_baseline` (or the trigger question above gets re-opened).
2. **Offboarding deletes the baseline**, with the same write surface.
3. **The live browser pass §39 left open**, end to end with no SQL: provision, create a project,
   invite a colleague, grant them a role — and now, additionally, confirm a plain member sees the
   company screen at all, which before this slice they could not.
