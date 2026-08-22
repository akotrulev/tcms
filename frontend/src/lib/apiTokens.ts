/**
 * What a token's row says about it — the one place a credential's state is decided (§118).
 *
 * ## The state is DERIVED, and the contract cannot serve it
 *
 * `ApiToken` carries `revokedAt` and `expiresAt` and no status field. That is not an omission:
 * expiry is a comparison against *now*, so a served status would be a snapshot that goes stale in
 * the cache while the row sits on screen — a token that expires at 14:00 would still read "live"
 * at 14:05 because the list was fetched at 13:58. Derived here, it is right on every render.
 *
 * ## Revoked and expired are NOT collapsed, and the Revoke button is why
 *
 * Both mean the credential cannot authenticate, so the tempting simplification is one "inactive"
 * state. It is wrong in a way that reaches an action: revoking an already-revoked token is a
 * no-op the row must not offer, while revoking an **expired** one is a legitimate and useful act —
 * it closes the credential deliberately rather than leaving it one date change away from working.
 * A screen that could not tell them apart would either disable a real action or offer a dead one.
 *
 * Revocation outranks expiry when a token is both, because it is the fact somebody *decided*: an
 * expired token that was then revoked is withdrawn, and reporting it as merely lapsed suggests
 * extending the date would bring it back.
 *
 * These labels live here rather than in `lib/labels.ts` on purpose — that file is keyed on
 * **contract** unions, where `satisfies` makes a new wire value a compile error. `ApiTokenState`
 * is this client's own vocabulary, so the same discipline is applied to the local union instead.
 */
import type { ApiToken } from '../api/types';

export type ApiTokenState = 'LIVE' | 'EXPIRED' | 'REVOKED';

export const tokenStateLabels = {
  LIVE: 'Live',
  EXPIRED: 'Expired',
  REVOKED: 'Revoked',
} as const satisfies Record<ApiTokenState, string>;

/**
 * The colour each state carries, as a CSS custom property from `index.css`.
 *
 * Neither dead state is painted in the failure colour, and that is deliberate: a credential
 * reaching its stated end date is the system working, and a withdrawn one is somebody having done
 * their job. A red row on this screen would report an incident where there is none — the colour
 * budget in `index.css` is spent on test outcomes, and a token's state is not one.
 */
export const tokenStateColors = {
  LIVE: 'var(--color-success)',
  EXPIRED: 'var(--color-deprecated)',
  REVOKED: 'var(--color-skipped)',
} as const satisfies Record<ApiTokenState, string>;

export function tokenState(token: ApiToken, now: number = Date.now()): ApiTokenState {
  if (token.revokedAt) return 'REVOKED';
  if (token.expiresAt && new Date(token.expiresAt).getTime() <= now) return 'EXPIRED';
  return 'LIVE';
}

/**
 * Where a token can act, in a sentence rather than a uuid.
 *
 * **`null` is the wide answer, not the missing one** — an unpinned token reaches every project its
 * owner can, which is the more powerful of the two and therefore the one a blank cell must never
 * be mistaken for. `ui-rules`' names-not-ids rule with the null case carrying the weight.
 *
 * A `projectId` naming a project the reader cannot see falls back to the id. That is reachable:
 * the token list is company-wide and project visibility is not, so a Company Admin without a grant
 * on that project holds a row whose project is not in their own `/projects`. Rendering the id is
 * ugly and true; rendering "All projects" would be a lie about a token's blast radius.
 */
export function tokenScopeLabel(token: ApiToken, projectName: string | undefined): string {
  if (!token.projectId) return 'Every project its owner can reach';
  return projectName ?? token.projectId;
}
