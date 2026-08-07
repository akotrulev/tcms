# M3 slice 10 — attachment owners (#49 + #50)

**Decision round 3, step 6.** Depends on step 5 (§125, `storage` extracted). Written 2026-08-07.

`V1`'s `attachment` table is polymorphic over six `owner_type` values and **two of them have ever
been written** — `EXECUTION` and `STEP_RESULT`, both by `execution.EvidenceService` since §67. This
slice gives `CASE`, `DOC_PAGE` and `ARTIFACT` writers (#49) and drops `STEP` from the `CHECK` (#50),
taking the table from one-third claimed to fully claimed.

## What the plan already decided, and what this document adds

Round 3 settled the *what*. It did not settle the *how*, and four things had to be derived from the
code rather than looked up:

1. **Which permission gates each owner.** No key may be invented — the roles spec is authoritative
   and a key seeded to nobody is a 403 no administrator can grant their way out of (the standing
   argument against `evidence:read`). Derived below.
2. **Where the bytes live.** One root or a third one.
3. **Whether an owner's attachments are their own endpoint family or an embedded list.**
4. **What `EvidenceStore` is called once it backs four owners.**

## The gates — derived, not invented

| Owner | Attach | Delete | Download |
|---|---|---|---|
| `CASE` | `case:edit` | `case:edit` | the case read path, no extra key |
| `DOC_PAGE` | `doc:edit` | `doc:edit` | the page read path, no extra key |
| `ARTIFACT` | `artifact:edit` (plan-scope-qualified) | `artifact:delete` | the artifact read path, no extra key |

**`case:edit` for the delete as well, and it is deliberately not authorship-qualified.** The
tempting move is to copy `evidence:delete`'s `OWN` arm. It is wrong here, and the reason is what the
two things *are*: evidence is a record of something that happened, and unmaking somebody else's
record is the line the roles spec draws. A case attachment is part of the **case definition**, which
`case:edit` already owns wholesale — a Test Engineer holding it may rewrite every step, the title and
the preconditions of a lead's case. An authorship arm on the attachment would make deleting the
screenshot *stricter than deleting the step that references it*, which is incoherent in the direction
that reads as security. The same argument carries to `doc:edit` and a page's blocks.

**`artifact:delete` for the artifact, and that is `artifact:edit`'s asymmetry, not a new one.**
§124 built that key precisely so a Test Engineer registering a plan's documents cannot destroy a
lead's sign-off record. A `FILE` artifact is the first kind whose deletion also destroys bytes, so
the asymmetry that already existed becomes load-bearing rather than merely correct.

**No download key anywhere.** The catalogue has no `attachment:read` and inventing one repeats the
`evidence:read` mistake. Anyone who may read the owner may read what is attached to it — which is
also the only rule that does not require the SPA to reason about two permissions per thumbnail.

## The bytes go in the evidence root, and the port gets the table's name

There are two roots today because reports and evidence **differ in retention and in what losing them
costs** (§125): a report is regenerable, evidence is not. A case attachment, a page image and a
signed-off document are all in evidence's category — not regenerable, backup-critical — so they go in
that root and no third root is introduced.

That makes `EvidenceStore` a port serving four owner types under a name that describes one, and
`repository` importing a type called `EvidenceStore` is a question a reviewer would have to ask.
**The type is renamed `AttachmentStore`** (`FilesystemEvidenceStore` → `FilesystemAttachmentStore`),
which is what it has been since §125 gave it no notion of what it stores.

**The configuration property `tcms.storage.evidence.root` is NOT renamed, and that is the decision
rather than an omission.** Renaming a config key relocates the root on the next deploy: the old
directory keeps existing, unreferenced, while every existing row's bytes become unreachable and the
product renders a list of attachments that all 404. That is §107's evidence destruction in config
form, bought for a naming nicety. The property is the *deployment's* identity; the type is the
*code's*, and only one of them is free to change.

## Endpoint shape — a family per owner, and lists embedded in the detail

Attachments are read as part of the thing they are attached to, exactly as §121 landed
`TestCase.flake`: **detail-only**, never on a list or grid. There is no `GET .../attachments`
collection endpoint, because nothing would call it — the detail already serves the list and a
second read path is a second answer to "what is attached to this".

The writes are their own controller per owner, not extra methods on the existing one, for
`EvidenceController`'s stated reason: multipart and a streamed binary body bring handlers
(`MaxUploadSizeExceededException`) that would otherwise apply to every endpoint beside them.

```
POST   /projects/{projectId}/cases/{caseId}/attachments                    multipart → 201
DELETE /projects/{projectId}/cases/{caseId}/attachments/{attachmentId}     → 204
GET    /projects/{projectId}/cases/{caseId}/attachments/{attachmentId}/content
```

…and the same three shapes under `/doc-pages/{pageId}` and `/artifacts/{artifactId}`.

## `STEP` leaves the `CHECK` — #50

`V39` narrows `owner_type` to the five values that have writers. The argument is the audit's: a
computed check that permanently reports one dead value is a check that stops being read. `STEP`
specifically is not a gap — a step's attachment belongs to the **recorded result**
(`STEP_RESULT`, written since §67), not to the case definition, so the value was a design that was
reconsidered rather than a feature nobody built.

**The index is deliberately not touched.** `attachment_owner_idx (owner_type, owner_id)` omits
`company_id`, and `SchemaConventionTest` does not object because its rule is *"`company_id` leads
every index that includes it"* — presence is not asserted. That is a real hole in the check and it is
recorded here rather than fixed, because fixing it would be wrong: every read this slice adds is a
point lookup on a UUID `owner_id`, and leading with `company_id` would make the index less selective
for exactly the queries that use it.

## Phases

| # | Scope | Gate |
|---|---|---|
| 1 | `V39`, the `storage` vocabulary (`BlobUpload`, the three factories, the port rename), and **`CASE` end to end** in `repository` | `./gradlew build` |
| 2 | `DOC_PAGE` in `documentation` | `./gradlew build` |
| 3 | `ARTIFACT` — `ArtifactKind.FILE` becomes registrable | `./gradlew build` |
| 4 | The three frontend surfaces | `npm run build` + `lint` + `test:run` |

Phase 1 carries the shared half deliberately: a phase that shipped `storage`'s new vocabulary with
no consumer would be the writerless seam §121 phase 3 deleted and §125 warned about again.

**Verification is deferred and batched (§65).** Every phase appends its rows to
`docs/verification/backend-mutation-checks.md`, and phase 4 to
`docs/verification/frontend-browser-checks.md`. Nothing in this slice is verified by running it.
