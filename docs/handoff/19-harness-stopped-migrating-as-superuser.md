# §19. Backend — the test harness stopped migrating as a superuser (2026-07-31)

Run through the **`be-java`** skill (agent definition + `ep-systematic-debugging` +
`ep-test-driven-development`), **Mode A — fix**. Closes §18's next-step item 1, which was named there
as *"the highest-value backend work available"*. **Test-harness only: no production code changed.**

### The gap, and why it was the top of the list

`IntegrationTestBase` migrated as the Testcontainers container user — **a superuser** — and a superuser
bypasses RLS unconditionally, `FORCE` included. So every one of the 56 policies was *mandatory in
production and advisory under test*, and **the suite could not assert anything that depends on the owner
not being a superuser.** That is not a hypothetical: it is exactly how V7's `SECURITY DEFINER` company
lookup reached production returning **zero rows** while 171 tests stayed green (§18 defect 1). It took
`docker compose up` to find, and nothing in the suite would have found it later either.

The cause was **ordering**, not intent: `tcms_app` was created by a Flyway `afterMigrate` callback — i.e.
*after* the tables existed — so whatever ran the migration had to already own the schema, and the only
role available at that point was the container's superuser.

### Reproduced first, then fixed

Per Mode A, the failing assertions were written **against the old harness** and watched fail for the
right reasons before anything was changed. Three of `PrivilegeSplitTest`'s seven went red: the owner's
`rolsuper`, "FORCE covers the owner" (the owner saw the row it must not), and "the owner is *policed*,
not denied". Those three are the V7 defect's shape, expressed as assertions.

### What changed

| Path | Change |
|---|---|
| `src/test/resources/db/testinit/tcms-roles.sql` | **New.** The suite's mirror of `deploy/postgres/initdb/10-tcms-roles.sql`, run as a Testcontainers **init script — before Flyway**, which is the deployment's own ordering. Creates non-superuser `tcms_owner` + `tcms_app`, transfers database ownership, and grants the app role through `ALTER DEFAULT PRIVILEGES`. |
| ~~`src/test/resources/db/testcallback/afterMigrate__create_app_role.sql`~~ | **Deleted.** Its ordering was the cause. Its `GRANT … ON ALL TABLES` would also have masked a broken `ALTER DEFAULT PRIVILEGES` in the deployment script indefinitely — the harness now exercises the deployment's *mechanism*, not an equivalent-looking substitute. |
| `IntegrationTestBase.java` | Container user renamed `tcms_super`; Flyway connects as `tcms_owner`; `spring.flyway.locations` back to the production default. `ownerConnection()` **split into two deliberately un-confusable methods**: `superuserConnection()` (ground truth, the only RLS-exempt connection in the suite) and `migrationOwnerConnection()` (owns every table, **still policed**). |
| `PrivilegeSplitTest.java` | **New, 7 assertions.** Role attributes for both roles · FORCE covers the owner · the owner is filtered rather than denied · every migration's `installed_by` is `tcms_owner` · the owner owns every relation and the app role owns none · the app role cannot `DISABLE ROW LEVEL SECURITY`, `DROP POLICY` or `TRUNCATE`. |
| `RlsIsolationTest.java` | Its role-attribute test removed — `PrivilegeSplitTest` owns that claim now — leaving a pointer comment where it was. |
| 4 test files | `ownerConnection()` → `superuserConnection()` at the call sites; `SchemaConventionTest`'s cached field renamed `catalogConnection`, since what it does is read the catalogue. |
| `deploy/postgres/initdb/10-tcms-roles.sql` | Header corrected: it pointed at the deleted callback, and **the direction of the mirroring has reversed** — the suite now follows this file. |

**Naming was the safety mechanism.** `ownerConnection()` was not repurposed to mean the new,
policed owner: a call site that still wanted the old semantics would have compiled and silently changed
meaning. Deleting the name forces every one of them to be looked at.

### Gate evidence

`./gradlew build` clean — **177 tests** (171 − 1 relocated duplicate + 7 new). The three red assertions
are green **for the right reason**: the owner is `rolsuper=f, rolbypassrls=f`, sees **0** of its own
tables' rows with no company context, and sees the company in full with one set. All three trusted
extensions (`ltree`, `pg_trgm`, `citext`) still install — they are *trusted*, which is the only reason a
non-superuser owner is viable, and the suite now proves that rather than the compose run alone.

### Two defects in the new test, both order-dependence, both worth recording

1. **The probe company reused `3333…`, which `PermissionEvaluatorTest` also seeds projects into.** Green
   alone, red in the full suite. Fixed with an id no other class uses — and, more importantly, by
   asserting against **ground truth** (`the owner sees what the superuser sees, for this company`)
   instead of the literal `1`. A hard-coded count couples a test to a fixture never growing.
2. **The seed wrote UUID literals while the assertions read the constant.** Changing the constant left
   the two pointing at different companies, and the symptom — a failed *precondition* — reads exactly
   like a broken privilege split. The seed binds from the constants now. §6 lesson 9's family: if two
   places must agree, make one derive from the other rather than asserting they match.

### Three things worth not re-learning

1. **A test harness weaker than the deployment is a blind spot with a green light on it.** The gap was
   documented in `IntegrationTestBase`'s own comment from M0 and cost a HIGH defect anyway. Documenting
   an untested property is not testing it — the same shape as §17's *"a comment saying the deployment
   must do X is not a deployment doing X"*, one level up.
2. **The harness should reuse the deployment's mechanism, not an equivalent-looking one.** `GRANT ON ALL
   TABLES` after migrating and `ALTER DEFAULT PRIVILEGES` before it produce the same privileges today and
   fail differently forever after. The suite now runs the same ordering and the same statements the
   deployment does, so `deploy/postgres/initdb/` is covered by CI for the first time.
3. **When a rename changes a method's meaning, delete the old name.** Both connections are `Connection`
   and both work; only the compiler refusing the old name makes every call site get re-read.

### Deliberately not done

- **`compose.yaml` and the production path are untouched.** They already had the non-superuser owner
  (§17) — the suite was the thing that did not.
- **No mutation run over the new assertions.** They *were* the mutation: they went red against the old
  harness and green against the new one, which is the same evidence from the other direction.
- **Passwords are literals in `db/testinit/tcms-roles.sql`.** JDBC has no `\getenv`, the container lives
  for one test run on a random port, and the deployment's file — the one that must never carry a
  credential — still uses `\getenv`. Nothing asserts the literals match `IntegrationTestBase`'s
  constants; a mismatch fails every test on the first connection, loudly.
- **`RepositoryTransactionTest`'s and §13's surviving mutation lanes are unchanged.** An RLS-disabled
  lane is still open work (§18 next-step 3), and it is now *easier*: the roles exist before Flyway, so a
  variant harness can grant `BYPASSRLS` to a third role without touching migrations.

### Next on the backend

Unchanged from §18, minus the item this closes: **the frontend accept-invitation route and real login
wiring** is now the highest-value work — the SPA still runs entirely on MSW, so the two halves have
never met.
