/**
 * Reading a tool's arguments, and the schema fragments the tools share.
 *
 * MCP arguments arrive as untyped JSON — the host validates against the declared `inputSchema`,
 * but a server that trusts a client's validation is trusting a peer it did not write. So every
 * value is read through one of these, which coerce nothing silently: a string where a number was
 * declared is a refusal naming the field, not `NaN` sent to the server as a page number.
 */
import { TcmsClientError } from '@tcms/client';

/** A mistake in how the tool was called, as opposed to a refusal from the server. */
export class InputError extends TcmsClientError {}

export function str(input: Record<string, unknown>, name: string): string | undefined {
  const value = input[name];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'string') throw new InputError(`'${name}' must be a string.`);
  return value;
}

export function requireStr(input: Record<string, unknown>, name: string): string {
  const value = str(input, name);
  if (value === undefined || value === '') throw new InputError(`'${name}' is required.`);
  return value;
}

export function num(input: Record<string, unknown>, name: string): number | undefined {
  const value = input[name];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new InputError(`'${name}' must be a number.`);
  }
  return value;
}

export function bool(input: Record<string, unknown>, name: string): boolean | undefined {
  const value = input[name];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'boolean') throw new InputError(`'${name}' must be true or false.`);
  return value;
}

export function strings(input: Record<string, unknown>, name: string): string[] | undefined {
  const value = input[name];
  if (value === undefined || value === null) return undefined;
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw new InputError(`'${name}' must be an array of strings.`);
  }
  return value as string[];
}

/**
 * A field that distinguishes absent from null, which several `PATCH` bodies do.
 *
 * The contract's edit shapes treat an omitted field as *leave it alone* and an explicit `null` as
 * *clear it*, and collapsing the two empties fields nobody mentioned. JSON can express both, so
 * unlike the CLI — where a shell cannot type `null` and `--flag ''` had to stand in — this side
 * simply passes them through. The check is that a caller who sent neither gets neither.
 */
export function nullableStr(
  input: Record<string, unknown>,
  name: string,
): string | null | undefined {
  if (!(name in input)) return undefined;
  const value = input[name];
  if (value === null) return null;
  if (typeof value !== 'string') throw new InputError(`'${name}' must be a string or null.`);
  return value;
}

/**
 * {@link nullableStr}'s shape for a nullable number — the absent / null / value split, kept.
 *
 * Collapsing null into absent here would make a field the contract lets a caller CLEAR reachable
 * only for setting, which is the same divergence `present` exists to avoid on the other side.
 */
export function nullableNum(
  input: Record<string, unknown>,
  name: string,
): number | null | undefined {
  if (!(name in input)) return undefined;
  const value = input[name];
  if (value === null) return null;
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new InputError(`'${name}' must be a number or null.`);
  }
  return value;
}

/** Drops the keys nobody named, so a `PATCH` asks for exactly what was sent. */
export function present<T extends object>(body: T): Record<string, unknown> {
  return Object.fromEntries(Object.entries(body).filter(([, value]) => value !== undefined));
}

/* ------------------------------------------------------- shared schema pieces ---- */

export const PAGING_PROPERTIES = {
  page: { type: 'integer', minimum: 0, description: 'Zero-based page. Default 0.' },
  size: {
    type: 'integer',
    minimum: 1,
    maximum: 200,
    description: 'Rows per page. Default 25 — raise it only when a wider page is genuinely needed.',
  },
} as const;

/**
 * An identifier parameter, described once.
 *
 * Surface spec §4.4: every id parameter accepts the display key or the UUID, and every response
 * names keys. Keys are company-sequential (§6t), so they are what a human and a model both name
 * the row by — and repeating that sentence per parameter would cost 2KB budget on every tool.
 */
export function idProperty(what: string, example: string): Record<string, unknown> {
  return { type: 'string', description: `The ${what} — its display key (${example}) or its UUID.` };
}
