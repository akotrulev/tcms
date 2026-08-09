# §151 — the release that sorted after its own candidate, and the standard fix that measures as a no-op

**Date:** 2026-08-09 · **Branch:** `master` · **Scope:** `backend/` only — **one migration (`V44`),
no contract change, no frontend, no `packages/`.**

§150's finding 4a, taken as the slice §150 said it needed. Driven by the `be-java:be-java` and
`data:data-migrations` plugin skills.

---

## 1. The defect

`ReleaseSortKey` exists to prevent a silent wrong answer — V1's comment: *"Without this, '1.10'
sorts before '1.9' and every latest-release query breaks SILENTLY (plan §6q)."* It prevented that
one and introduced the same failure through the other end:

```
4.3.0        ->  00004.00003.00000
4.3.0-rc.1   ->  00004.00003.00000-rc.1
```

The release's key is a strict **prefix** of its own release candidate's, and a prefix collates
first. So the candidate reads as the newer of the two, and *"what did the newest release do"*
answers about the thing that was never released — with a version number on the screen that looks
exactly right. Semver states the opposite in as many words: a pre-release has **lower** precedence
than the associated normal version. The class javadoc had claimed the correct behaviour since M0.

The fix is a fourth component between the triple and the suffix: **`0` for a pre-release, `1` for
the release it precedes.**

```
4.3.0-rc.1     ->  00004.00003.00000.0-rc.1
4.3.0          ->  00004.00003.00000.1
4.3.0+build.7  ->  00004.00003.00000.1+build.7   build metadata is not a pre-release
```

## 2. THE FINDING — the obvious fix is a no-op, and it passes everywhere except here

The standard answer to this exact problem is Debian's: append a sentinel character that sorts above
everything, so the pre-release's letters fall below it. **Measured against the running database, it
changes nothing.**

| | `'…-rc.1' < '…~'` |
|---|---|
| `en_US.utf8` (libc) — **what this instance runs** | **false** |
| `C` | true |
| `und-x-icu` | true |

glibc's `en_US` is a multi-level collation that weights punctuation below letters, so `~` compares
**below** the `r` of `rc` and the ordering stays exactly as broken as before. The sentinel passes
under two collations and fails under the one actually in use — so the obvious repair, checked on a
differently-initialised box or in a unit test over Java's `String`, would have shipped green and
fixed nothing.

This is not a hypothetical: `RK-1` in the mutation queue **is** that fix, applied faithfully, and it
leaves three tests red on a real Postgres. It is the strongest form of §58's lesson — not "your
machine cannot see Linux", but *"Java cannot see the comparison"*: **Postgres orders this column,
not the application**, so the only observer that could ever have settled the question is a database.

A digit is a strong character in every collation and compares at the primary level under all three,
which is what makes this key's ordering a property of the key rather than of how a given cluster
happened to be initialised.

## 3. The second finding — nothing reads the column

`release_sort_key` is written on **three** tables, carries **three** indexes
(`test_run_release_idx`, `test_execution_release_idx`, `execution_fact_release_idx`), and there is
**no `ORDER BY release_sort_key` anywhere in the application.** `releaseVersion` is an exact-match
filter in every operation that takes it.

So the wrong ordering has never reached a screen and could not have. The column is maintained, and
indexed three times, for a query nobody has written yet — the **readerless** mirror of the writerless
shape §116, §117, §121 and §132 each found, and the reason a defect this central survived from M0.
It also means this slice ships no queue row on the browser side: nothing renders differently.

That is recorded rather than acted on. Giving it a reader is §6q's latest-release view, a feature and
not this slice. What correcting the stored value now buys is that the eventual reader is not written
against three tables of keys that order releases backwards.

## 4. `V44` — data-only, and a rewrite rather than a re-derivation

**It does not re-implement the parser in SQL.** It does not have to: the new key is a pure *string
rewrite* of the old one, since every part of the old key survives and only the flag is inserted.
Re-deriving from `release_version` would put a second implementation of `ReleaseSortKey.of()` in
PL/pgSQL that could disagree with the Java one and write rows the application would never write —
the divergence this project keeps finding. **A rewrite of the stored value cannot disagree about
parsing, because it does not parse.**

**The predicate is anchored on purpose** (`^[0-9]{5}[.][0-9]{5}[.][0-9]{5}([-+].*)?$`), which is
exactly the shape `of()` produces when it parsed, and therefore skips three things: the text fallback
(`nightly`), rows already in the new format (so the migration is **idempotent**), and — the trap —
a fallback that merely *looks* padded. `12345.12345.12345 hotfix` does not parse, falls back to
itself, and an unanchored prefix match corrupts a value that was **correct before the migration
ran**. `RK-2` is that mutation and it goes red on exactly that row.

**RLS: the owner is not exempt.** All three tables are under `FORCE ROW LEVEL SECURITY`, which covers
the table owner Flyway connects as, so an `UPDATE` with no `app.current_company` set matches **zero
rows and reports success** — a migration that runs clean and changes nothing, on every table, for
every company. `V44` loops over `company` (instance-level, ERD §0.1) setting the context per company:
V15's and V18's pattern, **third use**. `withoutContextTheUpdateIsASilentNoOp` asserts the mechanism
rather than trusting the comment.

**Not batched**, against the migrations skill's default, with the reason recorded in the file: the
loop is already per company, migrations run pre-deploy on a single-instance self-hosted product, and
a resume cursor for a statement with no plausible row count is ceremony.

## 5. The test that executes the migration instead of copying it

`ReleaseSortKeyBackfillTest` reads `V44` **off the classpath and executes it** — re-executing is
legitimate precisely because idempotency is one of the claims under test. A copy of the `UPDATE` in
the test would be a second implementation that could pass while the shipped one was wrong, which is
the shape §4 refuses for the parser.

Its equivalence assertion is against `ReleaseSortKey.of()` rather than against literals, so the
migration and the writer cannot drift: **a backfilled row must be indistinguishable from one written
by a run created after the deploy.**

`execution_fact` carries no foreign keys at all (V1), so a two-company fixture needs no project, case
or run — which is what keeps a nine-test integration class cheap. Ids are `f5…`, a prefix free across
the suite (checked against every UUID literal in `src/test`, not against the six classes that happen
to spell the convention in a javadoc).

## 6. What is NOT here

- **Precedence among pre-releases.** `-rc.10` still sorts before `-rc.9`: two suffixes are still
  compared as text. Same shape one level down, confined to one release's candidates rather than
  sitting between a release and its candidate, and repairing it is a second format change plus a
  second backfill. `ReleaseSortKeyTest` pins it as a **known limit** rather than leaving it a
  surprise.
- **A reader for the column** — see §3.
- **No contract change**, so neither `schema.d.ts` copy moved and neither the frontend nor
  `packages/` was rebuilt: `release_sort_key` appears nowhere on the wire.

## 7. Gates

`cd backend && ./gradlew build` — **SUCCESSFUL in 9m22s, 2401 tests across 499 result classes, 0
failures** (§150's run: 2389 / 495). +12 tests, +4 result classes — one new class whose three
`@Nested` groups each report separately, plus a net +3 in `ReleaseSortKeyTest` where §150's single
inverted pin became five tests.

Both new checks were verified red against the behaviour they deny — `RK-1` and `RK-2` in
`docs/verification/backend-mutation-checks.md`, both marked RUN.
