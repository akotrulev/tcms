# §16. Backend M0 — ArchUnit + Modulith verification (2026-07-30)

Run through the **`be-java`** skill (agent definition + `code-review`), Mode B — extend. Closes §15's
next-step list item 1. **No new product code** — this slice is entirely enforcement, and it is the
point at which "modules are Java packages" stops being a naming convention. Nothing about it was
enforced by the compiler: every class under `com.tcms` could see every other one.

### The four module declarations

`@ApplicationModule(allowedDependencies = …)` now sits on each `package-info.java`, matching what the
code already does:

| Module | Declares | Why |
|---|---|---|
| `tenancy` | `{}` — **nothing** | Everything depends on it. One edge out puts the isolation layer into a cycle, and "the boundary is established before anything reads a company-owned row" stops being guaranteeable. |
| `project` | `tenancy` | Company-owned, needs the boundary and nothing else. Must **not** reach into `identity` — the grant question is asked *of* the evaluator *about* a project, never the reverse. |
| `identity` | `tenancy`, `project` | The `project` edge is the §13 defect fix: the evaluator resolves the project through the company-filtered lookup before unioning any grant. |
| `provisioning` | `tenancy`, `identity`, `project` | It composes all three; that is why it is its own module (§15). |

**The default for `allowedDependencies` is *unrestricted*, not empty**, so leaving the annotation off
asserts nothing — and `verify()` reports no violation, because there is no constraint to violate. A
new module that simply forgot to declare would silently opt out of the whole rule set. Same failure
shape as a table quietly added without `company_id`, and it has its own test.

### What shipped

| Path | Purpose |
|---|---|
| `test/architecture/ModuleStructureTest.java` | Modulith: `verify()` (declared deps + cycles), the detected-module list, **every module declares** its dependencies, and **no declaration names a module outside `tenancy`/`identity`/`project`**. 4 tests. |
| `test/architecture/ArchitectureRulesTest.java` | The three rules Modulith cannot express (below), plus a fixture precondition on the class sets. 5 tests. |
| `test/architecture/ArchitectureRules.java` | `FOUNDATION_MODULES` and `moduleOf(JavaClass)`, shared so the two tests cannot drift. |
| four `package-info.java` | The declarations above, each with its reasoning. |

**Neither test extends `IntegrationTestBase`** — both are static bytecode analysis, so no Postgres and
no Spring context. A structural rule that only runs when Docker is up is one that gets skipped exactly
when someone is in a hurry.

### The three rules ArchUnit owns, and why Modulith cannot

1. **`tenancy` depends on nothing** — stated from the code side and derived (`com.tcms..` minus
   `com.tcms.tenancy..`), so a module added next year is covered without anyone remembering the file.
2. **`provisioning` is a leaf.** `allowedDependencies` constrains what a module reaches *out* to and
   has **no vocabulary for who reaches in**. The claim was already in its package comment; it is now a
   build failure. The composition root `com.tcms` is exempt, spelled as an exact package so the
   exemption covers `TcmsApplication` and nothing else.
3. **Every `@EventListener`/`@TransactionalEventListener` method takes one `CompanyScopedEvent`.**
   This is §14's whole design: the context is not inheritable, the decorator clears it, so the
   envelope is the handler's only trustworthy source.

### Two class sets, deliberately

Dependency rules import **production only** — a test may import anything, and running them over
`src/test` would report the isolation harness as an architecture violation. The event rule imports
**production *and* tests**, because the only event handler in the codebase today is
`AsyncCompanyContextTest`'s. Over production alone it would match nothing — and ArchUnit **fails a
rule that matches nothing**, which is the property that keeps it honest rather than a nuisance.

### Gate evidence

`./gradlew build` clean — **124/124 tests** (115 pre-existing + 9 new).

**Mutation-verified**, seven mutations, one narrow edit each. No survivors:

| Mutation | Tests failed |
|---|---|
| `tenancy` references `identity` | 2 — the tenancy rule + `verify()` |
| `tenancy` references a root-package class | 2 — the tenancy rule + `verify()`, but see below |
| `identity` references `provisioning` | 3 — leaf + cross-module + `verify()` |
| `implements CompanyScopedEvent` removed from an existing event | **compile failure** — see below |
| A new listener whose event forgets the envelope | 1 — the event rule, alone |
| `project` loses its `@ApplicationModule` | 1 — the declaration sweep, alone |
| `identity` *declares* `provisioning` (no code edge) | 1 — the declaration-contents rule, alone |

### Three things worth not re-learning

1. **A comment claiming a tool's blind spot is a claim, and it was wrong the first time.** The
   tenancy rule was written asserting Modulith "cannot see" a dependency on the composition root.
   Modulith **does** fail it today — as a *cycle* (`tenancy → root:com.tcms → provisioning →
   identity → tenancy`), which exists only because `TcmsApplication` wires the CLI. Remove that one
   import and Modulith goes quiet while the edge remains. The rule still earns its place; the comment
   was rewritten to say what was measured rather than what was assumed.
2. **A mutation can fail at the compiler instead of at the test, and that is a finding too.** Taking
   `implements CompanyScopedEvent` off an existing event does not reach the arch rule — it fails
   compilation at the `CompanyContext.openFor(event)` call. So the compiler already covers events that
   *are* opened. The gap the rule actually closes is **the handler that never calls `openFor`**: it
   compiles, runs, reads zero rows because the decorator cleared the context, and presents as an empty
   report rather than as a missing envelope.
3. **`git checkout --` on an uncommitted file is not a mutation revert, it is a delete.** Two
   mutations were reverted that way mid-run and silently took the session's own uncommitted
   `package-info` edits with them — which then contaminated the next mutation's result (an extra
   failing test that looked like a finding). Snapshot the file, or commit the slice before mutating
   it.

### Deliberately not done

- **No `ApplicationModules` documentation output** (`Documenter` — C4/PlantUML module canvases).
  Modulith can generate them; they belong with the first real cross-module event, when there is a
  graph worth drawing rather than a star around `tenancy`.
- **No rule pinning `@Transactional` on repository interfaces.** `RepositoryTransactionTest` (§15)
  already does it against the live context, which is stronger than a naming-based ArchUnit rule.
- **The publish side of the event contract is uncovered.** `ApplicationEventPublisher.publishEvent`
  takes `Object`, so bytecode carries no argument type and ArchUnit cannot check it. The *handler*
  side is checked, which is where the context is re-established and therefore where forgetting hurts.
