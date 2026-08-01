# §6w. AI Integration — MCP server, per-company models, grounded generation and three-axis coverage

**Date:** 2026-07-31 · **Branch:** `master` · **Status:** design, not yet scheduled
**Milestone:** M4 (with the integrations — it shares §6r's encrypted-credential machinery), except
§6w.4 which is deterministic and can land earlier.
**Origin:** a comparison against [SPECTRA](https://github.com/AutomateThePlanet/Spectra) (Apache-2.0,
.NET 8, git-native Markdown test cases), read at its **v2 (Claude Code) architecture**. Several of
its ideas are worth having; its architecture is not adoptable — see §6w.9.

> **Read the v2 docs, not the v1 summary.** SPECTRA v2 **removed its MCP server** and its in-process
> model runtime (the GitHub Copilot SDK). It now makes *no model calls of its own*: every
> model-touching flow is a deterministic `compile-*` → *a turn in the user's own Claude Code
> session* → `ingest-*` seam. That is the opposite of the direction this section takes for the
> outbound half, and §6w.3 states why the difference is a consequence of TCMS being a server
> product rather than a disagreement about design.

> This document defines a new plan section, **§6w**. The master plan
> (`docs/plans/2026-07-30-tcms-high-level-plan.md`) needs a one-line pointer added to its section
> index; that edit is deliberately left out of this file because §10 of the handoff records a
> concurrent strand editing `docs/plans/`.

---

## 6w.0 The decision this section exists to prevent

The tempting shape is "add AI to TCMS": one setting, one key, a *Generate* button that writes test
cases into the repository. That shape fails on three separate things this product has already
decided, and each failure is silent:

1. **It sends one client's specifications to a third party** on an instance whose entire selling
   point is that clients are provably separate (§6s). An egress that is not opt-in per company is a
   breach of the thing the RLS policies exist to guarantee.
2. **It writes unattributable content into a shared repository.** §6j makes every version
   attributable and §6g makes approval a human act. A generated case that arrives Approved, or
   arrives with `created_by` pointing at a human who never read it, corrupts both.
3. **It hides which half is which.** "Agents call TCMS" and "TCMS calls a model" are two systems
   sharing a buzzword — exactly the §6r mistake of conflating SSO, outbound integration and inbound
   machine access. They are separated first, below, and never share a code path.

Everything else in §6w follows from separating those.

---

## 6w.1 Three concerns, deliberately not conflated

| Concern | Question it answers | Mechanism | Needs a model key? |
|---|---|---|---|
| **Inbound — MCP server** | May *this* agent read and write TCMS on behalf of a user? | TCMS **hosts** an MCP server; the agent (Claude Code, Copilot Chat, an internal agent) is the client, authenticated by an `api_token` bound to a user and a project | **No.** The caller brings its own model. |
| **Outbound — model calls** | May TCMS call a model *itself*, for generation and criticism? | `ai_provider`, company-scoped, encrypted key, chosen model | **Yes**, and only if the company configured one. |
| **Derived — grounding & coverage** | Which documented obligations are covered, and which cases have rotted? | Deterministic SQL over `doc_block` / `acceptance_criterion` / `doc_link` / `execution_fact` | **No.** Works with zero AI configured. |

**The third column is the important one.** The most valuable idea taken from SPECTRA — acceptance
criteria with obligation levels and content hashing — needs no AI at all, and it is what makes the
other two worth having. It ships first (§6w.4) and stands alone if AI is never enabled.

**A company that configures nothing gets a fully working product**, minus a Generate button. That is
a hard requirement, the same shape as §6r's "local accounts never go away".

---

## 6w.1a AI is an option, never a condition — the capability ladder

**The rule, stated once and enforced everywhere below: no AI capability is ever load-bearing.** A key
that is absent, revoked, over budget or timing out degrades a *confidence label*; it never blocks a
write, never hides a screen, never fails a build, and never makes a number unavailable. TCMS is a
test case management system that can use a model, not a product that requires one.

The concrete consequence is that **the critic is an advisory pre-filter, not a gate**. §6g's human
review is the gate — it was the gate before §6w existed and it stays the gate. What the critic buys
is a *smaller, better-ordered reviewer queue*: fabrications dropped before a human sees them, and
assumptions named rather than hunted. Take it away and a human reviews everything, which is the
product as it ships today.

Three rungs, and every feature must state which one it needs:

| Rung | Available when | Generation | Verification of a proposal | Verified coverage (§6w.8) |
|---|---|---|---|---|
| **0 — no AI** | Always. Nothing configured. | Manual authoring only | **Human review (§6g)** | Claimed only |
| **1 — BYO agent** | An agent is connected over MCP (`mcp:connect`). **No key, no consent, no budget.** | In the caller's own agent, against real repository context | Whatever the agent asserts, recorded as `EXTERNAL` — plus human review | Claimed, with the external assertion shown but not counted |
| **2 — server-side** | `ai_provider` enabled, consent given, budget remaining, critic model reachable | TCMS's own async job | `TCMS_VERIFIED` by the isolated critic — plus human review | Counted as verified |

Note what does **not** change down the ladder: human review is in all three rows. That is the point.

### Degradation is per-attempt, not per-configuration

The ladder is evaluated **at the moment of use**, because a company sits on rung 2 until the key is
revoked at 14:00 on a Tuesday. Every one of these drops a rung and continues:

| Runtime condition | Result |
|---|---|
| No provider configured | Generation affordance disabled with a reason (§31 disable-don't-hide) |
| Provider disabled, or consent withdrawn | Same as unconfigured — consent withdrawal is immediate and total |
| Budget exhausted | The **job** fails with a named error before the first outbound call; nothing else is affected |
| Critic model unset, or the same as the generator | Generation proceeds; proposals land `UNVERIFIED`, flagged for the reviewer |
| Critic times out or errors | Proposal lands `UNVERIFIED`, distinguishably (§6w.5); the job completes |
| Key revoked mid-job | The job fails, names the cause, and points at provider health. Proposals already landed keep their verdicts |

**A dropped rung is always visible and always attributed.** Silently reviewing without a critic, or
silently counting an unverified proposal as verified, is worse than not having the critic — it is
the failure mode this section spends §6w.5 preventing, arriving through the back door.

### Verdict provenance — three values, because "verified" must mean one thing

`ai_proposal.verdict_source`:

- **`NONE`** — nobody checked but a human. Rung 0, and the honest default.
- **`EXTERNAL`** — an agent asserted a verdict over MCP. Recorded, shown to the reviewer, **never
  counted as verified**: TCMS cannot see that agent's critic, its isolation, or its prompt, so
  treating its claim as equivalent to its own would let any MCP client mint verified coverage by
  sending `verdict: grounded`. Displayed as *"the connecting agent reports grounded"*, attributed to
  the token that said so.
- **`TCMS_VERIFIED`** — TCMS ran the isolated critic itself, under a known model and prompt version.

This is the §6w.8 claimed-vs-verified split reappearing one layer down, and it is the same argument:
the headline number must not be inflatable by anything other than a check TCMS actually performed.

---

## 6w.2 Per-company AI provider — key, model, and consent

### The entity

```
ai_provider (company_id, …)         -- one row per configured provider, per company
  ├─ kind            ANTHROPIC | OPENAI | AZURE_OPENAI | BEDROCK | GOOGLE | OPENAI_COMPATIBLE
  ├─ base_url        for OPENAI_COMPATIBLE — self-hosted / vLLM / Ollama
  ├─ credential      encrypted at rest (§6r rules, verbatim)
  ├─ generation_model / critic_model
  ├─ enabled         BOOLEAN
  ├─ egress_consent_at / egress_consent_by   -- see below
  ├─ monthly_token_budget / tokens_used_this_period
  └─ last_check_at / last_check_error
```

`OPENAI_COMPATIBLE` is not decoration: a self-hosted TCMS whose client forbids sending
specifications to any SaaS is a real deployment, and pointing at a local vLLM or Ollama endpoint is
the only configuration that makes AI usable there. Excluding it makes the feature unavailable to
precisely the most privacy-sensitive users, who are the ones who chose self-hosting.

### Rules

- **`company_id NOT NULL`, RLS-forced, on the normal path.** Nothing about this table is
  instance-level, and it does not go near the ERD §0.1 exception list. One company's key is
  unreadable by another company's session, by the same mechanism as everything else.
- **The credential is encrypted at rest** with a key from configuration or KMS, per §6r —
  never plaintext in Postgres, **never in an audit event, an error message, or a log line**, and
  never returned by any endpoint. The API exposes a masked hint (`sk-…4f2a`) and nothing more.
- **The model is the company's choice, not the operator's.** `generation_model` and `critic_model`
  are free-text model ids validated against a per-kind catalogue that is *advisory* — an unknown id
  is a warning, not a rejection, because model ids change faster than releases do. Refusing an id we
  have not heard of is how this feature breaks three months after shipping.
- **Two models, and they should differ.** The critic (§6w.5) exists to catch the generator's
  fabrications; running the same model as its own critic is measurably weaker. The UI warns when
  they are equal, and does not forbid it — a company with one available model is still better served
  by a second pass than by none.
- **Egress consent is explicit, dated, and attributed.** Enabling a provider requires a Company Admin
  to affirm that this company's documentation and test content may be sent to the named endpoint.
  Stored as `egress_consent_at` + `egress_consent_by`, shown in company settings, and written to
  `audit_event`. This is the record that answers "who agreed to send our specs to Anthropic", and it
  is a question that will be asked.
- **A budget, not an invoice.** `monthly_token_budget` is a hard stop that fails the *job*, with a
  clear message, rather than a soft warning nobody reads. TCMS does not bill and does not price —
  it counts, and it stops.
- **Health is surfaced, like §6r's credential expiry.** A revoked key that silently stops generation
  while everything else keeps working is the realistic failure. `last_check_at` /
  `last_check_error` drive a status chip in settings, refreshed by an explicit *Test connection*
  action and by every job.

### Permission

One new `COMPANY`-scope permission, added to the §A3 catalogue:

| Permission | IA | CA | Every member | Covers |
|---|:--:|:--:|:--:|---|
| `company:ai` | ✓ | ✓ | — | Configure AI providers, keys, model selection, budget, consent |

It is **not** folded into `company:settings`, and not into `integration:manage` (which is
`PROJECT`-scope and would let a Test Lead spend the company's budget and consent to its egress on
their own authority). Both would grant an existing role a materially new capability — the ability to
send client data off-instance — without anybody deciding to. Reusing a permission is free; reusing
it here is not.

Two `PROJECT`-scope permissions govern *use* rather than configuration:

| Permission | IA | CA | TL | TE | AS | V |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `ai:generate` | ✓ | ✓ | ✓ | ✓ | — | — |
| `mcp:connect` | ✓ | ✓ | ✓ | ✓ | ✓ | — |

`ai:generate` follows `case:create` — generating is producing, and §A3's principle is that a Test
Engineer is never blocked from producing. Automation Service is denied it: a leaked CI token that can
spend the company's model budget is a bill and an egress, and §A6 keeps that role the smallest in
the product on exactly this reasoning. Viewer is denied both.

---

## 6w.2a Company-supplied skills — a company brings its own method, or inherits ours

A company may supply its **own skill/agent bundle** defining how cases are derived — their house
technique set, their step style, their domain vocabulary, their regulatory checklist. If they supply
none, generation runs on the **built-in library** (§6w.5's method). This is a per-company
configuration in exactly the sense §6s already establishes for statuses, vocabularies and IdPs.

### A bundle is data, never code

The one thing that must be settled before anything else: **a company bundle is declarative content —
method text and rule declarations — interpreted by TCMS. It is never executable, never a jar, never
a script, never a URL fetched at generation time.** A self-hosted product that lets a tenant upload
executable content has given that tenant the instance, and with it every other client's data. There
is no version of that trade worth making, and "it's only their own company" is false the moment the
process running it can reach `company_id`s it was not given.

So a bundle is a versioned document set:

```
skill_bundle (company_id, …)                     -- the company's own; RLS-forced like everything else
  ├─ name / description
  ├─ status        DRAFT | ACTIVE | ARCHIVED
  └─ skill_bundle_version[]                      -- immutable, per §6j
        ├─ version_no / content_hash
        ├─ documents  JSONB   method text per extension point
        ├─ rules      JSONB   declarative: technique set, coverage targets, BVA strength,
        │                     lint additions, vocabulary, step style
        └─ created_at / created_by
```

Versions are immutable because a proposal must record *which* version produced it (§6w.7). A bundle
edited in place makes every past proposal unexplainable, which is the same reasoning §6e applies to
reports and §6j to cases.

### Resolution order — most specific wins, and the floor is always ours

```
project generation profile  →  company skill bundle  →  built-in library
```

Each layer **overrides named extension points** and inherits everything else. A company that wants to
change only its step-writing style changes only that, and keeps every technique rule we ship —
including the ones a later release improves. Wholesale replacement is possible but is never the
default, because a bundle that copies the whole library freezes at the version it was copied from
and silently stops receiving improvements.

### The built-in library is instance-level reference data — a decision, not a slip

The built-in method is **one instance-level row set with no `company_id`**, alongside `permission` and
`role` on the ERD §0.1 exception list. **That list is closed and nothing joins it without a decision,
so this is that decision, with its alternative recorded:**

- **Chosen — instance-level.** It is seeded, immutable, identical for every company, and carries no
  client content whatsoever: it is method, the same way the permission catalogue is vocabulary. One
  copy means a release that improves the BVA guidance improves it for every company at once.
- **Rejected — a seeded copy per company** (the `key_sequence` / vocabulary shape). It keeps the
  exception list closed, which is worth something. But it produces one frozen copy per company on
  day one, so the improvement above reaches nobody without a data migration across every tenant, and
  `provision-company` — which is explicitly idempotent and *never updates* — is the wrong tool to fix
  that. The cost is real and recurring; the exception is one row set of non-client reference data.

**"Start from the built-in" is a UI action, not an inheritance mode**: it copies the current text
into a new company bundle version at creation time, which is a fork the company then owns. The
distinction matters because a fork stops tracking upstream and the user should be choosing that
deliberately.

### What a bundle may never override

This is the whole security surface, and it is short on purpose:

| Never overridable | Why |
|---|---|
| **The critic prompt and its isolation** | A company that can rewrite the critic can make it approve everything, and `TCMS_VERIFIED` (§6w.1a) stops meaning anything instance-wide. A bundle may **add** checks; it may never remove, relax, or replace one. Additive-only, enforced at validation. |
| **Permission checks, tenancy, `CompanyContext`** | Nothing in a bundle is consulted by any of them. A bundle is read *inside* an already-resolved company scope, never to establish one. |
| **The proposal gate (§6w.6)** | No bundle setting can write a case without a human accepting it, or land a case above `Draft`. |
| **The deterministic coverage math (§6w.8)** | A bundle may add a technique target; it may not redefine how coverage is computed, or the number stops being comparable between companies and across time. |
| **Budget, consent, egress (§6w.2)** | Method and money are separate concerns. |

**Validation happens at save, not at generation.** A bundle that fails to parse, declares an unknown
extension point, exceeds size caps, or attempts a non-additive critic override is **rejected when it
is uploaded**, naming the reason — the same discipline as §6r refusing to save an `ldap://` provider
config. Discovering it at 2am inside an async job is how a company finds out their method has been
silently ignoring a section for a month.

### Bundle text is untrusted input to the model, and is fenced as such

A company's own admin authoring their own method is a low-risk author — but the text still reaches a
model that also sees repository content, so it is delimited and labelled as *company-supplied method*
in the compiled prompt, never concatenated into the system instruction. The rule to hold: **a bundle
influences how cases are derived; it must not be able to instruct the model about what it may access,
because access was already decided before the prompt was compiled.**

### Rungs, and the BYO agent

A bundle's `rules` half — technique set, coverage targets, BVA strength, lint additions — is
declarative, so it drives the **deterministic phase-4 checks on rung 0**, with no model configured
at all. A company therefore gets value from its own method before it ever sets a key, which is the
§6w.1a shape repeating.

On **rung 1** the MCP server serves the resolved bundle to the connected agent (`get_method` /
`get_generation_profile`, plus an MCP *resource* for the method documents), so a BYO agent produces
suites in the company's house style rather than its own. Without this, rung 1 and rung 2 generate
differently-shaped cases into the same repository — which is precisely the inconsistency
`ai_generation_profile` exists to prevent.

### Permission

**`company:ai` covers bundles**, no new permission. It is already the "how does AI behave for this
company" surface, and splitting method from keys would put a second gate on the same admin screen
for no boundary anyone can name. Selecting *which* profile a project uses stays under
`project:settings`, matching where every other per-project choice lives.

---

## 6w.3 The MCP server — inbound, and it holds no privilege of its own

TCMS ships an **MCP server** (Spring AI MCP / the Java MCP SDK, stdio + streamable HTTP) so that any
agent — Claude Code, Copilot Chat, an internal orchestrator — can read and write the repository as
a first-class client.

### The decision that keeps it safe

**An MCP session is a normal authenticated session, and every tool call goes through
`PermissionEvaluator` unchanged.** There is no MCP bypass, no service account with elevated reach,
and **no new role** — §A2 fixes the role set at six and enforcement is permission-based, so an agent
is simply a caller holding whatever its token's user holds. Concretely:

- The token is an `api_token` (existing table, company-scoped, revocable) bound to **one user and
  one project**, carrying `mcp:connect`.
- The session opens `CompanyContext` from the token's company — **never** from a tool argument. An
  agent that passes `companyId` gets it ignored; the parameter does not exist in any tool schema.
- A tool the caller lacks permission for is **not listed** in `tools/list` for that session. An agent
  shown a tool it cannot use will call it, be refused, and retry — the same reasoning as §31's
  disable-don't-hide, arriving at the opposite affordance because an agent reads a list, not a
  screen.
- Every tool call writes an `audit_event` with `actor = the token's user`, `via = MCP`, and the tool
  name. "An agent did it" is never an acceptable audit line; "Ana's agent, holding Ana's grants, did
  it" is.

### Tool surface, in two waves

**Wave 1 — read.** `search_cases`, `get_case`, `list_suites`, `get_suite_tree`, `list_conditions`,
`get_doc_page`, `list_criteria`, `coverage_summary`, `repository_health`, `get_method` (the resolved
bundle, §6w.2a). Read tools are the whole
value for the first release: they let an external agent *ground itself in the real repository*
before proposing anything, which is precisely the failure mode file-based tools have — they generate
against docs while blind to the 891 cases that already exist.

**Wave 2 — write.** `propose_cases`, `create_case`, `update_case`, `link_case_to_criterion`,
`submit_for_review`. Each maps to an existing endpoint and its existing permission. Notably
**`propose_cases` is the interesting one and `create_case` is not**: it lands proposals (§6w.6)
rather than cases, so the BYO-agent path and the server-side path converge on one review surface.

### Why this half needs no model key

An agent connecting over MCP brings its own model and its own budget. So **the entire generation
story works with `ai_provider` unconfigured** — the user runs Claude Code, points it at TCMS's MCP
server and their docs, and the proposals land in TCMS for review. `ai_provider` is required only
when TCMS itself must call a model (§6w.5): a scheduled drift sweep, a CI-triggered generation, or a
user who wants the button without installing anything.

Stating it plainly, because it reorders the milestones: **the MCP server is the cheaper half and the
one that delivers generation first.** Server-side generation is the convenience path, not the
foundation.

### SPECTRA removed theirs — why that is not an argument against ours

SPECTRA v2 deleted its MCP server and replaced it with Claude Code *skills* over a deterministic
CLI seam. The reasoning is sound for what SPECTRA is: it is a **local CLI operating on the user's
own working tree**, so the agent already has the files, and an MCP server in front of a filesystem
the agent can read directly is a layer that buys nothing.

**TCMS is the opposite case, and the difference is the whole justification:**

| | SPECTRA | TCMS |
|---|---|---|
| Where the data lives | The agent's own working tree | A remote Postgres behind authentication, RLS and two permission gates |
| Can the agent read it without a protocol? | Yes — `cat` | **No.** There is no file to read. |
| What enforces authorization | The filesystem | `PermissionEvaluator`, per call |

An agent cannot `grep` a company's test repository. MCP is the *only* way to give it a grounded view
without either shipping an export (which leaves the isolation boundary and goes stale immediately)
or having the agent drive the REST API through screen-scraping. So SPECTRA's removal is evidence
about file-based tools, not about server-based ones — and it is worth recording that we read it and
concluded it does not transfer, rather than leaving the next reader to wonder whether we noticed.

**The half of their v2 decision that *does* transfer** is the seam: a deterministic
*compile-the-prompt* step, separated from the model turn, separated from a fail-loud *ingest* step.
That is what makes their pipeline testable without a live model, and §6w.11's grounding regression
fixture depends on TCMS having the same seam. Adopted.

---

## 6w.4 Acceptance criteria — obligation levels and content hashing

**The idea taken from SPECTRA, and the one that pays even if no AI is ever enabled.**

Today §6i links a case to a `doc_block`, and §6o derives `outdated_spec_changed` from "a linked doc
block changed after the case was last edited". That is right but coarse: a block is a paragraph, and
a paragraph typically states *several* obligations, of which an edit usually touches one. Every case
linked to the block is flagged, most of them wrongly, and a signal that cries wolf gets ignored —
which is the failure mode §6o's own "no mark-as-not-stale button" rule is trying to avoid from the
other direction.

### The entity

```
acceptance_criterion (company_id, project_id, …)
  ├─ block_id      → doc_block(id)            the anchor it was extracted from
  ├─ ordinal                                  position within the block
  ├─ obligation    MUST | SHOULD | MAY        RFC-2119 semantics
  ├─ text                                     the normative sentence
  ├─ source_hash   TEXT                       sha256 of the normalized source span
  ├─ extracted_by  DETERMINISTIC | MODEL      provenance (§6w.7)
  └─ status        ACTIVE | SUPERSEDED | DISMISSED
```

`doc_link` gains `ACCEPTANCE_CRITERION` as a `target_kind`, alongside `DOC_BLOCK` — so a case links
to *the obligation it verifies*, not to the paragraph that contains it. Existing block links keep
working untouched; this is additive, which is what makes it cheap.

### Extraction is deterministic first, and AI is the fallback

A regex/NLP pass over a block finds sentences containing `must` / `shall` / `should` / `may` /
`is required to`, splits them, and normalizes. It is unglamorous, it is free, it needs no key, and
on documents written in the way specifications are actually written it finds most of them. **The
model pass is an enhancement for prose that does not use the keywords** — and it is `MODEL`-tagged
so a reader can always tell which obligations a human's document actually asserted and which a model
inferred. Shipping the deterministic pass alone is a complete, useful feature.

### Hashing is what makes drift precise

`source_hash` is sha256 of the *normalized* span (whitespace-collapsed, case-folded, punctuation
retained) — so reflowing a paragraph or fixing indentation changes nothing, and changing a number,
a boundary or a negation changes everything. On every doc-page save:

- **hash unchanged** → nothing happens. This is the common case, and it is the whole point: today,
  every save of a page marks every linked case suspect.
- **hash changed** → the criterion is re-hashed, and every case linked to *that criterion* gets
  `case_health.outdated_spec_changed`, with a stored diff of the old and new text.
- **criterion gone** → `SUPERSEDED`, and linked cases get an `orphaned_criterion` signal — a new
  column on `case_health`, following that table's existing shape exactly.

`case_health` already exists in V1 with `outdated_spec_changed` and is already declared as derived
and self-healing. This slice makes the signal *true* rather than *approximately true*, and adds one
column. No new dashboard is required — §6o's tiles already render it.

---

## 6w.5 Grounded generation, and the critic

Two paths produce proposals, and **both end at the same gate**:

```
  BYO agent (MCP)  ─┐
                    ├─→  critic pass  ─→  ai_proposal  ─→  human accept  ─→  test_case + v1
  server-side job  ─┘                          ↑
                                        rejected steps recorded, not discarded
```

### The generation job

`POST /projects/{id}/ai/generation-jobs` → `202` + job id → poll or SSE. This follows §6e's async
report contract and `ingestion_job`'s table shape (status enum, idempotency key, error column)
rather than inventing a third job pattern. Input is a doc page, a subtree, or a set of criteria.

**It is `@Async`, so it must re-establish `CompanyContext` from a `CompanyScopedEvent` envelope.**
`CompanyContextTaskDecorator` clears the context on every task, so a handler that forgets fails
closed — and this is the handler where forgetting would send one client's specification into a job
that then writes into whatever company the pooled thread last held. `AsyncCompanyContextTest` is
extended to cover it, not trusted to already.

### The critic — grounding, and the isolation that makes it mean anything

**A second model is not enough; a second model in a *fresh context* is the actual mechanism.** The
critic must see only the proposed case and the source criteria it claims to verify — never the
generator's prompt, reasoning or tool calls. A critic that can see how the generator arrived at a
step is grading its own work with extra steps, and its approval carries no information. This is the
single most copyable detail in SPECTRA's design and it is easy to lose by "helpfully" passing the
generation context along for efficiency.

**Three verdicts, and only one is fatal:**

| Verdict | Meaning | Action |
|---|---|---|
| `GROUNDED` | Every step traces to a criterion, and any computed expected value is arithmetically correct | Proposal stands |
| `PARTIAL` | Some steps rest on assumptions; `unverified_claims` names each one | Proposal stands, flagged, claims shown to the reviewer |
| `HALLUCINATED` | Invented behaviour | Rejected before a human sees it — **but recorded**, per §6w.6 |

Advisory-gating, not blocking: `PARTIAL` is written through with its warnings rather than discarded,
because a case resting on a documented-but-unstated assumption is usually a *documentation* gap
worth seeing, not a fabrication.

**The damage paths fail loud, and they are distinguishable.** This is the part that is easy to get
wrong and impossible to notice afterwards:

- A critic response that is **unparseable or missing its verdict** is a specific, named error. It is
  **never** smoothed into a soft `PARTIAL` / score 0.5 — that is how a broken critic silently
  becomes a rubber stamp for months.
- A critic call that **fails or times out** marks the proposal `UNVERIFIED` and lets the job
  continue, and is recorded **distinctly** from a malformed response. "The critic didn't answer" and
  "the critic answered nonsense" have different causes and different fixes, and conflating them
  loses both.

**Arithmetic is verified, not assumed.** Where an expected result is a computed value — a
conversion, a formula, a total, a boundary — the critic recomputes it and compares. A documented
*principle* plus a wrong *number* is not grounded. This rule exists because a wrong number in an
expected result is the defect that survives every review: it looks authoritative, it is traceable to
a real documented rule, and nobody re-does the arithmetic.

### Grounding is not completeness — and they stay separate

Two different questions, deliberately not merged:

| Concern | Question | Timing | Where |
|---|---|---|---|
| **Grounding** | Does every claim in this case trace back to a criterion? | Retrospective, per proposal | The critic |
| **Boundary completeness** | Are the edges the criteria imply actually covered? | Proactive, before generation | The analysis pass |

The critic judges what is in front of it and treats anything it was not given as *unverified* —
never as a *missing test*. Folding completeness into it erodes the clean grounding-only contract and
produces a critic that fails cases for what they do not contain.

Completeness lives in the analysis pass instead, which reports **boundary gaps** — min/max,
off-by-one, empty/null, overflow, timeout — implied by the criteria but not covered by any existing
or proposed case. Advisory, never blocking, and surfaced as a *suggestion to generate more*, which
is the only useful shape for that information.

Determinism, because §6e requires reports to be reproducible and this is the same obligation:
temperature 0 where the provider supports it, and the proposal records `model`, `prompt_version` and
the `source_hash` of every criterion it consumed. A proposal generated against a since-changed spec
is identifiable as such, by the same hash that drives §6w.4.

### Every generated case is born linked

Generation walks *criteria*, so each proposed case carries a `doc_link` to the criterion it verifies
before it exists as a case. Two consequences, both free:

- It is never `unlinked` (§6o) — the signal that today flags 156 cases.
- It counts toward criteria coverage (§6w.8) the moment it is accepted, with no separate linking
  chore that everyone skips.

### Generation is coverage-aware, or it is useless on a mature project

**The failure that makes AI generation get switched off after one week:** run it against a project
that already has 891 cases and it proposes 139, of which 131 duplicate cases that already exist.
Nobody reviews that, and the feature is dead.

So the analysis pass builds a **coverage snapshot first** — existing case titles, their criterion
links, their doc links, and every criterion with no covering case — and the generator is asked only
for the *gaps*. On a mature suite this turns "139 proposals" into "the 8 uncovered criteria", which
is a queue a human will actually work through.

**TCMS is structurally better placed to do this than a file-based tool, and it is worth saying why:**
SPECTRA has to reconstruct its snapshot by parsing `_index.json` manifests and walking frontmatter
across the tree, and past ~500 cases it degrades to a summary mode to stay inside a prompt budget.
TCMS *queries* it — `criterion_coverage` (§6w.8) is a read model with partial indexes, and "criteria
with no covering case in this project" is one indexed query at any repository size. The thing that
forces their degradation does not exist here.

The same snapshot is what the MCP `coverage_summary` and `list_criteria` tools return, so a BYO
agent gets coverage-aware generation for free rather than needing its own implementation.

### Six techniques, or none — and the method is one we already own

SPECTRA's generation applies EP, BVA, decision tables, state transition, error guessing and use-case
derivation, and that is the difference between a suite of happy paths and a suite worth having. But
it only *names* the techniques; the method is left to the model.

**The method is already written, in this machine, at far greater depth**: the
`qa-java:qa-test-case-designer` agent and the `qa/manual/*` skills it loads. That is what
`ai_generation_profile` should encode, rather than a re-derived prompt:

| From the skill | What it contributes to a profile |
|---|---|
| The derivation chain — basis → conditions → **coverage items** → cases → data | The generator enumerates coverage items *before* writing cases, so the count is an output of coverage rather than a `--count` flag |
| Per-technique **stopping criteria** ("every reachable rule after collapsing"; "all valid transitions plus all invalid rejected") | An objective, checkable coverage claim per proposal — see below |
| 2-value vs 3-value BVA, with the `if (x <= 10)` → `== 10` defect model | Boundary depth chosen by risk, not by habit |
| State **tables**, not diagrams — "the empty cells are the bug farm" | Invalid transitions get generated, which is where the negative cases actually come from |
| Shared **Setup** + mandatory **Cleanup** | Structural test independence. SPECTRA's format has neither, and its cases are independent only by luck |
| `#assumption` vs `#observed` tagging | Maps onto `verdict_source` and §6w.7 provenance: what the spec said vs what someone saw |
| The anti-pattern list (no two invalid inputs in one negative case; no BVA on unordered partitions; no skipping the *rule* boundary because the *field* boundary was tested) | Deterministic lint on a proposal, no model required — it runs on rung 0 |

A profile therefore stores: the technique set and per-technique coverage targets, BVA strength, the
case-count ceiling, default classification (§6d), and the target suite. Without profiles every user
re-types their own instructions and gets a differently-shaped suite each time, which is how a shared
repository becomes inconsistent.

### The critic replaces self-review — when there is one

The skill's phase 4 is a **self**-review: the model that wrote the suite grades the suite. For a
human-driven run that is fine, because a human is the one reading it. For a *generated* run it is
the exact failure §6w.5's isolation exists to prevent — same context, same commitments, same blind
spots — so on rung 2 the isolated critic **is** phase 4, and self-review is not run at all.

On rungs 0 and 1 there is no critic, and the honest answer is that **phase 4 falls back to the
human** (§6g), not to the model reviewing itself. The deterministic half of phase 4 still runs
everywhere: the coverage matrix, the per-technique target check, and the anti-pattern lint are all
computable from the proposal and the criteria, with no model involved. That is a meaningful
verification floor available on rung 0 — and it is a floor SPECTRA does not have at all, because its
coverage targets are never stated in a checkable form.

---

## 6w.6 Proposals are not cases — the boundary that protects the repository

```
ai_proposal (company_id, project_id, job_id, …)
  ├─ payload          JSONB    the proposed case, in the §6m field shape
  ├─ criterion_id              what it claims to verify
  ├─ verdict          GROUNDED | PARTIAL | HALLUCINATED | UNVERIFIED
  ├─ verdict_source   NONE | EXTERNAL | TCMS_VERIFIED          -- §6w.1a
  ├─ grounding        JSONB    per-step findings, including rejections and unverified_claims
  ├─ status           PENDING | ACCEPTED | REJECTED | SUPERSEDED
  └─ decided_by / decided_at
```

**`UNVERIFIED` is a first-class verdict, not an error state.** It is what every proposal on rungs 0
and 1 carries, and what a rung-2 proposal falls back to when the critic times out or is unset. The
review UI renders it as *"not independently checked — review against the spec"*, which is exactly
the instruction a reviewer needs and exactly what a human-only workflow has always required.

**Nothing generated writes to `test_case` without a human accepting it.** On accept, the case is
created through **the existing create path** — the §29 `TC-n` allocator, v1 written with the case,
the same permission check — with `created_by` = the accepting human and `origin = AI_GENERATED` plus
the job id recorded on the version. So:

- Authorship is a person, always. §6j's attribution guarantee is untouched.
- A generated case enters at `Draft` and travels §6g's review workflow like any other. **There is no
  path from a model to `Approved`**, and the review UI shows the grounding evidence alongside the
  proposal so the reviewer is reviewing against the spec, not against their memory of it.
- Rejecting a proposal is a first-class outcome that is *recorded*. A generation profile whose
  proposals are rejected 80% of the time is a measurable fact, and the alternative — proposals that
  vanish when dismissed — makes that unknowable.

`test_case_draft` already exists in V1 and is the natural landing place for an accepted proposal
that the user wants to keep editing before it becomes a case.

---

## 6w.7 Provenance, everywhere

Every artifact this section can touch carries where it came from: `extracted_by` on a criterion,
`origin` on a case version, `via = MCP` on an audit event, and on a proposal the full set —
`model`, `prompt_version`, `verdict_source`, plus **`bundle_id` + `bundle_version`** naming the
method that produced it (§6w.2a).

The bundle version is what makes a company's own method auditable rather than merely configurable:
"these 40 cases were derived under our v3 method, and v4 changed the boundary rule" is a question a
regulated client will ask, and an immutable version id is the only thing that answers it.

This is not bookkeeping. A year in, the question "is our test repository actually ours, or did a
model write half of it and nobody checked" is answerable in one query, or it is not answerable at
all. Products that added AI without provenance cannot answer it, and that is the defect worth
avoiding in advance.

---

## 6w.8 Three-axis coverage

SPECTRA's dashboard reads coverage on three dimensions. TCMS has the sources for all three already —
what is missing is the criterion row (§6w.4) and one read model.

| Axis | Question | Source |
|---|---|---|
| **Documentation** | Which doc blocks have any linked case? | `doc_link` where `source_type = TEST_CASE` |
| **Criteria** | Which *obligations* are verified, weighted by MUST / SHOULD / MAY? | `acceptance_criterion` ← `doc_link` |
| **Automation** | Which verified obligations are verified *automatically*? | `test_case.automation_status` (§6c) |

The composite is the number worth putting on a wall: **"of 214 MUST obligations, 198 are verified,
143 automatically, 12 by a case that has never passed."** The last clause matters — a criterion
covered only by a case that has never executed, or that failed its last three runs, is not covered,
and a coverage number that counts it is lying. `execution_fact` (§6e) already holds what is needed to
tell the difference.

### Two refinements that decide whether anyone trusts the number

**1. Claimed coverage and verified coverage are tallied separately.** A case whose `doc_link` says it
verifies AC-042 *claims* coverage; a case whose proposal came back `GROUNDED` from an independent
critic has that claim *checked*. They are different facts and the dashboard reports both — because
collapsing them lets a run of unreviewed proposals inflate the headline number, which is the one
number people quote in release meetings.

**2. Some documents must be excluded from the denominator, visibly.** Release notes, changelogs,
meeting summaries and archived pages should stay indexed and searchable — a generator should still
be able to read them — but nobody writes test cases against them, and counting them drags the
coverage percentage down permanently until people stop looking at it. So: a per-project
`coverage_exclude` pattern set on doc pages, where an excluded page is **removed from the
denominator and reported as excluded, with the rule that excluded it** — never silently dropped. A
coverage number you cannot audit is a coverage number nobody defends.

### Automation-link health comes with the third axis

The automation axis is not just a percentage; it has three failure states worth surfacing, all of
which TCMS can already source from `automation_key_mapping` and §6c's drift detection:

- **Unlinked** — a case with no automation reference.
- **Orphaned automation** — an automated test referencing a case id that no longer exists.
- **Broken link** — a case pointing at an automation path that no longer resolves.

The second and third are the ones that rot silently: the suite stays green, the case stays marked
`AUTOMATED`, and nothing is actually being verified. §6o already carries `stale_automation` for the
time-based version of this; these are the structural version.

### Where it renders

**Not a new dashboard.** §6o's repository status dashboard already owns "is our repository in good
shape", every number is already a link into a pre-filtered case list, and its own design warns that
conflating two questions "produces a dashboard that answers neither". Criteria coverage is a row of
tiles there — `Uncovered MUST 16 · Covered but never executed 12 · Unlinked criteria 41` — plus the
inline marker §6i already specifies on the doc page itself, upgraded from block-level to
criterion-level.

Storage follows `case_health`: a derived, recomputed read model (`criterion_coverage`), never
authored, with partial indexes on the signals people filter by.

---

## 6w.9 What is deliberately *not* taken from SPECTRA

| Rejected | Why |
|---|---|
| **Adopting the tool itself** | .NET 8 global CLI. Cannot live inside a Spring Boot jar, and shelling out to a foreign runtime from a request thread is not an architecture. |
| **Git-native Markdown as the store** | It has no `company_id`, no RLS, no `created_by` FK, and no way to express §6b's two gates. Adopting it means adopting a second storage model that structurally cannot honour the invariant the rest of the product rests on. |
| **Its MCP *execution* engine** | TCMS is explicitly **not a test runner** (§1) — it ingests results. Driving a test run from an agent is a different product, and the non-goal is deliberate. |
| **Its coverage dashboard as a second surface** | §6o exists. A second dashboard is how a hundred consistent pages become a hundred slightly different ones. |
| **Its global test-ID allocator** | A cross-process file lock (`.spectra/id-allocator.lock`, 10s timeout) over a JSON high-water-mark, re-seeded by walking every `_index.json` *and* every `.md` frontmatter, with a `spectra doctor ids --fix` to renumber the duplicates it still produces. This is an impressive amount of machinery to obtain what a database gives for free — and §29 already solved it here with `key_sequence` and the one statement that cannot race. Recorded because it is the clearest single illustration of what the file-based model costs. |
| **`spectra` Markdown import** | Deferred, not rejected. It is a thin frontmatter→§6m adapter behind the same proposal gate — their `source_refs` → `doc_link`, `criteria` → `acceptance_criterion`, `automated_by` → §6c automation, `grounding.*` → the proposal's grounding record. Worth doing on evidence that a user actually runs SPECTRA. Apache-2.0 means the format spec can be copied outright. |

---

## 6w.10 Slices

Ordered so that **every slice ships something usable on its own**, and the deterministic value lands
before anything needs a key. Sizes are relative, not estimates.

| # | Slice | Depends on | Ships |
|---|---|---|---|
| **1** | **Criteria extraction, deterministic.** `acceptance_criterion` + `source_hash`, `doc_link.target_kind = ACCEPTANCE_CRITERION`, re-hash on doc save, precise `outdated_spec_changed` + `orphaned_criterion` on `case_health`. Criteria rendered inline on the doc page with their obligation chips. | §6i doc space (not yet built — this slice is gated on it) | Precise staleness. No AI, no key, no egress. |
| **2** | **Three-axis coverage.** `criterion_coverage` read model, §6o tiles, the "covered but never executed" distinction, claimed-vs-verified split, `coverage_exclude` patterns reported as excluded, automation-link health (unlinked / orphaned / broken), doc-page inline markers. | 1, §6o, §6e `execution_fact`, §6c | The number worth putting on a wall. |
| **3** | **MCP server, read-only.** `mcp:connect`, token→session→`CompanyContext`, wave-1 tools, per-session tool filtering by permission, `via = MCP` audit. | M1 read surface (**done**) | Any agent can ground itself in the real repository. The cheapest slice with the largest reach. |
| **3b** | **The method layer.** Built-in library as instance-level reference data, `skill_bundle` + immutable versions, the resolution chain, save-time validation, the additive-only critic rule, `get_method` over MCP, and the declarative `rules` half driving the **rung-0** deterministic checks (coverage targets, anti-pattern lint). | 3 | A company's own house method — **before any key exists**, and usable by a BYO agent immediately. |
| **4** | **`ai_provider` + `company:ai`.** Encrypted key, model selection, egress consent, budget, *Test connection*, health chip. No feature consumes it yet. | §6r credential encryption | Configuration, provably isolated, ahead of the thing that spends it. |
| **5** | **Proposals + MCP write tools.** `ai_proposal`, the accept path through the existing create path, review surface with grounding evidence, `propose_cases` over MCP. | 3, 4 | End-to-end generation via BYO agent — **before TCMS ever calls a model itself**. |
| **6** | **Server-side generation + critic.** Async job over the compile → turn → ingest seam, generation profiles, the six techniques, coverage-aware analysis, isolated-context critic with the three verdicts and the two distinguishable damage paths, arithmetic verification, budget enforcement. | 4, 5 | The button, for users who do not run an agent. |
| **6b** | **Boundary-gap analysis.** The completeness half, kept out of the critic: min/max, off-by-one, empty/null, overflow, timeout gaps implied by the criteria and covered by nothing. Advisory, feeds the generation queue. | 2, 6 | The edges nobody wrote a case for. |
| **7** | **Model-assisted extraction.** The `MODEL` fallback for prose without RFC-2119 keywords, tagged as such. | 1, 4 | Criteria from documents that were not written for us. |

**Slice 3 before slice 4 is the deliberate ordering**, and it inverts the obvious one. The MCP server
needs no key, no consent and no budget; it delivers the generation story through the user's own
agent; and it makes slices 5–6 an optimization rather than a prerequisite. Building the key
management first would mean four slices of plumbing before anything works.

**Slices 1 and 2 are gated on the documentation space (§6i), which does not exist yet.** That is
worth saying plainly rather than discovering in the orientation pass: `doc_block`, `doc_page` and
`doc_link` are in V1's DDL, but no module, entity, repository or endpoint reads them. Slice 1's real
predecessor is a documentation-space slice, and if AI is wanted sooner, **slice 3 has no such
dependency** and can go first.

---

## 6w.11 Gate

Beyond the standing gates (`./gradlew build` with `-Werror` and all tests; `npm run build`;
`npm run lint` at 0 warnings; a real browser pass):

- **`SchemaConventionTest` must pass unchanged.** Every new table — `ai_provider`,
  `acceptance_criterion`, `ai_proposal`, `ai_generation_job`, `ai_generation_profile`,
  `criterion_coverage` — carries `company_id NOT NULL` and a `FORCE ROW LEVEL SECURITY` policy.
  **Nothing here goes on the ERD §0.1 exception list**, and if a slice finds itself wanting to add
  one, that is the signal to stop and re-read this section.
- **`RepositoryTransactionTest`** covers every new repository interface — each carries
  `@Transactional(readOnly = true)`, or its queries run outside a transaction and return zero rows
  while `CompanyContext` holds the right company.
- **A cross-company AI isolation test**, in the paired-fixture harness: company A's job must not be
  able to read company B's docs, spend company B's budget, or resolve company B's provider. The
  two-company fixture already exists; this adds a third assertion family to it.
- **An async-context test for the generation job**, in `AsyncCompanyContextTest` — the job must
  re-establish context from its event envelope and must fail closed when the envelope is absent.
- **A key-leak test.** Assert that no endpoint response, no `audit_event` payload, no error message
  and no log line emitted by a failed generation contains the configured credential. This is a
  computed check over the actual outputs, not a careful reading of the code — §0.1's own argument,
  and the failure it prevents is the one that ends up in a bug bounty.
- **A grounding regression fixture**, over the compile → ingest seam so no live model is called in
  CI. A doc page with known obligations plus recorded critic responses, asserting each of:
  a fabricated step is rejected; a `PARTIAL` verdict is written through with its `unverified_claims`
  intact; **a malformed critic response raises a named error rather than degrading to a soft pass**;
  a critic timeout marks `UNVERIFIED` and is recorded *distinctly* from the malformed case; and a
  correct principle with wrong arithmetic is **not** `GROUNDED`. The pipeline, not the model, is what
  this project owns — and the last three assertions are the ones that catch a critic that has quietly
  stopped criticising.
- **A coverage-awareness test.** Generation against a project whose criteria are already fully
  covered proposes **zero** cases. Without this, the first mature-repository run buries the reviewer
  and the feature gets switched off.
- **CI configures no AI provider, ever — and that is the enforcement of §6w.1a.** The entire suite
  runs green on rung 0, so any code path that comes to *require* a key fails the build the day it is
  written. This is the computed check the invariant needs; asserting "AI is optional" in a document
  protects nothing, and a suite that quietly gained a fixture provider would hide the regression
  permanently. Rungs 1 and 2 are exercised by tests that construct their own provider or MCP session
  explicitly, which is what makes their dependency visible.
- **A ladder-degradation test per row of §6w.1a's runtime table.** No provider, disabled provider,
  withdrawn consent, exhausted budget, unset critic, critic timeout, key revoked mid-job — each
  asserts the *named* outcome and, critically, that **everything not named still works**: the case
  list, the coverage numbers, review, and every existing write. A degradation that takes an unrelated
  screen with it is the realistic version of this failure.
- **A verdict-provenance test.** An MCP client sending `verdict: grounded` produces a proposal with
  `verdict_source = EXTERNAL`, and that proposal does **not** move the verified-coverage number.
  Otherwise any token holder can mint verified coverage by asserting it.
- **Bundle containment tests**, one per row of §6w.2a's never-overridable table. Each constructs a
  bundle that *attempts* the override and asserts it is **rejected at save** with a named reason —
  most importantly a bundle that tries to relax or replace the critic, which must fail while an
  additive one succeeds. A containment rule with no test is a comment.
- **A bundle-resolution test.** Project profile → company bundle → built-in, asserting that a bundle
  overriding one extension point inherits every other from the built-in rather than silently
  emptying them. Partial inheritance failing open is how a company ends up generating with no
  technique set at all.
- **Bundle isolation, in the paired-fixture harness.** Company A's bundle is unreadable and
  unresolvable from company B's session, and a generation job resolves its method strictly within
  its own `CompanyContext`.
- **A no-bundle test.** Every company in the suite runs with no bundle configured and gets the
  built-in method — the §6w.1a rule applied to method rather than to keys, and the reason the
  built-in is a real seeded row set instead of a hardcoded default.
- **Budget enforcement is tested at the boundary**, not near it: a job that would exceed the budget
  fails with a named error before the first outbound call, not after the fourth.

---

## 6w.12 Open questions

| # | Question | Leaning |
|---|---|---|
| 0 | Should the deterministic phase-4 checks (coverage matrix, per-technique target, anti-pattern lint) run on **human-authored** cases too, not just proposals? | **Yes, eventually** — they are pure functions of a case and its criteria, and a §6o tile for "cases whose claimed technique missed its coverage target" needs no AI. Deferred to its own slice, not folded into §6w. |
| 1 | Does the instance operator get an **instance-wide default provider** that companies inherit? | **No.** It would mean one company's docs going out under a key and a consent that company never gave. Per-company or nothing. |
| 2 | ~~Prompt storage — versioned in the repo, or configurable per company?~~ | ~~**In the repo, versioned.** Per-company prompts make output unexplainable and unreproducible.~~ **→ superseded by §6w.2a.** Companies configure method, and reproducibility is preserved the way §6j preserves it everywhere else — by making versions *immutable and recorded* (`bundle_id` + `bundle_version` on every proposal), not by forbidding the configuration. The original concern was right about the failure and wrong about the remedy. |
| 2b | May a company bundle ship its own **prompt-level** text, or only declarative rules? | **Both**, but fenced: rules are interpreted, method text is delimited and labelled as company-supplied in the compiled prompt (§6w.2a), never merged into the system instruction. |
| 2c | Do bundles need an export/import format so a company can version them in their own git? | **Yes, eventually** — it is the natural request from anyone who already treats test method as code, and the immutable-version model makes it a straight serialization. Not first release. |
| 3 | Should a criterion be editable in TCMS, or is the document always the truth? | Document is truth; a criterion is derived. `DISMISSED` exists for false positives, and editing text does not. |
| 4 | Retention on `ai_proposal` — do rejected proposals live forever? | Purge `REJECTED` after N days (company setting), keeping the *counts* for the profile-quality signal. |
| 5 | Does the MCP server support streamable HTTP as well as stdio, in the first slice? | Both eventually; **stdio first** — it is what a locally-run agent uses, and it needs no new ingress on the deployment. |
| 6 | Rate limiting on MCP tool calls — per token, per company, or none in v1? | Per token, generous. An agent in a loop against `search_cases` is a realistic accident. |
| 7 | Do criteria participate in §6h's FTS index? | Yes — it is one more weighted `tsvector` on an existing pattern, and "find the obligation about refund windows" is the query people will actually type. |
