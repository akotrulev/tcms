import { useEffect, useState } from 'react';

/**
 * Mirrors `value` after it has been still for `delayMs`, so a value that changes per keystroke
 * can drive a request that should not.
 *
 * The one debounce in this project. Three screens inlined this same timer before it existed
 * (`ConditionsPanel`, `CaseEditorPage`'s step suggestions, and the case filter bar's push to the
 * URL), each with a comment explaining that no shared hook existed; §250 extracted it while a
 * fourth site — the cross-project search — was being fixed for firing one request per keystroke
 * against the widest read in the app.
 *
 * The delay is the caller's, deliberately not a constant here: a popup under the caret reads as
 * slow at 300ms where a grid refilling below the fold does not, and that judgment belongs to the
 * screen. The trailing edge is the only one implemented — a leading-edge call would fire on the
 * first character, which is the request every caller is trying not to make.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [settled, setSettled] = useState(value);

  useEffect(() => {
    if (value === settled) return;
    const id = setTimeout(() => setSettled(value), delayMs);
    return () => clearTimeout(id);
  }, [value, settled, delayMs]);

  return settled;
}
