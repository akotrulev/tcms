/**
 * `tcms_whoami` — WIP plan §6.2 carrier 2, *"always loaded … returns the same summary, live"*.
 *
 * Its own file because it is the one tool that is not a wrapper around a workflow: it answers a
 * question about the *session*, it has no gate, and it is the only one whose result changes what
 * the host believes about every other tool.
 *
 * ## "Live" is the load-bearing word
 *
 * The instructions are a **snapshot**, taken once at connect, and a snapshot of an authorization
 * answer is a cache with no invalidation — a grant revoked ten minutes into a session leaves the
 * summary and the filtered `tools/list` both describing a reach the credential no longer has. It
 * fails safe (the server refuses the call) and it reads badly (the model insists it can do
 * something and is refused every time).
 *
 * So this tool re-probes rather than reading `session.reach`, and `server.ts` turns a change in
 * the *listed set* into `notifications/tools/list_changed`. It is the only invalidation this
 * session has, which makes carrier 2 the mechanism and not just the answer.
 *
 * ## `alwaysLoad`, and why it is worth a definition's context
 *
 * Claude Code defers tool definitions behind tool search by default, so in a long session the
 * others may not be in context at all. This one has to be: the moment the user asks *"can you
 * approve this?"* is exactly the moment the definitions are furthest away.
 */
import { describeReach } from '@tcms/client';

import type { ToolDefinition } from './tool.js';

export const whoami: ToolDefinition = {
  name: 'tcms_whoami',
  title: 'Who this session acts as, and what it can reach',
  description:
    'Reports the person this credential acts as, the project in scope, the permissions it '
    + 'currently holds, and the tools that are available as a result. Re-reads the server rather '
    + 'than repeating the session summary, so it is the right answer after a role change. Call it '
    + 'when the user asks what you can do here, or before telling them something is impossible.',
  gate: { mode: 'all', permissions: [] },
  inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  meta: { 'anthropic/alwaysLoad': true },
  run: async (session) => {
    const before = session.reach;
    const reach = await session.refresh();

    return {
      user: reach.user,
      project: reach.project
        ? { key: reach.project.key, name: reach.project.name }
        : null,
      // The §6t-safe sentence, composed in the core. It is the branch that must not distinguish
      // "no such project" from "not visible to you", and an empty permission set is on the silent
      // side of that table however harmless it looks.
      scope: describeReach([...reach.permissions], reach.project !== undefined),
      ...(reach.unscoped ? { unscoped: reach.unscoped } : {}),
      visibleProjects: reach.visible.map((row) => row.key),
      // Keys **with their qualifiers**, because a bare key cannot answer "may I do this to that
      // row" — `session.ts`'s `Qualifier` carries the argument, and the CLI's `whoami` printed
      // this array with `.join()` and rendered `[object Object]` for a year of nobody looking.
      permissions: [...reach.qualifiers.entries()]
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, qualifier]) => ({ key, qualifier })),
      changed: differs(before.permissions, reach.permissions),
    };
  },
};

function differs(before: ReadonlySet<string>, after: ReadonlySet<string>): boolean {
  if (before.size !== after.size) return true;
  for (const permission of before) if (!after.has(permission)) return true;
  return false;
}
