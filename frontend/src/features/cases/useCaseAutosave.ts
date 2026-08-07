import { useEffect, useRef, useState } from 'react';

/** How long the editor stays quiet after the last keystroke before buffering (§128). */
export const AUTOSAVE_DELAY_MS = 2_000;

/** What the editor's status line has to say about the buffer. */
export type AutosaveStatus = 'idle' | 'pending' | 'saving' | 'saved' | 'failed';

/**
 * Buffers the editor's work on a timer — the client half of `test_case_draft` (§128).
 *
 * <h2>The ordering that makes this correct, and it is the whole reason this is a hook</h2>
 *
 * Autosave must not run until the restore prompt has been **answered**, and the failure if it does
 * is silent and total:
 *
 * 1. The editor mounts and seeds the form from the saved case.
 * 2. The draft query is still in flight, so nothing has offered to restore anything yet.
 * 3. The author types one character. The form is dirty.
 * 4. Autosave fires and **overwrites the buffered draft with the saved case's content**.
 * 5. The draft query resolves and the editor offers to restore — the thing it just destroyed.
 *
 * The user is then shown a restore prompt for their own unsaved work which, if they accept it,
 * gives back what they had already saved. Nothing errors, nothing is logged, and the work is gone.
 * So `enabled` is the caller's gate and it must include "the prompt is resolved" — the caller
 * spells that out rather than this hook inferring it, because a hook that guessed would be a second
 * opinion about a sequence only the screen can see.
 *
 * <h2>Debounced on the CONTENT, not on the keystroke</h2>
 *
 * The effect keys on the serialized payload, so a change that produces an identical payload —
 * tabbing through a field, typing a character and deleting it, toggling a control back — restarts
 * nothing and writes nothing. Keying on a render or a change count instead would buffer on every
 * blur, which is a request per field per pass over the form.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * No retry, and no queue. A failed autosave leaves the status at `failed` and the next content
 * change tries again on its own — the work is in the form, which is the safest place it can be,
 * and a client that retried on a timer would hammer a server that is already refusing. There is
 * nothing to reconcile either: the buffer is last-write-wins by design.
 */
export function useCaseAutosave({
  enabled,
  payload,
  save,
}: {
  enabled: boolean;
  payload: Record<string, unknown>;
  save: (payload: Record<string, unknown>) => Promise<unknown>;
}): { status: AutosaveStatus; savedAt: number | null } {
  const [status, setStatus] = useState<AutosaveStatus>('idle');
  const [savedAt, setSavedAt] = useState<number | null>(null);

  /*
    The payload as text, which is both the change detector and the debounce key. `JSON.stringify`
    is stable here because `toCasePayload` builds a fresh object with its keys in declaration order
    every time — there is no map or set in the shape whose iteration order could vary between two
    equal values.
  */
  const serialized = JSON.stringify(payload);

  /*
    What the server is known to hold. Compared against the current content so a re-render, a
    remount of a child, or `enabled` flipping does not re-send a payload that is already buffered.
    A ref rather than state: writing it must not itself cause a render, or every save schedules the
    next one.
  */
  const buffered = useRef<string | null>(null);

  /*
    Kept in a ref so the effect below does not depend on it. `save` is a mutation callback whose
    identity changes on every render of the form, and an effect that depended on it would clear and
    restart the debounce timer on every keystroke's re-render — which is not merely wasteful: with
    a render per keystroke the timer would never reach its deadline while somebody is typing
    steadily, and the draft would be buffered only once they stopped for two seconds *and* the
    component happened not to re-render.
  */
  const latestSave = useRef(save);
  latestSave.current = save;

  useEffect(() => {
    if (!enabled) return;
    if (buffered.current === serialized) return;

    setStatus('pending');
    const timer = setTimeout(() => {
      // Recorded BEFORE the request, not in its `then`. Two ticks can overlap — a slow request and
      // a fast typist — and recording on success would let the second tick send content the first
      // has already sent. It also means a failed save does not re-send by itself, which is the
      // no-retry rule above.
      buffered.current = serialized;
      setStatus('saving');
      void latestSave
        .current(JSON.parse(serialized) as Record<string, unknown>)
        .then(() => {
          setStatus('saved');
          setSavedAt(Date.now());
        })
        .catch(() => {
          // Left as `failed` and NOT re-thrown: the mutation is `meta.handled`, so nothing else
          // will announce it, and the status line is the whole surface. An unhandled rejection
          // here would reach the window handler and be reported as a crash on a screen that is
          // working.
          setStatus('failed');
          // Cleared so the next content change retries rather than being suppressed as
          // already-buffered. The server does not hold this content, and the ref's name is a claim
          // about the server.
          buffered.current = null;
        });
    }, AUTOSAVE_DELAY_MS);

    // Every content change within the window cancels the pending write — that is the debounce —
    // and unmounting cancels it too, so navigating away mid-window sends nothing rather than
    // buffering from a screen that no longer exists.
    return () => clearTimeout(timer);
  }, [enabled, serialized]);

  return { status, savedAt };
}
