import { useParams } from 'react-router-dom';
import { useProjects } from '../api/queries';
import type { Project } from '../api/types';
import { useCan, useCanInCompany } from '../auth/useCan';
import { isFolder, type NavEntry, type NavGate, type NavZone } from './nav';

/**
 * The gate behaviour the shell's navigation is drawn from — carried over **unchanged** from the
 * rail (021 D-h), which is the part that had to not drift:
 *
 *   - a row with no gate is listed for everybody (§31, disable-don't-hide);
 *   - a gated row is ABSENT rather than disabled — the nine recorded exceptions, each argued in
 *     `nav.ts` at the entry that carries it;
 *   - the verdict comes from `can()`, which fails **closed while pending** and **open when
 *     `/me/permissions` itself failed** (an entry wrongly shown leads to a page that refuses; an
 *     entry wrongly hidden takes an administrator's own screens away with nothing to say why).
 *
 * Two hooks rather than one with a scope argument, because the two read different endpoints'
 * answers — a COMPANY-scope question asked of a project (or the reverse) is answered confidently
 * and wrongly. `NavGate.scope` is what stops an entry asking the wrong one. Both are warm: every
 * screen already fetches `/me/permissions`.
 */
export function useNavGates(
  activeProjectId: string | undefined,
): (gate: NavGate | undefined) => boolean {
  const projectGate = useCan(activeProjectId);
  const companyGate = useCanInCompany();

  return (gate) => {
    if (!gate) return true;
    return gate.scope === 'company' ? companyGate.can(gate.key) : projectGate.can(gate.key);
  };
}

/**
 * An entry survives its own gate; **a folder whose every child is hidden is itself hidden** — a
 * folder that opens onto nothing advertises exactly the screens the permissions exist to withhold.
 */
export function visibleEntries(
  zone: NavZone,
  allows: (gate: NavGate | undefined) => boolean,
): NavEntry[] {
  return zone.entries.flatMap<NavEntry>((entry) => {
    if (!isFolder(entry)) return allows(entry.gate) ? [entry] : [];
    const items = entry.items.filter((child) => allows(child.gate));
    return items.length ? [{ ...entry, items }] : [];
  });
}

/**
 * Which project the shell is currently framed by, and the list to switch between.
 *
 * A `projectId` in the URL is not evidence that the project exists: `/projects/1/cases` must not
 * render a complete project that was never there. `ProjectGuard` diverts a wrong address before
 * the pages render; this is the other half, because the shell draws outside the routed area and
 * would otherwise still be asserting it while the not-found screen says the opposite.
 */
export function useActiveProject(): { projects: Project[]; activeProjectId: string | undefined } {
  const { projectId } = useParams();
  const projects = useProjects();

  const known = projects.data?.some((p) => p.id === projectId) ?? true;

  return {
    projects: projects.data ?? [],
    activeProjectId: (known ? projectId : undefined) ?? projects.data?.[0]?.id,
  };
}

export const navTestId = (label: string) => `Nav.Link.${label.replace(/[^A-Za-z]/g, '')}`;
