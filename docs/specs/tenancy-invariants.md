# Tenancy invariants

`company_id UUID NOT NULL` on every company-owned table, including join tables and version tables
(where it is actually forgotten), leading every composite index and every previously-global
uniqueness constraint. Enforcement is mechanical, in three layers:

1. **`SchemaConventionTest`** — asserts the rule over every table not on the ERD §0.1 exception list,
   so a new table fails the build until it complies. Adding to the exception list requires a decision.
2. **`CompanyContext`** (`backend/.../tenancy/`) — a `ThreadLocal` exposed only as a **scope guard**
   (`try (var ignored = CompanyContext.open(id))`), never a setter, so a company cannot outlive its
   block. Deliberately **not** `InheritableThreadLocal`: an `@Async` handler or scheduled job must
   re-establish context from its event envelope's `companyId`. Populated from the authenticated
   session — **never** from a request parameter, header, or path segment.
3. **`CompanyAwareTransactionManager`** — hooks `doBegin`, so the Hibernate filter and the
   `app.current_company` GUC are set for *every* transaction, not just web requests. Postgres RLS
   with `FORCE ROW LEVEL SECURITY` is the backstop; the app connects as an unprivileged role
   (`tcms_app`) that owns nothing and holds no `BYPASSRLS`. **Neither role in the test harness is a
   superuser either**: `IntegrationTestBase` creates a non-superuser `tcms_owner` *before* Flyway via
   `src/test/resources/db/testinit/tcms-roles.sql`, mirroring the deployment's ordering, and
   `PrivilegeSplitTest` pins every property of the split. The container's superuser is used only for
   ground truth in assertions. A suite that migrated as a superuser proves nothing — it had one, and
   that is precisely how V7's zero-rows-in-production defect got past 171 green tests.
   **The split is a deployment concern, not a test fixture**: `deploy/postgres/initdb/10-tcms-roles.sql`
   creates both roles for compose and the Hetzner runbook does the same on the VM, while
   `application.yml` gives Flyway its own `DB_MIGRATION_USER` so migrations run as the owner and the
   pool never does. Collapsing them into one role is the tempting simplification and it hands the
   application `DROP POLICY` on all 56 tables.

Two RLS traps, both silent when wrong: the owner bypasses plain `ENABLE` (hence `FORCE`), and an
unset GUC must yield **zero** rows, not all rows — `company_id = NULL` is never true. **There are two
spellings of "unset" and only one is NULL:** a GUC that was never set reads NULL, but once
`set_config` has run on a session — including the transaction-local form the transaction manager
issues — its reset value is `''` for the rest of that session, so every *pooled* connection is in the
second state. `V4` wraps the read in `nullif(…, '')` for exactly this; without it a context-less query
on a warm connection raises `invalid input syntax for type uuid` instead of returning nothing.
`RlsIsolationTest` covers both spellings and `SchemaConventionTest` pins the predicate on all 56
policies; a green suite alone does not.

**A repository interface must carry `@Transactional(readOnly = true)`.** Isolation is established in
`doBegin`, so it needs a transaction — and Spring Data gives one only to the CRUD methods *inherited*
from `SimpleJpaRepository` (`findById`, `count`, `save`), never to the derived and `@Query` methods
*declared on the interface*. Without the annotation those run outside any transaction, so
`app.current_company` is never set and every RLS-protected read returns **zero rows** while
`CompanyContext` holds the right company. Fail-closed, and it silently emptied the permission
evaluator. `RepositoryTransactionTest` reproduces it and derives the repository list from the live
context, so a new repository that forgets fails the build.

Companies are created by exactly one path: `CompanyProvisioningService` (the `provisioning` module),
driven by `java -jar tcms.jar provision-company`. It is idempotent — re-running is the supported way
to top up a company after a release adds a vocabulary or a display-key counter — and it never
*updates*. Only it may call `tcms_seed_company_defaults` / `tcms_seed_project_defaults`.

Async work is the other half: `CompanyContextTaskDecorator` clears the context on every task so a
handler that forgets fails closed, and `CompanyScopedEvent` is the only thing a handler may
re-establish from. `AsyncCompanyContextTest` covers ingestion, reporting, scheduling and event
handlers.
