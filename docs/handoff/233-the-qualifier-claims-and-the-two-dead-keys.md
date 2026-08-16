# §233 — The qualifier claims, and the two keys that were never dead

**Date:** 2026-08-16 · **Scope:** C11 + C8 of `docs/audits/2026-08-15-flow-review-and-improvements.md`
(the first slice of the C run, sequenced in that audit's §3.6; C6's `ArtifactKind.java` prose fix
rides along as decided). Prose, one spec, one test rule — **no behaviour change anywhere**: no
endpoint, no migration, no permission moved, and the one contract file touched (`openapi.yaml`)
changed only descriptions and comments, with both `schema` copies regenerated.

## C11 — the rule was extended first, and it found more than the audit did

The audit listed four stale sites asserting the pre-`V51`/`V52` qualifiers as current
(`useCan.ts:47`, `ArtifactDialogs.tsx:190,262`, `ArtifactTable.tsx:227`). Two of the four had
already been repaired in passing by A6 (§229) — which is itself the finding restated: a sweep by
hand is only as complete as the slice that happens to walk past. So the slice was done in the
audit's own order, **rule before repairs**: teach `tierClaims.test.ts` a third claim form, run it
red, fix what it names.

**The third form is {@link QUALIFIER_CLAIM}** — a grant asserted as *narrowed*: `` `artifact:edit`
is `PLAN_SCOPE` ``, `` `case:delete` is `OWN`-qualified ``, `held plan-scope-only`. The first two
forms structurally cannot see these (they name a qualifier, not a tier), which is exactly how seven
of §212's repairs had to be found by hand. Ground truth is the spec's own matrix cells: a qualifier
narrows a grant *in the table* (`quarantine only`, the one live instance), so a claim whose key has
no narrowing cell asserts data the catalogue does not hold — and if a migration ever re-qualifies a
grant, the spec's cell moves first and the rule reads it from there, rather than needing its own
constant flipped.

Three design points that cost a red run each:

- **The spaced `plan scope only` is deliberately unmatched.** It appears only inside quotation
  marks, as the cell text `matrixCopies.test.ts` and `roleMatrix.ts` give as their example of a
  wrong copy. The hyphenated compound is the spelling every live claim was actually written in.
- **The qualifier form gets its own history/negation guard**, wider than `PAST` (`granted`,
  `carried`, `until`, `no`, `not`, …) because qualifier prose narrates its own emptying far more
  often than tier prose does — and kept *separate* from `PAST`, because `granted` in that list
  would excuse a present-passive tier line (`is granted Lead-only`) the first form exists to catch.
- **Distance is now measured over normalised whitespace, for all three forms.** A YAML description
  wrapped under twelve spaces of indent put sixty characters between `artifact:edit` and the
  qualifier written about it — one line apart on screen, outside the `NEAR` window as raw text —
  and the ninth finding only appeared once the scan stopped counting indentation as prose.

**Red run: nine findings, zero false positives** — the audit's two still-live sites plus seven the
audit never listed, every one of them the same event (`V51` §207 emptying `OWN`, `V52` §210
emptying `PLAN_SCOPE`) leaving a present-tense sentence behind:

| Site | Claimed |
|---|---|
| `frontend/src/auth/useCan.ts` (canOnScope header) | `artifact:edit` is `PLAN_SCOPE` |
| `frontend/src/features/artifacts/ArtifactDialogs.tsx` (canProjectScope) | same, citing V19 as current |
| `frontend/src/api/queries/cases.ts` (useDeleteCase) | `case:delete` is `OWN`-qualified |
| `frontend/src/features/cases/CaseDetailPage.test.tsx` (attachment-gate header) | same — reworded to say the **fixture** serves `OWN`, fabricated deliberately, which is what it always meant |
| `docs/specs/openapi.yaml` (`createdBy` comment) | `case:delete` is `OWN`-qualified |
| `docs/specs/openapi.yaml` (`ArtifactInput.scope` **description** — copied verbatim into both `schema` files by `gen:api`, so this one sentence was three on disk) | `artifact:edit` plan-scope-only |
| `backend/.../project/ProjectAccess.java` (canDoOnScope) | same |
| `backend/.../repository/CaseAttachmentEndpointTest.java` | `evidence:delete` is `OWN`-qualified |
| `backend/.../documentation/DocPageAttachmentEndpointTest.java` | same |

All nine corrected with the repository's strikethrough-→-correction notation or the past tense —
none deleted, because each was the argument for a gate or an arm that is *retained* (§A5: the
qualifier is data, and the plumbing must not fail open the day a grant is qualified again). Two
near-misses the rule's guards excused were fixed anyway while in the files:
`ArtifactWriteService`'s mixed tense (*grants… and granted*) and `PermissionEvaluatorTest`'s
quoted historical line, which the `carried` guard now reads correctly.

**C6's rider:** `ArtifactKind.GENERATED_REPORT` no longer claims *"the `reporting` module does not
exist"* — it exists since §87 and §227 gave it a job list; what is missing is only the
save-to-register writer (`artifact.report_artifact_id`), decided *finish* and not yet built.

## C8 — the annotation had to say the uncomfortable half

The decision was an explicit *checked-by-project-read* annotation on `doc:read` and
`artifact:read`, docs-only. Writing it honestly required saying something both service headers
denied: **their shared evidence sentence has been stale since `V16`.** Both argued the second gate
"has never answered differently from the first" because the one role without the read keys —
Automation Service — *"holds no `project:read` either"*. `V16` granted AS `project:read` as the §A6
visibility precondition, so since 2026-08-01 there is exactly one role for which the two gates
genuinely diverge: **an AS credential that can see the project can read its pages and its register,
and the `—` in those two matrix cells is not enforced.** The annotation (under the spec's
Documentation and Artifacts tables) records that as accepted — the keys get their own evaluation
site when custom roles arrive, and until then a check no reachable caller can drive is one nobody
can tell is inverted — and both headers now carry the `<del>` correction pointing at it. A future
matrix sweep finding "no evaluation site" for either key is finding this paragraph, not a defect.

## Gates (on shanks)

Packages **331** (78 / 130 / 123 — client +2 for the new form's vacuity guard and findings test),
frontend **1508 / 111** with `npm run build` and 0 lint warnings, backend `./gradlew build`
**BUILD SUCCESSFUL**. No verification-queue rows: nothing here renders, mutates, or answers a
request differently — the one behavioural sentence in this slice (the AS read divergence) was true
before it and is merely *written down* now.

## Not done

- The rest of the C run, in the decided §3.6 order: **C6 + C7 + C10** (the artifact slice) is
  next, then C3, C5 + C9, C2, C1, C4.
- The tier-claims rule still does not catch a claim that names a *role and a tier but no key*
  (§212's stated floor), and reads neither `CLAUDE.md` nor `README.md` — the matrix copies there
  are `matrixCopies.test.ts`'s subject, but their surrounding prose is nobody's.
