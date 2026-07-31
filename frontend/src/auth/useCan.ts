import { useMemo } from 'react';
import { useMyCompanyPermissions, useMyPermissions } from '../api/queries';
import type { EffectivePermissions, PermissionKey } from '../api/types';
import { permissionLabels } from '../lib/permissions';
import type { UseQueryResult } from '@tanstack/react-query';

/**
 * Whether this caller may do a thing here — and, when they may not, the sentence that says so.
 *
 * ## Hidden is not protected, so this is an affordance layer and nothing more
 *
 * Every action gated here is *independently* rejected by the server (plan §6b). Nothing about
 * this hook is a security control; if it were removed, the app would be exactly as safe and
 * merely ruder. What it buys is the `ui-rules` requirement the SPA has violated since Phase 2:
 * an action the backend will refuse renders **disabled with a reason**, rather than inviting a
 * click that ends in a 403 the user cannot do anything about.
 *
 * ## Three states, and the asymmetry between two of them is the whole design
 *
 * | State | Verdict | Why |
 * |---|---|---|
 * | Loading | **denied**, "Checking your permissions…" | A flash of disabled-then-enabled costs a grey moment. Enabled-then-disabled is the defect: the click lands in the window before the answer does. |
 * | Resolved | the server's answer | — |
 * | Failed | **allowed** | See below. |
 *
 * **Failure falls open, deliberately, and it is the only rule here that looks wrong.** A wrong
 * *enable* costs one 403 rendered in the dialog the user is already looking at — the app's
 * behaviour for its entire life so far, and survivable. A wrong *disable* tells a Test Lead they
 * may not do their job, offers no retry, and shows no error anywhere, because the failure is in
 * a query that renders nothing. The server is still the gate either way, so the cheap failure is
 * the enabled one.
 *
 * Reads permissions for **one project**. {@link useCanInCompany} is the company-wide variant, which
 * the project picker's "New project" needed — `project:create` is COMPANY scope and there is no
 * project to ask about.
 */
export interface Gate {
  /** True when the caller holds the permission — or when the answer could not be obtained. */
  can: (permission: PermissionKey) => boolean;
  /** The tooltip for a denied action. `undefined` when it is allowed and needs no explanation. */
  reason: (permission: PermissionKey) => string | undefined;
}

export function useCan(projectId: string | undefined): Gate {
  // `isPending` covers the disabled case too (no projectId → the query never runs), which is the
  // right answer: without a project there is nothing to be permitted in.
  return useGate(useMyPermissions(projectId), 'in this project');
}

/**
 * The same verdict asked **of the company**, with no project in the question.
 *
 * Its own hook rather than a scope argument on {@link useCan}, because the two read different
 * endpoints' answers and a screen must not be able to slip from one to the other by passing an
 * `undefined` id. The three states, the fall-open on failure and the asymmetry behind it are
 * identical — that part is shared, so a change to the policy cannot land in one and not the other.
 *
 * The sentence differs, and it has to: "you don't have permission to create projects in this
 * project" would send a Test Lead to ask their project admin for something only a Company Admin can
 * grant.
 */
export function useCanInCompany(enabled = true): Gate {
  return useGate(useMyCompanyPermissions(enabled), 'in this company');
}

/** The one implementation of the three states, so the two scopes cannot drift apart. */
function useGate(query: UseQueryResult<EffectivePermissions>, where: string): Gate {
  const pending = query.isPending && !query.isError;
  const held = query.data?.permissions;

  return useMemo<Gate>(
    () => ({
      can: (permission) => (pending ? false : !held || held.includes(permission)),
      reason: (permission) => {
        if (pending) return 'Checking your permissions…';
        if (!held || held.includes(permission)) return undefined;
        return `You don't have permission to ${permissionLabels[permission]} ${where}.`;
      },
    }),
    [pending, held, where],
  );
}
