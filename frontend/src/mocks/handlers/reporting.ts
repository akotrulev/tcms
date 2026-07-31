/** The repository status dashboard and the report pipeline's two stubs. */
import { HttpResponse, delay, http } from 'msw';
import { newId, reporting } from '../db';
import { API, LATENCY, requireAuth } from './shared';

export const reportingHandlers = [
  http.get(`${API}/projects/:projectId/dashboard`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);
    return HttpResponse.json({ ...reporting.dashboard, generatedAt: new Date().toISOString() });
  }),

  http.get(`${API}/projects/:projectId/reports`, async () => {
    await delay(LATENCY);
    return HttpResponse.json([]);
  }),

  http.post(`${API}/projects/:projectId/reports/generate`, async () => {
    await delay(600);
    return HttpResponse.json(
      {
        id: newId(),
        status: 'QUEUED',
        format: 'PDF',
        requestedAt: new Date().toISOString(),
        downloadUrl: null,
      },
      { status: 202 },
    );
  }),
];
