# §150 — the unit-test lane this suite never had, and the two things the javadoc got wrong

**Date:** 2026-08-09 · **Branch:** `master` · **Scope:** `backend/src/test` only — **no production
code changed, no migration, no contract change, no frontend, no `packages/`.**

Driven by the `be-java:be-java-unit-testing` plugin skill, run against the repository as an audit and
then as a build.

---

## 1. What the skill owns, and what this suite had of it

The skill's boundary is one sentence: a unit test constructs the class with `new`, drives it, and
asserts on its return value or its effect on a fake. **Anything that needs an annotation which starts
an application context is not one** — that is the `qa-api-java` peer's lane.

Counted at the start of the slice:

| | classes |
|---|---:|
| Boot a Spring context (`IntegrationTestBase`, `@SpringBootTest`, ArchUnit's context-backed rules) | 100 |
| Pure — no context, no Postgres | 16 |

116 classes, 2328 tests, and **every backend test but sixteen boots a real Postgres 17 through
Testcontainers.** That is not a defect in itself — this project's reasons for having no H2 lane are
good ones, and RLS, `ltree` and `citext` are only provable against the real engine. It is a defect in
what it *leaves unobserved*: a pure function reached only through an endpoint test is asserted at the
resolution of that endpoint's response, which for several classes here turned out to be no resolution
at all.

## 2. The audit — what the skill's rules found, and what they did not

**Almost every anti-pattern the skill lists is already absent, and that is worth recording rather
than glossing.** Scanned across `src/test`: no `@InjectMocks` (0), no `lenient()` (0), no `@MockBean`
(0), no `mockStatic` (0), no `spy()` on a class under test (0), no `Thread.sleep` (0), no `@Disabled`
(0), no `org.junit.jupiter.api.Assertions` (AssertJ throughout, 0 mixed), and exactly one class in
the whole suite that imports Mockito at all. The clock is a constructor-injected `Clock` bean with an
ArchUnit rule behind it (§60), which is the skill's first seam already closed and enforced.

So the finding is not conformance. **It is coverage of a lane that was never opened**: five classes
carrying real logic, each reached only through a Testcontainers test that asserts something else.

| Class | Reached only by | What was unasserted |
|---|---|---|
| `execution.ReleaseSortKey` | nothing — **no test named it** | every property of the key |
| `storage.BlobUpload` | attachment endpoint tests, one well-behaved filename each | traversal, header injection, checksum, key shape |
| `execution.PlanScope` | plan endpoint tests, via the response | that normalization is a fixed point |
| `identity.PermissionSet` | `PermissionEvaluatorTest`, via real grants under RLS | what the value object *refuses* to represent |
| `execution.ApprovalGating` | `RunCreateEndpointTest`, via a created run | both invariants its javadoc states |

Four classes were considered and **left to the integration lane on purpose**, because the skill's
"never unit-test this" table names them: `repository.CaseSort` (builds `CriteriaBuilder`
expressions — the generated SQL is the thing under test), `repository.CaseSpecifications` (same),
`reporting.AggregateSelection` (composes SQL fragments), and every JPA entity.

## 3. What was added — 61 tests, 5 classes

- **`ReleaseSortKeyTest`** (13) — the padding, the shorthand, the suffix rules, the text fallback,
  the null cases. The one it exists for is `tenSortsAfterNine`, which asserts the two *keys* order
  correctly **and** that the two raw strings do not, because without the second assertion a reader
  can conclude the padding is decoration.
- **`BlobUploadTest`** (19) — filename reduction under both separators, the two traversal shapes, the
  four ways a name becomes nothing, quote/backslash escaping, **CRLF header injection**, SHA-256
  against known vectors rather than a round-trip through `MessageDigest`, and the key's three
  server-generated segments.
- **`PlanScopeTest`** (9) — sorting, dedup, null-dropping, the two levels, the targetless row that is
  a section held open, `itemCount` counting *rows written* rather than targets, and
  `normalizationIsAFixedPoint`: `ofItems(rowsOf(scope)) == scope`. That last one is the class's
  stated purpose — "the one definition of what the same scope means" — and nothing asserted it.
- **`PermissionSetTest`** (10) — a real `PermissionCatalogue` over a stubbed `PermissionRepository`.
  Two of the assertions expect a throw and they are the ones worth having: a `NONE` qualifier stored
  as a held entry makes `has()` answer true for a permission covering no rows, and an unknown key
  denies forever while reading as a grants problem. Also the catalogue's memoization, its
  empty-catalogue diagnostic (asserted on the message, because naming `V3` *is* the value), and
  **the vacuous pair**: `hasAll()` with no keys is `true`, which is the dangerous half.
- **`ApprovalGatingTest`** (4) — §6g's two invariants, both prose until now. `refusal == null` means
  proceed and never "refused with nothing to say"; `overridden` means **the gate fired and was
  overridden**, not "the caller sent the flag" — the distinction is the whole of §6g's word
  *documented*, since the audit field is what accuses somebody of a bypass.

Mockito needed no dependency change: `spring-boot-starter-test` already brings
`mockito-junit-jupiter` 5.17.0 to the test runtime classpath.

## 4. THE TWO FINDINGS — both in `ReleaseSortKey`, both stated wrongly in its own javadoc

The class exists to prevent a **silent** wrong answer: text collation puts `"1.10"` before `"1.9"`,
so "what did the newest release do" answers about a release from three months ago with a number on
the screen that looks exactly right. It does prevent that one. It documents two other cases it does
not handle the way it says it does, and **neither was observable from any test that existed**, because
no test named the class.

### 4a. A pre-release sorts AFTER the release it precedes — the defect the class exists to prevent, arriving through the suffix instead of the padding

The javadoc: *"anything after the patch component — `-rc.1`, `+build.7` — is appended verbatim after
the padded triple, so `4.3.0-rc.1` sorts immediately before `4.3.0` exactly as semver requires,
without this class having to implement pre-release precedence."*

It does not. `of("4.3.0")` is `00004.00003.00000` and `of("4.3.0-rc.1")` is
`00004.00003.00000-rc.1` — **the release's key is a strict prefix of its pre-release's, and a prefix
collates first.** So the release candidate sorts as the newer of the two, on a column
`test_run_release_idx` leads on, read by `ORDER BY` in three tables. This is the same shape as
`"1.10" < "1.9"`: right-looking output, wrong release.

The first draft of the test asserted the javadoc and went red. **It is pinned as it behaves, not
repaired**, and the reason is not effort:

- `release_sort_key` is written on **three** tables (`test_run`, `test_execution`, `execution_fact`).
- It is ordered by **Postgres**, not by Java, so the fix is a decision about which collation the
  separator character is being chosen under — punctuation is not weighted the same way under ICU as
  under C.
- Every existing row already holds the current format, so any format change needs a backfill.

That is a slice with a migration in it, and it needs the handoff entry a slice gets. The assertion
makes it impossible to change by accident and no longer possible to miss.

### 4b. A dated build parses as a version — the text fallback the javadoc offers as its example is not one

The javadoc: *"A value that does not parse — `"nightly"`, `"2026-W31"` — falls back to itself."*

`"nightly"` does. `"2026-W31"` does not: `2026` matches the major component, the optional minor and
patch groups do not match, and `-W31` is accepted as a pre-release suffix — so the key is
`02026.00000.00000-W31`. **The resulting order is correct** (dated builds sort by date, which is
better than falling back to text would have been), so this is a documentation defect rather than a
behavioural one. It is pinned, with the ordering property asserted alongside it, because a caller
reading the javadoc would expect the raw string back.

Both are recorded at the assertion, in the test, with why they are pinned rather than fixed.

## 5. Gates

`cd backend && ./gradlew build` — see the index row for the figure this slice's run produced. The
five new classes run in the same `test` task and need no Docker; they add no container start.

## 6. What is NOT here

- **No production code changed.** The two findings above are recorded, not repaired — see 4a for why
  the first one is a slice of its own.
- **No queue rows.** Nothing renders, no request differs, no stored byte differs: this slice adds
  test classes and nothing else, which is the one shape that genuinely earns that claim.
- **No mutation-score check.** The skill hands assertion quality to `qa-test-quality-check-java`
  (PIT), which this repo has never run. Every new assertion here was verified the manual way — by
  checking it goes red against the behaviour it denies — but that is a per-assertion argument, not
  the suite-level number PIT would give.
- **The 100:16 ratio is unchanged in kind.** Five classes moved into the pure lane; the rest of the
  suite still boots Postgres, correctly. A sweep of the remaining `execution` and `reporting` value
  types for the same treatment is the obvious follow-up and was not attempted here.
