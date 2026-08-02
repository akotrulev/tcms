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

  const fromExecutions: WorkItem[] = db.runs
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

  const fromStale: WorkItem[] = db.cases
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

export function addWorkItem(item: WorkItem): WorkItem {
  db.workItems.unshift(item);
  return item;
}
