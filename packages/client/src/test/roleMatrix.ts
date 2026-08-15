/**
 * Who holds what, read from the document that decides it.
 *
 * `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A3 is the authority over the catalogue
 * — CLAUDE.md and README carry convenience copies and say in as many words that the spec wins and
 * the copies are the bug. So a rule about what the prose may claim reads **the spec**, not a third
 * copy of the matrix.
 *
 * That is the whole argument for parsing a markdown table, and it is worth being explicit about
 * what the alternative would have been. §211's rule compares tool descriptions against
 * `AGENT_KEYS`, a hand-kept constant in `packages/mcp/src/test/harness.ts` that is *"only as
 * current as the last slice that moved a grant remembered to update it"* — the honest caveat in its
 * own comment. A second such constant, in a second package, for a rule that sweeps four corpora, is
 * a third place for the catalogue to be wrong. The spec is the place it cannot be.
 *
 * **What this does not read is the seeded data.** `V3` and `V52` are the rows Postgres actually
 * holds, and only `SeedDataTest` can compare the two — it derives Test Lead minus Test Engineer
 * from the live catalogue and asserts §A4's seven keys exactly, in both directions. So the chain is
 * `migration → SeedDataTest → spec → this file`, and this file's link is the last one: it settles
 * *prose against the spec*, and takes on faith that the backend suite settles the spec against the
 * data. A spec that drifts from `V52` fails there, loudly, in the stack that owns the grant.
 *
 * ## The parse, and why it is allowed to be this literal
 *
 * §A3 is a run of tables under `#### ` headings, each with the same six role columns. A row's first
 * cell is one or more backticked keys, `·`-separated, sometimes followed by an italic gloss. A body
 * cell is `✓`, `—`, or a qualifier that narrows the grant (`quarantine only` — the one live
 * instance, Automation Service's `case:create`).
 *
 * A parser that silently reads nothing looks exactly like a repository whose prose is clean, which
 * is the failure mode every check in this package is written against. So {@link roleMatrix} asserts
 * its own yield: the six roles, a floor on the keys found, and — the guard that actually catches a
 * table renamed or re-columned — that the two roles this repository argues about most, Test Lead
 * and Test Engineer, differ by **exactly the seven keys `V52` left between them**. That set is the
 * product decision §210 took, and if the parse drifts it is the first thing to stop matching.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { REPO } from './corpus.js';

export const ROLES_SPEC = 'docs/specs/2026-07-30-tcms-roles-permissions-naming.md';

/** The column headings §A3 abbreviates the six roles to, in the order the tables print them. */
const COLUMNS = ['IA', 'CA', 'TL', 'TE', 'AS', 'V'] as const;

export type Role = (typeof COLUMNS)[number];

/**
 * Test Lead minus Test Engineer, as `V52` (§210) left it: giving approvals, and adding and managing
 * permission on the project. Asserted rather than assumed — see the header.
 */
const LEAD_ONLY = [
  'case:approve', 'suite:approve', 'plan:approve',
  'project:audit', 'project:settings', 'project:members', 'integration:manage',
];

const HEADER = /^\|\s*Permission\s*\|\s*IA\s*\|\s*CA\s*\|\s*TL\s*\|\s*TE\s*\|\s*AS\s*\|\s*V\s*\|/;
const KEY = /`([a-z]+:[a-z]+)`/g;

/**
 * The `PROJECT`-scope matrix, role by role.
 *
 * `COMPANY` and `SYSTEM` scope are deliberately outside it. Their tables have different columns
 * (`Every member`, and Instance Admin alone), and no sentence in the population this instrument was
 * built for is about them: what the prose gets wrong is the contributor line, which is a
 * `PROJECT`-scope question by construction.
 */
export function roleMatrix(): Record<Role, Set<string>> {
  const text = readFileSync(join(REPO, ROLES_SPEC), 'utf8');
  const held = Object.fromEntries(COLUMNS.map((role) => [role, new Set<string>()])) as
    Record<Role, Set<string>>;

  let inTable = false;
  for (const line of text.split('\n')) {
    if (HEADER.test(line)) { inTable = true; continue; }
    if (!line.startsWith('|')) { inTable = false; continue; }
    if (!inTable) continue;

    const cells = line.split('|').slice(1, -1).map((cell) => cell.trim());
    if (cells.length !== COLUMNS.length + 1) continue;
    // The `|---|:--:|` separator under every header.
    if (cells.every((cell) => /^:?-+:?$/.test(cell))) continue;

    const keys = [...(cells[0] ?? '').matchAll(KEY)].map((match) => match[1] as string);
    COLUMNS.forEach((role, column) => {
      // Anything that is not the em dash is a grant — `✓`, or a qualifier that narrows one.
      // `—` is the only spelling of "not granted" §A3 uses, and the legend says so.
      if ((cells[column + 1] ?? '—') === '—') return;
      for (const key of keys) held[role].add(key);
    });
  }

  if (held.TE.size < 25 || held.TL.size < 30 || held.V.size < 5) {
    throw new Error(
      `${ROLES_SPEC} §A3 parsed as TL=${held.TL.size} TE=${held.TE.size} V=${held.V.size} keys, `
        + 'which is too few to be the matrix. Either the tables changed shape or the heading '
        + `pattern stopped matching — this instrument reads rows under \`${HEADER.source}\`.`,
    );
  }

  const delta = [...held.TL].filter((key) => !held.TE.has(key)).sort();
  if (delta.join(',') !== [...LEAD_ONLY].sort().join(',')) {
    throw new Error(
      `Test Lead minus Test Engineer parsed as [${delta.join(', ')}], and \`V52\` left exactly `
        + `[${[...LEAD_ONLY].sort().join(', ')}]. If the catalogue really moved, this constant and `
        + '`SeedDataTest.testEngineerToTestLeadBoundary` move with it — that test is what pins the '
        + 'seeded rows, and this one only pins the prose against the spec.',
    );
  }

  return held;
}
