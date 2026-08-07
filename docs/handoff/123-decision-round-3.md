# §123 — decision round 3, and the list that stopped being read

**2026-08-07 · documents only · no code, no gates**

Sixteen questions, **#45–#60**, resolved in
[`docs/plans/2026-07-30-tcms-high-level-plan.md`](../plans/2026-07-30-tcms-high-level-plan.md) §8 —
thirteen decided, three deferred with what they wait on named. **Derived from the code, not from the
plan**, per §28/§29/§46: the six computed checks in
`docs/audits/2026-08-05-remaining-scope-assessment.md` §1, re-run at `e43e1b3`.

## What round 2 got wrong, and it was not a decision

§78 closed with **"Nothing in §8 is open"** and the sentence right after it: *the next thing that
decays this list is not an unanswered question but an unrecorded one — a decision taken in a slice
and never written back here.* **That is exactly what happened.** Between §78 and §122 the product
grew M2.5's whole reporting lane and the entirety of M3, and what those slices left behind was
recorded in handoff sections and audit findings instead.

**None of #45–#60 is a new uncertainty.** Every one was already visible in the code as a writerless
table, an unconsumed enum value, an unenforced permission key, or a decision taken and never applied.
The list did not stay closed — **it stopped being read**. That is a different failure with a
different fix: not "decide more carefully", but "re-derive from the code on a schedule", which is
what the six computed checks are for and why a round 4 is cheap.

Two entries are the failure mode in miniature, and they are the reason step 4 exists:

- **#47** — #17's `BEST` removal was **resolved on 2026-08-01 and never applied.**
- **#57** — the Resolved table already says *"versions written on explicit save only; autosave goes
  to a draft buffer"*. The version half shipped in §29. **The buffer half never did**, so the editor
  has no autosave at all and `test_case_draft` has no writer.

**#53 is the third sighting**: `artifact:delete` and `run:execute` were called the cheap wins in
round 2 *and* round 3 of the scope assessment, and taken in neither.

## The fixed order, and why it is an order

Nine steps. Sequenced on dependency and on **what each step lets the next step observe** — not on
size.

| Step | Item | Why here |
|---:|---|---|
| 1 | **#46** register the shanks runner | Changes what every later gate can see. Seven of the nine steps write backend code; landing this first means they land on a **Linux** observer instead of on the one §58 proved structurally blind. |
| 2 | **#56** rotate the JWT key | Oldest open action in the repository, and **not carryable**: `application.yml`'s colon-less spelling means a box whose env file lacks `TCMS_JWT_SECRET` cannot be deployed to at all. The next release is blocked until it is done. |
| 3 | **#60** write §110's section | Pure evidence decay, cheapest now. **Done — [§110](110-the-live-pass-runs.md).** |
| 4 | **#47 + #53** | One slice. A contract change and two gate wirings, all three decisions already taken. |
| 5 | **#51** extract `storage` | The architectural move, **alone**. `ModuleStructureTest` and §3 change here and nowhere else. |
| 6 | **#49 + #50** attachment owners | Depends on 5. `CASE`, `DOC_PAGE`, `ARTIFACT` get writers; `STEP` leaves the `CHECK`. |
| 7 | **#48** doc blocks | Depends on 6 for `IMAGE`'s `DOC_PAGE` owner. |
| 8 | **#57** draft buffer, then **#59** clone | Additive on working features, independent of everything above. |
| 9 | **#52** the outbox | Last, deliberately. Widest blast radius, and by then it lands on a gate that has been green for eight steps, so a reddening is attributable. |

**Steps 1 and 2 have a written procedure** —
[`docs/plans/2026-08-07-linux-gate-and-key-rotation.md`](../plans/2026-08-07-linux-gate-and-key-rotation.md).
Both need a terminal a person is sitting at: `sudo` on shanks needs a password and cannot be
answered from a tool call, and the rotation touches a live instance's session keys.

**#51 must not be folded into #49.** `Attachment`'s own javadoc names the extraction *and* its exact
precondition — "a second caller to design against" — which #49 creates twice over. Doing them
together is how a module move becomes indistinguishable from a feature in one diff.

## The three deferrals, each with what it waits on

| # | Deferred | Waiting on |
|---|---|---|
| 54 | The instance/company admin surface — `instance_setting`, and seven unenforced permission keys | **M6.** §122 gave the operator tier a CLI writer, which is what makes the wait survivable. **Standing cost:** #29 made sign-off records editable *on the strength of the audit trail*, and nothing can read that trail today. |
| 55 | The S3/MinIO adapter | **The Helm chart** — and the correction is that it blocks on **two** adapters, `EvidenceStore` (§67) and `ReportStore` (§87). Nothing in the repository said so before this round. Exactly the "must not be discovered late" #6 warned about, discovered late enough to still be free. |
| 58 | Rich text + sanitized HTML | **After M4.** Revisit with the **Markdown** option first — the report renderers already emit it, so there is an existing lane and no new storage shape. |

A deferral recorded as a deferral, with its blocker named, is a different artifact from an entry
nobody answered. That distinction is the whole point of this table, and §121's open question #24 —
the **fourth** time a plan's own "blocked" note outlived its blocker — is why it is written down.

## The honesty note, restated because it is the one that will be forgotten

**#45 chose "close the small gaps", not the verification drain.** That does not retire the queues —
**it grows them.** #47 through #57 each add rows to both. The scope assessment's §5.2 calls the
~790 unrun rows the largest single block of remaining work, covering every slice from §65 to §122,
and §110 is the measurement of what a driven row is worth: six product findings in ~60 rows, none of
them reachable by any gate.

**Every slice in an unrun queue is *built and unverified*, and that now includes everything this
round ships.**

## Also recorded this round

- **The instance operator console is folded into M6** — `docs/plans/2026-08-07-instance-operator-console.md`,
  written and **scheduled, not queued**. It reverses §6s's "no company management UI", which was
  load-bearing on M6 being "a week of surface, not a phase": M6 goes from 1.5–2 weeks to **3–4**.
  The cost is struck through in the decision log rather than quietly dropped.
- **Granting Instance Admin stays CLI-only**, deliberately — the one exception to "all actions via
  the UI". Minting an operator should require a shell on the box.
- **`Company.suspend()` has had a writer and no reader since `V1`.** Nothing exposes suspension, so
  nothing is broken today; it becomes a defect the moment a suspend control ships. Both land
  together in M6, never in the other order.
- **The scope assessment is stale by 21 slices** and now says so in its own header — read its method
  (§1's six computed checks) and §5.2, not its shot list.
