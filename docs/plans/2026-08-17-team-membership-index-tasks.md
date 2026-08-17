# Next slice — landing T5's index, and closing the org chart's record

> **☑ RUN 2026-08-17.** Landed as `V67__team_membership_answers_which_teams_a_person_is_in.sql`
> (Flyway head was `V66`, confirmed by listing the directory). **The `CONCURRENTLY` question in step 2
> was resolved the OTHER way**: plain `CREATE INDEX`, with the argument in `V67`'s header and plan
> §6.1 struck through accordingly, so §6.1's DDL block no longer reads as the recommendation. Handoff
> §241 amended in place, `be-java.md` gains §241-A, one mutation-queue row (`§241 IX-1`) recorded as a
> gap. **One correction to step 2's own text:** it names five migrations refusing `CONCURRENTLY` —
> the count is six, and only four (`V17`, `V42`, `V59`, `V62`) refuse it *on the record*; `V11` and
> `V14` made the same call without saying so, which `V17`:66-69 notes on their behalf. T9 and T10
> remain unrun.

**Debt from `docs/plans/2026-08-15-org-chart.md`**, whose T5 lane measured a defect it was not
looking for and recommended DDL the org-chart slice deliberately did not carry. This file is the
prompt for the slice that lands it.

---

## Already done — do not redo any of it

| | State |
|---|---|
| Org chart, all eight code lanes | ☑ `3f100b1` … `bd420eb`, **on `master`** — not on `worktree-org-chart-plan`, which is still at `5149e58` |
| `GET /api/v1/org-chart` + 4 schemas, both `schema` copies | ☑ T1 `2b3546a` |
| `effectiveProjectRoleNames` id-list generalisation, §A1.1 helper | ☑ T2 `ba5ed64` |
| `OrgChartService` / `OrgChartController` | ☑ T3 `1ecd0db` |
| Backend tests — §4.4 row by row, §8.3 both directions, RLS both GUC spellings | ☑ T4 `d5b9b89` |
| `teamTree.ts` extraction | ☑ T6 `3f100b1` |
| `useOrgChart` + all nine staffing invalidations + the tenth derived arm | ☑ T7 `c98b49a` |
| `/org-chart` screen, four components | ☑ T8 `bd420eb` |
| Handoff §241, index row, both caches, both queues | ☑ T11 `64f9814` |
| **T5 query-plan review** | ☑ `0b2191e` — the finding is plan **§6.1** |
| T9 disclosure-boundary security pass | ☐ **not run**, still deferred |
| T10 manual test-case suite | ☐ **not run**, still deferred |

**The org chart itself needs no code.** It is built, gated, tested and recorded. This slice touches
one migration, one handoff amendment, and nothing on the org-chart surface.

---

## The prompt

```
Repo: ~/RiderProjects/tcms
Branch: whatever is checked out — check with `git rev-parse --abbrev-ref HEAD` and do NOT
create a new one.

Read CLAUDE.md FIRST — it is binding. Then docs/agent-context/be-java.md (trust its BODY
over its `Baseline commit:` header, and read the git delta since that header). Then
docs/plans/2026-08-15-org-chart.md **§6.1** — that is T5's measured finding and the whole
input to this slice.

Load the `data-migrations` and `data-postgresql` skills. Backend work → `/be-java`.

## What is already done

The org chart slice is COMPLETE and needs no code: eight code lanes plus the record landed
on master as 3f100b1..64f9814, and T5's review landed as 0b2191e. Do not re-implement any
of it, do not touch OrgChartService/Controller, the contract, or the /org-chart screen.
T9 and T10 remain deliberately unrun and are NOT part of this slice.

## The task

Land the index §6.1 recommends, and close the record it left open.

1. **The migration.** §6.1 gives the DDL and the reasoning; the version is UNASSIGNED there
   on purpose. Take the next free version — head was `V66` on 2026-08-17, so V67 unless
   something landed first. CONFIRM by listing backend/src/main/resources/db/migration/
   rather than trusting this number: `out-of-order` is unset, so a version landing after a
   higher one is REFUSED on any already-migrated database, and Testcontainers cannot see it
   because it migrates a fresh container every time. Two other worktrees are live.

2. **RESOLVE A REAL CONFLICT BEFORE YOU WRITE IT — this is the substance of the slice.**
   §6.1's DDL is `CREATE INDEX CONCURRENTLY` plus a `flyway:no-transaction` hatch. But
   **no migration in this repo has ever used that hatch**, and five have refused
   CONCURRENTLY on the record: V11, V14, V17, V42 and V62. Read V17's header (:62-69) — it
   is the fullest statement of the reason, it says the refusal is "a choice rather than an
   oversight", and it ends: *"the day one of them is genuinely hot, that is a decision for
   all four at once."*

   T5's measurement is arguably that day arriving: `team_membership` is read by
   `findProjectScopedHeldPermissions`, the authorization hot path, on every permission
   check. So decide it deliberately and write the decision into the migration header:
   - either take CONCURRENTLY, and then you are establishing the repo's FIRST
     no-transaction migration — say so, say what it means for the other five, and check
     what Flyway's no-transaction mode does to this project's `baseline-on-migrate: false`
     and to a failed-migration recovery (a CONCURRENTLY index that fails leaves an INVALID
     index behind, which the next run must cope with);
   - or take a plain `CREATE INDEX` and record why the ACCESS EXCLUSIVE lock is acceptable
     on a 60k-row / 8.5 MB table, in V17's own terms.
   Either is defensible. Choosing without noticing the precedent is not. If you take the
   plain form, §6.1 is then partly superseded and must be struck through and corrected —
   this project writes reversals down rather than quietly diverging.

3. **`SchemaConventionTest.companyIdLeadsCompositeIndexes`** (:128) passes by construction
   because `company_id` leads the index. Verify that rather than assuming it.

4. **Amend handoff §241.** It states "T5, T9 and T10 were not run"; T5 has since run and
   found something the plan had backwards. Handoff index rows are NEVER renumbered, so
   amend §241 in place (struck through, "→ superseded", per the working agreement) — do
   not renumber it and do not write a new section for the amendment alone. If this slice
   grows its own findings beyond the amendment, THOSE get a new section at the current
   high-water mark (§241 was the top on 2026-08-17 — re-measure).

5. **Update `docs/agent-context/be-java.md`** in this slice's own commit.

## What NOT to do

- No perf test. This repo has no performance lane, and a timing assertion would be flaky
  on shared CI. The measurement belongs in the migration header as prose with T5's numbers.
- Do NOT re-run T5's measurement to "verify" it unless you have reason to doubt it. If you
  do re-measure, measure as `tcms_app` with `app.current_company` set — a plan measured as
  the owner or a superuser is a DIFFERENT query, because the RLS predicate is part of it.
- Do NOT run browser passes or backend mutation checks (§65 — deferred and batched, and
  "deferred means ALL of them"). Record the rows instead. Ids come from the current
  high-water mark at append time, never from a number written in a document.
- Do NOT touch T9 or T10.

## Gates and commit

Gate: `./gradlew build` in backend/ — needs Docker (Testcontainers). Per the standing
directive, run it on shanks: `~/bin/tcms-remote backend ./gradlew build`. `hibernate.ddl-auto:
validate` means booting the context is itself a gate. CI runs NO backend tests, so this local
gate is the only observer until 01:17 UTC.

Stage ONLY the files this slice touches (`git add <paths>`, NEVER `git add -A`) — two other
worktrees are live, and a whole-file `add` in a shared plan file has already swept another
lane's in-flight edit into the wrong commit once in this project (2026-08-17). Re-read any
shared doc immediately before editing it, and check `git status` immediately before staging,
not merely before editing.

Commit at the end of the phase, after the gate passes.

If you find a real problem with this task as specified, say so in a sentence or two, then
deliver it under a stated assumption. Do not silently rescope.
```

---

## Notes

- **The slice is small on purpose.** One index, one header decision, one amendment. The
  temptation is to bundle T9/T10 or the `tierClaims.test.ts` extension §241 names; all three are
  their own slices.
- **Two other candidates for "next", both larger**, if this one is not what you want: T9's
  disclosure-boundary pass over real HTTP (§241 calls it the one that matters most, and it needs a
  provisioned instance), and the `tierClaims.test.ts` *"gated on `<key>`"* shape — the instrument gap
  §241 identifies, which is what would have caught the false `CurrentUser.teams` prose T1 repaired
  by hand.
- **Q4's two deferred follow-ups** from plan §14 are still deferred with owners: retire
  `roleAccess.ts` in favour of the server's effective-role answer, and §6k's live-workload figures.
