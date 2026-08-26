/**
 * `tcms format`, and the two `case edit` halves that are now rendered rather than written out.
 *
 * The interesting assertions are the ones about **what is NOT printed**. A help screen that names
 * an option the command does not take is worse than no help screen: it is a command a person
 * types, gets a usage error from, and does not trust again. Two of those were live in the first
 * draft of this command — `--title` on `case propose`, which takes only `--file`, and `[required]`
 * on `case edit`, where nothing is.
 */
import { describe, expect, it } from 'vitest';

import { CASE_SHAPE, SHAPES, fieldsFor, flagOf, optionSpecFor } from '@tcms/client';

import { findCommand } from './commands.js';
import { EXIT, main } from './main.js';
import { recorder } from './test/harness.js';

/** No `fetch` override and no environment: the point of the command is that it needs neither. */
async function format(...argv: string[]): Promise<{ code: number; text: string }> {
  const out = recorder();
  const code = await main(argv, out, {});
  return { code, text: `${out.stdout}\n${out.stderr}` };
}

describe('tcms format', () => {
  it('runs with no URL and no token, which is the whole reason it is declared offline', async () => {
    // Every other command fails here, correctly. The person who most needs the format is the one
    // who has not got credentials yet.
    const { code, text } = await format('format');
    expect(code).toBe(EXIT.ok);
    expect(text).not.toContain('No TCMS instance');
    expect(text).toContain('case — Test case');
  });

  it('says which adapter each action is on', async () => {
    const { text } = await format('format');
    // `submit` and `delete` are MCP-only. A person who cannot find `tcms case delete` should be
    // able to see that it is not there rather than conclude they typed it wrong.
    expect(text).toMatch(/propose\s+case:create\s+cli\+mcp/);
    expect(text).toMatch(/delete\s+case:delete\s+mcp$/m);
  });

  it('spells propose as body keys and edit as flags, and never the other way round', async () => {
    const { text } = await format('format', 'case');

    expect(text).toContain('Test case — propose (JSON keys in the --file body)');
    expect(text).toContain('Test case — edit (flags)');

    // `case propose` takes `--file`, `--verdict` and `--idempotency-key`. It does not take
    // `--title`, and a listing that offered one would be naming an option that does not exist.
    const propose = text.slice(text.indexOf('— propose'), text.indexOf('— edit'));
    expect(propose).not.toContain('--title');
    expect(propose).toContain('\n  title\n');
    // `verdict` rides the wrapper, not the case body, so it stays a flag even here.
    expect(propose).toContain('Flags beside --file:');
    expect(propose).toContain('--verdict');
  });

  it('does not mark anything required on the edit, because nothing is', async () => {
    const { text } = await format('format', 'case');
    const edit = text.slice(text.indexOf('— edit'));

    // The PATCH is not partial, so both adapters read the row and send back what they did not
    // change. Printing `[required]` here would make a person pass a field to leave it alone.
    expect(edit).not.toContain('[required]');
    expect(edit).toContain('Nothing here is required');
  });

  it('answers --json with a template that is only the fields an accept refuses without', async () => {
    const { text } = await format('format', 'case', '--json');
    expect(Object.keys(JSON.parse(text)).sort())
      .toEqual(['automationStatus', 'priority', 'stepFormat', 'title']);
  });

  it('names what exists when the entity is not one', async () => {
    const { code, text } = await format('format', 'testcase');
    expect(code).toBe(EXIT.usage);
    expect(text).toContain('case');
  });

  it('carries the pitfalls under --long and not otherwise', async () => {
    const short = await format('format', 'case');
    const long = await format('format', 'case', '--long');

    expect(long.text).toContain('ATTACHMENT ids');
    // Four screens of prose by default would train people to pipe this through `head`.
    expect(short.text).not.toContain('ATTACHMENT ids');
  });
});

describe('case edit, rendered from the catalogue', () => {
  it('takes every flag the catalogue declares, plus --file', () => {
    const command = findCommand(['case', 'edit'])!.command;
    const expected = { ...optionSpecFor(CASE_SHAPE, 'edit'), file: { type: 'string' } };
    expect(command.options).toEqual(expected);
  });

  it('gained --not-automatable-reason, which the hand-written list was missing', async () => {
    /*
      The flag was absent while `--automation-status NOT_AUTOMATABLE` was accepted, so the one
      spelling the server refuses was the only one this command could produce. Nothing found it
      because the option list and the body builder were two hand-maintained places and no check
      compared either to the field set.
    */
    const command = findCommand(['case', 'edit'])!.command;
    expect(Object.keys(command.options ?? {})).toContain('not-automatable-reason');
    expect(Object.keys(command.options ?? {})).toContain('automation-key');
  });

  it('derives its lock-version from the kind it declares, not from a list', () => {
    // Every `edit` in this registry takes `--lock-version` and no `state` or `delete` does —
    // surface spec §4.3 restated. `mechanicsFor` is where that stopped being a convention.
    const edit = findCommand(['case', 'edit'])!.command;
    expect(Object.keys(edit.options ?? {})).toContain('lock-version');
    expect(Object.keys(edit.options ?? {})).not.toContain('idempotency-key');
  });
});

/**
 * The catalogue against the COMMANDS — the CLI half of the same pin `mcp/src/writes.test.ts` runs.
 *
 * §253 wired `optionSpecFor` into exactly one command (`case edit`) and left fifteen hand-written,
 * with nothing comparing either to the field set. Three disagreements were live and invisible when
 * this was written: `tcms format plan` printed `--milestone-name` where the command takes
 * `--milestone`, the same for a run's `--release-version` / `--build-number` / `--commit-sha`, and
 * `doc edit` had no `--parent` at all while `tcms_write_doc_page` accepted one. The first two are
 * worse than they look — a flag printed by the surface's own help screen and refused by
 * `parseArgs` in strict mode is a usage error the user was walked into.
 */
describe('the shape catalogue against the commands that declare it', () => {
  const COMMAND_FOR: Record<string, string> = {
    'case.propose': 'case propose',
    'case.edit': 'case edit',
    'docPage.create': 'doc create',
    'docPage.edit': 'doc edit',
    'plan.create': 'plan create',
    'plan.edit': 'plan edit',
    'result.record': 'result record',
    'run.create': 'run create',
    'suite.create': 'suite create',
    'suite.edit': 'suite edit',
  };

  it('gives every field a flag the command actually takes', () => {
    const gaps: string[] = [];

    for (const [entity, shape] of Object.entries(SHAPES)) {
      for (const action of shape.actions) {
        if (!action.cli || (action.kind !== 'create' && action.kind !== 'edit')) continue;

        const name = COMMAND_FOR[`${entity}.${action.name}`];
        expect(name, `no command is mapped for ${entity}.${action.name}`).toBeDefined();
        const command = findCommand(name!.split(' '))!.command;
        const declared = Object.keys(command.options ?? {});

        /*
          A `file` action carries its body in `--file`, so only its WRAPPER fields are flags —
          `case propose`'s `verdict` rides beside the `TestCaseInput` rather than inside it. Asking
          for the body fields there would demand seventeen flags on a command that deliberately
          has one.
        */
        const scope = action.cliSpelling === 'file' ? 'wrapper' : 'all';
        for (const field of fieldsFor(shape, action.name, scope)) {
          // argv cannot carry an object array at all; `--file` is how those are sent.
          if (field.type === 'object[]') continue;
          for (const flag of [flagOf(field), ...(field.cli?.negate ? [field.cli.negate] : [])]) {
            if (!declared.includes(flag)) gaps.push(`${name} does not take --${flag}`);
          }
        }
      }
    }

    expect(gaps).toEqual([]);
  });

  it('maps every body-carrying action the catalogue says this surface has', () => {
    const declared = Object.entries(SHAPES).flatMap(([entity, shape]) =>
      shape.actions
        .filter((action) => action.cli && (action.kind === 'create' || action.kind === 'edit'))
        .map((action) => `${entity}.${action.name}`));

    expect(declared.sort()).toEqual(Object.keys(COMMAND_FOR).sort());
  });
});
