/** The project list, one project, and its two vocabularies. */
import { HttpResponse, delay, http } from 'msw';
import { db } from '../db';
import { dimensions, projects } from '../fixtures/core';
import { API, LATENCY, notFound, requireAuth } from './shared';

export const projectHandlers = [
  http.get(`${API}/projects`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);
    return HttpResponse.json(projects);
  }),

  http.get(`${API}/projects/:projectId`, ({ params }) => {
    const project = projects.find((p) => p.id === params.projectId);
    return project ? HttpResponse.json(project) : notFound('That project');
  }),

  http.get(`${API}/projects/:projectId/classification`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(dimensions);
  }),

  http.get(`${API}/projects/:projectId/statuses`, () => HttpResponse.json(db.statuses)),
];
