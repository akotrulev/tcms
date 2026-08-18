/**
 * The audit trail's two reads (§242).
 *
 * Both answer the same page shape and differ in their gate alone — `project:audit` on one,
 * `audit:view` at company scope on the other — so they share `AuditPage` and the same filter
 * carrier rather than growing a type each.
 *
 * **Both endpoints refuse rather than narrow.** A caller without the permission gets a 403, not an
 * empty page, because an empty audit trail reads as "nothing happened" and the reader has no way
 * to tell that apart from "you were not allowed to look". So both hooks take an `enabled` gate and
 * carry `meta: { handled: true }` — the screen renders the refusal itself, and a global toast on
 * top of it would report the same thing twice.
 */
import { type UseQueryResult, keepPreviousData, useQuery } from '@tanstack/react-query';
import { TIMEOUTS, api, qs } from '../client';
import type { AuditEvent, AuditFilters } from '../types';
import { base, keys } from './keys';

/** What both trails answer — `PageMeta` plus the rows. No facets on this surface. */
export interface AuditPage {
  items: AuditEvent[];
  page: number;
  size: number;
  total: number;
}

export function useProjectAuditEvents(
  projectId: string,
  filters: AuditFilters,
  enabled = true,
): UseQueryResult<AuditPage> {
  return useQuery({
    queryKey: keys.projectAuditEvents(projectId, filters),
    queryFn: ({ signal }) =>
      // `qs`, never an interpolated query string: contractOperations.test.ts reads the literal
      // path out of this file and resolves it against openapi.yaml, and an inline `?action=…`
      // becomes part of the literal it reads.
      //
      // The keys are listed rather than spread, and `projectId` is deliberately not among them:
      // the project is the PATH's here, and sending a second one would be asking a wider question
      // than the gate answered. `contractParameters.test.ts` reads this object statically, so a
      // spread carrying a key the operation does not declare fails there — which is how the first
      // version of this hook was caught sending `projectId` even though it set it to `undefined`.
      api.get<AuditPage>(
        `${base(projectId)}/audit-events${qs({
          actorUserId: filters.actorUserId,
          action: filters.action,
          occurredAfter: filters.occurredAfter,
          occurredBefore: filters.occurredBefore,
          page: filters.page,
          size: filters.size,
        })}`,
        { signal, timeoutMs: TIMEOUTS.interactive },
      ),
    enabled: enabled && !!projectId,
    // Paging through a trail should not blank the grid between pages — the rows are immutable,
    // so the previous page is never *wrong*, only stale.
    placeholderData: keepPreviousData,
    meta: { handled: true },
  });
}

export function useCompanyAuditEvents(
  filters: AuditFilters,
  enabled = true,
): UseQueryResult<AuditPage> {
  return useQuery({
    queryKey: keys.companyAuditEvents(filters),
    queryFn: ({ signal }) =>
      // Listed rather than spread, for the project hook's reason. `projectId` IS declared here and
      // is a filter rather than a gate — see the operation's description.
      api.get<AuditPage>(
        `/audit-events${qs({
          projectId: filters.projectId,
          actorUserId: filters.actorUserId,
          action: filters.action,
          occurredAfter: filters.occurredAfter,
          occurredBefore: filters.occurredBefore,
          page: filters.page,
          size: filters.size,
        })}`,
        { signal, timeoutMs: TIMEOUTS.interactive },
      ),
    enabled,
    placeholderData: keepPreviousData,
    meta: { handled: true },
  });
}
