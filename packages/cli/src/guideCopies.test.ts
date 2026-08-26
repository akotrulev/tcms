/**
 * The shipped user guide against the command registry — `mcp/src/guideCopies.test.ts`'s other half.
 *
 * The same defect ran in both lists at once: `tcms format` shipped in §253 and the guide's Reading
 * block did not gain it, so the command built to answer *what must a payload look like* was
 * undiscoverable from the page that exists to answer that question.
 *
 * Only the `tcms …` lines of the guide's two terminal blocks are read. A command mentioned in
 * running prose is a mention, not a listing, and demanding that every sentence enumerate the
 * registry would make the check a check on the writing.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { COMMANDS } from './commands.js';

const GUIDE = join(
  dirname(fileURLToPath(import.meta.url)),
  '../../../frontend/src/features/help/sections.tsx',
);

/**
 * Every command name a `tcms …` line lists.
 *
 * The lines carry three shapes — a bare invocation, one with arguments and a `#` comment, and
 * several alternatives joined by `|` (`tcms suite create | suite edit | suite status`). All three
 * are a listing, so the parse handles all three rather than the guide being rewritten to suit it.
 */
function listed(text: string): Set<string> {
  const names = new Set<string>();

  for (const line of text.split('\n')) {
    // Not anchored at the start of the line: the first line of each block carries the JSX that
    // opens the template literal (`<Terminal>{\`tcms whoami …`), so an anchored match silently
    // skipped the first command of every list — which is `whoami` and `case edit`.
    const match = /\btcms ([a-z].*)$/.exec(line.trim());
    if (!match) continue;
    const body = (match[1] ?? '').split('#')[0] ?? '';

    for (const alternative of body.split('|')) {
      const words = alternative.trim().split(/\s+/).filter(Boolean);
      // A name is one or two words; anything after them is an argument or a flag.
      for (const length of [2, 1]) {
        const candidate = words.slice(0, length).join(' ');
        if (COMMANDS.some((command) => command.name === candidate)) {
          names.add(candidate);
          break;
        }
      }
    }
  }

  return names;
}

describe('the shipped user guide against the command registry', () => {
  const text = readFileSync(GUIDE, 'utf8');
  const named = listed(text);

  it('lists every command the CLI has', () => {
    const missing = COMMANDS.map((command) => command.name).filter((name) => !named.has(name));
    expect(missing, 'the guide does not list these, so a user never learns they exist').toEqual([]);
  });

  it('documents the exit codes the CLI can actually return', () => {
    // A `tcms` line the parse could not resolve to a command would leave `named` short and the
    // check above would report it — but a table of exit codes has no such anchor, and an
    // undocumented code is one a script cannot branch on. See `EXIT`.
    for (const code of ['3 not authenticated', '4 permission denied', '7 rate limited']) {
      expect(text, `the exit-code table should carry '${code}'`).toContain(code);
    }
  });
});
