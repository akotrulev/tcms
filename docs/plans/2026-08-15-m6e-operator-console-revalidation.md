# M6 Plan E — Instance Operator Console: Revalidation and Amendments

**Date:** 2026-08-15 · **Written by:** `ep-writing-plans` · **Master plan:** `docs/plans/2026-08-15-m6-multi-company-activation.md`

> **This file does not replace `docs/plans/2026-08-07-instance-operator-console.md`.** That plan is
> sound and stays the executable source for its fourteen tasks. This is the revalidation block its
> own `:30` demands — *"This plan's Orientation section records the state of the code on 2026-08-07.
> M6 is several milestones away, so treat every claim in it as needing a re-grep"* — plus the
> amendments the four decisions of 2026-08-15 force. **Read this file, then execute that one.**

**Depends on:** Plan A (`instance:read`, `CompanyRef`) and Plan B (`SuspendedCompanyFilter`).

---

## 1. Re-grep results — every Orientation claim, checked at `8c2bacb` on 2026-08-15

The console plan's Orientation has six findings. **Five hold. One has moved and one number is
stale.**

| # | Claim | Verdict |
|---|---|---|
| 1 | The seven SYSTEM permissions already exist in `V3:99-105`, and Instance Admin's grants are derived from the catalogue (`V3:167-172`), so **no migration is needed to gate this console** | **HOLDS** — and it is now eight, because Plan A adds `instance:read` by the same derivation. |
| 2 | The operator session already works; `isSystemAdmin` reaches `schema.d.ts` and is consumed by **nothing** in `src/` except a test fixture | **HOLDS FOR THE SPA, and the sentence is now incomplete.** `isSystemAdmin` is consumed for real in `packages/mcp/src/session.ts:101,217`. The claim as written is about `frontend/src/` and is still true there — three test fixtures and nothing else. |
| 3 | The services are already CLI-independent; `GrantInstanceAdminService.grant(email, actorUserId)` documents `null` as this plan's case | **HOLDS.** |
| 4 | `SUSPENDED` is enforced nowhere; the only `src/main` hit is `Company.suspend()` | **HOLDS as written, and is half wrong in the product's favour.** Login already declines to pick a suspended company — `tcms_companies_for_user`'s loop is `WHERE status = 'ACTIVE'` (`V8:66`) — so the gap is *"suspension takes effect at next sign-in"*, not *"suspension does nothing"*. A token minted before the suspension keeps working for its whole TTL. **Plan B Task 1 closes it.** |
| 5 | `membership_invitation.company_id` is `NOT NULL` (`V6:65`), so neither the bootstrap operator nor an Instance-Admin invitee can use that table | **HOLDS.** |
| 6 | An Instance Admin already passes `company:members` in any company, because `evaluateCompanyWide` starts from the SYSTEM term and the company gate only *adds* to it | **HOLDS** — `PermissionEvaluator.java` still starts from `systemTermIfAccountActive(userId)`. This is what lets Task 10 open a `CompanyContext` and call `MembershipInviteService` unchanged. **Do not write a second invite path.** |

**Numbers that moved:**

| Was | Is |
|---|---|
| Next free Flyway version **V39** | Highest on disk is **V52**. This console's table takes **V56** (pre-assigned by the master plan; V53–V55 belong to Plans A–C). |
| *"the working tree currently carries the uncommitted §121 flake-detection slice"* | Gone. The tree was clean at `8c2bacb`. **This precondition is discharged.** |
| Highest handoff section | **§216**. The console's own handoff sections number from there. |
| Frontend test count, backend test count | Both have moved repeatedly; do not assert on the plan's figures. |

---

## 2. Amendments — six, and three of them change what gets built

### A1 — Task 1 moves to Plan B, entirely

`SuspendedCompanyFilter` is M6 work the console pulled forward, and it now sits with the
suspend/reinstate service it protects. **Do not implement Task 1 from the console plan.** Plan B's
Task 1 is the same work with three corrections the console plan could not have known:

- `CompanyContext.currentOrNull()` **does not exist**. `CompanyContext.current()` returns
  `Optional<UUID>` (`CompanyContext.java:98`). Use `.current().orElse(null)`. Do **not** add a getter
  beside `open(...)` as the console plan's note suggests — the scope-guard shape is what stops a
  company outliving its block, and a null-returning accessor is the first step to a setter.
- The console plan's snippet writes a **JSON literal** for the refusal body. §146 deleted one of
  those and §163 declined to reintroduce one; `ErrorBodyPolicyTest` pins both envelopes' key sets
  against the contract. Emit through `api.Problem` the way `RateLimitFilter` does for its 429.
- Its second test (`suspendedCompanyStillAdmitsAnInstanceAdmin`) hits `/api/v1/instance/companies`,
  which does not exist until Task 4, and the plan says to comment it out. Plan B's version tests the
  exemption against a path that exists today, so nothing is commented out and nothing has to be
  remembered.

**Console plan Task 6 (suspend/reinstate) now depends on Plan B**, not on its own Task 1. Do not
start Task 6 until Plan B Task 1 has landed — a suspend button over an unenforced column is an
affordance that is believed and does nothing, which is the plan's own stated reason for ordering
them.

### A2 — `instance:read` replaces `company:create` on every read gate

The console plan's second sign-off (`:84`) proposed gating the console's reads on `company:create`
and named the trade honestly: *"the semantically cleaner option is a new `instance:read` key, but
that is a change to the roles spec, which has authority over the catalogue. Gating reads on an
existing write permission is the smaller lie and needs no migration."*

**Decided 2026-08-15: pay for the key.** Plan A Task 1 adds it. So in the console plan:

- **Task 2 (`InstanceOperatorGate`)** demands `instance:read` for reads. Its whole point — *"the one
  place a SYSTEM permission is demanded, so eight endpoints cannot disagree"* — is unchanged and is
  what makes this a one-line amendment rather than eight.
- **Tasks 4 and 6's reads** inherit it through the gate.
- The writes keep the keys that name them: `company:create` for provisioning, `company:suspend` for
  suspend/reinstate, `user:manage` for the invite path.
- **Record the reversal in the console plan itself**, struck through with *"→ superseded"*, per the
  working agreement. The original reasoning was sound and its cost was paid deliberately; a reader
  who finds only the new answer will assume the alternative was never considered.

### A3 — The first sign-off is accepted as written

`instance_bootstrap_invitation` goes on the **ERD §0.1 RLS exception list**, for the same reason
`membership_invitation` is on it: the accept path runs before authentication, so no company context
can exist. One table serves both callers — the first-boot bootstrap and one operator inviting
another — because they differ by one nullable column, not by a table.

**§0.1 is edited first; `SchemaConventionTest` derives its list from §0.1 and follows.** Task 7's
migration is **V56**.

> Note the neighbour this creates. Plan C proposes adding `company_email_domain` to the same list on
> the same reasoning. If both land, §0.1 gains two exceptions in one milestone — which is worth a
> sentence in each entry acknowledging the other, so the list does not start looking like a place
> things go when RLS is inconvenient.

### A4 — Known gap #8 is closed by Plan D

The console plan's gap list says *"the company switcher is still not built"* and asks for it to be
sequenced with this work. It is **Plan D**. Strike the gap and point at the plan.

### A5 — The operator-access audit trail is sized here, and it is a real gap

§6s requires this, in the section on operator access to client data:

> **Audited in the client's own trail.** Instance-operator reads and writes inside a company land in
> that company's audit log, **flagged as operator access**, not just in the instance log. This is
> cheap — one flag on the audit event — and it is the difference between an auditable arrangement and
> a backdoor.

State of play, verified 2026-08-15: `instance_audit_event` has an entity and a repository in
`tenancy/` and **no reader surface anywhere**. `instance_setting` exists in `V1:1375` and is used by
**nothing** — its only references are as an RLS/schema-convention exception. The console plan's own
gap #5 leaves `instance:audit`, `role:manage` and `system:settings` unreachable.

So the console **writes** operator events and **nothing renders them**, in either trail. That is a
disclosed capability with no disclosure, which is the half of §6s that makes operator access
*auditable* rather than merely *logged*.

**Amendment: add Task 15 to the console plan** — the company-side operator-access flag and a reader.
Sized honestly:

- The flag on the company's own audit event: **small.** One boolean set where the console opens a
  `CompanyContext`, plus a column.
- Rendering the company's audit log: **already gated** on `audit:view` (COMPANY scope, Company Admin)
  and there is no screen. **Medium**, and it is a company-side feature rather than a console one.
- Rendering the *instance* audit log under `instance:audit`: **medium**, and it is the console's.

**Recommendation: build the flag and the instance-side reader in Task 15; leave the company-side
audit screen as a named, sized gap.** Building the flag without any reader repeats the exact shape
`status` was in for a year. Say which was built in the handoff.

### A6 — Two invariants that must survive, restated because they are easy to erode

Both are the console plan's own and both are worth reading twice before Tasks 8–10:

1. **No authenticated user action creates an Instance Admin.** Two paths mint one — first-boot
   bootstrap to the instance's own sender address, and `grant-instance-admin` on the CLI — and
   neither is reachable from a browser. Three plausible features would each break it and two were in
   earlier drafts: a `POST /instance/admins`, an "Instance Admin" option in the invite dropdown, and
   a SYSTEM-scope role in the company role-assignment screen. `RoleGrantService`'s existing 400
   blocks the third and **its header comment stays untouched**. Task 10's test whose only job is to
   fail if the second comes back is not optional.
2. **The invite dropdown chooses a destination, not a role.** `companyId` is a *target*, exactly like
   `InstanceAuditEvent.companyId`, not a tenancy discriminator — and the safeguard is that the gate
   is SYSTEM-scope, so only an operator can name a company they are not in. `roleId` stays absent:
   the invitee lands as a member with zero project visibility, and roles are granted afterwards on
   that company's own screen under a different gate.

---

## 3. Execution order inside Plan E

The console plan's six phases, with the amendments folded in:

| Phase | Tasks | Change |
|---|---|---|
| **1** | ~~Task 1~~ | **Skipped — it is Plan B Task 1.** |
| **2** | 2, 3, 4 | Task 2 demands `instance:read` (A2). |
| **3** | 5, 6 | Task 6 depends on Plan B Task 1 (A1). |
| **4** | 7, 8, 9, 10 | Task 7 is **V56** and edits ERD §0.1 first (A3). |
| **5** | 11, 12, 13 | Unchanged. |
| **6** | 14, **15 (new)** | Task 14 records the reversals; Task 15 is the operator-access flag and the instance audit reader (A5). |

---

## 4. What to do before Task 2

- [ ] **Confirm Plan A has landed.** `instance:read` must exist in the catalogue, or Task 2's gate
  has nothing to demand. `cd backend && ./gradlew test --tests 'com.tcms.schema.SeedDataTest'` and
  check `grep -rn "instance:read" backend/src/main/resources/db/migration/`.
- [ ] **Confirm Plan B Task 1 has landed** before Task 6, not before Task 2 — Phase 2 is reads only.
- [ ] **Write the A2 reversal into the console plan** (`:84`), struck through, before implementing
  against it. A plan whose text still argues the old position is a plan that will be re-argued.
- [ ] **Re-check the free Flyway version.** V56 is pre-assigned, but Plans A–C may have landed out of
  order; `ls backend/src/main/resources/db/migration | sed 's/V\([0-9]*\)__.*/\1/' | sort -n | tail -1`.
- [ ] **Re-check the handoff section number.** §216 at planning time.

---

## Self-review

**Coverage.** Every one of the console plan's six Orientation findings is verdicted; both of its
sign-offs are answered (one accepted, one overturned); its stale Flyway number, its discharged
precondition and its gap #8 are corrected; and the one §6s requirement its gap list left unsized —
operator access audited in the client's own trail — is sized and given a task.

**Placeholders.** None. Every amendment names the file and the line it changes.

**The thing I am least sure of.** A5's recommendation — build the flag and the instance-side reader,
leave the company-side audit screen as a sized gap. The alternative is to build all three and let M6
grow again, or to build none and record that §6s's operator-access disclosure is unimplemented in
full. I recommend the middle because a flag with no reader anywhere is exactly the shape this
milestone exists to stop repeating, and because the company-side screen is a Company Admin feature
that happens to be where operator access shows up, not a console feature. **It is worth the user's
call**, and it is the last open question in M6's planning.
