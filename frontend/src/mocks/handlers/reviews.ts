/**
 * The review record's endpoints — plan §6g's advisory tier, the trail behind it, and the threads
 * that hang off it.
 */
import { HttpResponse, delay, http } from 'msw';
import type { ReviewSubjectType } from '../../api/types';
import {
  addComment,
  assignReviewer,
  commentExists,
  db,
  recordVerdict,
  reviewsForSubject,
  saveProjectSettings,
  setResolution,
  settingsForProject,
  threadRoot,
  unassignReviewer,
} from '../db';
import { API, LATENCY, holdsOn, meRef, notFound, problem, projectMembers } from './shared';

const SUBJECT_TYPES: ReviewSubjectType[] = ['CASE', 'SUITE', 'PLAN'];

/** Which key a peer verdict costs, by subject type — the server's map, mirrored. */
const REVIEW_PERMISSION: Record<ReviewSubjectType, string> = {
  CASE: 'case:review',
  SUITE: 'suite:review',
  PLAN: 'plan:review',
};

/** `ReviewCommentEdit.MAX_BODY`, and the contract's `maxLength` on `ReviewCommentInput.body`. */
const MAX_COMMENT = 5000;

const SUBJECT_NOUN: Record<ReviewSubjectType, string> = {
  CASE: 'case',
  SUITE: 'suite',
  PLAN: 'plan',
};

export const reviewHandlers = [
  /**
   * The project's §6g policy.
   *
   * **No permission check**, matching the server: the read is gated on project visibility alone, so
   * that §6g's "a project that never turns it on should see no extra UI at all" is implementable by
   * a client rendering for a Viewer. A mock that asked for `project:settings` here would make the
   * review panel vanish in mock mode for exactly the personas it must still work for.
   */
  http.get(`${API}/projects/:projectId/settings`, async ({ params }) => {
    await delay(LATENCY);
    return HttpResponse.json(settingsForProject(String(params.projectId)));
  }),

  http.put(`${API}/projects/:projectId/settings`, async ({ params, request }) => {
    await delay(LATENCY);
    const projectId = String(params.projectId);

    if (!holdsOn(projectId, 'project:settings')) {
      return problem(
        403,
        'Not allowed',
        "You do not have permission to change this project's settings.",
      );
    }

    const body = (await request.json()) as Record<string, unknown>;
    const peers = body.requiredPeerReviews;

    // The server's two field errors, in the server's words. `requiredPeerReviews` and
    // `editAfterApproval` are required while the three switches default to false — an absent switch
    // is "off", which is §6g's default, and an absent number is an incomplete request.
    const errors: Record<string, string[]> = {};
    if (typeof peers !== 'number' || !Number.isInteger(peers)) {
      errors.requiredPeerReviews = ['Give a number of required peer reviews.'];
    } else if (peers < 0 || peers > 20) {
      errors.requiredPeerReviews = ['Required peer reviews must be between 0 and 20.'];
    }
    if (body.editAfterApproval !== 'RETAIN' && body.editAfterApproval !== 'RE_REVIEW') {
      errors.editAfterApproval = [
        'Choose what an edit to an approved artifact does: RETAIN or RE_REVIEW.',
      ];
    }
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    return HttpResponse.json(
      saveProjectSettings(
        projectId,
        {
          reviewEnabledCase: body.reviewEnabledCase === true,
          reviewEnabledSuite: body.reviewEnabledSuite === true,
          reviewEnabledPlan: body.reviewEnabledPlan === true,
          requiredPeerReviews: peers as number,
          authorMayApproveOwnWork: body.authorMayApproveOwnWork === true,
          editAfterApproval: body.editAfterApproval as 'RETAIN' | 'RE_REVIEW',
          // §6g's gating rules (§82), read the same way as the switches above: `=== true`, so an
          // absent field is OFF. That is the server's reading and the only safe one here — a
          // dropped field that turned a gate ON is the one reading of an omission that can stop a
          // release. `saveProjectSettings` takes the whole value, so the compiler refuses a
          // handler that forgets one, which is how this omission was caught rather than shipped.
          requireApprovedCaseForRun: body.requireApprovedCaseForRun === true,
        },
        meRef(),
      ),
    );
  }),

  http.get(`${API}/projects/:projectId/reviews`, async ({ params, request }) => {
    await delay(LATENCY);
    const url = new URL(request.url);
    const subjectType = url.searchParams.get('subjectType') as ReviewSubjectType | null;
    const subjectId = url.searchParams.get('subjectId');

    // Both parameters are required and both are read, exactly as the server binds them (§50): a
    // filter the mock ignored would make the panel look correct here and answer the wrong
    // question live.
    if (!subjectType || !SUBJECT_TYPES.includes(subjectType)) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        subjectType: ['Choose a subject type: CASE, SUITE or PLAN.'],
      });
    }
    if (!subjectId) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        subjectId: ['Name the artifact whose reviews you want.'],
      });
    }

    return HttpResponse.json(reviewsForSubject(String(params.projectId), subjectType, subjectId));
  }),

  /**
   * A peer's verdict. Three refusals in the server's order, and the order is the point (§72): the
   * permission first, so a caller who may not review is told 403 rather than learning whether this
   * particular review is still open; then the verdict; then the review's state.
   */
  http.post(
    `${API}/projects/:projectId/reviews/:reviewId/participations`,
    async ({ params, request }) => {
      await delay(LATENCY);
      const projectId = String(params.projectId);
      const review = db.reviews.find(
        (row) => row.id === params.reviewId && row.projectId === projectId,
      );
      // Resolved through the PROJECT, never by id alone — a review belonging to another project is
      // 404 here as it is on the server, where RLS scopes a bare lookup to the company.
      if (!review) return notFound('That review');

      if (!holdsOn(projectId, REVIEW_PERMISSION[review.subjectType])) {
        return problem(
          403,
          'Not allowed',
          "You do not have permission to review this project's work.",
        );
      }

      const body = (await request.json()) as { verdict?: string; comment?: string | null };
      // APPROVED and REJECTED are refused rather than accepted-and-ignored: they end a review and
      // belong to the artifact's own ladder, so a mock that took them would offer a second door to
      // the sign-off that only fails against a real backend.
      if (body.verdict !== 'LGTM' && body.verdict !== 'CHANGES_REQUESTED') {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          verdict: ['Choose a verdict: LGTM or CHANGES_REQUESTED.'],
        });
      }

      if (review.state !== 'IN_REVIEW') {
        return problem(
          409,
          'Review closed',
          `This review has already ended. Submit the ${SUBJECT_NOUN[review.subjectType]} for `
            + 'review again to reopen it.',
        );
      }

      const comment = body.comment?.trim() ? body.comment.trim() : null;
      recordVerdict(review, meRef(), body.verdict, comment);
      return HttpResponse.json(review, { status: 201 });
    },
  ),

  /**
   * A remark on an open review — §6g's threads.
   *
   * **Gated on `case:comment` whatever the subject is**, which is the whole shape of the server's
   * decision: the catalogue has one comment key and three review keys, because commenting is the
   * bottom tier rather than a per-artifact judgement. A mock that asked `REVIEW_PERMISSION` here
   * would hide the control from exactly the Viewer §6b built the key for, and mock mode would look
   * correct while contradicting the product.
   */
  http.post(
    `${API}/projects/:projectId/reviews/:reviewId/comments`,
    async ({ params, request }) => {
      await delay(LATENCY);
      const projectId = String(params.projectId);
      const review = db.reviews.find(
        (row) => row.id === params.reviewId && row.projectId === projectId,
      );
      if (!review) return notFound('That review');

      if (!holdsOn(projectId, 'case:comment')) {
        return problem(
          403,
          'Not allowed',
          "You do not have permission to comment on this project's work.",
        );
      }

      // Closed before validation, the server's order (§74): a caller told to fix a field on a
      // review that has ended is being told to do something that will not help.
      if (review.state !== 'IN_REVIEW') {
        return problem(
          409,
          'Review closed',
          `This review has already ended. Submit the ${SUBJECT_NOUN[review.subjectType]} for `
            + 'review again to reopen it.',
        );
      }

      const body = (await request.json()) as { body?: string; parentId?: string | null };
      const text = body.body?.trim() ?? '';
      if (!text) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          body: ['Write a comment.'],
        });
      }
      if (text.length > MAX_COMMENT) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          body: [`Keep the comment under ${MAX_COMMENT} characters.`],
        });
      }

      if (!addComment(review, meRef(), text, body.parentId ?? null)) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          parentId: ['That comment cannot be replied to.'],
        });
      }
      return HttpResponse.json(review, { status: 201 });
    },
  ),

  /**
   * Asks a person or a team — §6g's reviewer assignment.
   *
   * Costs `*:review`, the same key the verdict does: asking somebody to look at an artifact is a
   * statement about the review's progress, so `case:comment` is deliberately **not** enough. A mock
   * that gated this on the comment key would give the Viewer an "Ask" control that 403s live —
   * §31's disable-don't-hide inverted, which is the failure a stateful mock exists to prevent.
   *
   * The three field errors are the server's, in the server's words. The visibility check is the one
   * thing the mock cannot mirror faithfully — it has no evaluator for *another* person — so it
   * checks project membership, which is the list the picker offers and a subset of the server's
   * rule. Recorded rather than hidden: a name the picker cannot produce is refused here for the
   * right reason with slightly different reach.
   */
  http.post(
    `${API}/projects/:projectId/reviews/:reviewId/assignees`,
    async ({ params, request }) => {
      await delay(LATENCY);
      const projectId = String(params.projectId);
      const review = db.reviews.find(
        (row) => row.id === params.reviewId && row.projectId === projectId,
      );
      if (!review) return notFound('That review');

      if (!holdsOn(projectId, REVIEW_PERMISSION[review.subjectType])) {
        return problem(
          403,
          'Not allowed',
          "You do not have permission to review this project's work.",
        );
      }

      // Closed before validation — the comment handler's order, and the server's (§74).
      if (review.state !== 'IN_REVIEW') {
        return problem(
          409,
          'Review closed',
          `This review has already ended. Submit the ${SUBJECT_NOUN[review.subjectType]} for `
            + 'review again to reopen it.',
        );
      }

      const body = (await request.json()) as { userId?: string | null; teamId?: string | null };
      const userId = body.userId ?? null;
      const teamId = body.teamId ?? null;

      if ((userId === null) === (teamId === null)) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          userId: ['Ask either one person or one team to review, not both.'],
        });
      }

      if (userId !== null) {
        // The same list the picker offers and the same one `POST /work-items` validates its
        // assignee against — the mock's spelling of the server's "one implementation, two
        // callers", which is what makes a picker built from it unable to produce a 400.
        const person = projectMembers(projectId).find((row) => row.id === userId);
        if (!person) {
          return problem(400, 'Validation failed', 'Check the highlighted fields.', {
            userId: ['That person cannot see this project, so they cannot review it.'],
          });
        }
        if (review.submittedBy?.id === userId) {
          return problem(400, 'Validation failed', 'Check the highlighted fields.', {
            userId: [
              'They submitted this for review, so their own review would not count towards it.',
            ],
          });
        }
        assignReviewer(review, meRef(), { user: person });
        return HttpResponse.json(review, { status: 201 });
      }

      const team = db.teams.find((row) => row.id === teamId);
      if (!team) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          teamId: ['That team no longer exists.'],
        });
      }
      assignReviewer(review, meRef(), { team: { id: team.id, name: team.name } });
      return HttpResponse.json(review, { status: 201 });
    },
  ),

  /** Withdraws a request. A hard delete, and 200 with the whole review — never 204. */
  http.delete(
    `${API}/projects/:projectId/reviews/:reviewId/assignees/:assignmentId`,
    async ({ params }) => {
      await delay(LATENCY);
      const projectId = String(params.projectId);
      const review = db.reviews.find(
        (row) => row.id === params.reviewId && row.projectId === projectId,
      );
      if (!review) return notFound('That review');

      if (!holdsOn(projectId, REVIEW_PERMISSION[review.subjectType])) {
        return problem(
          403,
          'Not allowed',
          "You do not have permission to review this project's work.",
        );
      }

      // The lookup comes first, so an id naming nothing is 404 whatever state the review is in —
      // the server's order on this path, and the mirror image of the assign handler above.
      if (!review.assignees.some((row) => row.id === params.assignmentId)) {
        return notFound('That request');
      }

      if (review.state !== 'IN_REVIEW') {
        return problem(
          409,
          'Review closed',
          `This review has already ended. Submit the ${SUBJECT_NOUN[review.subjectType]} for `
            + 'review again to reopen it.',
        );
      }

      unassignReviewer(review, String(params.assignmentId));
      return HttpResponse.json(review);
    },
  ),

  /**
   * A thread's resolution — and this one costs `*:review`, not `case:comment`.
   *
   * The asymmetry with the handler above is the server's decision and the mock has to carry it, or
   * the Viewer gets a resolve control in mock mode that 403s against a real backend — §31's
   * disable-don't-hide inverted, which is the failure mode a stateful mock exists to prevent.
   */
  http.put(
    `${API}/projects/:projectId/reviews/:reviewId/comments/:commentId/resolution`,
    async ({ params, request }) => {
      await delay(LATENCY);
      const projectId = String(params.projectId);
      const review = db.reviews.find(
        (row) => row.id === params.reviewId && row.projectId === projectId,
      );
      if (!review) return notFound('That review');

      if (!holdsOn(projectId, REVIEW_PERMISSION[review.subjectType])) {
        return problem(
          403,
          'Not allowed',
          "You do not have permission to review this project's work.",
        );
      }

      const commentId = String(params.commentId);
      // A path segment naming nothing is a 404; a reply is a field error. Two different answers to
      // two different mistakes, as the server gives them.
      if (!commentExists(review, commentId)) return notFound('That comment');

      if (review.state !== 'IN_REVIEW') {
        return problem(
          409,
          'Review closed',
          `This review has already ended. Submit the ${SUBJECT_NOUN[review.subjectType]} for `
            + 'review again to reopen it.',
        );
      }

      const body = (await request.json()) as { resolved?: boolean };
      if (typeof body.resolved !== 'boolean') {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          resolved: ['Say whether the thread is resolved: true or false.'],
        });
      }

      const thread = threadRoot(review, commentId);
      if (!thread) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          resolved: ['Resolve the comment that started the thread, not a reply to it.'],
        });
      }

      setResolution(thread, meRef(), body.resolved);
      return HttpResponse.json(review);
    },
  ),
];
