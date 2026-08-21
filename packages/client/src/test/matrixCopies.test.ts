/**
 * The two convenience copies of the permission matrix, compared to the document they copy.
 *
 * ## The population, and why it is a guard rather than a repair
 *
 * `docs/specs/permission-matrix-summary.md` and `README.md` each print §A3's matrix, and each says
 * in its own words that the spec is authoritative and the copy is the bug. Nothing compared either
 * of them to anything —
 * §214 said so in the file it was closing, and named this as the nearest standing candidate.
 * Measured first: **the population is zero.** Both copies agree with the spec on all
 * 55 keys and every cell, qualifier text included. So this is a guard in §201's and
 * §204's sense — a way the documents could disagree is removed, rather than a disagreement
 * repaired — and saying that plainly is the point of measuring before writing.
 *
 * What the emptiness does not mean is that the copies are cheap to keep right. Three migrations
 * have moved grants since the copies were written (`V40`, `V51`, `V52`), each of them edited both
 * copies by hand, and the matrix file's own header calls a moved grant *"the one this table gets wrong
 * most easily"*. A copy that survives three edits by luck is still unchecked.
 *
 * ## Why here and not in `SeedDataTest`
 *
 * §214 built the parser one stack over and observed it would be cheap to point at the copies. It
 * would — and the backend suite is the one `ci.yml` does not run, which §214's own "what is NOT
 * done" calls the largest thing on its list. A rule about two *documents* needs no database, so
 * putting it in the backend suite would buy a Testcontainers boot and a nightly-only observer for
 * a comparison of two files on disk. The chain stays as both files describe it:
 * `migration → SeedDataTest → spec → the rules in this package`, and this is a third link off the
 * spec, beside `packages/client/src/test/tierClaims.test.ts` and the MCP surface `agentKeys()`
 * derives, in the job that runs on every push.
 *
 * The matrix file and `README.md` are not in `corpus.ts`'s `CORPUS` and are deliberately not added. That
 * list is the *prose* four rules sweep; this reads two named documents for one table each, and a
 * rule that walked every markdown file looking for a permission matrix would find the handoff
 * narrative's quotations of one.
 *
 * ## What is compared
 *
 * Every cell, both ways, over all three scopes — and the **text** of the cell, not whether it is a
 * grant. A qualifier narrows a grant without removing it, so a copy reading `plan scope only` where
 * the spec reads `✓` is wrong in exactly the way `V52` had to repair by hand in both files. The key
 * set is compared too, both ways: a copy that quietly drops a row is the way a matrix goes stale
 * that reads, to somebody scanning it, like a matrix.
 *
 * The copies are laid out differently from the spec and from each other, and none of that is
 * compared. §A3 groups by subject area under `#### ` headings with abbreviated columns
 * (`IA`/`CA`/…); the copies group by how widely a permission is held, spell the roles out, and
 * print them in the opposite order; the matrix file packs several keys into one row with `·` and
 * writes the `SYSTEM` scope as a sentence rather than a table. All three are the same claim in
 * different shapes, and the shape is the copy's business — what it may not do is disagree.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { REPO } from './corpus.js';
import { SPEC_ROLES, specMatrix, type SpecRole } from './roleMatrix.js';

/** How a copy spells each of §A3's column headings. `Every member` is spelled the same way. */
const COPY_COLUMNS: Record<string, SpecRole> = {
  'Instance Admin': 'IA',
  'Company Admin': 'CA',
  'Test Lead': 'TL',
  'Test Engineer': 'TE',
  'Automation Service': 'AS',
  Viewer: 'V',
  'Every member': 'Every member',
};

/**
 * A row a copy writes as a sentence instead of a table.
 *
 * One instance: the matrix file prints `SYSTEM` scope as a single line, because seven keys held by
 * one role is a list and a three-column table of ✓ would be worse to read. It is still a copy of
 * the table §A3 prints, and leaving it out would leave seven of the 55 keys uncompared — so the
 * sentence is declared here rather than excused.
 */
interface ProseRow {
  find: RegExp;
  roles: SpecRole[];
}

interface Copy {
  path: string;
  /** The heading the matrix lives under. The section ends at the next heading of the same depth. */
  heading: RegExp;
  prose: ProseRow[];
}

const COPIES: Copy[] = [
  {
    path: 'docs/specs/permission-matrix-summary.md',
    heading: /^(#{1,6}) Permission matrix — convenience copy\s*$/,
    prose: [{ find: /`SYSTEM` scope, Instance Admin only:([^.]*)\./, roles: ['IA'] }],
  },
  {
    path: 'README.md',
    heading: /^(#{1,6}) Permission matrix\s*$/,
    prose: [],
  },
];

const KEY = /`([a-z]+:[a-z]+)`/g;

/** §A3's spelling of a grant, which is what a prose row's keys are being given. */
const GRANTED = '✓';

/** The section of a copy the matrix lives in, bounded by its own heading depth. */
function matrixSection(copy: Copy): string[] {
  const lines = readFileSync(join(REPO, copy.path), 'utf8').split('\n');
  const start = lines.findIndex((line) => copy.heading.test(line));
  if (start < 0) {
    throw new Error(
      `${copy.path} has no heading matching ${copy.heading.source}, so this rule read no matrix at `
        + 'all. The section was renamed — follow it here. Deleting the copy is also an answer, and '
        + 'then this entry goes with it.',
    );
  }
  const depth = (copy.heading.exec(lines[start] as string)?.[1] ?? '#').length;
  const rest = lines.slice(start + 1);
  const end = rest.findIndex((line) => {
    const heading = /^(#{1,6}) /.exec(line);
    return heading !== null && (heading[1] as string).length <= depth;
  });
  return end < 0 ? rest : rest.slice(0, end);
}

/**
 * A copy's matrix, in {@link specMatrix}'s shape.
 *
 * Keyed by column heading for the same reason the spec parse is: the copies print the roles in the
 * reverse order and one of them merges keys into a row, so position says nothing. An unrecognised
 * heading is a hard failure here too — a copy that grew a seventh column would otherwise be
 * compared on six of them and read as agreeing.
 */
function copyMatrix(copy: Copy): Map<string, Map<SpecRole, string>> {
  const section = matrixSection(copy);
  const matrix = new Map<string, Map<SpecRole, string>>();

  let headings: string[] = [];
  for (const line of section) {
    if (!line.startsWith('|')) { headings = []; continue; }

    const cells = line.split('|').slice(1, -1).map((cell) => cell.trim());
    if (cells[0] === 'Permission') { headings = cells; continue; }
    if (headings.length === 0 || cells.every((cell) => /^:?-+:?$/.test(cell))) continue;

    const grants = new Map<SpecRole, string>();
    headings.forEach((heading, column) => {
      if (column === 0) return;
      const role = COPY_COLUMNS[heading];
      if (role === undefined) {
        throw new Error(
          `${copy.path}'s matrix has a column headed '${heading}' and this rule does not know `
            + `which of [${SPEC_ROLES.join(', ')}] that is. A column nobody compares reads exactly `
            + 'like agreement — add it to COPY_COLUMNS, or take the decision the new tier needs '
            + 'first (roles spec §A2).',
        );
      }
      grants.set(role, cells[column] ?? '');
    });
    for (const key of [...(cells[0] ?? '').matchAll(KEY)].map((match) => match[1] as string)) {
      matrix.set(key, grants);
    }
  }

  const text = section.join('\n');
  for (const row of copy.prose) {
    const sentence = row.find.exec(text)?.[1];
    if (sentence === undefined) {
      throw new Error(
        `${copy.path} no longer says ${row.find.source}, which is where this rule reads the keys `
          + 'that copy writes as a sentence rather than a table. If it became a table, drop the '
          + 'entry — the table parse above already covers it.',
      );
    }
    const grants = new Map<SpecRole, string>(row.roles.map((role) => [role, GRANTED]));
    for (const key of [...sentence.matchAll(KEY)].map((match) => match[1] as string)) {
      matrix.set(key, grants);
    }
  }

  return matrix;
}

describe('the convenience copies of the permission matrix', () => {
  const spec = specMatrix();

  it.each(COPIES.map((copy) => [copy.path, copy] as const))(
    '%s prints §A3 and nothing else',
    (path, copy) => {
      const held = copyMatrix(copy);

      const missing = [...spec.keys()].filter((key) => !held.has(key)).sort();
      const extra = [...held.keys()].filter((key) => !spec.has(key)).sort();

      expect(missing.join(', '), `${missing.length} permissions §A3 declares that ${path}'s copy `
        + 'does not print. A dropped row reads like a permission that does not exist, to the '
        + `reader most likely to be relying on the copy: ${missing.join(', ')}`).toBe('');
      expect(extra.join(', '), `${extra.length} permissions ${path}'s copy prints that §A3 does `
        + `not declare: ${extra.join(', ')}. The spec wins — either the key was removed there and `
        + 'not here, or this copy invented it.').toBe('');

      const disagreements: string[] = [];
      for (const [key, cells] of spec) {
        for (const role of SPEC_ROLES) {
          const declared = cells.get(role);
          const printed = held.get(key)?.get(role);
          if (declared === printed) continue;
          disagreements.push(
            `  ${key} / ${role}: §A3 says ${declared ?? '(no column)'} `
            + `and ${path} says ${printed ?? '(no column)'}`,
          );
        }
      }

      expect(disagreements.join('\n'), `${disagreements.length} cells where ${path}'s copy of the `
        + `matrix and roles spec §A3 disagree:\n\n${disagreements.join('\n')}\n\n`
        + 'The spec is authoritative — both copies say so themselves — so the repair is normally '
        + 'the copy. If the grant genuinely moved, it moved in a migration, and '
        + '`SeedDataTest.theSeededMatrixIsTheSpecsMatrix` is what settles the spec against the '
        + 'rows Postgres holds. `(no column)` on one side means the key sits under a different '
        + 'scope in the two documents, which is a bigger disagreement than a cell.').toBe('');
    },
  );

  it('reads a matrix out of both copies', () => {
    // The vacuity guard, per copy rather than over the total: a section that stopped matching
    // contributes nothing, and nothing compared to a complete spec looks like two clean documents.
    // `specMatrix` throws rather than answering thinly, so the spec side needs no floor here.
    for (const copy of COPIES) {
      expect(copyMatrix(copy).size, `${copy.path} contributed no rows`).toBe(spec.size);
    }
    expect(spec.get('case:create')?.get('AS'), 'the one live qualifier').toBe('quarantine only');
  });
});
