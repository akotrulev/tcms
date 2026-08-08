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
  One gate, one tool.
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

## 3. The MCP surface — 21 tools

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
| 9 | `tcms_write_doc_page` | `doc:edit` | `POST`/`PATCH …/doc-pages`, block link `POST`/`DELETE` | **Buildable after §4.2** |
| 10 | `tcms_write_case` | `case:create` + `case:edit` | `POST`/`PATCH …/cases`, submit-for-review | **edit arm buildable; create arm blocked — §5** |
| 11 | `tcms_write_suite` | `suite:manage` | `POST`/`PATCH …/suites`, membership, `PUT …/status` (submit / withdraw only) | **Buildable after §4.2** |
| 12 | `tcms_review_comment` | `case:comment` | `GET …/reviews`, `POST …/comments` | **Buildable** |
| 13 | `tcms_review_verdict` | `case:review` / `suite:review` / `plan:review` | participations, assignees, comment resolution | **Buildable** |
| 14 | `tcms_list_plans` | `case:read` | `GET …/plans`, `…/{planId}`, `…/statistics` | **Buildable** |
| 15 | `tcms_write_plan` | `plan:create` + `plan:edit` | `POST`/`PATCH …/plans`, `PUT …/status` (submit / withdraw only) | **Buildable** |
| 16 | `tcms_open_run` | `case:read` | `GET …/runs`, `…/{runId}`, `…/executions/{executionId}` | **Buildable** |
| 17 | `tcms_write_run` | `run:create` | `POST …/runs`, `POST …/runs/{runId}/clone` | **Buildable** |
| 18 | `tcms_record_result` | `run:execute` + `result:submit` + `evidence:attach` | `PATCH …/executions/{executionId}`, `POST …/evidence` | **Buildable** |
| 19 | `tcms_triage` | `case:read` + `report:view` | dashboard, quarantine state, `GET …/ingestions`, `…/automation-mappings` | **Buildable** |
| 20 | `tcms_write_work_item` | `workitem:manage` + `defect:create` | `POST`/`PATCH /work-items` | **Buildable** |
| 21 | `tcms_report` | `report:view` + `report:export` | `POST …/reports/generate`, poll, download | **Buildable** |

Eighteen are buildable against today's contract; two need §4.2's contract addition first; one has a
blocked arm (§5).

`tcms_review_verdict` declares three permissions and is the one tool listed on a **per-subject**
basis rather than outright: a caller holding `case:review` but not `plan:review` sees it, and its
description names which subjects it will accept. That is the honest reading of §2 — the three keys
are one gate applied to three nouns, not three arms — and it is why the resolution of a comment
thread sits here rather than beside the commenting: §A3 is explicit that resolving a thread is not
commenting and costs the subject's `*:review`.

### 3.1 The two composites, and why only two

`tcms_orient` and `tcms_open_case` are the only tools that fan out to more than one endpoint for a
*read*. They exist for one reason, which is §6w's own stated value: an agent must ground itself in
the repository that exists before proposing anything, and **an agent that has to assemble the
project's shape from six list calls will skip it and generate blind** — which is exactly the
file-based-tool failure mode §6w was written against.

Every other tool that spans endpoints does so along a single gate, per §2. No third composite is
added without the same argument, because a composite's cost is an output that is hard to bound.

### 3.2 `tcms_whoami` and the capability summary

Per §6.2 of the WIP plan, unchanged and adopted here: `_meta["anthropic/alwaysLoad"]: true`, and the
same grouped summary is rendered into the server's instructions at connect. Grouped, never a 55-row
dump — instructions truncate at 2KB. The refusal-copy constraint in §4.5 governs what it may say.

### 3.3 What is not a tool

- **Nothing gated by a permission `Agent` does not hold.** No approve, no `run:close`, no
  `result:amend`, no `doc:publish`, no delete of anything, no project configuration, no library
  write. These are absent from the surface entirely rather than present-and-refused: a tool the role
  can never hold is a definition nobody's `tools/list` will ever include, so it would be dead schema.
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
ingestions (required there), work-items, projects, users. **Absent from `POST …/suites` and
`POST …/doc-pages`** — the WIP plan's §6.1 audit, confirmed.

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

Default page size **25** on every list tool. `_meta["anthropic/maxResultSizeChars"]` is raised above
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

`tcms <noun> <verb>`, one command per operation the `Agent` role can reach — approximately 45,
against the MCP surface's 21. It is wide on purpose (§1): `--help` is paged and nobody loads all of
it.

- **Nouns match the MCP tool families exactly** — `case`, `suite`, `plan`, `run`, `doc`, `review`,
  `work-item`, `report`, `triage` — so the two surfaces share vocabulary even where they do not share
  granularity. A person who has read one can predict the other.
- **Read commands are phase 5, writes are phase 7**, per the plan's order.
- **`--json` on every command**, so the CLI composes in a shell. Human-readable output is the
  default and the JSON is the generated client's response verbatim.
- **`--project` is the only context flag**, per §4.1.
- Refusal copy, keys, idempotency and `If-Match` all come from the core, so a CLI command and its MCP
  counterpart cannot disagree about any of them.

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
2. **The §2 merge rule, derived from the catalogue.** For every multi-arm tool, assert that no role
   in `role_permission` holds a strict subset of its arms' permissions. This is what would have
   caught the `Automation Service` case in §2 had §134 not already separated the two token roles, and
   it is what will catch the next role that is added.
3. **`Idempotency-Key` and `If-Match` coverage.** Assert every create arm sends a key and every edit
   arm sends `If-Match`, over the tool registry rather than per tool — the same shape as
   `RepositoryTransactionTest` deriving its repository list from the live context.
4. **Refusal copy.** Assert the 404 branch's message contains no role name and no permission key.
   This is the one test whose failure is a security finding rather than a defect.

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
