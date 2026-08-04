/** The board's cards — three derived sources plus the ones someone actually typed. */
import type { UserRef, WorkItem } from '../../api/types';
import { runExecutions } from './executions';
import { db, users } from './store';

/**
 * Derived work items are computed on every read, never stored — that is what makes a
 * card unable to diverge from its execution (plan §6l).
 */
export function workItems(projectId: string, projectKey: string): WorkItem[] {
  const meRef = {
    id: users[0].id,
    displayName: users[0].displayName,
    email: users[0].email,
    avatarUrl: null,
  };

  /*
    Two of the three generators below read fixtures that carry **no project of their own**, and §83
    is where that stopped being harmless.

    `db.runs`, `db.cases` and `db.plans` are seeded as one project's repository — the contract puts
    the project in the *path* for every one of those endpoints, so the wire shapes have no
    `projectId` field to filter on and none of the fixtures has one. Until §83 the generators
    therefore drew every run's executions and every flagged case onto **whichever project's board
    was asked for**, stamped with that project's id and key. On the MOB project's board that was
    already wrong and silently so: a card keyed `TC-3` linking to `/projects/{MOB}/cases/{an OLB
    case}`, which 404s. On the cross-project board it becomes visible instead of merely wrong —
    the same execution would be drawn once per project, with the **same derived id** each time, so
    React would see duplicate keys and the reader would see the same card twice.

    So the repository belongs to the project that seeds it, stated once here rather than inferred
    per generator. `db.reviews` needs no such treatment: review rows carry a real `projectId`, and
    `db.workItems` (typed tasks) carry one too — which is why those two already filtered and these
    two could not.
  */
  const ownsTheRepository = projectId === db.projects[0]?.id;

  const fromExecutions: WorkItem[] = (ownsTheRepository ? db.runs : [])
    .filter((r) => r.status === 'IN_PROGRESS')
    .flatMap((run) =>
      runExecutions(run.id)
        .filter((e) => !e.status)
        .slice(0, 4)
        .map((e) => ({
          id: `derived-exec-${e.id}`,
          derived: true,
          sourceType: 'EXECUTION',
          sourceId: e.id,
          kind: 'EXECUTION' as const,
          title: e.caseTitle,
          description: `Execute in ${run.name}`,
          status: 'IN_PROGRESS',
          priority: 'HIGH' as const,
          dueDate: null,
          overdue: false,
          assignee: meRef,
          projectId,
          projectKey,
          linkedKey: e.caseKey,
          // EMPTY, like the server's. This read `['IN_PROGRESS', 'DONE']` until §54, so mock
          // mode offered a move on a derived card that live mode disables and refuses with a
          // 409 — the mock proving a capability the product does not have, which is the exact
          // divergence class §52 and §53 were each spent on. A derived card follows its source;
          // §6l's eventual "move to Done opens the execution" is a navigation, not a transition.
          allowedTransitions: [],
        })),
    );

  const fromReviews: WorkItem[] = reviewCards(projectId, projectKey);

  const fromStale: WorkItem[] = (ownsTheRepository ? db.cases : [])
    .filter((c) => c.healthSignals.includes('OUTDATED_SPEC_CHANGED') || c.healthSignals.includes('STALE_AUTOMATION'))
    .map((c) => ({
      id: `derived-maint-${c.id}`,
      derived: true,
      sourceType: 'CASE',
      sourceId: c.id,
      kind: 'CASE_MAINTENANCE' as const,
      title: `Update ${c.key} — ${c.healthSignals.includes('STALE_AUTOMATION') ? 'automation stopped reporting' : 'linked spec changed'}`,
      description: null,
      status: 'TODO',
      priority: 'MEDIUM' as const,
      dueDate: null,
      overdue: false,
      // `?? undefined` rather than `?? null`: WorkItem.assignee is optional in the
      // contract, not nullable, so an unowned case's derived card carries no assignee
      // at all — which is what BoardPage already renders (`item.assignee && …`).
      assignee: c.owner ?? undefined,
      projectId,
      projectKey,
      linkedKey: c.key,
      allowedTransitions: [],
    }));

  return [...db.workItems.filter((w) => w.projectId === projectId), ...fromExecutions, ...fromReviews, ...fromStale];
}

/**
 * The reviewer's inbox — one card per person who has been asked and has not answered (§81).
 *
 * **This replaced a generator that invented the feature, and the divergence is worth keeping on the
 * record.** Until §81 the mock built a review card for every case whose `lifecycleStatus` was
 * `IN_REVIEW`, assigned to the case's **owner**, in the `IN_REVIEW` column. Every one of those four
 * decisions was wrong against a server that produced *no review cards at all*:
 *
 *  - it drew a card for a review **nobody had been asked to do**, which is the one fact §80's table
 *    was created to store and the one thing a queue is about;
 *  - it put the card on the **owner's** board — the person whose work is under review, and precisely
 *    the person the server refuses to accept as an assignee (§80 refuses the submitter);
 *  - it used a different column (`IN_REVIEW`, not `TODO`), so the board's own shape differed;
 *  - it never drew a suite or a plan, because it read a case column.
 *
 * Mock mode has therefore shown a reviewer inbox since Phase 1 that no deployment could produce, to
 * the wrong person — §52's "the mock agrees with itself" in its most expensive form, because here
 * the mock was not agreeing with a *server*, it was agreeing with an idea. Nothing could have caught
 * it: the contract has carried `kind: REVIEW` since §53 on purpose, so both sides typechecked.
 *
 * What is here now is the server's query, spelled in TypeScript: open reviews only, teams expanded
 * at read time, anybody who has spoken removed, the subject resolved to a key and a title, and
 * `sourceType`/`sourceId` naming the **artifact** rather than the review — a review has no URL of
 * its own, because §6g's panel renders on the artifact's page.
 */
function reviewCards(projectId: string, projectKey: string): WorkItem[] {
  const cards: WorkItem[] = [];

  for (const review of db.reviews) {
    if (review.projectId !== projectId || review.state !== 'IN_REVIEW') continue;

    const subject = subjectOf(review.subjectType, review.subjectId);
    // A soft-deleted case, or an artifact this fixture set does not carry: "go and review this" is
    // not something to say about something that is not there.
    if (!subject) continue;

    // Expanded HERE rather than stored, which is the mirror of §80's refusal to expand at write
    // time: membership moves, so the only correct moment to ask who is on a team is the read.
    const asked = new Map<string, UserRef>();
    for (const row of review.assignees) {
      if (row.user) asked.set(row.user.id, row.user);
      for (const member of db.teams.find((t) => t.id === row.team?.id)?.members ?? []) {
        asked.set(member.id, member);
      }
    }

    for (const [userId, person] of asked) {
      // Spoken, not agreed — somebody who requested changes has done what was asked of them, and
      // the ball is with the author.
      if (review.participations.some((verdict) => verdict.user?.id === userId)) continue;

      const asker = review.assignees.find(
        (row) => row.user?.id === userId || db.teams.some((t) => t.id === row.team?.id && t.members.some((m) => m.id === userId)),
      )?.assignedBy;

      cards.push({
        id: `review:${review.id}:${userId}`,
        derived: true,
        sourceType: review.subjectType,
        sourceId: review.subjectId,
        kind: 'REVIEW',
        title: subject.title,
        description: asker ? `Review requested by ${asker.displayName}` : 'Review requested',
        status: 'TODO',
        priority: 'MEDIUM',
        dueDate: null,
        overdue: false,
        assignee: person,
        projectId,
        projectKey,
        linkedKey: subject.key,
        allowedTransitions: [],
      });
    }
  }

  return cards;
}

/** The opaque triple, expanded — the read three modules have to cooperate on server-side. */
function subjectOf(
  subjectType: 'CASE' | 'SUITE' | 'PLAN',
  subjectId: string,
): { key: string; title: string } | undefined {
  if (subjectType === 'CASE') {
    const found = db.cases.find((c) => c.id === subjectId);
    return found && { key: found.key, title: found.title };
  }
  if (subjectType === 'SUITE') {
    const found = db.suites.find((s) => s.id === subjectId);
    return found && { key: found.key, title: found.name };
  }
  const found = db.plans.find((p) => p.id === subjectId);
  return found && { key: found.key, title: found.name };
}

/**
 * §6l's cross-project **personal board** (§83) — `BoardService.personalBoard`, spelled in TypeScript.
 *
 * Three properties are mirrored from the server rather than from what the SPA happens to send,
 * which is §52's rule and the one §81 paid for in this very file:
 *
 *  1. **It spans the projects the caller can SEE**, not the project the client asked about — there
 *     is no project in the request at all. `db.projects` is that set: it is the exact list
 *     `GET /projects` answers, read from the same store, so the mock cannot show a board with work
 *     from a project whose name the switcher will not offer. *What the fixture cannot express:* it
 *     carries no project the caller is barred from, so the exclusion is structural here rather than
 *     demonstrated — the same honest limit `projectMembers` records about per-project grants.
 *  2. **The personal filter runs AFTER assembly**, not inside each generator — the server's
 *     `present()` and its reason: four predicates kept in step is three chances to leak somebody
 *     else's work onto a personal board, silently, with nothing failing.
 *  3. **A person with no projects gets `[]`, never an error.** The caller named nothing, so nothing
 *     can fail to resolve; this board has no 404 to give.
 *
 * Not merged into {@link workItems}: that function answers *one project's* board and this one has
 * no project, and a single function taking `projectId | null` would put the branch inside the
 * generator loop where the personal filter must not be.
 */
export function personalBoard(userId: string): WorkItem[] {
  return assemble().filter((item) => item.assignee?.id === userId);
}

/**
 * The cards, before anybody's filter — **the one place the visible project set is named** (§84).
 *
 * Extracted when the team board arrived, because it is the line that decides what a board may
 * contain and there are now two callers of it. `db.projects` is the exact list `GET /projects`
 * answers, read from the same store, so neither board can show work from a project the switcher
 * will not offer; two copies of that expression is the one that drifts (§58's E1), and it drifts in
 * the direction where a board quietly grows a project.
 *
 * `projectId` narrows *where* and is the third selector's legal conjunction (`teamId` + `projectId`,
 * §6l's swimlanes). It is filtered from the same list rather than fetched separately, so a project
 * outside the visible set cannot be reached by naming it.
 *
 * **The caller's own project visibility is what the mock cannot model**, and this is the honest
 * limit §83 recorded for the personal board, restated because the team board makes it matter more:
 * the fixture carries no project the signed-in user is barred from, so `db.projects` *is* their
 * visible set, and the server's decision that a team board is bounded by the READER rather than by
 * the team is structural here rather than demonstrated. What the fixture can and does prove is the
 * other bound — the team's membership — and that the assembly reads no project outside this list.
 */
function assemble(projectId?: string): WorkItem[] {
  const scope = projectId ? db.projects.filter((project) => project.id === projectId) : db.projects;
  return scope.flatMap((project) => workItems(project.id, project.key));
}

/**
 * §6l's **team board** (§84) — `BoardService.teamBoard`, spelled in TypeScript.
 *
 * Four properties are mirrored from the server rather than from what `useTeamBoard` happens to
 * send, which is §52's rule and the one this module has now been caught breaking twice (§81's
 * invented review generator, §83's project stamp):
 *
 *  1. **The team contributes nothing but a set of assignees.** The projects come from
 *     {@link assemble} — the caller's visible set — exactly as the personal board's do. A board
 *     built the other way round ("everything assigned to a member of team T") is one query, reads
 *     correctly, and renders the case keys, titles and project names of every project any member
 *     was granted, to a reader granted none of them.
 *  2. **Membership is expanded at READ time, and direct members only.** §80 refused to expand a
 *     team when a review request is *written* because a stored expansion is a snapshot of who was
 *     on the team the day somebody clicked; the same fact makes expanding here correct. `team` has
 *     a parent edge (§6k) and this deliberately does not walk it — a parent team inheriting every
 *     child team's work is a different board, and one §6l does not describe.
 *  3. **The filter runs AFTER assembly**, not inside each generator, for the reason `present`'s own
 *     header gives: four predicates to keep in step is three chances to show somebody else's work,
 *     silently, with nothing failing.
 *  4. **An unknown team is `undefined` — a 404, never an empty board.** The handler turns it into
 *     the status; the distinction lives here so it cannot be lost in a route. A team with **no
 *     members** answers `[]` instead, and so does a team whose members have no visible work: the
 *     two are deliberately the same answer, because a client able to tell them apart would be
 *     reading team membership out of a board.
 *
 * The empty-member shortcut is inside this function and **after** the team has resolved, never in
 * front of the project gate the handler applies — answering an empty team first turns "empty team +
 * unreachable project" into a 200 that confirms the project exists.
 */
export function teamBoard(teamId: string, projectId?: string): WorkItem[] | undefined {
  const team = db.teams.find((row) => row.id === teamId);
  if (!team) return undefined;

  const members = new Set(team.members.map((member) => member.id));
  if (members.size === 0) return [];

  // `item.assignee &&` first: an unassigned card belongs to no team, and `members.has(undefined)`
  // would be a type error rather than the silent `false` it looks like.
  return assemble(projectId).filter((item) => item.assignee !== undefined && members.has(item.assignee.id));
}

export function addWorkItem(item: WorkItem): WorkItem {
  db.workItems.unshift(item);
  return item;
}
