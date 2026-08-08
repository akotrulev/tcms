/**
 * What a tool is, and the two declarations that make the registry checkable.
 *
 * The shape mirrors `@tcms/cli`'s `Command` on purpose — a `gate` where the CLI has nothing (a
 * person reads `--help` and finds out by trying), and the same `WriteDeclaration` the CLI carries
 * so `conformance.test.ts` can assert the headers each arm implies over *both* registries with
 * one rule. Surface spec §8.3.
 *
 * ## The gate is a listing rule, and it is NOT authorization
 *
 * §6w.3 filters `tools/list` by permission — *"an agent shown a tool it cannot use will call it,
 * be refused, and retry"*. That is an affordance, and it is the only thing this field decides.
 * **It must never be read as "the caller may do this."** Two independent reasons, both live:
 *
 *   - `GET /me/permissions` answers the *keys* a caller holds, and §A3's qualifiers narrow
 *     several of them — `case:delete` is `OWN` for a Test Engineer. A tool listed on the key can
 *     still 403 on the row.
 *   - the permissions are resolved **once, at connect**. A grant revoked mid-session leaves a
 *     tool listed that the server will refuse, which is correct: the server refuses, not this
 *     list.
 *
 * So every handler calls the endpoint and lets the server decide, and `result.ts` turns the
 * refusal into copy the core composed. A client-side gate that *short-circuited* the call would
 * be a second implementation of the permission model — the thing this whole package exists to
 * not have.
 */
import type { WriteKind } from '@tcms/client';

import type { Qualifier, Session } from './session.js';

/** JSON Schema, as it goes on the wire. Hand-written rather than derived — see `schemas.ts`. */
export interface JsonSchema {
  type: 'object';
  properties: Record<string, unknown>;
  required?: readonly string[];
  additionalProperties?: boolean;
}

/**
 * One arm of a write tool — surface spec §2's "arms", declared so they can be counted.
 *
 * A tool merges arms only when every *reachable intersection* holds all of them (§2), so the
 * arms are listed rather than collapsed into the tool's gate: the conformance check needs to see
 * each one to assert the rule, and the gate needs to see all of them to filter the list.
 */
export interface ToolArm {
  /** The `action` value that selects it, for a tool that takes one. */
  action: string;
  kind: WriteKind;
  permission: string;
}

/** `all`: every permission is required to list. `any`: one is enough — see `tcms_review_verdict`. */
export type GateMode = 'all' | 'any';

export interface ToolGate {
  mode: GateMode;
  permissions: readonly string[];
}

export interface ToolDefinition {
  name: `tcms_${string}`;
  title: string;
  /**
   * What the model reads before deciding to call.
   *
   * **Truncated at 2KB by the host**, so a description that runs long is not merely verbose —
   * its last sentence does not arrive, and the last sentence is where the caveats go.
   * `conformance.test.ts` measures every one of these.
   */
  description: string;
  gate: ToolGate;
  /** Absent on a read tool. */
  arms?: readonly ToolArm[];
  inputSchema: JsonSchema;
  /**
   * `_meta` as the host reads it.
   *
   * Two keys are used on this surface and both are Anthropic's:
   * `anthropic/alwaysLoad` on `tcms_whoami`, so the capability answer survives tool-search
   * deferral, and `anthropic/maxResultSizeChars` on the two tools that return one authored
   * document (surface spec §4.6).
   */
  meta?: Record<string, unknown>;
  /**
   * An extra sentence for a session that reaches only part of an `any` gate.
   *
   * `tcms_review_verdict` is the one tool listed on a per-subject basis: a caller holding
   * `case:review` and not `plan:review` sees it, and its description has to say which subjects
   * it will accept *for them*. Composed per session rather than baked, because the answer is a
   * property of the credential and not of the build.
   */
  narrow?: (held: ReadonlySet<string>) => string | undefined;
  run: (session: Session, input: Record<string, unknown>) => Promise<unknown>;
}

/** True when this session's permissions satisfy the tool's listing rule. */
export function isListed(tool: ToolDefinition, held: ReadonlySet<string>): boolean {
  const { mode, permissions } = tool.gate;
  if (permissions.length === 0) return true;
  return mode === 'any'
    ? permissions.some((permission) => held.has(permission))
    : permissions.every((permission) => held.has(permission));
}

/**
 * The tool's description as this session should read it.
 *
 * Two things are appended, and both are properties of the credential rather than of the build:
 * the `any`-gate narrowing (`tcms_review_verdict` naming the subjects it will accept for *this*
 * caller), and the {@link Qualifier} sentence — see `session.ts` for why a listed tool may still
 * be restricted to the caller's own rows, and why saying so beats hiding it.
 */
export function describeFor(
  tool: ToolDefinition,
  held: ReadonlySet<string>,
  qualifiers: ReadonlyMap<string, Qualifier> = new Map(),
): string {
  const parts = [tool.description];

  const narrow = tool.narrow?.(held);
  if (narrow) parts.push(narrow);

  const restricted = tool.gate.permissions
    .filter((permission) => held.has(permission))
    .map((permission) => [permission, qualifiers.get(permission)] as const)
    .filter(([, qualifier]) => qualifier !== undefined && qualifier !== 'ALL');

  if (restricted.length > 0) {
    parts.push(
      'This credential holds it narrowly: '
        + restricted.map(([permission, qualifier]) => `${permission} covers ${QUALIFIER_COPY[qualifier as Qualifier]}`).join('; ')
        + '. Anything outside that will be refused, so check before offering it.',
    );
  }

  return parts.join('\n\n');
}

/** What each restrictive qualifier means, in the words a model should relay. */
const QUALIFIER_COPY: Record<Qualifier, string> = {
  ALL: 'every row in scope',
  OWN: 'only rows this user authored',
  PLAN_SCOPE: 'only rows belonging to one plan, not the project-scope ones',
};
