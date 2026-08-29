import { useState } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { useProjects } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Badge, HBar } from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Button } from '../../components/ui';
import { formatCount, pluralNoun } from '../../lib/format';
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
 */
export function ProjectsPage() {
  const projects = useProjects();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);

  const rows = projects.data ?? [];
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
          /*
            The app's only COMPANY-scope affordance, and therefore the only `PermissionGate` that
            asks the company question: `project:create` is held by Company Admin and above, and
            there is no project for it to be held on. Disabled with a reason rather than hidden —
            a Test Lead seeing a greyed "New project" learns the capability exists and who to ask;
            seeing nothing at all learns the product cannot do it, which is a lie.
          */
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
        isEmpty={(items) => items.length === 0}
        empty={{
          kind: 'no-records',
          title: 'No projects',
          body: "You don't have access to any project yet. Ask an administrator to add you to one.",
        }}
      >
        {(items) => (
          <div
            /*
              Intrinsically responsive: reflows 3 → 2 → 1 with no media query, so it cannot
              misalign at a breakpoint.
            */
            className="grid gap-4"
            style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 18rem), 1fr))' }}
          >
            {items.map((project) => (
              <RouterLink
                key={project.id}
                to={`/projects/${project.id}`}
                data-testid="Projects.Card"
                className="block h-full rounded-lg border border-white/8 bg-panel p-4 no-underline transition-colors hover:border-steel/35 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]"
              >
                <span className="mb-2 flex items-baseline gap-2">
                  <span className="text-[15px] font-semibold tracking-[-.015em] text-ink">
                    {project.name}
                  </span>
                  <Identifier variant="muted">{project.key}</Identifier>
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
                <HBar
                  label="Cases"
                  fraction={project.caseCount / largest}
                  value={formatCount(project.caseCount)}
                />
                <span className="mt-2 flex flex-wrap gap-4 text-[11.5px] text-ink-4">
                  <span>{pluralNoun(project.caseCount, 'case')}</span>
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
