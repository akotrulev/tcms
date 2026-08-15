/**
 * Carries the deep link across an external sign-in (§222).
 *
 * Local sign-in returns people to where the auth gate caught them — `navState.from` rides the
 * router's location state through the form. The SSO leg cannot use that mechanism: the browser
 * leaves this app entirely for the issuer and comes back on a server redirect, so the router state
 * is gone and the server-side `state` deliberately carries nothing a caller supplied. Without this
 * seam every SSO sign-in landed on `/`, and a shared link to a run or a case died at the login form
 * for exactly the people whose company uses SSO.
 *
 * `sessionStorage`, not `localStorage`: the value belongs to this tab's round trip to the issuer
 * and must not outlive the tab — a path left in shared storage would ambush an unrelated sign-in
 * days later. It survives the cross-origin excursion because the excursion is a navigation, not a
 * new tab.
 *
 * The consume guards for a **path**: storage is writable by anything same-origin, and handing a
 * stored absolute URL to a redirect is how an open redirect gets built out of a convenience. A
 * value that does not start with `/` lands on the root instead.
 */

const KEY = 'tcms.sso.return-path';

/** Remembered at the moment the provider button hands the browser to the issuer. */
export function rememberSsoReturnPath(path: string): void {
  try {
    sessionStorage.setItem(KEY, path);
  } catch {
    // Storage unavailable (privacy mode, quota) — the sign-in still works, it just lands on `/`.
  }
}

/** Read once and cleared, so a failed later visit to the callback cannot replay it. */
export function consumeSsoReturnPath(): string {
  try {
    const stored = sessionStorage.getItem(KEY);
    sessionStorage.removeItem(KEY);
    return stored != null && stored.startsWith('/') ? stored : '/';
  } catch {
    return '/';
  }
}
