/** The review record's two endpoints — plan §6g's advisory tier, and the trail behind it. */
import { HttpResponse, delay, http } from 'msw';
import type { ReviewSubjectType } from '../../api/types';
import {
  db,
  recordVerdict,
  reviewsForSubject,
  saveProjectSettings,
  settingsForProject,
} from '../db';
import { API, LATENCY, holdsOn, meRef, notFound, problem } from './shared';

const SUBJECT_TYPES: ReviewSubjectType[] = ['CASE', 'SUITE', 'PLAN'];

/** Which key a peer verdict costs, by subject type — the server's map, mirrored. */
const REVIEW_PERMISSION: Record<ReviewSubjectType, string> = {
  CASE: 'case:review',
  SUITE: 'suite:review',
  PLAN: 'plan:review',
};

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
];
