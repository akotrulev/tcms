# §135 — the tool surface, and the requirement model that was never a criteria list

**Date:** 2026-08-08 · **Branch:** `master` · **Commit:** `8d05612` · **Design only — no code, no
migration, no contract change.**

Closes the MCP WIP plan's **§8 question 1**, which that plan called *"the only genuinely blocking
one"* and which the last four handoff headers have named as the next slice. Deliverable:
[`docs/specs/2026-08-08-mcp-tool-and-command-surface.md`](../specs/2026-08-08-mcp-tool-and-command-surface.md),
plus §6 of the WIP plan flipped `[OPEN]` → `[SETTLED]` and question 1 struck through with a pointer.

This is the first slice since §110 that ships no code, and the first design slice re-derived from the
code in the sense §132 established: the question could not be answered from the plan that asked it.

---

## 1. The blocker was the answer

The plan's own §9 carried a standing obligation — *"`list_criteria` / `get_doc_page` were specified
in §6w against a documentation module that did not exist then. **re-read the module first**"* — and
recorded it as a prerequisite to answering the question. It was not a prerequisite. **It was the
content of the answer**, and reading the module changed two of §6w's ten named read tools and
invalidated three more things beside them.

**There is no `acceptance_criterion` table and no migration has ever proposed one.** §6w.4's criteria
model is unbuilt backend work. So `list_criteria` has no data source, and `coverage_summary` —
specified as three-axis coverage *over criteria* — has none either. That much is ordinary: a tool
blocked on a slice.

What is not ordinary is the second fact. **This product already decided what a requirement is, two
years of documents ago, and it is not a criterion row.** ERD resolved question #18: there is
deliberately **no `requirement` table**; a requirement is a `doc_block` — a paragraph in somebody's
specification — and traceability is a query over `doc_link` joining those blocks to the cases that
verify them. `documentation/package-info.java` opens with this as *the decision this module is built
around*.

So `list_criteria` is **dropped rather than deferred**. It is not a tool waiting for a table; it is a
tool for a model this product refused. The thing §6w wanted from it — ground the agent in the stated
requirements before it writes the 892nd case — is served by `tcms_open_doc_page`, and served better,
because a block's id is *permanent* and links target `(page_id, block_id)`. §6w was reaching for a
surface that already existed under a different name.

**The third fact closes the Wave-2 half of it.** `doc_link` has had a writer since §93
(`BlockLinkService`), `DocBlockView.links` is real and served, and
`POST …/doc-pages/{pageId}/blocks/{blockId}/links` is gated `doc:edit` — **inside the `Agent`
grant**. §6w's `link_case_to_criterion` is therefore buildable today, as an arm of
`tcms_write_doc_page`. It is not a reversal; it is a rename.

### Five reversals, recorded with reasons

| §6w said | Now | Why |
|---|---|---|
| `list_criteria` | **Dropped** | No table, and the requirement model is the doc block (above) |
| `coverage_summary` | **Demoted to a field** | Per-block coverage is a three-module query §81's rule puts in `work`; `DocBlockView` already states this at length |
| `get_method` | **Dropped from v1** | §6w.2a's bundles and `ai_provider` do not exist, and §6w.3's own argument is that an MCP agent brings its own model *and its own method* |
| `repository_health` | **Folded** into `tcms_triage` | Four tools for one job is what the budget is spent avoiding |
| audit `via = MCP` | **Not implementable as written** | `audit_event` has **no `via` column** — it has `actor_user_id`, `actor_token_id`, `action`, `user_agent`, `request_id`. The requirement §6w.3 stated in prose is met by `actor_token_id`, which §132 made real |

The last one is the §6w-era pattern this repo keeps finding: a specification written against a schema
nobody re-read. It is cheap here because nothing was built on it.

---

## 2. The rule that decides the count, and the merge it caught in this document's own draft

The surface is **21 MCP tools over 88 REST paths**, plus **~45 CLI commands** — two shapes over one
generated client and one command core. The count is an outcome, not a target, of one rule:

> **A tool never spans two permissions on its write path.**

§6w.3 filters `tools/list` by permission, on the reasoning that *"an agent shown a tool it cannot use
will call it, be refused, and retry"*. A tool spanning two gates is therefore either **hidden** from
a caller holding one of them, or **listed** for a caller who 403s on half of it — both being the
failure the filtering exists to prevent, arriving through the merge.

**The loose statement of that rule is wrong, and this document's first draft used it.** Stated
loosely it asks *"does a role hold both permissions"*, and by that test merging commenting with
review verdicts is safe: no role in the catalogue holds a `*:review` without `case:comment`. But the
catalogue is not what a token holds. **The effective set is an intersection** —
`evaluate(ownerId, projectId) ∩ permissionsOf(token.roleId)` — so a **Viewer**-owned agent token
comes out holding `case:comment` and no `*:review` at all, and the merged tool would have been hidden
from a caller who can legitimately comment.

The correct question is *"does every **reachable intersection** hold both"*, and the reachable
intersections are `Agent ∩ each of the five assignable roles`. `tcms_review` is two tools now.

**Two further merges clear only because of §134**, one slice earlier. `Automation Service` is the one
role holding `result:submit` without `run:execute`, and `case:read` without `report:view` — and
because `V41` made it non-assignable it is never a token *owner*, so neither intersection is
reachable. Had the two token roles been collapsed, as §134 recorded being tempted to, both merges
would be defects. §134 is load-bearing for this surface in three separate places, which was not
visible when it shipped.

### The exclusion worth reading

**`POST …/cases/bulk` is not a tool**, and it is exactly the endpoint an agent would get the most
leverage from — 143 cases in one call is the shape agent work takes. It is refused because **its gate
is decided by the operation inside its payload**, so no single permission can be declared for a tool
wrapping it.

That is the same objection `documentation/package-info.java` already records, one client over: *"a
gate that depends on the payload is one the SPA cannot render a disabled state for"*. What the SPA
cannot render disabled, `tools/list` cannot filter. It stays in the CLI, where a person reads the 403
and knows which operation was refused. Splitting it into per-operation tools is available later and
is not free — it is one tool per bulk operation, against a budget that is the whole design.

---

## 3. What is buildable, and the two things blocking the rest

**Eighteen of the twenty-one are buildable against today's contract.** The other three:

- **`tcms_write_doc_page` and `tcms_write_suite`** need `Idempotency-Key` on `POST …/suites` and
  `POST …/doc-pages`. The WIP plan's §6.1 already audited this gap and filed it as *"add before the
  CLI write phase"*; it is a **prerequisite of two tools**, not a reminder, and the plan now says so.
- **`tcms_write_case`'s create arm** waits on phase 6's `ai_proposal`. §1.3 is `[SETTLED]` that
  creates are proposals, so shipping a direct create first ships precisely what the review surface
  exists to prevent — and it would then have to be *taken away from a working tool*, with the agent's
  prompt history still carrying the old affordance. The arm is **absent from the schema** until then,
  never present-and-refusing.

**§8 question 2 is settled at the tool layer as a side effect.** The create/edit arm boundary *is*
the new-vs-edit line, so the candidate rule already written there — *anything naming an existing case
id and threading `If-Match` is an edit; anything that would allocate a display key is a create* —
falls out of the `If-Match` and display-key rules rather than needing its own decision. What remains
open is only the bulk-generation semantics.

---

## 4. A stale comment found on the way past

**`documentation/package-info.java`'s "What is deliberately not built" section still says
`doc_link` has no writer.** Stale since §93. `DocBlockView`'s header records the supersession
properly, struck through and dated; the package comment — the file an agent reads *first* to orient
in the module — does not, and it is the one that says *"no block has a `coverage` marker and no case
has a `relatedDocuments` entry"* as present tense.

Not fixed here, because this slice ships no code. It belongs in the first implementation phase, and
it is the same shape as the four documents that called the Linux gate inert for two days: the
supersession was written somewhere true and not everywhere the claim appears.

---

## 5. Obligations this slice creates

- **§65.** No rows yet — the spec adds none, because a design ships nothing to drive or mutate. The
  spec's §9 carries the obligation forward to every implementation phase, and records that **nothing
  goes in the frontend queue**: neither a CLI nor a remote MCP server is a browser lane.
- **Testing, specified as computed checks** (spec §8), per the schema-wide-invariant rule whose most
  expensive instance is §108. Four of them, and the interesting one is the second: assert over
  `role_permission` that no role holds a strict subset of any multi-arm tool's permissions. That is
  the check that would have caught §2's merge without a careful reading, and the one that will catch
  the next role added to the catalogue.
- **The §6w reversals** in the table above must be written **into §6w itself**, struck through, per
  the working agreement. Listed in the spec's §7; not yet done.

## 6. Next

`ep-writing-plans` for the implementation plan, starting at the WIP plan's phase 4 (the shared TS
client). Phases 1–3 are done — §132 the two dead columns, §133 the `Agent` role and token expiry,
§134 `Automation Service`.
