/** The suite list, its three writes, and its history. */
import { HttpResponse, delay, http } from 'msw';
import type { LifecycleStatus, Suite } from '../../api/types';
import { lifecycleLabels } from '../../lib/labels';
import {
  allocateSuiteKey,
  db,
  newId,
  reporting,
  reviewMoved,
  suiteCaseCount,
  suiteList,
  suiteOwnCaseCount,
} from '../db';
import { decide, needsApproval, standingOf } from '../lifecycleLadder';
import { API, LATENCY, MAX_SUITE_NAME, meRef, notFound, permissionsOn, problem } from './shared';

interface SuiteBody {
  name?: string;
  description?: string | null;
  tagRules?: string[];
  caseIds?: string[];
}

/** Trimmed, blanks dropped, duplicates collapsed — `SuiteEdit.normalizeTagRules`, in the mock. */
function normalizeTagRules(rules: string[] | undefined): string[] | undefined {
  if (rules === undefined) return undefined;
  return [...new Set(rules.map((tag) => tag.trim()).filter((tag) => tag !== ''))];
}

export const suiteHandlers = [
  http.get(`${API}/projects/:projectId/suites`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(suiteList());
  }),

  /*
    Aligned with the real endpoint in one pass, because the divergences were the kind that make
    mock mode prove a property live mode does not have — the same class as the `If-Match`-in-the-
    body and honoured-Idempotency-Key findings the backend slices kept turning up. Three of them
    were here: the path was slugged from the NAME (so a rename would have had to rewrite the
    subtree), a parent from outside the project was accepted, and there was no DELETE at all.
  */
  http.post(`${API}/projects/:projectId/suites`, async ({ request }) => {
    await delay(LATENCY);
    const body = (await request.json()) as SuiteBody;

    const name = body.name?.trim() ?? '';
    const errors: Record<string, string[]> = {};
    if (!name) errors.name = ['Enter a name.'];
    else if (name.length > MAX_SUITE_NAME) {
      errors.name = [`Keep the name under ${MAX_SUITE_NAME} characters.`];
    }

    // §98: no parent to validate. This used to reject a `parentId` from outside the project as a
    // FIELD error rather than a 404 — the project resolved, and the message belonged on the
    // picker; unchecked, the folder was created under another project's suite and appeared in no
    // tree at all. There is no tree and no picker now.

    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    const { key } = allocateSuiteKey();
    const suite: Suite = {
      id: newId(),
      key,
      name,
      description: body.description?.trim() || null,
      caseCount: 0,
      // Absent means "none yet" on a create — there is nothing to leave alone. On the PATCH below
      // absent means "leave them alone", which is not the same instruction and is the one a rename
      // dialog sends by not mentioning either field.
      tagRules: normalizeTagRules(body.tagRules) ?? [],
      caseIds: body.caseIds ?? [],
      lifecycleStatus: 'DRAFT',
      conditions: [],
      lockVersion: 0,
    };
    db.suites.push(suite);
    return HttpResponse.json(suite, { status: 201 });
  }),

  http.get(`${API}/projects/:projectId/suites/:suiteId`, ({ params }) => {
    const suite = suiteList().find((s) => s.id === params.suiteId);
    return suite ? HttpResponse.json(suite) : notFound('That suite');
  }),

  http.patch(`${API}/projects/:projectId/suites/:suiteId`, async ({ params, request }) => {
    await delay(LATENCY);
    const suite = db.suites.find((s) => s.id === params.suiteId);
    if (!suite) return notFound('That suite');

    const body = (await request.json()) as SuiteBody;

    // The precondition is the HEADER, never a body field. It was read from the body here for as
    // long as no server read it at all, which is exactly how a divergence survives: both sides
    // agreed, and both were wrong about where the contract puts it.
    const ifMatch = request.headers.get('If-Match');
    if (ifMatch !== null && ifMatch.trim() !== '') {
      const expected = Number(ifMatch.replace(/^"|"$/g, ''));
      if (Number.isNaN(expected)) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          'If-Match': ["Send the suite's lockVersion as the If-Match header, or omit the header."],
        });
      }
      if (expected !== suite.lockVersion) {
        return problem(
          409,
          'Someone else saved first',
          'Reload the suite to see the newer version, then reapply your change.',
        );
      }
    }

    const name = body.name?.trim() ?? '';
    const errors: Record<string, string[]> = {};
    if (!name) errors.name = ['Enter a name.'];
    else if (name.length > MAX_SUITE_NAME) {
      errors.name = [`Keep the name under ${MAX_SUITE_NAME} characters.`];
    }
    // §98: the move refusal is gone with the parent. It used to reject a differing `parentId`
    // rather than ignore it, because dropping it silently lets a client show the folder in its
    // new place, reload, and find it back — with the server having reported success. There is no
    // longer anywhere to move a suite to, so the field is not on the contract and not read here.
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    suite.name = name;
    if (body.description !== undefined) suite.description = body.description?.trim() || null;
    // Absent leaves them alone; [] clears them. A rename that emptied a collection somebody
    // assembled would be the mock teaching a behaviour the server refuses to have.
    if (body.tagRules !== undefined) suite.tagRules = normalizeTagRules(body.tagRules) ?? [];
    if (body.caseIds !== undefined) suite.caseIds = body.caseIds;
    // `lifecycleStatus` is NOT applied any more: it is not on SuiteInput, and a status an
    // ordinary edit can set is an approval its author granted themselves (§6g).
    suite.lockVersion = (suite.lockVersion ?? 0) + 1;
    return HttpResponse.json({ ...suite, caseCount: suiteOwnCaseCount(suite.id) });
  }),

  /*
    The ladder. Ordered EXACTLY as the server orders it — resolve, then permission, then the
    transition — because a mock that checks them the other way round teaches a 409 where live gives
    a 403, and the screen is built against whichever one it saw first (§74's mirroring rule).

    The permission half is not optional here. `suite:approve` is withheld from Test Engineer, and
    the tree's menu enables its items from `permissionsOn`; a mock that accepted every move would
    make the one screen this slice adds look correct in the only mode it can be driven in without a
    backend.
  */
  http.put(`${API}/projects/:projectId/suites/:suiteId/status`, async ({ params, request }) => {
    await delay(LATENCY);
    const suite = db.suites.find((s) => s.id === params.suiteId);
    if (!suite) return notFound('That suite');

    const body = (await request.json()) as { status?: string };
    const target = body.status as LifecycleStatus | undefined;
    if (!target || !(target in lifecycleLabels)) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        status: ['Choose a status: DRAFT, IN_REVIEW, APPROVED or DEPRECATED.'],
      });
    }

    const held = permissionsOn(String(params.projectId)).map((entry) => entry.key);
    const required = needsApproval(suite.lifecycleStatus, target) ? 'suite:approve' : 'suite:manage';
    if (!held.includes(required)) {
      return problem(
        403,
        'Forbidden',
        'You do not have permission to make this change to suites in this project.',
      );
    }

    const decision = decide(suite.lifecycleStatus, target, 'suite');
    if (decision.kind === 'refused') {
      return problem(409, 'Status change refused', decision.message);
    }
    // `unchanged` is a 200 with the suite and nothing written — no version, no bump, no audit —
    // so a repeated click cannot bury the change that actually happened.
    if (decision.kind === 'move') {
      suite.lifecycleStatus = decision.target;
      // A suite's review pins its COMPOSITION version, which is what approving a folder is a
      // statement about. The mock has no version number on a suite, so it pins 1 — the value a
      // fresh suite carries — rather than inventing a counter the server would disagree with.
      reviewMoved(
        String(params.projectId),
        'SUITE',
        suite.id,
        1,
        standingOf(decision.target),
        meRef(),
      );
    }
    return HttpResponse.json({ ...suite, caseCount: suiteOwnCaseCount(suite.id) });
  }),

  http.delete(`${API}/projects/:projectId/suites/:suiteId`, async ({ params }) => {
    await delay(LATENCY);
    const index = db.suites.findIndex((s) => s.id === params.suiteId);
    if (index < 0) return notFound('That suite');
    const suite = db.suites[index];

    // Empty means empty of FILED cases, and only those (§98).
    //
    // The child-suite half of this check went with the tree. The other half narrowed at the same
    // time and deliberately: a case merely *added* to this suite, or matched by one of its tag
    // rules, lives somewhere else and only appears here, so deleting the collection loses a claim
    // and never a case. A cascade would delete other people's cases through a collection action
    // with no preview, which is why the server refuses instead of offering a "delete everything"
    // flag — and that argument only ever applied to the cases this suite actually holds.
    const cases = suiteCaseCount(suite.id);
    if (cases > 0) {
      return problem(
        409,
        'Suite is not empty',
        `This suite still holds ${cases} ${cases === 1 ? 'case' : 'cases'}. Move or delete them first.`,
      );
    }

    db.suites.splice(index, 1);
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(`${API}/projects/:projectId/suites/:suiteId/history`, async () => {
    await delay(LATENCY);
    // `entries`, which is what the contract has always called it. This handler said `runs` and so
    // did the hook's hand-written type, so the two agreed with each other and with nothing else —
    // mock mode rendered perfectly and live mode would have rendered an empty grid (§52).
    return HttpResponse.json({
      entries: reporting.suiteHistoryEntries,
      versionMarkers: reporting.suiteVersionMarkers,
    });
  }),
];
