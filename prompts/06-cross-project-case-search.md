# Slice 6 — Cross-project case search

**Lane:** backend-led · **Migration:** none · **Depends on:** nothing (but run it after the write slices)

---

## Prompt

```text
Implement **Slice 6 — Cross-project case search** from `docs/plans/2026-08-18-unplanned-gaps.md`.

**First act: run `ep-writing-plans` on this slice alone** and save it as
`docs/plans/2026-08-18-gap-6-cross-project-search.md`. Then execute with `ep-executing-plans`.

Read first: `docs/agent-context/be-java.md`, and **`docs/specs/2026-07-30-tcms-erd.md` §0.1** —
the `company_id` rule and the RLS exception list. Then the Slice 6 section of the master plan.
**`/be-java`**.

**What this slice is:** the case repository is project-scoped throughout, so "have we tested SSO
anywhere in this company" cannot be asked — even though My Work, the boards and the org chart all
went cross-project. Add `GET /cases/search`: company-scoped, returning `projectId` and
`projectName` on every hit.

**Read this before writing the query.** This is the riskiest read in the whole plan. Every other
case read is scoped to one project and gated by that project's grant. This one returns rows from
*many* projects, so the caller's project grants must narrow it **inside the query** — not after it
in Java. Filtering a page after an unrestricted read computes the count and the paging over rows
the caller may not see, which leaks the existence and the volume of work they have no access to.

**Handoff:** next free § from `docs/HANDOFF.md`. Orientation cache in this slice's own commit.
```

---

## The trap this slice turns on

**RLS scopes to the company, not the project. It will not save you here.**

Row-level security answers "is this row this company's". The question this endpoint asks is "is this
row in a project this *person* was granted", and that is the application's job — the two gates are
independent by design (company membership, then an explicit project grant). Everywhere else the
project gate is implicit in the URL. Here it is not, and this is precisely where it is easiest to get
wrong.

**Acceptance gates, both required:**

1. A person granted **one project of three** sees hits from one project only — and **assert on the
   total count**, not just the first page. A count computed over all three projects tells them how
   much work exists elsewhere.
2. A query-plan check that the search does not seq-scan. `V67` already fixed one seq scan on the
   authorization hot path; do not add another. Note that `CREATE INDEX CONCURRENTLY` has been
   refused in this repo's migrations before — check how `V67` did it rather than reaching for it.

## Standing rules that bind this slice

- **Tests run on `shanks`** — `~/bin/tcms-remote backend ./gradlew build`.
- **`@Transactional(readOnly = true)` on the repository interface**, or every read returns zero rows
  while the context holds the right company. `RepositoryTransactionTest` will fail the build.
- **Check the permission, never a role name.** There is no wildcard permission.
- Full-text search here is Postgres FTS. Elasticsearch is deferred until FTS proves insufficient —
  this slice is not the place to reopen that.
- Verification rows into both queues; run nothing (§65).
