/**
 * Who holds what, read from the document that decides it.
 *
 * `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A3 is the authority over the catalogue
 * — CLAUDE.md and README carry convenience copies and say in as many words that the spec wins and
 * the copies are the bug. So a rule about what the prose may claim reads **the spec**, not a third
 * copy of the matrix. ~~And nothing compares the two copies to it.~~ → **§215 does**
 * (`packages/client/src/test/matrixCopies.test.ts`), over all three scopes and all 55 keys, which
 * is what {@link specMatrix} is here for: {@link roleMatrix} answers *who holds what* and takes the
 * `PROJECT` tables alone, and a rule about a **copy** has to read every cell of every table,
 * including the qualifier text a boolean throws away.
 *
 * That is the whole argument for parsing a markdown table, and it is worth being explicit about
 * what the alternative would have been. ~~§211's rule compares tool descriptions against
 * `AGENT_KEYS`, a hand-kept constant in `packages/mcp/src/test/harness.ts` that is *"only as
 * current as the last slice that moved a grant remembered to update it"* — the honest caveat in its
 * own comment. A second such constant, in a second package, for a rule that sweeps four corpora, is
 * a third place for the catalogue to be wrong.~~ → **superseded 2026-08-15 (§213): that constant is
 * {@link agentKeys} now**, so there is no second place. The spec is the place it cannot be.
 *
 * **What this does not read is the seeded data.** `V3` and `V52` are the rows Postgres actually
 * holds, and only `SeedDataTest` can compare the two. So the chain is
 * `migration → SeedDataTest → spec → this file`, and this file's link is the last one: it settles
 * *prose against the spec*, and takes on faith that the backend suite settles the spec against the
 * data. A spec that drifts from `V52` fails there, loudly, in the stack that owns the grant.
 *
 * ~~It derives Test Lead minus Test Engineer from the live catalogue and asserts §A4's seven keys
 * exactly, in both directions.~~ → **that sentence was true and it was not the chain.** §214
 * measured what the middle link actually compared: one delta of one pair, plus three hand-written
 * lists that name the spec in their failure message and never open it. The other 385 cells of the
 * matrix were compared to nothing on either side of the arrow, while this file parsed the same
 * document to police prose in four corpora. `SeedDataTest.theSeededMatrixIsTheSpecsMatrix` is the
 * comparison now, cell by cell, both ways — and the divergence it found is three inert
 * `company:read` rows the spec explains in the paragraph under the table that cannot express them.
 *
 * The window under it is unchanged and is worth stating where somebody reading this file will meet
 * it: that comparison lives in the **backend** suite, and `ci.yml` runs no backend test. Between a
 * bad spec edit and the 01:17 UTC nightly, both rules in this package answer confidently out of a
 * wrong document.
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
 * Every column heading §A3 grants through, the six plus the one only its `COMPANY` table has.
 *
 * `Every member` is not a seventh tier — it is `V15`'s non-assignable **Company Member** baseline,
 * and the spec says so in the paragraph under the table. It is here because
 * {@link specMatrix} is total over the catalogue and that column carries `company:read`.
 */
export const SPEC_ROLES = [...COLUMNS, 'Every member'] as const;

export type SpecRole = (typeof SPEC_ROLES)[number];

/** A column that grants nothing — the prose the `COMPANY` and `SYSTEM` tables end in. */
const PROSE_COLUMN = 'Covers';

/** The catalogue, as §A3 has printed it since `V3` and as every copy of it claims. */
export const CATALOGUE_SIZE = 55;

/**
 * Test Lead minus Test Engineer, as `V52` (§210) left it: giving approvals, and adding and managing
 * permission on the project. Asserted rather than assumed — see the header.
 */
const LEAD_ONLY = [
  'case:approve', 'suite:approve', 'plan:approve',
  'project:audit', 'project:settings', 'project:members', 'integration:manage',
];

const KEY = /`([a-z]+:[a-z]+)`/g;

/** A grant cell that says the role does not hold the key. §A3's legend spells it exactly one way. */
export const NOT_GRANTED = '—';

/** One row of one §A3 table: the keys its first cell names, and the cell each column gave them. */
export interface MatrixRow {
  keys: string[];
  cells: Map<SpecRole, string>;
  /** The table this row came from carries all six `PROJECT` columns. */
  project: boolean;
}

/**
 * Every §A3 row, read between the `### A3.` and `### A4.` headings and keyed by **column heading**.
 *
 * Keying by heading rather than by position is what lets one parse serve three tables of different
 * shapes, and it is why an unknown heading is a hard failure: a column nobody compares reads
 * exactly like universal agreement, which is the failure mode every rule in this package is
 * written against. `SeedDataTest.matrixFromTheSpec` reads the same document the same way, one
 * stack over, and says the same thing about it.
 */
export function specRows(): MatrixRow[] {
  const lines = readFileSync(join(REPO, ROLES_SPEC), 'utf8').split('\n');
  const start = lines.findIndex((line) => line.startsWith('### A3.'));
  const end = lines.findIndex((line) => line.startsWith('### A4.'));
  if (start < 0 || end < start) {
    throw new Error(
      `${ROLES_SPEC} has no '### A3.' … '### A4.' span, so this instrument read nothing. The `
        + 'section was renumbered or renamed — follow it here and in '
        + '`SeedDataTest.matrixFromTheSpec`, which bounds the same region.',
    );
  }

  const rows: MatrixRow[] = [];
  let headings: string[] = [];
  for (const line of lines.slice(start, end)) {
    if (!line.startsWith('|')) { headings = []; continue; }

    const cells = line.split('|').slice(1, -1).map((cell) => cell.trim());
    if (cells[0] === 'Permission') { headings = cells; continue; }
    // The `|---|:--:|` separator under every header, and any `|`-led line before one was seen.
    if (headings.length === 0 || cells.every((cell) => /^:?-+:?$/.test(cell))) continue;

    const keys = [...(cells[0] ?? '').matchAll(KEY)].map((match) => match[1] as string);
    if (keys.length === 0) {
      throw new Error(
        `${ROLES_SPEC} §A3 row '${cells[0]}' names no backticked permission key. The row shape `
          + 'changed, and a parse that skips a row reads as agreement about every cell in it.',
      );
    }

    const grants = new Map<SpecRole, string>();
    headings.forEach((heading, column) => {
      if (column === 0 || heading === PROSE_COLUMN) return;
      if (!(SPEC_ROLES as readonly string[]).includes(heading)) {
        throw new Error(
          `${ROLES_SPEC} §A3 has a column headed '${heading}' and this instrument does not know `
            + 'which role that is. Add it to SPEC_ROLES — and to `SeedDataTest.SPEC_COLUMNS`, '
            + 'which needs the seeded role name for the same column — or, if a seventh tier '
            + 'really arrived, take that decision first (spec §A2).',
        );
      }
      grants.set(heading as SpecRole, cells[column] ?? NOT_GRANTED);
    });
    rows.push({ keys, cells: grants, project: COLUMNS.every((role) => grants.has(role)) });
  }
  return rows;
}

/**
 * The whole catalogue as §A3 prints it — key by key, column by column, cell text kept.
 *
 * The text rather than a boolean, because a qualifier narrows a grant without removing it
 * (`quarantine only`, Automation Service's `case:create` — the one live instance) and a copy that
 * says `plan scope only` where the spec says `✓` is wrong in the way `V52` had to repair by hand.
 * A role a table has no column for gets no entry: `company:read` is answered for `IA`, `CA` and
 * `Every member` and by nobody else, which is the shape of the document and not a gap in it.
 */
export function specMatrix(): Map<string, Map<SpecRole, string>> {
  const matrix = new Map<string, Map<SpecRole, string>>();
  for (const row of specRows()) {
    for (const key of row.keys) {
      if (matrix.has(key)) {
        throw new Error(
          `${ROLES_SPEC} §A3 names \`${key}\` in two tables. A key is answered by exactly one `
            + 'scope, so one of the two rows is a stray copy — and merging them here would let a '
            + 'copy of the matrix agree with whichever half suited it.',
        );
      }
      matrix.set(key, row.cells);
    }
  }

  if (matrix.size !== CATALOGUE_SIZE) {
    throw new Error(
      `${ROLES_SPEC} §A3 parsed as ${matrix.size} keys and the catalogue is ${CATALOGUE_SIZE}. `
        + 'Either a table stopped matching this parse, or a migration really did add a key — in '
        + 'which case `SeedDataTest.catalogueMatchesTheContract` moves first, because it is the '
        + 'one that reads the seeded rows.',
    );
  }
  return matrix;
}

/**
 * The `PROJECT`-scope matrix, role by role.
 *
 * `COMPANY` and `SYSTEM` scope are deliberately outside it. Their tables have different columns
 * (`Every member`, and Instance Admin alone), and no sentence in the population this instrument was
 * built for is about them: what the prose gets wrong is the contributor line, which is a
 * `PROJECT`-scope question by construction.
 */
export function roleMatrix(): Record<Role, Set<string>> {
  const held = Object.fromEntries(COLUMNS.map((role) => [role, new Set<string>()])) as
    Record<Role, Set<string>>;

  for (const row of specRows()) {
    if (!row.project) continue;
    for (const role of COLUMNS) {
      // Anything that is not the em dash is a grant — `✓`, or a qualifier that narrows one.
      if ((row.cells.get(role) ?? NOT_GRANTED) === NOT_GRANTED) continue;
      for (const key of row.keys) held[role].add(key);
    }
  }

  if (held.TE.size < 25 || held.TL.size < 30 || held.V.size < 5) {
    throw new Error(
      `${ROLES_SPEC} §A3 parsed as TL=${held.TL.size} TE=${held.TE.size} V=${held.V.size} keys, `
        + 'which is too few to be the matrix. Either the tables changed shape or a `PROJECT` table '
        + `stopped carrying all six of [${COLUMNS.join(', ')}], which is what marks one.`,
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

/**
 * What a `tcms_` bearer can reach, derived from the same tables.
 *
 * Roles spec §A6a: the `Agent` role is **Test Engineer entire, minus `company:read`** (`V52`, §210),
 * and `SeedDataTest.agentIsTestEngineerMinusNothingItCouldUse` derives that delta from the live
 * catalogue rather than restating it. So the `PROJECT`-scope answer is Test Engineer's row exactly —
 * `company:read` is a `COMPANY`-scope key and {@link roleMatrix} parses `PROJECT` scope alone.
 *
 * The subtraction is therefore invisible here, which is precisely the way this could go wrong
 * later: if §A3's `COMPANY` table ever grew the six `PROJECT` columns, its rows would parse into
 * `TE` and the Agent would silently widen by a key no token can answer for — a credential with no
 * membership row has no `V15` baseline, and `evaluateCompanyWide` reads the SYSTEM and COMPANY terms
 * only. The guard below is that case, stated rather than trusted.
 */
export function agentKeys(): readonly string[] {
  const engineer = roleMatrix().TE;
  if (engineer.has('company:read')) {
    throw new Error(
      `${ROLES_SPEC} §A3's PROJECT matrix parsed with 'company:read' in the Test Engineer column, `
        + 'and that key is COMPANY scope. Either a COMPANY-scope table grew the six PROJECT '
        + 'columns and is now being read as one, or the grant genuinely moved — in which case §A6a '
        + "and `SeedDataTest.agentIsTestEngineerMinusNothingItCouldUse` move first, because a token "
        + 'holding it still cannot answer for it.',
    );
  }
  return [...engineer];
}
