# AREA-005 — Review & Collaboration

**Created:** 2026-08-03 · **Owned by:** `be-shared-business-documentation`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03

Covers `project.ReviewController` and its service: peer review, threaded comments, reviewer
assignment, and the per-project settings (AREA-002) that turn the apparatus on and configure
it. Applies uniformly to three kinds of content: test cases, suites, and test plans.

## What this area is for

Lets a team formally review a piece of test content (a case, a suite, or a plan) before it's
approved, discuss it in threaded comments, and explicitly ask a specific person or team to look
at it — without inventing a separate mechanism for each of the three content types.

## Actors

- **Anyone who can see the project (incl. Viewer)** — may comment and read reviews.
- **Test Engineer and above** — may give a formal review verdict (LGTM / request changes).
- **Test Lead and above** — may approve, and may resolve comment threads.
- **Anyone with review rights on the subject** — may ask ("assign") a specific colleague or
  team to review; may withdraw a request they made or that's theirs to manage.

## Capabilities

1. **See every review of one subject** (a specific case, suite, or plan), newest first. `GET
   /projects/{id}/reviews?subjectType=&subjectId=` — `ReviewController.java:46-71` `#observed`.
   There is deliberately no "all reviews in the project" listing; a future review inbox is
   named as a different, harder question the code does not yet answer.
   `ReviewController.java:49-53` `#observed`.
2. **Give a peer verdict on an open review.** `POST /reviews/{id}/participations` —
   `ReviewController.java:73-92` `#observed`.
3. **Leave a threaded comment on an open review.** `POST /reviews/{id}/comments` —
   `ReviewController.java:94-116` `#observed`. Threads are one level deep. `#inferred` from
   orientation cache (§79), not independently re-read this session.
4. **Mark a comment thread resolved, or reopen it.** `PUT
   /reviews/{id}/comments/{id}/resolution` — `ReviewController.java:118-139` `#observed`.
5. **Ask a specific person or team to review** (assign). `POST /reviews/{id}/assignees` —
   `ReviewController.java:141-165` `#observed`.
6. **Withdraw a review request.** `DELETE /reviews/{id}/assignees/{id}` —
   `ReviewController.java:167-188` `#observed`.
7. **Turn the whole apparatus on or off per project, and per artifact type**, plus
   author-cannot-approve-own-work and an edit-after-approval policy — see AREA-002's
   `ProjectSettingsController`. `#inferred` from orientation cache (§77), not independently
   re-read this session in this pass.

## Business rules

- **One review record type serves cases, suites and plans alike** — a review's subject is an
  opaque (type, id) pair rather than three separate tables, which is what lets one apparatus
  serve all three without tripling the code (and the ways it could disagree with itself).
  `ReviewController.java:22-31` `#observed`.
- **Commenting is a separate, lower tier from formally reviewing, and it is deliberately ONE
  permission covering all three content types** — a Viewer may comment on a case, a suite, or a
  plan with the same single grant. Naming the wrong thing when refused matters enough that
  commenting and reviewing get two different 403 sentences. `ReviewController.java:94-116,247-256`
  `#observed`.
- **Resolving a comment thread costs the reviewing permission, not the commenting one** — marking
  a point dealt with is a statement about the review's own progress, not a remark, so it's
  gated like a review act rather than a comment act. `ReviewController.java:118-139` `#observed`,
  consistent with the roles spec's explicit statement of this rule.
- **A closed review refuses every one of its five write paths with the same sentence** —
  participating, commenting, resolving, assigning and unassigning on a review that has already
  ended all answer the identical 409. `ReviewController.java:198-213` `#observed`.
- **Asking someone to review is a worklist entry, not part of the permanent review record** —
  unassigning is a genuine, hard delete (no "withdrawn" tombstone), unlike a comment (which is
  always kept, resolved or not). `#inferred` from orientation cache (§80), not independently
  re-read this session, but consistent with `unassign` returning 200 with the review rather
  than any kind of soft-delete marker.
- **No new permission was created for "ask someone to review"** — deliberately: a permission
  seeded to nobody would be a refusal no administrator could grant their way out of, so asking
  costs the same review permission the ask is about. `#inferred` from orientation cache (§80,
  explicitly a business/design decision recorded there), not independently re-verified this
  session.
- **The person being asked to review is checked against their ability to see the project, not
  against company membership** — you cannot be assigned a review on something you cannot open.
  `#inferred` from orientation cache (§80), not independently re-read.
- **A review request cannot be withdrawn or resolved by inventing an implicit "everybody
  clear" action** — assignment is `POST` (add one) rather than `PUT` (replace the whole list),
  specifically so nobody can accidentally clear every colleague's assignment in one call, and
  so two people assigning at once don't race each other into a lost update.
  `ReviewController.java:141-153` `#observed`.

## Failure behavior

| Situation | Response | Evidence |
|---|---|---|
| Unknown/misspelled `subjectType` | 400, field error naming the three valid values | `ReviewController.java:62-66` `#observed` |
| Reviewing/commenting without the right tier's permission | 403, with a sentence specific to which tier was missing | `ReviewController.java:88-116,247-256` `#observed` |
| Any write on an already-closed review | 409, "review closed" | `ReviewController.java:198-213` `#observed` |
| Unreachable review/project | 404 | `ReviewController.java:211` `#observed` |

## Boundaries / not built

- **There is no review "inbox"** — nothing surfaces "reviews assigned to me" or "overdue
  reviews" outside the one artifact's own review panel; that would need expanding the (type,
  id) subject into something readable across cases/suites/plans at once, which the design
  explicitly calls out as unbuilt. `ReviewController.java:49-53` `#observed`.
- **No notifications** of any kind fire from review events (assigned, commented, approved).
  `#inferred` from orientation cache, not independently verified this session.

## Open questions for the business

1. Is a "my reviews" / "review inbox" screen a near-term priority, or is per-artifact review
   sufficient for now? The endpoint shape today deliberately refuses to answer that broader
   question. `#inferred`
2. Should review events (assigned, commented, resolved, approved) trigger any notification
   channel (email, in-app), or is the review panel itself, checked manually, the intended
   workflow indefinitely? `#inferred`
