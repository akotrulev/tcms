/** The reusable library — conditions and shared steps. */
import { HttpResponse, delay, http } from 'msw';
import type { SharedStep } from '../../api/types';
import { caseDetail, db, newId } from '../db';
import { API, LATENCY, requireAuth } from './shared';

/**
 * Distinct live cases referencing each entry — the same rule the server applies, and the reason
 * the fixture's own `usageCount` is ignored.
 *
 * Distinct *cases*, not step rows: a case referencing the same entry twice counts once, matching
 * `SharedStepUsage` on the backend. Getting this wrong in only one of the two places is how a
 * ranking looks right in development and reorders itself the first time it meets a real server.
 *
 * Case details are materialized lazily in this mock, so the steps have to be pulled through
 * `caseDetail` rather than read off the summary rows — a summary carries no steps at all, and
 * counting over the summaries would answer 0 for everything, forever.
 */
function usageOf(sharedStepId: string): number {
  let cases = 0;
  for (const summary of db.cases) {
    const detail = caseDetail(summary.id);
    if (detail?.steps.some((step) => step.sharedStepId === sharedStepId)) cases += 1;
  }
  return cases;
}

function withUsage(step: SharedStep): SharedStep {
  return { ...step, usageCount: usageOf(step.id) };
}

export const libraryHandlers = [
  http.get(`${API}/projects/:projectId/conditions`, async ({ request }) => {
    await delay(LATENCY);
    const url = new URL(request.url);
    const q = url.searchParams.get('q')?.toLowerCase();
    const type = url.searchParams.get('type');
    let rows = db.conditions;
    // A BOTH entry matches type=PRE and type=POST, because BOTH means "usable in either
    // position" — not a third role. Exact matching would make marking a condition reusable
    // everywhere the one way to hide it everywhere. The real API does the same; see
    // ConditionType.matching on the backend, which is where the decision is written down.
    if (type) rows = rows.filter((c) => c.type === type || (type !== 'BOTH' && c.type === 'BOTH'));
    if (q) rows = rows.filter((c) => c.title.toLowerCase().includes(q));
    // Most-reused first — the whole point of a reusable-condition search is to surface
    // the one 400 cases already share, not the one someone wrote yesterday.
    return HttpResponse.json([...rows].sort((a, b) => b.usageCount - a.usageCount || a.title.localeCompare(b.title)));
  }),

  http.get(`${API}/projects/:projectId/shared-steps`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);
    const q = new URL(request.url).searchParams.get('q')?.toLowerCase();
    const rows = q ? db.sharedSteps.filter((s) => s.title.toLowerCase().includes(q)) : db.sharedSteps;
    // Most-used first, then title, then id — the server's comparator, ties included. Sorting by
    // usage alone left the equal-usage rows in insertion order, so a newly created entry jumped
    // around between keystrokes.
    return HttpResponse.json(
      rows
        .map(withUsage)
        .sort(
          (a, b) =>
            b.usageCount - a.usageCount || a.title.localeCompare(b.title) || a.id.localeCompare(b.id),
        ),
    );
  }),

  http.post(`${API}/projects/:projectId/shared-steps`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    const input = (await request.json()) as { title?: string; body?: string[] };
    const title = input.title?.trim();
    // The same two refusals the server makes, in the same shape — blank lines dropped, an
    // all-blank body refused. A mock more permissive than the server is how a form ships with
    // validation nobody ever saw fail.
    const body = (input.body ?? []).map((line) => line.trim()).filter(Boolean);

    const errors: Record<string, string[]> = {};
    if (!title) errors.title = ['Enter a title.'];
    else if (title.length > 150) errors.title = ['Keep the title under 150 characters.'];
    if (body.length === 0) errors.body = ['Add at least one action.'];

    if (Object.keys(errors).length > 0) {
      return HttpResponse.json(
        { type: 'about:blank', title: 'Validation failed', status: 400, errors },
        { status: 400 },
      );
    }

    // usageCount 0 by construction — nothing can reference an entry that did not exist a moment
    // ago. Stored so the list read that follows finds it; the GET recomputes regardless.
    const created: SharedStep = { id: newId(), title: title!, body, usageCount: 0 };
    db.sharedSteps.push(created);
    return HttpResponse.json(created, { status: 201 });
  }),
];
