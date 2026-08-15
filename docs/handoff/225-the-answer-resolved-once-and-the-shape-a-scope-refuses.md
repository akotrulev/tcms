# §225 — The answer resolved once, and the shape a scope refuses

**One slice, two independent hardenings of the roles-and-permissions layer, both from the same
review pass:** `PermissionEvaluator` now memoizes its answers for the life of one transaction, and
`V56` puts triggers on both grant tables so a row whose shape disagrees with its role's scope is
refused at the database, for every writer, not only on the one HTTP path that already refused it.

## The memo — and what it is not

A full resolution is seven-to-eight queries (account gate, company gate ×2, project lookup, the
three terms, the override switch, plus the token's role on capped requests), and one request asks
repeatedly: `ProjectService.writeGranted` asks `canView` then `canDo`, a gated delete adds
`qualifierOf` — each a complete re-resolution — and `AdminDirectoryService.reachOf` multiplies a
resolution by every project in the company, then gets called more than once per request
(`RoleGrantService.grant` gates through it and then renders its view through it again).
`ProjectService.listProjects` filters with one `canView` per project.

The memo is a map bound to the current transaction through `TransactionSynchronizationManager`,
unbound on completion, keyed `(scope, companyId, userId, projectId, tokenPrincipal)`. The lifetime
is the point: it dies with the transaction, so it cannot serve one request's answer to another,
cannot key across companies (the company is *in* the key, and the memo never outlives the context
that produced it), and needs no invalidation protocol. **It is not the cross-request cache the
`role_assignment` table comment specifies** — the evaluator's header now says so in both
directions: the memo must not grow a longer lifetime, and the real cache remains a deliberate
future slice with an invalidation design of its own.

### The one obligation, and the check that already guards it

A service that mutates a grant-bearing table **and asks the evaluator again in the same
transaction** is answered from before its own write unless it calls the new
`forgetMemoizedAnswers()` between the two. Exactly one service does that today, and it is the one
whose correctness depends on re-asking: `RoleGrantService.revoke`'s self-lockout check deletes the
grant, flushes, and asks the same gate question again — which the memo, populated by the gate a few
statements earlier, would have answered "still holds" forever. The call sits between the flush and
the question, with the grant path clearing too (a caller granting to *themselves* has `viewOf`
re-derive their reach afterwards).

This is not an honor-system rule: `RoleGrantEndpointTest`'s lockout tests go red if the revoke-path
call is removed, and `PermissionEvaluatorTest`'s new `TransactionScopedMemo` nest pins the
behaviour itself — including the deliberately uncomfortable assertion that *without* the seam the
transaction reads its pre-write answer. That staleness is pinned as the contract rather than
tolerated as a quirk, so the day it surprises somebody, the test names the mechanism.

Existing tests needed no change, which is itself evidence of scope: every other test in
`PermissionEvaluatorTest` calls the evaluator outside any shared transaction, so each call opens
its own and the memo never spans two.

## `V56` — the two rows the evaluator misreads, now unwritable

`RoleGrantService.scopeError` was the **only** thing anywhere refusing a COMPANY-scope role paired
with a `project_id`, and nothing at all refused a non-SYSTEM role in `instance_role_assignment`.
Neither row errors when read — both *answer*:

- A COMPANY-scope role naming a project confers its keys as project permissions
  (`findProjectScopedHeldPermissions` deliberately never joins `role`) **and trips the override
  switch** — `hasProjectScopedAssignment` counts it as "this project said otherwise", replacing the
  subject's inherited company-wide default. A row written to widen quietly removes.
- A PROJECT- or COMPANY-scope role in `instance_role_assignment` becomes an instance-wide grant of
  its keys with no company gate in front of it, because the SYSTEM term is deliberately outside
  that gate.

`V56` adds a `BEFORE INSERT OR UPDATE` trigger per table (triggers because the fact being checked,
`role.scope`, lives in another table; the declarative alternative — a denormalized scope column
with a composite FK — would need populating by every writer, which is the per-writer remembering
the migration exists to end), plus a sweep that fails the migration if existing rows already
violate. The sweep iterates companies setting the GUC transaction-locally, because `FORCE` RLS
covers the migration role too — `V15`'s backfill section is the precedent and is cited in the file.
`RoleScopeShapeTest` drives all three refusals and every legal shape through raw SQL, deliberately
below the application layer, since the service refusal is the part that was already proven.

## Recorded, not built

The same review produced two more items that are suggestions, not slices, and they are recorded
here so they are found rather than re-derived: **the cross-request cache** (the cheap shape is a
per-company grants epoch bumped by the few services that write grant tables, so a cached set
validates with one indexed read), and **denial observability** (a refused permission currently
leaves no trace anywhere — a Micrometer counter by key and outcome, not audit rows, which
high-volume denials would bloat).

## Gate evidence — and the two-lanes story it comes with

Targeted suites on shanks, green: `TransactionScopedMemo` 4/4, `RoleScopeShapeTest` 4/4,
`RoleGrantEndpointTest` unchanged and green, compile clean under `-Werror`. Packages suites green
(123/78/119 — the prose rules scan the new backend comments). **The full `./gradlew build` could
not be run on the shared tree**: two other lanes were mid-flight in the same checkout, and the
first two full-suite attempts failed with dozens of "Could not write XML test results" errors —
which turned out to be *two sessions sharing one remote `backend/build/`*, the §0 two-writers
failure arriving through the build directory rather than git. So the gate ran on an **isolated
tree** (`git archive HEAD` + this slice's five files, synced to its own remote directory):
**2837 tests, 3 failed — all three pre-existing on the base commit**, the LDAP lane's §223 phase 1
having declared `POST /auth/ldap/{providerId}/login` with no handler and the LDAP provider fields
with no paired record members. Zero failures in this slice's scope; the XML-write errors did not
reproduce in isolation. Backend-only slice: no contract change, no frontend, no browser rows.
Mutation rows `§225 PM-1..PM-3` added to the backend queue.

**The numbering was contested twice while landing.** Drafted as §223/`V56`; §223 turned out to be
the LDAP lane's (its commits carry it), §224 landed pushed by the SSO-review lane mid-slice, so
this is §225 — and `V56` was *kept*, per the parallel-execution rule that landing order decides:
this slice lands first, so the LDAP plan's pre-assigned `V56__ldap_identity_provider.sql` becomes
`V57` (its own plan anticipates exactly this; a note was left in it, and both live sessions were
messaged). A `V57` here with their `V56` landing later would be refused on every already-migrated
database — `out-of-order` is unset.
