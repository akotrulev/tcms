# §210 — The principle that was restated twice, and the ceiling that moved out of the catalogue

Three phases, all green. `V52`, `SeedDataTest`, the roles spec; then the MCP consent gate, two
`openapi.yaml` descriptions, both `schema` copies and five stale frontend comments; then fifteen
backend tests that asserted the old tier and three production sentences that named it.

Final: **backend 2701 / 0 failures**, **frontend 1349**, **packages 307**, lint clean on all three.

**A product requirement, not a defect.** Stated whole:

> Viewer — can see all cases, steps, plans, etc.
> Test engineer and up — same as viewer but can also create, edit, delete.
> The test lead has the same permissions as the engineer with the addition of adding and managing
> permission on the project as well as give approvals.
> Company admin can do anything within the company.
> Instance admin can do anything within any company.
> Automation service stays as it is now.

## Three of the five rows needed no change, and that is the first thing to record

"Already true" and "not done" are indistinguishable from a diff, so they are named here rather than
left to be inferred from an absent hunk:

| Row | Why nothing moved |
|---|---|
| **Instance Admin** | `V3` **derives** the grant by joining the whole catalogue. It has held everything since M0 and holds anything a later migration adds — which is also why there is no `'*'` sentinel. |
| **Company Admin** | `V3` enumerates every PROJECT key plus company configuration, and `companyAdminHoldsNoSystemPermission` pins that it crosses no client boundary. |
| **Automation Service** | Named in the requirement as staying put. |
| **Viewer** | Also unchanged, and it was worth checking rather than assuming: it already holds `case:read`, `project:read`, `doc:read`, `artifact:read`, `report:view`, plus `case:comment` and `report:export`. |

So the whole change is the **Test Engineer / Test Lead line**.

## What moved

`V52` grants Test Engineer five keys and removes one qualifier:

| Key | Was withheld as |
|---|---|
| `suite:delete` · `artifact:delete` | **shared structure** — `V51`'s header argues it at length, and §A4 line 1 had been rewritten around exactly that claim eight days earlier |
| `run:close` · `result:amend` | **judgements about the work**, not CRUD on an artifact |
| `doc:publish` | the human's **readiness signal** |
| `artifact:edit` | `PLAN_SCOPE` — the project-scope artifact every plan inherits read-only |

After it, **Test Lead minus Test Engineer is exactly seven keys**: the three approvals and the four
project-configuration keys. That is the requirement's two clauses and nothing else.

## The lesson: a principle restated twice is not the boundary

§A4's first line has now been stated three times and deleted once.

1. `V3`/`V12` — **creative vs destructive.** Policed with an authorship qualifier (`OWN`).
2. `V51`, 2026-08-14 — **content vs shared structure and judgement.** The qualifier was removed and
   the line redrawn one level out, at the tier.
3. `V52`, 2026-08-15 — **removed.**

Each rewrite kept the intuition and moved the boundary, and each time what broke it was an *ordinary*
workflow case, not an edge case: the colleague's abandoned duplicate for the first, the contributor
standing in front of a greyed-out button for the second. **Generalised: when a tier line has to be
re-stated every time it meets the workflow, the line is not the principle — the tier is.** What
replaces it as the safety story is soft delete on every affected table, the audit trail, and the fact
that a contributor is an explicit grant and never a default (§A7).

The test shape changed with it. `testEngineerToTestLeadBoundary` used to assert three lines with
`contains`/`doesNotContain`, which pins the keys somebody thought to name and says **nothing about a
key nobody did** — so a migration granting the lead a sixth kind of power passed. It is now an exact
set in both directions: a key leaving the list is a capability handed to contributors, a key joining
it is one taken away.

## Both qualifiers are now carried by zero rows

`artifact:edit` was the **only key in the catalogue that ever carried `PLAN_SCOPE`** — Test
Engineer's grant (`V19`) and Agent's copy of it (`V40`). De-qualifying both leaves
`role_permission.qualifier` as `'ALL'` on every row.

**That expires reason 2 of `V51`'s three for keeping the mechanism**, and it was the only one that
claimed the thing was in use: *"`PLAN_SCOPE` is the same shape and IS populated, so the column and
the resolution seam are earning their keep regardless."* The mechanism is still kept — the two enum
members, `IdentityProjectAccess.canDoOnAuthored`, the plan-scope resolution arm — on the two
arguments from future cost: the qualifier is *data*, so deleting an arm makes a future qualified
grant fail **open**; and rebuilding either narrowing costs a migration, a gate change and a contract
change.

`SeedDataTest.everyQualifierIsAll` pins the column in both directions the way `ownIsCarriedByNoGrant`
pinned one value. **If a later slice proposes deleting the mechanism, roles spec §A5 is the paragraph
to read**: the argument for keeping it is now down to two reasons, and neither is "it is in use".

## `Agent` stops being a ceiling — decided over a recorded objection

`Agent` (`V40`) was *"Test Engineer minus the things an API caller must not do"* — five keys by
`V51`. The requirement is that **an agent is the same as an engineer, with delete operations gated by
user consent in the MCP tool definitions**. So the ceiling stops being data and becomes an
interaction.

**The objection was raised before the change and is recorded in roles spec §A6a, struck through as
superseded rather than as wrong.** That section's own closing argument was:

> A ceiling enforced by omitting a tool controls what an agent reaches *for*, not what it can *do* —
> the `tcms_` bearer authenticates against `/api/*` and can be used with `curl`.

Every word of that still holds and now cuts against the design. **A consent prompt lives in the MCP
server, and the credential does not have to be presented through the MCP server.** An agent token
that is leaked, or driven by any other client, can delete every case, plan, suite, artifact and
evidence row its owner can, with no consent step in the path.

What still holds: the token intersection is unchanged, so an agent never exceeds *the person who
issued it* — a Viewer's agent token is still a Viewer. What is lost is the ceiling **below** the
owner. Remaining mitigations: soft delete, the **required** `expiresAt`, the audit trail, Revoke.
`Automation Service` does not follow and now holds alone the property that a leaked token cannot edit
or delete a real case.

`V52` statement 3 **derives** Agent's grants from Test Engineer's rather than enumerating 23 keys, so
the two cannot drift, and the delta check survives inverted.

## The find: `company:read`, caught by a rule written in §43

The first run of `V52` reddened `SeedDataTest.companyReadIsAlwaysExplicit`, which refuses
`company:read` on the two token-carried roles. **This is not a ceiling and it is not about what an
agent may do.** A PROJECT-scope role's `company:read` is unanswerable by
`PermissionEvaluator.evaluateCompanyWide`, which reads the SYSTEM and COMPANY terms only. A *human*
Test Engineer holds the key twice — inertly at project scope, and reachably through the
`Company Member` baseline `V15` grants on joining — so the inert row is harmless for them. **A token
has no membership row and therefore no baseline**, so that row would be its only source: a company
surface advertised on the roles screen and permanently false at the gate.

It is the §A6 `project:read` finding pointing the other way, and it was **not reasoned out in
advance**. A rule written in §43 caught a migration written today, which is the entire case for
deriving these checks from the catalogue rather than reading the seed.

## The consent gate, and the honest measure of its reach

Two halves in `packages/mcp/src/`, both keyed on `ToolArm.kind` rather than on a list of tool names,
so a delete arm added to any tool later is covered by construction:

- **`annotationsFor()`** (`tool.ts`) — `destructiveHint` on any tool with a delete arm, which is what
  makes a host prompt. The MCP spec's own words are that annotations are *hints* a client must not
  trust from an untrusted server, so a host may ignore it entirely.
- **`withheldConsent()`** (`server.ts`) — the half with teeth. It elicits a confirmation before any
  delete arm runs, and **refuses when the client declares no `elicitation` capability** rather than
  falling through. A consent gate that degrades to *"no prompt available, carry on"* is not a gate,
  and it would fail open on exactly the minimal client an unattended agent is most likely to be. A
  decline is a **result, not an error** — the model asked, a person said no, and `isError` would read
  as a fault to work around, the obvious way around a failed delete being to try it again.

**The surface has exactly one delete arm today: `tcms_write_doc_page`'s `unlink`.** That is not a
measure of the change's blast radius, and reading it that way would be the mistake. **The five delete
keys `V52` gave the credential have no MCP tools at all** — they are reachable over REST and the CLI
and not through this surface. Adding one is what puts it behind the prompt.

## Phase 3 — and a green I reported that was not green

**Phase 1 was committed on a `./gradlew build` I read as passing because the command was piped
through `tail`, so the exit code belonged to `tail`.** It was not passing. Fifteen endpoint and
evaluator tests assert that a Test Engineer is **refused** exactly what `V52` grants, and they were
red from the moment the migration landed. The lesson is small and mechanical: **never read a gate's
result off a pipeline** — redirect to a file and check `$?`, which is what every gate run in this
slice does now.

Each of the fifteen is **inverted rather than deleted**, and where the inversion costs the test its
discriminator, a sibling drives the same route with a **Viewer** — which is what still proves the
route is gated at all.

### Three production sentences named a tier that no longer gates the act

| Sentence | Key | Wrong since |
|---|---|---|
| *"Ending or reopening a run needs a test lead."* | `run:close` | `V52`, this slice |
| *"A result has already been recorded here, and changing one needs a test lead."* | `result:amend` | `V52`, this slice |
| *"Deleting a test plan needs a test lead."* | `plan:delete` | **`V51` — §207 missed it** |

All three now name the capability instead of the tier. §32's rule is untouched — no refusal names a
permission key — and so is the amend-vs-first-record distinction, which was always the valuable half:
telling somebody they cannot record results when what they were refused was an *amend* sends them
after a permission they already hold.

The third row is the interesting one: **a tier-named refusal outlived the tier by a slice**, in
exactly the place §207's own account said it had been careful. Copy that names a role is a claim
about the catalogue, and nothing checks it.

## What this slice cost — `§210 DQ-5`, and it is the biggest trade

Five key pairs were previously told apart by the **Test Engineer persona holding one and not the
other**, and swapping a gate's constant reddened exactly one test each:

| | |
|---|---|
| `suite:delete` | vs `suite:manage` |
| `artifact:delete` | vs `artifact:edit` |
| `doc:publish` | vs `doc:edit` |
| `run:close` | vs `run:execute` |
| `result:amend` | vs `result:submit` |

**`V52` gives the contributor both halves of all five, and no seeded role holds one without the
other, so every one of those swaps now leaves the suite green.** The keys remain separate in the
catalogue and the routes still ask the right ones — but that is upheld by the service's constant and
by review, not by a test. Restoring it needs a persona built from an **ad-hoc grant** rather than a
built-in role, and the suite has no helper for that.

The same thing happened to the qualifier, and there it was repaired rather than recorded:
`PermissionEvaluatorTest.theQualifierIsOverriddenToo` covers replace-don't-union, one of the two
rules the whole scope model rests on, and it was driven by `artifact:edit` being `PLAN_SCOPE` in the
seed. It now **writes the qualifier itself** and restores it in a `finally` — `role_permission` is
instance-wide with no `company_id`, so a leak would narrow a grant for every class that runs after.
Deleting the test was the alternative.

**Generalised, and this is the part worth carrying forward: widening a role deletes tests you did not
know were being paid for by the narrowness.** Five checks here were funded by one persona's gap.

## Files

| File | What |
|---|---|
| `backend/src/main/resources/db/migration/V52__the_tier_line_is_approvals_and_configuration.sql` | New. Five grants, one de-qualification, Agent derived from Test Engineer, the column comment restated. |
| `backend/src/test/java/com/tcms/schema/SeedDataTest.java` | `agentIsTestEngineerMinusFive` → `agentIsTestEngineerMinusNothingItCouldUse`; `testEngineerToTestLeadBoundary` rewritten as an exact seven-key delta; `everyQualifierIsAll` (new); `testEngineerHoldsArtifactEditOnlyAtPlanScope` → `artifactEditIsUnqualifiedEverywhere`, inverted rather than deleted because the widening is silent. |
| `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` | §A2, §A3 (four tables), §A4 (principle 1 deleted, the closed set added), §A5 (reason 2 struck), §A6a (rewritten), change log. |
| `packages/mcp/src/tool.ts` · `server.ts` · `server.test.ts` · `test/harness.ts` | The consent gate, four tests, and `AGENT_KEYS` following the seed. |
| `docs/specs/openapi.yaml` + both `schema` copies | Two descriptions that called `result:amend` and `suite:delete` Lead-only. |
| `frontend/src/features/{cases/SuiteList,docs/DocsPage,runs/ExecutionGrid,runs/ExecutionPage}.tsx` + one test | Five comments asserting a tier fact that is now false. **No gate changed** — the SPA gates on keys, and no key moved. |
| `backend/.../{PlanController,RunController}.java` | The three tier-naming refusal sentences. |
| `backend/.../{SuiteWrite,DocPage,DocPageAttachment,Artifact,ArtifactFile,Execution}EndpointTest.java`, `PermissionEvaluatorTest.java` | The fifteen inverted assertions, the Viewer siblings, and the qualifier fixture. |
| `backend/.../{IdentityProjectAccess,RoleAssignmentRepository,PermissionEvaluator,PermissionSet,MeController,ArtifactWriteService}.java` | Six javadocs citing `artifact:edit`'s `PLAN_SCOPE` grant as a live example, marked historical. The mechanisms they justify are retained and the arguments stand — none was ever about that particular grant. |

## What is NOT done

- **The verification queues have rows and none was run** (§65 standing policy). Eight rows —
  `§210 DQ-1..6`, `AR-7..8`. **`DQ-5` is the one to read**: it records the five discriminations this
  slice cost, and it is written as *expected green*, which means it is a gap and not a check.
  `AR-7` matters too — it **inverts a queued row §209 wrote** (`§209 AR-3` expects a disabled ⋯ menu
  on a project-scope artifact for a Test Engineer), so running the browser queue without it would
  drive an expectation the product no longer has.
- **Nothing drives the consent gate against a real MCP host.** The four tests use an in-memory client
  this repo controls, so *"a host will prompt on `destructiveHint`"* is an assertion about the
  annotation and not a measurement of any host.
- **`docs/specs/2026-08-08-mcp-tool-and-command-surface.md` cites `SeedDataTest.agentIsTestEngineerMinusFive`**,
  which no longer exists under that name. It was already modified by another strand when this slice
  started, so it was left alone rather than have that strand's in-flight edits interleaved into this
  diff. `citedFiles.test.ts` checks paths and not method names, so nothing reddens — fix it in the
  next slice that owns the file.
- **No MCP delete tools were added.** The credential holds five delete keys the surface cannot reach.
