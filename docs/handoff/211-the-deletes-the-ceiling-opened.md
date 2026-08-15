# §211 — The deletes the ceiling opened, and the confirmation that named no row

**Three phases, `packages/` plus a contract *description* change and both `schema` copies. No
migration, no permission moved, no grant changed, nothing in `backend/src`, and not one file under
`frontend/src` that ships.** §210's own closing list, taken from the item it named last: *"No MCP
delete tools were added. The credential holds five delete keys the surface cannot reach."*

Three of the five reach it now. The other two are excluded **by decision**, which is the part worth
reading — one of them for a reason that has nothing to do with permissions.

And the slice found two things nobody was looking for. The first is that the consent gate §210
shipped **asked a question a person could not answer**. The second is that §210's own closing lesson
— *copy that names a role is a claim about the catalogue, and nothing checks it* — was true of four
more sentences, in the two corpora §210 did not sweep, and two of them are read by the model.

## The find, and it was live for the whole of §210: the prompt named no row

`server.ts`'s `identifying()` composed the "which row is about to go" half of every delete
confirmation, by matching the tool's arguments against a hand-written list:

```
projectKey · key · caseKey · planKey · suiteKey · runKey · slug · id · targetId
```

**No tool on this surface declares any of them.** This surface's arguments are `case`, `plan`,
`suite`, `run`, `page`, `link` — surface spec §4.4, one name per resource, no `Key` suffix and no
`projectId` anywhere by rule. So the list matched nothing, and the one delete arm that existed
prompted:

> Confirm deletion — write documentation, and link cases to it (unlink)

and stopped there. The user is asked to approve removing *something* from a page they are not
shown. The function's own doc comment said, correctly, that *"a confirmation naming only the
operation is one a user cannot answer"* — and then produced exactly that.

**The repair is a declaration, not a longer list.** `ToolArm.targets` names the input fields that
identify the row, per arm; `writes.test.ts` fails a delete arm that declares none, and fails one
that names a field its own `inputSchema` does not have. That second half is what stops the same
defect returning under a new spelling — a list of plausible names is exactly as wrong as the old
one if nothing compares it to the schema.

The old comment's *reason* for restricting the echo is kept and is still right: a shared tool's
schema also carries prose, and dumping every argument into a dialog puts a case body in a
confirmation.

`server.test.ts` now reads the message the host actually received. It asserts `page: DOC-1` and
`link: l-1` are in it, rather than that the prompt is non-empty — the failing state was a prompt
that was perfectly non-empty and said nothing.

## `V52` is what made three deletes into three arms rather than three tools

The obvious way to add a delete is a new tool. It is the wrong way here, and §2's merge rule says
why: **a tool merges arms only when every reachable intersection holds all of them**, because the
list is filtered by permission and a caller who holds one key and not the other would be shown a
tool one of whose arms 403s.

Run that test on the three keys:

| Arm | Tool | Would the merge have been legal before `V52`? |
|---|---|---|
| `case:delete` | `tcms_write_case` | **No.** The key was Test Engineer's, but `OWN`-qualified until `V51` — a listed tool restricted to rows the credential's owner authored. |
| `suite:delete` | `tcms_write_suite` | **No, and this is the clear one.** `suite:delete` was Lead-only while `suite:manage` was a contributor's, so folding them would have hidden `tcms_write_suite` from every Test Engineer — the exact failure §2's rule exists to prevent. |
| `plan:delete` | `tcms_write_plan` | **No.** Lead-only until `V51`. |

After `V52` no seeded role holds any of those edit keys without the matching delete key, so all
three fold cleanly. **Widening the tier is what bought the surface shape**, and that is not a
coincidence to note in passing — the alternative was three tools that only a lead would ever be
shown, on a surface whose entire caller population is agent tokens capped below a lead.

The gate rule is *enforced*, not merely observed: `writes.test.ts` already asserted every arm's
permission is inside its tool's gate, so the three gates grew a key each and nothing was optional
about it.

## The two that did not ship, and why one of them is not about permission at all

| Key | Why not |
|---|---|
| `artifact:delete` | **Artifacts have no surface here at all** — no read tool, no write tool, nothing. A delete alone would take an id nothing on this transport can produce, which is exactly the shape §140 recorded for reviewer assignment (`POST …/assignees` needs a `userId` and `/users` is `COMPANY`-scope). It waits for a slice that gives the register a surface, and then it is one arm. |
| `evidence:delete` | **Reachable, and refused anyway.** |

The evidence row is the interesting one, because the id *is* obtainable: `tcms_open_run` with a case
serves the execution detail, and `Evidence.id` is on it. So the §140 argument does not apply and the
tool is buildable. Two other facts decide it, and only together:

- **The delete is hard.** The bytes leave the disk, deliberately — the contract's own words are that
  *"a soft-deleted row pointing at retained bytes is a deletion that did not delete, which is the
  wrong answer for the one thing here a customer may need removed for privacy reasons."* It is the
  only irreversible delete on this surface; the other three are soft.
- **This transport cannot attach evidence.** `POST …/evidence` is `multipart/form-data` and a tool
  call carries no bytes. §140 recorded this as a shortfall of the transport, and it stands.

Put together: the only evidence an agent could remove is **a person's**, through the one delete
nothing can undo, offered to the one actor that can never create the thing. The consent prompt does
not change that arithmetic — it asks a person to approve a loss with no repair. If it is ever
wanted, it wants the upload arm first, and then the pair is coherent.

Both reasons are in `writes.ts`'s module header beside the two §140 recorded there, because *"not
built"* and *"decided against"* are different states and a reader cannot tell them apart from an
absence.

## What the consent gate did NOT need

Nothing. Three delete arms arrived on three tools that were not destructive before, and
`withheldConsent` was not touched — it keys on `ToolArm.kind`, and `annotationsFor` derives
`destructiveHint` the same way. §210 wrote that as the design intent; this is the measurement.

The check that noticed is worth naming: `server.test.ts` asserts the destructive set as an **exact
list of tool names**, on the stated grounds that *"the failure that matters is a delete arm added to
some other tool and NOT annotated — which is invisible from the tool it was added to and shows up
here as a name this list does not have."* It went red on the first run of phase 2 with the right
three names. A check that only asked "is `tcms_write_doc_page` destructive" would have stayed green.

A new end-to-end test drives the case delete through a real MCP client to prove the gate travelled:
`tcms_write_case` is the tool whose other three arms are **not** gated, so a gate that had leaked up
to the tool would refuse an edit and one that had stayed on the doc-page tool would let this delete
through. Neither happens.

## §210's lesson, made mechanical — and it found two sentences the model reads

§210 closed with: *"a tier-named refusal outlived the tier by a slice … copy that names a role is a
claim about the catalogue, and nothing checks it."* It swept `backend/` and `frontend/` and found
three. It did not sweep `packages/` or the contract. Four more were live:

| Where | Claim | Wrong since |
|---|---|---|
| `tcms_write_run.description` | *"Closing a run needs run:close, which this credential does not hold."* | `V52` — **§210 itself** |
| `tcms_record_result.description` | *"Amending a recorded result needs result:amend, which this credential does not hold."* | `V52` — **§210 itself** |
| `openapi.yaml`, `DELETE …/suites/{id}` | *"Requires `suite:delete`, which is held by leads and above and **not** by Test Engineer"* | `V52` — §210 fixed two contract descriptions and missed this one |
| `openapi.yaml`, `HeldPermission` | Teaches the qualifier through *"a Test Engineer holds `artifact:edit` at `PLAN_SCOPE`"* | `V52` — no grant carries any qualifier now |
| `cli/src/writes.ts` | *"`run:close` is Lead and up and an Agent token does not hold it"* | `V52` |

**The first two are worse than a stale comment, and that is the finding.** A tool description is not
documentation a maintainer reads later — it is what the model reads *before deciding whether to try*.
A false one does not mislead somebody in a month; it stops the agent attempting something it is
allowed to do, now, in every session, silently. The failure has no error, no log line and no
symptom: the model simply does not call.

The instrument is eight lines in `writes.test.ts`. For every sentence of every tool description
containing `does not hold`, extract the permission keys and fail on any key `AGENT_KEYS` holds. It
passes on the three sentences that are still true (`suite:approve`, `plan:approve`, and
`tcms_review_verdict`'s *"approval is a separate permission this credential does not hold"*, which
names no key at all) and reddens on exactly the two that are not.

**Know what it is measured against.** `AGENT_KEYS` is this package's *copy* of what `Agent` is
seeded with — a test constant, only as current as the last slice that moved a grant remembered to
update it. `SeedDataTest.agentIsTestEngineerMinusNothingItCouldUse` pins the real thing against the
real seed; this pins the prose against the copy. Two hops, and it is the best available from a stack
that cannot read the migration. Saying so is the point: a check whose reference could drift is worth
having and is not worth trusting further than its reference.

## `tcms_record_result` amends now, and its declaration cannot say so

The second stale sentence is not repaired by deleting it. `result:amend` is held, so **re-recording
over an existing result works today** — the server chooses which key the call costs by whether a
result is already there.

The tool cannot declare that. A `ToolArm` carries one `permission`, and this arm's is
state-dependent. Adding `result:amend` to the tool's *gate* would hide the tool from a caller who
can record and cannot amend, which is the failure §2's rule exists to prevent, arriving through a
permission the arm may never exercise. So the tool stays listed on what a **first record** needs, the
arm keeps declaring `result:submit`, and the description carries the true answer. The cost is
recorded rather than hidden: an amend refusal names `result:submit` in its copy.

## Files

| File | What |
|---|---|
| `packages/mcp/src/tool.ts` | `ToolArm.targets`, and why it is a declaration rather than a sniff of the input. |
| `packages/mcp/src/server.ts` | `identifying` reads the arm; the consent-gate header stops saying the surface has one delete arm. |
| `packages/mcp/src/writes.ts` | Three `delete` arms, three gates, three descriptions; the two excluded keys in the module header; `targets` on `unlink`; the two stale claims. |
| `packages/mcp/src/writes.test.ts` | The targets rule, the "does not hold" rule. |
| `packages/mcp/src/writeConformance.test.ts` | Three `INVOCATIONS` entries; the arm floor 22 → 25. |
| `packages/mcp/src/server.test.ts` | The prompt's target, the destructive set (four names), the gate travelling to a tool that was not destructive. |
| `packages/cli/src/writes.ts` | One comment: `run status`'s gate needed no change, its sentence did. |
| `docs/specs/openapi.yaml` + both `schema` copies | Two descriptions. Nothing on the wire moved. |
| `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` | §A2's Agent row still said *minus three*; §A6a records what the prompt covers rather than what it intended to. |
| `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` · `docs/plans/2026-08-08-mcp-server-wip-plan.md` | The in-flight planning notes replaced by what shipped, and the §210 citation §210 said to fix here. |
| `.claude/settings.json` | The `autoMode.hard_deny` rule, committed with the surface that gives it something to match. Second layer, and client-specific — see below. |

## Verification

**Two rows, neither run, and they go where §203's, §204's and §205's went** — `packages/` still has
no persistent queue, so they live here and are carried in the next slice's list.

| Row | Drive | Expect |
|---|---|---|
| `§211 PL-5` | Against a **real backend**, through a host that supports elicitation: `tcms_write_suite` `delete` on a suite with a filed case, then `tcms_write_plan` `delete` on a plan a run was created from, then both again on empty ones. | ⚠️ **The row that matters — no delete arm here has ever met a server.** The two 409s the descriptions promise are quoted from `openapi.yaml`, not measured, and what a model actually receives is `result.ts`'s rendering of a `Problem` whose `detail` carries the count. Read whether the count survives into the text. Then the empty cases must 204 and answer the `note`. |
| `§211 PL-6` | Same host: call `tcms_write_case` `delete` and **decline** the prompt; then call it again and accept. | The decline is a **result, not an error**, carrying *"Not confirmed"* and no DELETE on the wire — and the prompt must name `case: TC-…`. The accept goes through. This is §210's gate driven against a host this repo does not control, which is the thing the in-memory tests cannot be. |

No browser rows and no backend-mutation rows: no Java moved, no UI moved, and the contract diff is
two `description` strings.

## What is NOT done

- **`packages/` still has no persistent verification queue** (§203's finding, unchanged). This
  slice's rows are `§211 PL-5` and `§211 PL-6`, carried in the next slice's list beside `§203 PL-1`,
  `§204 PL-2`, `§204 PL-3` and `§205 PL-4`, all still ⬜ NOT RUN. **`PL-5` is the one to read**: no
  delete arm here has ever been driven against a real server, so the 409s the descriptions promise —
  a suite with filed cases, a plan with runs — are quoted from the contract and not measured.
- **The `.claude/settings.json` rule binds one client.** It forces a permission prompt for any
  `tcms_*` delete and matches the `action` argument as well as the tool name, which is right for
  this surface. It is also a rule in **Claude Code's** auto-mode classifier: no other MCP client has
  one. The server-side elicitation is what binds every caller, and neither binds `curl`.
- **Nothing drives the prompt against a real MCP host**, unchanged from §210. The tests use an
  in-memory client this repo controls, so *"a host prompts on `destructiveHint`"* remains an
  assertion about an annotation the spec itself calls a hint.
- **Four things the credential holds that this surface still cannot do**: close or reopen a run
  (`run:close`), publish a doc page (`doc:publish`), anything at all with artifacts
  (`artifact:read` / `edit` / `delete`), and `library:manage` — which is off the surface on its own
  standing argument (the control is an impact modal and an API caller never sees a modal) rather
  than by omission. The first three are now larger than the delete gap §210 named, and `run:close`
  in particular is one `state` arm and the same call the CLI's `run status` already makes.
- **The `identifying` defect was a class, and only one instance was looked for.** The list was wrong
  because nobody compared it to the schemas. Nothing else in this package matches argument names
  against a hand-written list — checked — but the question of *what else is a second spelling of a
  declaration* is the one §211 answered once and did not sweep.
