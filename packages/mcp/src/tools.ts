/**
 * The registry — one list, and the two lookups everything else derives from.
 *
 * Same arrangement as `@tcms/cli`'s `commands.ts` and for the same reason: `conformance.test.ts`
 * walks this list and asserts the properties the surface spec requires of *every* tool (§8.3's
 * header coverage, §8.4's refusal copy, §6's 2KB description ceiling), so a tool that forgets one
 * fails the build rather than being found by an agent in the field.
 *
 * The count is 21 and it is an **outcome**, not a target — surface spec §2: *a tool never spans
 * two permissions on its write path*, because `tools/list` is permission-filtered and a tool
 * spanning two gates is either hidden from a caller who holds one of them or listed for a caller
 * who 403s on half of it.
 */
import { READ_TOOLS } from './reads.js';
import { whoami } from './whoami.js';
import { WRITE_TOOLS } from './writes.js';

import type { ToolDefinition } from './tool.js';

export const TOOLS: readonly ToolDefinition[] = [whoami, ...READ_TOOLS, ...WRITE_TOOLS];

export function findTool(name: string): ToolDefinition | undefined {
  return TOOLS.find((tool) => tool.name === name);
}
