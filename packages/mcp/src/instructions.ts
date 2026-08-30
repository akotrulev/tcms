/**
 * The capability summary — WIP plan §6.2 carrier 1, rendered into the server's `instructions`.
 *
 * *"The agent should be able to tell the user 'I can't do that, and here is what would let me.'"*
 * Three carriers were specified; this is the first, and it is the one that arrives **before any
 * tool is discovered**, because a host loads server instructions at session start even when tool
 * search defers the definitions. `tcms_whoami` is carrier 2 and `core/refusal.ts` is carrier 3.
 *
 * ## Two constraints, and they pull against each other
 *
 * **It must be short.** Instructions truncate at 2KB, so this is a grouped summary and never a
 * 55-row dump — and because *the host* does the truncating, a summary that runs long does not
 * read as verbose, it silently loses its last paragraph. {@link INSTRUCTIONS_BUDGET} is enforced
 * here instead, dropping whole sections in a fixed order so what survives is always the part that
 * was going to matter most.
 *
 * **It must not become an enumeration oracle.** §6t's rule is that an unreachable project and a
 * non-existent one answer identically, *"so this endpoint cannot be used to probe for projects in
 * other companies"*. An empty permission answer is on the silent side of §4.5's table, and it is
 * the case that looks harmless — summarising `[]` as "you hold nothing *here*" asserts that
 * *here* is somewhere. `describeReach` in the core is the one place that sentence is composed,
 * and this file calls it rather than writing its own.
 *
 * ## What it may not say, which is a finding and not an omission
 *
 * §6.2 asks for *"the operations that are unavailable with the role that holds each"*. **The role
 * cannot be named.** `core/refusal.ts` carries the full argument: the role→permission catalogue
 * lives on `GET /roles` alone, `AdminDirectoryService.listRoles` gates it on `company:read`, and
 * `V40` §6 withholds `company:read` from `Agent` deliberately — *a token is not a member* — so an
 * agent token receives `[]`, indistinguishable from an empty catalogue. This names the
 * **permission** instead, which is strictly less than §6.2 permits and is the actionable half:
 * it is what the user takes to an administrator.
 */
import { describeReach } from '@tcms/client';

import type { Reach } from './session.js';
import { isListed, type ToolDefinition } from './tool.js';

/**
 * The host's ceiling, in bytes.
 *
 * Bytes rather than characters, and measured rather than assumed from `String.length`: a case
 * title in Cyrillic or an em-dash in a project name is two or three bytes apiece, so a summary
 * that counts characters is under budget in English and over it in the languages this product
 * already stores (`citext`, and §6 has no ASCII rule anywhere).
 */
export const INSTRUCTIONS_BUDGET = 2000;

/** What {@link fit} joins sections with, named so the size arithmetic and the join agree. */
const SEPARATOR = '\n\n';

/**
 * Renders the summary for one session.
 *
 * The **unavailable** list is derived from the tool registry rather than written out: it is
 * exactly the set of tools this session's `tools/list` will not contain, so the summary and the
 * list cannot disagree. A hand-written list would be a third copy of the permission matrix, which
 * is the thing this repository has paid for five times.
 */
export function renderInstructions(reach: Reach, tools: readonly ToolDefinition[]): string {
  const held = reach.permissions;
  const sections: string[] = [];

  sections.push(
    'TCMS — the test repository for this company. Every call acts as the person who issued this '
      + "credential, with their grants; nothing here can exceed them.",
  );

  sections.push(identity(reach));

  const hidden = tools.filter((tool) => !isListed(tool, held));
  if (hidden.length > 0) {
    /*
      Sized against what the two sections above leave, and shortened rather than dropped.

      `fit` drops whole sections from the end, which is right for the closing advice — every write
      tool's own description repeats it — and wrong for this one: a credential holding almost
      nothing is exactly the caller who needs to be told what to ask for, and it is also the
      caller for whom this list is longest. So the section that matters most to the reader was the
      one guaranteed to be sacrificed as the registry grew.

      That is not hypothetical. Audit C10 added two tools and pushed the detailed form to 1848
      bytes, over budget with the header, and the whole section disappeared — silently, because a
      dropped section reads exactly like a credential with nothing hidden.

      The fallback keeps the half the module header argues is the actionable one: the permission
      key is what a user takes to an administrator, the tool titles are context. Bounded by
      construction — the catalogue is the ceiling — so this cannot overflow again as tools are
      added, only as permissions are, and there have been none since `V3`.
    */
    const spare = INSTRUCTIONS_BUDGET - bytes(sections.join(SEPARATOR)) - SEPARATOR.length;
    const detailed = unavailable(hidden);
    sections.push(bytes(detailed) <= spare ? detailed : unavailableKeysOnly(hidden));
  }

  sections.push(
    'Ground yourself before you write: tcms_shape for the format of what you are writing, '
      + 'tcms_orient once for this project\'s own vocabulary, then read the cases or the '
      + 'documentation you are about to change. A generated case is filed as a proposal for a '
      + 'person to accept — it never becomes a test case on its own.',
  );

  return fit(sections);
}

function identity(reach: Reach): string {
  const who = `Acting as ${reach.user.displayName} <${reach.user.email}>.`;

  if (!reach.project) {
    // `unscoped` already carries the §6t-safe wording — it names counts and keys the credential
    // itself can see, and asserts nothing about anything it cannot.
    return `${who} ${reach.unscoped ?? 'No project is in scope.'} `
      + describeReach([...reach.permissions], false);
  }

  return `${who} Project: ${reach.project.key} — ${reach.project.name}. `
    + describeReach([...reach.permissions], true);
}

/**
 * The grouped "and here is what would let me" half.
 *
 * Grouped by the permission each tool's gate is missing, not listed per tool: three tools behind
 * `plan:edit` is one sentence a user can act on and three lines of noise.
 */
function unavailable(hidden: readonly ToolDefinition[]): string {
  const byPermission = new Map<string, string[]>();
  for (const tool of hidden) {
    for (const permission of tool.gate.permissions) {
      byPermission.set(permission, [...(byPermission.get(permission) ?? []), tool.title]);
    }
  }

  const lines = [...byPermission.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([permission, titles]) => `- ${permission}: ${[...new Set(titles)].sort().join(', ')}`);

  return 'Not available to this credential — ask an administrator for a role holding the '
    + `permission named:\n${lines.join('\n')}`;
}

/**
 * The same answer with the titles taken out — what is rendered when the detailed form will not
 * fit. See the call site for why the choice is made there and not here.
 *
 * The keys are still sorted and still deduplicated, so this is the detailed form's own left
 * column: a reader who has both in front of them at different times sees the same set.
 */
function unavailableKeysOnly(hidden: readonly ToolDefinition[]): string {
  const keys = [...new Set(hidden.flatMap((tool) => tool.gate.permissions))].sort();

  return 'Not available to this credential — ask an administrator for a role holding one of '
    + `these permissions: ${keys.join(', ')}.`;
}

/**
 * Joins the sections and drops whole ones from the end until the result fits.
 *
 * From the end, because the order above is the order of decreasing consequence: who you are
 * acting as is the thing a user most needs and the thing they cannot recover any other way, while
 * the closing advice is repeated by every write tool's own description. Truncating the *string*
 * was the obvious alternative and is worse — it ends mid-permission-key, and a half-written key
 * is one a model will hand to a user as the thing to ask for.
 */
function fit(sections: readonly string[]): string {
  const kept = [...sections];
  for (;;) {
    const text = kept.join(SEPARATOR);
    if (bytes(text) <= INSTRUCTIONS_BUDGET || kept.length <= 1) return text;
    kept.pop();
  }
}

export function bytes(text: string): number {
  return new TextEncoder().encode(text).length;
}
