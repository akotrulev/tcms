import { useMemo, useState } from 'react';
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router-dom';
import { useClients, useProjects } from '../../api/queries';
import { ProjectKeyChip } from '../../app/ProjectSwitcher';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Badge, HBar } from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Button, Switch } from '../../components/ui';
import { formatCount, plural } from '../../lib/format';
import { ProjectCreateDialog } from './ProjectCreateDialog';

/**
 * Project picker. Reached explicitly, and also the fallback when a URL names a project the
 * user can't see — better to land here with a way forward than on a bare 403.
 *
 * **A picker, not a grid** — `renders/01-index-tables.html` frame H, which is the one screen in
 * family 1 that is deliberately not a table: three rows and three columns is a table for the sake
 * of one. Each card carries its case count as a bar rather than only as a number, because the bar
 * is what makes the counts comparable **down the column** at a glance, which is the whole job of a
 * picker.
 *
 * The bar's denominator is the **largest project in the list**, not a target — there is no such
 * thing as a project's expected case count, and inventing one would make the bar a judgement. So it
 * says *relative to the biggest thing here*, which is the only proportion this data supports. The
 * render draws two more bars per card, `Automated` and `Last run`; neither is on
 * `ProjectSummary`, so neither is drawn. A bar over a number the response does not carry is the
 * defect the plan's §9.3 warns about.
 *
 * The 2026-08-30 renders audit raised those two missing bars again (finding 1.12). The answer is
 * still this paragraph: they need two fields on `ProjectSummary` and the services behind them, and
 * that is a contract change rather than a screen fix — it is on plan 008 T19's deferred list. The
 * audit's third item on this card, a `Current` badge, is not drawn for a related reason: this route
 * carries no `:projectId`, and the app has no persisted "last project" to read, so the badge would
 * have to invent a current project rather than mark one.
 */
export function ProjectsPage() {
  const projects = useProjects();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);

  /*
    In the URL rather than in `useState`, this repo's rule for filter state, and the same shape
    `PlanListPage` uses for the same switch: somebody who ticked it to find last spring's project
    sends the link on, and component state sends a list the project is not in.
  */
  const [params, setParams] = useSearchParams();
  /*
    **Default ON**, and the parameter spells the hiding rather than the showing.

    The render draws a `Show archived` switch, which reads as off-by-default — and off-by-default
    is the one thing this list may not do. The archived badge's comment below records why, and
    `ArchiveProject.test.tsx` asserts it: the server returns retired projects deliberately, because
    omitting one makes it unreachable rather than marked, and this card is the only route back to
    the screen that can un-archive it. A default that hid them would turn "marked" into "gone" for
    every reader who never finds the switch.

    So the control exists, as the render asks, and it defaults to the state that keeps the
    guarantee. The count beside it says what it is holding back once it is off.
  */
  const showArchived = params.get('archived') !== 'false';

  /*
    018 Mc's client filter, and the whole of it is conditional on the answer being non-empty.

    A company with no clients must see NO Client control anywhere — not disabled, not showing
    "None (internal)" alone, not an empty select (scope D1 rule 3). That is the module's acceptance
    criterion and it is asserted as an absence in this file's test. `enabled` on the permission for
    the reason `clients.ts` gives: a reader without `client:read` must not fire a 403 for a control
    they were never meant to see.
  */
  const clientGate = useCanInCompany();
  const clients = useClients({ status: 'ALL', enabled: clientGate.can('client:read') });
  const companyHasClients = (clients.data?.length ?? 0) > 0;
  const clientFilter = params.get('client') ?? '';

  const clientNames = useMemo(
    () => new Map((clients.data ?? []).map((client) => [client.id, client.name])),
    [clients.data],
  );

  const all = projects.data ?? [];
  /*
    Hidden by default, with a switch — the render draws one, and the audit found the list showing
    every retired project unconditionally (finding 1.12).

    This does not reopen the decision the archived badge's comment below records. That comment is
    about *omitting* a project, which would make it unreachable; the switch is the opposite of
    omitting, and the count of what it is holding back is printed beside it, so nothing is
    silently missing.
  */
  const archivedCount = all.filter((project) => project.archived).length;
  const byArchived = showArchived ? all : all.filter((project) => !project.archived);
  /*
    Filtered here rather than server-side, because `GET /projects` declares no client parameter and
    the list is a picker of a handful of cards — the same reason the archived switch filters here.
    `clientFilter` is in the URL so the narrowed list can be sent to somebody, this codebase's rule.
  */
  const rows =
    clientFilter === '' ? byArchived : byArchived.filter((project) => project.client?.id === clientFilter);
  const hiddenByTheSwitch = all.length > 0 && rows.length === 0 && clientFilter === '';
  const hiddenByTheClient = clientFilter !== '' && rows.length === 0;
  /*
    Guarded at 1 rather than at 0: a list where every project has no cases would divide by zero and
    render `NaN%`-wide bars. One means every bar is empty, which is the truthful drawing of that.
  */
  const largest = Math.max(1, ...rows.map((project) => project.caseCount));

  return (
    <div>
      <PageHeader
        testId="Projects.Header"
        title="Projects"
        subtitle="Pick a project to work in. A project is a whole client product — Payments is a feature inside one, never a project of its own."
        actions={
          <div className="flex items-center gap-4">
          {/*
            The switch the render draws, and the count of what it is holding back — so a hidden
            project is marked as hidden rather than simply absent. Ungated: which projects are
            retired is not a secret from anyone who can already see them listed.
          */}
          {archivedCount > 0 && (
            <label className="flex items-center gap-2 text-[12px] text-ink-3">
              <Switch
                checked={showArchived}
                onCheckedChange={(next) =>
                  setParams(next ? {} : { archived: 'false' }, { replace: true })
                }
                data-testid="Projects.ShowArchived"
              />
              Show archived ({formatCount(archivedCount)})
            </label>
          )}
          {/*
            The Client filter (018 Mc), and it renders ONLY when the company has at least one
            client. Not disabled and not empty: an empty "Client: any" is exactly the nagging
            `solo-overview.html` rules out, and a company building its own products is the owner's
            own worked example rather than an edge case.

            A native `<select>` rather than the kit's `Combobox`: the list is a handful of names
            with no search worth having, and the picker has to be reachable in one keystroke beside
            a switch.
          */}
          {companyHasClients && (
            <label className="flex items-center gap-2 text-[12px] text-ink-3" data-testid="Projects.ClientFilter">
              Client
              <select
                className="rounded-md border border-line bg-panel px-2 py-1 text-[12px] text-ink"
                value={clientFilter}
                onChange={(event) =>
                  setParams(
                    (current) => {
                      const next = new URLSearchParams(current);
                      if (event.target.value === '') next.delete('client');
                      else next.set('client', event.target.value);
                      return next;
                    },
                    { replace: true },
                  )
                }
                data-testid="Projects.ClientFilter.Select"
              >
                <option value="">Any</option>
                {(clients.data ?? []).map((client) => (
                  <option key={client.id} value={client.id}>
                    {client.name}
                  </option>
                ))}
              </select>
            </label>
          )}
          {/*
            The app's only COMPANY-scope affordance, and therefore the only `PermissionGate` that
            asks the company question: `project:create` is held by Company Admin and above, and
            there is no project for it to be held on. Disabled with a reason rather than hidden —
            a Test Lead seeing a greyed "New project" learns the capability exists and who to ask;
            seeing nothing at all learns the product cannot do it, which is a lie.
          */}
          <PermissionGate projectId="company" permission="project:create">
            {({ allowed }) => (
              <Button
                variant="primary"
                disabled={!allowed}
                onClick={() => setCreating(true)}
                data-testid="Projects.New"
              >
                New project
              </Button>
            )}
          </PermissionGate>
          </div>
        }
      />

      {/*
        Conditionally rendered, so the form mounts fresh each time — see the dialog's own note on
        why it must not `reset()` itself.
      */}
      {creating && (
        <ProjectCreateDialog
          open
          onClose={() => setCreating(false)}
          /*
            Straight into the project the user just made. They created it to work in it, and the
            picker they would otherwise land back on now has one more card on it that they would
            have to find and click.
          */
          onCreated={(project) => void navigate(`/projects/${project.id}`)}
        />
      )}

      <DataRegion
        testId="Projects.List"
        state={{ ...projects, refetch: () => void projects.refetch() }}
        isEmpty={() => rows.length === 0}
        empty={
          hiddenByTheClient
            ? {
                kind: 'no-filter-match',
                title: 'No projects for that client',
                hidden: byArchived.length,
                filters: 1,
                noun: 'project',
                onClearFilters: () =>
                  setParams(
                    (current) => {
                      const next = new URLSearchParams(current);
                      next.delete('client');
                      return next;
                    },
                    { replace: true },
                  ),
              }
          : hiddenByTheSwitch
            ? {
                kind: 'no-filter-match',
                title: 'Every project you can open is archived',
                hidden: all.length,
                filters: 1,
                noun: 'project',
                onClearFilters: () => setParams({}, { replace: true }),
              }
            : {
                kind: 'no-records',
                title: 'No projects',
                body:
                  "You don't have access to any project yet. Ask an administrator to add you to "
                  + 'one.',
              }
        }
      >
        {() => (
          <div
            /*
              Intrinsically responsive: reflows 3 → 2 → 1 with no media query, so it cannot
              misalign at a breakpoint.
            */
            className="grid gap-4"
            style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 18rem), 1fr))' }}
          >
            {rows.map((project) => (
              <RouterLink
                key={project.id}
                to={`/projects/${project.id}`}
                data-testid="Projects.Card"
                className="block h-full rounded-lg border border-line bg-raised p-4 shadow-e1 no-underline transition-colors hover:border-steel/35 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]"
              >
                <span className="mb-2 flex items-center gap-2">
                  <ProjectKeyChip project={project} />
                  <span className="text-[15px] font-semibold tracking-[-.015em] text-ink">
                    {project.name}
                  </span>
                  {/*
                    The archived marker, and the reason the list is not filtered instead: the
                    server returns retired projects deliberately, because omitting them would
                    make one unreachable rather than marked — and this card is the only route
                    back to the screen that can un-archive it. `archived` was a required field
                    of this response from M0 and nothing in the SPA read it until §159, for the
                    honest reason that nothing could set it. Same decision as the docs tree
                    makes for an archived page.
                  */}
                  {project.archived && (
                    <Badge tone="plain" className="ml-auto shrink-0" data-testid="Projects.Card.Archived">
                      Archived
                    </Badge>
                  )}
                </span>
                <span className="mb-3 block text-[13px] text-ink-4">{project.description}</span>
                {/*
                  018 Mc. Rendered only when the project HAS a client — a project without one is
                  internal work, which is the common case, and nothing in this product marks it as
                  missing one (scope D1 rule 1). There is no "None (internal)" line here for that
                  reason: the mockup's table has a column that must say something in every cell,
                  and a card has no such obligation.
                */}
                {project.client && (
                  <span
                    className="mb-3 block text-[12px] text-ink-3"
                    data-testid="Projects.Card.Client"
                  >
                    {clientNames.get(project.client.id) ?? project.client.name}
                  </span>
                )}
                <HBar
                  label="Cases"
                  fraction={project.caseCount / largest}
                  value={formatCount(project.caseCount)}
                />
                <span
                  className="mt-2 flex flex-wrap gap-4 text-[11.5px] text-ink-4"
                  data-testid="Projects.Card.Meta"
                >
                  {/*
                    `plural`, not `pluralNoun`. `pluralNoun` returns the NOUN only (`lib/format.ts`
                    documents that, and the dashboard wants it that way because it wraps the number
                    in an `Identifier`), so this line rendered the bare word "cases" under a bar
                    whose own label already said `Cases` — and with `myRole` null for a Company
                    Admin, correctly omitted below, the whole line was one dangling noun. Renders
                    audit 1.12.
                  */}
                  <span>{plural(project.caseCount, 'case')}</span>
                  {/* myRole is null when the caller reaches this project through a
                      COMPANY-scope grant, which is the contract's stated meaning and not a
                      missing value. Rendering the label anyway printed a dangling
                      "Your role:" — invisible in mock mode, where every fixture project
                      carries a role, and visible on the first real Company Admin session.
                      The line is omitted rather than filled with an invented label: the API
                      deliberately does not say what their company role is here, and guessing
                      it would be the UI making a claim the response never made. */}
                  {project.myRole !== null && <span>Your role: {project.myRole}</span>}
                </span>
              </RouterLink>
            ))}
          </div>
        )}
      </DataRegion>
    </div>
  );
}
