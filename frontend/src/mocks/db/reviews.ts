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
  ReviewComment,
  ReviewParticipation,
  ReviewSubjectType,
  TeamRef,
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
        assignees: [],
        participations: [],
        comments: [],
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

/**
 * Writes one remark — a thread root, or a reply under one.
 *
 * The stored shape is the served shape: roots at the top level, each carrying its own `replies`.
 * The server stores rows flat with a `parentId` and assembles them, and the mock keeping them
 * already-assembled is the one place the two implementations differ in *storage* rather than in
 * behaviour — which is fine, and would not be if a second reader had to un-nest them.
 *
 * Returns null when the parent does not resolve **or is itself a reply**, which the handler turns
 * into the server's field error. Threads are one level deep on both sides or mock mode teaches an
 * author a shape the product refuses.
 */
export function addComment(
  review: MockReview,
  actor: UserRef,
  body: string,
  parentId: string | null,
): ReviewComment | null {
  const written: ReviewComment = {
    id: newId(),
    author: actor,
    body,
    createdAt: new Date().toISOString(),
    resolvedAt: null,
    resolvedBy: null,
    replies: [],
  };

  if (!parentId) {
    review.comments.push(written);
    return written;
  }

  const root = review.comments.find((thread) => thread.id === parentId);
  // A reply's own id never matches a root's, so a reply-to-a-reply lands here as "no such root" —
  // the same refusal, which is also how the server words it: telling the two apart tells a caller
  // that the id they guessed names a real comment somewhere they cannot see.
  if (!root) return null;

  root.replies.push(written);
  return written;
}

/**
 * Asks a person or a team — §6g's reviewer assignment.
 *
 * **Already asked writes nothing and is not an error**, the server's rule and §79's reason:
 * re-stamping would hand `assignedBy` to whoever clicked last, so the field kept to record who
 * asked would name the second person to agree. A mock that appended a second chip would teach an
 * author that double-asking is visible when it is not.
 */
export function assignReviewer(
  review: MockReview,
  actor: UserRef,
  assignee: { user: UserRef } | { team: TeamRef },
): void {
  const already = review.assignees.some((row) =>
    'user' in assignee ? row.user?.id === assignee.user.id : row.team?.id === assignee.team.id,
  );
  if (already) return;

  review.assignees.push({
    id: newId(),
    user: 'user' in assignee ? assignee.user : null,
    team: 'team' in assignee ? assignee.team : null,
    assignedBy: actor,
    assignedAt: new Date().toISOString(),
  });
}

/**
 * Withdraws a request — a **hard delete**, no tombstone.
 *
 * An assignment is a worklist entry rather than part of the audit record, which is the server's
 * decision and the reason a comment cannot be deleted while this can. Returns false when the id
 * names nothing in this review, which the handler turns into the server's 404.
 */
export function unassignReviewer(review: MockReview, assignmentId: string): boolean {
  const at = review.assignees.findIndex((row) => row.id === assignmentId);
  if (at < 0) return false;
  review.assignees.splice(at, 1);
  return true;
}

/** Marks a thread dealt with, or re-opens it. Roots only — `undefined` when the id is a reply. */
export function threadRoot(review: MockReview, commentId: string): ReviewComment | undefined {
  return review.comments.find((thread) => thread.id === commentId);
}

/** Whether any comment in this review carries the id at all — a reply included. */
export function commentExists(review: MockReview, commentId: string): boolean {
  return review.comments.some(
    (thread) =>
      thread.id === commentId || thread.replies.some((reply) => reply.id === commentId),
  );
}

/**
 * Sets a thread's resolution.
 *
 * **Already in the requested state writes nothing**, which is not housekeeping: re-stamping would
 * hand `resolvedBy` to whoever clicked last, so the pair that records who dealt with a thread would
 * name the second person to agree rather than the person who decided. The server's rule, and it was
 * a real defect in the first draft of it.
 */
export function setResolution(thread: ReviewComment, actor: UserRef, resolved: boolean): void {
  if (!!thread.resolvedAt === resolved) return;
  thread.resolvedAt = resolved ? new Date().toISOString() : null;
  thread.resolvedBy = resolved ? actor : null;
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
