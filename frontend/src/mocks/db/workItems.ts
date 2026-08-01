/** The board's cards — three derived sources plus the ones someone actually typed. */
import type { WorkItem } from '../../api/types';
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
          // A derived card may only move where its source can legally go.
          allowedTransitions: ['IN_PROGRESS', 'DONE'],
        })),
    );

  const fromReviews: WorkItem[] = db.cases
    .filter((c) => c.lifecycleStatus === 'IN_REVIEW')
    .map((c) => ({
      id: `derived-review-${c.id}`,
      derived: true,
      sourceType: 'REVIEW',
      sourceId: c.id,
      kind: 'REVIEW' as const,
      title: `Review ${c.title}`,
      description: null,
      status: 'IN_REVIEW',
      priority: c.priority,
      dueDate: null,
      overdue: false,
      // `?? undefined` rather than `?? null`: WorkItem.assignee is optional in the
      // contract, not nullable, so an unowned case's derived card carries no assignee
      // at all — which is what BoardPage already renders (`item.assignee && …`).
      assignee: c.owner ?? undefined,
      projectId,
      projectKey,
      linkedKey: c.key,
      allowedTransitions: ['IN_REVIEW', 'DONE'],
    }));

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
      allowedTransitions: ['TODO', 'IN_PROGRESS', 'DONE'],
    }));

  return [...db.workItems.filter((w) => w.projectId === projectId), ...fromExecutions, ...fromReviews, ...fromStale];
}

export function addWorkItem(item: WorkItem): WorkItem {
  db.workItems.unshift(item);
  return item;
}
