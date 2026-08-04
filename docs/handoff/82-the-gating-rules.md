# §82 — the gating rules: the edge that is not a target, and the override nobody had to invent

**Date:** 2026-08-04 · **Backend** 1487 → **1505** · **Frontend** 649 → **662** ·
**`V26`** · policy count unchanged at **58**

§6g's mechanics list ends with one line that had no implementation since the plan was written:

> **Gating (opt-in, off by default):** a project may require APPROVED status before a case can be
> added to a run, or before a plan can be started. Powerful and genuinely obstructive — must be an
> explicit choice, with a documented lead override for the 2am release.

§81 named it as next and said it *"now has everything it needs"*, which is the unusual part: this
slice built almost nothing new. The case and suite ladders landed in §75, the plan's in §73, the
record in §76, the settings row to be opt-in *in* in §77, and §81's board is where somebody stuck
behind a gate goes to find out what is blocking them. What was missing was **two boolean columns
and the two places that read them**.

## The slice is two decisions, and both are about where a rule binds

### 1. The plan rule is an EDGE, and the target-keyed reading is a trap

`PlanLifecycle` reaches `ACTIVE` from three states, and only one of them is somebody starting an
unreviewed plan:

- `DRAFT → ACTIVE` — the move §6g's ladder allows *because review is optional*, and therefore the
  one a project turning gating on is asking to close;
- `APPROVED → ACTIVE` — the rule being satisfied;
- `CLOSED → ACTIVE` — **a reopen**.

The obvious implementation is *"the target is `ACTIVE`, therefore the plan must be `APPROVED`"*. It
compiles, it passes the draft test, the approved test, both override tests and the independence
test — and it makes **every closed plan on a gated project permanently un-reopenable, by anybody,
for ever**, because a closed plan's status is `CLOSED`, no legal move takes it back to `APPROVED`,
and `CLOSED → DRAFT` is refused outright. A dead end reachable by ordinary use: §68's and §69's
bound, arrived at from a third direction.

**The code had already drawn the line the gate needed, one slice before it needed it.**
`PlanStatusService.action` audits `CLOSED → ACTIVE` as `plan.reopened` and the other two as
`plan.started`, because §72 argued that two acts reaching one target are not the same act to
anybody reading the trail. So §6g's word *started* was a defined term in this codebase before there
was a rule about it, and `startsWithoutApproval` is that definition re-used rather than a second
opinion about it.

The mutation is the guard: dropping the `current == DRAFT` half reddens **exactly one** test.

### 2. The case rule binds on the RESOLVED set, or the plan is the bypass

A run names its cases one of two ways — an explicit `caseIds` list, or the expansion of a plan's
scope — and `RunCreateService` resolves them into one set before it materialises anything. Gating
only the explicit list would leave *"create the run from the plan instead"* as a way round the
policy that **costs no permission and leaves no trace**, on the path a release run is most likely to
take. So the gate reads `edit.caseIds()`, after resolution.

**And the whole run is refused, never trimmed.** Dropping the unapproved cases and materialising the
rest is `CaseBulkService`'s shape for a filtered selection, and it is wrong here for the reason this
class's own header already gives about partial materialisation: a checklist quietly shorter than the
one that was asked for is indistinguishable from a run whose scope was smaller, and its pass rate is
computed over a denominator nobody chose.

## The override needed no new permission key, and the key is derived rather than chosen

§6g asks for *"a documented lead override"*. Three things about it:

**No new key.** §67 refused to invent `evidence:read`, §79 refused `suite:comment`, §80 refused an
assignment key — all for one reason: a key `V3` seeds to nobody is a 403 no administrator can grant
their way out of. So the override costs the artifact's own approve key, `case:approve` /
`plan:approve`.

**Which is *derived*, not picked.** A holder of `case:approve` can reach this exact run today by
approving the cases and then creating it. The override therefore **widens nobody's reach**. What it
changes is the record left behind: the cases stay honestly unapproved with `approvalGateOverridden`
on the run's audit row, where the workaround it replaces launders a sign-off nobody performed into
the approval history §6g exists to keep honest. That argument is the whole justification, and it is
why an override is *better* than the thing people would otherwise do.

**Explicit, never automatic.** The request carries a flag. A lead silently exempted never discovers
the policy their own project turned on, and the run would carry no record that anything was
overridden — which is what §6g's word *documented* is for. The flag is consulted **only where the
gate would otherwise refuse**, and only for a holder of the key, so it can neither widen reach nor
turn an acceptable request into a refused one. A caller who sends it without the key gets **the same
409**, not a 403: they are authorized to make the request and simply not authorized to ignore the
project's policy, and a 403 would send them to an administrator about a grant they already hold.

`ApprovalGating.Verdict` holds the *vocabulary* — `refusal == null` means proceed, and `overridden`
means the gate **fired and was overridden**, which is narrower than "the flag was sent". The two
rules are **not** folded into one method: they have nothing in common as computations (one queries
up to four hundred cases and names the offenders, the other compares two enums), and abstracting
over the only parts that differ is the pattern-stacking the plugin rules call out. What is shared is
the meaning of the answer, because getting *that* wrong is silent both ways.

## Gating is NOT governed by the review opt-in, and that is §77's own rule applied

The tempting reading is that gating is part of §6g's apparatus and should be inert wherever review
is switched off — the shape `ReviewService.reReviewOnEdit` takes, chosen deliberately in §77.

It is the wrong reading here, and §77 settles it: **"off" is narrow — it suppresses the reviewer
apparatus and NOT the status ladder.** A project with review off still has `DRAFT` / `IN_REVIEW` /
`APPROVED` on every case and plan, still moves artifacts through them, and can perfectly well want
"nothing unapproved goes into a release run" without ever running a peer review. Gating reads the
*status*, which the opt-in does not touch. So it is its own opt-in with its own default.

ANDing the two would have disabled the gate on exactly the projects that never turned peer review on
— most of them, and including every project that enabled gating for the reason it exists. Stated
rather than discovered: **turning review off does not turn gating off.**

## What the build and the tests caught

**`npm run build` caught the mock dropping both fields.** `saveProjectSettings` takes the whole
value, so the compiler refused a handler that forgot one — which is the same defect class the
frontend's own form carries, and the reason the settings form's submit now has a comment on it: this
is a `PUT` of all eight values onto a server that reads an absent boolean as `false`, so a field
listed on the type and omitted from the submit does not fail to save, it **switches that setting off
on the next save of the form**. An admin correcting the peer-review count would quietly reopen the
gate, with a success message. `ui-rules` calls this the highest-severity form defect.

**Five backend tests went red on the first run and only one was about the code.** Four were a
fixture leak: `project_settings` is written by the new `gating()` helper and by nothing else in
those classes, so it was not on `clearMutableRows`' list and **one test turned the gate on for every
test that ran after it** — three of the casualties predate this slice and none of them is about
gating. A per-project policy row is mutable state and belongs on that list. The fifth was a wrong
persona: reopening is a move *down* from `CLOSED`, so `permissionFor` answers `plan:approve`, and an
engineer is refused with 403 whatever the gate says.

**`ReviewSettingsPage` had no test file at all** — shipped that way in §77, found while adding to it.
It has one now, and the load-bearing test asserts the save body as an **exact object**: a
`toMatchObject` would pass with a field silently dropped, which is the failure.

## Two things found and deliberately not changed

**`TestRunInput` is hand-written**, not `S['TestRunInput']`, so nothing mechanically checks the
request body against the contract — a property added to the spec is invisible on the client until
somebody notices. Left alone as a minimal change; it is a real follow-up.

**`CaseFacts.unapprovedCases` uses `<> 'APPROVED'`, and no test covers `DEPRECATED`.** The
enumeration `IN ('DRAFT','IN_REVIEW')` passes the whole suite while letting **retired content into a
release run**. The mutation row says so and says not to skip it for reddening nothing — that is what
it is reporting.

## Gates

`./gradlew build` — **1505 tests, 0 failures, 0 skipped** (1487 → 1505: 8 in
`PlanLifecycleEndpointTest`, 10 in `RunCreateEndpointTest`). `npm run build` clean, `npm run lint` 0
warnings, `npm run test:run` **662/662** (649 → 662: 4 run dialog, 3 plan lifecycle, 6 the new
`ReviewSettingsPage.test.tsx`).

> **This machine had neither toolchain installed.** No JDK 21 (only 17) and no `node_modules`, so
> `./gradlew build` had never been run here — the Gradle wrapper downloaded its distribution on the
> first invocation. A Temurin 21 was unpacked into the session scratchpad and passed via
> `-Porg.gradle.java.installations.paths`, and `npm install` was run. Nothing about the repo was
> changed to accommodate either. Worth knowing before the next session assumes the gate is one
> command away: it is, but only after ~15 minutes of setup, and `./gradlew build` itself takes
> **15 minutes** here.

## Not browser-verified

The sixteenth slice in the deferred queue; **15 rows** in
`docs/verification/frontend-browser-checks.md`, three of them live-only (the disabled override needs
a persona mock mode cannot produce, and the audit field has no screen).

**Zero mutations run inline** — the policy, followed. **Ten** are queued in
`docs/verification/backend-mutation-checks.md`. The one to run first is the plan edge: it is the
implementation an ordinary reading of §6g produces, it looks like a simplification, and its casualty
is a state no test reaches without thinking to close a plan first.

## What this leaves for the next slice, named rather than implied

**§6g is complete.** The workflow's every part now has an implementation: the three ladders, the
record, the settings, comments, assignment, the inbox, and the gates. What is left in the plan
around it is not review work:

- **The two remaining board generators**, unchanged from §81 and still a feature apiece rather than
  a generator apiece — `DOC_UPDATE` needs §6i's stale-case flagging, which nothing computes, and
  `DEFECT` needs the local defect record, which nothing writes.
- **§6l's cross-project "My Work" board**, so the inbox stops being per project.
- **`/reports` and M2.5**, still the only dead contract left, and still a milestone rather than a
  slice.

Still latent, unchanged: **`@Version` on `TestRun` and `work.WorkItem`** (§74),
**`project_settings.lock_version` with nothing sending an `If-Match`** (§77), §79's residual — **the
closed-review path on the comment resolution endpoint has no test of its own** — and now
**`TestRunInput`'s hand-written shape** and **the `DEPRECATED` case that no gating test covers**.
