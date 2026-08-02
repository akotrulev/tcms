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
import type {
  ProjectSettings,
  Review,
  ReviewParticipation,
  ReviewSubjectType,
  UserRef,
} from '../../api/types';
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

/**
 * §6g's defaults — the same six values `ProjectSettings.defaults()` answers on the server, and the
 * same ones `V23`'s column defaults write.
 *
 * Duplicated here rather than imported from anywhere, because the mock exists to be a second
 * implementation of the contract; what must not diverge is the *contract*, and a test pins these
 * against the schema's own declared defaults.
 */
export function defaultProjectSettings(projectId: string): ProjectSettings {
  return {
    projectId,
    reviewEnabledCase: false,
    reviewEnabledSuite: false,
    reviewEnabledPlan: false,
    requiredPeerReviews: 0,
    authorMayApproveOwnWork: false,
    editAfterApproval: 'RETAIN',
    updatedBy: null,
    updatedAt: null,
  };
}

/**
 * A project's settings — **the one read**, defaults-tolerant exactly like
 * `ProjectSettingsService#effective`.
 *
 * The demo project is seeded with review ON so the fixtures §76 added are reachable and the panel
 * has something to render; every other project answers the defaults, which is what a real newly
 * provisioned project looks like. Both are states a real instance produces, and having only one of
 * them is how mock mode starts disagreeing with a fresh install (§72).
 */
export function settingsForProject(projectId: string): ProjectSettings {
  const stored = db.projectSettings.get(projectId);
  if (stored) return stored;

  const seeded: ProjectSettings =
    projectId === db.projects[0]?.id
      ? {
          ...defaultProjectSettings(projectId),
          reviewEnabledCase: true,
          reviewEnabledSuite: true,
          reviewEnabledPlan: true,
        }
      : defaultProjectSettings(projectId);

  db.projectSettings.set(projectId, seeded);
  return seeded;
}

/** Replaces them, stamping the editor — the server's PUT, mirrored. */
export function saveProjectSettings(
  projectId: string,
  update: Omit<ProjectSettings, 'projectId' | 'updatedBy' | 'updatedAt'>,
  actor: UserRef,
): ProjectSettings {
  const saved: ProjectSettings = {
    ...update,
    projectId,
    updatedBy: actor,
    updatedAt: new Date().toISOString(),
  };
  db.projectSettings.set(projectId, saved);
  return saved;
}
