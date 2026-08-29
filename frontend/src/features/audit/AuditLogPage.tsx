import { Bot, ChevronDown, ChevronRight } from 'lucide-react';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useCompanyAuditEvents, useProject, useProjectAuditEvents } from '../../api/queries';
import type { AuditEvent } from '../../api/types';
import { groupByBulkOperation, type AuditGroup } from './auditGrouping';
import { useCan, useCanInCompany } from '../../auth/useCan';
import { DataRegion, GridSkeleton } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { Timeline, TimelineEvent } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { formatAge, formatCount, formatDateTime, plural } from '../../lib/format';
import { useAnnouncedResults } from '../../a11y/useAnnouncedResults';
import { auditActionLabel, auditTargetTypeLabel } from '../../lib/labels';
import { useAuditFilters } from './useAuditFilters';

/**
 * **The audit trail** (§242) — what happened here, and who did it.
 *
 * One component for two routes, because the two trails differ in their *gate* and in nothing else.
 * `/projects/:projectId/audit` costs `project:audit` and shows that project's acts;
 * `/admin/audit` costs `audit:view` at company scope and shows every act in the company,
 * project-scoped rows included. Two components would be two renderings of the same five columns
 * with nothing keeping them identical, which is what the shared-layer rule exists to prevent.
 *
 * ## A refusal is shown, not hidden
 *
 * Both endpoints answer 403 rather than an empty page, and this screen says so in words. That is
 * the whole reason the server was built that way: an empty audit trail reads as *"nothing
 * happened"*, which is a false statement about the past that the reader cannot detect, on the one
 * screen whose entire purpose is to be trusted about the past. So the fetch is gated on the
 * permission and the denial renders as a sentence naming the permission and who to ask.
 *
 * ## Both actors are rendered, always
 *
 * A token-driven act carries **both** `actorUserId` and `actorTokenId` — the person answerable for
 * the credential, and the credential. Showing one and hiding the other loses half of who did it,
 * so the person is named and the credential is a marker beside them. `actorUserId` is null only
 * for an unattended job, and a null *name* on a non-null id means the membership is gone entirely
 * — not that the person left, which does not take the name away.
 *
 * ## A timeline, not a table (007 T15)
 *
 * ~~Five columns in a grid.~~ → **superseded.** Design language §5 gives the timeline three
 * owners — case history, suite history and this — and the render draws the audit trail as one:
 * mono stamps in the gutter, one sentence per act. The reason it is the right shape here is that
 * every row of this screen is *a sentence about a moment*, and a table asks a reader to reassemble
 * one out of five cells that each say a fragment. The columns are still there in the order they
 * were, as the clauses of the line.
 *
 * ## What this screen is not
 *
 * It does not read the **instance** trail (`instance_audit_event`, `instance:audit`). That table
 * records what the *operator* did across companies, has no company on it, and belongs to the M6
 * operator console. There is no export either — `report:export` is a different permission and a
 * report type is a different slice.
 */
export function AuditLogPage() {
  const { projectId } = useParams();
  const scope = projectId ? 'project' : 'company';

  const { filters, hasActiveFilters, setFilter, setPage, setSize, clearFilters } = useAuditFilters();

  const projectGate = useCan(projectId);
  const companyGate = useCanInCompany(scope === 'company');
  const allowed =
    scope === 'project' ? projectGate.can('project:audit') : companyGate.can('audit:view');
  const pending = scope === 'project' ? projectGate.pending : companyGate.pending;

  // Undefined on `/admin/audit`, where the hook's own `enabled` gate keeps it from firing.
  const project = useProject(projectId);

  // Exactly one of these ever runs: the other's `enabled` is false for the whole life of the
  // screen, because `scope` is decided by the route. Both hooks are called unconditionally —
  // that is the rule about hooks, not a preference — and `enabled` is what makes only one fetch.
  const projectTrail = useProjectAuditEvents(
    projectId ?? '',
    filters,
    scope === 'project' && allowed,
  );
  const companyTrail = useCompanyAuditEvents(filters, scope === 'company' && allowed);
  const trail = scope === 'project' ? projectTrail : companyTrail;
  useAnnouncedResults(
    trail,
    `${formatCount(trail.data?.total ?? 0)} audit ${(trail.data?.total ?? 0) === 1 ? 'event' : 'events'} match the current filters`,
  );

  const title = scope === 'project' ? 'Project audit trail' : 'Company audit trail';

  return (
    <div>
      <PageHeader
        testId="Audit.Header"
        title={title}
        documentTitle="Audit trail"
        crumbs={
          scope === 'project'
            ? [
                { label: 'Projects', to: '/projects' },
                { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
                { label: 'Audit' },
              ]
            : [{ label: 'Administration' }, { label: 'Audit' }]
        }
        subtitle={
          scope === 'project'
            ? 'Every recorded act in this project, newest first. Company-wide acts appear on the company trail.'
            : 'Every recorded act in this company, newest first — this project’s and every other’s.'
        }
      />

      {!allowed ? (
        <section data-testid="Audit.Denied">
          <p className="max-w-[74ch] text-[13px] leading-[1.6] text-ink-3" data-testid="Audit.DeniedText">
            {pending
              ? 'Checking your permissions…'
              : scope === 'project'
                ? 'Reading this project’s audit trail needs project:audit, which is part of administering the project. Ask a test lead or a company administrator.'
                : 'The company audit trail needs audit:view. It is available to company administrators — ask one of them.'}
          </p>
        </section>
      ) : (
        <>
          {/*
            The filters sit ABOVE the region rather than inside it, so a failed load leaves them
            usable — the reader can narrow or clear and try again without a reload. DataRegion's
            own header states the rule.
          */}
          <div className="mb-4 flex flex-wrap gap-3" data-testid="Audit.Filters">
            <Field label="Action" htmlFor="audit-action" className="w-[220px]">
              <Input
                id="audit-action"
                placeholder="case.updated"
                value={filters.action ?? ''}
                onChange={(event) => setFilter('action', event.target.value || undefined)}
                data-testid="Audit.Filter.Action"
              />
            </Field>
            <Field label="From" htmlFor="audit-after" className="w-[170px]">
              <Input
                id="audit-after"
                type="date"
                value={(filters.occurredAfter ?? '').slice(0, 10)}
                onChange={(event) =>
                  // A date control gives a day; the contract wants an instant. Widen to the whole
                  // day at both ends so "4th to 9th" includes both, which is what a person means.
                  setFilter(
                    'occurredAfter',
                    event.target.value ? `${event.target.value}T00:00:00Z` : undefined,
                  )
                }
                data-testid="Audit.Filter.After"
              />
            </Field>
            <Field label="To" htmlFor="audit-before" className="w-[170px]">
              <Input
                id="audit-before"
                type="date"
                value={(filters.occurredBefore ?? '').slice(0, 10)}
                onChange={(event) =>
                  setFilter(
                    'occurredBefore',
                    event.target.value ? `${event.target.value}T23:59:59Z` : undefined,
                  )
                }
                data-testid="Audit.Filter.Before"
              />
            </Field>
          </div>

          <section data-testid="Audit.Panel">
            <DataRegion
              testId="Audit.List"
              state={{ ...trail, refetch: () => void trail.refetch() }}
              isEmpty={(page) => page.items.length === 0}
              skeleton={<GridSkeleton />}
              empty={
                hasActiveFilters
                  ? {
                      kind: 'no-filter-match',
                      title: 'No acts match',
                      body: 'Nothing recorded here matches the current filters.',
                      onClearFilters: clearFilters,
                    }
                  : {
                      kind: 'no-records',
                      title: 'Nothing recorded yet',
                      body:
                        scope === 'project'
                          ? 'Acts against this project will appear here as they happen.'
                          : 'Acts in this company will appear here as they happen.',
                    }
              }
            >
              {(page) => (
                <>
                  <Timeline data-testid="Audit.Grid">
                    {groupByBulkOperation(page.items).map((group) =>
                      group.rows.length === 1 ? (
                        <PlainRow key={group.key} row={group.rows[0]} />
                      ) : (
                        <GroupRow key={group.key} group={group} />
                      ),
                    )}
                  </Timeline>
                  {/*
                    The count stays the SERVER's row count. Deriving a count of operations here
                    would make the pager disagree with itself on every page — the server pages
                    rows, and a page holding one 12-row group would report a total of one while
                    the next page started at offset 25. This is the second-most likely
                    "improvement" somebody will make here, after grouping across a page boundary.
                  */}
                  <Pager
                    total={page.total}
                    page={page.page}
                    size={page.size}
                    onPage={setPage}
                    onSize={setSize}
                  />
                </>
              )}
            </DataRegion>
          </section>
        </>
      )}
    </div>
  );
}

/**
 * Where the reader is in the trail, and the two moves out of it.
 *
 * It states the window and the total in words rather than as a widget's caption: *"25 of 1 942"* is
 * the sentence somebody screenshots into a thread, and a bare pair of arrows is not. The page size
 * is a native select for the reason every picker on these screens is.
 */
function Pager({
  total,
  page,
  size,
  onPage,
  onSize,
}: {
  total: number;
  page: number;
  size: number;
  onPage: (next: number) => void;
  onSize: (next: number) => void;
}) {
  const first = total === 0 ? 0 : page * size + 1;
  const last = Math.min(total, (page + 1) * size);
  const lastPage = Math.max(0, Math.ceil(total / size) - 1);

  return (
    <div
      className="mt-3 flex flex-wrap items-center gap-3 border-t border-white/8 pt-3 text-[12px] text-ink-3"
      data-testid="Audit.Pager"
    >
      <span>
        <Identifier variant="muted">
          {formatCount(first)}–{formatCount(last)}
        </Identifier>{' '}
        of <Identifier variant="muted">{formatCount(total)}</Identifier> acts
      </span>
      <label className="ml-auto flex items-center gap-2">
        <span className="text-ink-4">Per page</span>
        <NativeSelect
          className="w-[76px]"
          value={size}
          onChange={(event) => onSize(Number(event.target.value))}
          data-testid="Audit.Pager.Size"
        >
          {[25, 50, 100].map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </NativeSelect>
      </label>
      <Button size="sm" disabled={page <= 0} onClick={() => onPage(page - 1)} data-testid="Audit.Pager.Previous">
        Newer
      </Button>
      <Button
        size="sm"
        disabled={page >= lastPage}
        onClick={() => onPage(page + 1)}
        data-testid="Audit.Pager.Next"
      >
        Older
      </Button>
    </div>
  );
}

/**
 * One bulk act, as one line that can be opened.
 *
 * The act label is the shared one where every row agrees and a neutral count where they do not: a
 * distribute that also takes rows off somebody writes both `execution.assigned` and
 * `execution.unassigned` in one act, so picking either would be wrong about half the group. The
 * count is worded as *shown here* because a group straddling a page boundary is real.
 */
function GroupRow({ group }: { group: AuditGroup }) {
  const [open, setOpen] = useState(false);
  const first = group.rows[0];
  const actions = new Set(group.rows.map((row) => row.action));
  const label =
    actions.size === 1
      ? auditActionLabel(first.action)
      : `${group.rows.length} changes in one operation`;
  const targets = group.rows.reduce((total, row) => total + row.targetIds.length, 0);

  return (
    <TimelineEvent
      data-testid="Audit.Group"
      when={<span title={formatDateTime(first.occurredAt, true)}>{formatAge(first.occurredAt)}</span>}
      detail={
        open && (
          // Nested inside the event rather than as a sibling row, so opening one cannot move the
          // gutter or the stamp column of anything below it.
          <ol className="mt-1.5 flex flex-col gap-1.5 border-l border-white/8 pl-3" data-testid="Audit.Group.Rows">
            {group.rows.map((row) => (
              <li key={row.id} className="text-[12.5px] text-ink-3" data-testid="Audit.Row">
                <Actor row={row} /> <span data-testid="Audit.Row.Action">{auditActionLabel(row.action)}</span>{' '}
                <span data-testid="Audit.Row.TargetType">{auditTargetTypeLabel(row.targetType)}</span>{' '}
                <Targets ids={row.targetIds} />
              </li>
            ))}
          </ol>
        )
      }
    >
      <span className="flex flex-wrap items-baseline gap-1.5">
        <Button
          size="icon"
          variant="quiet"
          className="size-5"
          onClick={() => setOpen(!open)}
          aria-expanded={open}
          aria-label={open ? 'Hide the rows of this operation' : 'Show the rows of this operation'}
          data-testid="Audit.Group.Toggle"
        >
          {open ? (
            <ChevronDown aria-hidden className="size-3.5" />
          ) : (
            <ChevronRight aria-hidden className="size-3.5" />
          )}
        </Button>
        {/* Every row of one act has one actor, so the group's is the first row's. */}
        <Actor row={first} />
        <span data-testid="Audit.Group.Action">
          {label} — {group.rows.length} of one operation shown here
        </span>
        <span className="text-ink-4" data-testid="Audit.Group.TargetType">
          {auditTargetTypeLabel(first.targetType)}
        </span>
        <span className="text-ink-4" data-testid="Audit.Group.Targets">
          {plural(targets, 'item')}
        </span>
      </span>
    </TimelineEvent>
  );
}

/** One act, one line — the clauses in the order the columns were in before §245. */
function PlainRow({ row }: { row: AuditEvent }) {
  return (
    <TimelineEvent
      data-testid="Audit.Row"
      /*
        The age reads at a glance and the exact instant is a hover away — CaseGrid's pairing.
        Seconds are shown because two acts a second apart are a different story from two acts a
        minute apart, and this is the screen where that difference is the question.
      */
      when={
        <span title={formatDateTime(row.occurredAt, true)} data-testid="Audit.Row.When">
          {formatAge(row.occurredAt)}
        </span>
      }
      detail={
        <span className="flex flex-wrap items-baseline gap-1.5">
          <span data-testid="Audit.Row.TargetType">{auditTargetTypeLabel(row.targetType)}</span>
          <Targets ids={row.targetIds} />
        </span>
      }
    >
      <span className="flex flex-wrap items-baseline gap-1.5">
        <Actor row={row} />
        <span data-testid="Audit.Row.Action">{auditActionLabel(row.action)}</span>
      </span>
    </TimelineEvent>
  );
}

/**
 * Who acted — the person, the credential, or neither.
 *
 * All three shapes are real and each renders differently. A person alone is a session. A person
 * *with* the robot marker is a pipeline acting under that person's credential, which reads
 * differently from "they typed it" and is exactly the distinction this trail exists to preserve.
 * Neither is an unattended job, and it says so rather than blaming a person.
 */
function Actor({ row }: { row: AuditEvent }) {
  if (!row.actorUserId && !row.actorTokenId) {
    return (
      <span className="text-ink-4" data-testid="Audit.Row.Actor">
        Automatic
      </span>
    );
  }

  return (
    <span className="inline-flex items-baseline gap-1" data-testid="Audit.Row.Actor">
      {row.actorTokenId && (
        <span
          title={
            row.actorTokenName
              ? `Performed by the API token “${row.actorTokenName}”, not typed by this person.`
              : 'Performed by an API token this person owns, not typed by them.'
          }
        >
          <Bot aria-hidden className="size-3.5 self-center text-ink-4" data-testid="Audit.Row.ByToken" />
        </span>
      )}
      <b className={row.actorUserName ? 'font-medium text-ink' : 'font-normal text-ink-4'}>
        {/*
          A null name on a non-null id means the membership no longer exists at all. Offboarding
          does NOT do that — a departed colleague still resolves — so this branch is rare and must
          not pretend to know who it was.
        */}
        {row.actorUserName ?? (row.actorUserId ? 'Former member' : 'API token')}
      </b>
    </span>
  );
}

/**
 * What was acted on.
 *
 * `targetIds` is an array because **one act may cover many** — a bulk operation over 143 cases is
 * one line reading "143 cases" rather than 143 lines (plan §6f). So the count is the headline and
 * the single-target case shows its id, which is the one a reader can actually do something with.
 */
function Targets({ ids }: { ids: string[] }) {
  if (ids.length === 0) {
    return <span className="text-ink-4">—</span>;
  }
  if (ids.length === 1) {
    return (
      <Identifier variant="muted" testId="Audit.Row.Target">
        {ids[0]}
      </Identifier>
    );
  }
  return <span data-testid="Audit.Row.Target">{plural(ids.length, 'item')}</span>;
}
