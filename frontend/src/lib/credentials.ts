/**
 * Offering the browser's password manager a credential to save — Google Password Manager,
 * iCloud Keychain, 1Password and the rest, through the two mechanisms they actually watch.
 *
 * ## Why this needs code at all, when the form already has `autocomplete`
 *
 * A password manager offers to save on a **navigating form submission**: the browser sees a form
 * with a username and password field go away, and infers a sign-in happened. This app never does
 * that. `react-hook-form` calls `preventDefault()`, the request goes out through `fetch`, and the
 * result is a client-side route change — so from the browser's point of view the page never
 * submitted and never navigated. Chrome has heuristics for SPAs and they fire *sometimes*, which is
 * the worst case: the feature appears to work on the machine it was written on.
 *
 * So the offer is made explicitly, and the markup is fixed too — they are not alternatives:
 *
 *   - **The form markup and the route change are the mechanism that actually carries this**, and
 *     they are what Google documents. `sign-in-form-best-practices` says a password manager learns
 *     a form was submitted in one of two ways: navigate to a different page, or "emulate navigation
 *     with `History.pushState()` or `History.replaceState()`, and remove the password form" — which
 *     is precisely what React Router's `navigate()` does when it unmounts the sign-in route. So the
 *     forms carry `autocomplete="username"` and `current-password` / `new-password`, a real
 *     `<form>`, `id` and `name` on every field, and a username field even where the address is
 *     already known. A password form with no username field is the classic reason a manager either
 *     declines to save or saves the entry under no account at all — which is why the
 *     accept-invitation form gained a read-only email field rather than keeping the address as a
 *     caption. Safari, which implements no Credential Management API for passwords, has only this.
 *   - **This function** calls the Credential Management API on top, and it is a **belt-and-braces
 *     addition, not the Chromium mechanism.** ~~Chrome, Edge and other Chromium browsers implement
 *     it, which covers Google Password Manager.~~ → **superseded 2026-08-07.** That was the claim
 *     this file shipped with and it does not survive checking. Google's own Credential Management
 *     post now opens "This API has been drastically updated and sample codes in this article won't
 *     work any longer", was last revised in 2016, and steers readers to One Tap; the 2025 identity
 *     update never recommends `store()` for saving a password, only the `autocomplete` attributes
 *     and `/.well-known/change-password`. Driven live on 2026-08-07 the call **resolved and stored
 *     nothing** — before and inside a user gesture, in a top-level secure context with
 *     `navigator.webdriver` false. That observation is not conclusive on its own, because the same
 *     browser also declined to prompt on a plain navigating HTML form, so its password manager was
 *     suppressed for reasons of its own. What *is* settled is that this call cannot be assumed to
 *     do anything, and nothing here may be described as verified on the strength of it resolving.
 *
 * **A resolved promise from `store()` is not evidence the browser acted**, and reading it as such
 * is how this file came to be believed. The only observation that counts is the browser's own
 * prompt appearing.
 *
 * ## It never throws, and never blocks the caller
 *
 * Storing a credential is an offer, not a step in signing in. A browser without the API, a user who
 * dismisses the prompt, an insecure origin, or a permissions-policy rejection must all leave the
 * caller on the same path — the user is already signed in by the time this runs, and a rejected
 * promise here would turn a successful sign-in into an error toast. Hence the swallow, which is
 * deliberate rather than defensive-by-habit: there is no failure here a caller could act on.
 */

/**
 * `PasswordCredential` is not in TypeScript's DOM lib (it ships in a Chromium-only spec), so the
 * shape is declared narrowly here rather than reached for through `any`. Only the two members this
 * module touches are described; a wider hand-written declaration would be a second, drifting copy
 * of a spec we do not own.
 */
interface PasswordCredentialConstructor {
  new (data: { id: string; password: string; name?: string }): Credential;
}

interface CredentialCapableWindow {
  PasswordCredential?: PasswordCredentialConstructor;
}

/**
 * Whether this browser can be *told* about a credential.
 *
 * Exported so a screen can reason about it in a test. Not used to decide whether to render
 * anything: there is no UI for this, and there must not be — the prompt belongs to the browser,
 * and a page-drawn "save your password?" banner is what phishing pages look like.
 */
export function canStoreCredentials(): boolean {
  return (
    typeof window !== 'undefined' &&
    !!(window as CredentialCapableWindow).PasswordCredential &&
    !!navigator.credentials?.store
  );
}

/**
 * Offers the just-used credential to the browser's password manager.
 *
 * Call it **after** the sign-in or password-set has succeeded and never before: the API's contract
 * is that the credential worked, and offering to save one that was about to be rejected is how a
 * manager ends up holding a wrong password that then autofills forever.
 *
 * `await`-able, and awaiting it is optional — the caller may navigate immediately. Chrome keeps the
 * prompt across the client-side route change because the document does not unload.
 *
 * **Call it before the route change, not after.** The route change is the mechanism (see the file
 * note); this is the addition. Ordering them the other way would leave the addition firing against
 * an unmounted form.
 */
export async function offerToSaveCredentials(credential: {
  /** The username the manager files the entry under — this product's is the email address. */
  id: string;
  password: string;
  /** The display name shown in the manager's own account picker. */
  name?: string;
}): Promise<void> {
  if (!canStoreCredentials()) return;
  // Guarded above; the cast is the narrow declaration above rather than an escape hatch.
  const PasswordCredentialCtor = (window as CredentialCapableWindow).PasswordCredential!;

  try {
    await navigator.credentials.store(new PasswordCredentialCtor(credential));
  } catch {
    // See the file note: no failure here is one the caller could act on, and the user is already
    // signed in. Silence is the correct handling, not a swallowed bug.
  }
}
