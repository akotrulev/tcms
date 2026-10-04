import { useMemo, useState, type ReactNode } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { useProject, useProjectMembers, useRoleAssignments, useRoles, useTeams } from '../../api/queries';
import type { RoleAssignment } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { SettingsSubnav } from '../settings/SettingsSubnav';
import { PermissionGate } from '../../components/PermissionGate';
import { Banner } from '../../components/kit';
import { Button } from '../../components/ui';
import { useCan } from '../../auth/useCan';
import { GrantRoleDialog } from './GrantRoleDialog';
import { RoleAssignmentTable } from './RoleAssignmentTable';
import { RoleReference } from './RoleReference';
import { isOverriddenHere, namesProject, overriddenUserIds } from './roleAccess';

/**
 * Who can do what **on one project** — the project half of the access model.
 *
 * `Teams & roles` (`/admin/teams`) is the company view and stays company-wide: the org chart, the
 * whole directory, every grant the caller may administer. Somebody staffing one project had to
 * read that list and find their project's rows in it, and had no way to answer the question the
 * project actually raises — *who can act here, and what did this project decide versus what did it
 * inherit*. This page is that question.
 *
 * ## Three populations, and only one of them is this project's decision
 *
 * `GET /role-assignments` answers a flat list and two different things live in it with
 * `projectId: null` — a COMPANY-scope grant (Company Admin: authority over the company) and, since
 * roles spec §A1.1 landed on 2026-08-02, a PROJECT-scope grant held company-wide, meaning *this
 * role on every project unless that project says otherwise*. So the page splits them:
 *
 *   1. **Granted on this project** — revocable here, gated on `project:members`.
 *   2. **Inherited from the company** — read-only here, because revoking one removes the role from
 *      every project and that blast radius belongs on the screen that is about the company. Rows
 *      §A1.1's replace rule has superseded are marked, because *replace, not union*: a
 *      company-wide Test Lead granted Viewer here **is** a Viewer here, and rendering the
 *      inherited row as live would tell an administrator the demotion did not take.
 *   3. **Company Admins** are shown with the inherited rows and never marked replaced —
 *      §A1.1 exemption 2, and it exists to stop anyone holding `project:members` neutralising the
 *      company's administrator by granting them Viewer.
 *
 * ## Why the unfiltered list, when `?projectId=` exists
 *
 * The endpoint takes a `projectId` filter and it would answer region 1 exactly. It cannot answer
 * region 2 — those rows name no project — so the page would need both calls, and the override in
 * region 2 is computed *across* them. Two queries means two snapshots: a revoke that lands between
 * them renders "replaced here" against a grant that no longer exists. One list, partitioned in
 * `roleAccess.ts`, cannot disagree with itself; it is also the same cache key the company screen
 * already fills, so arriving from there costs no request.
 *
 * **What this page cannot show, stated rather than left to be found.** The server filters the list
 * by `AdminReach.mayStaff`, so a Test Lead — `project:members` here, no `company:members` — is
 * shown *no* company-wide rows at all. Their inherited region is empty and says so with its own
 * copy, rather than rendering an empty state that reads as "nobody inherits anything", which is a
 * §47/§49-shaped lie: a region accurate about its query and false about the thing it labels.
 */
export function ProjectAccessPage() {
  const { projectId = '' } = useParams();
  const [granting, setGranting] = useState(false);

  /*
    Whether this reader may *administer* access here — which decides what an empty region MEANS,
    and the live pass is what turned that from a footnote into code.

    The server returns only the grants the caller may staff (`AdminReach.mayStaff`), so a Test
    Engineer on this project sees zero rows — and the first version of this page answered that with
    "Nobody is staffed here directly", to a reader who was demonstrably staffed here herself. That
    is §47's tile and §49's chip a third time: copy accurate about the query and false about the
    thing it labels. Both regions now say which of the two empties they are in, and the page says
    it once at the top rather than leaving the reader to infer it from two empty tables.
  */
  const canStaff = useCan(projectId).can('project:members');

  const project = useProject(projectId);
  // Unconditionally enabled, unlike the picker this hook was written for: on this page the list is
  // the content rather than the contents of a dialog nobody has opened.
  const members = useProjectMembers(projectId, true);
  const assignments = useRoleAssignments();
  const roles = useRoles();
  // The org chart, for expanding a project's team grants into the people they cover — a
  // person staffed through a team overrides their inherited role exactly as a directly-granted
  // one does, and a computation that forgets it is §54's defect (a plausible, wrong list).
  const teams = useTeams();

  /*
    `undefined` in, `undefined` out — deliberately, and it is the one thing this memo has to get
    right. `DataRegion` distinguishes "failed with nothing to fall back on" from "failed while
    holding older rows" by whether `data` is undefined, so a partition that answered `[]` before
    the first response would turn every failed first load into an empty table wearing a staleness
    marker. Same shape as the §57 defect where a disabled query's pending state rendered as a
    permanent skeleton: the region is only as honest as the state handed to it.
  */
  const partition = useMemo(() => {
    const all = assignments.data;
    if (!all) return undefined;
    return {
      onProject: all.filter((grant) => namesProject(grant, projectId)),
      /*
        Region 2 renders both populations that name no project — the PROJECT-scope grants a
        project inherits, and the COMPANY-scope ones (Company Admin) it does not so much inherit
        as sit under. Only the first kind can be replaced, which `overriddenPredicate` re-asks
        rather than assuming from position in the list.
      */
      fromCompany: all.filter((grant) => grant.projectId === null),
      overridden: overriddenUserIds(all, teams.data ?? [], projectId),
    };
  }, [assignments.data, teams.data, projectId]);

  // `isOverriddenHere` re-asks `isInherited` itself — see its note. Nothing here re-states the
  // COMPANY-scope exemption, so there is one place it can be got wrong instead of two.
  const overriddenPredicate = (grant: RoleAssignment) =>
    !!partition && isOverriddenHere(grant, partition.overridden, partition.onProject, projectId);

  return (
    <div>
      <PageHeader
        views={<SettingsSubnav projectId={projectId} />}
        testId="ProjectAccess.Header"
        title="Access & roles"
        documentTitle={
          project.data ? `Access & roles · ${project.data.name}` : 'Access and roles'
        }
        subtitle="Who holds which role on this project, what it inherits from the company, and what each role can do here."
        actions={
          /*
            GATED, unlike the company screen's opener — and the contrast is the whole reason that
            one is the app's single ungated opener. There, the permission the server will check is
            chosen by a field inside the form. Here the scope is the page: `project:members` on
            THIS project, known before anything renders, so §31's disable-don't-hide rule applies
            in its ordinary form.
          */
          <PermissionGate projectId={projectId} permission="project:members">
            {({ allowed }) => (
              <Button
                variant="primary"
                disabled={!allowed}
                onClick={() => setGranting(true)}
                data-testid="ProjectAccess.Grant.Open"
              >
                Grant a role here
              </Button>
            )}
          </PermissionGate>
        }
      />

      {/* Conditionally mounted, like every other create dialog in this app: a form mounted behind
          `open={false}` keeps one component instance — and therefore one set of field state and
          one mutation result — across every use of it (§54). */}
      {granting && (
        <GrantRoleDialog
          open
          lockedProjectId={projectId}
          onClose={() => setGranting(false)}
          onGranted={() => setGranting(false)}
        />
      )}

      <div className="flex flex-col gap-7">
        {/*
          Who is on this project — readable by **anybody who can open the project** since 005 Q2
          relaxed `GET /projects/{id}/members` to `project:read`.

          It is first, above the two staffing tables, and it is the reason this screen is worth
          opening for a reader who cannot staff anybody. Both tables below are filtered by
          `AdminReach.mayStaff`, so for a Test Engineer they are empty — and the page used to be
          nothing but two empty tables and a roles reference, to a person who is demonstrably ON
          this project. This panel is the fact they came for.

          ~~The members read was `project:manage`, which made this impossible.~~ → superseded: the
          org chart already discloses who is on which project, company-wide, so the endpoint was
          refusing a fact a shipped endpoint serves. The gate on every write is unchanged.
        */}
        <Section title="On this project" testId="ProjectAccess.Members">
          <DataRegion
            testId="ProjectAccess.Members.List"
            state={{ ...members, refetch: () => void members.refetch() }}
            isEmpty={(rows) => rows.length === 0}
            empty={{
              kind: 'no-records',
              title: 'Nobody is on this project',
              /*
                An honest empty this time, unlike the two tables below: this read is not filtered by
                what the caller may administer, so zero rows means zero people rather than zero
                people *you may see*.
              */
              body: 'Nobody holds a role here yet — directly, through a team, or across the company.',
            }}
          >
            {(rows) => (
              <div className="flex flex-wrap gap-x-5 gap-y-1" data-testid="ProjectAccess.Members.Rows">
                {rows.map((member) => (
                  <span key={member.id} className="text-[13px] text-ink-2" data-testid="ProjectAccess.Members.Row">
                    {member.displayName}
                  </span>
                ))}
              </div>
            )}
          </DataRegion>
        </Section>

        {!canStaff && (
          /*
            Said once, at the top, because it frames both tables below and neither of them can say
            it convincingly on its own. Without it the page reads as "this project has no access
            rules" to precisely the readers who are governed by them.
          */
          <Banner tone="neutral" data-testid="ProjectAccess.ReadOnlyNotice">
            <span>
              You can see who is on this project and what each role can do here, but not which role
              each person holds. The staffing lists below are shown to people who can manage access
              on this project.
            </span>
          </Banner>
        )}

        <Section title="Granted on this project" testId="ProjectAccess.Direct">
          <DataRegion
            testId="ProjectAccess.Direct.List"
            state={{
              ...assignments,
              data: partition?.onProject,
              refetch: () => void assignments.refetch(),
            }}
            isEmpty={(rows) => rows.length === 0}
            empty={
              canStaff
                ? {
                    kind: 'no-records',
                    title: 'Nobody is staffed here directly',
                    body: 'Grant a role to give someone sight of this project — or, if the company grants one already, this project simply uses that.',
                  }
                : {
                    kind: 'no-records',
                    title: 'Not shown to you',
                    body: 'Who is staffed on this project is visible to people who can manage its access. This does not mean nobody is.',
                  }
            }
          >
            {(rows) => (
              <RoleAssignmentTable
                rows={rows}
                testId="ProjectAccess.Direct"
                // Every row is this project; a column repeating its name says nothing.
                showProject={false}
              />
            )}
          </DataRegion>
        </Section>

        <Section title="Inherited from the company" testId="ProjectAccess.Inherited">
          <div className="flex flex-col gap-2.5">
            <p className="max-w-[74ch] text-[12.5px] leading-[1.55] text-ink-3">
              A role granted across the company applies here too, unless this project grants that
              person a role of its own — a project grant{' '}
              <b className="font-medium text-ink-2">replaces</b> the company-wide one outright, even
              when it confers less. Company Admins are never replaced. These are managed on{' '}
              <RouterLink to="/admin/teams" className="text-steel hover:underline">
                Teams &amp; roles
              </RouterLink>
              .
            </p>

            <DataRegion
              testId="ProjectAccess.Inherited.List"
              state={{
                ...assignments,
                data: partition?.fromCompany,
                refetch: () => void assignments.refetch(),
              }}
              isEmpty={(rows) => rows.length === 0}
              /*
                This copy is the honest version of an empty state that would otherwise read as
                "nobody inherits anything". The server answers only the grants THIS caller may
                administer, so a Test Lead holding `project:members` here and nothing at company
                scope is shown none of them — which is a fact about the reader, not about the
                company. Saying so is the §47/§49 lesson applied before the defect: a region
                accurate about its query and silent about its scope is a region that lies.
              */
              empty={
                canStaff
                  ? {
                      kind: 'no-records',
                      title: 'Nothing is inherited here',
                      body: 'Nobody holds a company-wide role that reaches this project — or those grants are administered by a company administrator rather than by you.',
                    }
                  : {
                      kind: 'no-records',
                      title: 'Not shown to you',
                      body: 'Company-wide grants are visible to people who can manage access. This does not mean there are none.',
                    }
              }
            >
              {(rows) => (
                <RoleAssignmentTable
                  rows={rows}
                  testId="ProjectAccess.Inherited"
                  showProject={false}
                  readOnlyReason="This role is held across the company. Revoking it would remove it from every project, so it is managed on Teams & roles."
                  isOverridden={overriddenPredicate}
                />
              )}
            </DataRegion>
          </div>
        </Section>

        <Section title="What each role can do here" testId="ProjectAccess.Roles">
          <div className="flex flex-col gap-2.5">
            <Banner tone="neutral" data-testid="ProjectAccess.Roles.Note">
              <span>
                Only project roles can be granted on a project. Company Admin is authority over the
                whole company and is granted on Teams &amp; roles.
              </span>
            </Banner>
            <DataRegion
              testId="ProjectAccess.Roles.List"
              state={{ ...roles, refetch: () => void roles.refetch() }}
              /*
                The predicate has to describe the set RoleReference actually renders, or the region
                reports "loaded" over nothing. It filters non-assignable roles as well as scope
                since V40 — Agent is PROJECT-scope and is never rendered here — so counting on
                scope alone would call this panel non-empty on the strength of a card it drops.
              */
              isEmpty={(rows) =>
                rows.filter((role) => role.scope === 'PROJECT' && role.assignable).length === 0
              }
            >
              {(rows) => (
                <RoleReference roles={rows} testId="ProjectAccess.Roles" scope="PROJECT" />
              )}
            </DataRegion>
          </div>
        </Section>
      </div>
    </div>
  );
}

/**
 * A region heading in the render's uppercase mono, and its rows directly under it.
 *
 * Rule 1: no card wrapping a table. All four regions on this page were a bordered `Panel`, which
 * put a box around a table that is already a grid of lines — and on a page whose whole argument is
 * that *these three lists are different populations*, the boxes were carrying weight the headings
 * should.
 */
function Section({
  title,
  children,
  testId,
}: {
  title: string;
  children: ReactNode;
  testId?: string;
}) {
  return (
    <section data-testid={testId}>
      <h2 className="mb-2.5 font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
        {title}
      </h2>
      {children}
    </section>
  );
}
