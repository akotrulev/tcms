# §50. The parameter checks at both ends — the rung §47 and §49 each named on the way out

**Date:** 2026-08-01 · **Branch:** `master` · **Sequential**, the fifth slice since §45.

Two computed checks and eight contract repairs. No runtime code changed — not one file under
`src/main/java` or outside `src/api/` in the SPA, which is worth stating up front because it means
the three defects below were all *latent*: they were live in the contract and the generated types,
waiting for the consumer that would read them.

## Why this slice

§47 shipped the defect, §49 shipped it again, and both sections ended by naming the same missing
check:

> "The next contract-hygiene rung is a check comparing *every* parameter the SPA sends against
> the contract's declared set; it would have caught both." — HANDOFF, and §49's *Not done*

That is now built, and building it found the half neither section had thought of. The two sections
described one direction — a key the SPA sends that the contract does not declare. **The mechanism
they both identified is symmetric**, and the other direction is worse:

| | The SPA sends | The contract declares | The handler binds | Result |
|---|---|---|---|---|
| §47, §49 | ✔ | ✘ | ✘ | The server ignores it. Wrong answer, 200. |
| **New here** | — | ✔ | ✘ | The server ignores it. Wrong answer, 200, **and the spec said it would work.** |

The second row is the more dangerous one because every artifact in the chain agrees. The parameter
has been written into the reviewed contract, generated into `schema.d.ts`, and offered to the SPA's
author by autocomplete. Nothing in that path touches a controller.

## The three defects

**1. `GET /cases/{caseId}/history` declared `releaseVersion` and `configurationId`, and
`CaseHistoryController` binds neither.** §49's own endpoint, from the slice that named this check.
Both had been carried over from plan §6p's description of the screen and never implemented; the
tell, visible in hindsight, is that they are the only two parameters on that operation with **no
description** — every other one on it carries a paragraph.

**2. `POST /projects/{projectId}/cases` declared the case list's entire fourteen-parameter filter
block**, and **3. `POST /projects/{projectId}/runs` declared `planId` and `releaseVersion`** — in
both cases because the block was written on the **path item**, which applies to every operation
under it. `schema.d.ts` said `?page=2&ownerId=…` was a legitimate query on a create. The generated
diff is the clearest statement of the repair: both POSTs went from a full query type to
`query?: never`.

## Two decisions, both of which could have gone the other way

**The path-item rule is strict: no query parameters there at all, ever.** The check reported eight
path items and only two were actually leaking, because the other six happen to have a single `GET`
underneath. Repairing all eight rather than writing the narrow rule ("a path item may declare a
query parameter that every operation under it binds") is deliberate. The six are correct **by
accident of arity**, and the accident is invisible at the place the parameter is written: the day
someone adds a `POST /conditions`, `q` and `type` silently appear on it, in a diff that does not
touch the parameter block. The narrow rule would report that as a defect in the new operation. The
strict one removes the trap instead of arranging to be told about it later, and it costs one level
of indentation.

**The two unbound history parameters were deleted, not implemented.** Both readings are defensible
— §6p does describe narrowing that timeline by release and by configuration, and the service
already groups by `(version, configuration)` for the flakiness figure, so the data is a `WHERE`
away. Deleted anyway, for the reason §28, §35 and §49 have each paid for: **nothing sends them.**
Building the filter would add a second unconsumed surface to the endpoint that already taught this
lesson. An unimplemented filter in the contract is strictly worse than an absent one — an absent
one is discoverable, and an unimplemented one answers 200. Re-adding is contract-first, and the
reasoning is written into the operation's own description so the next reader does not think it was
overlooked.

## The SPA-side check, and why it drives the compiler

`frontend/src/api/contractParameters.test.ts` resolves every `qs({…})` call in `src/api/queries/`
against `openapi.yaml`. It uses the TypeScript compiler API rather than reading the files as text,
and the case list is the reason:

```ts
api.get(`${base(projectId)}/cases${qs({ ...filters, sort })}`)
```

**The fourteen keys that spread contributes are a type, not text.** `CaseFilters` gaining a field
is precisely how the next occurrence of this defect arrives — a filter added to the interface and
to the grid, and nowhere else — and no amount of regex over that line can see it. The checker can:
mutating `CaseFilters` to add a `tagId` fails the check by name, which is the mutation that matters.

The same argument decides the URL side. `base(projectId)` is expanded by **reading `base`'s own
declaration** through the AST rather than by mapping it to the string `/projects/{}` here; a literal
would be a second definition of the one path helper every module builds URLs from, agreeing with
the real one right up until somebody changed it.

**Why static, not a runtime assertion in `qs()`.** A dev-mode check inside `qs` only sees the call
sites something executes, and both §47's and §49's defects were on paths that *were* executed — by
a human, who read the confident wrong answer and believed it. A dropped parameter has no runtime
signature, so it has to be findable without running anything.

## The vacuity guard earned its place within the hour

Every assertion in that file iterates something derived from the compiler, so all three ways the
machinery can break — the program not loading, `qs` being renamed, the checker declining to resolve
a spread — end in an empty iteration and a **green** test. The guard asserts a floor on call sites
found, that no spread resolved to zero properties, and that the case list contributed at least ten
keys.

It fired on the first run. `base` is *imported*, so the symbol at the call site is the import alias
whose only declaration is an `ImportSpecifier` — not the arrow function — and without
`checker.getAliasedSymbol` every URL came out as `{}/cases` instead of `/projects/{}/cases`. Under
the union-of-declared-parameters comparison alone that would have been ten silent skips reported as
a pass. **A check whose failure mode is silence is the defect it was written about.**

## Where each check lives, and the tsconfig seam it needed

The two halves cannot live together: the SPA's keys are in TypeScript source and the bindings are in
a running Spring context.

- **`ContractConventionTest`** gains the path-item rule — still no Spring context, still
  milliseconds, per that class's own header.
- **`ContractParameterBindingTest`** is new and boots the real context, reading
  `RequestMappingHandlerMapping` for the same reason `EndpointAccessTest`,
  `RepositoryTransactionTest` and `CompanyFilterTest` derive their subjects from the live context:
  the mapping is what the server actually does. It therefore judges only **implemented** operations
  — an endpoint still on MSW has no handler to compare against and is skipped, because a check that
  turned "not built yet" into a build failure would get an ignore list, and the real finding would
  arrive inside it.
- `contractParameters.test.ts` sits in `src/` with every other test but is **excluded from
  `tsconfig.app.json` and compiled by `tsconfig.node.json`** instead. It reads the filesystem, so
  typechecking it in the app project would mean putting `types: ["node"]` — `process`, `Buffer`,
  `__dirname` — in scope for the whole SPA. That is the objection `vitest.config.ts` already
  records against `globals: true`, and `tsconfig.node.json` is the seam the project had already
  built for "node-side, not application source". `npm run build` still covers the file.

**`@vitest-environment node` was tried and reverted**: the test needs no DOM, but
`src/test/setup.ts` does, and it is shared by all seventeen files. Not worth changing the setup for
one file's half-second.

## Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL in 3m
           900 tests, 0 failures, 0 skipped     (898 + 2)
frontend:  npm run gen:api → regenerated; both POSTs now `query?: never`
           npm run build   → ✓ built in 304ms
           npm run lint    → oxlint, exit 0, no output
           npm run test:run→ 17 files, 272 tests passed   (261 + 11)
browser:   not run, and not applicable — zero runtime files changed. The SPA sends exactly the
           request bytes it sent before this slice; what changed is that the contract now agrees
           with them.
```

**Three mutations, each killed:**

- Dropping `@RequestParam` from `caseVersionNo` in `CaseHistoryController` → the binding test names
  the operation and the parameter.
- Adding `configurationId` to `useCaseHistory`'s `qs({ … })` → the SPA check names the file, the
  line and the path. (This is §49's defect exactly, re-injected.)
- Adding `tagId` to the `CaseFilters` **interface** → the SPA check names it, which is the case
  that justifies the compiler.

The first two checks were also red on arrival against the three real defects above, which is
stronger evidence than a synthetic mutation.

## Not done

- **Only query parameters.** Request-body fields, response fields and headers have no equivalent
  check. `If-Match` on `PATCH /cases/{caseId}` is bound and declared, but nothing enforces that
  pairing; the body-field version of this defect is the one §5 already records as the largest
  remaining resilience gap ("no runtime response validation"), and it is a bigger slice.
- **`DELIBERATELY_UNBOUND` is empty**, deliberately. The first entry will be the interesting one —
  its header states that "not implemented yet" is the finding, not a reason to be listed.
- **The check reads `qs()` and only `qs()`.** Every query string in the SPA goes through that
  helper today and the guard's call-site floor notices if the count drops, but a URL assembled with
  a bare `?` would not be seen. Making `qs` the only way is a lint rule, not this check.
- **Teams and memberships** remain the next write surface with no endpoints (§39, §45–§49).
- **`documentation`, `reporting` and `work` writes still run on MSW** — `/doc-pages`, `/artifacts`,
  `/reports`, `/work-items`, plus `cases/bulk`. `/suites/{id}/history` still has no link in the app.
