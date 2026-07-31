# §11. Backend M0 — first build (2026-07-30)

Docker became available, so the session ran the plan's own Immediate Next Step #3. Run through the **`be-java`** skill (agent definition + `pattern-writer` + `database-code`).

### The headline: V1 works, and running it found two defects

`V1__initial_schema.sql` had been statically validated but **never executed**. It runs clean on Postgres 17.10 — 63 tables, 56 RLS policies, all `FORCE`, and `ltree`/`pg_trgm`/`citext` all present in the stock image (the largest unquantified risk, since some managed hosts gate extensions). Full evidence in **`docs/audits/2026-07-30-v1-schema-first-execution.md`**.

**Trap 2 passes.** Verified as a non-owner role: with no `app.current_company` set, a query returns **zero** rows while the superuser sees the rows exist. The highest-severity silent-inversion risk in the schema is now closed empirically rather than by reasoning.

| # | Finding | Fix |
|---|---|---|
| 1 (HIGH) | **SYSTEM-scope grants were unreachable.** `role_assignment.company_id` was nullable to mean SYSTEM scope, but the policy is a bare equality and `NULL = anything` is never true — so an Instance Admin grant was invisible in *every* company context, permanently. It presents as an authorization denial, not a schema fault, and the tempting repair is `BYPASSRLS` on the app role, which destroys isolation for all 56 tables. Widening the policy is **also** wrong: measured, it regresses trap 2. | SYSTEM grants moved to **`instance_role_assignment`** (instance-level, no RLS, **user-only** — a team is company-owned, so a SYSTEM grant to a team lets a client-controlled entity confer operator rights). `role_assignment` is now `company_id NOT NULL`, removing the last nullable discriminator under RLS. |
| 2 (LOW) | **`role_permission` was missing from the ERD §0.1 exception list** while correctly having no `company_id` — so the schema test as specified would have failed the build on its first run, and the tempting repair is a meaningless `company_id`. | Corrected the list, not the DDL. Added `instance_role_assignment` too. |

Schema is now **64 tables, 56 policies, zero nullable `company_id` under RLS**.

### What was built

Gradle lives in **`backend/`, not the repo root** — the repo is polyglot, so each stack owns its directory and `cd backend && ./gradlew build` mirrors `cd frontend && npm run build`. Spring Modulith modules are **packages**, not Gradle subprojects, so no subproject split is needed; `settings.gradle.kts` records why and how to add one if that changes.

| Path | Purpose |
|---|---|
| `backend/settings.gradle.kts` · `build.gradle.kts` · `gradle/libs.versions.toml` | Spring Boot 3.5.6, Java 21 toolchain, version catalog, `-Werror`. |
| `tenancy/CompanyContext.java` | **A scope guard, not a setter** — `open()` returns an `AutoCloseable` that restores the previous value, so a company cannot outlive its block. Deliberately **not** `InheritableThreadLocal`: an async handler must re-establish from the event envelope, because inheriting silently makes a leak look correct under test. |
| `tenancy/CompanyAwareTransactionManager.java` | Hooks `doBegin`, so the Hibernate filter **and** the `app.current_company` GUC are established for **every** transaction — not just web requests. Scheduled jobs, `@Async` handlers, ingestion and the provisioning CLI are exactly where context loss is invisible. Uses `set_config(…, true)` rather than literal `SET LOCAL`, because the latter takes no bind parameters. |
| `tenancy/{Company,CompanyStatus,CompanyRepository,CompanyFilter}.java` · `package-info.java` | The `@FilterDef` and the company aggregate. |
| `project/{Project,ProjectRepository}.java` | The M0 vertical slice — a company-owned entity that proves the plumbing. |
| `IntegrationTestBase.java` | One container per run + **the privilege split**: Flyway migrates as the owner, the app connects as `tcms_app`. **The owner became a non-superuser in §19** — until then it was the container's superuser, which is what let V7's defect through. |
| `SchemaConventionTest.java` | **The ERD §0.1 enforcement mechanism.** 5 assertions. |
| `RlsIsolationTest.java` | **The two-company harness.** 8 assertions → 7 in §19, when the role-attribute assertion moved to its own home. |
| ~~`src/test/resources/db/testcallback/afterMigrate__create_app_role.sql`~~ | ~~Creates the unprivileged role.~~ → **superseded and deleted in §19.** Creating the role *after* migrating is what forced the owner to be a superuser. Replaced by `src/test/resources/db/testinit/tcms-roles.sql`, run **before** Flyway, mirroring the deployment's ordering. |

### Two things worth not re-learning

1. **A green isolation suite proves nothing until you break it on purpose.** The suite was mutation-tested: simulating **context bleed** (the transaction manager receiving a stale company) failed exactly the three isolation-dependent tests while the four that shouldn't depend on it kept passing. A blunter mutation — disabling isolation entirely — failed *all* of them, including two pure-unit tests, because `@BeforeEach` seeding broke. The targeted mutation is the one that proves the assertions *discriminate*; the blunt one only proves they run.
2. **That mutation surfaced an untested load-bearing property.** The policies declare `USING` with **no explicit `WITH CHECK`**, so Postgres reuses `USING` as the insert check — the read boundary is also the write boundary. Good behaviour, but implicit in the DDL. Now pinned by its own test, so a later "fix" adding `WITH CHECK (true)` fails instead of silently letting a company plant rows in another's tenancy.

Also: **Docker Engine 29 rejects the API version Testcontainers negotiates**, and reports it as `Could not find a valid Docker environment` — which reads as "no daemon" rather than "version floor". Pinned via `systemProperty("api.version", …)` in the test task (the `DOCKER_API_VERSION` env var is *not* the key docker-java reads).

### Next on the backend, in order

1. ~~`V2__triggers_and_seed.sql`~~ and ~~the migration chain~~ → **done 2026-07-30, see §12.** Shipped as **two** migrations (V2 DDL + V3 seed).
2. ~~**The remaining harness personas** — the no-project-grant member and the cross-company user.~~ → **done 2026-07-30, see §13.** Both landed with the evaluator; the no-project-grant persona found nothing, but building the evaluator behind it found two HIGH defects.
3. ~~**The async-context test**~~ → **done 2026-07-30, see §14.** It found a HIGH defect in V1's RLS policy (the reset-GUC case), fixed in `V4`.
4. **`provision-company` CLI** — now a thinner job than it was: the vocabularies exist as `tcms_seed_company_defaults` / `tcms_seed_project_defaults`, so the CLI creates the company row, opens the context, calls both, creates the first Company Admin as an invitation, and writes an instance audit event. **Nothing else may call those two functions.**
5. Docker Compose, CI, ArchUnit + Modulith verification, ~~the `identity` module~~ (**done, §13**).

### How to run it

```bash
cd backend
./gradlew build      # THE gate: compile (-Werror) + all tests against Testcontainers
./gradlew test       # 87 tests, needs Docker running
```

Docker must be running — every test boots a real Postgres 17. There is no H2 fallback and there should not be: RLS, `ltree` and `citext` have no H2 equivalent, so an in-memory lane would test a schema the product never runs on.
