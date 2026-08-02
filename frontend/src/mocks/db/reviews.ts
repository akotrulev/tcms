/**
 * The review record, mock side — plan §6g's reviewer apparatus, with the server's own rule about
 * what a status move does to it.
 *
 * Three handlers move an artifact along a ladder (plans, suites, the case bulk operation) and every
 * one of them has to open and close reviews the same way, so the rule lives here exactly as it
 * lives in one class on the server (`project.ReviewService#moved`). Three copies inside the mock is
 * how mock mode starts recording a withdrawal as a rejection on one screen and not on another —
 * §52's divergence class, inside a single codebase.
 *
 * What this deliberately does not do is invent a review for an artifact that is already
 * `IN_REVIEW`. The record starts when something is submitted *after* it exists; an artifact that was
 * in review before has no row, which is the state every real instance is in for its existing work
 * and is why the fixtures below carry one of each.
 */
import type { Review, ReviewParticipation, ReviewSubjectType, UserRef } from '../../api/types';
import { db, newId } from './store';

/** Where an artifact sits relative to review — the server's `ReviewStanding`, three values. */
export type ReviewStanding = 'OUTSIDE_REVIEW' | 'IN_REVIEW' | 'APPROVED';

/** A stored review, plus the project it belongs to. */
export interface MockReview extends Review {
  projectId: string;
}

export function reviewsForSubject(
  projectId: string,
  subjectType: ReviewSubjectType,
  subjectId: string,
): MockReview[] {
  return db.reviews
    .filter(
      (review) =>
        review.projectId === projectId &&
        review.subjectType === subjectType &&
        review.subjectId === subjectId,
    )
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

export function openReviewFor(
  projectId: string,
  subjectType: ReviewSubjectType,
  subjectId: string,
): MockReview | undefined {
  return reviewsForSubject(projectId, subjectType, subjectId).find(
    (review) => review.state === 'IN_REVIEW',
  );
}

/**
 * What a status move does to the review record — the server's three rules, in the server's order.
 *
 *  1. into `IN_REVIEW` → open one, pinned to the version submitted, unless one is already open;
 *  2. out of `IN_REVIEW` to `APPROVED` → close it and write the `APPROVAL` row naming the signer;
 *  3. out of `IN_REVIEW` to anything else → close it, as a **withdrawal** when the person taking it
 *     back is the one who submitted it and as a rejection otherwise.
 *
 * Anything else writes nothing, including an approval on an artifact with no open review.
 */
export function reviewMoved(
  projectId: string,
  subjectType: ReviewSubjectType,
  subjectId: string,
  versionNo: number,
  to: ReviewStanding,
  actor: UserRef,
): void {
  const open = openReviewFor(projectId, subjectType, subjectId);

  if (to === 'IN_REVIEW') {
    if (!open) {
      db.reviews.push({
        id: newId(),
        projectId,
        subjectType,
        subjectId,
        subjectVersionNo: versionNo,
        state: 'IN_REVIEW',
        submittedBy: actor,
        createdAt: new Date().toISOString(),
        approvedBy: null,
        approvedAt: null,
        participations: [],
      });
    }
    return;
  }

  if (!open) return;

  if (to === 'APPROVED') {
    open.state = 'APPROVED';
    open.approvedBy = actor;
    open.approvedAt = new Date().toISOString();
    open.participations.push(participation(actor, 'APPROVAL', 'APPROVED'));
    return;
  }

  // The submitter taking their own submission back is not a rejection, and must not read as one.
  const withdrawn = open.submittedBy?.id === actor.id;
  open.state = withdrawn ? 'WITHDRAWN' : 'REJECTED';
  if (!withdrawn) {
    open.participations.push(participation(actor, 'APPROVAL', 'REJECTED'));
  }
}

/** A peer's verdict — the only kind `POST …/participations` can write, on either side. */
export function recordVerdict(
  review: MockReview,
  actor: UserRef,
  verdict: 'LGTM' | 'CHANGES_REQUESTED',
  comment: string | null,
): void {
  review.participations.push(participation(actor, 'PEER_REVIEW', verdict, comment));
}

function participation(
  actor: UserRef,
  kind: ReviewParticipation['kind'],
  verdict: ReviewParticipation['verdict'],
  comment: string | null = null,
): ReviewParticipation {
  return {
    id: newId(),
    user: actor,
    kind,
    verdict,
    comment,
    createdAt: new Date().toISOString(),
  };
}
