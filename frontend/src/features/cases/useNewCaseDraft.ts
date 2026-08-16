import { useCallback, useMemo, useState } from 'react';

/** Where one project's New Case buffer lives. One key per project — see the hook's header. */
export const newCaseDraftKey = (projectId: string) => `tcms.newCaseDraft.${projectId}`;

/** What is stored, and what a restore offer is made of. */
export type NewCaseDraft = {
  /** ISO, from the browser's clock — the only clock this buffer has. See the header. */
  updatedAt: string;
  /** The same `toCasePayload` shape the server buffer stores, so `fromCasePayload` decodes both. */
  content: Record<string, unknown>;
};

/**
 * The New Case form's draft buffer — **D1's second half, and it is deliberately client-side.**
 *
 * `test_case_draft.case_id` is a foreign key to `test_case`, so a case being created has nothing to
 * key a server-side draft on: §128 said so, shipped the edit-mode buffer alone, and left the New
 * Case form exactly as lossy as it had always been. `V64` re-keys that table on `(case_id, user_id)`
 * and changes none of that — the missing half is a *case id*, not a person.
 *
 * So this buffer lives in `localStorage`, and that is a decision rather than a stopgap:
 *
 * - **Nobody else can ever be shown it.** The whole reason the server holds the edit-mode buffer is
 *   that a case is a shared object — a colleague opening `TC-9` has to be told there is unsaved work
 *   on it. A case that does not exist yet is shared with nobody, so a server round trip would buy
 *   durability across devices and nothing else.
 * - **A server-side new-case draft is a different table with a different lifetime**, and the two
 *   questions it forces — what expires an abandoned one, and who may read it — are a decision with a
 *   slice behind it. Neither has an obvious answer, and neither has to be answered to stop the form
 *   losing a paragraph to a closed tab.
 * - **What it costs is stated:** the draft is per browser profile. Typing on a laptop and returning
 *   on a phone offers nothing, and clearing site data discards it. That is the ordinary bargain of
 *   an unsent form, and it is strictly better than the nothing that was there before.
 *
 * ## One key per project, and never one key
 *
 * A single `tcms.newCaseDraft` would offer a half-written case from *another client's project* the
 * next time somebody opened New Case — the tenancy boundary this product is built around, breached
 * in the one place no server check can see it. The project id is in the key for that reason.
 *
 * ## Reading happens once, on mount
 *
 * The offer is a snapshot taken before the author types, exactly like the edit-mode draft query, and
 * for the same reason `useCaseDraft` sets `refetchOnMount: false`: an offer that re-read itself
 * would propose restoring the draft the author is currently writing.
 *
 * ## Storage failures are swallowed, on purpose
 *
 * Private-mode Safari throws on `setItem`, a full quota throws, and a corrupted value throws on
 * parse. None of those is worth interrupting somebody's typing over — the work is in the form, which
 * is the safest place it can be, and a failed backup of an unsent form is not a fact the author can
 * act on. The status line above the form reports it (`CaseDraftStatus`), because the promise this
 * makes is small enough that saying nothing at all would be the wrong kind of quiet.
 */
export function useNewCaseDraft(projectId: string, active: boolean): {
  /** The buffered draft found on mount, or `null`. */
  offered: NewCaseDraft | null;
  /** Buffers the form. Shaped as a promise so `useCaseAutosave` drives this and the server alike. */
  save: (content: Record<string, unknown>) => Promise<unknown>;
  /** Throws the buffer away — on dismiss, and on a successful create. */
  discard: () => void;
} {
  const key = newCaseDraftKey(projectId);

  /*
    `useState` with an initializer rather than an effect: the value is read once, before the first
    paint, so the banner does not appear a frame after the form. `useState(() => …)` runs the
    initializer once per mount, which is exactly the "snapshot on mount" the header describes.
  */
  const [offered] = useState<NewCaseDraft | null>(() => (active ? read(key) : null));

  const save = useCallback(
    (content: Record<string, unknown>) => {
      try {
        const draft: NewCaseDraft = { updatedAt: new Date().toISOString(), content };
        window.localStorage.setItem(key, JSON.stringify(draft));
        return Promise.resolve(draft);
      } catch (failure) {
        // Rejected rather than swallowed here, so `useCaseAutosave` moves its status line to
        // `failed` — the hook already treats a rejection as "the server does not hold this" and
        // retries on the next content change, which is the right behaviour for a full quota too.
        return Promise.reject(failure);
      }
    },
    [key],
  );

  const discard = useCallback(() => {
    try {
      window.localStorage.removeItem(key);
    } catch {
      // Nothing to tell the author: they asked for the draft to be gone, and it is gone from the
      // only place that will ever offer it — the offer is a mount-time snapshot, so a removal that
      // failed re-offers at worst once, on a future visit.
    }
  }, [key]);

  return useMemo(() => ({ offered, save, discard }), [offered, save, discard]);
}

/** Reads and validates the stored draft. Anything unreadable is treated as absent. */
function read(key: string): NewCaseDraft | null {
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    /*
      Decoded defensively, `fromCasePayload`'s rule one level up: this value may have been written
      by an older build of the form, and nothing in it was ever validated. A shape that is not the
      envelope reads as no draft rather than throwing on a screen whose job is not losing work.
    */
    if (
      typeof parsed !== 'object'
      || parsed === null
      || typeof (parsed as NewCaseDraft).updatedAt !== 'string'
      || typeof (parsed as NewCaseDraft).content !== 'object'
      || (parsed as NewCaseDraft).content === null
    ) {
      return null;
    }
    return parsed as NewCaseDraft;
  } catch {
    return null;
  }
}
