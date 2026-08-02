# Work order — make the orientation-cache protocol safe for parallel agent runs

**For:** an agent working in the **SkillsLibrary** repo (the source of the `be-java` / `fe-react` /
`devops` / `qa-java` / `data` plugins). You do not need any context from the repo this file was
written in.

**Type:** documentation-only change to skill and agent definitions. No code, no tests, no build
logic. Three files change materially; a vendoring step may multiply that.

---

## 1. The defect

`engineering-practices/agent-orientation-cache/SKILL.md` defines a per-agent orientation cache at
`docs/agent-context/<agent-name>.md` in the *target* project. It justifies one file per agent type
with this sentence:

> Different agent types never write the same file, so there is no cross-agent write contention.

**That is true for one instance per type and false the moment two run at once.** A parallel run with
two backend lanes has two `be-java` agents and one `be-java.md`. The protocol has no lane concept, so
both execute Phase 3 (update-after) against the same file.

Three failures, in increasing order of nastiness:

1. **Guaranteed conflict.** The header stamps `Baseline commit` and the session log is *prepended*.
   Two lanes collide in both places, every time.
2. **Lost seams.** Phase 3's highest-value step is *"add the seams the run created"*. Last-writer-wins
   drops the other lane's. A confidently **incomplete** map is worse than a missing one, because the
   next run's gate will TRUST it.
3. **A poisoned baseline stamp — the real one.** Each lane stamps the `HEAD` *it* finished at, so the
   surviving stamp describes neither lane. The next session's delta gate then computes
   `git diff <wrong baseline>..HEAD`, under-reports what moved, and returns **TRUST for sections that
   are stale**. Silent, and it lands on the *following* session rather than the run that caused it.

Git worktrees (which parallel runs already use per lane) do not fix this. They defer it: no contention
during the run, a conflict at merge, and each lane spends the run reading a map missing whatever its
sibling just built.

**The fix is the one already applied to generated API clients: single writer.** The cache stays — it
is the mechanism that stops every lane re-deriving the same premises, which is the main cost of
running lanes at all. Only the *writes* serialise.

---

## 2. Files to change

| # | Path (relative to repo root) | Change |
|---|---|---|
| 1 | `engineering-practices/agent-orientation-cache/SKILL.md` | The false sentence; a new "Parallel runs" section; two smaller amendments |
| 2 | `backend/java/agents/be-java.md` | Two lines |
| 3 | `frontend/react/agents/fe-react.md` | Two lines |

**Then check vendoring — see §6. This is the step most likely to be missed.**

If a path does not exist, search for it rather than guessing:
`rg -l "agent-orientation-cache|orientation-cache v1" --type md`

---

## 3. Change 1 — `engineering-practices/agent-orientation-cache/SKILL.md`

### 3a. Replace the false claim

In the section **"The artifact"**, find:

```
**One file per agent type** (`fe-react.md`, `be-dotnet.md`, `qa-api-typescript.md`). Not one shared file: each agent orients on different things, so a shared file would be the union of unrelated concerns and stale for everyone. Different agent types never write the same file, so there is no cross-agent write contention.
```

Replace the **final sentence only** so the paragraph ends:

```
**One file per agent type** (`fe-react.md`, `be-dotnet.md`, `qa-api-typescript.md`). Not one shared file: each agent orients on different things, so a shared file would be the union of unrelated concerns and stale for everyone. Different agent *types* never write the same file — but **two instances of the same type do**, which the "Parallel runs" section below is entirely about.
```

### 3b. Add the new section

Insert **immediately after Phase 3 (`## Phase 3 — Update-after …`) and before `## Authoring rules`**:

````markdown
## Parallel runs — the cache is a single-writer resource

Phases 1–3 assume one instance of this agent type is running. When several are, this file becomes
the same kind of resource as a generated API client: shared, valuable, and ruined by concurrent
writes. Two backend lanes are two `be-java` agents and one `be-java.md`.

**Three failures, worst last.** (1) The header stamps `Baseline commit` and the session log is
*prepended*, so two lanes collide in both places every time. (2) Phase 3's highest-value step is
recording the seams the run created; last-writer-wins drops the other lane's, and a confidently
*incomplete* map is worse than a missing one because the next gate will TRUST it. (3) **Each lane
stamps the `HEAD` it finished at**, so the surviving stamp describes neither lane — the next
session's delta gate computes against a wrong baseline, under-reports what moved, and returns TRUST
for sections that are stale. That one is silent and lands on the *following* session, not on the run
that caused it.

Worktrees do not fix this, they defer it: no contention during the run, a merge conflict at
integration, and each lane reading a map that is missing whatever its sibling just built.

### Lane role

**A run is SOLO unless told otherwise**, and SOLO behaves exactly as Phases 1–3 describe. Nothing
below changes single-agent behaviour.

The invoking prompt may instead name a role:

- **`cache-holder`** — the one lane per agent type that runs Phase 3.
- **`cache-reader`** — every other lane. Reads the cache, **never writes it**, and emits a delta
  block instead.

If no role is named, detect one: when `git rev-parse --git-dir` differs from
`git rev-parse --git-common-dir` you are in a **linked worktree**, which is what a parallel run
creates per lane — assume `cache-reader`. Defaulting an undeclared lane to *reader* is the safe
direction: a cache that goes un-updated is caught by the next delta gate, whereas a poisoned
baseline stamp is not caught by anything.

Detection is a fallback, not a substitute for the orchestrator naming roles. State the resolved role
in your report so a wrong guess is visible.

### A `cache-reader` emits this instead of running Phase 3

```
### Cache delta — <agent-name> — lane <id>
Sections touched: <section names>
Corrections:      <the cached line that was wrong> → <what it should say>
New seams:        <one line each — the highest-value part>
```

Verbatim-foldable by the holder. If there is nothing to report, say so explicitly rather than
omitting the block.

### A `cache-holder` runs Phase 3 once, at integration

Folding in every sibling's delta block, and **stamping the integration commit — never its own lane
`HEAD`.** The session log gets one line for the run as a whole, naming the lanes, not one line per
lane.

### One correctness rule that is not about writing

A lane's delta gate sees only its own branch, so it is **blind to what a sibling lane has already
built**. Never return **TRUST** for a section the run plan says a sibling lane owns — treat those
sections as DELTA and re-derive them, even when your own git delta is empty.
````

### 3c. Amend the Phase 1 verdict table

Directly under the Phase 1 verdict table (the one whose rows produce **FULL / TRUST / DELTA**), add:

```markdown
**In a parallel run this table is necessary but not sufficient** — it can only see your own branch.
Apply the sibling-lane rule under "Parallel runs" before accepting a TRUST verdict.
```

### 3d. Amend the Output contract

In the **`## Output contract`** section, find:

```
Orientation: cached (full) | cached (delta: <n> sections refreshed — <what invalidated them>) | full (<reason>)
Orientation cache: updated | updated (partial — <why>) | created
```

Replace with:

```
Orientation: cached (full) | cached (delta: <n> sections refreshed — <what invalidated them>) | full (<reason>)
Lane role: solo | cache-holder | cache-reader (detected: worktree)
Orientation cache: updated | updated (partial — <why>) | created | delta emitted (reader, not holder)
```

---

## 4. Change 2 — `backend/java/agents/be-java.md`

### 4a. The skill-loading protocol

Find (in the **Skill-loading protocol** section):

```
**Always loaded, every run — before anything else:** `engineering-practices/agent-orientation-cache` (`ep-agent-orientation-cache`), the read-validate / update-after protocol for `docs/agent-context/be-java.md`. Not brownfield-only: a greenfield run is what creates the cache.
```

Append one sentence:

```
**Always loaded, every run — before anything else:** `engineering-practices/agent-orientation-cache` (`ep-agent-orientation-cache`), the read-validate / update-after protocol for `docs/agent-context/be-java.md`. Not brownfield-only: a greenfield run is what creates the cache. Runs are **SOLO by default**; if this run is one lane of a parallel set the cache is **read-only** and you emit a delta block instead of updating it — see that skill's "Parallel runs".
```

### 4b. Both output contracts

This file has **two** `Orientation cache:` lines — one in the greenfield block, one in the brownfield
block. Change **both**.

Greenfield block — find:

```
Orientation cache: created | updated | updated (partial — <why>)   # last phase of a run only
```

Replace with:

```
Lane role: solo | cache-holder | cache-reader
Orientation cache: created | updated | updated (partial — <why>) | delta emitted (reader)   # last phase of a run only
```

Brownfield block — find:

```
Orientation cache: created | updated | updated (partial — <why>)
```

Replace with:

```
Lane role: solo | cache-holder | cache-reader
Orientation cache: created | updated | updated (partial — <why>) | delta emitted (reader)
```

---

## 5. Change 3 — `frontend/react/agents/fe-react.md`

Identical shape. Note this file's wording differs slightly from `be-java.md` — match what is actually
there rather than the quotes below if they have drifted.

### 5a. The skill-loading protocol

Find:

```
**Always loaded, every run — before anything else:** `ep-agent-orientation-cache`, the read-validate / update-after protocol for `docs/agent-context/fe-react.md`. Not brownfield-only: a greenfield run is what creates the cache.
```

Append the same sentence:

```
**Always loaded, every run — before anything else:** `ep-agent-orientation-cache`, the read-validate / update-after protocol for `docs/agent-context/fe-react.md`. Not brownfield-only: a greenfield run is what creates the cache. Runs are **SOLO by default**; if this run is one lane of a parallel set the cache is **read-only** and you emit a delta block instead of updating it — see that skill's "Parallel runs".
```

### 5b. Output contract

This file states the closing line in prose **and** in two fenced blocks. Update all three.

Prose — find:

```
and closes with `Orientation cache: created | updated | updated (partial — <why>)`.
```

Replace with:

```
and closes with `Lane role: …` and `Orientation cache: created | updated | updated (partial — <why>) | delta emitted (reader)`.
```

Then apply the same two fenced-block replacements as §4b.

---

## 6. Vendoring — do not skip this

`engineering-practices/` appears to be **vendored into each plugin**. In an installed plugin cache the
skill resolves at, for example:

```
.../plugins/cache/skills-library/be-java/<hash>/engineering-practices/agent-orientation-cache/SKILL.md
.../plugins/cache/skills-library/fe-react/<hash>/engineering-practices/agent-orientation-cache/SKILL.md
```

— i.e. `be-java`, `fe-react`, `devops`, `qa-java` and `data` each carry a copy.

**Determine which case the source repo is in, and say which in your report:**

```bash
rg -l "there is no cross-agent write contention" --type md
```

- **One hit** → single source of truth. Patch it; find the build/sync step that vendors it
  (`rg -n "engineering-practices" -g '!*.md'` — look for a copy/sync script or CI job) and note in
  your report whether re-vendoring is manual or automatic.
- **Several hits** → the copies are checked in. **Patch every one identically.** A run that fixes
  `be-java` and leaves four agents asserting the old claim is worse than not starting, because the
  claim is now contradicted between siblings.

---

## 7. Definition of done

- [ ] The sentence *"there is no cross-agent write contention"* appears **nowhere** in the repo:
      `rg "no cross-agent write contention" --type md` returns zero hits.
- [ ] `rg -c "Parallel runs — the cache is a single-writer resource" --type md` matches the number of
      `agent-orientation-cache/SKILL.md` copies found in §6.
- [ ] Both agent files mention SOLO-by-default and the read-only lane rule.
- [ ] Every `Orientation cache:` line in both agent files and the skill offers the
      `delta emitted` outcome, and every output contract has a `Lane role:` line.
- [ ] **Solo behaviour is unchanged.** Re-read Phases 1–3 as a solo agent would and confirm nothing
      you added alters what a single-agent run does. This is the acceptance test that matters most —
      the overwhelming majority of runs are solo and must not get slower or more ceremonious.
- [ ] Markdown renders: no unbalanced fences (§3b nests a fenced block inside a fenced block — it
      uses four backticks on the outer fence, keep it that way).

## 8. Do not

- **Do not** add a lockfile, a `.lock` convention, or any runtime coordination. The fix is a stated
  protocol, not a mechanism; agents cannot be relied on to release a lock.
- **Do not** split the cache per lane (`be-java.lane-a.md`). It fragments the map and the next solo
  run has no single file to read — which destroys the value the cache exists for.
- **Do not** make the cache read-only unconditionally, or drop it in parallel runs. It is the one
  mechanism that stops N lanes each re-deriving the same premises; that re-derivation is the
  measured cost of running lanes at all.
- **Do not** change Phase 1's verdict table logic, the fingerprint-file rules, or the artifact's
  location. Only the additions above.

## 9. Report back

1. Which vendoring case §6 found, and how many files you changed.
2. Any anchor text in §3–§5 that had **drifted** from the quotes here, and what you matched instead.
   (These quotes were taken from an installed plugin cache; the source may be ahead of it.)
3. Confirmation of each §7 checkbox, with the `rg` output for the first two.
