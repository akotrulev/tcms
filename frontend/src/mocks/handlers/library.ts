/** The reusable library — conditions and shared steps. */
import { HttpResponse, delay, http } from 'msw';
import { db } from '../db';
import { API, LATENCY } from './shared';

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
    await delay(LATENCY);
    const q = new URL(request.url).searchParams.get('q')?.toLowerCase();
    const rows = q ? db.sharedSteps.filter((s) => s.title.toLowerCase().includes(q)) : db.sharedSteps;
    return HttpResponse.json([...rows].sort((a, b) => b.usageCount - a.usageCount));
  }),
];
