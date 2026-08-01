/** The kanban board's cards, including the refusal a derived card must answer with. */
import { HttpResponse, delay, http } from 'msw';
import type { WorkItem } from '../../api/types';
import { addWorkItem, db, newId, workItems } from '../db';
import { currentUser, projects } from '../fixtures/core';
import { API, LATENCY, problem, userRef } from './shared';

export const workItemHandlers = [
  http.get(`${API}/work-items`, async ({ request }) => {
    await delay(LATENCY);
    const url = new URL(request.url);
    const projectId = url.searchParams.get('projectId') ?? projects[0].id;
    const project = projects.find((p) => p.id === projectId) ?? projects[0];
    const mine = url.searchParams.get('mine') === 'true';

    let items = workItems(project.id, project.key);
    if (mine) items = items.filter((w) => w.assignee?.id === currentUser.id);
    return HttpResponse.json(items);
  }),

  http.post(`${API}/work-items`, async ({ request }) => {
    await delay(LATENCY);
    const body = (await request.json()) as Record<string, unknown>;
    const title = String(body.title ?? '').trim();
    if (!title) {
      return HttpResponse.json(
        { type: 'about:blank', title: 'Validation failed', status: 400, detail: 'Check the highlighted fields.', errors: { title: ['Enter a title.'] } },
        { status: 400 },
      );
    }
    const project = projects.find((p) => p.id === body.projectId) ?? projects[0];
    const item: WorkItem = {
      id: newId(),
      derived: false,
      sourceType: null,
      sourceId: null,
      kind: 'TASK',
      title,
      description: (body.description as string) ?? null,
      // Not `body.status`: the contract has no status on WorkItemInput, because a create
      // that could choose its column is a move nobody recorded (see the schema's note).
      status: 'TODO',
      priority: (body.priority as WorkItem['priority']) ?? 'MEDIUM',
      dueDate: (body.dueDate as string) ?? null,
      overdue: false,
      // Unassigned unless the caller said otherwise — the mock used to assign every new
      // task to the current user, which made the unclaimed pool §6l calls for unreachable
      // and, worse, made `mine=true` look like it worked when nothing had been assigned.
      assignee: body.assigneeId ? userRef(0) : undefined,
      projectId: project.id,
      projectKey: project.key,
      linkedKey: null,
      allowedTransitions: ['TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE'],
    };
    return HttpResponse.json(addWorkItem(item), { status: 201 });
  }),

  http.patch(`${API}/work-items/:itemId`, async ({ params, request }) => {
    await delay(120);
    const body = (await request.json()) as { status?: WorkItem['status'] };
    const item = db.workItems.find((w) => w.id === params.itemId);

    // Derived cards are not stored, so their status can't be written here at all — it is
    // computed from the source. Moving one means acting on the source (plan §6l).
    if (!item) {
      return problem(
        409,
        "That card can't be moved here",
        'This card mirrors an execution or review. Update the execution or review itself and the card follows.',
      );
    }
    if (body.status) item.status = body.status;
    return HttpResponse.json(item);
  }),
];
