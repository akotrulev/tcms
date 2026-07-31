/** The documentation space and the artifact register. */
import { HttpResponse, delay, http } from 'msw';
import type { Artifact } from '../../api/types';
import { db } from '../db';
import { API, LATENCY, notFound } from './shared';

export const documentationHandlers = [
  http.get(`${API}/projects/:projectId/doc-pages`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(db.docPages);
  }),

  http.get(`${API}/projects/:projectId/doc-pages/:pageId`, async ({ params }) => {
    await delay(LATENCY);
    const page = db.docPageDetails[String(params.pageId)];
    if (page) return HttpResponse.json(page);

    // Pages without authored blocks are a normal state in a young docs space.
    const summary = db.docPages.find((p) => p.id === params.pageId);
    if (!summary) return notFound('That page');
    return HttpResponse.json({ ...summary, currentVersionNo: 1, lockVersion: 1, blocks: [] });
  }),

  http.get(`${API}/projects/:projectId/artifacts`, async ({ request }) => {
    await delay(LATENCY);
    const url = new URL(request.url);
    const scope = url.searchParams.get('scope');
    const planId = url.searchParams.get('planId');

    if (scope === 'PLAN' || planId) {
      // Plan scope also returns project-scope artifacts, flagged inherited — the
      // inheritance has to be visible or people duplicate what they already have.
      const planScoped = db.artifacts.filter((a) => a.scope === 'PLAN');
      const inherited: Artifact[] = db.artifacts
        .filter((a) => a.scope === 'PROJECT')
        .map((a) => ({ ...a, inherited: true }));
      return HttpResponse.json([...planScoped, ...inherited]);
    }
    const rows = scope ? db.artifacts.filter((a) => a.scope === scope) : db.artifacts;
    return HttpResponse.json(rows);
  }),
];
