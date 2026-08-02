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
 *   - **This function** calls the Credential Management API, whose whole purpose is a page telling
 *     the browser "these credentials were just used successfully; offer to store them". Chrome,
 *     Edge and other Chromium browsers implement it, which covers Google Password Manager.
 *   - **The form markup** carries `autocomplete="username"` and `current-password` /
 *     `new-password`, a real `<form>`, and a username field even where the address is already
 *     known. Safari implements **no** Credential Management API for passwords, so iCloud Keychain
 *     is reached through the markup alone — which is why the accept-invitation form gained a
 *     read-only email field rather than keeping the address as a caption. A password form with no
 *     username field is the classic reason a manager either declines to save or saves the entry
 *     under no account at all.
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
