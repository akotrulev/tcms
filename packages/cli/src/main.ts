#!/usr/bin/env node
/**
 * The CLI entry point: parse, dispatch, and turn a failure into an exit code and one sentence.
 *
 * Argument parsing is Node's own `util.parseArgs`, so this package has **no dependencies beyond
 * `@tcms/client`**. That is worth a line: ~~it is published as `npx @tcms/cli`~~ — it is served by
 * the instance as a tarball that VENDORS its whole dependency closure (2026-08-23,
 * `docs/specs/client-distribution.md`), which makes the argument stronger rather than weaker. Every
 * dependency is bytes this repository ships to a user and their agent executes on our say-so, and
 * they no longer float a patch version at install time either. The argument parser is the one place
 * where taking a package would buy the least.
 */
import { parseArgs } from 'node:util';

import {
  describeFailure,
  isProcessEntryPoint,
  type FailureCode,
  type TcmsClient,
} from '@tcms/client';

import { COMMANDS, UsageError, findCommand } from './commands.js';
import { CLI_VERSION, connect, requestOptions } from './config.js';
import { consoleOutput, type Output } from './output.js';
import { type Command } from './context.js';

/**
 * Exit codes, because a CLI's callers are scripts.
 *
 * `2` for a usage mistake is kept apart from `1` for a refusal on purpose: a CI step that typed a
 * flag wrong and a CI step that was denied permission need different reactions, and collapsing
 * them means the pipeline retries the one that will never succeed.
 *
 * ~~Four codes.~~ → **ten**, because four made exactly the mistake that argument warns about one
 * level up: 403, 404, 409, 429, every 5xx and every timeout all returned `1`, so a pipeline could
 * not tell *"rate limited, back off"* from *"this token will never be allowed to do this"* without
 * parsing stderr. The table below is the one thing a caller can branch on without reading English,
 * which is the same reason the MCP side grew `structuredContent`. Both are rendered from the one
 * classifier in the core (`client/src/core/outcome.ts`).
 */
export const EXIT = {
  ok: 0,
  /** Something went wrong that none of the codes below name. Not retryable — see `FailureCode`. */
  failed: 1,
  /** The command was typed wrong, or the server rejected the payload as malformed. */
  usage: 2,
  unauthenticated: 3,
  denied: 4,
  notFound: 5,
  conflict: 6,
  rateLimited: 7,
  timedOut: 8,
  unavailable: 9,
  cancelled: 130,
} as const;

/**
 * The core's closed vocabulary, rendered as this surface's.
 *
 * `satisfies Record<FailureCode, number>` is the point: a code added to the core is a compile
 * error here until it has been given a number, rather than silently collapsing into `failed`.
 */
const EXIT_FOR = {
  cancelled: EXIT.cancelled,
  unauthenticated: EXIT.unauthenticated,
  forbidden: EXIT.denied,
  not_found: EXIT.notFound,
  conflict: EXIT.conflict,
  // A payload the server rejected is a usage mistake found one hop later than a mistyped flag.
  invalid_input: EXIT.usage,
  rate_limited: EXIT.rateLimited,
  timeout: EXIT.timedOut,
  unavailable: EXIT.unavailable,
  failed: EXIT.failed,
} satisfies Record<FailureCode, number>;

const GLOBAL_OPTIONS = {
  project: { type: 'string' },
  json: { type: 'boolean', default: false },
  url: { type: 'string' },
  timeout: { type: 'string' },
  help: { type: 'boolean', default: false },
  version: { type: 'boolean', default: false },
  /*
    Suppresses the human rendering and leaves the refusals, which still go to stderr. There is no
    `--verbose` beside it: this client has no second level of detail to print, and a flag that
    accepts a word and changes nothing is worse than the absence of one.
  */
  quiet: { type: 'boolean', default: false },
  /** Skips the confirmation on a command that removes something. See {@link Confirm}. */
  yes: { type: 'boolean', default: false },
  /** Answers what a write would do and sends nothing. */
  'dry-run': { type: 'boolean', default: false },
} as const;

/**
 * How a destructive command asks.
 *
 * Injectable for the same reason {@link Output} is — a test asserts on the answer rather than on a
 * mock of the standard library — and the default is deliberately strict: with no TTY there is
 * nobody to ask, so it **refuses** rather than proceeding. The MCP side made the same call and
 * argued it (`server.ts`: *"a consent gate that silently degrades to 'no prompt available, carry
 * on' is not a gate"*), and until this existed the same credential deleted silently from a
 * terminal while being gated through a host.
 */
export type Confirm = (question: string) => Promise<boolean>;

const askOnTty: Confirm = async (question) => {
  if (!process.stdin.isTTY) return false;
  const { createInterface } = await import('node:readline/promises');
  const rl = createInterface({ input: process.stdin, output: process.stderr });
  try {
    const answer = await rl.question(`${question} [y/N] `);
    return /^y(es)?$/i.test(answer.trim());
  } finally {
    rl.close();
  }
};

export async function main(
  argv: readonly string[],
  out: Output = consoleOutput(),
  env: NodeJS.ProcessEnv = process.env,
  confirm: Confirm = askOnTty,
): Promise<number> {
  if (argv[0] === '--version' || argv[0] === '-V') {
    out.line(CLI_VERSION);
    return EXIT.ok;
  }

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

  const sink = values['quiet'] === true ? quieted(out) : out;

  const timeout = typeof values['timeout'] === 'string' ? Number(values['timeout']) : undefined;
  if (timeout !== undefined && (!Number.isFinite(timeout) || timeout <= 0)) {
    out.error('--timeout takes a number of seconds.');
    return EXIT.usage;
  }

  const write = found.command.write;

  if (values['dry-run'] === true) {
    if (!write) {
      out.error('--dry-run is only meaningful on a command that changes something.');
      return EXIT.usage;
    }
    /*
      A dry run stops at dispatch, so its guarantee is the one that matters and the only one it
      can make honestly: **nothing was sent.** It does not render the body the command would have
      built — that is composed inside `run`, past the point of no return — so it reports the
      operation and the permission it costs rather than a diff it would have to fake.
    */
    sink.line(`Dry run: '${found.command.name}' would ${write.kind} — nothing was sent.`);
    sink.line(`It costs \`${write.permission}\`.`);
    return EXIT.ok;
  }

  if (write?.kind === 'delete' && values['yes'] !== true) {
    const agreed = await confirm(consentQuestion(found.command, parsed.positionals));
    if (!agreed) {
      out.error(process.stdin.isTTY
        ? 'Cancelled.'
        : `'${found.command.name}' removes something and there is no terminal to confirm at. `
          + 'Pass --yes to proceed, or --dry-run to see what it would do.');
      return EXIT.cancelled;
    }
  }

  try {
    /*
      An offline command is handed a client that throws rather than one that is absent, so the
      `CommandContext` shape stays uniform and a command that *starts* reaching the instance later
      fails loudly at the call instead of silently taking a different path. See `Command.offline`.
    */
    const client = found.command.offline
      ? new Proxy({}, {
        get: () => () => {
          throw new Error('This command is declared offline and must reach no instance.');
        },
      }) as TcmsClient
      : connect(
        {
          url: typeof values['url'] === 'string' ? values['url'] : undefined,
          timeout,
        },
        env,
      );

    await found.command.run({
      client,
      out: sink,
      project: typeof values['project'] === 'string' ? values['project'] : undefined,
      json: values['json'] === true,
      args: parsed.positionals,
      options: values,
      request: requestOptions({ timeout }),
    });

    return EXIT.ok;
  } catch (error) {
    return report(error, sink, found.command, values['json'] === true);
  }
}

/**
 * What the prompt says.
 *
 * It names the row, not just the operation. The MCP's gate learned this the expensive way — its
 * confirmation echoed the operation and nothing else for as long as it existed, which is a
 * question a person cannot answer — so this echoes the positionals the command was given.
 */
/**
 * Drops the human rendering and keeps everything a caller cannot re-derive.
 *
 * `json` survives, so `--json --quiet` is exactly "machine output and nothing else" rather than
 * silence; so do the refusals, which are on stderr and are why the exit code is not 0.
 */
function quieted(out: Output): Output {
  return { line: () => undefined, json: out.json, error: out.error };
}

function consentQuestion(command: Command, positionals: readonly string[]): string {
  const what = positionals.length > 0 ? ` ${positionals.join(' ')}` : '';
  return `'${command.name}'${what} removes something and cannot be undone. Continue?`;
}

/**
 * One failure, one sentence, one exit code.
 *
 * A cancellation is **not a failure** and must not be reported as one — the SPA's client records
 * the same rule for the same reason — so the classifier checks it first and this prints nothing
 * for it.
 *
 * ~~*"prints the server's own `detail` rather than a sentence composed here, because the server is
 * the only party that knows why it refused"*~~ → **superseded.** The server deliberately does not
 * know: its 403 body is a fixed *"You do not have permission to do this"* naming no key, and the
 * only party that can name one is the command, which declares it. So the same refusal read
 * *"Cannot edit this case: this token's role does not hold `case:edit`. Ask a project
 * administrator…"* through the MCP and *"You do not have permission to do this."* here — on the
 * surface a **person** uses to find out what to ask for. `describeFailure` is that copy, and the
 * surface spec's §6 claimed all along that both adapters shared it.
 *
 * `--json` gets the failure as JSON on stdout. Success stays verbatim (`output.ts` argues why), but
 * on failure stdout was empty and the exit code ambiguous, so a caller piping to `jq` saw nothing at
 * all. A **usage** error is deliberately not in that envelope: it happens before any call is made,
 * its reader is a person who mistyped a flag, and a machine-readable body for it would imply the
 * instance said something.
 */
function report(error: unknown, out: Output, command: Command, json: boolean): number {
  if (error instanceof UsageError) {
    out.error(error.message);
    out.error(command.usage);
    return EXIT.usage;
  }

  const failure = describeFailure(error, {
    what: command.summary.charAt(0).toLowerCase() + command.summary.slice(1),
    // A read has no declared permission and needs none: a refused read answers 404, whose copy
    // names nothing at all. See `core/refusal.ts` for why that boundary is not negotiable.
    permission: command.write?.permission ?? 'a permission this token does not hold',
    write: command.write !== undefined,
  });

  if (failure.code === 'cancelled') return EXIT.cancelled;

  if (json) {
    out.json({ ok: false, error: failure });
    return EXIT_FOR[failure.code];
  }

  out.error(failure.message);
  for (const [field, messages] of Object.entries(failure.fields ?? {})) {
    for (const message of messages) out.error(`  ${field}: ${message}`);
  }
  // Printed only when the server sent one: it is the handle a support conversation uses, and
  // an invented or absent id printed as "none" is noise on every other line.
  if (failure.correlationId) out.error(`  correlation id: ${failure.correlationId}`);
  return EXIT_FOR[failure.code];
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
  out.line('  --json          print the API response verbatim (a failure as an error envelope)');
  out.line('  --url URL       the TCMS instance, overriding TCMS_URL');
  out.line('  --timeout SECS  override the request deadline');
  out.line('  --yes           skip the confirmation on a command that removes something');
  out.line('  --dry-run       say what a write would do, and send nothing');
  out.line('  --quiet         drop the human rendering; --json and refusals still print');
  out.line('  --version       print the client version');
  out.line();
  out.line('Environment:');
  out.line('  TCMS_URL        the instance origin, e.g. https://tcms.example.com');
  out.line('  TCMS_TOKEN      an API token from Settings → API tokens');
  out.line();
  // Undocumented exit codes are undiscoverable ones, and a script that cannot tell 429 from 403
  // retries what will never succeed. See `EXIT`.
  out.line('Exit codes:');
  out.line('  0 ok            2 usage        3 not authenticated   4 permission denied');
  out.line('  5 not found     6 conflict     7 rate limited        8 timed out');
  out.line('  9 unavailable   1 other        130 cancelled');
}

// `import.meta.main` is Node 24+; this package targets 22.12, so the entry check is the older
// spelling. Guarded at all so `main` can be imported by the tests without running, and shared
// with @tcms/mcp because the obvious spelling of it is wrong for `npm install -g` — see
// `isProcessEntryPoint`.
if (isProcessEntryPoint(import.meta.url)) {
  main(process.argv.slice(2))
    .then((code) => { process.exitCode = code; })
    .catch((error: unknown) => {
      process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = EXIT.failed;
    });
}
