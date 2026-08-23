# Running the backend mutation backlog — the plan

> **CLOSED 2026-08-23.** Waves 0, 1, S and 2 ran in full; wave 3 ran **batch A of its slimmed set
> only** (`docs/plans/2026-08-23-wave-3-slimmed.md`). Waves 4–7 — the TypeScript, infrastructure,
> manual/live-stack and Hetzner lanes — **never started**. 492 rows are `CLOSED:NOT-RUN`, which is a
> deliberate stop and **not a measurement**. 244 rows were adjudicated and stand as evidence; see
> `docs/verification/backend-mutation-checks.md`'s banner and its four RUN RECORD sections.

**Written 2026-08-22.** Subject: `docs/verification/backend-mutation-checks.md`, the §65 deferred
queue, as it stands after §249. This plan does **not** run anything. It counts what is owed, prices
it against a measurement taken today, splits it into the lanes that can actually execute it, and —
the thing that was asked for — says which rows are duplicates of each other and can be collapsed.

Everything below is derived mechanically from the queue file, not read impressionistically. The
extraction is one `awk` over the tables (`| id | mutation | expected | result |`), skipping rows
already marked `✅` / `FOUND AND FIXED`. Re-derive it before acting on it — the file grows every
slice.

---

## 1. What is actually owed

| | Rows |
|---|---|
| Table rows across the whole queue file | 905 |
| Already run and recorded (`✅`, `FOUND AND FIXED`) | 120 |
| **Unrun** | **797** |
| Sections containing them | 124 (§98 → §249) |

The 2026-08-05 RUN RECORD covers 182 rows and ends at §97. **Everything after that has accumulated
unrun for fourteen slices.** The queue is not behind by a phase; it is behind by a milestone.

### The yield to expect

The one completed pass is the only evidence for what a row is worth. Of its 182 rows: 96 MATCH,
26 MORE, 8 FEWER, 13 zero-as-predicted, **9 zero-unexpected (the findings)**, 6 stale, 15 confirmed
by reading, 9 blocked. So roughly **5% of rows produce a finding** and another ~14% produce a MORE
that is worth reading. Against the deduped 598 below, expect **~30 findings**. That is the return
this plan is buying, and it is worth stating up front so the effort is sized against it rather than
against completeness for its own sake.

---

## 2. What a row costs — measured today, on `shanks`

Two runs against `62875cc5`, warm Gradle daemon, via `~/bin/tcms-remote`:

| Scenario | Wall clock |
|---|---|
| `--tests 'com.tcms.tenancy.RlsIsolationTest'`, no source change | **19.1 s** |
| Same, after a one-line semantic edit to `tenancy/CompanyContext.java` (real incremental recompile + re-run) | **19.4 s** |

A no-op edit (trailing newline) came back in 5.9 s with `:test UP-TO-DATE` — Gradle's up-to-date
check is on the *class output*, so a formatting-only mutation costs nothing and a real one costs
about twenty seconds.

**The compute is not the constraint.** 598 rows × ~30 s (allowing for wider recompiles and
multi-class scopes) is under five hours of machine time. What costs is the human/agent work per row:
authoring the patch, deriving the scope, and adjudicating the red set against the prediction. Plan
against *that*, not against Gradle.

Two consequences for the harness:

- **One container per JVM.** `IntegrationTestBase` starts a single `PostgreSQLContainer` in a static
  block, so the per-run fixed cost is one container + one Flyway migration of V1…V68. Batching
  several rows into one Gradle invocation saves that fixed cost — but only where the mutations are
  in disjoint files *and* their predicted red sets are disjoint, otherwise a MORE/FEWER result cannot
  be attributed. **Default to one row per invocation**; batch only inside the scripted sweeps of §5.
- Keep the daemon warm and never run the whole suite. The queue's own instruction — *run the named
  scope, never the whole suite* — is also the cost model.

---

## 3. Lane split — 82 of the 797 rows cannot be run by `./gradlew test` at all

Classified by mutation target:

| Lane | Rows | Runner |
|---|---|---|
| **Backend** — Java, JPQL/SQL in Java, Flyway migrations, `openapi.yaml` | ~715 | `./gradlew test --tests …` on shanks |
| **TypeScript** — `packages/client`, `packages/cli`, `packages/mcp`, `frontend/src` | ~54 | `npm run test:run` in `packages/` and `frontend/` |
| **Infrastructure** — `Dockerfile`, `compose*.yaml`, `.github/workflows`, `build.gradle.kts` inputs | 9 | `docker build` / `docker compose` / `act`, no test suite |
| **Manual / live stack / real box** | ~26 | the compose stack, or a restored dump on Hetzner |

**DONE 2026-08-22.** `docs/verification/packages-and-frontend-mutation-checks.md` now holds §137
(`TS-1…9`), §139 (`CW-1…12`), §140 (`MS-1…10`) and §189 (`CN-1…7`) — 38 rows, ids unchanged, with a
forwarding line left where each section stood. Five further TS rows (`§141 RL-7/8`, `TA-9`,
`§244 ED-M8/M9`) sit **inside sections whose other rows are backend**; extracting a single row would
break the narrative its section is written as, so they stay put and are indexed in the new file's
closing table instead.

The rows were in the wrong file. The queue already did this once:
its own header records that the frontend rows of §83–§96 were struck out of a backend pass and left
in git history for whoever runs the other suite. Do the same here — move them into a sibling
`packages-and-frontend-mutation-checks.md` (or a clearly fenced section) so a backend pass is not
carrying 54 rows it structurally cannot execute. A backend row and a Vitest row have nothing in
common but the word "mutation".

The manual lane is `§223 LD-1…4` (LDAP against the compose stack), `§231 SB-1/2` (concurrent OAuth
callbacks), `IN-9` (a 400 MB body), `GA-6`, `ST-11`, `CA-6`, `DA-11`, `DR-8`, `TS-9`, `V22`'s race,
and the seven `Needs the Hetzner box` migration-in-place rows. None of these becomes runnable by
trying harder in the suite; they need a second process, a real dump, or a real box.

---

## 4. Retire 46 rows: they are not measurements

> **CORRECTED 2026-08-22, during wave 0.** This section first said ~95 rows. That number came from a
> line-level grep for *"reddens nothing"*, which swept up the **conditional** form — *"if this
> reddens nothing, that is the finding"* — and the conditional means the opposite of a prediction:
> the row **expects a red** and is saying what a zero would prove. Re-classified per row, the retire
> set is **46**, not 95. The decision below is unchanged; its size is less than half what was
> estimated. Recorded here rather than quietly restated, because the inflated number is what the
> decision was taken against.

Classified per row over the extracted table (`RUN` / `ZERO-PREDICTED` / `NOT-A-MEASUREMENT` /
`UNDETERMINED`):

| Class | Rows | Disposition |
|---|---|---|
| Ordinary — predicts a red, or says what a zero would prove | 745 | Run |
| **Zero is predicted outright** (*"expected to redden nothing"*, *"should redden nothing"*) | **30** | **Retire** to `DOCUMENTED GAPS` |
| **Not a measurement at all** (*not assertable*, *compile-time*, *no test to redden*, *a gap, not a mutation*) | **16** | **Retire** to `DOCUMENTED GAPS` |
| **Undetermined** (*"may redden nothing, and that would be the finding"*) | **6** | **Run** — the measurement settles it |

The retire is narrow on purpose. Where a row has *derived* its zero from the code's structure — a
`CHECK` makes the state unreachable, the test class hand-builds its subject, a single-threaded test
cannot arrange the interleaving — the run is a ceremony and the reasoning is the deliverable. Where
the row merely says *what a zero would mean*, the zero is in doubt and the queue's own rule binds:
**the measured number is the deliverable.**

**Each retired row must name what would close it** — a fixture, a second writer, a concurrent caller,
a real box. That register feeds §7's tests-to-write list, which is where the value of these rows
actually lives.

## 5. The duplicates — collapse 113 rows into 42

These are rows whose mutation is the *same defect shape* on a different method, and where one
measurement answers for the whole family. The queue itself has been asking for two of these for six
sections (*"the row that closes all six is one two-project fixture written once for the seam, and it
should stop being re-recorded per method"*).

| # | Family | Rows | Keep | Why one measurement answers for all |
|---|---|---|---|---|
| D1 | **`AND x.project_id = :projectId` dropped over RLS** | `12c` `R7-6` `R8-7` `CM-6` `IN-11` `IH-1` `IK-4` `AP-7` `SD-3` `§167 DL-3` `§168 CV-3` `§235 PM-1` (12) | **2** | RLS scopes to the *company*; the project predicate is the second layer, and every one of these twelve removes it in the same way. `IH-1` is the only one with a fixture that can see it (§119 grew a second project for exactly this). Run `IH-1` — the direction where a test exists — and one of the eleven blind ones as the control that proves the rest are blind. Then write the **one two-project fixture** and the family retires permanently. |
| D2 | **`AND deleted_at IS NULL` dropped from a join** | `12b` `13h` `13i` `CM-7` `IH-7` `FQ-18` `§169 XL-5` `§237 QR-7` `§240 AR-M1` (9) | **3** | Three distinct semantics, not nine: a soft-deleted **case** reached through a side table (`12b`, `FQ-18`, `§237 QR-7`), a soft-deleted **run** counted in a rollup (`13h`, `IH-7`), a soft-deleted **page/plan** serving as a spine (`CM-7`, `§240 AR-M1`). One per semantic. |
| D3 | **`ON CONFLICT` set-list clobbers a sibling writer's columns** | `DR-5` `FQ-8` `SA-6` `§158 CD-7` `§166 LR-3` `§166 LR-4` `§178 SL-M5` `§244 ED-M7` (8) | **3** | This is one rule — *own your own columns* — on one table (`case_health`) with five writers. `DR-5`'s own note already says its test seeds a signal **no job writes**, so it does not exercise the two-live-writers race it describes. The honest output here is one row run plus **one test written** that seeds two live writers; the other six are the same assertion re-spelled. |
| D4 | **`Map.of()` with a nullable lookup key (§38's NPE-by-contract trap)** | `FQ-21` `FQ-24` `CA-12` `DA-10` (4) | **1** | Four sightings of one defect that has already fired for real once (`FQ-25`, a 500 on the common path). Run one. Then close it with a **convention test** — a comment cannot prevent the fifth sighting and a mutation row per site is not prevention either. |
| D5 | **`WRITERLESS/READERLESS/UNEXERCISED_BY_DECISION` register re-add** | `UX-2` `WL-1` `AR-M5` `§181 OS-M5` `§182 CC-M5` `§242 LG-M12` `§245 BA-M11` (7) | **2** | All seven ask the same question: can `ArchitectureRulesTest`'s exemption register still fail when an exemption becomes false? One add-back and one remove-the-writer proves the rule in both directions; the other five are the same rule with a different constant. |
| D6 | **`@Transactional(readOnly = true)` removed from a repository** | `§241 OC-4` `§242 LG-M11` `§243 RN-M9` `§247 SK-M10` (4) | **1** | One line, one consequence, stated identically four times: derived queries return zero rows under RLS because the GUC is set in `doBegin`. Prove it once. |
| D7 | **Attachment owners, three copies of one design** | `CA-1…20`, `DA-1…14`, `FA-1…15` (49) | **22** | §126's three phases repeat twelve shapes across `CASE`, `DOC_PAGE` and `ARTIFACT`: the gate constant, the `OWN`-qualified delete, the download gate, `findOwnedById` scoping, project-scoped resolution, soft-delete-instead-of-delete, bytes-before-row ordering, the uploader map, the audit row losing the filename, the empty-list serialization, the wrong storage root. **Run each shape once, on the owner with the strongest fixture** (`CA-*` — 20 rows, the richest class), then run only the owner-*specific* rows on the other two: `DA-5` (archived pages still accept files), `DA-8` (a PATCH appearing to empty the list), `DA-13`/`DA-14`, and `FA-2…FA-9`, `FA-12`, `FA-14`, `FA-15` (the two-rows-referencing-each-other write, which the other owners do not have). |
| D8 | **`ORDER BY` loses its id tiebreak (determinism)** | `11e` `13g` `CM-8` `IH-6` `FQ-10` `§242 LG-M2` `§245 BA-M12` `§249 CS-M9` `R8-5` (9) | **3** | Six of the nine already predict they cannot be reddened without two rows written at the same instant, and say so. **One same-instant fixture** makes the family observable; without it, running nine gives nine unrepeatable greens. Run the three that have a discriminating fixture today and hold the rest behind that fixture. |
| D9 | **Exact restatements across sections** | `DR-4` ≡ `FQ-9` (unbounded window, the signal can never go false) · `SA-5` ≡ `§158 CD-6` ≡ `§169 XL-6` (version comparison replaced by a timestamp one) · `CL-11` ≡ `CL-29` (the `Replayed` arm falls through) · `§181 OS-M5` ≡ `§182 CC-M5` (already in D5) · `R6-8` appears twice, the second being its own corrected re-run (10) | **5** | Same mutation, same consequence, different slice wrote it down. Keep one of each and cross-reference. |
| D10 | **`~~14a~~` — void** | 1 | **0** | The file it mutates was deleted by §107. Already struck through; drop it from the count rather than carrying it. |

**Collapse total: 113 rows → 42. Saved: 71.**

### And one family that is *not* collapsible but is fully scriptable

**~28 rows are a one-word permission-constant swap** — `CA-1/2/3`, `DA-1/2/3/4`, `FA-1/11`,
`IH-2`, `RX-2/3`, `§128 CD-3/4`, `CL-9/24/28`, `§227 PM-1/2`, `§229 PM-1/2`, `§236 DK-6`,
`§240 AR-M4`, `§242 LG-M5`, `§210 DQ-5`, `§247 SK-M20`. These are **not** duplicates: each names a
different pair of keys, and the whole point is whether the seeded catalogue can *discriminate* that
pair — which the queue's own closing section shows is sometimes impossible (`company:teams` vs
`company:members` reddens nothing, because `V3` grants both to the same roles).

So keep all 28, but stop authoring them one at a time. They share one mechanical form — replace a
constant in a service, run the class named in the section header, record the red set — and belong in
a **scripted sweep** (§6, wave S). The same is true of the ~49 rows that edit a `V*` migration and
the ~15 that edit a convention-test register.

### Net arithmetic

| Step | Rows |
|---|---|
| Unrun | 797 |
| − TypeScript lane (relocated, not deleted) | −54 |
| − Infrastructure lane | −9 |
| − Manual / live-stack / Hetzner lane | −19 |
| **Backend lane** | **715** |
| − retired as non-measurements (§4) | −46 |
| − collapsed duplicates (§5) | −71 |
| **Backend rows to run** | **≈598** |

At ~30 s a row that is **five to six hours of Gradle** — but roughly 8–10 working sessions of
authoring and adjudication. That is the real number to plan around.

---

## 6. The waves

Ordered so the highest-yield rows are measured first and a stop after any wave still leaves a
coherent result.

| Wave | Content | Rows | Why here |
|---|---|---|---|
| **0** | Build the harness and the manifest (§7). Relocate the TypeScript lane. Write the `DOCUMENTED GAPS` register (§4). | — | Nothing below is repeatable without it, and the retire step removes 85 rows before any of them are attempted. |
| **1** | The rows the queue itself flags — *"THE row"*, *"the slice's own defect, armed"*, *"the security row"*, *"the row worth running first"*, *"the mutation this slice is most likely to receive"* | **~120** | Self-selected by the authors who had the code in front of them. If only one wave is ever run, this is the one. Includes `11a`, `12a`, `13a/b/c`, `R7-1`, `R8-1`, `AT-5`, `CA-1`, `DA-1`, `FA-1`, `§247 SK-M1`, `§246 CE-M5`. |
| **S** | The scripted sweeps: gate-constant swaps (28), `V*` migration edits (~49), convention-register edits (~15), enum/severity ordering (~10) | **~100** | One script, one shape, no per-row authoring. Can run unattended overnight on shanks. |
| **2** | The three cross-cutting fixtures — two-project (D1), same-instant pair (D8), two-live-writers on `case_health` (D3) — **written first**, then the family representatives run against them | **~10 + 3 tests** | These three fixtures are the only way six recurring families stop being re-recorded per method. Writing them is cheap; not writing them is why the queue has the same row in seven sections. |
| **3** | Everything remaining in the backend lane, section by section | **~330** | Batched by section, because 67 of the 124 sections name their test class in the prose rather than per row — the scope is a section property. The 57 that name nothing need the scope derived once per section, not once per row. |
| **4** | TypeScript lane | ~54 | Different runner, different session, `npm run test:run` in `packages/` and `frontend/`. |
| **5** | Infrastructure lane | 9 | `docker build` and a compose bring-up. |
| **6** | Manual / live-stack | ~19 | Folds naturally into the browser queue's live pass, which already stands up the compose stack. |
| **7** | Hetzner migration-in-place | 7 | Needs a restored dump. Independent of everything above; `V32` and `V33` are the two that matter. |

**Waves 1, S and 2 are ~230 rows and carry most of the yield.** A reasonable stopping point if the
appetite runs out is the end of wave 2 — with the retired register and the three fixtures written,
the queue is in a materially better state than a completed wave 3 would leave it without them.

---

## 7. The harness

The queue's current instruction is a shell snippet per row. At 598 rows that has to become a script,
because the two failure modes of a hand-run pass are both silent: forgetting to revert, and
mis-reading a red set.

**The pass needs its own remote tree, and this is not a nicety.** Measured 2026-08-22, wave 0:
a second session ran `./gradlew clean build` in the shared `builds/tcms` tree while this pass's
baseline was running. Two runs died in two different ways from the one cause — `clean` deleted
`build/` under a live run, so **130 classes could not write their result XML** while reporting
3249 passed and 0 failed, and a `--stop` killed the daemon 1m41s into the retry. Neither failure
looks anything like a test failure, and both would have been attributed to a mutation had one been
applied. `CLAUDE.md`'s rule — *one `backend/build/` and one Gradle daemon cannot serve two lanes* —
governs the **remote** tree exactly as it does a local one, and nothing in the tooling said so.

So the runner exports its own `TCMS_REMOTE_DIR=/home/akotrulev/builds/tcms-mut` **and** its own
`GRADLE_USER_HOME=/home/akotrulev/.gradle-mut`. The tree isolates `build/`; the Gradle home isolates
the daemon, because `--stop` stops every daemon for the user and a shared home would leave the other
lane able to kill this one at any point in a 598-row pass. One-time cost, measured: 522 MB and a
2m19s cold first run. Warm rows return to ~20s.

**The other lane's edits are still in the tree**, and one of them matters: `docs/specs/openapi.yaml`
is a **declared Gradle test input**, read as *text* by `IdempotencyKeyCapTest` and
`ConfigConventionTest`. Every measurement in this pass is therefore against HEAD plus that in-flight
contract edit. That is attributable as long as it is written down, and misleading the moment it is
not — which is why it is here and in the run record rather than in a memory.

**A manifest**, `docs/verification/mutation-manifest.tsv`, one line per row, generated from the queue
file and then hand-completed:

```
id  section  lane  file  scope  predicted_reds  status
```

`file` and `scope` are what the row leaves implicit today; filling them in is most of wave 0 and is
the thing that makes the pass resumable across sessions.

**A runner**, machine-local (like `~/bin/tcms-remote`, not in this repo), which for each row:

1. asserts the tree is clean for `file` — a row that starts dirty is a row whose predecessor did not
   revert, and that must abort the pass rather than produce a result;
2. applies the patch;
3. runs `~/bin/tcms-remote backend ./gradlew test --tests '<scope>'`;
4. parses the failed-test set out of the Gradle output;
5. `git checkout -- <file>` **by exact path** — the queue's own rule, and the reason it is written in
   capitals there;
6. appends `id, measured_reds, MATCH|MORE|FEWER|ZERO` to a results TSV.

**Results go back into the queue file as a RUN RECORD section**, in the shape the 2026-08-05 one
already has: an outcome table, then the findings written out with their reasoning. Per-row `Result`
cells get filled in place. **Ids are never renumbered** — they are quoted from handoff sections.

One rule worth stating because it is the expensive one to learn twice: **a mutation that reddens
more than predicted is as much a finding as one that reddens fewer.** The runner must record the
whole red set, not a boolean.

---

## 8. Should this be `pitest` instead?

Worth answering rather than leaving as an unspoken alternative. **No, not for this backlog, and
partly yes going forward.**

A real mutation-testing tool covers the Java-arithmetic and conditional-boundary families cheaply
and would retire whole classes of hand-written rows. But it cannot touch what most of this queue is
actually about: SQL held in Java text (`ExecutionSource`, `CaseFacts`, `OwnerStanding`), Flyway
migrations, `openapi.yaml`, convention-test registers, ArchUnit declarations, and Docker/compose
files. Those are ~60% of the rows and effectively all of the findings the 2026-08-05 pass produced.
Running PIT over a suite where every test boots Spring and a Postgres container would also be
extremely slow.

The honest position: run this backlog by hand as planned, and consider PIT afterwards as a **standing
gate for the pure-Java layer only**, which would let future slices stop writing rows for that layer.

---

## 9. Decisions taken — 2026-08-22, by the user

All four open questions were resolved before wave 0 began. Recorded here with the reasoning, so the
next reader does not re-open them.

| # | Decision | Consequence |
|---|---|---|
| 1 | **Retire the non-measurements** to a `DOCUMENTED GAPS` register; run only the 6 whose zero is undetermined — `FQ-10`, `FQ-14`, `FQ-24`, `AD-5`, `GA-1`, `DR-6`. | **46 rows, not the ~95 this plan first estimated** — see §4's correction; the decision stands, its size does not. The backend lane drops from 715 to 669 before any row is attempted. **This is a stated deviation from the queue's own rule** (*"a row predicting zero is not a row to skip — the measured number is the deliverable"*), and it is narrow: it applies only where the row has already **derived** its zero from the code's structure, never where the zero is in doubt. Every retired row keeps its id, its reasoning, and **must name what would close it** — a fixture, a second writer, a concurrent caller, a real box. That register is the input to §7's tests-to-write list. |
| 2 | **Move the 54 TypeScript rows** to `docs/verification/packages-and-frontend-mutation-checks.md`, ids intact. | `TS-*`, `CW-*`, `MS-*`, `§189 CN-*`, `§141 RL-7/8`, `TA-9`, `§244 ED-M8/M9` and the rest leave the backend queue. Ids are **never renumbered** — they are quoted from handoff sections — so the move is a relocation with a forwarding line, not a re-issue. The backend file keeps a one-line pointer per moved row's section saying where the lane went. |
| 3 | **Wave 1 before the fixtures.** | The 120 self-flagged rows are measured first, while they are the most valuable thing in the file. Accepted cost, stated rather than discovered: a handful of wave-1 rows will come back green for want of a fixture wave 2 has not written yet. **Those must be recorded as `BLIND — re-run after wave 2`, not as a zero**, or the pass will have manufactured exactly the false reassurance this queue exists to prevent. |
| 4 | **Run waves 1 + S + 2 (~230 rows), then reassess.** | Not a commitment to the full 598. The stopping point is deliberate: with the gaps register written, the TypeScript lane relocated, the three cross-cutting fixtures in place and the self-flagged rows measured, the queue is in a coherent state — which a half-finished wave 3 would not be. Wave 3 (~370 rows) is re-decided on what waves 1 and S actually find. |

### What this makes the numbers

| Step | Rows |
|---|---|
| Unrun | 797 |
| − TypeScript lane → sibling file (decision 2) | −54 |
| − Infrastructure lane | −9 |
| − Manual / live-stack / Hetzner lane | −19 |
| **Backend lane** | **715** |
| − retired to `DOCUMENTED GAPS` (decision 1) | −46 |
| − collapsed duplicates (§5) | −71 |
| **Backend rows to run, eventually** | **≈598** |
| **Committed now — waves 1 + S + 2** | **≈230** |

### Wave 0, as it now stands

1. Generate `docs/verification/mutation-manifest.tsv` from the queue file; hand-complete `file` and
   `scope` per row for waves 1 and S only.
2. ~~Create `packages-and-frontend-mutation-checks.md` and move the rows, leaving forwarding lines.~~
   **Done** — 38 rows moved whole, 5 indexed in place.
3. ~~Write the `DOCUMENTED GAPS` register.~~ **Done** — 44 rows in three buckets (true gaps ·
   tests worth writing · manual-lane rows), appended to the queue file with a pointer from *How to
   run this file*.
4. ~~Write the runner.~~ **Done** — `~/bin/tcms-mutate <id> <file> <scope>`, with the lane-scoped
   dirty-tree guard, the revert-by-exact-path step, and the remote-tree isolation above.

Nothing in wave 0 runs a mutation. It ends with 230 rows queued, addressable and resumable.
