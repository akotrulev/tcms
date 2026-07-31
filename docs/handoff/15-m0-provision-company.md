# §15. Backend M0 — `provision-company`, and the two defects writing it found (2026-07-30)

Run through the **`be-java`** skill (agent definition + `pattern-writer` + `database-code` +
`test-driven-development` + `code-review`), Mode B — extend. Closes §14's next-step list item 1 —
**the last M0 product deliverable.** Full evidence:
**`docs/audits/2026-07-30-provision-company-and-repository-transaction-defect.md`**.

### The headline: the first end-to-end use of a company found two fail-closed defects

Neither was a cross-company leak. Both were invisible to the existing suite for the same structural
reason — **nothing had yet tried to use a company end to end.**

| # | Defect | Fix |
|---|---|---|
| 1 (HIGH) | **A provisioned company could not allocate a display key.** Five entity types carry one (`TC-` `TS-` `TP-` `TR-` `DOC-`), and *nothing created their `key_sequence` rows* — `V1` declares the table, `V2` seeds the vocabularies, `V3` the permission catalogue, and no path writes a counter. The ERD's allocator is `UPDATE … RETURNING`, which against a missing row is **not an error but an empty result**, so it presents as "could not allocate a key" and invites two worse repairs: `MAX(key_seq)+1` (the racing allocator the ERD rejects) or `INSERT … ON CONFLICT` on the hot path of every case creation. | **`V5`** — `tcms_seed_company_defaults` also seeds the five counters, idempotently. In a migration, not the CLI, for `V2`'s own two reasons. A company provisioned earlier is topped up by **re-running the CLI**. |
| 2 (HIGH) | **Repository query methods ran outside any transaction.** Isolation is established in `CompanyAwareTransactionManager.doBegin`, which needs a transaction. Spring Data gives one to the CRUD methods *inherited* from `SimpleJpaRepository` (`findById`, `count`, `save`) — and **none** to the ones *declared on the interface*: derived queries and `@Query` methods have no implementation method to read an annotation from. So no `doBegin`, no `app.current_company`, and every RLS-protected read answered **zero rows** while `CompanyContext` held the right company. | **`@Transactional(readOnly = true)` on all eleven repository interfaces**, plus `RepositoryTransactionTest` — which both reproduces the defect *and* derives the repository list from the live context to fail the build on a new one that forgets. |

**Defect 2's blast radius is the part worth remembering.** It covered
`RoleAssignmentRepository`'s two permission-union queries — so **`PermissionEvaluator` resolved to
the empty set for a user who genuinely held grants**, unless the caller already had a transaction
open. Also the membership gate and every project lookup. Fail-closed throughout, so no leak; but each
symptom reads as a bug in the *feature* rather than in the plumbing.

**Why the suite missed it: the suite was shaped around it.** Every existing test wraps its reads in
an explicit `TransactionTemplate` — `PermissionEvaluatorTest`'s `memberOf` helper is
`inCompany(…, () -> tx.execute(…))`, written that way because the bare form did not work, without the
reason ever being chased. Measured with a probe, one line apart on the same thread: derived query →
empty, `count()` → 1 row, and the GUC read from inside the derived query's own execution → `''`.

### What shipped

| Path | Purpose |
|---|---|
| `db/migration/V5__seed_company_key_sequences.sql` | Defect 1's fix. Replaces `tcms_seed_company_defaults` whole (Postgres has no partial-body replacement), adding the five counters. |
| `provisioning/CompanyProvisioningService.java` | **The only provisioning path.** One transaction, company context opened *around* it (the transaction manager reads the context in `doBegin`; opening it inside leaves the GUC unset and RLS `WITH CHECK` refuses every insert). |
| `provisioning/ProvisionCompanyRequest.java` · `ProvisionCompanyResult.java` | Validated in the canonical constructor, so an invalid request cannot be constructed. The result reports **per part** whether each piece was created or already existed. |
| `provisioning/ProvisionCompanyCommand.java` | The CLI. Parsing and reporting only. Exit codes 0 / 2 / 1 = success (incl. top-up) / usage error / failed and rolled back. |
| `tenancy/InstanceAuditEvent.java` + repository | The operator trail. Append-only by construction — no mutator, no `@Version`. Lives in `tenancy` because suspension and admin appointment will write here too. |
| `tenancy/RepositoryTransactionTest.java` | Defect 2's reproduction **and** its mechanical enforcement. |
| `TcmsApplication` | `WebApplicationType.NONE` + banner off for the CLI invocation; a `Clock` bean. |
| `provisioning/package-info.java` | **A new module.** It composes `tenancy` + `identity` + `project`; inside any one of them it would create a sideways edge the module rules forbid, while depending *toward* those three is exactly what they permit. It is a leaf — nothing depends on it. |

### Decisions worth not re-litigating

- **The first Company Admin is `INVITED`, never activated.** An operator who could activate a
  client's administrator on their behalf is an audit finding, not a convenience.
- **Idempotent, and it reports per part.** Re-running is the *supported* way to top up a company
  after a release adds a vocabulary or a counter — which is what makes `V5` need no backfill. "It
  succeeded" is not enough information: an operator who meant to create a new client and sees
  "existed" everywhere has typed a slug belonging to another one.
- **It never *updates*.** A differing display name on a re-run is left alone rather than overwriting
  a rename the client asked for.
- **An existing email is reused, not rejected** — one account, two memberships, a different
  per-membership display name in each (§6u).
- **Both project arguments or neither.** Half a project means guessing the other half, in a field
  that is immutable once cases carry it.
- **No actor is invented for an unattended run.** A synthetic "system" user row is indistinguishable
  from a real operator in the trail.

### Gate evidence

`./gradlew build` clean — **115/115 tests** (87 pre-existing + 28 new).

**Mutation-verified**, eight mutations, one narrow edit each:

| Mutation | Tests failed |
|---|---|
| `V5` no longer seeds `key_sequence` (defect 1 restored) | 2 |
| `ProjectRepository` loses `@Transactional` (defect 2 restored) | 2 — the reproduction **and** the enforcement sweep |
| Membership created `ACTIVE` instead of `INVITED` | 1 |
| Grant idempotency check always says "absent" | 2 |
| `isRequestedBy` uses a prefix match | 1 — `provision-company-dry-run` would have provisioned |
| Half a project accepted | 1 |
| **`saveAndFlush` → `save` before the project seed call** | **0 — survived**, see below |
| Company context opened *inside* the transaction (blunt) | 14 — proves only that they run |

**The survivor is the finding, reported rather than re-rolled:** Hibernate's AUTO flush mode already
flushes ahead of a native query, so the explicit flush is not what makes that call work today — it is
what stops it depending on a flush mode nobody set deliberately. Kept, with the survival recorded in
the class comment. Same disposition as §13's surviving `company_id` predicate.

**Run for real** (lesson 5), from the boot jar against a live `postgres:17`, not from a test: first
run created all five parts and **exited 0** without starting a servlet container; the second reported
`existed` for all five; `--slug=acme` alone printed the named missing argument plus usage and exited
**2**.

### Three things worth not re-learning

1. **A repository's inherited methods and its declared methods do not get the same transaction
   treatment**, and only the declared ones matter for anything with a `WHERE`. Any ambient
   per-transaction mechanism — RLS GUCs, filters, auditing, `SET LOCAL` anything — silently skips
   them unless the interface is annotated. The one-sidedness is what makes it hard to see: `count()`
   works, so the connection, the pool and the policy all look fine.
2. **A suite can be shaped around a defect rather than exposing it.** Every read here was wrapped in
   a `TransactionTemplate` because the bare form "didn't work"; nobody asked why. When a test needs a
   wrapper to pass, the wrapper is a finding until it is explained.
3. **`@JdbcTypeCode(JSON)`, not `columnDefinition = "jsonb"`** — the `citext` trap in a different
   shape, and the difference between the fixes is the point. `columnDefinition` only satisfies
   `ddl-auto: validate`, which compares the SQL type *name*; the parameter is still bound as
   `varchar`, and Postgres does **not** implicitly cast `varchar` to `jsonb` the way it does to
   `citext`. The context starts, every read works, and the **first insert** fails — 14 tests at once.

### Next on the backend, in order

1. ~~**ArchUnit + Modulith verification.**~~ → **done 2026-07-30, see §16.**
2. **Docker Compose and CI.** Everything else in M0 is done.
3. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation.
4. **The invitation/activation path** — provisioning leaves an `INVITED` administrator and there is
   no table, token or email behind that yet. It is the first thing M0.5 needs to be usable.
