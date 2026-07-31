# §12. Backend M0 — V2 and V3 (2026-07-30)

Run through the **`data:data-migrations`** and **`data:data-postgresql`** skills. Continues §11's own next-step list, items 1 and 2.

### The migration was split in two, against what the ERD named

The ERD and the roles spec both called for a single `V2__triggers_and_seed.sql`. It shipped as:

| File | Kind | Contents |
|---|---|---|
| `V2__search_vectors_triggers_and_seed_functions.sql` | **DDL** | `search_vector` mechanisms · SINGLE-cardinality classification trigger · team cycle + company-consistency trigger · `tcms_seed_company_defaults` / `tcms_seed_project_defaults` |
| `V3__seed_permissions_and_roles.sql` | **DML** | 55 permissions · the six built-in roles · 183 `role_permission` grants |

The `migrations` skill's rule is one logical change per file with schema and data separate, and it earns its keep immediately here: a grant correction is a two-line corrective migration that does not re-run a trigger definition, and the seed can be re-run by hand during a recovery without touching DDL. Both files are idempotent (`ON CONFLICT DO NOTHING`, `WHERE NOT EXISTS`), which is what makes that safe.

### The `IMMUTABLE` question — measured, and the answer had a third part

ERD §10 point 3 flagged this as the next thing static analysis could not settle. On Postgres 17.10:

| Function | Volatility | Usable in a generation expression |
|---|---|---|
| `to_tsvector(regconfig, text)` | `IMMUTABLE` | yes |
| `to_tsvector(text)` | `STABLE` | **no** — reads `default_text_search_config` |
| `jsonb_to_tsvector(regconfig, jsonb, jsonb)` | `IMMUTABLE` | yes |
| `setweight(tsvector, "char")` | `IMMUTABLE` | yes |
| **`concat_ws(text, VARIADIC "any")`** | **`STABLE`** | **no** |

The regconfig-pinning part was as predicted. `concat_ws` was not, and it is the one that actually bit: it reads as a pure string function, and it is `STABLE` only because its `VARIADIC "any"` arguments pass through type output functions. `||` over `coalesce()` is immutable and is what the multi-field expression uses. Postgres refuses the whole `ALTER TABLE` with "generation expression is not immutable", so this fails loudly — but only once you try it.

### Generated columns for three tables, triggers for one — and why that asymmetry is the design

`test_case`, `condition` and `shared_step` use `GENERATED … STORED`. **A generated column cannot be forgotten**: there is no INSERT path that skips it, no trigger to drop, no window where column and row disagree. `doc_page` cannot use one — a page's text is in `doc_block` rows and a generation expression may only read its own row — so it is trigger-maintained from both sides, and every failure mode that opens has its own assertion: a `DELETE` that leaves deleted text matching, a title edit that wipes the body, a recursion between the two triggers.

Converting the V1 columns needed `DROP COLUMN` + re-add (`ALTER COLUMN … SET GENERATED ALWAYS AS` is a syntax error), and **dropping the column silently drops its GIN index** — verified, and both indexes are recreated in V2. Safe only because V1 has never been deployed outside CI.

### Company-owned seed data cannot live in a migration, and that shaped the file

The handoff's §11 list included "per-company classification dimensions, default case statuses, default configuration per project" in the seed. They are not there, for two independent reasons either of which is sufficient:

1. **There are no companies at migration time.** Each client gets its own *copy* of the vocabulary so one client renaming "Compatibility" doesn't rename it for another (§6d, §6s).
2. **Every table involved has `FORCE ROW LEVEL SECURITY`, which covers the migration role too.** An INSERT would need `app.current_company` set to a placeholder company — encoding a fixture as schema.

So the vocabulary content is still *versioned in a migration*, as two functions that `provision-company` calls per company and per project. Both **refuse to run** unless the active company matches the target, rather than writing rows the policy then hides. The Test Type / Test Level / Phase values, the Feature dimension, the six execution statuses and the Default configuration all live there — and the status colours match the frontend design tokens deliberately, since status colour is load-bearing data and is not brandable (§6u).

### The trigger that guards more than it was asked to

`case_classification.dimension_id` is a *denormalized copy* of `classification_value.dimension_id`, present so the SINGLE-cardinality rule can be enforced at all (a partial unique index cannot reach another table). Writing the trigger surfaced the hole the denormalization itself opens: **a caller that writes a MULTI dimension's id next to a SINGLE dimension's value bypasses the cardinality check entirely** *and* leaves a row lying about which axis it classifies, so every facet count computed from `dimension_id` goes wrong. The copy is now verified rather than trusted, and the mutation run below shows the cascade: disabling that one check broke the MULTI test too, because a mislabelled row shows up under the wrong dimension.

The trigger also takes a `FOR NO KEY UPDATE` lock on the parent case. There is no row to lock for a value that does not exist yet, so two concurrent inserts would each see zero existing rows and both commit.

### Gate evidence

`./gradlew build` clean — **45/45 tests** (13 pre-existing + 32 new across four classes: `SeedDataTest`, `SearchVectorTest`, `IntegrityTriggerTest`, `CompanyVocabularySeedTest`).

**Mutation-verified**, per this project's own lesson that a green suite proves the assertions *run*, not that they *discriminate*. Seven targeted mutations, each failing only what it should:

| Mutation | Tests failed |
|---|---|
| `doc_block` trigger drops `DELETE` | 1 — the both-sides doc_page test |
| `test_case` title weighted `B` instead of `A` | 2 — both generated-vector tests |
| `jsonb_to_tsvector` filter `["string"]` → `["all"]` | 1 — the JSON-keys test |
| SINGLE-cardinality check never fires | 1 |
| Denormalized `dimension_id` agreement check removed | 2 — including the MULTI test, via the cascade above |
| Team cycle walk removed | 1 |
| Seed functions no longer require company context | 1 |

An earlier, **blunter** mutation (a malformed generation expression, so V2 failed outright) took down 20 tests including all the seed ones — which proves only that they run. That contrast is the same lesson §11 recorded for the isolation harness, and it is why the seven above were made one narrow edit at a time.

**Also verified:** the seed matches `openapi.yaml`'s `PermissionKey` in both directions and matches the frontend fixture's grants role-for-role (48 Company Admin / 40 Test Lead / 27 Test Engineer / 8 Viewer / 5 Automation Service, Instance Admin derived). `SeedDataTest` parses `docs/specs/openapi.yaml` directly, so contract drift is a **backend** build failure too, not only a frontend type error.

### Two things worth not re-learning

1. **`@BeforeAll` is too early for anything needing the database.** All four new classes failed on `FATAL: password authentication failed for user "tcms_app"`, which reads as a credentials problem. It is an ordering one: the Spring context — and so Flyway, and so the `tcms_app` role its test-only callback creates — is built when the test *instance* is post-processed, after a static `@BeforeAll` has already run. `SchemaConventionTest` never noticed because it connects as the owner, which always exists. Use `@BeforeEach` with a null guard.
2. **A five-minute throwaway container beats a five-minute Gradle cycle.** Both migrations were dry-run against a scratch `postgres:17` via `psql` before a line of Java existed, which is where the `concat_ws` failure and the drop-column-drops-the-index behaviour were found. The suite then encoded what the probe had already established, instead of being the thing that discovered it.

### Still not started on the backend

~~Unchanged from §11's list, minus its first two items: the remaining harness personas, the async-context test, `provision-company`, Docker Compose, CI, ArchUnit/Modulith verification, and the `identity` module. The permission **evaluator** is M0.5 and is now unblocked.~~ → **superseded by §13**, which landed the `identity` module, the evaluator and both remaining personas. What is left is in §13.
