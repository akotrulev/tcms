import { useMemo } from 'react';
import { useMyCompanyPermissions, useMyPermissionsAcross, useProjects } from '../../api/queries';
import { useAuth } from '../../auth/useAuth';
import type { PermissionKey, Project } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Badge, Banner } from '../../components/kit';
import { NAV, SETTINGS_SECTIONS, isFolder, type NavGate } from '../../app/nav';
import { askableHoldersOf, listOfRoles, permissionLabels } from '../../lib/permissions';

/**
 * `/account/access` — **the answer to "why is your rail different from mine?"** (021 §4, brief 15).
 *
 * Nine entries in this shell are **hidden** rather than disabled, each for a reason recorded at the
 * entry that carries it (`nav.ts`, `NavGate.why`), and until this page nothing on screen said so.
 * Two colleagues screen-sharing now have a sentence, and a person who has been told to "open
 * Billing" has somewhere to look other than a support ticket.
 *
 * ## What it does not do
 *
 * **The entries stay hidden.** This page names them and says who holds the key; it does not restore
 * them, does not link to them, and discloses nothing the permission exists to protect — the reasons
 * are about the *entry*, never about the screen behind it. That distinction is the whole licence
 * for the page: a screen that leaked what it was explaining would defeat all nine decisions at once
 * in the one surface a reader is guaranteed to visit when they suspect something is hidden.
 *
 * It also does not name **people**. The mockup draws a "Who to ask" panel of colleagues, and the
 * data for it exists (`GET /org-chart` carries every member's effective role); it is not built here
 * because the deliverable is roles and holders, and a directory of who to escalate to is a screen's
 * worth of decisions — whether to show somebody on leave, whether to rank by project, what to do in
 * a company where nobody holds the key — rather than a panel. `Teams & roles` and the org chart
 * already answer it, from the same grants, for a reader who has the role name this page gives them.
 *
 * ## Where it reads from
 *
 * **No new contract.** `GET /projects` carries `myRole` per project and `GET /me/permissions`
 * answers each scope — the two reads the shell already makes to decide the rail, rendered as prose
 * instead of as an absence. The per-project permissions are one request per visible project
 * (`useMyPermissionsAcross`), every one of which is normally already in the cache, because a hidden
 * settings card is hidden per project and one project's answer cannot state that.
 */
const TEST_ID = 'Access';

/** A gated entry, flattened out of the two lists the shell gates. */
interface GatedEntry {
  label: string;
  where: string;
  gate: NavGate;
}

/**
 * The nine, derived from the same data the rail and the settings landing render.
 *
 * Derived rather than listed, for `lib/permissions.ts`' own rule: a catalogue-wide claim needs a
 * computed check and not a careful reading. A tenth gate added to `nav.ts` appears here the same
 * day, and the day somebody un-gates one it stops being explained — which is the correct outcome,
 * because it stops being hidden.
 */
const GATED: readonly GatedEntry[] = [
  ...NAV.flatMap((zone) =>
    zone.entries.flatMap((entry) =>
      isFolder(entry)
        ? entry.items
            .filter((item) => item.gate !== undefined)
            .map((item) => ({
              label: item.label,
              where: `${zone.label} → ${entry.label}`,
              gate: item.gate as NavGate,
            }))
        : entry.gate
          ? [{ label: entry.label, where: zone.label, gate: entry.gate }]
          : [],
    ),
  ),
  ...SETTINGS_SECTIONS.filter((section) => section.gate !== undefined).map((section) => ({
    label: section.label,
    where: 'Project → Settings',
    gate: section.gate as NavGate,
  })),
];

export function AccessPage() {
  /*
    The session, not a `/me` read. The name is already in the shell's auth context — this page is
    reached from the menu that draws it — and a second fetch of the same document to put a name in a
    subtitle is a request that can fail behind a page whose whole subject is explaining things.
  */
  const { user } = useAuth();
  const projects = useProjects();
  const inCompany = useMyCompanyPermissions();

  const visible = useMemo(() => projects.data ?? [], [projects.data]);
  const perProject = useMyPermissionsAcross(visible.map((project) => project.id));

  /**
   * `held.get(projectId)` — what the caller may do on each project they can see.
   *
   * A project whose answer has not arrived yet contributes **no set at all** rather than an empty
   * one, and the difference is the whole point: an empty set says "you hold nothing here", which
   * would list every gated card as hidden on a project the page has not finished asking about. The
   * rendering below reads "not yet" as "do not claim", never as "no".
   */
  const held = useMemo(() => {
    const map = new Map<string, Set<PermissionKey>>();
    visible.forEach((project, index) => {
      const answer = perProject[index]?.data;
      if (answer) map.set(project.id, new Set(answer.permissions.map((grant) => grant.key)));
    });
    return map;
  }, [visible, perProject]);

  const companyHeld = useMemo(
    () => new Set((inCompany.data?.permissions ?? []).map((grant) => grant.key)),
    [inCompany.data],
  );

  const hidden = GATED.map((entry) => ({
    entry,
    lacking:
      entry.gate.scope === 'company'
        ? inCompany.data && !companyHeld.has(entry.gate.key)
          ? COMPANY_WIDE
          : []
        : visible.filter((project) => {
            const grants = held.get(project.id);
            return grants !== undefined && !grants.has(entry.gate.key);
          }),
  })).filter(({ lacking }) => lacking.length > 0);

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Your access"
        documentTitle="Your access"
        subtitle={user?.displayName}
      />

      <Banner tone="neutral" className="mb-4" data-testid={`${TEST_ID}.Summary`}>
        <span>
          {visible.length > 0 ? (
            <>
              {rolesSentence(visible)}{' '}
            </>
          ) : (
            <>
              <strong>You hold no project grant yet.</strong>{' '}
            </>
          )}
          Company membership authenticates you; each project needs its own grant.
        </span>
      </Banner>

      <div className="grid gap-4">
        <Panel title="Where you have a grant" testId={`${TEST_ID}.Grants`}>
          {visible.length === 0 ? (
            <p className="m-0 text-[13px] text-ink-3">
              Nothing yet. A project appears here the moment somebody grants you a role on it.
            </p>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {visible.map((project) => (
                <li
                  key={project.id}
                  className="flex items-center gap-3"
                  data-testid={`${TEST_ID}.Grant`}
                >
                  <Identifier variant="strong">{project.key}</Identifier>
                  <span className="min-w-0 truncate text-[13px] text-ink">{project.name}</span>
                  <span className="ml-auto shrink-0">
                    {/*
                      `myRole` is nullable on the contract and the null is meaningful: a listed
                      project always carries a grant, so a null here is a server that could not name
                      it rather than a caller with none. Rendering the word "Viewer" as a default
                      would be the client inventing a grant.
                    */}
                    <Badge tone="steel">{project.myRole ?? 'Role unavailable'}</Badge>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Panel>

        <Panel
          title="Entries hidden from you"
          testId={`${TEST_ID}.Hidden`}
          actions={
            <span className="text-[12px] text-ink-4" data-testid={`${TEST_ID}.HiddenCount`}>
              {hidden.length}
            </span>
          }
        >
          {hidden.length === 0 ? (
            <p className="m-0 text-[13px] text-ink-3" data-testid={`${TEST_ID}.NothingHidden`}>
              None. Every entry this shell can draw is drawn for you.
            </p>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-3 p-0">
              {hidden.map(({ entry, lacking }) => (
                <li
                  key={`${entry.where}:${entry.label}`}
                  className="flex flex-col gap-1 sm:flex-row sm:items-start sm:gap-3"
                  data-testid={`${TEST_ID}.HiddenEntry`}
                >
                  <div className="min-w-0 flex-1">
                    <strong className="text-[13px] font-medium text-ink">{entry.label}</strong>
                    <p className="m-0 text-[12.5px] text-ink-3">
                      {scopeOf(entry, lacking)} · {entry.gate.why}
                    </p>
                  </div>
                  {/*
                    Who holds the key, from roles spec §A3 — the same list the disabled-reason
                    sentence uses, spelled by the same helper so the two cannot diverge. Named as
                    ROLES rather than people: a person's grants change under them and this page is
                    read, not acted on.
                  */}
                  <span className="shrink-0" data-testid={`${TEST_ID}.Holders`}>
                    <Badge tone="plain">{listOfRoles(askableHoldersOf(entry.gate.key))}</Badge>
                  </span>
                </li>
              ))}
            </ul>
          )}
          <p className="mt-4 mb-0 text-[12.5px] text-ink-4">
            Everything else you lack is <strong>visible and disabled</strong>, with the reason on the
            control — the standing rule. Only these are hidden.
          </p>
        </Panel>

        <Panel title="What each key is" testId={`${TEST_ID}.Keys`}>
          <dl className="m-0 grid gap-2 text-[12.5px] sm:grid-cols-[max-content_1fr]">
            {[...new Set(GATED.map((entry) => entry.gate.key))].map((key) => (
              <div key={key} className="contents">
                <dt className="text-ink-3">
                  <Identifier variant="muted">{key}</Identifier>
                </dt>
                <dd className="m-0 text-ink-2">
                  {permissionLabels[key]} — held by {listOfRoles(askableHoldersOf(key))}
                </dd>
              </div>
            ))}
          </dl>
        </Panel>
      </div>
    </div>
  );
}

/**
 * The stand-in for "this is a company-wide key, so there are no projects to list".
 *
 * A one-element array rather than a boolean, so the `lacking.length > 0` filter above is one
 * expression over both scopes instead of a branch that has to be written twice and can therefore
 * be written two different ways.
 */
const COMPANY_WIDE: Project[] = [{ id: '', key: '', name: '', archived: false, caseCount: 0, myRole: null }];

function scopeOf(entry: GatedEntry, lacking: Project[]): string {
  if (entry.gate.scope === 'company') return 'Company';
  return lacking.map((project) => project.key).join(', ');
}

/**
 * *"You are a Test Engineer on ACME and BETA, and a Viewer on GAMMA."*
 *
 * Grouped by role rather than listed per project, which is the sentence the mockup writes and the
 * shape somebody says out loud. Sorted by how many projects each role covers, so the reader's usual
 * role leads.
 */
function rolesSentence(projects: readonly Project[]): string {
  const byRole = new Map<string, string[]>();
  for (const project of projects) {
    const role = project.myRole ?? 'a member';
    byRole.set(role, [...(byRole.get(role) ?? []), project.key]);
  }
  const clauses = [...byRole]
    .sort(([, a], [, b]) => b.length - a.length)
    .map(([role, keys]) => `a ${role} on ${listOfRoles(keys)}`);
  return `You are ${listOfRoles(clauses)}.`;
}
