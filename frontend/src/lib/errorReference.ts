/**
 * A short handle on one moment a failure happened.
 *
 * Design language §7: *"Every failure carries a six-character reference and a timestamp"* — so that
 * a reader who reports one has something to quote and a log has something to match. The 2026-08-30
 * renders audit found the route error boundary carrying neither (9.2), which leaves somebody
 * describing a red page with nothing to identify it by.
 *
 * Six characters from a Crockford-style alphabet with the ambiguous glyphs removed. A reference is
 * read aloud into a call and typed into a support message, and `0`/`O` and `1`/`I` are exactly
 * where that goes wrong — which is a property of how it is used rather than of how it is generated.
 *
 * **It is not an id.** Nothing looks it up and nothing stores it; it is a handle on one moment, so
 * it is generated where the failure is caught and never regenerated on a re-render. A reference
 * that changed while the reader was copying it would be worse than none.
 */
const ALPHABET = '23456789ABCDEFGHJKMNPQRSTVWXYZ';

export function newErrorReference(): string {
  /*
    `crypto.getRandomValues` rather than `Math.random`: not for unpredictability — there is nothing
    to guess here — but because it is the API that exists in every browser and in jsdom without a
    polyfill, and the modulo bias over 30 letters is irrelevant to a handle nobody indexes.
  */
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  return [...bytes].map((byte) => ALPHABET[byte % ALPHABET.length]).join('');
}
