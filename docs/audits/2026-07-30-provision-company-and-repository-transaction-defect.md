# provision-company, and the two defects writing it found

**Date:** 2026-07-30
**Scope:** Backend M0's last deliverable — the `provision-company` operator CLI.
**Run through:** the `be-java` skill (agent definition + `pattern-writer` + `database-code` +
`test-driven-development` + `code-review`), Mode B — extend.
**Gate:** `./gradlew build` clean, **115/115 tests** (87 pre-existing + 28 new), mutation-verified
across eight mutations, plus a real CLI run against a live Postgres 17.10.

---

## 1. The headline

Provisioning is the one path by which a client company comes into existence, and building it turned
up two defects that had nothing to do with provisioning. Both were **fail-closed** — neither was a
cross-company leak — and both were invisible to the existing suite for the same structural reason:
**nothing had yet tried to use a company end to end.**

| # | Defect | Severity | Fix |
|---|---|---|---|
| 1 | **A provisioned company could not allocate a display key.** Nothing ever created its `key_sequence` rows. | HIGH (fail-closed) | `V5` — the counters are seeded by `tcms_seed_company_defaults` |
| 2 | **Every repository query method ran outside a transaction**, so `app.current_company` was never set and every RLS-protected read returned zero rows. | HIGH (fail-closed) | `@Transactional(readOnly = true)` on every repository interface, enforced by a computed test |

---

## 2. Defect 1 — the display-key counters nobody created

### What was wrong

Five entity types carry a company-scoped display key (`TC-` `TS-` `TP-` `TR-` `DOC-`, plan §6t). The
ERD specifies a deliberately single-statement allocator, so concurrent creation cannot issue a
duplicate:

```sql
UPDATE key_sequence SET next_value = next_value + :n
 WHERE company_id = :c AND entity_type = :t
RETURNING next_value - :n;
```

`V1` declares the table. `V2`'s `tcms_seed_company_defaults` seeds the classification vocabularies.
`V3` seeds the instance-level permission catalogue. **No path writes a `key_sequence` row.** A
provisioned company therefore had none, and the allocator matched zero rows.

### Why it matters more than "an INSERT was missing"

It fails in the shape that invites the wrong repair. `UPDATE … RETURNING` matching nothing is **not
an error** — it is an empty result. The caller sees "could not allocate a key", and the two obvious
fixes are both worse than the defect:

- `MAX(key_seq) + 1` — precisely the racing allocator the ERD rejects, and it issues duplicates only
  under concurrency, months later;
- `INSERT … ON CONFLICT` at allocation time — a write on the hot path of every case creation forever,
  to cover a one-time setup step.

### The fix, and where it lives

`V5__seed_company_key_sequences.sql` replaces `tcms_seed_company_defaults` so it also seeds the five
counters, `ON CONFLICT DO NOTHING`. It lives in a migration rather than in the CLI for the same two
reasons `V2` gave for the vocabularies (V2 §4): these are company-owned rows under
`FORCE ROW LEVEL SECURITY`, and they are product constants that should be reviewed like schema.
Putting them in Java would also mean a second provisioning path — a fixture, a future restore tool —
could create a company that looks complete and cannot allocate a key.

Because the function is idempotent, **a company provisioned before `V5` is topped up by re-running
the CLI**, not by a backfill migration.

`CompanyProvisioningServiceTest` asserts the counters through the ERD's own allocator statement
rather than by reading `next_value` — reserving 1, then 500, then 1 more, and asserting `1, 2, 502`.
Reading the column would have passed against a table that could not actually allocate.

---

## 3. Defect 2 — repository query methods had no transaction, so RLS saw no company

### What was wrong

Isolation is established in `CompanyAwareTransactionManager.doBegin`. That requires a transaction to
exist. Spring Data gives one to the CRUD methods a repository **inherits** from `SimpleJpaRepository`
(`findById`, `count`, `save`) via that class's own `@Transactional`. It gives **none** to the methods
**declared on the repository interface** — derived queries and `@Query` methods have no
implementation method to read an annotation from, so unless the *interface* carries one they execute
outside any transaction.

No transaction → no `doBegin` → no `app.current_company` → every RLS-protected table answers with
**zero rows**, while `CompanyContext` sits there holding the right company.

### Measured, not inferred

The probe that found it, one line apart on the same thread with the same company open:

```
bare=false  rw=true  ro=true
bareCount=1 bareFindAll=1
bareGuc=''  txGuc=8611f4b7-…  ctx=Optional[8611f4b7-…]
```

- `findByCompanyIdAndUserId` (derived) → **empty**
- `count()` / `findAll()` (inherited) → **1 row**, same instant
- the GUC read from inside the derived query's own execution → **`''`**, the reset value — the second
  spelling of "absent" that `V4` already had to teach the policy about
- instrumenting `doBegin` confirmed it: **4 calls for 4 explicit transactions, none for the bare
  repository calls**

Read-only versus read-write made no difference; the boundary is exactly "declared on the interface"
versus "inherited from `SimpleJpaRepository`".

### Blast radius

Every custom query method on every repository, including:

- `RoleAssignmentRepository`'s two permission-union queries — so **`PermissionEvaluator` resolves to
  the empty set for a user who holds grants**, unless the caller already opened a transaction;
- `CompanyMembershipRepository.findByCompanyIdAndUserId` — the membership gate;
- `ProjectRepository.findByKey` / `findScopedById` — a list screen rendering "no rows" instead of a
  client's data.

It fails closed, so it was never a leak. What it is, is a family of failures that each read as a bug
in the *feature* rather than in the plumbing.

### Why the suite did not catch it

**Every existing test wraps its reads in an explicit `TransactionTemplate`.** That is not wrong, but
it means the suite was shaped around the defect rather than exposing it. `PermissionEvaluatorTest`'s
`memberOf` helper is `inCompany(…, () -> tx.execute(…))` — written that way because the bare form did
not work, without the reason being chased down.

This is the same family as handoff §6 lesson 9 and §14 lesson 1: **a property tested only under the
conditions the tests happen to create is not tested for the application.**

### The fix

`@Transactional(readOnly = true)` on all eleven repository interfaces. Interface-level is the
correct level: Spring's attribute lookup prefers the target-class method, so `SimpleJpaRepository`'s
own read-write `@Transactional` on `save`/`delete` still wins and writes are unaffected — verified by
the full suite staying green.

`RepositoryTransactionTest` covers both halves:

1. **Reproduction** — a derived query with only `CompanyContext.open()` and no caller transaction
   must find the row; plus the fail-closed complement (no company → still nothing) so the fix cannot
   be "widen something".
2. **Enforcement** — every `JpaRepository` interface under `com.tcms` is derived from the live
   application context and asserted to carry the annotation, with a non-vacuity check on the count.
   A repository added a year from now fails the build until it complies. "Remember to annotate the
   next one" is not a mechanism (handoff §6 lesson 9).

---

## 4. What `provision-company` does

```bash
java -jar tcms.jar provision-company \
    --slug=acme --name="Acme Corporation" \
    --admin-email=ops@acme.test --admin-name="A. Kotrulev" \
    [--project-key=OLB --project-name="Acme Online Banking"]
```

One transaction, company context opened **around** it (the transaction manager reads the context in
`doBegin`, so opening it inside would leave the GUC unset and every company-owned insert would be
refused by the RLS `WITH CHECK`):

1. the `company` row;
2. `tcms_seed_company_defaults` — three classification vocabularies, twenty values, five key counters;
3. the first Company Admin: an `app_user` **or the existing account for that address**, a
   `company_membership` in `INVITED`, and a **COMPANY-scope** `role_assignment`;
4. optionally the first project plus `tcms_seed_project_defaults` — the Feature dimension, six
   execution statuses, a Default configuration;
5. an `instance_audit_event`.

### Decisions worth not re-litigating

| Decision | Reason |
|---|---|
| **The administrator is `INVITED`, never activated** | An operator who could activate a client's administrator on their behalf is an audit finding, not a convenience. |
| **Idempotent, and it reports per part** | Re-running is the supported way to top up a company after a release adds a vocabulary or a counter. "It succeeded" is not enough information: an operator who meant to create a new client and sees "existed" everywhere has typed a slug belonging to another one. |
| **It never *updates*** | A differing display name on a re-run is left alone rather than overwriting a rename the client asked for. |
| **An existing email is reused, not rejected** | Email is unique instance-wide; a consultant working with two of your clients has one account and two memberships, with a different per-membership display name in each (§6u). |
| **Both project arguments or neither** | Half a project means guessing the other half — a key derived from a name, or a name from a key — in a field that is immutable once cases carry it. |
| **No actor is invented for an unattended run** | A synthetic "system" user row is indistinguishable from a real operator in the trail. `actor_user_id` stays null. |
| **Not serialized against concurrent runs** | `uq_company_slug` resolves it. An advisory lock would convert a clear constraint violation into a silent second no-op. |
| **A new `provisioning` module** | It composes `tenancy` + `identity` + `project`. Inside any one of them it would create a sideways edge the module rules forbid; depending *toward* those three is what the rules explicitly permit. It is a leaf — nothing depends on it. |
| **Exit codes 0 / 2 / 1** | Success (including a top-up) / usage error / failed and rolled back. A deployment script can tell "you typed it wrong" from "it did not work". |

### Two Spring/Hibernate traps met on the way

1. **`WebApplicationType.NONE` for the CLI invocation.** With the web starter on the classpath the
   default is a servlet container, so a provisioning run would do its work correctly and then never
   exit — an operator sees a command that "hangs" after succeeding. Decided from the raw `main` args,
   because the application type is fixed before `ApplicationArguments` exists.
2. **`@JdbcTypeCode(JSON)`, not `columnDefinition = "jsonb"`.** This is the `citext` trap in a
   different shape, and the difference between the two fixes is the point. `columnDefinition` only
   satisfies `ddl-auto: validate`, which compares the SQL type *name* when the type codes differ; the
   parameter is still bound as `varchar`, and Postgres does **not** implicitly cast `varchar` to
   `jsonb` the way it does to `citext`. So the context starts, every read works, and the **first
   insert** fails with *"column is of type jsonb but expression is of type character varying"* — 14
   tests at once.

---

## 5. Gate evidence

`./gradlew build` clean — **115/115** (87 pre-existing + 18 provisioning + 7 CLI arguments + 3
repository-transaction, of which one is the enforcement sweep).

**Mutation-verified.** Eight mutations, one narrow edit each:

| Mutation | Tests failed |
|---|---|
| `V5` no longer seeds `key_sequence` (the defect restored) | 2 — the allocator test and the idempotence count |
| `ProjectRepository` loses `@Transactional` (defect 2 restored) | 2 — the reproduction **and** the enforcement sweep |
| Membership created `ACTIVE` instead of `INVITED` | 1 |
| Grant idempotency check always says "absent" | 2 — the re-run and its audit entry |
| `isRequestedBy` uses a prefix match | 1 — `provision-company-dry-run` would have provisioned |
| Half a project accepted | 1 |
| **`saveAndFlush` → `save` before the project seed call** | **0 — survived**, see below |
| Company context opened *inside* the transaction instead of around it (blunt) | 14 — proves only that they run |

**The survivor is the finding, and it is reported rather than re-rolled.** Dropping the explicit
flush before `tcms_seed_project_defaults` failed nothing: Hibernate's AUTO flush mode already flushes
the persistence context ahead of a native query. So the explicit flush is not what makes that call
work today — it is what stops it depending on a flush mode nobody set deliberately. Kept, with the
survival recorded in the class comment so it is not deleted later on the evidence that no test covers
it. Same disposition as §13's surviving `company_id` predicate.

**Run for real**, per this project's lesson 5. Against a live `postgres:17` (17.10), from the boot
jar, not from a test:

- first run → `created` for all five parts, ids printed, **exit 0**, process exits (no servlet
  container);
- second run → `existed` for all five, "Nothing was created", exit 0;
- `--slug=acme` alone → the named missing argument plus usage, **exit 2**;
- banner suppressed for the CLI, so the usage message is not six lines of ASCII art down.

---

## 6. Deliberately not done

- **No invitation token or email.** There is no invitation table in `V1`; the administrator is
  `INVITED` and the activation path is M0.5/M1 work. The CLI says so in its output rather than
  implying the person has been notified.
- **No `--actor` argument.** Attributing a CLI run to an Instance Admin needs an authenticated
  operator identity, which is the login path's job. The service already takes the actor; only the CLI
  passes null.
- **Suspension and reinstatement are not CLI commands yet.** `Company.suspend()` exists and
  `InstanceAuditEvent` is now the place their audit entries go.
- **`instance_setting` is untouched.** No instance-level setting has a provisioning-time value yet.
