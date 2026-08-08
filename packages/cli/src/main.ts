#!/usr/bin/env node
/**
 * The CLI entry point: parse, dispatch, and turn a failure into an exit code and one sentence.
 *
 * Argument parsing is Node's own `util.parseArgs`, so this package has **no dependencies beyond
 * `@tcms/client`**. That is worth a line: it is published as `npx @tcms/cli`, which means every
 * dependency is something a user's CI fetches and executes on the strength of this repo's say-so,
 * and the argument parser is the one place where taking a package would buy the least.
 */
import { parseArgs } from 'node:util';

import { TcmsApiError, TcmsClientError, TcmsTimeoutError, isAbort } from '@tcms/client';

import { COMMANDS, UsageError, findCommand } from './commands.js';
import { connect, requestOptions } from './config.js';
import { consoleOutput, type Output } from './output.js';

/**
 * Exit codes, because a CLI's callers are scripts.
 *
 * `2` for a usage mistake is kept apart from `1` for a refusal on purpose: a CI step that typed a
 * flag wrong and a CI step that was denied permission need different reactions, and collapsing
 * them means the pipeline retries the one that will never succeed.
 */
export const EXIT = { ok: 0, failed: 1, usage: 2, cancelled: 130 } as const;

const GLOBAL_OPTIONS = {
  project: { type: 'string' },
  json: { type: 'boolean', default: false },
  url: { type: 'string' },
  timeout: { type: 'string' },
  help: { type: 'boolean', default: false },
} as const;

export async function main(
  argv: readonly string[],
  out: Output = consoleOutput(),
  env: NodeJS.ProcessEnv = process.env,
): Promise<number> {
  if (argv.length === 0 || argv[0] === '--help' || argv[0] === '-h' || argv[0] === 'help') {
    usage(out);
    return argv.length === 0 ? EXIT.usage : EXIT.ok;
  }

  const found = findCommand(argv);
  if (!found) {
    out.error(`Unknown command: ${argv.join(' ')}`);
    out.error("Run 'tcms help' for the list.");
    return EXIT.usage;
  }

  let parsed;
  try {
    parsed = parseArgs({
      args: [...found.rest],
      /*
        The command's own flags are merged in, and the parser runs **strict**. Both halves are
        §139 corrections of a defect §137 shipped: `strict: false` was chosen so a command's
        flags would parse "without every one being declared here", and that is not what it does
        — an *undeclared* option gets the value `true` and its argument becomes a positional, so
        `case list --suite TS-7` filtered by nothing and said so nowhere. Declaring them makes
        the parse right; strict mode then makes a misspelling a usage error rather than a
        boolean nobody reads.
      */
      options: { ...GLOBAL_OPTIONS, ...found.command.options },
      strict: true,
      allowPositionals: true,
    });
  } catch (error) {
    out.error(error instanceof Error ? error.message : String(error));
    return EXIT.usage;
  }

  const values = parsed.values as Record<string, string | boolean | (string | boolean)[] | undefined>;

  if (values['help'] === true) {
    out.line(found.command.usage);
    out.line(found.command.summary);
    return EXIT.ok;
  }

  const timeout = typeof values['timeout'] === 'string' ? Number(values['timeout']) : undefined;
  if (timeout !== undefined && (!Number.isFinite(timeout) || timeout <= 0)) {
    out.error('--timeout takes a number of seconds.');
    return EXIT.usage;
  }

  try {
    const client = connect(
      {
        url: typeof values['url'] === 'string' ? values['url'] : undefined,
        timeout,
      },
      env,
    );

    await found.command.run({
      client,
      out,
      project: typeof values['project'] === 'string' ? values['project'] : undefined,
      json: values['json'] === true,
      args: parsed.positionals,
      options: values,
      request: requestOptions({ timeout }),
    });

    return EXIT.ok;
  } catch (error) {
    return report(error, out, found.command.usage);
  }
}

/**
 * One failure, one sentence, one exit code.
 *
 * The ordering matters. A cancellation is **not a failure** and must not be reported as one —
 * the SPA's client records the same rule for the same reason — so it is checked before anything
 * that would print. A `TcmsApiError` prints the server's own `detail` rather than a sentence
 * composed here, because the server is the only party that knows why it refused; the field
 * errors are printed under it so a caller can see which input was rejected.
 */
function report(error: unknown, out: Output, usageLine: string): number {
  if (isAbort(error)) return EXIT.cancelled;

  if (error instanceof UsageError) {
    out.error(error.message);
    out.error(usageLine);
    return EXIT.usage;
  }

  if (error instanceof TcmsApiError) {
    out.error(error.detail);
    if (error.fieldErrors) {
      for (const [field, messages] of Object.entries(error.fieldErrors)) {
        for (const message of messages) out.error(`  ${field}: ${message}`);
      }
    }
    // Printed only when the server sent one: it is the handle a support conversation uses, and
    // an invented or absent id printed as "none" is noise on every other line.
    if (error.correlationId) out.error(`  correlation id: ${error.correlationId}`);
    return EXIT.failed;
  }

  if (error instanceof TcmsTimeoutError || error instanceof TcmsClientError) {
    out.error(error.message);
    return EXIT.failed;
  }

  out.error(error instanceof Error ? error.message : String(error));
  return EXIT.failed;
}

function usage(out: Output): void {
  out.line('tcms — the TCMS command line client');
  out.line();
  out.line('Usage: tcms <noun> <verb> [options]');
  out.line();
  // Reads and writes are listed apart, and it is not cosmetic: this list is the first thing a
  // person or an agent sees, and "which of these changes the instance" is the question they are
  // actually asking. The width is measured rather than guessed — `review comment` is 14
  // characters and a hardcoded 14 left it touching its own summary.
  const width = Math.max(...COMMANDS.map((command) => command.name.length)) + 2;

  out.line('Commands:');
  for (const command of COMMANDS) {
    if (command.write) continue;
    out.line(`  ${command.name.padEnd(width)} ${command.summary}`);
  }

  out.line();
  out.line('Commands that change something:');
  for (const command of COMMANDS) {
    if (!command.write) continue;
    out.line(`  ${command.name.padEnd(width)} ${command.summary}`);
  }
  out.line();
  out.line('Global options:');
  out.line('  --project KEY   the project to act in (needed when the token can see several)');
  out.line('  --json          print the API response verbatim');
  out.line('  --url URL       the TCMS instance, overriding TCMS_URL');
  out.line('  --timeout SECS  override the request deadline');
  out.line();
  out.line('Environment:');
  out.line('  TCMS_URL        the instance origin, e.g. https://tcms.example.com');
  out.line('  TCMS_TOKEN      an API token from Settings → API tokens');
}

// `import.meta.main` is Node 24+; this package targets 22.12, so the entry check is the older
// spelling. Guarded at all so `main` can be imported by the tests without running.
if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  main(process.argv.slice(2))
    .then((code) => { process.exitCode = code; })
    .catch((error: unknown) => {
      process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = EXIT.failed;
    });
}
