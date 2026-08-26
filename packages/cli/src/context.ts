/**
 * What every command is handed, and the handful of things every command does with it.
 *
 * Split out of `commands.ts` when the write commands landed (phase 7). The registry, the reads
 * and the writes all need these, and leaving them in the file that holds the registry would have
 * made `writes.ts` import from the module that imports it.
 */
import { readFileSync } from 'node:fs';

import {
  resolveProject,
  type TcmsClient,
  type TcmsRequestOptions,
  type WriteKind,
} from '@tcms/client';

import type { Output } from './output.js';

export interface CommandContext {
  client: TcmsClient;
  out: Output;
  /** `--project`, when given. */
  project?: string | undefined;
  /** `--json`: print the response verbatim rather than a rendering of it. */
  json: boolean;
  /** Positional arguments after the noun and verb. */
  args: readonly string[];
  /** Named options a command declares — `--suite`, `--tag`, `--size`. */
  options: Readonly<Record<string, string | boolean | (string | boolean)[] | undefined>>;
  request: TcmsRequestOptions;
}

/**
 * What a command does to the instance, when it does anything.
 *
 * Declared rather than inferred, because `writeConformance.test.ts` asserts the headers each
 * kind implies **over this registry** — surface spec §8.3. A write command that forgets its
 * declaration is a build failure at the registry's type, and one that declares the wrong kind
 * fails the header assertion; neither is discovered by an agent in the field.
 */
export interface WriteDeclaration {
  kind: WriteKind;
  /**
   * The permission the surface declares for this arm — `case:edit`, `suite:manage`.
   *
   * It comes from the command rather than from the response: the API's 403 body is a fixed
   * sentence that names no key, deliberately, so this is the only thing that can tell a refused
   * caller what to go and ask for. See `core/refusal.ts` for what may be said with it.
   */
  permission: string;
}

/**
 * A command's own flags, in `util.parseArgs` shape.
 *
 * **This is not optional metadata, and §137 shipped without it as a live defect.** `parseArgs`
 * in non-strict mode gives every *undeclared* option the value `true` and pushes what followed it
 * into the positionals — so `case list --suite TS-7 --q login` parsed as two booleans, both
 * command helpers read them as absent, and the command answered the **unfiltered** list while
 * reporting success. Every string flag on every read command was silently dropped, and nothing
 * failed: the request was legal, the response was real, and only the filter was missing.
 *
 * Declaring them per command is what makes the parse correct **and** lets the parser run in
 * strict mode, so a misspelled flag is a usage error instead of a boolean nobody reads.
 */
export type OptionSpec = Record<string, { type: 'string' | 'boolean'; multiple?: boolean }>;

export interface Command {
  /** `case list` */
  name: string;
  summary: string;
  usage: string;
  /** The flags this command takes, beyond the global ones. See {@link OptionSpec}. */
  options?: OptionSpec;
  /** Absent on a read. */
  write?: WriteDeclaration;
  /**
   * True when the command reaches no instance at all, so `main` must not require one.
   *
   * `tcms format` is the only one: it answers what a payload must look like, which is a property
   * of the build rather than of any instance — and the person who most needs it is the one who
   * has not got a URL or a token yet. Every other command here fails correctly without them.
   *
   * Declared rather than inferred from "does it touch `context.client`", because that question
   * cannot be asked of a function without running it, and the failure of guessing wrong is a
   * command that demands credentials it never uses.
   */
  offline?: boolean;
  run: (context: CommandContext) => Promise<void>;
}

/* ------------------------------------------------------------------ options ---- */

export const first = (
  value: string | boolean | (string | boolean)[] | undefined,
): string | undefined => {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) {
    const found = value.find((item) => typeof item === 'string');
    return typeof found === 'string' ? found : undefined;
  }
  return undefined;
};

export const all = (value: string | boolean | (string | boolean)[] | undefined): string[] => {
  if (typeof value === 'string') return [value];
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === 'string');
  return [];
};

export const flag = (value: string | boolean | (string | boolean)[] | undefined): boolean =>
  value === true || (Array.isArray(value) && value.includes(true));

export const number = (value: string | undefined): number | undefined => {
  if (value === undefined) return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
};

/**
 * A numeric option that must be a number when it is present.
 *
 * `number()` answers undefined for both "absent" and "not a number", which is right for a page
 * size (fall back to the default) and wrong for `--lock-version`, where the fallback is *read a
 * fresh one* — the behaviour the whole flag exists to prevent. A typo must not silently become
 * the unsafe path.
 */
export function strictNumber(
  value: string | boolean | (string | boolean)[] | undefined,
  name: string,
): number | undefined {
  const raw = first(value);
  if (raw === undefined) return undefined;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) throw new UsageError(`${name} takes a number, not '${raw}'.`);
  return parsed;
}

/** The project every project-scoped command runs against. */
export async function projectOf(context: CommandContext): Promise<string> {
  const resolved = await resolveProject({
    client: context.client,
    requested: context.project,
    ...(context.request.signal ? { signal: context.request.signal } : {}),
  });
  return resolved.id;
}

export function requireArg(context: CommandContext, what: string, position = 0): string {
  const value = context.args[position];
  if (!value) throw new UsageError(`Which ${what}? Give its key or id.`);
  return value;
}

export function requireOption(context: CommandContext, name: string): string {
  const value = first(context.options[name]);
  if (value === undefined) throw new UsageError(`--${name} is required.`);
  return value;
}

/**
 * A JSON body read from a file, or from stdin for `-`.
 *
 * Read synchronously and whole. A body is a case, a page or a plan — kilobytes — and a streaming
 * read here would buy nothing while making the failure ("that file is not JSON") arrive somewhere
 * other than where the file was named.
 */
export function readJsonFile(path: string): unknown {
  let text: string;
  try {
    text = readFileSync(path === '-' ? 0 : path, 'utf8');
  } catch (error) {
    throw new UsageError(
      `Could not read ${path === '-' ? 'stdin' : `'${path}'`}: `
        + (error instanceof Error ? error.message : String(error)),
    );
  }

  try {
    return JSON.parse(text);
  } catch (error) {
    throw new UsageError(
      `${path === '-' ? 'stdin' : `'${path}'`} is not JSON: `
        + (error instanceof Error ? error.message : String(error)),
    );
  }
}

/**
 * A body from `--file`, checked to be an object.
 *
 * An array or a bare string parses as JSON and would go to the server as a body it rejects with a
 * message about the wrong shape — true, and useless to somebody who piped the wrong file.
 */
export function objectFrom(path: string, what: string): Record<string, unknown> {
  const parsed = readJsonFile(path);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new UsageError(`Expected ${what} — a JSON object — in ${path === '-' ? 'stdin' : path}.`);
  }
  return parsed as Record<string, unknown>;
}

/**
 * Refuses a write that would send nothing.
 *
 * A `PATCH` with an empty body is accepted by the contract (every field is optional) and changes
 * nothing while answering 200 — so a command that was given no field to change reports success
 * having sent nothing.
 */
export function requireSomethingToDo(
  body: Record<string, unknown>,
  hint: string,
): Record<string, unknown> {
  if (Object.keys(body).length === 0) throw new UsageError(`Nothing to change. ${hint}`);
  return body;
}

/** A mistake in how the command was typed, as opposed to a refusal from the server. */
export class UsageError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'UsageError';
  }
}
