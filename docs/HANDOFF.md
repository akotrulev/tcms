# TCMS — Session Handoff

**Date:** 2026-08-02 (latest section: **§76, the review record** — `review` and
`review_participation` have been in `V1` since the beginning with **no entity, no query and no
writer of any kind**, and §75 named this slice on its way out by *deriving* it rather than listing
it: once the suite's ladder landed, **every item-side ladder existed** (case §62, plan §73, suite
§75) and what was left was what §6g puts *inside* `IN_REVIEW`. **Three permission keys get their
first consumer at once** — `case:review`, `suite:review`, `plan:review` — the seventh instance of
§71's search, and the first time keys were left unconsumed *deliberately*; `case:comment` stays
unconsumed and that is also correct, because `V3` grants it to **Viewer** and the review tier to
Test Engineer, which is §6g's three tiers made observable. **The placement decision is most of the
design.** A review's subject is an **opaque triple** with no FK, which is what lets one record serve
all three artifacts — and three writers in *two* modules must reach it (`SuiteStatusService` and
`CaseBulkService` in `repository`, `PlanStatusService` in `execution`), which cannot depend on each
other. So it lives in **`project`**: `review.project_id` is `NOT NULL`, every gate on it is
project-scope, and its every FK already points at the three tables plan §3 permits. **A `review`
module was considered and refused** — it needs plan §3 and `ModuleStructureTest` amended and *still*
could not be called by either module (a fourth foundation module is "a decision, not an import"),
leaving the product's first domain event, whose type could live in neither end. What `project` costs
is that it may depend on `tenancy` only, so names resolve through **`PeopleDirectory`**, the port its
own package comment prescribes. **And writing the third consumer of "what is this person called"
found the first two**: `CaseOwners` and `ExecutionPeople` were byte-identical copies of §6u's naming
rule, the second under a comment saying *"the two classes must not disagree"* — §58's E1, surviving
because neither module can import the other; both now delegate. **The ladder tells the record where
the artifact landed and nothing else** (`ReviewStanding`, three values) — and deliberately **not**
where it came from, because the open review row is that fact and is the better one. **`V22` adds a
fourth state because a withdrawal is not a rejection**: the shipped ladders put `IN_REVIEW → DRAFT`
on the *edit* key, so one edge is walked by two acts, and closing both as `REJECTED` writes a
rejection nobody performed into the record kept to show an auditor who said what; the distinction is
**derived, never sent**. Its other half is a **partial** unique index — one *open* review per
subject, while the closed rows are the history. **The participation endpoint cannot write an
`APPROVAL` row at all**: `APPROVED` and `REJECTED` are refused as field errors, because accepting
either is a second, cheaper door to the sign-off §6g gates once, held by everyone with the advisory
key; the gate is decided by the row (§69's seam, fifth consumer) and resolves through
`findInProject`, never `findById`, which would accept a **sibling project's** review. **The record
starts when a writer exists** — an artifact already `IN_REVIEW` has no row on any real instance until
it is re-submitted, so the panel reads "Not in review" beside a chip saying In review, correctly.
**Seven mutations run, three queued — and running them at all was a drift the user corrected the
same day**: §65 defers mutation checks *in full*, §72–§75's inline handfuls made the exception look
like the rule, and both queue headers plus `CLAUDE.md` now say **write the row, run nothing**. The
fifth is still the one to read: it **failed to redden**,
because `waitFor(...toBeDisabled())` is satisfied by `useCan`'s pending state, so a panel with a
hard-coded permission passed it. §66's finding inside this slice's own tests, caught only by running
the mutation. **`V23` is the next free number**, and the per-project settings row now blocks four
things at once. Before that: **§75, the suite lifecycle** — `suite.lifecycle_status` had
**exactly one writer**, `Suite.create` writing `DRAFT`, so three of four values were unreachable and
**every suite on every real instance has been a draft for its entire life**; `Suite.applyEdit`'s own
javadoc named the reason its mutator was absent and, by naming it, named this slice. §72's and §73's
finding one aggregate over. **§74 named the reviewer apparatus as next and this is deliberately not
that** — §28's rule applied, re-deriving from the code found §6g's ladder has *three* subject types
and the suite's had never been built, which is a better place to start the reviewer half from than
a `V22` and three tables. **The ladder is ONE ladder**: `LifecycleStatus` is the status of a suite
*or* a case, `V1` spells the identical constraint on both tables, so `LifecycleLadder` is read by
both writers rather than maintained twice (§58 E1); the *plan's* is deliberately not folded in,
because `ACTIVE`/`CLOSED` are an execution phase these four do not have. **And writing the second
consumer is what exposed the first one's defect.** `PlanLifecycle`'s header named
`BulkOperations.permission` as the weaker target-keyed shape when §73 derived the edge rule — and
then §73 fixed only plans. On a case it was **live**: `case:approve` was asked only for a move
*into* `APPROVED`, so a Test Engineer could select two hundred approved cases, ask for `DRAFT`, and
**rescind a lead's sign-off with the permission they use every day**, silently, through a dialog
built for that volume. **Leaving an approval is an approval act too** — three acts cost the approve
key (granting the sign-off, retiring, and taking either back), and the fix is **per row**, because
one end of every edge is the row's stored status and a filtered selection mixes statuses; raising
the floor instead would refuse an engineer submitting their own drafts, which §6g says needs only
edit rights. **The mutation is the proof**: dropping the unmaking arm reddens **exactly 2 out of
112**, one per aggregate — which is both the evidence that the two ladders are genuinely one and
the evidence that the case half was not hypothetical. **Three case transitions changed and each
reversed something recorded**: `APPROVED → IN_REVIEW` no longer exists (the test asserting it
justified itself in a comment that was wrong twice over), `DEPRECATED` returns only to `DRAFT`
(§6g's clone rule — approval attaches to reviewed content by a named person), and deprecating now
costs the approve key because retiring is the soft form of a delete that Test Engineer is withheld
from outright; the `OWN`-qualified refinement is named and not built. **The fixture finding runs
both ways at once**: the backend fixture hard-coded every seeded suite `APPROVED` while no real
suite has ever been anything but `DRAFT` (§72's rule, applied to a test fixture), *and* the mock's
fixtures have carried approved folders since Phase 1 — so **mock mode has been showing a signed-off
tree the product could not produce**, which is §74's exit criteria one aggregate over. A status
change writes **no** `suite_version` row (composition, not a verdict — §73's call); the read-back
helper moved to `SuiteService.viewOf` before a fourth `CaseRenderer`-style copy could exist. **Six
mutations run, three queued** — four backend (**2 / 3 / 1 / 2**) and two frontend, the latter not
optional because all 17 of that file's tests passed first time. **No migration; `V22` is still
free**, and the reviewer slice will need it. Before that: **§74, the plan editor** — **three things had never been
written by anything and all three are in one aggregate**: `entry_criteria` and `exit_criteria`, so
`PlanCriteria.evaluate` and the exit-criteria panel §46 built above it have **had nothing to evaluate
on any real instance for their entire lives**; `test_plan_version`, the last of §6j's four versioned
aggregates with no writer; and a plan's **scope after creation**, which could only be changed by
deleting the plan and losing its key, its runs and its history. **The orientation finding changed the
shape before any Java — you could not write back what you were served**: the detail served
`caseCount`, an *expansion*, and no scope ids at all, so a picker would have had to invent what it
submitted. §68's finding one aggregate over, arrived at the same way, and the rule it produced is now
general — *a field a `PATCH` accepts must be readable from the `GET` before it*. **A plan version is
its SCOPE**, which is what makes most saves write nothing: `V1`'s own column comment plus §6j, so a
**rename writes no version** (`suite_version`'s identical call) and `caseCount` and `configurations`
stay out of the snapshot because one is derived and the other is a *project's* list. **One value
serves as both snapshot and hash**, a deliberate divergence from `CaseVersions` — nothing in a plan's
scope moves on its own, so splitting them would be two definitions of "changed" maintained side by
side. **The mutation is the proof**: bump-on-every-save reddens **exactly 3** while every scope and
criteria test stays green. **Absent, null and empty are three instructions**, split by *kind* —
scalars clear on null, lists leave-alone on null and clear on `[]` (§68's rule, now stated
generally) — and that is what keeps `TestPlan.create`'s *nobody has decided* distinguishable from
*there are deliberately none*; reading it wrong reddens **4**, two of them the version tests, because
a rename would empty the scope. **The metric vocabulary is closed because a typo is invisible**: an
unrecognised metric renders *identically* to one the product cannot evaluate yet — both a gate with
no verdict — and a release meeting cannot tell them apart, so the check is at authoring time;
`isKnownOperator` **calls** the comparator rather than listing the six operators twice (§58 E1, §70).
**A closed plan refuses the edit with 409, giving `PlanLifecycle.isClosed` its first consumer** —
§73 wrote it and nothing called it — with §72's exact placement re-derived: after the permission, before
the `If-Match`, before validation. **The defect the slice found is the optimistic lock that could not
fail**: `TestPlan` mapped `lock_version` and **never annotated it `@Version`**, so it was `0` on every
plan since M2 slice 2 while the contract declared it *required* — an `If-Match` could not fail,
because there is no such thing as a stale 0. §73's own lesson applied: the sweep found **three**
entities missing it — `TestPlan`, `TestRun`, `work.WorkItem` — and only the first is fixed, because
neither of the others serves `lockVersion` and adding it to `TestRun` would make two testers recording
results on one run collide on the product's most-used write. **Six mutations run, four queued** —
four backend (exactly 3 / 2 / 4 / 1) and two frontend, the latter not optional because 8 of 10
frontend tests passed first time. **The MUI checkbox trap fired a third time** despite §71 extracting
a helper for it, because the dialog was written from `PlanCreateDialog`, which predates the helper and
still carries the bare spelling. **No migration; `V22` is still free.** Before that: **§73, the plan
lifecycle** — `PlanStatus` has five values,
`TestPlan.create` writes `DRAFT`, and **nothing else has ever written that column**, so four of the
five were unreachable and **no plan on any real instance has ever been in review, approved, active or
closed**; `test_plan.closed_at` was a `V1` column the entity did not even map. §72's finding one
aggregate over, found the same way, and named twice in `TestPlan`'s own javadoc — *"there is no
mutator, because `plan:edit` is a separate permission and a separate slice"*. **Three permissions get
their first consumer in Java at once** (`plan:edit`, `plan:approve`, `plan:delete`), the sixth
instance of that shape; **`plan:review` stays unconsumed and that is the correct answer**, because
§6g puts peer review *inside* `IN_REVIEW` accumulating on the item without moving it, so the key
belongs to the reviewer apparatus and not to a transition — as do `case:review` and `suite:review`.
**The slice is one decision: the gate is decided by the EDGE, not by the target.** The natural
reading of §6g — `plan:approve` guards the move *into* `APPROVED`, the shape `BulkOperations` uses
one module over — compiles, passes an "an engineer cannot approve" test, and **lets that same
engineer rescind a lead's sign-off by asking for `DRAFT`**, the button beside it. So `permissionFor`
takes both ends: `plan:approve` grants the sign-off, ends the plan, and **takes either back**;
`plan:edit` carries a plan forward through the states nobody has signed off. Roles spec §A4 principle
1 unchanged, and the reason the gate reads a **stored** fact — `writeGrantedOnTarget`, §69's seam,
second consumer. **The mutation is the proof**: dropping that arm reddens **exactly 2** while every
other gate assertion stays green, so the cheaper implementation would have shipped with a full class
of tests passing. **The ladder must not require approval**, because §6g says review is optional, off
by default, per project — and that setting does not exist yet — so `DRAFT → ACTIVE` is legal and
review is a detour; two "reachable only from" rules survive it, `APPROVED` only from `IN_REVIEW`
(§62's call) and `CLOSED` only from `ACTIVE` (a plan nobody started, recorded as a finished release,
is `RunLifecycle`'s 0%-executed run). **Delete and close partition the space**: `plan:delete` refuses
with 409 and the count while a live run names the plan — `findPlanKeys` is not filtered on
`deleted_at`, so nothing breaks loudly, the runs keep rendering `TP-3` and the link goes nowhere —
and the way out is `CLOSED`, not force. **Delete a plan nobody executed; close one somebody did.**
**Two pre-existing defects, both in one file, and the first is the lesson**: `PlanListPage`'s colour
map is the *sibling* of the defect §72 found on the run list and §72's sweep never reached it —
`Record<string, string>`, carrying an **`ARCHIVED`** that is not a plan status, **missing `IN_REVIEW`
and `APPROVED`**, with `status.toLowerCase()` beside it; a fix applied to one instance of a class
needs the grep that finds the others. And the exit-criteria column counted `!c.met`, where **null
means *not evaluated*, not *unmet*** — so a plan whose gates were green would have read "3 unmet",
unfireable only because `exit_criteria` has no writer, **which the next slice gives it**. The mock's
fixtures gained a `DRAFT` and an `IN_REVIEW` plan: §72's lesson applied rather than restated, since
every fixture plan was `ACTIVE` or `CLOSED` while every real plan was `DRAFT`. **Six mutations run,
four queued** — four backend (exactly 2 / 3 / 1 / 1) and two frontend, the latter not optional
because all 7 frontend tests passed first time. **No migration; `V22` is still free.** Before that:
**§72, the run lifecycle** — `test_run.status` had been
written **exactly once in the product's life**, to `NOT_STARTED` by `TestRun.create`, so on every
real instance **every run has always read "Not started"** — including after every one of its results
had been recorded — and `started_at`/`completed_at` were columns nothing ever filled. Three
production javadocs name this slice by name: `TestRun`'s *"Still no mutator: closing a run is
`run:close`, a separate slice"*, `Evidence`'s *"the invariant belongs with `run:close`"*, and
`RunDetailPage`'s comment stating the rule it renders — *"a run starts when somebody records the
first result"* — **which nothing implemented**. **Not §52's "mock agrees with itself"**: the mock had
the same hole, and looked right only because its fixtures ship runs already `IN_PROGRESS`, so a run
*created* in mock mode was as stuck as one created live. One shape, two implementations, both absent,
and a fixture hiding it from both — the check that would have caught it is that **the mock's fixtures
never sit in the state a fresh install produces**. **`run:close` is the fifth permission with no
consumer in Java and the first that DISCRIMINATES**: §71 had to record that its mutation could not be
written, because `V3` grants `company:teams` and `company:members` to the same roles — here `V3`
grants `run:close` to Lead and up and **withholds it from Test Engineer**, who holds `run:execute`
and `result:submit`, so the gate swap reddens **exactly 1**. **"Closed" is a status PAIR, not a fifth
value** — `V3` calls the permission *"Close or abort"*, two acts, and `RunStatus` already has two
terminal values whose difference its own javadoc explains; a fifth would give the product two ways to
say a run is over and force every reader to know both. Hence **no migration; `V22` is still free**.
**Starting is automatic, finishing is not, and the asymmetry is the gate**: the first result starts
the run (stamped with the *result's* instant, or a run sorts after the first result in it) and needs
no permission, while a run never completes itself — auto-completion would let a Test Engineer close a
run by finishing it, which is exactly the line §A4 draws. **Reopening exists because a mis-click must
be repairable** (§68's and §69's bound, from the other side) and its target is **derived, never
chosen**: both live spellings are normalized server-side, so a client cannot produce an `IN_PROGRESS`
run with a null `started_at`. Two transitions are refused and both refusals are the feature —
completing a run with nothing recorded (that is what ABORTED is for) and sliding from one ending to
the other (the audit trail would show one and the row another). **§67's deferral is closed on its own
stated terms**: it named exactly what had to be true first — a real closing act, and `record`
refusing to amend a closed run — so the freeze lands on **all three writes at once** rather than
making the screenshot stricter than the verdict. Three placements carry the reasoning: **after** the
permission (a Viewer still gets 403, not 409), **before** the `If-Match` (a stale header is fixable
and this is not), and **before** authorship on the delete (a closed run refuses *everybody, the
author included* — "this is not yours" would name a fix that does not exist). **The slice had to
delete a duplicate before it could start**: `ExecutionService#resolve` and `EvidenceService#resolve`
were byte-identical private copies under a javadoc saying they were the same, and both needed the
same change — the freeze must see the *run*, which the old signature threw away. §58's E1 and §70's
verb list again; hence `ExecutionTargets`. **Two pre-existing defects found.** `RunListPage`'s colour
map was typed `Record<string, string>` and had drifted precisely as `lib/labels.ts`'s header
predicts — it carried a **`PLANNED`** that is not a run status and was **missing `NOT_STARTED`**, so
on a real box *every run rendered grey*; both screens also spelled the status with a regex rather
than a label map. And `ExecutionService` writes its audit `target_type` as `"TEST_EXECUTION"` where
every sibling writes snake_case — **found and not fixed**, because nothing reads the column yet
(`audit:view` has no consumer) and changing it would split existing rows. **Seven mutations run,
three queued** — four backend (exactly 1 / 2 / 2 / 1) and three frontend, the last not optional
because **all 7 frontend tests passed first time**, which §66 established is the moment to distrust
them. Before that: **§71, team management** — `team` has been in `V1` from the
beginning with **readers on three sides and a writer on none**: `GET /teams` renders the org chart,
`RoleGrantService` accepts a team as a grant's subject, and `PermissionEvaluator` resolves a
caller's teams inside **every one of its five union queries** — and nothing could create one.
`provision-company` seeds no team, `Team.create` had **only test callers**, `rename`/`reparent` had
**none at all**. So on every real instance that has ever run `GET /teams` answered `[]`, and
`POST /role-assignments`' team branch — built, validated, tested, documented — **could not be
reached with a `teamId` that named anything**. **Mock mode looked finished the whole time**, because
the fixtures carry three teams with members, leads and project chips; §52's "mock agrees with
itself" class, and why this survived to §71. **`company:teams` gets its first consumer in Java** —
seeded by `V3`, granted since M0, never once asked: §62's `case:approve`, §64's `library:manage` and
§69's `company:vocabulary` for the **fourth time**, and the pattern is now reliable enough to be a
search. Five endpoints, gated at COMPANY scope. **`PUT` not `PATCH`, for `parentTeamId`** — an
absent field and an explicit `null` are the same bytes, so "move this to the root" would be
unreachable (§69's reason) — **and a team may actually be MOVED, which is where the two part
company**: §69 refused it because the values below a classification value are what cases are
classified by, while a team's parent edge is **org-chart structure and never a permission edge**
(§6k), so a move changes a picture and confers nothing. **The substance is the membership check.**
`team_membership.user_id` references instance-level `app_user` while `company_id` comes from the
session, so a **sibling company's account** satisfies the FK, the company column, RLS and every
constraint — §64's `sharedStepId` family one level up — and then two things happen at once: the
evaluator's five projections resolve teams through `team_membership` **alone**, never joining
`company_membership`, so that person holds whatever the team holds; while `listTeams` renders them
**nowhere**, because `refs` drops an id it cannot place. **The read side hides the evidence of the
write side, and an invisible grant is worse than a leaked name** — so the test asserts *404 **and no
row written***, which a status-only assertion would have missed. 404 rather than a field error, or
the endpoint is an instance-wide membership oracle (§6t); `INVITED` counts, `REMOVED` does not.
**Delete refuses and the refusal is the feature**: `role_assignment.team_id` is `ON DELETE CASCADE`,
so deleting a team holding grants would revoke every one **silently** — 409 with the count, §69's
live-children shape; child teams likewise, since `parent_team_id` has no cascade and the
alternative is a 500 or silently promoting somebody's sub-teams. Members do **not** block a delete.
**`V21` fixes a backstop that was strictly weaker than its check**: `V1`'s `uq_team_name` is
exact-match and had **never adjudicated a single insert**, while `TeamEdit` folds case — and an app
check stricter than its constraint is a check with no backstop, so two admins racing "QA" and "qa"
both win. **The mutation that cannot be written is the finding**: both were run inline (gate →
constant reddens **exactly 6**, dropping the membership check **exactly 2**), and **neither can tell
`company:teams` from `company:members`**, because `V3` grants both to the same roles and every
persona the suite can build holds both or neither. §61's "vacuously true" shape, recorded rather
than papered over — `SeedDataTest` now fails the day a role holds one without the other (**and that
is the good case**: add the persona to `TeamEndpointTest`, do not re-align the seed), while
`TeamsPage.test.tsx` carries the only test that can tell them apart today, because a frontend test
can mock a permission set the seed cannot produce. **§68's orphan rule matters more here than it did
there**: `TeamTree` descended from the roots, so an orphan or a cycle member was silently dropped —
cosmetic while read-only, and now the tree is the **only** place a team can be edited or deleted, so
a row it does not draw is a row nobody can fix. **A third copy of one helper was about to be
written** — `inputTestId`/`selectTestId` were already two, each carrying its reason in its own
comment (§58 E1's shape); both now import `lib/muiTestIds.ts`, and the trap fired again immediately
in a new way: the parent select's id went on MUI's **hidden native input**, so the first draft failed
with `pointer-events: none` on the affordance rather than on the assertion. Before that: **§70, the verb the contract checks could not see** — §69's
build-status note recorded four frontend tests it could not account for and left them for "one grep
next session". The grep found the author and, through it, a defect open since §53. The three
contract-hygiene checks are **data-driven** — one `it.each` case per `api.*` call site discovered
through the TypeScript compiler — so §69's three new endpoints generated tests nobody wrote; but the
arithmetic is **2 + 2, not 3 + 3**, and the missing third is the finding. Both checks carried their
own copy of the client's verb list, `['get','post','patch','put','delete']`, under the comment *"as
spelled on the client"* — and **the client spells it `del`**, plus an `upload` that was never in the
list. `grep -rn "api\.delete"` returns **nothing**: that string matched no call site on the day it
was written and none since, so **eight call sites were invisible to both checks** — every delete in
the product (`cases`, `suites`, `admin` ×2, `documentation`, `runs`, `projects`) and §67's evidence
upload. **Nothing could catch it, and that is the section**: both files guard against vacuity by
*count* (`>= 40`, `>= 25`) and by distinct-verb count (`>= 3`), and four verbs sail past all three.
A guard by count answers *"is this check still running?"* — it was, perfectly, over a client one
verb smaller than the one that ships. §48's rule arriving **inside the §48 family of checks**, and
§58's E1 shape (a hand-maintained copy of another module under an instruction to keep them in step,
already drifted; there too the fix was to delete the copy). The verb set is now **derived from
`client.ts`** through the program that was already being built, mapping each member of the exported
`api` object to the method it actually sends — and **the mapping matters as much as the
membership**, since path items are keyed by the OpenAPI spelling, so `del` must arrive as `delete`
or the repair only swaps "unchecked" for "compared against a method no path item can declare".
`unresolvedApiVerbs` is the other half: a verb whose method cannot be read is exported and asserted
empty, so an unfamiliar shape **fails** instead of narrowing the check silently. `apiResponseTypes`
also built its own second `ts.createProgram` and now shares the module's. **The sixteen newly
generated cases all pass** — the blind spot was real for eight slices and this time empty, which is
evidence about the contract's health rather than evidence the check did not matter. Both mutations
run inline (the slice *is* a check): the unmapped spelling reddens **exactly 8**, and a verb the
reader cannot parse reddens **exactly 2** — one guard per file, the property that did not exist
before. Class swept: `OPERATION_KEYS` describes the spec format rather than another module,
`ContractParameterBindingTest` reads the live `RequestMappingHandlerMapping`, and
`contractParameters.test.ts` keys off `qs(...)` rather than verbs — two instances, both fixed.
**No runtime code changed, so no browser row is owed**; the frontend queue records that decision
rather than leaving the slice absent. Before that: **§69, the classification vocabulary editor** — `classification_value`
had exactly one writer since `V1` and it was a SQL seed function, which creates the Feature dimension
**empty on purpose**; §68 built the case editor's tree picker against it and recorded what was left,
that the tree was **usable and not fillable**. §34's "provisioned company *plus hand-written SQL*"
one vocabulary over. **The orientation pass changed the shape twice before any Java.** First,
**`locked` meant three different things in three documents** — `V2`'s seed says *"a client editing
them is fine"*, the view's javadoc said *"the value list is not editable by the client"*, and the
roles spec grants `company:vocabulary` covering *"Test Type / Level / Phase values"*. Two of three
say editable, and the third makes a seeded, correctly-granted permission **cover nothing anybody can
do** — §62's `case:approve` a third time. Settled: `locked` says **whose the dimension is**, and the
reading that now has a consumer is *which permission at which scope*. Second, **`@ColumnTransformer`
was missing exactly as `Suite.path` predicted by name** — *"it will fail exactly like this on the day
something does"* — and today was that day. **The gate is decided by the row, in KEY and in SCOPE**:
`project:settings` on this project for the project's own Feature tree, `company:vocabulary`
**company-wide** for the three axes the company shares — two different *questions*, since asking a
company key at project scope is the shortcut roles spec §A3 records rejecting. `company:vocabulary`
gets its **first consumer in Java**. Hence `RequiredPermission` (key + scope) and
`ProjectService.writeGrantedOnTarget`, which resolves the target **before** asking anything, because
the permission to ask *is a property of the target* — a caller whose dimension does not resolve
cannot be told 403 without the server inventing which permission they failed. The dimension resolves
through `findForProject`, never `findById`: the latter is scoped by RLS to the **company** and not
the project, so it accepts a **sibling project's** Feature dimension and every cross-company test in
the suite still passes. **The `ltree` label is derived from the id, not the name** — `Suite.create`'s
decision plus one the suite tree does not have: `tcms_ltree_label` answers the **empty string** for
`支付`, and an empty label is not a valid `ltree` label, so a name-derived path fails the insert with
a message about the *path*. A Feature value is the client's own taxonomy in the client's own
language, which is exactly where that arrives first. **What it refuses is the feature**: a move
(`SuiteWriteService`'s reason plus "the values below this one are what cases are classified by"), a
change of dimension (a value that changes axis is a different value), and a retire with live children
(409 with the count — a cascade unclassifies every case beneath it and **none of those assignments is
restorable through the API**, §68's bound from the other end). A value merely *in use* is **not**
refused; that is what the soft delete is for. **`V20`** backs the sibling-name check with a
**partial, per-parent, case-insensitive** unique index — per-parent because §68 turned on two leaves
sharing a name under different parents, `COALESCE(parent_id, nil)` because NULL ≠ NULL would exempt
every root, partial because a retired name must not permanently block a new value. A **backstop, not
the check**. The screen is `/projects/:id/settings/classification`, all four dimensions with the
company's greyed and explained (§31), the tree from §68's `buildValueTree` — whose orphan-surfacing
matters here in mirror image, since **a value nothing renders is one nobody can retire**. The mock
moved `dimensions` into the store and `handlers/cases.ts` with it, or a value added in the editor
would be refused by the case editor as "not in the vocabulary". **Two test findings.** All 25 backend
tests passed first time, and the first mutation reported **4** where the honest answer is **3** — a
broken gate let one create *succeed*, so a second test failed on the *duplicate* check rather than
the gate; a mutation count that measures the fixture reads as extra confidence. And the frontend test
failed for §66's reason before it failed for a real one: rendered bare rather than through a
`<Route>`, `projectId` is `''`, `useCan` is disabled and permanently pending, so **every "disabled"
assertion would have passed with no gate in the code at all**. Four backend mutations run, three
queued; one frontend mutation run. Before that: **§68, classification assignment** — `case_classification` has
been in `V1` since the beginning with **three readers and no writer**, and it was one of
`CaseUpdateRequest`'s **"refused six"**, whose javadoc has said since M1 that each of them is *"a
boundary a later slice deletes in one place"*. `CaseVersions`'s header predicted the same slice by
name. It is the **last of §5's three scheduled Phase-2 gaps** and the one §5 called the largest.
**The orientation pass changed the shape twice before any Java.** First: **you could not write back
what you were served** — `TestCase` serves `features`/`testType`/`testLevel` as **names** with no id
anywhere, while the writer takes uuids, and there is no mapping, because the Feature dimension is
hierarchical (two leaves under different parents may share a name) and the company vocabularies are
renameable. Hence `classificationValueIds` on the detail, **filtered by the same live-vocabulary rule
`CaseSummaries` applies to the names** — one implementation, so the ids and names on one response
cannot disagree. Second: **there is no leaf-only rule.** The editor kept the field read-only for two
milestones on the stated grounds that a select *"would let someone set a parent where a leaf is
required"*; `grep -i leaf` across the plan, the ERD, the roles spec and `V1` finds only §6p's count
arithmetic, and §6q's descendant rule is about **filtering**. What a flat select really loses is the
parentage — a smaller problem, wanting a tree. §51's evidence blocker was wrong the same way, so
**the orientation pass's third question is now "is the stated reason actually written down
anywhere"**. **The slice's real content is the reconcile's bound**: a retired value is a soft delete,
so every read drops it, so a client is **never shown one and cannot resubmit it** — and a reconcile
reading "absent" as "delete" would silently discard a classification nobody chose to drop, on the
first save of any case carrying one. Removal is therefore bounded by the vocabulary the caller was
served, and the consequence is stated rather than discovered: a retired assignment is **immutable
through the API**. §64's rule about stored references, from the delete side. **The sibling project's
value is why validation exists at all** — it satisfies the FK, the company column, RLS and every
check `V2`'s trigger makes, so unchecked it is *stored*, and every later read resolves a
classification through a project the reader holds no grant on; the **third column in that family**
after `suiteId` and `sharedStepId`. Two values on a SINGLE axis are a **400, not the trigger's
`23505`** reaching the client as a 500; duplicates are de-duplicated rather than refused, because
`(case_id, value_id)` already says a case carries a value once. **The edit's list is sorted**, which
is a hash requirement — `CaseVersions#hash` rests its determinism on there being no *set* in the
shape — and `CaseEdit.of` carries the stored ids so a bulk action's version row does not hash as
though the case were unclassified (§62's mis-attribution defect, on a field that class never reads).
**Two pre-existing defects found on the way.** `TestCaseDetailView` **never served `createdBy`**,
though `TestCase` is an `allOf` of `TestCaseSummary` which requires it — and `CaseDetailPage` reads
it from that endpoint, so it was always null, null is *a supplied author matching nobody*, and
**Delete was disabled on every case for every `OWN`-qualified Test Engineer**: §66's headline feature
inverted on the only screen with a delete affordance, shipped through §67. Nothing could see it —
`tsc` allows optional chaining on a required field, the page's own test supplies a fixture that has
the key, and the detail endpoint's field test enumerated the required list **by hand**, written
before `createdBy` existed. That list is now a **computed check** resolving `required` through
`allOf`, verified by re-introducing the defect. And **`CaseDetailService` was a fourth copy of the
render `CaseRenderer` exists to prevent** — the `GET` path was never moved onto it, which is the
wrong way round since it is the render the writers exist to agree with; invisible while the copies
agreed, visible the moment a field was added. **The picker renders from `cardinality` and
`hierarchical`, never the dimension key**, and `buildValueTree` **surfaces orphans *and cycle
members* at the root** where `SuiteTree` drops the latter — in a picker, a value with no control on
screen is silently deselected by the next save. §62's lesson again in its own test: a bare
`data-testid` on a MUI `Checkbox` lands on the wrapper span where `toBeChecked` cannot see it. Four
mutations run, four queued. **No migration; `V20` is still free.** Before that: **§67, evidence attach and delete** — `evidence` and
`attachment` have been in `V1` since the beginning with **no reader and no writer**, and
`EvidenceView`'s own header said so: *"always an empty list in this slice"*. So the execution screen
has rendered a field the product could never populate for the whole life of the module. Both halves
land, and `evidence:delete` gets the **`OWN` consumer §66 named on its way out**. **The orientation
pass changed the slice's shape before any code**: `Evidence.url` is a **required** field with no
route behind it, so attach alone would have had to *invent a string* every consumer may dereference
— hence the download endpoint in the same commit, session-authenticated because a filesystem adapter
cannot sign a URL (#28/§51) and needing **no permission**, since the catalogue has no
`evidence:read` and inventing one is a 403 nobody can fix. **The port lives in `execution`, not a
`storage` module**: `ModuleStructureTest` hard-codes both the module list and the
tenancy/identity/project FK rule, so a new module means amending plan §3 and two architecture tests
to serve **one** caller — `forExecution`/`forStepResult` keep the eventual move honest, since no
call site spells an owner type. **The genuinely new problem is that a filesystem does not roll back
with the transaction** — the first product state outside Postgres. Rows first, bytes second, in both
directions, so every failure leaves the *recoverable* half: a row whose blob is missing 404s and can
be found by joining, where bytes-first leaves a blob **no row names** — unreachable and
uncollectable. **No test asserts either ordering** and that is queued as the mutation whose expected
result is *nothing reddens*. **`V1`'s "immutable once the run closes" is deliberately NOT
implemented**: there is no `CLOSED` status, `run:close` has no implementation, and
`ExecutionService.record` already amends results on a `COMPLETED` run — gating evidence alone would
make the screenshot **stricter than the verdict it documents**. **The contract defect `tsc` found**:
`Evidence.createdBy` was `required` and non-nullable while the server returns null for an
unattributable row — and that null *is* the whole `OWN` arm, so the declaration would have made the
SPA collapse "no author" into "some author"; now `allOf` + `nullable`, §66's shape. **`api.upload` is
a new client seam**: a `FormData` body must not carry a hand-set `Content-Type` or the multipart
boundary is lost, so the first upload would have been a **400 on a request that looks correct in
devtools** — and `spring.servlet.multipart` was **unset**, so Boot's 1 MB default would have refused
an ordinary screenshot (an unset limit is not "no limit", it is the wrong limit). Four mutations run;
the `OWN` one reddens **exactly 2** while `deletesOwn` and the lead's test stay green, which is the
proof they measure authorship rather than the gate. **Adding five frontend tests reddened a file this
slice never touched** — `DocPageEditorPage`'s two `userEvent.type` tests crossed the 20 s budget, so
the suite has a load-dependent cliff; raised to 30 s per §54's precedent and recorded in
`vitest.config.ts` as the **second raise for one cause**. **No migration; `V20` is still free.**
Before that: **§66, the `OWN` qualifier reaches the row** — §65's named next
rung, closed. `case:delete`'s `OWN` grant was serialized and **nothing read it**, so a Test Engineer
saw Delete enabled on a colleague's case and learned whose it was from a 403. **The rung was smaller
than §65 named and the blocker was elsewhere**: only the case detail has a delete affordance at all
(the grid has none, and none of the bulk dialog's eight actions is a delete), but the server compares
`test_case.created_by` and **that column was not on the wire in any form** — `TestCaseSummary`
carries `owner`, which is a *different question*, and the two diverge the moment a case is
reassigned. A slice that had wired the new gate to `owner` would have compiled, rendered, and refused
the wrong rows. Hence `createdBy`, served from the **same `CaseOwners` batch** as `owner` (the id
sets are unioned; a second call would double a two-query resolution to render one column). **The
nullable arm is the whole gate**: `created_by` has no `NOT NULL` and no FK, and the server answers a
null author `false` for `OWN` — *a grant covering "your own work" must not stretch to work nobody can
attribute* — so `isTheAuthor` is written out rather than left to `===`, because **two** absences are
in play (no author, no session) and loose equality would make them equal each other and hand an
unauthenticated render a Delete button on every unauthored case. **Reading the caller through
`useAuth()` broke 73 tests at once**, none about authorship: `useCan` is called by `PermissionGate`
which is called by two dozen components, so requiring `<AuthProvider>` everywhere is a large coupling
increase bought for one arm of one grant — hence `useCurrentUserId`, the same read, **non-throwing**,
whose `undefined` is a real answer (no caller matches no author) rather than a swallowed error.
`PermissionGate` gained `authorId` and two decisions: **`!== undefined` rather than truthiness**
(`null` is a *supplied* author and must reach the arm that refuses it), and it **throws** when
combined with `projectId="company"` — one of the two arguments would have to be ignored, so neither
is. **The finding is this slice's own test file**: `CaseDetailPage.test.tsx` exists because nothing
proved the page *passes* the author, and a call site that drops it falls back to the unqualified
verdict silently — and **the first draft passed all four assertions against exactly that mutation**,
in 44 ms, because `useCan` is fail-closed while pending and `waitFor(…toBeDisabled())` is satisfied
immediately by the loading state. §62's lesson one level in, caught by running the mutation rather
than by reading the test; hence a `permissionsResolved()` barrier. Four mutations run, one
(`createdBy` ← `owner`) reddening **exactly 4** backend tests — possible only because the fixture now
seeds the two columns **disagreeing**, §64's rule. **The mock stopped deferring the `OWN` refusal**,
a gap it had recorded in prose pending exactly these two halves. `npm run build` caught a type error
the test runner did not. **No migration; `V20` is still free.** Before that: **§65, permission qualifiers on the wire** — the rung §61 named
and deferred. `/me/permissions` was **keys-only**, so §31's disable-don't-hide was
**structurally unfollowable** for a qualified grant: a Test Engineer holds `artifact:edit` at
`PLAN_SCOPE`, the project-scope control enabled, and the server refused the submit — a defect
shipped knowingly in §61 with the mitigation written into three javadoc blocks. The response now
carries `[{key, qualifier}]`. **The real content is that two computations became one**:
`evaluate` and `qualifierOf` resolved the *same three terms* with the *same* replace-don't-union
boundary, written out twice, and **nothing failed if one copy was edited** — `qualifierOf`'s own
comment said *"it has to be the same or the two disagree"*, which is a rule maintained by a comment
saying it must be maintained. `PermissionSet` is now `key → qualifier` and `qualifierOf` is a
**delegation**; **four repository queries were deleted**, and none of them had a direct test, which
is why the duplication survived. `DISTINCT` covers **both** columns deliberately — one key can
legitimately return twice, and `DISTINCT ON (key)` would make **an authorization decision by row
order**. Two consequences stated rather than discovered: `strongest`'s throw on `OWN`+`PLAN_SCOPE`
now fires on **any** resolution rather than at one gate (correct — a key whose qualifier is
inexpressible has nothing honest to serialize), and **`NONE` is unrepresentable**, `PermissionSet.of`
throwing rather than holding a key that covers nothing. The wire shape **replaces** the key array
rather than adding a parallel map, so the compiler named every consumer: five, one real and four
test helpers. **The existing tests caught the slice's own defect** — the dialog moves the form off a
scope the caller may not use, and `canOnScope` is fail-closed **while pending**, so the first
implementation silently demoted *every* caller's default during the load; two unrelated pre-existing
tests went red because the form submitted at the wrong scope rather than showing anything. Hence
`Gate.pending` — *"not yet" is not "no"*. Also: five test files mock `/me/permissions` through an
`as never` cast, so the shape change broke them with **zero type errors**, surfacing as
`pointer-events: none` three assertions later; fixed by one shared `heldPermissions` helper.
**Two standing verification queues now exist** (`docs/verification/`) — browser passes and backend
mutation checks are **deferred and batched** at the user's direction, functionality first while
requirements move. The bar moved rather than dropped: a slice must still state the mutation and the
flow, in a file built to be run. **§65 is therefore NOT browser-verified**, and the highest-risk
queued check is not the feature — the response shape changed under **eleven** gated affordances at
once and `useCan` falls **open** on failure, so a missed consumer looks *normal*. The one mutation
run before the policy: hard-coding `ALL` reddens **exactly 8 tests** across both qualifier families
and nothing else. **No migration; `V20` is still free.** Before that: **§64, the shared-step library** — M1.5's other half, and the
last dead hook §61 named. `useSharedSteps` had **zero call sites** and the endpoint had **no
server**: dead on both sides at once, which is why neither noticed. The finding is that it was not
merely dead — **`TestStepInput.sharedStepId` has been accepted and persisted UNCHECKED since M1**,
beside a `suiteId` and a step `id` that are each validated against a set with a paragraph of javadoc
saying why. The identical argument was never made for the column between them, so an id **naming
nothing** answered **500** from the foreign key where a field error belongs, and an id naming a
**sibling project's** entry **satisfied the key and was stored** — after which every read of the case
resolves a library entry through a project the reader holds no grant on. The general shape: *a field
whose validator needs a table that does not exist yet gets no validator, and nothing goes back for
it.* The rule is a **UNION** — live in this project **or** already referenced by this case's stored
steps — and the second half is the subtle one: retiring an entry is a soft delete **because
references survive it**, so the naive check would make **every case using a retired entry
unsaveable**, which is worse than the defect it closes. `CaseEdit.of` passes the stored ids and **no
library at all**, or a bulk `MOVE_TO_SUITE` over such a case would be skipped as "cannot validate its
own content". The other decision is **`usageCount`, which is DERIVED**: the column exists, nothing
maintains it, and `ConditionRepository`'s `ORDER BY usageCount DESC` has therefore **never once
ranked anything**. Denormalizing needs every path that rewrites a step list to recompute it — and a
case edit **replaces the list wholesale** — so it drifts on the first path that forgets. The query
counts instead, over **distinct live cases** (§6e's rule about conflating counts) and the column is
**not mapped at all**. The mutation guard is in the fixture, not the assertion: `usage_count` is
seeded to values that **contradict** the references, because seeded to the derived answer both
implementations pass. **`library:manage` got its first consumer in Java** — §62's `case:approve`
shape again, three slices later. **The live pass found the defect no gate could**: the refusal was
correct (400, not 500) and carried a **second, contradictory message** — *"Add at least one step"* to
an author who had just added one — because `normalizeSteps` **drops** a step it has already
complained about, so the emptiness check read "nothing survived validation" as "nothing was sent". It
**predates the slice** (a blank action and an unknown step id are dropped the same way) and is swept
across all three. Live: a sibling project's real entry refused with one message, the picker reading
**"1 case"** while psql shows `usage_count = 0` on that row, and 0 client errors. **`/reports` is now
the only dead contract left, and it is M2.5's** — a 7–8 week milestone (`ExecutionFact`, `ReportModel`,
headless-Chromium renderers, an async job pipeline), checked and deliberately not started. **No
migration; `V20` is still free.** Before that: **§63, the project's own access screen.** Before that:
**§62, `cases/bulk`** — the last MSW-only surface with a live
consumer, closed. `BulkActionDialog` has called this endpoint since Phase 1 and **no controller had
ever served it**; all eight declared actions are now implemented. The finding is that **`SET_STATUS`
cannot be gated on `case:edit`**: the roles spec grants `case:approve` to Lead and up and explicitly
**not** to Test Engineer, and plan §6g says the state machine has exactly one gate held by
`*:approve` — so the obvious gate, the one every neighbouring write in the module uses, lets a Test
Engineer **approve two hundred cases in one click**, silently, because they hold the permission the
gate names. §61's shape again: a widening, not an outage. **`case:approve` had zero consumers in
Java**, seeded by `V3` and correctly granted since M0 and never once asked. Mutation-verified —
reverting the gate to a constant leaves 35 of 37 green and reddens exactly the two approve tests.
Two consequences: **`lifecycle_status` had no writer at all** before this (`CaseUpdateRequest` omits
it deliberately — *"a status an ordinary edit can set is an approval anyone grants themselves by
saving"*), so this endpoint is the product's whole approval path; and **`APPROVED` is reachable only
from `IN_REVIEW`**, or the gate would guard a door standing beside an open one. The second decision
is the version hash: `MOVE_TO_SUITE` and `SET_AUTOMATION_STATUS` change fields `CaseVersions.hash`
digests, so they **write version rows** — skipping that is silent and surfaces as the *next* person's
save being credited with the bulk change. `CaseEdit.of` reconstructs the edit by **round-tripping
through `CaseEdit.validate`** rather than reading the row's getters, because a second normalizer
would make every hash disagree; pinned by a round-trip test, since the failure is a hash equality no
reading finds. **The live pass found the defect every gate missed**: `PermissionGate` **cannot be used
inside a MUI `Select`** — `Select` clones its *direct* children to inject `role="option"` and
`data-value`, so the wrapped APPROVED item rendered as an inert `role="menuitem"` that was
**impossible to choose**, and a Company Admin holding `case:approve` could not approve anything.
The headline feature was unreachable with build, lint and 452 tests green. The component test passed
**for the wrong reason** — it looked the item up by `data-testid` and asked whether it was disabled,
and an element MUI never processed carries no `aria-disabled` either, so *"not disabled"* and *"not
an option at all"* read identically. Tests now assert through `role="option"` and count options
against `lifecycleLabels`. Also: the MSW mock **dropped ids it could not resolve** (`if (!row)
continue`), so a selection of 44 was accounted for as 42 — `changed + unchanged + skipped == total`
is now an invariant a test pins. `CaseRenderer` extracts the render method `CaseCreateService` and
`CaseUpdateService` each carried a byte-identical copy of, each arguing in javadoc against a third.
§19's fixture-collision defect appeared a **fifth** time. **No migration; `V20` is still free.**
Before that: **§61, the artifact register** — the nav rail has offered
"Artifacts" since Phase 1 and live mode had **nothing behind it**, so the link was dead on the
deployed box; there is a server there now, and a writer with it, because shipping the read alone is
what §54, §55, §56 and §57 each recorded as the mistake. The writer is why this touched the
**authorization core**. The roles spec has said `artifact:edit` is *"plan scope only"* for a Test
Engineer since M0, and V12's qualifier CHECK took `ALL` and `OWN` only — **and Test Engineer's grant
was `ALL`**, V12's backfill default, so this did *not* fail closed: a gate written against the old
data lets a Test Engineer rewrite the strategy every cycle inherits read-only (§6n). Hence **`V19`**,
and hence the interesting part: **`OWN` and `PLAN_SCOPE` do not compare** — one restricts by who
wrote the row, the other by what scope it sits at, so the union a caller holding both would have is
not expressible in one value. `strongest` was `Enum#compareTo` over a declaration order, which after
V19 is *an authorization decision taken by declaration order*; it is now written out and **throws**
on that pair, kept unreachable by a **computed `SeedDataTest` check** that no key carries two
different restrictive qualifiers. Before V19 that held **vacuously** — §50's shape again, a property
true by accident and invisible where it was written. Adding the value made `canDoOnAuthored`'s switch
non-exhaustive under `-Werror`, so the compiler made every consumer decide. **§31's
disable-don't-hide could not be followed** and that is recorded rather than papered over: a Test
Engineer *holds* `artifact:edit`, `/me/permissions` is **keys-only**, so the button enables and the
server answers that one refusal **with a body** naming `scope` — the sentence lands on the selector
the user is one choice from getting right, while a Viewer's stays bodiless. Putting qualifiers on the
wire is the real fix and is its own slice. `documentation` gained `identity` (the register renders
`updatedBy`), which **falsifies its own package comment** — "nothing here renders a person" was true
of the docs space and never of the register beside it; both it and `ModuleStructureTest`'s copy now
record the reversal. `execution` is still not a dependency: `PlanFacts` reads `test_plan` through one
file, the fourth `*Facts` instance. **The live pass proved it end to end** — `V19` applied
**v18 → v19 in place**, a Test Engineer was refused `PROJECT` scope with the message on the control
and accepted at `PLAN` scope, and psql showed her row exists **once**. **No defect on the primary
surface**, the second slice in eight. Before that: **§60, the clock that out-resolved its database** — §58's
carried debt is closed and **no test is `@Disabled` any more**: 1078 tests, 0 failures, **0 skipped**.
`TcmsApplication#clock()` is now `Clock.tick(Clock.systemUTC(), 1000ns)`, because every instant it
stamps is written to a Postgres `timestamptz` that stores microseconds while `Instant` carries
nanoseconds — so a value both *returned in memory* and *read back* was two different values, which is
what made `provision-company` print an expiry that was not the stored one. **The fix is a no-op on
macOS**, where `systemUTC()` already yields only microseconds (re-measured: 20000/20000 samples on a
whole microsecond), so `ClockResolutionTest` asserts what the bean *is* and not only what it does —
the behavioural round-trip assertion **passes on this machine with the defect present**, measured.
The sweep is the other half: `ExecutionService.record` (an `executedAt` both persisted and returned —
the identical defect) and `SessionTokens.issue` were calling `Instant.now()` and bypassing the bean
entirely, so `ArchitectureRulesTest.nothingCallsNowDirectly` now refuses a direct wall-clock read in
production code, **verified by reintroducing the defect and watching it go red**. §48's shape again:
a catalogue-wide invariant needs a computed check, and grep-at-review-time is exactly what missed
these two for the whole life of both modules. Before that: **§59, `issue-invitation`** — written up
this session, because it had shipped with **no handoff section at all** while the index still ended
at §58. Deploying found that an invitation raised from the UI on an instance with **no SMTP relay was
undeliverable by anybody**: the token is stored only as a digest, the response carries no URL, the
unconfigured mailer logs no link, and `provision-company` neither replaces an outstanding invitation
nor addresses anyone but the first admin — five correct decisions composing into a dead end. Hence
`InvitationService.reissue` and an operator CLI verb. **The same slice narrowed CI to
`./gradlew build -x test`** (~327s → ~60s), so **no machine anywhere runs the 1000+ integration tests
now** — which is precisely why §60 spent its effort on a rule rather than a fix. Before that:
**§58, the first real deployment** — the box exists.
Hetzner CX23 at `203.0.113.10` (Helsinki), Ubuntu 24.04.4, serving
`https://tcms.203-0-113-10.sslip.io` behind Caddy on a Let's Encrypt cert, with Postgres 17.10
host-native and the two-role split live: `tcms_owner` and `tcms_app` both `rolsuper=f`,
`rolbypassrls=f`, database owned by the migration role. **The product is now on it** (updated
2026-08-02): `0.1.1` deployed from GHCR, 18 migrations all `installed_by=tcms_owner`, the SPA behind
Caddy, mail relaying, and **every RLS row of the runbook's checklist green on the real box** —
`tcms_app` owns 0 objects, 56 tables FORCE RLS, a context-less `SELECT` returns 0 rows on both a
cold and a warm connection, and `ALTER TABLE … DISABLE ROW LEVEL SECURITY` as `tcms_app` is refused.
The runbook was authored 2026-07-30 and marked *not yet executed*; executing it found
**seven errors in it** (E1–E7, now folded into the procedure as traps A–E), and the shape of two of them is the finding: each presents as a symptom the
document already explains, attributed to the wrong cause. **E2** — `ufw` drops container→host
Postgres on `docker0`, presenting as the connection timeout §6 blames on a wrong bridge subnet (the
bridge was right). **E3** — §9's `log` block cannot start under the packaged Caddy unit's
`ProtectSystem=full` sandbox, and `caddy validate` says *"Valid configuration"*, and
`chown caddy:caddy` makes `sudo -u caddy touch` succeed while the service stays down; the fix is a
`LogsDirectory=` drop-in. **E1 is the one that generalises**: §6 carried a third copy of the
privilege-split SQL under the instruction *"keep the two in step, they are the same contract"* — and
it had already drifted, missing the `GRANT EXECUTE ON FUNCTIONS` that V7's `SECURITY DEFINER`
function needs. V1–V5 would still have come up green, because V7 re-grants itself; the *next* such
function would have failed at runtime, in a component with no test that could see it. The fix was to
**delete** §6's copy rather than correct it. Same family as §48. Before that: **§57, the
`documentation` module** — the biggest MSW-only surface, closed. `GET`/`POST /doc-pages`, `GET`/`PATCH`/`DELETE /doc-pages/{id}`,
`PUT /doc-pages/{id}/status` and **`V18`**, which seeds the `doc_space` that `doc_page.space_id` has
required since `V1` and **nothing had ever created** — the documentation module's `V5`, found by the
first code that needed it. Three sessions in a row (§54, §55, §56) recorded that these reads needed
a **writer** to be honest, and two earlier slices had already pointed here by name: `DisplayKeys`
reserves `DOC_PAGE` for a module that did not exist, and `TestCaseDetailView` omits
`relatedDocuments` *because* it did not. **The slice is one decision.** `V1` says `doc_block.id` is
immutable and never derived from content, because `doc_link` targets `(page_id, block_id)` and
requirements *are* blocks — so "replace the blocks" cannot be delete-and-recreate. That
implementation passes every assertion about text, order and type while **voiding every link on the
page silently**, since the links cascade rather than dangle. `PageContent` decides identity once: a
submitted block carrying an id **is** that block, an unknown id is a 400 rather than a create, and a
removed block something links to is a 409 raised *before* anything is written. The client is
load-bearing here — the server cannot refuse an id that was never *sent* — and the way it breaks is
a framework quirk: `useFieldArray` shadows a data property called `id` with its own render key, so
reading `field.id` submits a fresh uuid per block per save and passes any test that only checks the
text came back. Hence `blockId`, and payload assertions rather than render assertions. **`coverage`
is absent rather than zeroed** (the call `TestCaseDetailView` already made) — and the live pass found
the one place that was not honoured: the page-level chip read **"1 of 1 unlinked" on a page whose
heading demonstrably had a link**. Two more live-only defects: an empty documentation space rendered
a **permanent loading skeleton** (a disabled query is pending, and no mock fixture has an empty
space), and **a delete that SUCCEEDED reported "Not found"** because `removeQueries` on a key with a
mounted observer forces a refetch. Before that: **§56, removing a person** — `DELETE /users/{userId}`,
the membership goes `REMOVED` and nothing is deleted, the session epoch is bumped (the first caller
`AppUser.revokeSessions()` said did not exist), and the defect it found was **the token that outlived
the status that made it unusable** — the exact mirror of §55's dead link. Before that: **§55, inviting a colleague** — `POST /users`, so a company can
finally add a person to itself. Nine sessions named this and §54 sharpened it: the product could show
you who was on a project and let you staff one, and the only way to get a *person* in was an operator
with a psql prompt — §54's own live fixture needed three `INSERT`s. **No migration**; the tokens, the
mailer and the accept flow have been there since §18, and `InvitationService`'s javadoc already named
*"a future invite-a-colleague endpoint"*. So the slice is decisions, not plumbing: the response
**cannot say whether the account already existed** (`app_user` is instance-level with a unique email,
so a differing answer enumerates the instance one guess at a time), a `REMOVED` membership must be
reset or the product sends **a link it then rejects**, and the input carries **no `roleId`** because
inviting and granting are two acts with two gates. The mutation that proves the disclosure rule is
the non-obvious one — seeding the membership from `canonicalName` passes every fresh-account test,
because for a fresh account the two strings are equal, and fails only against a **reused** account.
**The live pass found the defect no gate could**: `emailed: false` has *three* causes and the body
expressed one, so on an install **with** a relay a second click told the administrator their mail was
broken — `issued` is now on the wire and the three-state test exists because the two-state one passed.
Found by clicking Send twice. Before that: **§54, the board's three gaps** — `GET /projects/{projectId}/members`, `Idempotency-Key` on `POST /work-items` with `V17` behind it, and `BLOCKED` as a real fifth column. The members endpoint is not plumbing: the server already validated `assigneeId` against *active member ∧ project grant*, so the picker had to offer **that same set** rather than one that agrees with it — the predicate now lives in one place and a test drives both sides of it. It cannot be a query over `role_assignment`: that finds only direct grants, so it silently omits everybody staffed through a **team** and every **Company Admin**, and answers 200 with a plausible list. `BLOCKED` needed the value, the label *and* the column together, because `satisfies readonly WorkItemStatus[]` rejects a non-status and **accepts a subset** — only one direction was ever checked. The live pass found **no defect**, the first slice in five where it did not; it did prove the whole predicate against a four-person fixture and `V17` migrating an already-at-v16 database in place. Along the way: three mock defects of the "mock agrees with itself" class, §19's fixture-collision defect a **third** time (the prefix *space* is hand-managed and `c0` was taken), and a **pre-existing suite-wide flake** — 1–6 random failures per full run, all reading "Test timed out in 5000ms" rather than an assertion, fixed by raising `testTimeout`; the first attempt raised RTL's `asyncUtilTimeout` to the same value and made it worse. Before that: **§53, the `work` module and the `case_health` writer** — the kanban board runs on a real server, `case_health` has its first writer after six slices of being read and never written, and §52's named next rung — *every URL the SPA calls resolves to a declared operation with that method* — is built and immediately caught the defect this slice opened with: `POST`/`PATCH /work-items` had been called since Phase 1 and **declared nowhere**. The live pass found the one thing no gate could — both derived card types rendered with **no reason on them**, because the server sent `description` and the card never rendered it. Before that: **§52, the suite execution timeline** — and it landed alongside a concurrent strand's **§51, the open-question decision round** — no code, nine of plan §8's outstanding questions settled, **two of which turned out to be answered already by the code and the contract**. ~~The next slice is **M2 execution writes**: recording a result is the last hop still running on MSW.~~ → **superseded in §52**: recording a result has been live since §38, and §52's live pass drove `PATCH /runs/{runId}/executions/{executionId}` eleven times against the real server to build its fixture. ~~The MSW-only surfaces are `documentation`, `reporting` and `work`~~ → **superseded in §53**: `work` went live in that slice, so the MSW-only surfaces are now `documentation` and `reporting` (`/doc-pages`, `/artifacts`, `/reports`, plus `cases/bulk`). → **superseded in §57**: `documentation` went live in that slice, so the MSW-only surfaces are now `reporting` and `artifacts` (`/artifacts`, `/reports`, `cases/bulk`, `shared-steps`). This is §46's "a 'Not done' note decays" a second time, inside a day. → **superseded in §61**: `/artifacts` went live in that slice, so what remains is **`cases/bulk`** (the only one with a live consumer — `BulkActionDialog`, and therefore the obvious next rung) plus **two dead ends** §61's orientation pass identified and did not touch: `/reports` has **no hook at all**, and `useSharedSteps` has **zero call sites** (§35's dead-hook shape again). Both should be **deleted or built** rather than left as contract that reads as implemented. → **superseded in §62**: `cases/bulk` went live in that slice, so **`reporting` is the last MSW-only surface** and it is one of the two dead ends — `/reports` still has no hook, and `useSharedSteps` still has no call site. Every contract path with a live consumer now reaches a real server. Before that: **§50, the query-parameter checks at both ends**. Before that: **§49, the case execution timeline**. Before that: **§48, the contract-hygiene computed checks**. Before that: **§47, the repository dashboard**. Before that: **the third and final parallel run** — §42 · §43 · §44, with §45
recording it **and the decision to stop running lanes**. Work is **sequential**. That was
re-opened on 2026-08-02 — a cap of 4 agents with a safety gate — and **reversed the same day**, not
on §45's cost argument but on a correctness incident: two agents in one tree produced **three silent
losses of committed work** (a rebase, an unrelated commit reverting a doc as collateral, a stale
re-application wiping a later change), with no conflict, no error and a clean `git status`
throughout. The failure mode is *silent*, so the price of getting coordination slightly wrong is not
a conflict to resolve but work that quietly disappears. The parallel plan's **§0** carries the
account; §1–§8 stand as analysis. The M3/M4 fan-outs in §5 remain the only shape that would justify
reopening it, and the bar is now "can a silent loss be **detected**", not "can the files be
de-conflicted".)
**State:** Planning and design complete **including multi-company tenancy**. **Backend M0 is COMPLETE**, and **M0.5 now has a usable login** (§18): invitation → activation → sign-in works end to end, `CompanyContext` finally has a production entry point, and the permission evaluator answers over HTTP. **The instance is reachable for the first time.** Frontend complete through **Phase 3** and aligned with the multi-company decisions (§9), and **the two halves have now met (§20)**: `VITE_API_MODE=live` points the SPA at the real backend through a dev proxy, `/accept-invitation` redeems a real CLI-issued link, and a real session survives a reload. **As of §25 the SPA's biggest screen renders live**: the case grid, its suite tree, its facet chips and its filters all run against the real API. **As of §26 that screen raises no errors at all** — its condition library is served, so nothing on it 404s. **As of §27 the case *detail* screen is live too, including §6f's suite-condition inheritance** — which makes the `repository` module's read surface complete. **As of §28 the product has its first WRITE**: editing a case is live end to end, `test_case_version` has a writer so §6j history actually accumulates, `audit_event` has its first row, and the write gate is a permission (`case:edit`) rather than the read gate every role holds. **As of §29 a case can be CREATED live**: `TC-n` is allocated from the company's `key_sequence` by the one non-racing statement, v1 is written with the case so a created case has no history gap, and the `Idempotency-Key` the SPA has sent since Phase 1 is finally read — a retry returns the case it already made. **As of §30 a provisioned company is finally usable without hand-written SQL**: suites can be created, renamed and deleted from the tree, both halves in one slice, so `useCreateSuite` — dead in the data module since Phase 1 — has its first consumer. **As of §31 the SPA finally asks what the caller may do**: `GET /me/permissions` — in the contract and implemented since the mock era, and never once called — now gates eleven write affordances, so an action the server will refuse renders disabled with a reason instead of inviting a 403. **As of §34 a company can create its own projects** — the blocker named in §31, §32 and §33 is closed, so "provisioned company" no longer means "provisioned company plus hand-written SQL". The gate is `project:create` at **COMPANY scope**, one level above the project, because there is no project grant for a project that does not exist yet. **As of §35 the oldest dead hook has a caller**: `useCaseVersions` was seven slices old with zero call sites, and the case detail now renders its authored history, gap and all. **As of §36 the frontend has a test runner and 203 tests** — Vitest + RTL, mutation-checked rather than watched to pass, and wired into CI at integration. **As of §38 the `execution` module exists at all** — plans, runs, executions and the recorded result are live, and the module crosses into `repository` through exactly one class that reads the *pinned snapshot* rather than the live case, so an execution shows what it ran. **As of §39 a company can staff its own projects**: `POST`/`DELETE /role-assignments` mean a colleague who signs in is no longer guaranteed an empty project list, which was the last blocker of the §34 shape. **As of §40 Phase 5 has finally been run** — the hostile review found four majors, all by driving the app and none by reading it, two of which had survived three previous browser passes. **As of §42 a run can be CREATED, which is what makes the product usable end to end**: `POST /plans` and `POST /runs` land, and creating a run resolves the plan's scope and writes one execution per case **pinned to that case's current version** — the first write of a `case_version_id`, and the thing that materialises the checklist a result is recorded against. It was driven live, the first live pass in three runs, and found two defects no gate could: a successful create navigating into an unimplemented route, and a `NOT_STARTED` run rendering "Still running" because until this slice **no run could reach that state**. **As of §43 `company:read` finally works at company scope** — a `Company Member` baseline granted on joining and backfilled, with the evaluator's scope model left alone. **As of §44 a sibling tab no longer lies about who is signed in**, focus revalidation is on with a written reason, and the case grid keeps its rows while you type. **As of §45 the same permission defect has been found twice in one day and turned into a computed check** (see the box below) — `V16` makes Automation Service reachable at all, which M3 ingestion depends on. **As of §46 plan statistics is live and the `execution` module is read-complete**: the endpoint §42's live pass navigated *into* and 404'd on now renders, so creating a plan lands on a working page. Two thirds of the blocker three sessions had recorded turned out to be already unblocked — coverage never needed a repository-wide denominator (§6p defines it over the plan's own scope) and `bySuite` had its seam since §24. **As of §47 the project's landing page is live and the `repository` module is read-complete**: the dashboard's tiles, breakdowns, coverage and activity chart all run against the real API, and building it found that the by-owner tiles emitted an `ownerId` the case list never bound — a link that silently opened *everyone's* cases under a heading naming one person. `documentation`, `reporting` and `work` writes still run on MSW. **As of §48 the contract is checked by machine rather than by reading** — the debt §42–§47 each recorded and none paid. Four computed checks over `openapi.yaml` found that **24 of 49 operations declared no 4xx at all** (so the generated types said a 401 could not happen on half the API) and **11 schemas declared no `required`**, flipping 29 properties from optional to required once repaired — including `PageMeta` and the case list's own `items`/`facets`. The fourth check is §47's own defect made mechanical, and its mutation left 26 of the other 27 dashboard tests green, which is the proof it was not already covered. **As of §49 the case execution timeline is live, which is the screen the version pin exists for**: the "Execution history" button the case detail has rendered since Phase 1 led to a route that 404'd, and now shows "green, green, *edited to v2*, red, red" — §6p's causation shape, driven live. Building it found **the same defect class §47 shipped and §48 made mechanical, in a place §48's check does not reach**: the SPA has always sent `?scope=CURRENT_VERSION`, the contract never declared it, and Spring silently ignores an unbound parameter — so the "Current version" chip would have answered with every version's results under a heading saying otherwise. It also found that `VersionMarker.author` was typed non-null while the column has no foreign key, and the tightened type failed the build on an **unguarded dereference that would have crashed the page**. §6p's "deleted runs must not orphan history" needed a new required field (`runDeleted`) to be expressible at all. **As of §50 the defect class §47 and §49 each named on the way out is machine-checked at both ends, and building it found the direction neither had described**: the two known cases were *SPA sends a key the contract does not declare*, and the symmetric one — *the contract declares a parameter no handler binds* — is worse, because there the reviewed spec, the generated types and autocomplete all agree with the caller while the server ignores it. Three live instances, one of them on **§49's own endpoint** (`releaseVersion` and `configurationId`, deleted rather than implemented since nothing sends them) and two created by a filter block written on the **path item**, which every operation under it inherits — so `POST /cases` declared the case list's whole fourteen-parameter query. The SPA-side check drives the **TypeScript compiler** rather than reading text, because the case list spreads `...filters` and those fourteen keys are a *type*. **No runtime code changed in this slice**; all three defects were latent in the contract and the generated types.
**As of §52 the suite execution timeline is live and the page finally has a way in** — nothing had linked to `/suites/{id}/history` since Phase 1. The orientation pass found the defect before any code: the SPA declared its own `{ runs: … }` response type at the call site while the contract said `entries`, and the MSW handler was written from the same hand-made shape, so **mock mode agreed with itself and live mode would have rendered nothing**. The lesson is that `api.get<T>` is an *assertion*, not a comparison — only a **generated** type puts the contract on the other side of it — and `apiResponseTypes.test.ts` now refuses an inline literal at all 45 `api.*` call sites. Driving it found two more, both shared rather than local: **every table in the app was clipped instead of scrollable** (`Panel`'s `overflow: hidden`, so all eight of them), and **signing out and in as somebody else in the same tab kept the previous user's data** — §44 built that protection and it ran for every tab except the one the user was looking at, because `BroadcastChannel` does not deliver to its sender.
**As of §55 a provisioned company can add its own people**, which closes the last leg of the staffing loop (§34 projects → §55 people → §39 grants → §54 the picker). `company:members` is defined as "invite, remove, set membership display names"; this is the first third, and **removing a person is still not possible over HTTP** — `CompanyMembership.remove()` and `rename()` have no caller, which is §28's dead-code question with one more instance. → **half superseded in §56**: `remove()` has a caller now (`DELETE /users/{userId}`); `rename()` still does not.
**As of §57 the `documentation` module exists and its reads are honest**, which closes the surface §54, §55 and §56 each recorded as the biggest MSW-only one. A specification page can be written, edited, published, archived and deleted against a real server; `V18` seeds the `doc_space` that `doc_page.space_id` has required since `V1` and nothing had ever created. The module's whole design is that **a block keeps its id through an edit** — links target `(page_id, block_id)`, so delete-and-recreate would void every case's traceability silently — and the refusal that protects it is built *before* anything can create a link, so the invariant precedes the data rather than being retrofitted onto it.
**Build status:** `backend` — `./gradlew build` clean, **1378 tests, 0 failures, 0 skipped against Testcontainers Postgres 17** (§74 added 30, §73 23, §72 23 — 20 written plus 3 the Modulith and contract checks derive, §71 36, §69 25, §68 19, §67 17, §66 4, §65 3, §64 43, §62 45, §61 33), migrating as a **non-superuser owner** (§19). The *skipped* count is worth reading: it was 2 from 2026-08-01 to 2026-08-02 while §58's clock defect was open, and §60 closed it. **Nothing but this local gate runs these tests** — CI has run `build -x test` since §59. `frontend` — `npm run build` clean, `npm run lint` 0 warnings, **`npm run test:run` 584/584** (§74 adds 10 written + **2 generated**, §73 7 written + 4 generated, §72 7 written + **2 generated** — the generated ones being 1 new `api.*` call site × the 2 per-call-site contract checks; §71 8 written + 10 generated; §70 16, all generated). **The generated arithmetic was checked rather than noted, which is §70's lesson applied on its first opportunity — and again in §72, where 1 new call site produced exactly 2 generated cases.** ~~four tests came from somewhere this slice did not count — noted rather than reconciled~~ → **reconciled in §70, and the answer was a defect**: the three contract-hygiene checks emit one `it.each` case per discovered `api.*` call site, so a slice that adds endpoints adds tests it did not write — but §69's *three* new endpoints produced only *four* cases across two checks, because the third was an `api.del` and **both checks were looking for `api.delete`, which has never existed**. Repairing the verb set added the sixteen cases above, covering eight call sites that had never been checked at all. **A test appearing without an author was worth the one grep**, and the general form is now recorded: in a suite with data-driven checks, an unexplained count *change* is a report about the checks themselves. **§67 raised `testTimeout` 20s → 30s, the SECOND raise for one cause**: adding five tests pushed `DocPageEditorPage`'s two `userEvent.type` tests over the budget — a file that slice never touched — so the suite has a **load-dependent cliff** and any slice adding tests anywhere can redden an unrelated file. §54's precedent, recorded in `vitest.config.ts` with the real fix named (those two tests type sentences character-by-character; `paste` would assert the same thing) and left out of scope. **Browser passes are DEFERRED since §65 — see the box above; §65, §66, §67, §68, §69, §71, §72, §73 and §74 are all built and NOT browser-verified.** §71's queue rows lead with the **live-only** ones, because mock mode has shown three teams since Phase 1 and live mode has shown **none, ever**. **Last driven in a real browser in LIVE mode in §62**, which found a defect **only the browser could see** — `PermissionGate` inside a MUI `Select` renders an item MUI never processes, so the APPROVED option was unselectable and a Company Admin could not approve anything, with every gate green. Before that, §61's pass found **no defect on the primary surface** and did prove the qualifier end to end: a Test Engineer refused `PROJECT` scope with the sentence on the "Belongs to" selector and accepted at `PLAN` scope, with psql confirming her row exists once. It also left two things **found and not fixed**, both recorded in §61: the register's plan filter is `useState` rather than the URL, so **a plan's register cannot be linked to** while every other filtered screen can; and ~~`issue-invitation` will re-invite an **ACTIVE** account~~ — **retracted the same session, there is no defect**: every path already refuses it, and the report confused instance-level `app_user.status` with per-company `company_membership.status`, which are routinely different because one person belongs to several companies (§6s). §61 keeps the mistake as the trap it is. (§57's live pass found three defects no gate could see — a permanent loading skeleton on an empty documentation space, a coverage chip claiming "1 of 1 unlinked" about a block that had a link, and a delete that succeeded reporting "Not found" — and proved the slice's central claim in psql: a typo fixed in a linked heading left the block id byte-identical and the `doc_link` intact.) (§56's live pass proved the removal, the revoked session and the cancelled invitation in psql; §55's found the three-state defect by clicking Send twice.) (§54's live pass was the one slice in five that found nothing.) (§53's live pass found the one no gate could see:  both derived card types rendered **without the reason the server was already sending**, because `WorkCard` never rendered `description` while the backend test asserting its contents passed.) (§52's live pass caught two on the sibling screen: every table in the app was clipped rather than scrollable, and a sign-out/sign-in in one tab kept the previous user's data.) (§49's live pass caught the copy defect on the sibling screen: every case timeline ended with "Edited to v1", a change nobody made, on the screen whose whole job is answering "what changed?".) (§47's live pass caught the sibling: MUI turns a unitless `minHeight: 1` into `100%`, so every zero-activity day drew a full-height bar.) **`docker compose up` serves provision → accept-in-the-browser → sign in against a non-superuser owner** (§18, §20), and its aggregate `/actuator/health` now reports **UP** — the `mail`-indicator defect §20 found is **fixed in §21**. CI runs both gates on GitHub Actions.
**Branch:** `master`. Per the git workflow, **work continues on the checked-out branch; do not branch per task** — and since §45 ended the parallel experiment, the concurrency-worktree exception should not be needed. The two worktrees §45 used were merged; prune anything left under `.claude/worktrees/`. Last commit: §74, the plan editor. **No migration in §59 or §60** — both are behaviour and configuration only. **Migrations now run to `V21`** — §71 added it: `uq_team_name` becomes a unique **index** over `(company_id, lower(name))`, replacing `V1`'s exact-match table constraint, which had **never adjudicated a single insert** because nothing could create a team. An application check stricter than its constraint is a check with no backstop. Before it, §69's `V20` added a partial, per-parent, case-insensitive unique index on `classification_value`. **`V22` is the next free number** — neither §73 nor §74 added one: §74's `entry_criteria`, `exit_criteria` and the whole `test_plan_version` table have been in `V1` from the beginning, the table simply gets its first entity and its first row, and putting `@Version` on `lock_version` changes no DDL. Before them, §72 added none either: `test_run.status`, `started_at`, `completed_at` and `updated_by` have all been in `V1` from the beginning, and `updated_by` is merely *mapped* for the first time. Before it, §61's `V19` taught `role_permission.qualifier` the `PLAN_SCOPE` value the roles spec has needed since M0. **Neither §62, §64, §65, §66, §67 nor §68 added a migration** — every column they touch already existed; §68's `case_classification` has been in `V1` since the beginning with three readers and no writer; §67's `evidence` and `attachment` tables the same, waiting for a first reader and writer; §66's `test_case.created_by` has been written since M1 and was simply never served; §64's `shared_step` table has been in `V1` since the beginning, waiting for its first reader and writer. `V19` was verified applying to an **already-migrated** database (v18 → v19, `installed_by=tcms_owner`) in §61's live pass, and `V18` the same way (v17 → v18, re-seeding 8 existing projects) in §57's — the one thing Testcontainers structurally cannot check, because it migrates a fresh container every time.

> **⚠ Browser passes and backend mutation checks are DEFERRED AND BATCHED — §65, standing.**
> Set 2026-08-02 at the user's direction, to deliver the remaining functionality first while
> requirements are still moving, then verify in one concentrated pass. Two queues:
> [`docs/verification/frontend-browser-checks.md`](verification/frontend-browser-checks.md) and
> [`docs/verification/backend-mutation-checks.md`](verification/backend-mutation-checks.md).
>
> **A slice must still state the mutation it would have run and the flow it would have driven** —
> appended to the right queue, in a form that can be executed later. A slice that lands without
> adding its rows has skipped the check outright, which is the thing batching is meant to avoid.
>
> **This reverses a standing rule and the risk is the reversal.** `CLAUDE.md`: *"the gates prove it
> compiles, not that it works."* The live pass has found a defect **no gate could see** in most
> slices since §42 — §62's unselectable option, §57's permanent skeleton, §53's reasonless card,
> §52's clipped tables, §64's contradictory messages. Deferring does not make those less likely; it
> makes them arrive **later and in a batch**. **Until a queue is run, the slices in it are built and
> unverified** — say so, rather than calling them done. §65 is the first, §66 the second,
> §67 the third, §68 the fourth, §69 the fifth, §71 the sixth, §72 the seventh, §73 the eighth,
> §74 the ninth, §75 the tenth; **§76 is the eleventh, and its first row is live-only AND
> unrepeatable** — on a real instance no artifact has a review row at all, including the ones
> already `IN_REVIEW`, and that state disappears the moment anything is submitted. §75's
> **live-only rows lead for §72's reason exactly** — every
> suite on a real instance is a draft and always has been, while the mock's fixtures have shipped
> approved folders since Phase 1, so mock mode will look unchanged by the slice and live mode
> changes completely. **§74's live-only rows lead for a NEW reason** — the mock's fixtures have
> carried hand-written exit criteria since Phase 1 while the columns had no writer at all, so **mock
> mode has been showing a feature the product did not have**. That is §52's "mock agrees with
> itself" from the opposite direction. §72's rows lead because its
> live-only rows lead for the INVERSE of §71's reason — the state it fixes is one **live mode has
> always been in and mock mode has essentially never been in**, because the mock's fixtures ship
> runs that already started. **§70 owes nothing** — it changed only the two
> contract checks and the module they share, which no bundle imports, so its queue row records that
> decision rather than leaving the slice silently absent. Both slices' queue rows name their **live-mode-only**
> checks up front — §67's because mock mode serves `url` as a `blob:` URI, §68's because the mock
> fixture has no *retired* classification value, so the rows covering the reconcile's bound cannot
> be driven in mock mode at all and it will look correct either way.

> **✅ The `company:read` gap is closed — §43** — by the baseline COMPANY-scope grant, a seventh
> built-in role (`Company Member`) that is deliberately **invisible on the wire**. The evaluator's
> scope model is unchanged; the rejected "held anywhere in this company" alternative is recorded in
> the roles spec §A3 with its reasoning.
>
> **⚠ It happened a second time the same day, and that is the lesson — §45.** Automation Service
> held its five §A6 capabilities and no `project:read`, and every project-scoped path asks
> `ProjectAccess.canView` — which *is* `project:read` — **before** asking the permission. So the
> role 404'd on everything and was inert over HTTP; `canView`'s own javadoc justified the method on
> a claim about the six roles that was **false when written**. Repaired by `V16`. **Both defects
> were invisible to every reading of `V3`, because the defect is an absent row.** The rule is now a
> computed check over the catalogue (`SeedDataTest`), and the repair for this family is always
> *grant the missing permission*, never *weaken the gate*.

> **⚠ A file-sync daemon on this machine creates `<name> 2.<ext>` conflict copies, and they BREAK
> THE BUILD — in TWO ways. The second one is a HANG, not an error (§45).** 105 `* 2.xml` copies in
> `backend/build/test-results/` are hashed by Gradle as task inputs and stall `:test` for 20 minutes
> with no output at all; `find build -name "* 2.*" -delete` and the same build took 1m49s. The
> original mode is a compile error: 14 were sitting in the tree at the start of §30 — `ProjectController 2.java` declares
> `public class ProjectController`, which is a hard `compileJava` error, so the backend did not
> compile on arrival. They are gitignored inside `build/` and untracked in `src/`, so **`git status`
> does not always show them and a clean `git status` does not mean a clean build.** They reappeared
> mid-session the moment a script bulk-deleted and recreated a directory. **Check
> `find . -name "* 2*" -not -path "./.git/*"` before trusting a build failure**, and prefer editing
> files in place over `rm -rf` + `cp -R` on a source directory.
>
> **⚠ The pattern above lost its trailing dot in §47, and that is the fix.** The command as
> originally written — `-name "* 2.*"` — **cannot match a conflict-copy DIRECTORY**, because
> directories have no extension. §47 hit the 10-minute hang with that check reporting zero matches,
> while `build/classes 2`, `build/generated 2`, `build/reports 2` and `build/resources 2` sat there;
> `jstack` on the daemon showed it parked in `DirectorySnapshotter`. `rm -rf build` fixed it.
> **Also:** two concurrent `./gradlew` invocations block on the build lock and emit *no output at
> all* while waiting, which is indistinguishable from this hang — check for a second gradle process
> before blaming the sync daemon.

> **✅ The silently-ignored query parameter is machine-checked at both ends — §50.** It had shipped
> twice (§47's dashboard tile opened everyone's cases under a heading naming one person; §49's
> "Current version" chip would have returned every version's results). Both sections ended by
> naming the same missing check and it is now built — **and it found the direction neither had
> thought of.** The two known cases were *SPA sends, contract does not declare*; the symmetric and
> worse case is **contract declares, handler does not bind**, because there every artifact agrees:
> the parameter is in the reviewed spec, generated into `schema.d.ts`, and offered by autocomplete.
> Three live instances, one of them on §49's own endpoint. `contractParameters.test.ts` drives the
> **TypeScript compiler**, not a regex — the case list spreads `...filters`, so its fourteen keys
> are a *type*, and a field added to `CaseFilters` is how the next one arrives.
>
> **A path item must declare no query parameters at all (§50).** They are inherited by every
> operation under it, so a filter block written once for the list also lands on the POST beside it.
> The rule is strict rather than "…that every operation binds" on purpose: the six benign cases are
> benign only because they have one operation each, and that accident is invisible where the
> parameter is written.
>
> **⚠ A "Not done" note decays, and §46 found one that was two-thirds wrong.** The statistics
> endpoint was recorded as blocked on a `reporting` module in the contract, in §38 and again in §45.
> By the time anyone checked, `bySuite` had had its seam since §24 and `coveragePercent` had **never**
> needed the repository-wide denominator it was credited with — §6p defines coverage over the plan's
> own scope. Three sessions inherited the claim without re-testing it. **The orientation pass's first
> question is "which of these has a consumer" (§28); its second is now "is this still blocked".**

> **⚠ Re-derive the next slice from the code, not from the last session's "Next" list — §28, and
> again in §29.** §27's three named next steps were all unsupportable; §28's own list then led with
> `/versions` again, and `useCaseVersions` **still has no consumer**. Twice in a row, the same way —
> so it is a property of writing that list at the end of a slice, not a mistake. The orientation
> pass is not optional, and its first question is *which of these has a consumer*. One grep each.

> **✅ The suite's superuser owner is gone — §19.** `IntegrationTestBase` now creates a **non-superuser
> `tcms_owner`** before Flyway, mirroring `deploy/postgres/initdb/10-tcms-roles.sql`, and `PrivilegeSplitTest`
> pins every property of the split — including that **`FORCE ROW LEVEL SECURITY` covers the owner**, the
> assertion the old harness structurally could not make and the one V7's HIGH defect turned on. The
> container's superuser is now used only for ground truth in assertions.


> **✅ The §10 concerns from the previous handoff are resolved.** The two uncommitted doc files are committed (`d08590b`) and `§6v` exists — the dangling reference is gone. §10 is retained as the record of that strand's decisions, not as an outstanding warning.
>
> **⚠ The DDL is no longer unexecuted — and running it found two defects.** See §11.
>
> **⚠ Every repository interface now needs `@Transactional`, and a test enforces it.** Without it a
> repository's *query methods* run outside any transaction, so `app.current_company` is never set and
> every RLS-protected read returns **zero rows**. Fail-closed, but it silently emptied the permission
> evaluator. See §15.

---

## How this handoff is organised

**Every section is its own file under [`docs/handoff/`](handoff/), numbered by its § number.**
A reference anywhere in this repo — `§6 lesson 5`, `handoff §31`, `see §19` — resolves by the
filename prefix. This file is the index and the current-state summary above; it holds no section
bodies.

**Writing a new section: add a file, do not append here.** `docs/handoff/NN-slug.md`, then one row
in the table below. That is the whole convention, and it exists so two sessions finishing slices
at the same time do not both edit one 276 KB file — which is exactly what §10 records going wrong.
See [`docs/plans/2026-07-31-parallel-execution-rework.md`](plans/2026-07-31-parallel-execution-rework.md).

### Standing sections — read these first

| § | Topic |
|---|---|
| [§0](handoff/00-plugin-skills-are-mandatory.md) | IMPORTANT — Use the installed plugin skills |
| [§1](handoff/01-what-this-project-is.md) | What this project is |
| [§2](handoff/02-artifacts-produced.md) | Artifacts produced |
| [§3](handoff/03-decisions-locked-in.md) | Decisions locked in (do not re-litigate without reason) |
| [§4](handoff/04-frontend-current-state.md) | Frontend — current state |
| [§5](handoff/05-not-done-next-session-starts-here.md) | NOT done — next session starts here |
| [§6](handoff/06-known-risks-and-lessons.md) | Known risks and open items |
| [§7](handoff/07-how-to-run.md) | How to run |
| [§8](handoff/08-working-agreement.md) | Working agreement for this project |
| [§9](handoff/09-frontend-multi-company-alignment.md) | Multi-company alignment of the frontend — DONE 2026-07-30 |
| [§10](handoff/10-the-second-concurrent-work-strand.md) | A SECOND, CONCURRENT WORK STRAND — read before editing the plan |

### The slice log — newest last

| § | Slice |
|---|---|
| [§11](handoff/11-m0-first-build.md) | Backend M0 — first build |
| [§12](handoff/12-m0-v2-and-v3.md) | Backend M0 — V2 and V3 |
| [§13](handoff/13-m05-identity-and-permission-evaluator.md) | Backend M0.5 — the identity module and the permission evaluator |
| [§14](handoff/14-m0-async-context-and-the-reset-guc-defect.md) | Backend M0 — the async-context harness, and the reset-GUC defect |
| [§15](handoff/15-m0-provision-company.md) | Backend M0 — `provision-company`, and the two defects writing it found |
| [§16](handoff/16-m0-archunit-and-modulith.md) | Backend M0 — ArchUnit + Modulith verification |
| [§17](handoff/17-m0-compose-ci-and-the-privilege-split.md) | Backend M0 — Docker Compose, CI, and the deployment privilege split |
| [§18](handoff/18-m05-invitation-activation-and-login.md) | Backend M0.5 — invitation, activation and local login |
| [§19](handoff/19-harness-stopped-migrating-as-superuser.md) | Backend — the test harness stopped migrating as a superuser |
| [§20](handoff/20-frontend-live-api-mode.md) | Frontend — the two halves meet: live API mode, real login, accept-invitation |
| [§21](handoff/21-health-stops-reporting-down.md) | Backend — the default install stops reporting itself DOWN |
| [§22](handoff/22-m1-s1-project-list-and-detail.md) | Backend M1, slice 1 — the project list and detail, and the second gate over HTTP |
| [§23](handoff/23-m1-s2-project-vocabularies-and-the-shared-gate.md) | Backend M1, slice 2 — the two project vocabularies, and the gate that is now shared |
| [§24](handoff/24-m1-s3-the-suite-tree.md) | Backend M1, slice 3 — the suite tree, and the `repository` module's first endpoint |
| [§25](handoff/25-m1-s4-the-case-list.md) | Backend M1, slice 4 — the case list, and the screen that finally renders live |
| [§26](handoff/26-m1-s5-the-condition-library.md) | Backend M1, slice 5 — the condition library, and the last error on the case list |
| [§27](handoff/27-m1-s6-the-case-detail.md) | Backend M1, slice 6 — the case detail, and the SPA's order defect the mock could not show |
| [§28](handoff/28-m1-s7-the-first-write.md) | Backend M1, slice 7 — the first write, and why §27's own next-step list was wrong |
| [§29](handoff/29-m1-s8-case-create-and-the-key-allocator.md) | Backend M1, slice 8 — case create, the key allocator, and v1 |
| [§30](handoff/30-m1-s9-suite-writes.md) | Backend M1, slice 9 — suite writes, and the first slice that shipped both halves |
| [§31](handoff/31-frontend-effective-permissions-disable-dont-hide.md) | Frontend — the caller's effective permissions, and disable-don't-hide everywhere at once |
| [§32](handoff/32-m1-s10-case-delete-and-the-own-qualifier.md) | Backend M1, slice 10 + frontend — case delete, and the `OWN` qualifier it forced |
| [§33](handoff/33-parallel-execution-rework.md) | The parallel-execution rework — the three write-conflict files, the handoff, and a second stack |
| [§34](handoff/34-m1-s11-project-creation.md) | Backend M1, slice 11 + frontend — project creation, and the gate one level above the project |
| [§35](handoff/35-m1-s12-case-versions.md) | Backend M1, slice 12 + frontend — case version history, and the oldest dead hook's first caller |
| [§36](handoff/36-frontend-phase-4-vitest-rtl.md) | Frontend Phase 4 — Vitest + RTL, and 203 tests that can go red |
| [§37](handoff/37-the-first-parallel-run.md) | The first parallel run — three lanes, what merged clean, and what integration had to fix |
| [§38](handoff/38-m2-s1-the-execution-module.md) | Backend M2, slice 1 + frontend — the `execution` module, and the seam it could not cross |
| [§39](handoff/39-m1-s13-role-grants.md) | Backend M1, slice 13 + frontend — role grants, and the `company:read` gap they exposed |
| [§40](handoff/40-frontend-phase-5-review.md) | Frontend Phase 5 — the hostile review, and four defects only driving it found |
| [§41](handoff/41-the-second-parallel-run.md) | The second parallel run — the holder rule that broke before launch, and the contract habit that is now three-for-three |
| [§42](handoff/42-m2-s2-plan-and-run-creation.md) | Backend M2, slice 2 + frontend — plan and run creation, and the two defects only the live pass found |
| [§43](handoff/43-the-company-read-scope-repair.md) | Backend — the `company:read` scope repair, and the seventh role that stays off the wire |
| [§44](handoff/44-frontend-resilience-residuals.md) | Frontend — the resilience residuals, and the sibling tab that lied about who was signed in |
| [§45](handoff/45-the-third-parallel-run-and-the-end-of-the-experiment.md) | The third parallel run — the same permission defect twice, and the decision to stop running lanes |
| [§46](handoff/46-m2-s3-plan-statistics.md) | Backend M2, slice 3 + frontend — plan statistics, and the panel that hid an environment |
| [§47](handoff/47-m1-s14-the-dashboard.md) | Backend M1, slice 14 + frontend — the repository dashboard, and the tile that linked to the wrong list |
| [§48](handoff/48-contract-hygiene-computed-checks.md) | The contract-hygiene computed checks — the debt five sessions recorded and none paid |
| [§49](handoff/49-m1-s15-the-case-timeline.md) | Backend M1, slice 15 + frontend — the case timeline, and the parameter nobody had declared |
| [§50](handoff/50-contract-parameter-binding-checks.md) | The parameter checks at both ends — the rung §47 and §49 each named on the way out |
| [§51](handoff/51-open-question-decisions.md) | The open-question decision round — nine settled, two of them by reading rather than deciding |
| [§52](handoff/52-m1-s16-the-suite-timeline.md) | Backend M1, slice 16 + frontend — the suite timeline, and the field the two sides spelled differently |
| [§53](handoff/53-m1-s17-the-work-module.md) | The `work` module + the `case_health` writer — the board runs on a server, and the check that would have caught it |
| [§54](handoff/54-m1-s18-project-members-and-the-boards-three-gaps.md) | The board's three gaps — project members, `V17` idempotency, the fifth column, and the flake that predated the slice |
| [§55](handoff/55-m1-s19-inviting-a-colleague.md) | Inviting a colleague — the write nine sessions named, and the field the live pass added |
| [§56](handoff/56-m1-s20-removing-a-person.md) | Removing a person — the half §55 named on its way out, and the token that outlived the status |
| [§57](handoff/57-m1-s21-the-documentation-module.md) | The `documentation` module — the biggest MSW-only surface, and the block id that must survive an edit |
| [§58](handoff/58-the-first-real-deployment.md) | The first real deployment — the box exists, and four things the runbook only learned by being run |
| [§59](handoff/59-issue-invitation-and-the-undeliverable-link.md) | `issue-invitation` — the link that was never emailed, the mail that stays off, and the CI lane that stopped running tests |
| [§60](handoff/60-the-clock-that-outresolved-its-database.md) | The clock that out-resolved its database — §58's carried debt closed, two call sites that bypassed the fix, and the rule that watches them |
| [§61](handoff/61-the-artifact-register-and-the-plan-scope-qualifier.md) | The artifact register — the dead link in the nav rail, and the qualifier the roles spec has had since M0 |
| [§62](handoff/62-cases-bulk-and-the-gate-that-depends-on-its-argument.md) | `cases/bulk` — the gate that depends on its argument, and the option that could not be chosen |
| [§63](handoff/63-project-access-and-the-password-managers.md) | The project's own access screen — the exemption a test moved into the helper, and telling the password managers |
| [§64](handoff/64-the-shared-step-library.md) | The shared-step library — the derived count, and the reference persisted unchecked since M1 |
| [§65](handoff/65-permission-qualifiers-on-the-wire.md) | Permission qualifiers on the wire — two computations become one, and the two verification queues |
| [§66](handoff/66-the-own-qualifier-reaches-the-row.md) | The `OWN` qualifier reaches the row — the author on the wire, and the test that passed while pending |
| [§67](handoff/67-evidence-attach-and-delete.md) | Evidence attach and delete — the port, the write ordering, and the invariant deliberately not implemented |
| [§68](handoff/68-classification-assignment.md) | Classification assignment — the reconcile's bound, the leaf rule that never existed, and the required field the detail never served |
| [§69](handoff/69-the-classification-vocabulary-writer.md) | The classification vocabulary editor — the gate the dimension decides, and the label that stopped spelling the name |
| [§70](handoff/70-the-verb-the-contract-checks-could-not-see.md) | The verb the contract checks could not see — `api.delete` never existed, and eight call sites went unchecked behind a guard that counts |
| [§71](handoff/71-team-management.md) | Team management — the permission with no consumer, and the membership that must be a membership |
| [§72](handoff/72-the-run-lifecycle.md) | The run lifecycle — the status nothing ever wrote, and the freeze §67 deferred |
| [§73](handoff/73-the-plan-lifecycle.md) | The plan lifecycle — the ladder nothing could climb, and the gate decided by the edge |
| [§74](handoff/74-the-plan-editor.md) | The plan editor — the gates nothing could author, and the optimistic lock that could not fail |
| [§75](handoff/75-the-suite-lifecycle.md) | The suite lifecycle — the last status nothing ever wrote, and the rescind §73 left on cases |
| [§76](handoff/76-the-review-record.md) | The review record — the tables three ladders were written for, and the tier below approval |
| [§77](handoff/77-per-project-review-settings.md) | Per-project review settings — the row four behaviours were waiting on, and the demotion that must not cost the approve key |
| [§78](handoff/78-open-question-decisions-round-2.md) | Open-question decision round 2 — §8 emptied, five that were answered by the product and never struck, and the chart that now depends on the S3 adapter |
