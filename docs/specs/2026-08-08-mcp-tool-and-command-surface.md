# MCP Tool and CLI Command Surface Design

**Date:** 2026-08-08 · **Written by:** `ep-brainstorming`

Closes **§8 question 1** of [`docs/plans/2026-08-08-mcp-server-wip-plan.md`](../plans/2026-08-08-mcp-server-wip-plan.md)
— *"the command and tool list itself"*, which that plan calls **the only genuinely blocking one**.
It is what phases 4, 5, 7 and 8 of that plan's §7 are waiting for.

It also discharges the plan's §9 standing obligation to **read the `documentation` module before
answering**: §6w specified `list_criteria` and `get_doc_page` against a module that did not exist
when it was written, and what that module turned out to be changes two of the ten read tools §6w
named. §6 of the WIP plan moves from `[OPEN]` to `[SETTLED]` on this document.

**No code changes here.** This is a design, and the implementation plan is a separate artifact.

---

## 0. What the reading found, before any design

Four facts about the shipped system, each of which invalidates something §6w assumed. They are
listed first because three of the five reversals in §7 follow directly from them.

1. **There is no `acceptance_criterion` table and no migration has ever proposed one.** §6w.4's
   criteria model is unbuilt backend work (its own slice 1). So `list_criteria` has no data source,
   and `coverage_summary` — specified as three-axis coverage *over criteria* — has none either.
2. **This product already decided what a requirement is, and it is not a criterion row.** ERD
   resolved question #18: there is deliberately **no `requirement` table**; a requirement is a
   `doc_block`, and traceability is a query over `doc_link` joining blocks to the cases that verify
   them. `documentation/package-info.java` opens with this as the decision the whole module is built
   around. So the grounding surface §6w wanted from `list_criteria` exists — it is the doc page, and
   it is richer than a criteria list, because a block's id is permanent and links target
   `(page_id, block_id)`.
3. **`doc_link` has a writer** (§93, `BlockLinkService`) and `DocBlockView.links` is real and served.
   `POST …/doc-pages/{pageId}/blocks/{blockId}/links` is gated `doc:edit`, which is **inside the
   `Agent` grant** — so §6w's Wave-2 `link_case_to_criterion` is buildable today under a different
   name. **`package-info.java`'s "What is deliberately not built" section still says `doc_link` has
   no writer**; `DocBlockView` records the supersession and the package comment does not. That is a
   stale-comment defect to fix in the first implementation phase, not a design question.
4. **Per-block `coverage` is deliberately absent and will not be a tool.** `DocBlockView` states the
   reason: its five required fields include `passed`/`failed`/`notRun`, which are `test_execution`
   joined to `test_case` — two modules `documentation` may not name — and §81's rule puts a
   three-module query in `work`. When it lands it is a **field on an existing tool's response**, not
   a tool.

Two more facts that shape the surface rather than reverse it:

5. **88 REST paths; the `Agent` role holds 23 permissions** (`V40` §6). Reading the shared library
   (`GET /conditions`, `GET /shared-steps`) needs only `case:read` — `library:manage` gates the
   *writes* (`ConditionService`, line 97: *"Readable, and `library:manage` is not held"*). So §6w's
   `list_conditions` is buildable for an agent that cannot write the library, which is the intended
   shape.
6. **`audit_event` has no `via` column.** It has `actor_user_id`, `actor_token_id`, `action`,
   `user_agent` and `request_id`. §6w.3's *"every tool call writes an `audit_event` with … `via = MCP`"*
   was written against a table that does not have that column.

---

## 1. Architecture — one client, one core, two adapters

```
docs/specs/openapi.yaml
        │  npm run gen:api  (phase 4)
        ▼
generated TS client                     ← the only thing that makes an HTTP request
        │
        ▼
COMMAND CORE                            ← the rules that must not diverge
  · display-key resolution (TC-42 ⇄ uuid)
  · Idempotency-Key generation
  · lockVersion / If-Match threading
  · refusal copy (§6.2's 404-vs-403 table)
  · pagination and output-size budgets
        │
   ┌────┴────┐
   ▼         ▼
 CLI       MCP server
(ph. 5,7)   (ph. 8)
```

**Neither adapter contains a request.** The CLI and the MCP server are deliberately *different
shapes* (§2), so everything that differs between them must be presentation and nothing else. Every
rule whose divergence would be a defect — an edit that forgot `If-Match`, a refusal that named a
role it should not have — lives in the core, once.

### Why the two shapes differ

| | CLI | MCP |
|---|---|---|
| Reader | a person at a terminal | a model with a context budget |
| Cost of a wide surface | ~zero — `--help` is paged, and nobody loads all of it | high — definitions and results consume context; descriptions truncate at **2KB**, output warns at 10K tokens and is **capped at 25K** |
| Right granularity | close to REST, one command per operation | workflow-shaped, ~21 tools over 88 paths |

The WIP plan's §6 states the MCP half of this directly: *"Roughly 80–90 REST paths are in scope. **Do
not mirror them.**"* The mirror was considered and refused for a second reason beyond size: Claude
Code defers tool definitions behind tool search by default, **but the Messages API connector needs
`defer_loading` set explicitly and other clients do not defer at all**. A surface that is only
affordable in one client is a surface that breaks in the others.

---

## 2. The rule that decides the tool count

> **A tool never spans two permissions on its write path.**

§6w.3 filters `tools/list` by permission — *"an agent shown a tool it cannot use will call it, be
refused, and retry"*. A tool spanning two gates is therefore either **hidden** from a caller who
holds one of them, or **listed** for a caller who 403s on half of it. Both are the failure the
filtering exists to prevent, arriving through the merge.

Stated precisely, because the loose form is wrong: **a tool may merge arms only when every role that
can reach the tool holds all the arms' permissions together.** Three consequences, all real:

- `tcms_write_doc_page` merges create, edit, link-block and unlink-block. All four are `doc:edit`.
  ~~One gate, one tool.~~ → **five arms and two gates since §212**: `publish` joined them, and the
  merge is legal only because `V52` (§210) granted `doc:publish` to Test Engineer. Before it, that
  key was Lead-only while `doc:edit` was held by everyone who writes, so folding the two would have
  hidden the whole tool from every contributor — this rule's first consequence, exactly. **A tier
  widening is what makes an arm mergeable**, and it is the second time: `suite:delete` joined
  `tcms_write_suite` in §211 on the same migration. `tcms_write_run` is the third, taking
  `run:close` beside `run:create` — where the role that would have broken the merge is
  `Automation Service`, which holds the first without the second and is not a reachable
  intersection because `V41` made it non-assignable.
- `tcms_review_comment` and `tcms_review_verdict` are **two tools, and the first draft of this
  document had them as one.** The merge looked safe from the catalogue — no role holds a `*:review`
  without `case:comment` — and it is wrong, because the catalogue is not what a token holds. **The
  effective set is an intersection** (`evaluate(ownerId, projectId) ∩ permissionsOf(token.roleId)`),
  so a **Viewer**-owned agent token comes out holding `case:comment` and no `*:review` at all. A
  merged tool would be hidden from a caller who can legitimately comment. `ReviewSubjectType` binds
  the three review keys per subject and `COMMENT_PERMISSION = "case:comment"` covers all three (§A3 —
  `suite:comment` and `plan:comment` deliberately do not exist), so commenting is one tool across
  subjects and verdicts are another.

  **The general form of the mistake is worth stating, because §2's rule is easy to apply to the wrong
  set:** the question is never *"does a role hold both"*, it is *"does every **reachable
  intersection** hold both"* — and the reachable intersections are `Agent ∩ each of the five
  assignable roles`. Checking the other multi-gate tools against that set clears them: `Viewer`
  contributes nothing to any of them, and Test Engineer and up hold each tool's arms entire. Two of
  those clearances rest on `Automation Service` being **non-assignable** (`V41`) and so never an
  owner — it is the one role holding `result:submit` without `run:execute`, and `case:read` without
  `report:view`. §134 is load-bearing for this surface in three separate places.
- **The same rule splits the status transitions apart, which is not where they look like they
  belong.** Submitting for review costs *edit* rights, not review rights — the contract says so per
  artifact, and §6g is the reason: `PUT …/suites/{id}/status`'s forward moves are `suite:manage`,
  `PUT …/plans/{id}/status`'s are `plan:edit`, and a case's `SUBMIT_FOR_REVIEW` is `SET_STATUS` with
  `lifecycleStatus: IN_REVIEW`. So submit is an arm of each **write** tool and never of
  `tcms_review`, and the approve moves are absent from all of them because `Agent` holds no
  `*:approve`.
- `tcms_write_case` merges create and edit — **and it is §134 that makes this safe.** `case:create`
  without `case:edit` exists in exactly one role, `Automation Service`, where it is additionally
  qualified *quarantine only*. That role is a CI credential, it is now non-assignable (`V41`), and it
  never carries this surface: an MCP token carries `Agent`. Had the two token roles been collapsed —
  the simplification §134 refused — this merge would list a create-only caller a tool whose edit arm
  403s.

The count below is the **outcome** of this rule, not a target.

---

## 3. The MCP surface — ~~21~~ ~~26~~ ~~28~~ 30 tools

Namespaced `tcms_*`. `projectId` and `companyId` **do not appear in any schema** (§4.1).

| # | Tool | Gate(s) | Wraps | Status |
|---|---|---|---|---|
| 1 | `tcms_whoami` | none | `GET /me`, `GET /me/permissions` | **Buildable** |
| 2 | `tcms_orient` | `project:read` | project detail + classification + statuses + suites + tags + case counts | **Buildable** |
| 3 | `tcms_search_cases` | `case:read` | `GET …/cases` (full filter block + facets) | **Buildable** |
| 4 | `tcms_open_case` | `case:read` | `GET …/cases/{caseId}` (+ `…/history`, `…/versions` on request) | **Buildable** |
| 5 | `tcms_list_suites` | `case:read` | `GET …/suites`, `GET …/suites/{suiteId}` | **Buildable** |
| 6 | `tcms_list_library` | `case:read` | `GET …/conditions`, `GET …/shared-steps` | **Buildable** (read-only by design — §0.5) |
| 7 | `tcms_doc_tree` | `doc:read` | `GET …/doc-pages` | **Buildable** |
| 8 | `tcms_open_doc_page` | `doc:read` | `GET …/doc-pages/{pageId}` | **Buildable** |
| 9 | `tcms_write_doc_page` | `doc:edit` ~~alone~~ + `doc:publish` (§212) | `POST`/`PATCH …/doc-pages`, block link `POST`/`DELETE`, `PUT …/doc-pages/{pageId}/status` | **Buildable after §4.2** |
| 10 | `tcms_write_case` | `case:create` + `case:edit` | `POST`/`PATCH …/cases`, submit-for-review | **edit arm buildable; create arm blocked — §5** |
| 11 | `tcms_write_suite` | `suite:manage` | `POST`/`PATCH …/suites`, membership, `PUT …/status` (submit / withdraw only) | **Buildable after §4.2** |
| 12 | `tcms_review_comment` | `case:comment` | `GET …/reviews`, `POST …/comments` | **Buildable** |
| 13 | `tcms_review_verdict` | `case:review` / `suite:review` / `plan:review` | participations, assignees, comment resolution | **Buildable** |
| 14 | `tcms_list_plans` | `case:read` | `GET …/plans`, `…/{planId}`, `…/statistics` | **Buildable** |
| 15 | `tcms_write_plan` | `plan:create` + `plan:edit` | `POST`/`PATCH …/plans`, `PUT …/status` (submit / withdraw only) | **Buildable** |
| 16 | `tcms_open_run` | `case:read` | `GET …/runs`, `…/{runId}`, `…/executions/{executionId}` | **Buildable** |
| 17 | `tcms_write_run` | `run:create` ~~alone~~ + `run:close` (§212) | `POST …/runs`, `POST …/runs/{runId}/clone`, `PUT …/runs/{runId}/status` | **Buildable** |
| 18 | `tcms_record_result` | `run:execute` + `result:submit` + `evidence:attach` | `PATCH …/executions/{executionId}`, `POST …/evidence` | **Buildable** |
| 19 | `tcms_triage` | `case:read` + `report:view` | dashboard, quarantine state, `GET …/ingestions`, `…/automation-mappings` | **Buildable** |
| 20 | `tcms_write_issue` ~~`tcms_write_work_item`~~ | **any of** `issue:create` / `issue:edit` / `issue:comment` | `POST /issues`, `PATCH /issues/{key}`, `POST …/transitions`, `POST …/comments`, **`POST /boards/{id}/cards/{key}/move`, `PUT /issues/{key}/labels`** (012 T5 — §3.5) | 011 T5 — see §3.4 |
| 21 | `tcms_report` | `report:view` + `report:export` | `POST …/reports/generate`, poll, download | **Buildable** |
| 22 | `tcms_list_artifacts` | `case:read` | `GET …/artifacts` | Audit C10 |
| 23 | `tcms_write_artifact` | `artifact:edit` + `artifact:delete` | `POST`/`PATCH`/`DELETE …/artifacts` | Audit C10 |
| 24 | `tcms_shape` | **none** | **nothing — it reads no endpoint** | §253 |
| 25 | `tcms_list_proposals` | `case:read` | `GET …/ai-proposals`, `…/{proposalId}` | Shipped; this row was missing |
| 26 | `tcms_read_issue` | `issue:read` | `GET /issues`, `GET /issues/{key}`, `…/comments`, `…/doc-links` | 011 T5 — see §3.4 |
| 27 | `tcms_read_board` | `board:read` | `GET /projects/{id}/boards`, `GET /boards/{id}`, `…/cards`, `GET /projects/{id}/backlog`, `…/labels` | 012 T5 — see §3.5 |
| 28 | `tcms_write_backlog` | `board:rank` + `board:configure` | `PUT /issues/{key}/rank`, `POST /projects/{id}/labels`, `PATCH`/`DELETE /labels/{id}` | 012 T5 — see §3.5 |

Eighteen are buildable against today's contract; two need §4.2's contract addition first; one has a
blocked arm (§5).

> **~~Eighteen are buildable.~~ → AMENDED 2026-08-08 BY THE IMPLEMENTATION (§140).** §4.2's
> contract addition landed in §136 and §5's blocked arm was unblocked by §138, so tools 9, 10 and 11
> all shipped. **Two arms of two *Buildable* tools did not, and neither is a shortfall in the
> implementation** — both are decisions taken elsewhere meeting a transport this table reasoned
> about separately:
>
> | Tool | Arm dropped | Why it cannot exist here |
> |---|---|---|
> | 18 `tcms_record_result` | `evidence:attach` — `POST …/evidence` | The endpoint accepts **`multipart/form-data` only**; it takes a file. MCP tool arguments are JSON and a model has no bytes to send. The tool therefore declares `run:execute` + `result:submit` and **not** `evidence:attach`: declaring a permission it never exercises would hide it from a caller who can record results and cannot attach, which is §2's failure arriving through the declaration rather than through the merge. |
> | 13 `tcms_review_verdict` | assignees — `POST …/reviews/{id}/assignees` | It needs a `userId` or a `teamId`, and **nothing on this surface can obtain either**: §3.3 excludes everything under `/users` and `/teams` as `COMPANY`-scope, and `V40` §6 withholds `company:read` from `Agent` deliberately — *a token is not a member*. A tool taking a UUID only a human can paste in is the CLI's job. The tool records verdicts and resolves threads. |
>
> This is the **second** time `V40` §6 has removed something this document asked for — §4.5's
> refusal copy was the first (§137). The general form: *the `Agent` grant bounds what a credential
> may **know** as well as what it may do, including the things a helpful client wants to know in
> order to be helpful.* Both are recorded in [§140](../handoff/140-the-mcp-server.md) §3.
>
> **§2's rule also needs a qualifier it does not have.** `GET /me/permissions` answers
> `{key, qualifier}` pairs and this document reasons entirely in keys; a `tools/list` filtered on
> keys alone lists a tool for rows the caller cannot touch, which is §65's SPA defect one client
> over. The resolution is **not** to hide — `OWN` still lets a caller act on their own rows — but to
> list the tool and compose the narrowing into its description, per session. §140 §2 has the
> argument.

`tcms_review_verdict` declares three permissions and is the one tool listed on a **per-subject**
basis rather than outright: a caller holding `case:review` but not `plan:review` sees it, and its
description names which subjects it will accept. That is the honest reading of §2 — the three keys
are one gate applied to three nouns, not three arms — and it is why the resolution of a comment
thread sits here rather than beside the commenting: §A3 is explicit that resolving a thread is not
commenting and costs the subject's `*:review`.

### 3.0a `tcms_shape` — the one tool that reads nothing (§253)

Added by the authoring-shape catalogue slice
([plan](../plans/2026-08-26-authoring-shape-catalogue.md)). It answers *what a payload must look
like*, which is a property of the **build** rather than of the instance, so it calls no endpoint and
carries no gate.

**Why it is not part of `tcms_orient`,** which is where it obviously belongs and does not: orient
opens with `session.projectId()` and every one of its five reads is project-scoped. The caller who
most needs the format is the one who has connected and has **not yet resolved a project**, and
folding this into orient would make the one tool that must work early depend on the thing that is
not there yet.

The division of labour is stated in both descriptions and checked: **orient owns the project's live
vocabulary** — statuses, classification dimensions and their value paths, tags in use — and
`tcms_shape` owns the shape. A second copy of orient's half here would go stale the moment a project
adds a dimension.

It also engages **none** of §3.1's rule: that governs tools fanning out to more than one *read*, and
this fans out to zero.

**A `resources` capability arrived with it** — `tcms://shape/{entity}`, serving the long-form guide,
which is the carrier for the pitfalls that do not fit in a 2KB tool description. Advertised
**without** `listChanged`, on the rule §3.2's `tools` capability already states: the set is compiled
into the build and cannot change within a session, so claiming a notification nothing emits would be
a claim a host plans around. Resources are **not permission-filtered**, unlike `tools/list`: the
shape of a test case is the same document whatever a credential holds, it names no project, no
company and no row, and withholding it would tell a caller nothing except that it must guess.

### 3.1 The ~~two~~ three composites, and why only three

`tcms_orient` and `tcms_open_case` are the only tools that fan out to more than one endpoint for a
*read*. They exist for one reason, which is §6w's own stated value: an agent must ground itself in
the repository that exists before proposing anything, and **an agent that has to assemble the
project's shape from six list calls will skip it and generate blind** — which is exactly the
file-based-tool failure mode §6w was written against.

Every other tool that spans endpoints does so along a single gate, per §2. No third composite is
added without the same argument, because a composite's cost is an output that is hard to bound.

~~The two composites~~ → **three, since 015 T4**, and the third is the argument above applied to a
narrower reader. `tcms_read_issue {context: true}` reaches `GET /issues/{key}/agent-context`, which
assembles a task with its parent story's acceptance criteria, the epic's goal, the target release,
the linked specification excerpts and the project's repositories. The same sentence governs it:
**an assistant that has to assemble what it is being judged against from six calls will skip it and
generate blind**, and the criteria are exactly the thing skipping loses.

Two things keep the cost bounded, and they are why this one is admissible where a fourth may not
be. **The fan-out is the server's** — one endpoint, one call from here, so the tool does not span
endpoints at all and §2's gate rule is untouched (`issue:read` throughout). And it is **opt-in**:
`context` is off by default, exactly as `tcms_open_case`'s sub-reads are, so the detail read a
caller already makes does not silently grow.

### 3.2 `tcms_whoami` and the capability summary

Per §6.2 of the WIP plan, unchanged and adopted here: `_meta["anthropic/alwaysLoad"]: true`, and the
same grouped summary is rendered into the server's instructions at connect. Grouped, never a 55-row
dump — instructions truncate at 2KB. The refusal-copy constraint in §4.5 governs what it may say.

### 3.3 What is not a tool

- **Nothing gated by a permission `Agent` does not hold.** No approve, no `run:close`, no
  `result:amend`, no `doc:publish`, ~~no delete of anything~~, no project configuration, no library
  write. These are absent from the surface entirely rather than present-and-refused: a tool the role
  can never hold is a definition nobody's `tools/list` will ever include, so it would be dead schema.

  **→ two of these are false as of 2026-08-15 and the bullet is kept because its ARGUMENT is still
  the rule.** A tool the credential can never reach is dead schema; what changed is the reach, not
  the reasoning. `V52` (§210) made `Agent` equal to Test Engineer minus `company:read`, so
  `run:close`, `result:amend`, `doc:publish` and every delete key are now **held**. Approve, project
  configuration and library write are unmoved and still correctly absent.

  **"No delete of anything" — superseded, and shipped in two slices.** The planning note this
  paragraph used to carry said *the ceiling has to move first, and moving it is not a one-line
  change*; that was right and §210 did it — a migration after `V51`, roles spec §A6a and its
  changelog row, and `SeedDataTest.agentIsTestEngineerMinusFive`, which was renamed to
  `agentIsTestEngineerMinusNothingItCouldUse` because the delta it computes became one key. §211
  then built the arms. **Three of the five delete keys are reachable**, as `delete` arms on the
  tools that already own those resources — `case:delete` on `tcms_write_case`, `suite:delete` on
  `tcms_write_suite`, `plan:delete` on `tcms_write_plan`.

  **Folding a delete into an existing tool is only legal because of `V52`, and this is the part
  worth carrying forward.** §2 merges arms only when every reachable intersection holds all of
  them. Before `V52`, `suite:delete` was Lead-only while `suite:manage` was a contributor's, so the
  fold would have hidden `tcms_write_suite` from every Test Engineer — the exact failure §2's rule
  exists to prevent. Widening the tier is what made three deletes into three arms rather than three
  tools nobody but a lead would ever be shown.

  **The other two are excluded by decision, not by permission** (`packages/mcp/src/writes.ts`
  module header):

  | Key | Why not |
  |---|---|
  | `artifact:delete` | Artifacts have **no surface here at all** — no read, no write. A delete alone takes an id nothing on this transport can produce, which is the reviewer-assignment shape §140 recorded. It waits for a slice that gives the register a surface. |
  | `evidence:delete` | Reachable — `tcms_open_run` serves an execution's `evidence[].id` — and refused anyway. It is the **one hard delete** on this surface (the bytes leave the disk, deliberately), and this transport **cannot attach evidence at all**: `POST …/evidence` is `multipart/form-data` and a tool call carries no bytes. So the only evidence an agent could remove is a person's, irreversibly, offered to the one actor that can never create the thing. The consent prompt does not change that arithmetic. It wants the upload arm first. |

  Note the surface was **not** delete-free before any of this, which the struck sentence reads as
  claiming: the `unlink` arm of `tcms_write_doc_page` has been `kind: 'delete'` since §140. It was
  always permissible because detaching a `doc_link` costs `doc:edit`, not a delete permission — the
  ceiling is a statement about *permissions*, not about verbs.

  **The consent half is now server-side, and the client-side rule is a second layer rather than the
  gate.** `.claude/settings.json` carries an `autoMode.hard_deny` rule forcing a permission prompt
  for any `tcms_*` delete, matching on the `action` argument as well as the tool name since this
  surface expresses verbs as arguments (§2) — but that is a rule in **Claude Code's** classifier and
  no other client has one. What binds every MCP caller is in the server: `destructiveHint` on any
  tool with a delete arm, plus an **elicitation** prompt that **refuses when the client declares no
  elicitation capability**. That refusal is the decision to read before relaxing anything here — a
  consent gate that degrades to "cannot ask, carry on" fails open on exactly the minimal client an
  unattended agent is most likely to be. Both halves key on `ToolArm.kind`, so an arm added later is
  covered without anybody remembering; §211 added three and changed no gate code.

  **Neither reaches raw REST**, and §A6a records that objection as superseded rather than as wrong:
  the same `tcms_` bearer deletes over `curl` with nobody to ask. What still bounds it is the token
  intersection, soft delete, the audit trail, the required `expiresAt` and Revoke.

  One observation, still an **open question**: a gate phrased around *deletes* does not cover this
  surface's most destructive writes, which are tagged `edit`. Every COMPLETE-SET property discards
  what the caller omits — `blocks` on `tcms_write_doc_page` ("a block you omit is deleted"),
  `tagRules`/`cases`/`pre`/`postconditions` on `tcms_write_suite`, `suites`/`cases` on
  `tcms_write_plan`, `tags` on `tcms_write_case`. A read-modify-write that drops a paragraph or
  unfiles a case arrives through that shape and through no other. Whether those belong behind the
  same prompt is unsettled. (Line numbers were cited here and are gone: they rotted within one
  slice, and `citedFiles.test.ts` checks paths, not lines.)

  **What is held and still has no tool**, recorded so the gap is a decision rather than an
  oversight: `run:close`, `result:amend` as a distinct arm, `doc:publish`, and the whole
  `artifact:*` family. `tcms_record_result` amends today by re-recording — the server decides which
  key the call costs by whether a result exists — but nothing here closes a run or publishes a page.
- **Nothing under `/users`, `/teams`, `/roles`, `/role-assignments`, `/api-tokens`.** All are
  `COMPANY`-scope, and `Agent` deliberately does not hold `company:read` (`V40` §6: *a token is not a
  member*).
- **`POST /ingestions`.** CI already has `Automation Service` and a purpose-built path; an MCP tool
  here would duplicate the one endpoint in the product that is not context-constrained.
- **`POST …/cases/bulk`**, and it is the interesting exclusion. It is exactly the endpoint an agent
  would get the most leverage from — and **its gate is decided by the operation inside its payload**,
  so no single permission can be declared for a tool wrapping it and §2's rule refuses it outright.
  That is the same objection `documentation/package-info.java` already records about payload-decided
  gates (*"a gate that depends on the payload is one the SPA cannot render a disabled state for"*),
  reappearing one client over: what the SPA cannot render disabled, `tools/list` cannot filter. It
  stays in the CLI (§6), where a person reads the 403 and knows which of the operations they sent was
  refused. Splitting it into per-operation tools is available later and is not free — it is one tool
  per bulk operation, and the ceiling on this surface is the whole design.

### 3.4 The issue verbs (011 T5) — and the count 009 got wrong

009 said *"the six `work-item` verbs in `packages/cli` and `packages/mcp` become `issue` verbs"*.
**That counted nothing in the tree.** What existed was four sites, and the CLI's share of them was a
sentence saying it had none:

| Site | What it was |
|---|---|
| `mcp/src/writes.ts` | `tcms_write_work_item` — **one** tool, **two** arms (`create`, `move`) |
| `mcp/src/reads.ts` | one `GET /work-items` inside `tcms_triage`, kept for its `QUARANTINE` cards |
| `client/src/core/shapes.ts` | `WORK_ITEM_SHAPE`, registered under `workItem` |
| `cli/src/reads.ts` | a **comment** naming `work-item` as a noun §6 promises and does not deliver |

So this is not six verbs becoming six verbs. It is one tool growing from two arms to four, one read
changing its path, one catalogue entry being renamed, and **the CLI gaining five commands it never
had** — the asymmetry §6 recorded is closed for `work-item` and still open for `report` and `triage`.

#### Type and status are NAMES here, never UUIDs

`IssueInput.typeId` and `IssueTransitionInput.statusId` are UUIDs of per-project rows, and **an agent
handed a bare UUID field will invent one**. This is the problem `shapes.ts`'s execution status already
solved, in the words its own note uses — *a status guessed from another project is refused, and
`tcms_orient` serves this project's own list*.

Both surfaces therefore take a **name**, resolve it against `GET /projects/{projectId}/workflow`, and
refuse an unknown one **by listing what the project has**. The division of labour is deliberate and is
the CLI's `requireStatus` rule restated: **the client checks the vocabulary, the server checks the
legality.** A status this project does not have is refused here, with the list; a status it has that
the workflow forbids *from where this issue stands* is a 409 from `POST …/transitions`, whose `detail`
names the moves that are allowed. A client-side legal-move ladder would be a second copy of a rule
that lives in one place.

#### `tcms_write_issue` — typed input

Gate: **`any` of `issue:create`, `issue:edit`, `issue:comment`.** `any`, not `all`, for
`tcms_review_verdict`'s reason one noun over: **Viewer holds `issue:comment` and neither of the other
two** (roles spec §A3), so an `all` gate would take a reader's only way of contributing away from them
entirely. The description is narrowed per session with the actions the credential can actually reach.

| Field | Type | Arms | Notes |
|---|---|---|---|
| `action` | `create` / `update` / `move` / `comment` | all | required |
| `issue` | string — a key, `ACME-124` | update · move · comment | required for those, refused for `create` |
| `typeName` | string | create | a **name** from the project's workflow — `Story`, `Bug` |
| `statusName` | string | move | a **name** from the project's workflow — `In Review` |
| `title` | string | create · update | required on `create` |
| `description` | string or null | create · update | |
| `parentKey` | string or null | create · update | required for a `TASK`-level type, refused for an `EPIC`-level one |
| `priority` | `Priority` | create · update | enumerated, pinned to the contract by `shapesContract.test.ts` |
| `estimateHours` | number or null | create · update | hours, never points (011 D8) |
| `startDate` · `targetDate` | date or null | create · update | epics only, server-side |
| `dueDate` | date or null | create · update | |
| `acceptanceCriteria` | string[] | **create only** | see the shortfall below |
| `agentEligible` | boolean | create · update | D30 — a hint and a filter; it assigns nothing (015 T4) |
| `prUrl` | string | **attach-pr** | the PR *you* opened; this product never opens one (D25) |
| `body` | string | comment | required |

Arms, and the header each one's `kind` implies (§4.2/§4.3):

| Arm | Kind | Permission | Writes to |
|---|---|---|---|
| `create` | `create` | `issue:create` | `POST /issues` — carries `Idempotency-Key` |
| `update` | `edit` | `issue:edit` | `PATCH /issues/{key}` — carries **no** `If-Match`; see below |
| `move` | `state` | `issue:edit` | `POST /issues/{key}/transitions` |
| `attach-pr` | `edit` | `issue:edit` | `PATCH /issues/{key}` — carries **no** `If-Match`, the same wall as `update` |
| `comment` | `state` | `issue:comment` | `POST /issues/{key}/comments` |

**`comment` is a `state` arm, not a `create`.** `POST …/comments` declares no `Idempotency-Key`, and
`review comment` is declared the same way for the same reason: a `create` kind would send a header the
endpoint never reads, which is *the absence of the guarantee wearing the guarantee's name*.

**`update` sends no `If-Match`, and that is a recorded shortfall rather than an oversight.**
`PATCH /issues/{key}` declares no such parameter and `Issue` carries no `lockVersion` on the wire, so
there is nothing to precondition on — the same wall `tcms_write_artifact.edit` meets, and it is
recorded the same way, in `writeConformance.test.ts`'s `CREATE_WITHOUT_A_KEY` with what it costs. What
it costs is bounded by the endpoint being a **true delta**: an absent key is *leave it*, so two
concurrent editors lose only the fields they both named. Closing it is a contract change on both the
response and the request, not a header here.

**`acceptanceCriteria` is create-only, and this is a stated gap.** `IssueInput` takes `string[]`;
`IssueUpdate` takes `{text, done}[]` against an **inline** item schema with no component name, and
every nested pin in `shapesContract.test.ts` resolves a named one. So ticking a criterion off is not
on this surface — it is the story screen's (011 T6), and it is written down here rather than
discovered as a missing field.

#### `tcms_read_issue` — typed input

Gate: `issue:read`. **Two branches, not a third composite.** With no `issue` it is a list; with one it
is the detail, and the two optional sub-reads are `tcms_open_case`'s shape exactly — opt-in and off by
default, so the tool never fans out unasked and §3.1's ceiling is untouched.

| Field | Type | Notes |
|---|---|---|
| `issue` | string — a key | omit to list |
| `level` | `EPIC` / `STORY` / `TASK` | list |
| `epic` · `parent` | string — a key | list |
| `unparented` | boolean | story-level issues with no epic |
| `queue` | boolean | list — **`agentEligible=true` AND `assigneeId=<the token's owner>`**; refused beside `issue` |
| `context` | boolean | detail — reads `GET …/agent-context` **instead of** the plain detail (§3.1's third composite) |
| `sort` | `key` / `title` / `status` / `dueDate` / `estimateHours` | list |
| `page` · `size` | integer | list — §4.6's window |
| `comments` | boolean | detail — also reads `GET …/comments` |
| `docLinks` | boolean | detail — also reads `GET …/doc-links` |

**`allowedTransitions` comes back as NAMES**, and so do `type` and `status`. That is the read half of
the same decision the write half makes: the tool hands back exactly the token `move` takes, so an
assistant that can see which moves are legal does not attempt one that is not — the difference between
a refusal it can act on and a retry loop. The ids are dropped for §6's own rule (*UUIDs resolved to
display keys*), and because an id on this surface is a second handle nothing here accepts.

#### The shared output envelope

Unchanged, and named here because this section is where a reader will look for it:

- **A list** — `tcms_read_issue` with no `issue` — answers §4.6's one list shape,
  `{ items, page, size, total, hasMore, truncated }`. `GET /issues` is not paginated on the wire, so
  this is the client-side window `reads.ts`'s header describes; `hasMore` and `truncated` mean
  different things there and are kept apart.
- **A detail read** answers the projected resource, with `comments` and `docLinks` beside it when
  they were asked for.
- **Every write arm** answers the contract resource the endpoint returned, through `result.ts` — JSON,
  no prose — and a refusal is composed once by `core/outcome.ts`.

#### The three arms and modes 015 T4 adds — and why the count is still 30

`tcms_read_issue` gains `queue` and `context` (both `issue:read`); `tcms_write_issue` gains
`attach-pr` (`issue:edit`). **No tool was added**, so the surface is still 30 — every one of the
three is an arm or a mode of a tool whose gate already covers it, which is §2 doing exactly what it
was written to do. A `tcms_agent_queue` holding `issue:read` would be a second tool listed to every
caller who already has `tcms_read_issue`, and a `tcms_report_pr` holding `issue:edit` would be a
one-field tool listed to everyone holding `tcms_write_issue`.

**`queue` is two query terms and neither is optional.** `agentEligible=true` alone is the project's
whole flagged pool, which is not *mine*; `assigneeId` alone is everything on the owner's plate,
flagged or not. D30 has the flag assign nothing and D26 has a token act as its owner, so the
intersection is the only thing that means *the work waiting for me*. **The owner is resolved from
the session, never taken as an argument** — an owner parameter would let a token read somebody
else's queue, and there is no version of that this surface needs. `queue` beside `issue` is refused
rather than one of them silently winning.

**`context` replaces the detail read rather than riding beside it**, which is the one place it
differs from `comments`/`docLinks`. `AgentTaskContext` already carries the whole `Issue`, so
serving both would send the same resource twice; the arm projects the nested issue through the
*same* mapping the plain branch uses, so `allowedTransitions` is names on both paths.

**`attach-pr` sends one field and it is not nullable.** `IssueUpdate.prUrl` accepts null, but *take
the PR off this issue* is not something an assistant does — it reports what it opened, and the
server records it as `issue.pr_url_set` on the history. Clearing it is `update`'s job.

**None of the three is in the shape catalogue**, and `core/shapes.ts`'s `ISSUE_SHAPE` header
carries the rule: the catalogue holds an action only when it has a payload of its own to describe.
`attach-pr` is the sharpest case, because `prUrl` is the one of them that *has* a contract property
— it lives on `IssueUpdate` and nowhere else, so cataloguing it would pull that schema into
`shapesContract.test.ts`'s accounted set, where every other property is already offered at its
`IssueInput` site by the `update` action. The accounting would then have to be satisfied with a
dozen `UNOFFERED` lines declaring fields unoffered that this surface offers, and a check answered
by writing down the opposite of what is true is worse than no entry. `agentEligible` *is* a
catalogue field — it is a plain `IssueInput` property — which is what closed T1's
`UNOFFERED['IssueInput.agentEligible']` placeholder.

#### One measured gap in the §8.1 check

`declaredPermissionsOf` reads a gate out of an operation's prose by matching the literal word
**`Requires`**. The tracker operations state their gates as `` `issue:create` — Instance Admin, … ``
and do not use that word, so §8.1's permission assertion is **skipped, not satisfied**, for all four
write arms. The header half (§8.3) does cover them, because it reads `parameters` rather than prose.
Closing it is a contract-prose change plus a regeneration of both schema copies; it is recorded here
rather than left as a silent no-op, which is the failure that check's own vacuity guard exists to name.

### 3.5 The board verbs (012 T5) — and why they are two tools and two new arms, not one

The board contract (012 T1) adds ten operations. §2's rule decides how they land here, and it does
not put them where they look like they belong:

| Operation | Gate | Roles holding it (§A3) |
|---|---|---|
| `listBoards` · `getBoardConfig` · `getBoardCards` · `getBacklog` · `listLabels` | `board:read` | **all seven** |
| `moveBoardCard` · `putIssueLabels` | `issue:edit` | Test Engineer and up |
| `rankIssue` | `board:rank` | **Project Manager and up** |
| `createLabel` · `updateLabel` · `deleteLabel` | `board:configure` | **Project Manager and up** |

So a single `tcms_write_board` is illegal: `Agent ∩ Test Engineer` is a reachable intersection that
holds `issue:edit` and neither of the other two, and a merged tool would be listed to a caller who
403s on two thirds of it — §2's failure, arriving through exactly the merge that rule exists to
refuse. `board:rank` and `board:configure` are granted to the *same* three roles, so those two may
merge and do.

**`createBoard`, `updateBoard` and `deleteBoard` are on neither surface.** Defining a board's
columns is a screen with a status-claim rule the server enforces in a 422 naming which status was
claimed twice; an agent composing that array blind would spend its turns on the refusal. The board a
project has is what these verbs read, and §2's budget is better spent elsewhere.

**That screen landed in 012 T9** — `/projects/{projectId}/settings/boards`,
`frontend/src/features/settings/BoardSettingsPage.tsx` — and it settles the sentence above rather
than merely deferring it. The screen renders the claim rule as a status × board table that says the
invariant is failing, **by status name, before a save is attempted**; the three verbs are reachable
there and nowhere else. It is also where a label's `color` is chosen, which is the one field on
`LabelInput` an agent has no way to pick well: `tcms_write_backlog`'s `color` takes the token name a
person has already seen on a swatch.

**None of the eight commands or two tools has been run against a running instance.** Their gate is
the packages build and the contract suites, and every exchange in those crosses a `fetch` double
compared to `openapi.yaml` — which is the *contract's* shape and not a handler's answer. The eight
handlers exist (012 T3, T4) and five of the ten board operations have still never been driven over
HTTP at all.

#### `tcms_read_board` — typed input

Gate: `board:read`. **Three branches, not three tools**, all read-only, all the same gate.

| Field | Type | Notes |
|---|---|---|
| `board` | string — a board **name** | omit for the board list; §3.4's rule, one noun over |
| `backlog` | boolean | read the ranked backlog instead of a board's cards |
| `labels` | boolean | read the project's labels instead |
| `label` · `assignee` | string[] — **names** | OR within, AND across (scope §12.2) |
| `epic` | string — a key | |
| `level` | `EPIC` / `STORY` / `TASK` | |
| `priority` | `Priority`[] | |
| `unparented` | boolean | issues with no epic |
| `page` · `size` | integer | §4.6's window |

**Columns and lanes come back with their cards inside them**, not as a flat list with a `columnId`
to join on — the model is reading a board, and a join it has to perform itself is a turn spent
rebuilding the shape the endpoint already had. `rank` is passed through **as the string it is on the
wire** and is never parsed (see `RankResult.rank`).

##### Where the two name filters resolve — decided by the implementation (012 T5)

The table above says `label` and `assignee` are names and does not say against what, which is the
one thing the row could not settle: `labelId` and `assigneeId` are UUIDs on the wire and a tool that
passed a name through would send a filter matching nothing, which a model reads as *an empty board*
rather than as a mistake. So both are resolved before the request and an unknown one is refused with
the list:

| Filter | Resolved against | Why that endpoint |
|---|---|---|
| `label` | `GET /projects/{id}/labels` | The same read the tool's own `labels: true` branch serves — one list, one refusal message, shared with `tcms_write_issue label` and all three of `tcms_write_backlog`'s label arms. |
| `assignee` | `GET /projects/{id}/members` | §3.3 puts `/users` and `/teams` outside this surface as `COMPANY` scope, and an agent token holds no `company:read`. `…/members` costs **`project:read`** and answers *exactly the set the write side validates against*, so a filter built from it can never name somebody the server would refuse. |

The cost is one extra read per filtered call, paid only when the filter is given. The alternative —
filtering client-side on the display names the cards already carry — was rejected because it would
make a lane's `completion` roll-up disagree with the cards under it: the roll-up is the server's,
over the unfiltered lane.

#### `tcms_write_backlog` — typed input

Gate: **`board:rank` + `board:configure`.** `all`, not `any`, and legal only because §A3 grants both
to the same three roles; the day one of them widens, this merge is re-checked rather than assumed.

| Field | Type | Arms | Notes |
|---|---|---|---|
| `action` | `rank` / `label-create` / `label-update` / `label-delete` | all | required |
| `issue` | string — a key | rank | required for that arm |
| `after` · `before` | string — a key | rank | **exactly one**, never both — the endpoint answers 400 for both-or-neither, and the tool refuses it before the call |
| `label` | string — a **name** | label-update · label-delete | resolved against `tcms_read_board`'s label list |
| `name` | string | label-create · label-update | |
| `color` | string — a token name, `label-3` | label-create · label-update | a token, never a hex value; a stored `#RRGGBB` is invisible in the other colour scheme |

`rank` answers `RankResult`, and **`rebalanced: true` means the model's own copy of the order is
stale** — the read is made again rather than trusted, which is the same instruction the SPA follows.

#### The two arms `tcms_write_issue` gains

Both are `issue:edit`, which that tool already gates on, so they are arms rather than a third tool.

| Arm | Kind | Permission | Writes to |
|---|---|---|---|
| `move-card` | `state` | `issue:edit` | `POST /boards/{boardId}/cards/{key}/move` |
| `label` | `state` | `issue:edit` | `PUT /issues/{key}/labels` |

**`move-card` is not a duplicate of `move`.** `move` names a status and reaches
`POST …/transitions`; `move-card` names a **column** and lets the server resolve which of the
statuses that column claims the card takes (D2). On a board whose column claims one status they
agree; on a stakeholder board's three-status column only `move-card` can be spelled at all, because
the caller does not know which status the column means.

`label` **replaces the whole set**, matching the endpoint: the field is the labels the issue should
have afterwards, not the ones to add. A model that sends one label removes the rest, and the
description says so in those words.

#### `move-card` needs a board, and the tool takes one — decided by the implementation (012 T5)

`POST /boards/{boardId}/cards/{key}/move` has a board in its path and neither §3.5's table above nor
§6's spells one, because every project is seeded exactly one and naming it would be an argument with
a single possible value. It is not a single value once a project grows a second board, so the arm
takes an optional `board` **name** and refuses with the list when it is omitted and there is more
than one. Defaulting silently to the first would move a card on a board nobody was looking at — and
because a board is a lens, the *move* would still be right and the caller's account of it wrong,
which is the failure that reads as correct behaviour. `tcms board move --board` is the same
decision, and `boardIn` / `boardOf` are the one place each surface answers it.

#### The §8.1 gap, again, for all ten board operations

§3.4's measured gap covers these too and for the same reason: `declaredPermissionsOf` matches the
literal word **`Requires`**, and the board operations state their gates as `` `board:read`, which
every role holds `` and *"Costs **`issue:edit`**, not a `board:move`"*. So §8.1's permission
assertion is **skipped, not satisfied**, for every arm added here. §8.3's header half does cover
them, because it reads `parameters` rather than prose — and it found one shortfall, recorded in
`writeConformance.test.ts`'s `CREATE_WITHOUT_A_KEY`: `PATCH /labels/{labelId}` declares no
`If-Match` and `Label` carries no `lockVersion` on the wire to precondition on, so two people
renaming one label at once lose a rename.

### 3.6 The release verbs (013 T4) — two tools, because the gates split two ways

The release contract (013 T1) adds eleven operations. §2's rule splits them three ways, and the split
is the reason there is no single `tcms_write_release` covering all of them:

| Operation | Gate | Roles holding it (§A3) |
|---|---|---|
| `listReleases` · `getRelease` · `getReleaseReadiness` · `getRoadmap` | `release:read` | **six of seven — not Automation Service** |
| `listIssueTestCases` · `listCaseVerifications` | `issue:read` | Test Engineer and up, plus Viewer and Automation Service |
| `createRelease` · `updateRelease` · `deleteRelease` | `release:manage` | **Project Manager and up** |
| `publishRelease` | `release:publish` | **Project Manager and up** |
| `putIssueTestCases` | `issue:edit` | Test Engineer and up |

`release:manage` and `release:publish` are granted to the **same three roles**, so they merge into
one `tcms_write_release` without violating anything: §2's rule is that a tool must not be listed to a
caller who 403s on part of it, and two keys with one audience cannot produce that caller. Two keys,
one tool.

**`putIssueTestCases` does not join them.** It costs `issue:edit`, and `Agent ∩ Test Engineer` is a
reachable credential holding `issue:edit` and neither release key — §2's failure exactly. So it
becomes a **`cases` arm on the existing `tcms_write_issue`**, beside `label`, which is where every
other `issue:edit` write already lives.

#### `release:read` is the first gate on this surface an Automation Service credential does not hold

Every read tool before this one is listed to every credential that can reach the project at all.
`tcms_read_release` is not, and that is correct rather than awkward: a CI token posts results and has
no business reading a delivery plan. It is worth an explicit assertion in `server.test.ts` rather
than a tool count — a narrowing that nothing tests is a narrowing that comes back.

#### `tcms_read_release` — typed input

Gate: `release:read`. **Three branches, not three tools**, all read-only, all the same gate.

| Field | Type | Notes |
|---|---|---|
| `release` | string — a **version** | omit for the list; §3.4's rule, one noun over |
| `readiness` | boolean | with `release`: the full readiness rather than the summary |
| `roadmap` | boolean | the timeline instead of the list |

`release` is resolved against `GET /projects/{projectId}/releases` before the request, and an unknown
version is refused **with the list** — `tcms_read_board`'s label-and-assignee rule, for its reason: a
version passed through as a filter matching nothing reads to a model as *an empty release* rather
than as a mistake.

#### The one number a model must not round

`ReadinessSummary.casesPassed` and `casesFailed` are **null, not zero**, when `hasRun` is false, and
every rendering on both surfaces has to keep them that way. `tcms read` output and
`tcms release list` print the words **`no run yet`**; a `?? 0` anywhere between the wire and the
terminal turns *"nothing has been tested"* into *"nothing failed"*, which is the one sentence this
whole module exists to stop the product saying.

#### `putIssueTestCases` sends case KEYS on both surfaces

§4.4's rule: display keys in both directions. The endpoint takes `caseIds` as UUIDs, and neither a
person nor a model has one — `TC-311` is what a story lists and what a test plan names. Both surfaces
resolve keys through the project's case list before the request and refuse an unknown one by name.

#### `tcms_write_release` — typed input

Gate: **`release:manage` + `release:publish`.** `all`, not `any`, and legal only because §A3 grants
both to the same three roles; the day one of them widens, this merge is re-checked rather than
assumed. `writes.test.ts` asks the question mechanically — it walks §A3 and asserts that no role
holds one key without the other — so the re-check is a red test rather than somebody remembering.

| Field | Type | Arms | Notes |
|---|---|---|---|
| `action` | `create` / `update` / `delete` / `publish` | all | required |
| `release` | string — a **version** | update · delete · publish | required for those, refused for `create`; resolved against `GET /projects/{id}/releases` |
| `version` | string | create · update | the new version on `create`, a **rename** on `update` — which recomputes the ordering key, so the row moves |
| `description` | string or null | create · update | |
| `targetDate` | date or null | create · update | a date in the past is accepted; a release that slipped is a real state |

Arms, and the header each one's `kind` implies (§4.2/§4.3):

| Arm | Kind | Permission | Writes to |
|---|---|---|---|
| `create` | `create` | `release:manage` | `POST /projects/{projectId}/releases` — carries `Idempotency-Key` |
| `update` | `edit` | `release:manage` | `PATCH /releases/{releaseId}` — carries **no** `If-Match`; see below |
| `delete` | `delete` | `release:manage` | `DELETE /releases/{releaseId}` |
| `publish` | `state` | `release:publish` | `POST /releases/{releaseId}/publish` |

**`delete` is a `delete` kind and not a `state`, and that distinction is the whole of the consent
gate.** Both kinds imply no header, so every assertion in `writeConformance.test.ts` passes either
way and nothing in the tree would have noticed the difference — but `annotationsFor` reads the kind
for `destructiveHint`, and `server.ts` refuses outright when there is no channel to ask on. An arm
that removes a row and calls itself a state transition loses its prompt silently. The CLI's
`release delete` is declared the same way for the same reason; plan 013 T4's own step spelled both
as `state`, and this is the correction.

**`update` sends no `If-Match`, and it is a recorded shortfall rather than an oversight.**
`PATCH /releases/{releaseId}` declares no such parameter and `Release` carries no `lockVersion` on
the wire, so there is nothing to precondition on — the third instance of the wall
`tcms_write_issue.update` and `tcms_write_backlog.label-update` already meet, and recorded the same
way in `writeConformance.test.ts`'s `CREATE_WITHOUT_A_KEY` with what it costs. It is bounded by the
endpoint being a **true delta** over three fields.

**Publishing is not gated on readiness, and the tool says so.** A team may ship a version with a
known failure and record that they did; a client that refused would be asserting a release policy
this product has no way to know. The description tells the model to read `tcms_read_release` and
*relay* what it says rather than withhold the call.

#### The two arms `tcms_write_issue` gains — and why `release` is one of them

Both are `issue:edit`, which that tool already gates on.

| Arm | Kind | Permission | Writes to |
|---|---|---|---|
| `cases` | `state` | `issue:edit` | `PUT /issues/{key}/test-cases` |
| `release` | `edit` | `issue:edit` | `PATCH /issues/{key}` — carries no `If-Match`, same wall as `update` |

`cases` is §3.6's own conclusion. **`release` is decided by the implementation (013 T4)** and is not
in the eleven operations above, because it is an *amendment*: `IssueUpdate` gained `releaseId`, and
without an arm for it the MCP could create a release, read it and publish it while having no way to
put a single story into one. It takes the **version**, resolved the same way `tcms_read_release`
resolves it, and an explicit `null` clears the target — absent is refused rather than treated as a
clear, because on a delta `PATCH` an absent field means *leave it* and a caller who named neither
would otherwise silently unschedule the issue.

**It is not offered on `create`, and that is recorded rather than silent**:
`shapesContract.test.ts`'s `UNOFFERED` carries `IssueInput.releaseId` with the reason — the product
itself targets releases by retargeting from the backlog (D-l: N `PATCH`es from a list somebody is
looking at), not at the moment a story is raised, and offering both would be two handles on one
field.

### 3.7 The import verbs (014 T1) — and why MCP gets none of them

**No tool, and this follows from §3.3 rather than from taste.** Every operation in plan 014 costs
`issue:import`. `Agent` is Test Engineer minus `company:read` (`V52`, §210), and `issue:import` is
one of the three keys on the **Test Engineer → Test Lead** line — `SeedDataTest`'s
`testEngineerToTestLeadBoundary` asserts that delta exactly, in both directions. So no MCP token
that can exist holds it, and a tool would be a definition nobody's `tools/list` will ever include:
§3.3's dead schema, arriving through a permission rather than through a decision.

**The CLI gets two, both reads** — see §6. It runs on a person's token, so a Test Lead reaches
them, and the release verbs (§3.6) already established that this surface serves operations `Agent`
cannot. The two writes are absent on purpose: `POST /imports` takes a Jira credential, which as a
CLI argument or an environment variable is shell history and a CI log — and the mapping step is a
confirmation a person makes **having read a preview**, which a flag file is that confirmation with
the reading removed. That is the property D32 exists to create.

**The five operations, recorded here so the absence is legible as a decision.** Three of them are
`POST` with a body carrying the credential — a token in a query string is in the access log, the
proxy log and the browser history — which is a second reason none of them is a tool: this surface's
`kind: 'read'` arms are the ones a model composes freely, and these three write to a third party.

| Operation | `operationId` | Gate | MCP | CLI |
|---|---|---|---|---|
| `POST /imports/source-projects` | `listSourceProjects` | `issue:import` | — | — |
| `POST /imports/preview` | `previewImport` | `issue:import` | — | — |
| `POST /imports` | `startImport` | `issue:import` | — | — |
| `GET /imports` | `listImportRuns` | `issue:import` | — | `tcms import list` (014 T6) |
| `GET /imports/{importRunId}` | `getImportRun` | `issue:import` | — | `tcms import show` (014 T6) |

The two CLI rows were **declared here and spelled in §6 by 014 T6**, which has landed: `import` is
in `COMMANDS` as two reads and this table is what says why the other three never will be.

---

## 4. Cross-cutting rules — all of them in the command core

### 4.1 No `projectId`, no `companyId`, in any schema

The token is bound to one company and one project. §6w.3 required that context come from the token
and *"never from a tool argument"*; making the parameter **not exist in any tool schema** is the
enforceable spelling of that, and it is what §6w.3 itself already specified. The CLI takes
`--project` only because a person may hold a token for several; the core resolves it before the
client call, and the MCP adapter never offers it.

### 4.2 `Idempotency-Key` on every create arm — and the two the contract is missing

Verified against `openapi.yaml`: carried by cases, case clone, plans, plan clone, runs, run clone,
ingestions (required there), ~~work-items~~ **issues** (011 T5 — `POST /issues` inherited the
parameter when `POST /work-items` was removed), projects, users. **Absent from `POST …/suites` and
`POST …/doc-pages`** — the WIP plan's §6.1 audit, confirmed. **`POST /issues/{key}/comments` is
absent too**, which is why that arm is declared a `state` rather than a `create` on both surfaces.

Both are inside this surface, so **the contract addition is a prerequisite of tools 9 and 11**, not
a task to remember before the write phase. Conditions and shared steps stay without one, on their
own recorded reasons, and are outside the grant anyway.

The MCP adapter derives the key from the tool-call id; the CLI generates one per invocation and
accepts `--idempotency-key` for a scripted retry.

### 4.3 `If-Match` with `lockVersion` on every edit arm

The contract is explicit that *"omitting it is last-write-wins"*. A command that does not thread the
version silently clobbers a human's concurrent edit with no error, which is the single worst thing
this surface can do.

The rule has a structural consequence: **an edit arm may only exist where the paired read returns
`lockVersion`.** Verified present on `Suite`, `TestCase`, `Condition`, `TestPlan`, `DocPage` and the
execution detail. Where a write's contract records *"no `lockVersion` field"* — the run/plan status
transitions, whose precondition is the state rather than the version — the arm sends none, and its
tool description says so rather than leaving a model to guess.

### 4.4 Display keys in both directions

Every id parameter accepts either the display key (`TC-42`, `SU-7`) or a UUID; every response names
keys. `GET …/cases?q=` already short-circuits a key-shaped `q` to an exact lookup (§6q), so the read
path costs nothing. This is the WIP plan's *"UUIDs resolved to display keys"* requirement, and it is
also what makes a tool result readable at all — keys are company-sequential (§6t), so they are what
a human and an agent both name the row by.

### 4.5 Refusal copy — §6.2's table, unchanged

| Situation | What may be said |
|---|---|
| Past the read gate, write refused (403) | name the missing permission and the **lowest built-in role that holds it** |
| Not past the read gate (404), or an empty permission answer | **nothing about roles** — "no such project, or you cannot see it" |

§6t's 404-not-403 rule is what this protects: `GET /me/permissions` answers the empty set
identically for an unreachable project and a non-existent one *"so this endpoint cannot be used to
probe for projects in other companies"*. Getting the table backwards turns the MCP server into the
enumeration oracle §6t exists to prevent — so this belongs in the core, computed in one place, and
never composed at a call site.

### 4.6 Output budgets

Default page size **25** on every list tool. **Four of the six lists have no pagination on the wire
at all** (§140) — suites, plans, runs and doc pages answer a plain array of the project's rows, as
`core/resolve.ts` records from the other side — so the page size there is a **client-side window
over a response that already arrived whole**. It bounds the tokens the model sees, which is what
this budget is for, and it buys the server nothing; the result says `truncated` rather than `more`,
because implying a next page the endpoint does not have would be worse than saying so.
`_meta["anthropic/maxResultSizeChars"]` is raised above
the default on exactly two tools — `tcms_open_doc_page` and `tcms_open_case` — because both return
one authored document whose truncation would be silently misleading grounding. Every other tool
paginates instead of raising its ceiling.

### 4.7 Audit

Every call is already audited by the endpoint it wraps; **the surface adds no audit code.** §6w.3's
*"`via = MCP`"* is not implementable as written (§0.6) and is not implemented: the carriers are
`actor_token_id` — real since §132, and previously empty for two milestones — plus the `user_agent`
the MCP server and CLI each set to a stable identifier. The tool name is **not** recorded, because
`action` already carries the operation and a tool name is a client-side alias for it.

§6w.3's requirement itself is met and is worth restating in its own words: *"An agent did it" is
never an acceptable audit line; "Ana's agent, holding Ana's grants, did it" is.* `actor_user_id` +
`actor_token_id` is that line.

---

## 5. The one blocked arm

**`tcms_write_case`'s create arm does not ship until phase 6's `ai_proposal` does.**

§1.3 of the WIP plan is `[SETTLED]`: *edits direct, creates as proposals*. Shipping a direct create
first would ship precisely what the review surface exists to prevent, and it would then have to be
taken away from a working tool — the worst version of this, because an agent's prompt history would
carry the old affordance.

The edit arm ships in phase 7 as normal. Until phase 6, `tcms_write_case` lists with a create arm
that is **absent from the schema**, not present-and-refusing.

This also settles the shape of §8 question 2 (*where the "new vs edit" line falls*) at the tool
layer, though not the question itself: the arm boundary **is** the line, so the candidate rule
already written there — *anything naming an existing case id and threading `If-Match` is an edit;
anything that would allocate a display key is a create* — falls out of §4.3 and §4.4 rather than
needing a separate decision. A bulk generation therefore decomposes per item.

---

## 6. The CLI surface

`tcms <noun> <verb>`, one command per operation the `Agent` role can reach — approximately ~~45~~ ~~53~~ 61,
against the MCP surface's ~~21~~ ~~28~~ 30. **The registry holds ~~43~~ ~~53~~ ~~55~~ 58** (`COMMANDS`, measured
015 T4, eight of them 012's board verbs, ten 013's release verbs, two 014's import reads and three
this slice's issue verbs),
and the two numbers have
never met: the count above is what the
`Agent` grant *reaches*, the registry is what has been *spelled*, and §6's own struck-through
paragraph names the families — `report` and `triage` — that are still reachable and unoffered. It is wide on purpose (§1): `--help` is paged and nobody loads all of
it.

- ~~**Nouns match the MCP tool families exactly** — `case`, `suite`, `plan`, `run`, `doc`, `review`,
  `work-item`, `report`, `triage`~~ → **superseded 2026-08-26.** Three of those nouns —
  `work-item`, `report` and `triage` — have **no CLI command at all**, and never did. What is true
  is the weaker claim, which is still the useful one: **where both surfaces offer a capability they
  spell it the same way**, so a person who has read one can predict the other for everything it
  covers. Which actions each surface offers is declared per action in the shape catalogue
  (`ActionSpec.cli` / `.mcp`) and printed by `tcms format`, so the asymmetry is a checked fact
  rather than a sentence here — see §253.
  - ~~Three of those nouns~~ → **two, since 011 T5.** `work-item` is gone as a noun and `issue` is
    the one that replaced it, and the CLI **delivers** it: `tcms issue list`, `issue show`,
    `issue create`, `issue move`, `issue comment`. `report` and `triage` are the two that remain
    undelivered, and the comment in `cli/src/reads.ts` that recorded the asymmetry now names those
    two rather than going on claiming a gap that has closed.

**The five issue commands**, and how each is spelled (§3.4 carries the typed inputs and the reasons):

| Command | Kind | Permission | Reaches |
|---|---|---|---|
| `tcms issue list [--level L] [--epic K] [--parent K] [--unparented] [--sort F]` | read | `issue:read` | `GET /issues` |
| `tcms issue show ACME-124` | read | `issue:read` | `GET /issues/{key}` + comments + doc-links |
| `tcms issue create --type Story --title T [...]` | `create` | `issue:create` | `POST /issues` |
| `tcms issue move ACME-124 --status "In Review"` | `state` | `issue:edit` | `POST …/transitions` |
| `tcms issue comment ACME-124 --body TEXT` | `state` | `issue:comment` | `POST …/comments` |

`--type` and `--status` take **names**, not UUIDs, for §3.4's reason — and at a terminal the reason is
sharper still, because nobody has a `issue_status` UUID to paste. `issue create`'s option list is
rendered from the catalogue by `optionSpecFor`, so a field added there cannot be missing a flag here;
`format.test.ts` is what asserts it. There is **no `tcms issue edit`**: the three issue writes here are
the ones a person or a CI step actually reaches for, and the field-by-field update stays on the MCP
side, where it is composed from a read the model has already made.

**The three issue commands 015 T4 adds**, so the noun now carries eight of the CLI's commands:

| Command | Kind | Permission | Reaches |
|---|---|---|---|
| `tcms issue queue [--page N] [--size N]` | read | `issue:read` | `GET /issues` with **both** queue terms |
| `tcms issue context ACME-124` | read | `issue:read` | `GET /issues/{key}/agent-context` |
| `tcms issue attach-pr ACME-124 --pr-url URL` | `edit` | `issue:edit` | `PATCH /issues/{key}` |

Three things there are decisions rather than spellings:

- **`issue queue` takes no `--assignee`.** The owner comes from `/me`, for §3.4's reason: a flag
  there would let a token read somebody else's queue.
- **An empty queue prints a sentence, not `0 of 0`.** It is the ordinary answer on a project that
  has flagged nothing, and a bare zero reads as a failed request.
- **`--pr-url`, not `--url`, and this one was found rather than chosen.** `--url` is a GLOBAL flag
  naming the instance, so `attach-pr --url https://github.com/…/pull/482` parses as a command
  pointed at GitHub and writes to `PATCH /northwind/…/pull/482/api/v1/issues/…`. It fails loudly
  rather than silently, and it is still a flag nobody could use; the contract-exchange check named
  it. `issue create` gains `--agent-eligible` for free, rendered from the catalogue by
  `optionSpecFor` — the flag exists because the field does, which is what that renderer is for.

**The eight board commands** (012 T5; §3.5 carries the typed inputs and the reasons). `board` is the
third noun the CLI gains, and unlike `work-item` it arrives delivered rather than promised:

| Command | Kind | Permission | Reaches |
|---|---|---|---|
| `tcms board list` | read | `board:read` | `GET /projects/{projectId}/boards` |
| `tcms board show "Team board"` | read | `board:read` | `GET /boards/{boardId}` + `…/cards` |
| `tcms board move ACME-124 --column "In flight"` | `state` | `issue:edit` | `POST /boards/{boardId}/cards/{key}/move` |
| `tcms backlog [--label L] [--assignee A] [--epic K] [--level L] [--unparented]` | read | `board:read` | `GET /projects/{projectId}/backlog` |
| `tcms rank ACME-124 --after ACME-99` · `--before ACME-98` | `state` | `board:rank` | `PUT /issues/{key}/rank` |
| `tcms label list` | read | `board:read` | `GET /projects/{projectId}/labels` |
| `tcms label add --name Flaky --color label-3` | `create` | `board:configure` | `POST /projects/{projectId}/labels` |
| `tcms label set ACME-124 --labels Flaky,Regression` | `state` | `issue:edit` | `PUT /issues/{key}/labels` |

Three things about that table are decisions rather than spellings:

- **`--column`, `--label` and `--labels` take NAMES**, for §3.4's reason and more sharply here:
  nobody has a `board_column` UUID to paste. An unknown one is refused by listing what the board has.
- **`--after` and `--before` are mutually exclusive and one is required.** The endpoint answers 400
  for both-or-neither; the CLI refuses it before the call, because a terminal round-trip to learn
  that is a round-trip a `--help` line could have saved.
- **`label set` replaces the whole set.** `--labels` is what the issue should carry afterwards, and
  omitting one removes it. There is no `label unset`: an add/remove pair over a multi-select lets a
  stale client remove a label somebody else just added, which is the same argument the endpoint makes.
- **There is no `tcms board create`/`edit`/`delete`.** Defining columns is a screen with a
  status-claim rule (§3.5); the CLI reads the boards a project has and moves cards on them.
- **`--board NAME` is an eighth flag, not a ninth command** (012 T5). `board show` names its board
  as a positional and `board move` does not, because the move's board is almost always the only one
  the project has — so it is optional, and omitting it on a project with two is refused with the
  list rather than defaulted. See §3.5 for the argument, which is the same on both surfaces.
- **A WIP breach prints and the command still exits 0.** `board move` writes the breach to *stderr*
  and returns `ok`: the card is where it was asked to go, and a non-zero exit would make a limit
  something a CI step fails on — story B3's "a limit people route around" in its automation
  spelling. `tcms rank` does the same with a `rebalanced` answer, which is a success carrying an
  instruction to read the list again, not an error.
**The ten release commands** (013 T4; §3.6 carries the typed inputs and the reasons). `release` is
the fourth noun the CLI gains, and `roadmap` is a bare verb for `backlog`'s reason — it is the one
view of the project rather than one of several things a noun has:

| Command | Kind | Permission | Reaches |
|---|---|---|---|
| `tcms release list` | read | `release:read` | `GET /projects/{projectId}/releases` |
| `tcms release show 2.4` | read | `release:read` | `GET /releases/{releaseId}/readiness` |
| `tcms roadmap` | read | `release:read` | `GET /projects/{projectId}/roadmap` |
| `tcms issue cases ACME-118` | read | `issue:read` | `GET /issues/{key}/test-cases` |
| `tcms release create --version 2.6 [--date D] [--description T]` | `create` | `release:manage` | `POST /projects/{projectId}/releases` |
| `tcms release edit 2.6 [--version] [--date] [--description]` | `edit` | `release:manage` | `PATCH /releases/{releaseId}` |
| `tcms release publish 2.4` | `state` | `release:publish` | `POST /releases/{releaseId}/publish` |
| `tcms release delete 2.6` | `delete` | `release:manage` | `DELETE /releases/{releaseId}` |
| `tcms issue release ACME-118 --release 2.4 \| --none` | `edit` | `issue:edit` | `PATCH /issues/{key}` |
| `tcms issue cases set ACME-118 --case TC-311` | `state` | `issue:edit` | `PUT /issues/{key}/test-cases` |

**Ten, and plan 013 T4's own deliverable line said eight.** The pipe-list there names the six
`release` verbs, `roadmap` and `issue cases`; its Step 2 then spells `issue release` and
`issue cases set` as well, and both are the CLI's only writer for their field — there is no
`tcms issue edit` (above), so without the first a person at a terminal can create a release and
never put anything in it. The count in the plan is the summary being loose, not a scope decision,
and it is corrected there.

Five things about that table are decisions rather than spellings:

- **The version is the handle, never the id.** `release.id` is a UUID and every one of these is
  typed `tcms release publish 2.4`, so the version is resolved against the project's own list and an
  unknown one is refused by printing what the project has — `boardOf`'s and `labelIds`' rule, with a
  sharper reason: a version is *text a person typed*, so a typo is the expected input.
- **`release list` prints `no run yet` and never `0/0`.** `ReadinessSummary.casesPassed` and
  `casesFailed` are **null and not zero** when `hasRun` is false, and a `?? 0` anywhere between the
  wire and the cell turns *nothing has been tested* into *nothing failed*. The badge column is the
  **verdict's** vocabulary and not the status's (013 D-e), except for `RELEASED`, which is the one
  state a person set.
- **`release delete` declares a `delete` kind**, so it asks first and refuses outright with no
  terminal to ask at. See §3.6 — the plan spelled it `state`, which passes every header assertion in
  the tree and silently removes the prompt.
- **`issue release` takes `--none` rather than `--release ''`.** The empty-string clearing form the
  nullable text flags use is right for free text and wrong for a resolved reference, where `''`
  would have to be looked up before it could be recognised as a clear. Exactly one of the two flags
  is required, refused locally, for `rank`'s reason.
- **`issue cases set` replaces the whole set**, matching the endpoint, and takes case **keys**
  (§4.4). It is the surface's only three-word command name, which is why `findCommand` and
  `guideCopies.test.ts` both try three words before two — shortest-first would read
  `issue cases set ACME-118` as the *read* command with `set` as the issue key.

**The two import commands** (014 T6; §3.7 carries the operation table and the reason MCP gets
none of them). `import` is the fifth noun the CLI gains, and it is the first that arrives **read-only
by design** rather than because its writes have not been reached yet:

| Command | Kind | Permission | Reaches |
|---|---|---|---|
| `tcms import list` | read | `issue:import` | `GET /imports?projectId=…` |
| `tcms import show <importRunId>` | read | `issue:import` | `GET /imports/{importRunId}` |

**There is no `tcms import run` and no `tcms import preview`, and the absence is a decision that is
recorded here so it is not read as an unfinished slice** (plan 014 D-l). Two reasons, and neither is
effort:

- **The credential.** `POST /imports` and `POST /imports/preview` carry a Jira API token in the
  body. At a terminal that token arrives as an argument or an environment variable, which is shell
  history and a CI log — and 014 D-a's whole point is that this credential has the shortest possible
  life: it is held for one operation and written nowhere, not on `import_run`, not encrypted, not
  anywhere. A CLI flag would defeat that in the one place the rest of the design cannot reach.
- **The confirmation.** The mapping step is a confirmation a person makes **having read a preview**.
  A flag file passed to a command is that confirmation with the reading removed, which is precisely
  the property D32 exists to create. `import-mapping.html`'s disabled Confirm and the endpoint's
  `400` on an unmapped status are the same argument from the other two sides.

Three things about the two that *are* here are decisions rather than spellings:

- **`import list` takes no `--project`.** The endpoint's `projectId` is a query parameter rather
  than a path segment, which makes this the easiest place on the surface to slip: no CLI command
  takes a project id (§4.1), so it is resolved from the context like every other project-scoped
  read and `--project` remains the one context flag.
- **`import show`'s dropped-field table is not truncated**, alone on this surface. §4.6's budget
  windows every other list, and the exception is the point of the module: the dropped list is the
  artifact the whole feature exists to produce, and a report that elides the thing it records
  answers *that field did not survive* and *it is not on screen* in the same words. The budget
  applies instead to the **status mapping**, a listing of what did arrive, which is capped and
  carries a *"n more"* line.
- **A run is named by its id, not by anything friendlier.** `release show 2.4` takes text a person
  typed; a run has no such handle, so `import list` prints the ids beside the dates that tell them
  apart.

- **Read commands are phase 5, writes are phase 7**, per the plan's order.
- **`--json` on every command**, so the CLI composes in a shell. Human-readable output is the
  default and the JSON is the generated client's response verbatim.
- **`--project` is the only context flag**, per §4.1.
- Refusal copy, keys, idempotency and `If-Match` all come from the core, so a CLI command and its MCP
  counterpart cannot disagree about any of them.
  - **This was aspirational until 2026-08-26 and the CLI half was simply not wired.** `describeRefusal`
    had exactly one consumer — `mcp/src/result.ts` — and the CLI printed the server's raw `detail`
    instead, so the same 403 read *"this token's role does not hold `case:edit`. Ask a project
    administrator…"* through a host and *"You do not have permission to do this."* in a terminal.
    The safe direction, and the wrong surface: the CLI is the one a **person** uses to find out what
    to ask for. Both adapters now go through `core/outcome.ts`, which classifies the failure and
    composes the copy once.

---

## 7. Reversals to write into §6w

Per the working agreement — *write superseded decisions and their reversal into the docs, struck
through, so the next reader doesn't think the old reasoning was never considered.* All five go into
[`docs/plans/2026-07-31-ai-integration-mcp-grounded-generation-and-coverage.md`](../plans/2026-07-31-ai-integration-mcp-grounded-generation-and-coverage.md)
§6w.3's Wave-1 list and §6w.4, struck through with a pointer here.

| §6w said | Now | Why |
|---|---|---|
| `list_criteria` | **Dropped** | No `acceptance_criterion` table exists (§0.1), and this product resolved ERD question #18 by deciding a requirement **is** a doc block (§0.2). `tcms_open_doc_page` is the tool §6w was reaching for, and it is the better one — a block id is permanent, so a link survives an edit to the text. |
| `coverage_summary` | **Deferred and demoted** | Not a tool. Per-block coverage is a three-module query that §81's rule puts in `work` (§0.4); when it lands it is a field on `tcms_open_doc_page`'s response. |
| `get_method` (the resolved skill bundle) | **Dropped from v1** | §6w.2a's bundles and `ai_provider` do not exist, and §6w.3's own argument is that an MCP agent brings its own model *and its own method*. |
| `repository_health` | **Folded** into `tcms_triage` | It is one read among four that answer one question, and four tools for one job is what §2's budget is spent avoiding. |
| audit `via = MCP` | **Not implementable as written** | `audit_event` has no `via` column (§0.6). `actor_token_id` + `user_agent` carry it; the requirement §6w.3 stated in prose is met. |

`link_case_to_criterion` (Wave 2) is **not** a reversal — it is renamed and shipped as an arm of
`tcms_write_doc_page`, because the edge it names is `doc_link` and that edge has existed since §93.

---

## 8. Testing

**A computed check, not a copied table** — the schema-wide-invariant rule, whose most expensive
instance is §108.

1. **Gate conformance.** For every tool, assert its declared permission set equals the permission set
   the wrapped endpoints actually enforce, derived from the code rather than from a list in this
   document. A tool whose endpoint's gate changes must fail the build, not be discovered by an agent.
   - **Built 2026-08-26**, in `mcp/src/writeConformance.test.ts`, after being specified here and left
     unwritten for as long as the surface existed. It reads the gate out of the operation's own prose
     in `openapi.yaml` (*"Requires `suite:manage`."*) via `declaredPermissionsOf`, resolving the
     operation from the URL the arm actually sent. That is a weaker source than the Java annotation
     and the strongest one a TypeScript test can reach; what matters is that it is **not a list kept
     beside the tool registry**, which is the whole of the requirement. Its own vacuity guard is a
     guard on the *reader*, because prose can quietly stop answering.
2. **The §2 merge rule, derived from the catalogue.** For every multi-arm tool, assert that no role
   in `role_permission` holds a strict subset of its arms' permissions. This is what would have
   caught the `Automation Service` case in §2 had §134 not already separated the two token roles, and
   it is what will catch the next role that is added.
   - **Built 2026-08-26**, in `mcp/src/writes.test.ts`, on `roleMatrix()` — the §A3 parser
     `matrixCopies.test.ts` and `tierClaims.test.ts` already trust. Every merge is legal today, which
     is exactly why it was worth writing: nothing fails now, and a migration narrowing one key would
     otherwise take a whole tool away from every contributor with the build staying green. The
     nearest thing that existed asserted each arm's permission is inside **its own tool's** gate,
     which a gate wrong in both places satisfies.
3. **`Idempotency-Key` and `If-Match` coverage.** Assert every create arm sends a key and every edit
   arm sends `If-Match`, over the tool registry rather than per tool — the same shape as
   `RepositoryTransactionTest` deriving its repository list from the live context.
4. **Refusal copy.** Assert the 404 branch's message contains no role name and no permission key.
   This is the one test whose failure is a security finding rather than a defect.
5. **The shape catalogue against the contract (§253).** `@tcms/client`'s `core/shapes.ts` is the one
   place either adapter answers *what fields does this object take*, and it is written by hand —
   §3.0a's tools take display keys where the API takes UUIDs, so it cannot be generated. That is only
   safe with `shapesContract.test.ts` beside it, which parses `openapi.yaml` and asks five questions:
   every declared enum equals the contract's, **both ways**; every unconditionally-required field
   matches the contract's `required` array, **both ways**; every field names a real contract property
   or carries a stated `surfaceOnly` reason; every contract property is either offered or in
   `UNOFFERED` with its reason; and every nested `object[]` is pinned to its own item schema. The
   fourth is the one that finds omissions rather than errors — it is what surfaced that a plan's
   `tags` reach neither adapter. The fifth was added after the doc-page blocks shipped as
   `{ type, text }` against a `DocBlockInput` that declares `blockType`, invisible to the other four
   because they read top-level properties only.
6. **The shape catalogue against the SURFACE (2026-08-26).** §5 pins the catalogue to the contract
   and asks nothing of the ~~eleven~~ twelve write tools, ~~ten~~ eleven of which hand-write their
   `inputSchema`. So
   `tcms_shape` could tell a model one thing about a suite and `tcms_write_suite` declare another
   with nothing firing. `mcp/src/writes.test.ts` now asserts that every field the catalogue offers
   for a body-carrying arm is a property that tool accepts, and `cli/src/format.test.ts` the same
   for flags. It found three live disagreements: `tcms format plan` printed `--milestone-name`
   where the command takes `--milestone` (a flag the surface's own help screen refused in strict
   mode), the same for a run's `--release-version` / `--build-number` / `--commit-sha`, and
   `doc edit` had no `--parent` at all while `tcms_write_doc_page` accepted one.
7. **The shipped user guide against both registries (2026-08-26).** `frontend/src/features/help/`
   enumerates the tools and commands in prose, and it went stale the slice it was written in —
   `tcms_shape` and `tcms format` shipped in §253 and neither list gained them. `guideCopies.test.ts`
   in each package compares the prose to the registry, both ways for the tools, and to the tool
   count beside it.

## 9. Verification obligations (§65)

Per the WIP plan's §9 and the standing policy: rows go in
[`docs/verification/backend-mutation-checks.md`](../verification/backend-mutation-checks.md) for the
agent-token path and every write arm, in executable form. **Nothing goes in the frontend queue** —
neither a CLI nor a remote MCP server is a browser lane.

Landing any implementation phase without those rows skips the check outright, which is what batching
exists to avoid.

---

## 10. Scope boundary

This document specifies **the surface**. It does not specify the MCP server's transport and
deployment (§4 of the WIP plan, `[SETTLED]` there), the TS client's generation (phase 4), rate
limiting (phase 9), or `ai_proposal`'s schema and review UI (phase 6). Each is its own slice, and
each now has a surface to build against.
