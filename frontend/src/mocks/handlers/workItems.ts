/** The kanban board's cards, including the refusal a derived card must answer with. */
import { HttpResponse, delay, http } from 'msw';
import type { WorkItem } from '../../api/types';
import { addWorkItem, db, newId, workItems } from '../db';
import { currentUser, projects } from '../fixtures/core';
import { workItemColumns } from '../../lib/labels';
import { API, LATENCY, problem, projectMembers } from './shared';

/**
 * Which task each `Idempotency-Key` created, so a retry can be recognised.
 *
 * Kept beside the store rather than on the row, because the server's own column
 * (`work_item.created_idempotency_key`, V17) is **never on the wire** — it is write-once and read
 * only by the replay lookup. Putting it on the mock's `WorkItem` would make the mock the one place
 * a client could see it, which is the drift this file's own header warns about in the other
 * direction. Module state, so a reload clears it exactly as the mock store does.
 */
const createdByIntent = new Map<string, string>();

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

  /*
    Create.

    **The `Idempotency-Key` is genuinely honoured here**, and that is the rule this project has
    paid for twice: a mock that accepts a header nothing reads is how the SPA sent one for sixteen
    slices while every retry created another row (§29 for cases, §53 for this endpoint). The
    property is provable in mock mode or it is not a property.
  */
  http.post(`${API}/work-items`, async ({ request }) => {
    await delay(LATENCY);
    const body = (await request.json()) as Record<string, unknown>;

    const idempotencyKey = request.headers.get('Idempotency-Key');
    if (idempotencyKey) {
      const already = db.workItems.find((w) => w.id === createdByIntent.get(idempotencyKey));
      // 200, not 201, and answered BEFORE validation — the client is asking "did my intent
      // land", so a retry whose payload has since drifted must still get the card it made
      // rather than a 400 sending it to fix fields on something that already exists.
      if (already) return HttpResponse.json(already);
    }

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
      //
      // **And the id the caller sent is the id that lands.** Until §54 this resolved to
      // `userRef(0)` for ANY assignee, so the picker built in that slice would have shown the
      // colleague you chose and rendered the first person in the directory on the card — a
      // wrong answer that looks like a real one, in mock mode only.
      assignee: body.assigneeId
        ? (projectMembers(project.id).find((m) => m.id === body.assigneeId) ?? undefined)
        : undefined,
      projectId: project.id,
      projectKey: project.key,
      linkedKey: null,
      // Every column but the one it is in, from the contract's enum rather than a literal
      // list — the fifth (BLOCKED) arrived in §54 and a hand-written array here is exactly
      // what would have kept the mock one column behind the server.
      allowedTransitions: workItemColumns.filter((column) => column !== 'TODO'),
    };
    if (idempotencyKey) createdByIntent.set(idempotencyKey, item.id);
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
