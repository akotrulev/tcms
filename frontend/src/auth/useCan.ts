import { useMemo } from 'react';
import { useMyPermissions } from '../api/queries';
import type { PermissionKey } from '../api/types';
import { permissionLabels } from '../lib/permissions';

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
 * Reads permissions for **one project**. There is no company-wide variant here because no screen
 * needs one yet; the endpoint answers that question (`projectId` omitted) when one does.
 */
export interface Gate {
  /** True when the caller holds the permission — or when the answer could not be obtained. */
  can: (permission: PermissionKey) => boolean;
  /** The tooltip for a denied action. `undefined` when it is allowed and needs no explanation. */
  reason: (permission: PermissionKey) => string | undefined;
}

export function useCan(projectId: string | undefined): Gate {
  const query = useMyPermissions(projectId);

  // `isPending` covers the disabled case too (no projectId → the query never runs), which is the
  // right answer: without a project there is nothing to be permitted in.
  const pending = query.isPending && !query.isError;
  const held = query.data?.permissions;

  return useMemo<Gate>(
    () => ({
      can: (permission) => (pending ? false : !held || held.includes(permission)),
      reason: (permission) => {
        if (pending) return 'Checking your permissions…';
        if (!held || held.includes(permission)) return undefined;
        return `You don't have permission to ${permissionLabels[permission]} in this project.`;
      },
    }),
    [pending, held],
  );
}
