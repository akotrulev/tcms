# §126 — attachment owners, and the table that stopped being one-third claimed

**Date:** 2026-08-07 · **Slice:** M3 slice 10 · decision round 3's **step 6 (#49 + #50)** · **four
phases, all shipped** · commits `18399b0`, `f541f84`, `e95ef1a`, + phase 4

`V1` declared `attachment` polymorphic over six `owner_type` values. **Two had ever been written** —
`EXECUTION` and `STEP_RESULT`, both by `execution.EvidenceService` since §67. This slice gave `CASE`,
`DOC_PAGE` and `ARTIFACT` writers (#49) and dropped `STEP` from the `CHECK` (#50). The table is
**fully claimed**: five values, five writers, and `V39` is what makes that a fact rather than a
description.

It is the sixth writerless shape M3 has closed, and the first where the missing writers were a
*majority* of the declared vocabulary.

---

## 1. The plan document was wrong about one of its own three owners, and the schema had said so

The plan derived four things the round had not settled — the gates, where the bytes live, the
endpoint shape, and what `EvidenceStore` is called once it backs four owners. Three held. **The
endpoint shape did not**, and the way it failed is the more useful half.

The plan wrote *"a family per owner"* and then projected the `CASE` shape onto all three: *"the same
three shapes under `/doc-pages/{pageId}` and `/artifacts/{artifactId}`"*. That would have given an
artifact a **list** of attachments. It does not have one. `V1` declares `artifact.attachment_id` — a
single nullable FK sitting among four payload columns, one per kind:

| kind | payload column |
|---|---|
| `FILE` | `attachment_id` |
| `DOC_PAGE_REF` | `doc_page_id` |
| `EXTERNAL_URL` | `external_url` |
| `GENERATED_REPORT` | `report_artifact_id` |

**A case *has* files; a `FILE` artifact *is* one.** §6n replaces a document by *superseding* it — a
second artifact naming the first — not by attaching a second file to the same row. So phase 3 is not
a third copy of the family: the bytes travel **with** the registration in one multipart request
(`POST /projects/{projectId}/artifacts/files`), and there is no attach-afterwards, no detach, no
re-upload and no `PUT`. A `FILE` row with no file is a register entry claiming a document it cannot
produce, in the module where §6n makes the audit trail the point; one request removes that state
rather than making it a window somebody has to remember to close.

The generalisation was drawn from **two** examples that agreed, and the third was in the schema the
whole time. The plan doc is corrected in place with the reasoning rather than quietly rewritten.

---

## 2. The gates, and the two most likely to be "fixed" into failing

| Owner | Attach | Delete | Download |
|---|---|---|---|
| `CASE` | `case:edit` | `case:edit` — **no `OWN` arm** | the case read gate, no extra key |
| `DOC_PAGE` | `doc:edit` — **not `doc:publish`** | `doc:edit` | the page read gate |
| `ARTIFACT` | `artifact:edit`, **scope-qualified** | `artifact:delete` (§124's key) | the register's read gate |

**No `OWN` arm on the case delete, and it is deliberately not `evidence:delete`'s shape.** Evidence
is a record of something that happened, and unmaking somebody else's record is the line the roles
spec draws. A case attachment is part of the **case definition**, which `case:edit` already owns
wholesale — a Test Engineer holding it may rewrite every step, the title and the preconditions of a
lead's case. An authorship arm would make removing the screenshot *stricter than removing the step
that references it*, which is incoherent in the direction that reads as security. Phase 4's browser
row `M2-64` is built to catch exactly that: Attach and Remove **enabled** on a case whose own Delete
button is **disabled**, two opposite verdicts in one screen.

**`doc:edit` and not `doc:publish`.** Attaching a signed-off PDF *sounds* like a publication act, so
this is a plausible misreading of the roles spec rather than a slip. An attachment is content; this
module's whole shape is that `doc:edit` writes content while `doc:publish` moves the publication
state. The publish key would leave a Test Engineer able to author a specification they cannot
illustrate.

**No download key anywhere, on all three.** The catalogue has no `attachment:read` and inventing one
repeats the `evidence:read` mistake: a key seeded to nobody is a `403` no administrator can grant
their way out of. Anyone who may read the owner may read what is attached to it — also the only rule
that does not make the SPA reason about two permissions per row.

---

## 3. THE FINDING: the download the SPA had been getting wrong since §67, on a surface that could not show it

Phase 4's one hard problem is not a gate. **This app cannot render `Attachment.url` as a link.**

The session is a bearer token the client sets on every request — `SecurityConfiguration` is
`STATELESS`, there is no cookie anywhere — so an `<a href="/api/v1/…">` sends **no credential** and
answers `401` **while rendering as a working link**. `<img src>` is the same request and therefore
the same 401, as a broken image on every row.

`api/client.ts` has named the anchor half since §89 and explicitly deferred it, recording that
`EvidencePanel` still has the hole. **The `<img>` half is named nowhere**, and
`EvidencePanel.tsx:169` renders one. So:

- Phase 4 ships **download-only**: a row's filename is a *button* that runs `fetchArtifact`
  (same-origin guard, bearer token, 60s budget) and hands the blob to `saveBlob`.
- The inline preview is recorded as **unbuilt with its reason**, not shipped broken. Making it work
  means fetching each image into an object URL — an eager full-size download of every image on page
  load, with no thumbnail endpoint to make it cheap. A broken image on every row is worse than no
  image.
- **Browser row `M4-7` is expected to FAIL against a real deployment.** It asserts §67's evidence
  thumbnail renders "not a broken image". It was written in mock mode, where the URL was served by a
  handler that needed no credential, and **has never been driven live** — the mock agreed with the
  wrong implementation, which is §107's whole argument arriving one more time.

`FileDownloadLink` is the extraction, at its **third** call site: `DownloadReportButton` was first
and stays separate (it composes a filename because a report response may name none; an attachment
always has a `filename` column). Two implementations of an authenticated download would be two
implementations of the transport, the busy guard and the in-place failure, and would agree until one
of them changed.

**The contract is not wrong and was not changed.** The server serves these `inline` with their
stored content type, which is right for any client that can carry a credential in a navigation. The
SPA is the client that cannot.

---

## 4. Three non-copies, and one shared panel

`AttachmentsPanel` is **one** component behind the case and the page, because `Attachment` is one
schema over three owner types — identical field for field *and* meaning for meaning. Two panels
would be two places deciding how a size renders, where the delete lives, what an empty list says,
and which of them gets the authenticated download right.

`EvidencePanel` stays its own file and is **not** folded in. Evidence has a caption, an
`OWN`-qualified delete and a frozen-run state; none of those exist here. The shared half is the list
and its two controls, and that is what was extracted.

The artifact surface is deliberately **not** a third panel: one register dialog grew a third kind and
branches to the upload endpoint at submit. A second Upload button would duplicate the scope rules,
the category list, the title and description limits, the `PLAN_SCOPE` qualifier and the
403-as-a-field-error mapping — and would make somebody pick an endpoint before picking a kind. The
cost is one honest asymmetry, recorded in the file: the form's `kind` is wider than
`ArtifactInput.kind`, so submit branches *before* either payload is built and TypeScript refuses a
mixed one.

**The `artifact` multipart part must be a JSON `Blob`, not a string.** `@RequestPart` picks its
converter from the part's own `Content-Type`; a string part arrives as `text/plain`, no converter
matches, and the failure is a `415` about media types nowhere near the field it is about. One test
reads the part back and asserts `.type === 'application/json'` — the assertion exists because the
symptom points at the wrong thing.

---

## 5. Two rows the schema kept and one it lost

**`artifact.attachment_id` and `attachment.owner_type = 'ARTIFACT'` are both written and are not
rivals.** A reader meeting the two will assume the schema declared one relationship twice and picked
wrong, then delete the wrong half. `owner_id`/`owner_type` are `NOT NULL` — there is no unowned blob
— while the FK is the *kind's payload*, single-valued where the owner side is not.

**`Artifact.register` takes the id as a parameter now.** The two rows reference each other, so one id
must exist before either object does. The alternative — construct the artifact, then set the column
on the transient instance — works, and is a trap: the same call on a **managed** instance compiles,
runs and persists nothing (`updatable = false`), giving a `FILE` artifact with no file and no error.

**`STEP` left the `CHECK` in `V39`.** Not a gap: a step's attachment belongs to the recorded *result*
(`STEP_RESULT`, written since §67), not to the case definition. A computed check that permanently
reports one dead value is a check that stops being read.

**`Artifact`'s "four deliberately unmapped columns" is three now** — the rule stands and its
condition expired. **Fifth blocker note in this repo to outlive its blocker.**

---

## 6. Gates

| Gate | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|---|---|---|---|---|
| `./gradlew build` | ok | 2072 / 439 | **2088 / 445 / 0 failures** | not re-run (no backend change) |
| `npm run build` | — | — | — | **clean, 0 errors** |
| `npm run lint` | — | — | — | **0 warnings** |
| `npm run test:run` | — | — | — | **1088 passed / 83 files** |

All four phase-4 figures were **re-run independently after the work landed**, not taken from the
report that produced them.

> **⚠ SEVENTH DRIFT OF THE FRONTEND TEST COUNT, and this one was measured on both sides.** Phase 3's
> commit message claims `1045/1045`; a clean solo run at `e95ef1a` measures **1055/79**. So phase 4's
> real delta is **+33 tests / +4 files**, not the +43 the carried-forward figure implies. The number
> has now been wrong in the header of the orientation cache, in a commit message, and in
> `HANDOFF.md`, every time by being copied rather than run. **Read it off a solo run or do not state
> it.**

**No migration in phases 2–4** — `V39` landed in phase 1 and is still the head; **`V40` is the next
free number**. No contract change in phase 4: `schema.d.ts` was already regenerated by the backend
phases, verified with `git log -p` rather than by re-running `gen:api`.

---

## 7. Verification

**Built and unverified**, per §65.

- **Mutation rows:** phases 1–3 appended theirs (phase 3's `FA-1`…`FA-15`), **none run**.
- **Browser rows:** **11 rows** in three lanes — `M2-62`…`M2-66` (A1, a case's files),
  `M3-65`…`M3-69` (A2, a page's files and the register's third kind), `F-8`…`F-10` (the transport
  failures). The `F` rows are in `F` because a 25MB file and a hung request are faults, not states.
- **The rows author their own fixtures**, at the top of `A1`: a PNG, a CSV, a PDF, a **zero-byte**
  file and a **25MB** file. `F-8` and `F-9` exist because the two refusals must read differently —
  a file over the limit is refused client-side before any request (the server's `413` is refused by
  the servlet container before any handler runs, so it carries no problem document and there is
  nothing to render beside a control), and a zero-byte file gets its own sentence.
- **`M3-38` was CORRECTED IN PLACE**, struck through rather than appended: it asserted the absence of
  the upload control this slice adds (*"no upload control — the storage port does not exist"*).
  Driving it against the old wording would have reported the feature as a defect. That is the second
  time a queue row has outlived the state it described.
- **Three absences are stated rather than left to look like oversights**: no inline preview (§3
  above), no `artifact:delete` screen, and no re-upload/detach row because neither exists.

---

## 8. What this leaves

**`artifact:delete` still has no screen** — unchanged from §124's note, and this slice made it
*worse*: a `FILE` artifact is the first kind whose deletion destroys **bytes**, so the missing
control now guards the last copy of a document rather than a record of one. The control, its
confirmation and its two 409 sentences are a frontend slice, and §118's dialog is the pattern.

**`EvidencePanel`'s 401 anchor and image are a live defect** on a shipped surface (§67), now the only
place in the product that renders a stored file the wrong way. `FileDownloadLink` is the fix and
applying it is a small slice; it was left alone here because evidence is not this slice's subject and
a drive-by would have made the extraction indistinguishable from a repair.

**`MAX_EVIDENCE_BYTES` and `MAX_UPLOAD_BYTES` are two names for one deployment setting.** The new
one was not folded into the old for the same reason. Fold them when evidence is next touched.

**Decision round 3's step 6 is done, and steps 1 and 2 are still next** — the shanks runner and the
key rotation, both of which need a terminal a person is sitting at. Everything after them is step 7
(#48, doc blocks).
