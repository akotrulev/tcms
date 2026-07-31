/** Instance-level reference reads — users, teams, roles and their assignments. */
import { HttpResponse, delay, http } from 'msw';
import { users } from '../db';
import { roleAssignments, roles, teams } from '../fixtures/core';
import { API, LATENCY } from './shared';

export const adminHandlers = [
  http.get(`${API}/users`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(users);
  }),

  http.get(`${API}/teams`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(teams);
  }),

  http.get(`${API}/roles`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(roles);
  }),

  http.get(`${API}/role-assignments`, async ({ request }) => {
    await delay(LATENCY);
    const projectId = new URL(request.url).searchParams.get('projectId');
    const rows = projectId ? roleAssignments.filter((a) => a.projectId === projectId) : roleAssignments;
    return HttpResponse.json(rows);
  }),
];
