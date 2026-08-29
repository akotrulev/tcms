import type { ReactNode } from 'react';
import { Tooltip, TooltipContent, TooltipTrigger } from './ui';
import type { PermissionKey } from '../api/types';
import { useCan, useCanInCompany } from '../auth/useCan';

/**
 * The one place a permission the caller lacks becomes a disabled control with a reason.
 *
 * `ui-rules` calls for **disable, don't hide**: an action the backend will refuse renders
 * disabled with a why-tooltip, never render-then-reject and never quietly absent. Both halves
 * of that are mechanical enough to get wrong once per call site, so they live here:
 *
 *  1. **The child decides what "disabled" means for it.** A render prop, not a cloned element —
 *     a `Button` takes `disabled`, a `MenuItem` takes `disabled`, an `<a>` takes neither, and
 *     `cloneElement`-ing a prop onto whatever arrives is how a disabled-looking anchor ships.
 *  2. **The tooltip needs a wrapper, and forgetting it is silent.** A disabled control has
 *     `pointer-events: none`, so no hover event ever reaches a `Tooltip` wrapped directly around
 *     one — the control greys out and explains nothing, which is the *worse* half of the original
 *     defect. The denied branch wraps the child in a span that still receives the hover. (Written
 *     against MUI's Tooltip; Radix's has the identical requirement, so the port changed the
 *     import and nothing else about this.)
 *
 * Deliberately NOT hiding anything: a Viewer seeing a greyed "Delete" learns the capability
 * exists and is worth asking for. A Viewer seeing no button at all learns the product cannot do
 * it. The second is a lie, and it is also why action cells stop shifting between rows.
 *
 * Every gated control keeps its `data-testid` — a disabled button is still the button under
 * test, and a test that can't find it can't assert it is disabled.
 */
export function PermissionGate({
  projectId,
  permission,
  authorId,
  wrapper = 'inline',
  children,
}: {
  /**
   * The project the action happens in, or **`'company'`** for an action that has no project —
   * today only "New project", whose permission (`project:create`) is COMPANY scope because the
   * project it would create does not exist to be asked about.
   *
   * A literal rather than a second component: the tooltip wrapper below is the thing that is easy
   * to get wrong once per call site, and two components would be two places to get it wrong. The
   * verdict itself still comes from two different hooks, so neither scope can answer the other's
   * question.
   */
  projectId: string | undefined | 'company';
  /**
   * The key this action costs — or **several keys, any one of which is enough**.
   *
   * The array form is for an action a caller can reach by more than one route: the `.feature`
   * import (§255) plans `CREATE` rows and `UPDATE` rows, and either `case:create` or `case:edit`
   * gives a caller work the screen can do. Asking about one of the two would grey the entry point
   * out for somebody the server would serve, which is the failure mode this component exists to
   * prevent, inverted.
   *
   * The single form routes to `Gate.can`/`Gate.reason` unchanged rather than through a
   * one-element array, so the hundred existing call sites keep the exact verdict they had.
   */
  permission: PermissionKey | PermissionKey[];
  /**
   * **The row's author**, for a permission whose grant may be `OWN`-qualified — today `case:delete`
   * and only it (roles spec §A5). Supplying it switches the verdict from "do they hold this" to
   * `Gate.canOnAuthored`, the client half of the server's `canDoOnAuthored`.
   *
   * Pass it through from the row **including when it is null** — an unauthored case is one an `OWN`
   * grant does not reach, and defaulting the absence away would enable Delete on exactly the rows
   * the server refuses. Omitting the prop entirely is the unqualified question, which is right for
   * every other permission.
   *
   * Meaningless with `projectId="company"`: authorship is a question about a row in a project, and
   * a company-scope grant has no row. That combination throws rather than quietly ignoring one of
   * the two arguments — the same call this codebase's server makes when a qualifier and a question
   * do not meet, and unreachable for the same reason.
   */
  authorId?: string | null;
  /**
   * How the denied branch's tooltip anchor lays out. `inline` suits a flex row of buttons;
   * **`block` is required inside a `Menu`**, whose `MenuList` is a plain `<ul>` — an inline
   * anchor there makes the items flow along one line instead of stacking, which is what the
   * first browser pass of this component actually did to the suite tree's kebab menu.
   */
  wrapper?: 'inline' | 'block';
  /** Receives the verdict; renders the control with its own notion of `disabled`. */
  children: (state: { allowed: boolean; reason: string | undefined }) => ReactNode;
}) {
  // Both hooks run on every render — they must, and calling one conditionally would be the hook
  // order violation React cannot recover from. The unused one is a cache read, not a request: its
  // query is either already resolved or disabled.
  const inProject = useCan(projectId === 'company' ? undefined : projectId);
  const inCompany = useCanInCompany(projectId === 'company');
  const gate = projectId === 'company' ? inCompany : inProject;

  // `authorId !== undefined` rather than a truthiness check: null is a *supplied* author — the
  // unauthored row — and it must reach canOnAuthored, which is the arm that refuses it. Reading
  // `authorId ? … : …` here would silently give every unauthored case the unqualified verdict,
  // which is the exact defect this prop exists to close.
  const asksAuthorship = authorId !== undefined;
  if (asksAuthorship && projectId === 'company') {
    throw new Error(
      'PermissionGate: authorId is a question about a row in a project, and projectId="company" ' +
        'has no row. One of the two arguments would have to be ignored, so neither is.',
    );
  }

  const anyOf = Array.isArray(permission) ? permission : null;
  // An empty list is a question with no subject: `canAny` would answer false and `reasonAny` would
  // have nothing to name, so the control would render disabled and explain nothing — the silent
  // half of the original defect. Refused at the call site instead, where it is a typo.
  if (anyOf && anyOf.length === 0) {
    throw new Error('PermissionGate: permission={[]} asks about no key, so it can only ever deny.');
  }
  // Authorship qualifies ONE grant. Asking it of a list would have to pick which of the keys the
  // author question is about, and picking is the thing that goes silently wrong.
  if (anyOf && asksAuthorship) {
    throw new Error(
      'PermissionGate: authorId qualifies a single permission; pass one key, not a list.',
    );
  }

  const allowed = asksAuthorship
    ? gate.canOnAuthored(permission as PermissionKey, authorId)
    : anyOf
      ? gate.canAny(anyOf)
      : gate.can(permission as PermissionKey);
  const reason = asksAuthorship
    ? gate.reasonOnAuthored(permission as PermissionKey, authorId)
    : anyOf
      ? gate.reasonAny(anyOf)
      : gate.reason(permission as PermissionKey);

  const control = children({ allowed, reason });
  if (allowed || !reason) return <>{control}</>;

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        {/*
          `inline-flex` rather than a bare span: the wrapper sits where the control sat, and a
          default inline box would collapse a full-width button and break the alignment of a row
          of them. `cursor-not-allowed` states the verdict before the tooltip has time to open.
        */}
        <span
          /*
            Explicitly presentational. Inside a menu this span sits between the `role="menu"`
            list and the `role="menuitem"` it wraps, and an unmarked generic there can break the
            parent/child relationship some screen readers rely on. `role="none"` says the wrapper
            is scaffolding. Outside a menu a span has no implicit role, so this costs nothing.
          */
          role="none"
          className={
            wrapper === 'block' ? 'block cursor-not-allowed' : 'inline-flex cursor-not-allowed'
          }
        >
          {control}
        </span>
      </TooltipTrigger>
      <TooltipContent>{reason}</TooltipContent>
    </Tooltip>
  );
}
