/** The kanban board's cards, including the refusal a derived card must answer with. */
import { HttpResponse, delay, http } from 'msw';
import type { WorkItem } from '../../api/types';
import { addWorkItem, db, newId, personalBoard, teamBoard, workItems } from '../db';
import { currentUser, projects } from '../fixtures/core';
import { workItemColumns, workItemStatusLabels } from '../../lib/labels';
import { API, LATENCY, notFound, problem, projectMembers } from './shared';

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
  /*
    One project's board — or, with no `projectId`, §6l's cross-project personal board (§83).

    **Both of the parameter's absent cases are the server's, not the client's** (§52):

      - **No `projectId` and no `mine`** is a **400**, not a defaulted project and not a silently
        narrowed personal board. §6l describes three boards and the cross-project one is the
        *personal* one; there is no board that is every project's work unfiltered, and answering
        that request anyway would hand back a list the client cannot tell apart from a company that
        only has the caller's work in it. The title and sentence are `WorkItemController`'s verbatim,
        so the refusal reads identically in both modes.
      - **A `projectId` nobody can see** is a **404**. This handler used to fall back to
        `projects[0]` for any unknown id, which is the mock proving reachability the server refuses:
        a stale bookmark or a mistyped uuid rendered *another project's* board under the id in the
        URL, and every key on it linked back into a project that does not contain them.
  */
  http.get(`${API}/work-items`, async ({ request }) => {
    await delay(LATENCY);
    const url = new URL(request.url);
    const projectId = url.searchParams.get('projectId');
    const teamId = url.searchParams.get('teamId');
    const mine = url.searchParams.get('mine') === 'true';

    /*
      §6l's THIRD board (§84), and the two refusals around it — both mirrored from
      `WorkItemController` rather than from what the SPA sends (§52). `useTeamBoard` cannot produce
      either of them, which is exactly why they belong here: the mock is written from the server's
      rules, and a rule only the current client happens to respect is a rule the next client breaks.

        - **`teamId` with `mine=true` is a 400.** They select two different boards, and their
          intersection is a fourth nobody asked for — my own work minus anything the team does not
          hold, which is my own work again if I am on the team and always empty if I am not. Note
          that for a caller who *is* on the team the refusal is unobservable in the body, because
          their cards are a subset of the team's; it exists only as a status.
        - **`teamId` with `projectId` is LEGAL** — §6l's "swimlanes by member", a team's work inside
          one project. The two narrow along different axes (who, and where) rather than naming two
          boards. No screen sends it yet; the mock answers it anyway, because the day one does is
          not the day to discover the mock guessed.

      **The project gate runs BEFORE the team's empty-set shortcut**, which is the trap §84 named:
      answering an empty team first — there is nothing to assemble either way — turns "empty team +
      unreachable project" into a **200 that confirms the project exists**.
    */
    if (teamId) {
      if (mine) {
        return problem(
          400,
          'Choose one board',
          'A team board and your own work are two different boards. Ask for one of them.',
        );
      }
      if (projectId && !db.projects.some((p) => p.id === projectId)) return notFound('That project');

      const board = teamBoard(teamId, projectId ?? undefined);
      // Unknown team, another company's team — one answer, or this operation is a company-wide
      // team-existence oracle answerable by anybody with a session and a guessed UUID (§6t).
      // Distinct from `[]`, which is a reachable board with nothing on it.
      if (!board) return notFound('That team');
      return HttpResponse.json(board);
    }

    if (!projectId) {
      if (!mine) {
        return problem(
          400,
          'Choose a board',
          "Ask for a project's board with projectId, or your own work across every project with mine=true.",
        );
      }
      return HttpResponse.json(personalBoard(currentUser.id));
    }

    const project = db.projects.find((p) => p.id === projectId);
    if (!project) return notFound('That project');

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
    /*
      **The transition is enforced here, because the contract says the server enforces it.**

      `PATCH /work-items/{id}` declares a 409 for two situations — a derived id (above) and *"the
      requested column is not in the card's `allowedTransitions`"* — and this handler answered 200
      to the second from Phase 1 until §106. It did not matter while the only way to move a card
      was a button the board had already disabled: the illegal request was unreachable from the
      UI, so the mock's leniency was never exercised. A drag makes it reachable the moment the
      client-side guard is wrong, and a mock that accepts what the server refuses is a mock that
      certifies the bug (§52 — handlers are written from the server's rules, not from what one
      client happens to send).
    */
    if (body.status && body.status !== item.status && !item.allowedTransitions.includes(body.status)) {
      return problem(
        409,
        "That card can't be moved there",
        `${workItemStatusLabels[item.status]} → ${workItemStatusLabels[body.status]} is not a move this card allows.`,
      );
    }

    if (body.status) item.status = body.status;
    return HttpResponse.json(item);
  }),
];
