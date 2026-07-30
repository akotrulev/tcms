# Async context harness — evidence, and the reset-GUC defect it found

**Date:** 2026-07-30
**Scope:** backend M0, the fourth isolation deliverable (plan §0.2, handoff §3 lesson 14)
**Outcome:** harness landed (11 new tests, 87/87 green), **one HIGH defect found and fixed in `V4`**

---

## 1. The defect: an RLS policy that raises instead of returning zero rows

### What was wrong

`V1`'s isolation policy, on all 56 tables:

```sql
company_id = current_setting('app.current_company', true)::uuid
```

The `true` is there so a *missing* setting yields `NULL`, and `company_id = NULL` is never
true — the trap-2 property: **no company context means zero rows, not every row.**

That reasoning holds only for a connection where the GUC has **never** been set. It does
not survive a connection pool.

### Root cause, measured on Postgres 17.10

Postgres does not return a custom GUC to *undefined* when a transaction ends. Once the
setting has been assigned even once — including via `set_config(…, is_local => true)`,
which is exactly what `CompanyAwareTransactionManager` issues per transaction — its
**reset value for the remainder of the session is the empty string**.

```
--- never set in this session ---
 is_null: t

--- inside a tx, set LOCAL, then COMMIT ---
--- what a recycled connection then sees ---
 is_null: f    value: ''

--- and the policy expression on that value ---
ERROR:  invalid input syntax for type uuid: ""
```

So on any Hikari connection that has already served one company, a subsequent
**context-less** query against a company-owned table raises instead of returning nothing.

### Severity, stated honestly

**It fails closed, so it was never a cross-company leak.** What it was:

- a 500 on every legitimately context-less read touching a company-owned table — the login
  path, the provisioning CLI, an instance-level operator query, a report job that forgets
  its context;
- never on the first request on a fresh connection, always afterwards. Which is the worst
  shape for something to be found in staging;
- and a **false reading of the existing trap-2 test**, which opened a fresh connection and
  therefore only ever exercised the never-set case.

### The fix — `V4__rls_policy_tolerates_reset_guc.sql`

```sql
company_id = nullif(current_setting('app.current_company', true), '')::uuid
```

`nullif` collapses both spellings of "absent" — never set, and reset to `''` — onto the
`NULL` the trap-2 reasoning already depends on. The predicate is otherwise unchanged:
still a bare equality, no widening, so **trap 2 is preserved rather than traded away**
(widening was measured and rejected in handoff §11 for exactly that reason).

The migration rewrites the policies **from `pg_policies`**, not from a fourth
hand-maintained copy of the 56-table list, refuses to run against any policy whose
predicate is not the one `V1` created, and asserts it rewrote exactly 56 — a loop that
silently rewrote nothing would have reported success while leaving the defect in place.

### How it was found

By the new async harness, and specifically by the *forgetful* report-generation surface —
a handler that deliberately opens no context, asserting it reads zero rows. It was the
first test in the suite to issue a context-less query on a **warm** connection. Nothing in
the existing 75 tests did.

---

## 2. A second finding: four inert "safety" beans

`AsyncTenancyConfiguration` was first written with four `*Customizer` beans installing the
task decorator by hand on each executor kind Boot might build.

**Mutating each of them to stop installing the decorator changed no test result.** Spring
Boot 3.5 already applies a single `TaskDecorator` **bean** to all four builders. The
customizers were ceremony that read as a safety mechanism while doing nothing — worse than
absent, because the next reader would have trusted them.

Deleted; the fact is recorded in the class comment so it is not "restored" as a fix later.
The behaviour it was meant to guarantee is now asserted directly, in both configurations
(`AsyncCompanyContextTest` on virtual threads, `PooledAsyncContextTest` with
`spring.threads.virtual.enabled=false`).

---

## 3. What the harness covers

| Surface | Claim pinned |
|---|---|
| **Ingestion** | Re-establishes from the event envelope; the worker inherits nothing |
| **Report generation** | A handler that *forgets* reads **zero** rows, never the submitter's — plus a correct variant that is scoped to its argument and leaves the caller's context intact |
| **Scheduled purge** | A sweep across companies does not carry iteration N's company into N+1 |
| **Event handler** | `@Async @TransactionalEventListener(AFTER_COMMIT)` re-establishes from the envelope, after the publishing transaction (and its GUC) is gone |
| **Mechanism** | A pooled thread carries no residue from a leaking task; an inline task restores the caller's company; the decorator is installed on both executor kinds |
| **Schema** | Every isolation policy carries the *same* predicate and no explicit `WITH CHECK` |

Each of the four surfaces runs **with a foreign company active on the submitting thread** —
the condition under which "it inherited the caller's company" and "it re-established its
own" are indistinguishable from the outside.

---

## 4. Mutation evidence

Per this project's standing rule that a green suite proves the assertions *run*, not that
they *discriminate*. Eight mutations, one narrow edit each.

| # | Mutation | Tests failed |
|---|---|---|
| 1 | `V4` removed (the defect restored) | **2** — forgetful report + the recycled-connection trap-2 test |
| 2 | Task decorator reduced to a pass-through | 2 — pooled residue + inline restore |
| 3 | `SimpleAsyncTaskSchedulerCustomizer` stops setting the decorator | **0 — survived** → finding §2 |
| 4 | Transaction manager never establishes isolation (blunt) | 41, incl. all 8 async tests — proves only that they run |
| 5 | **Worker inherits the submitting thread's company** (the real leak) | 4 — ingestion, forgetful report, event handler, inline restore |
| 6 | Scheduled sweep leaks company N into N+1 | 1 — the sweep test |
| 7 | `@EnableAsync` removed (handlers run on the caller's thread) | 3 |
| 8 | One policy widened to `USING (true)` | 1 — the new predicate assertion |

Mutation **5** is the one that matters: it simulates the exact failure the
non-inheritable `ThreadLocal` exists to prevent, and the forgetful-report test caught it
reading **another company's rows**. Mutation 4 is the documented contrast — a blunt
mutation fails everything and proves nothing about discrimination.

Mutation **3 survived and is reported rather than hidden**; it is what surfaced §2.

---

## 5. Known limits of this harness

- **On this deployment's own settings the decorator guards nothing.**
  `spring.threads.virtual.enabled=true` means every task gets a fresh virtual thread, and a
  thread that is never reused cannot carry residue. The guard matters when that property is
  flipped — one line of YAML — which is why `PooledAsyncContextTest` boots that
  configuration explicitly.
- **The four surfaces are test fixtures, not production code.** The modules that will own
  them (`execution`, `reporting`, `work`) do not exist yet. What is pinned is the tenancy
  plumbing underneath them, which does.
- **`@Async` concurrency is unbounded.** `SimpleAsyncTaskExecutor` has no default
  concurrency limit, so nothing applies backpressure to async work. Harmless today (no
  production caller), and deliberately not "fixed" with an invented number.
- **Async handler failures have no destination.** A `void` `@Async` listener swallows its
  exception. `V1` already carries an `outbox` table; wiring it belongs to the module that
  publishes the first real event.
- **Cross-class fixture coupling is real.** This test first ran against
  `PermissionEvaluatorTest`'s company ids and silently saw its projects, because the
  container is shared across the whole run. Every class needs its own company-id namespace;
  the schema tests' mnemonic-prefix convention is the one to follow.
