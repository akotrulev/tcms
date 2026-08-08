/**
 * Display keys in both directions — surface spec §4.4.
 *
 * A person and an agent both name a row by its key (`TC-42`), never by its UUID: keys are
 * company-sequential (§6t), they appear in reports and tickets, and they are what a tool result
 * has to print to be readable at all. Every id this surface accepts therefore takes either.
 *
 * ## The prefixes are five, and the surface spec names one of them wrongly
 *
 * `V5` seeds exactly `TC- TS- TP- TR- DOC-` for `CASE SUITE PLAN RUN DOC_PAGE`.
 * `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` §4.4 gives the example `SU-7` for a
 * suite; there is no `SU-` prefix and there never was. Recorded here rather than corrected
 * silently, because a client that accepted `SU-7` would resolve nothing and report "not found"
 * for a key the user believed in.
 *
 * ## Why they are written down here at all
 *
 * `DisplayKeys.java` states the opposite rule for the server — *"the prefix comes from the row,
 * not from Java"* — because there the row is the definition and a constant beside it would be a
 * second one. Here the row is on the other side of the network and is not exposed by any
 * endpoint, so the choice is not "constant or definition" but "constant or nothing". The
 * constant's job is narrow and its failure is safe: deciding whether a string the user typed
 * *looks* like a key, in order to know which lookup to make. A wrong guess costs a lookup that
 * finds nothing, never a wrong row — the server is what resolves the key, and `V5`'s comment
 * records that prefixes are not per-company configurable, which is what makes the guess sound.
 */
import { TcmsClientError } from '../errors.js';

/** The five keyed entity types, as `key_sequence.entity_type` spells them. */
export type KeyedEntity = 'CASE' | 'SUITE' | 'PLAN' | 'RUN' | 'DOC_PAGE';

/** `V5`'s seeded prefixes. Immutable by contract and printed in reports and tickets. */
export const KEY_PREFIXES = {
  CASE: 'TC-',
  SUITE: 'TS-',
  PLAN: 'TP-',
  RUN: 'TR-',
  DOC_PAGE: 'DOC-',
} as const satisfies Record<KeyedEntity, string>;

/**
 * `TC-42`. Anchored, and the number has no leading zero: `V5` allocates 1-based and never
 * zero-pads, because `key_seq` owns ordering and `TC-010` is a key nothing would ever print.
 */
const KEY_PATTERN = /^(TC|TS|TP|TR|DOC)-([1-9][0-9]*)$/i;

/** RFC-4122 shaped, any version — the server decides what it mints; this only has to tell them apart. */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface DisplayKey {
  entity: KeyedEntity;
  /** Normalised to the seeded spelling — `tc-42` in, `TC-42` out. */
  key: string;
  number: number;
}

const ENTITY_BY_PREFIX = new Map<string, KeyedEntity>(
  Object.entries(KEY_PREFIXES).map(([entity, prefix]) => [prefix, entity as KeyedEntity]),
);

/** The display key a string spells, or null when it is not one. */
export function parseDisplayKey(value: string): DisplayKey | null {
  const match = KEY_PATTERN.exec(value.trim());
  if (!match?.[1] || !match[2]) return null;

  const entity = ENTITY_BY_PREFIX.get(`${match[1].toUpperCase()}-`);
  if (!entity) return null;

  return { entity, key: `${match[1].toUpperCase()}-${match[2]}`, number: Number(match[2]) };
}

export function isUuid(value: string): boolean {
  return UUID_PATTERN.test(value.trim());
}

/**
 * What a user typed, classified.
 *
 * The third arm is the one that earns the type. A string that is neither shape is **not** passed
 * through as an id in the hope that the server recognises it: a UUID-shaped path segment answers
 * 404 for "no such row" and a malformed one answers 400 for "that is not a UUID", and the second
 * reads to a user — and to an agent deciding what to do next — as the server being broken rather
 * than as their own typo. Refusing here says the true thing at the point it is knowable.
 */
export type Identifier =
  | { kind: 'uuid'; id: string }
  | { kind: 'key'; key: DisplayKey };

export function classify(value: string, expected?: KeyedEntity): Identifier {
  const trimmed = value.trim();
  if (isUuid(trimmed)) return { kind: 'uuid', id: trimmed.toLowerCase() };

  const key = parseDisplayKey(trimmed);
  if (!key) {
    throw new TcmsClientError(
      `'${value}' is neither a UUID nor a display key. Keys look like `
        + `${expected ? KEY_PREFIXES[expected] : 'TC-'}42.`,
    );
  }

  if (expected && key.entity !== expected) {
    // Naming a case where a suite belongs is a typo with a plausible-looking answer — the key
    // resolves, to the wrong kind of thing — so it is refused rather than looked up.
    throw new TcmsClientError(
      `'${key.key}' is a ${label(key.entity)} key, but a ${label(expected)} was expected `
        + `(${KEY_PREFIXES[expected]}n).`,
    );
  }

  return { kind: 'key', key };
}

function label(entity: KeyedEntity): string {
  return entity === 'DOC_PAGE' ? 'documentation page' : entity.toLowerCase();
}
