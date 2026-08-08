/**
 * The command registry — one list, reads then writes, and the dispatcher over it.
 *
 * It was the whole surface until phase 7. The reads moved to `reads.ts` and the writes arrived in
 * `writes.ts` at the same time, leaving this file the two things that must stay singular: **the
 * list every other module derives from**, and the name lookup.
 *
 * The list is not decoration. `writeConformance.test.ts` walks it and asserts the headers each
 * command's `write` declaration implies (surface spec §8.3), so a write command that never sends
 * `Idempotency-Key` or `If-Match` fails the build here rather than being found by an agent
 * writing over somebody's edit.
 */
import { READS } from './reads.js';
import { WRITES } from './writes.js';

export type {
  Command,
  CommandContext,
  WriteDeclaration,
} from './context.js';
export { UsageError } from './context.js';

import type { Command } from './context.js';

export const COMMANDS: readonly Command[] = [...READS, ...WRITES];

export function findCommand(argv: readonly string[]): { command: Command; rest: string[] } | undefined {
  // Two-word names first, so `case list` is not read as the noun `case` with an argument.
  for (const length of [2, 1]) {
    const name = argv.slice(0, length).join(' ');
    const command = COMMANDS.find((candidate) => candidate.name === name);
    if (command) return { command, rest: argv.slice(length) };
  }
  return undefined;
}
