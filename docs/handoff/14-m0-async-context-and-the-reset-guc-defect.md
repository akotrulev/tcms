# §14. Backend M0 — the async-context harness, and the reset-GUC defect (2026-07-30)

Run through the **`be-java`** skill (agent definition + `pattern-writer` + `test-driven-development` +
`code-review`), Mode B — extend. Closes §11's next-step list item 3, the **last of M0's four isolation
deliverables**. Full evidence: **`docs/audits/2026-07-30-async-context-and-reset-guc-defect.md`**.

### The headline: writing the harness found a HIGH defect in V1's RLS policy

`V1`'s policy was `company_id = current_setting('app.current_company', true)::uuid`, with the `true`
there so an unset GUC yields NULL and a context-less query sees **zero** rows (trap 2). **That holds
only on a connection where the GUC was never set** — and production has a pool.

Postgres does not return a custom GUC to *undefined* when a transaction ends. Once assigned even once
— including via `set_config(…, is_local => true)`, exactly what `CompanyAwareTransactionManager`
issues per transaction — its reset value for the rest of the **session** is the **empty string**. So
every recycled connection evaluates `''::uuid` and raises:

```
ERROR:  invalid input syntax for type uuid: ""
```

**It fails closed, so it was never a leak.** It was a 500 on every legitimately context-less read
touching a company-owned table — the login path, the provisioning CLI, an operator query, a forgetful
report job — on any warm connection. Never on the first request; always afterwards.

It also means **the existing trap-2 test read stronger than it was**: it opened a fresh connection, so
it only ever covered the never-set case. `RlsIsolationTest` now has a second assertion for the
recycled case, reproducing what the pool actually does.

**Fixed in `V4__rls_policy_tolerates_reset_guc.sql`:** `nullif(current_setting(…, true), '')::uuid`,
collapsing both spellings of "absent" onto NULL. Still a bare equality — **no widening**, so trap 2 is
preserved rather than traded away. The migration rewrites from `pg_policies` (not a fourth copy of the
56-table list), refuses to touch a policy whose predicate is not `V1`'s, and asserts it rewrote exactly
56.

### What shipped

| Path | Purpose |
|---|---|
| `tenancy/CompanyScopedEvent.java` | The event-envelope contract: every cross-module event carries its `companyId`. **The only thing an async handler may re-establish context from.** A new event that forgets it does not compile. |
| `tenancy/CompanyContextTaskDecorator.java` | Makes "the handler forgot" fail **closed**. Every task runs with the context cleared, and the submitting thread's company restored afterwards (a `CallerRuns` rejection or an inline executor runs the task on the *caller's* thread — clearing without restoring would strip a live request's company mid-flight). |
| `tenancy/AsyncTenancyConfiguration.java` | `@EnableAsync` + `@EnableScheduling` + the decorator **as a single bean**. |
| `tenancy/CompanyContext.openFor(event)` | So the correct path is also the shortest one. |
| `db/migration/V4__rls_policy_tolerates_reset_guc.sql` | The defect fix, above. |
| `test/tenancy/AsyncCompanyContextTest.java` | The harness — the four named surfaces plus the mechanism. 8 tests. |
| `test/tenancy/PooledAsyncContextTest.java` | The same guard with `spring.threads.virtual.enabled=false`, where threads are pooled and it stops being insurance. 2 tests. |
| `test/tenancy/RlsIsolationTest` +1 | Trap 2 on a **recycled** connection. |
| `test/tenancy/SchemaConventionTest` +1 | **Every isolation policy carries the same predicate**, and none has an explicit `WITH CHECK`. |

Each of the four surfaces — ingestion, report generation, a scheduled purge, an `@Async`
`AFTER_COMMIT` event handler — runs **with a foreign company active on the submitting thread**, which
is the condition under which "inherited the caller's company" and "re-established its own" look
identical from outside. Two properties are pinned, and both are needed: handlers **re-establish** from
the envelope, and a handler that **forgets** reads zero rows rather than the submitter's. The first
without the second is a convention, and the convention is what gets forgotten.

### Four "safety" beans that did nothing

`AsyncTenancyConfiguration` first carried four `*Customizer` beans installing the decorator by hand on
each executor kind. **Mutating each to stop doing so changed no test result** — Boot 3.5 already
applies a single `TaskDecorator` *bean* to all four builders. Deleted, and the reason recorded in the
class comment so it is not "restored" as a fix later. Code that reads as a safety mechanism while
doing nothing is worse than no code.

### Gate evidence

`./gradlew build` clean — **87/87 tests** (75 pre-existing + 12 new).

**Mutation-verified**, eight mutations, one narrow edit each:

| Mutation | Tests failed |
|---|---|
| `V4` removed (the defect restored) | 2 — forgetful report + recycled-connection trap 2 |
| Decorator reduced to a pass-through | 2 — pooled residue + inline restore |
| One scheduler customizer stops setting the decorator | **0 — survived**, and that is the finding above |
| Transaction manager never establishes isolation (blunt) | 41 — proves only that they run |
| **Worker inherits the submitting thread's company** | 4 — and the forgetful-report test caught it reading **another company's rows** |
| Scheduled sweep leaks company N into N+1 | 1 |
| `@EnableAsync` removed | 3 |
| One policy widened to `USING (true)` | 1 — the new predicate assertion |

### Three things worth not re-learning

1. **A GUC's "unset" and a GUC's "reset" are different values, and only one of them is NULL.** The
   whole trap-2 design rested on `current_setting(…, true)` returning NULL, which it does — until the
   setting has been assigned once on that session, after which it returns `''` forever. Every
   connection in a pool is in the second state after its first company-scoped transaction. **A
   property tested only on a fresh connection is not tested for a pooled application.**
2. **The mutation that survives is the finding.** Three of eight mutations were expected to fail
   things and did; one survived, and it was the only one that told us something new — four beans of
   inert ceremony. Report the survivor rather than quietly re-rolling the mutation.
3. **Tests share a container, so they share a fixture.** This harness first ran against
   `PermissionEvaluatorTest`'s `3333…`/`4444…` company ids and silently saw its projects — an
   assertion failure that looked like a leak and was a collision. Give every test class its own
   company-id namespace, per the schema tests' mnemonic-prefix convention.

### Deliberately not done

- **`@Async` concurrency is unbounded.** `SimpleAsyncTaskExecutor` applies no limit and nothing
  backpressures async work. No production caller exists yet, and inventing a number now would be
  guessing.
- **Async handler failures have nowhere to go.** A `void` `@Async` listener swallows its exception.
  `V1` already carries an `outbox`; wiring it belongs to the module that publishes the first real event.
- **The four surfaces are test fixtures.** `execution`, `reporting` and `work` do not exist yet; what
  is pinned is the tenancy plumbing beneath them.

### Next on the backend, in order

1. ~~**`provision-company` CLI**~~ → **done 2026-07-30, see §15.** It found two more defects: a
   provisioned company could not allocate a display key, and every repository *query method* was
   running outside a transaction.
2. ArchUnit + Modulith verification — and note that `CompanyScopedEvent` is now the contract those
   rules should be written against.
3. Docker Compose, CI.
4. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation.
