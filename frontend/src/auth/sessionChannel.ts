/**
 * Cross-tab session transport.
 *
 * The problem it solves is one the single-tab code cannot see: `localStorage` is shared across
 * every tab on this origin, but React state is not. So when one tab signs out, the token is gone
 * from under its siblings while they keep rendering a signed-in shell, a user name, and a rail
 * full of controls — every one of which now produces a 401. Signing *in* is worse: if the second
 * sign-in is a different person, the sibling tab renders the previous user's name and their
 * cached projects over the new user's token.
 *
 * ## Why `BroadcastChannel` and not the `storage` event
 *
 * The `storage` event is the older idiom and would fire for free, but it is tied to the thing we
 * must not depend on: `safeStorage` degrades to an in-memory `Map` when `localStorage` throws
 * (quota, Safari private mode, blocked third-party context), and in that state no `storage` event
 * is ever emitted — the multi-tab behaviour would silently disappear exactly where sessions are
 * already fragile. It also cannot express *intent*: it reports that a key changed, so "signed out"
 * and "token rotated" arrive identically and the receiver has to guess. This channel carries the
 * transition as a message.
 *
 * ## One channel object per tab, and why that matters
 *
 * A `BroadcastChannel` never delivers a message to the object that posted it — but it *does*
 * deliver to any other object with the same name, **including other objects in the same tab**.
 * Two providers each opening their own channel would therefore hear their own tab's sign-out and
 * recurse. So this module keeps exactly one channel object per tab and fans out to local
 * subscribers itself; publishing goes through that same object, which is excluded by the spec.
 * That is the whole reason this is a module rather than a hook.
 *
 * Unsupported environments (jsdom without the global, an old browser) degrade to a silent no-op:
 * the app is exactly as correct as it was before this file existed, in one tab.
 */

export type SessionBroadcast =
  /** Someone signed in on another tab. `email` identifies *who*, which decides what siblings do. */
  | { type: 'signed-in'; email: string }
  /** Someone deliberately signed out on another tab. The token is already gone from storage. */
  | { type: 'signed-out' };

const CHANNEL_NAME = 'tcms.session';

type Listener = (message: SessionBroadcast) => void;

const listeners = new Set<Listener>();

/**
 * Created lazily and kept for the life of the tab. Never closed: closing it on the last
 * unsubscribe would make a remount silently miss messages posted in the gap, and one open channel
 * per tab costs nothing.
 */
let channel: BroadcastChannel | null | undefined;

function getChannel(): BroadcastChannel | null {
  if (channel !== undefined) return channel;

  if (typeof BroadcastChannel === 'undefined') {
    channel = null;
    return channel;
  }

  try {
    channel = new BroadcastChannel(CHANNEL_NAME);
    channel.onmessage = (event: MessageEvent<SessionBroadcast>) => {
      const message = event.data;
      // A message from a different application on the same origin, or from a future build with a
      // shape this one does not know, must not throw inside a listener and take the tab down.
      if (!message || (message.type !== 'signed-in' && message.type !== 'signed-out')) return;
      // Copied before iterating: a listener that unsubscribes itself on receipt (the sign-out
      // path does exactly this when its provider unmounts) would otherwise mutate the set mid-loop.
      for (const listener of [...listeners]) listener(message);
    };
  } catch {
    // Some embedded/partitioned contexts expose the constructor and refuse to construct it.
    channel = null;
  }

  return channel;
}

/** Tell the other tabs. Never delivered to this tab — see the note on the single channel object. */
export function publishSession(message: SessionBroadcast): void {
  try {
    getChannel()?.postMessage(message);
  } catch {
    /* a failed broadcast must never fail the sign-in or sign-out that triggered it */
  }
}

/** Subscribe to session transitions made in *other* tabs. Returns the unsubscribe. */
export function subscribeSession(listener: Listener): () => void {
  getChannel();
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Test-only: drop the channel and every subscriber so one test cannot leak into the next. */
export function resetSessionChannelForTests(): void {
  listeners.clear();
  channel?.close();
  channel = undefined;
}
