/**
 * `tcms_shape` — the authoring format, as a tool.
 *
 * Carrier 2 of three (`docs/plans/2026-08-26-authoring-shape-catalogue.md`). Carrier 1 is the
 * enriched `inputSchema` on every write tool, which is what a model reads at the moment it calls;
 * carrier 3 is the `tcms://shape/{entity}` resource, which is where the long-form guide lives. This
 * one sits between them: discoverable in `tools/list`, so it does not depend on a host that
 * surfaces resources, and cheap enough to call before deciding anything.
 *
 * ## No gate, and no endpoint — both deliberate
 *
 * **It calls nothing.** That keeps surface spec §3.1 unengaged: the "no third composite without
 * the same argument" rule governs tools that fan out to more than one *read*, and this fans out to
 * zero. It is static text compiled into the build.
 *
 * **It must work with no project in scope, which is why it is not part of `tcms_orient`.** Orient
 * opens with `session.projectId()` and every one of its five reads is project-scoped. An agent that
 * has connected but has not yet resolved a project is exactly the caller who needs to know what a
 * case looks like, and folding this into orient would make the one tool that must work early
 * depend on the thing that is not there yet.
 *
 * The division of labour with orient is the other way round and is stated in both descriptions:
 * **orient owns the project's live vocabulary** — the statuses, the classification dimensions and
 * their value paths, the tags actually in use — and this owns the shape. Duplicating orient's half
 * here would be a second copy that goes stale the moment a project adds a dimension.
 */
import { SHAPES, guideFor, shapeOf, templateFor } from '@tcms/client';

import { InputError } from './input.js';
import type { ToolDefinition } from './tool.js';

const ENTITIES = Object.keys(SHAPES).sort();

export const shape: ToolDefinition = {
  name: 'tcms_shape',
  title: 'Get the authoring format for a thing you are about to write',
  description:
    'The fields, the enums, the required-when rules and the pitfalls for one kind of object — '
    + `call it before your first write of that kind. Entities: ${ENTITIES.join(', ')}. It reads `
    + 'nothing and needs no project, so it is safe to call first. It does NOT carry this '
    + "project's vocabulary — the priorities are fixed, but statuses, classification values and "
    + 'tags are per-project and configurable, and tcms_orient is what serves those. A value '
    + 'guessed from another project will be refused. Reviews and reports are NOT here and the '
    + 'omission is deliberate: a review verdict, a remark and a report request are arguments to '
    + 'tcms_review_verdict, tcms_review_comment and tcms_report rather than objects with a shape, '
    + "and those tools' own schemas carry them in full.",
  gate: { mode: 'all', permissions: [] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      entity: {
        type: 'string',
        enum: ENTITIES,
        description: 'Which kind of object. Omit for the list of every entity and its actions.',
      },
    },
  },
  // The guide is one authored document and a truncated one is worse than none: a model that reads
  // the first half of `declinedConditions` learns that it takes ids and not that they are
  // ATTACHMENT ids. Same argument as `tcms_open_case` and `tcms_open_doc_page` (§4.6).
  meta: { 'anthropic/maxResultSizeChars': 200_000 },
  run: async (_session, input) => {
    const requested = typeof input['entity'] === 'string' ? input['entity'] : undefined;

    if (requested === undefined) {
      return {
        entities: ENTITIES.map((name) => {
          const found = SHAPES[name]!;
          return {
            entity: found.entity,
            title: found.title,
            actions: found.actions.map((action) => ({
              action: action.name,
              permission: action.permission,
              // Named per action rather than per tool: an action this adapter does not expose is
              // one a model would otherwise keep looking for.
              onThisSurface: action.mcp,
              describe: action.describe,
            })),
          };
        }),
        note: 'Call tcms_shape with an entity for its fields, and tcms_orient for this project\'s '
          + 'own vocabulary.',
      };
    }

    const found = shapeOf(requested);
    if (!found) {
      /*
        ~~A result rather than a throw~~ → **a refusal that still names what exists.** Returning it
        through `ok()` set no `isError`, so a host rendered a failure as data and a model read an
        object with an `error` key as a successful answer about a shape. The `enum` on the input
        means a validating host refuses this first — but `input.ts` is written on the rule that a
        server trusting a peer's validation is trusting a peer it did not write, and this was the
        one place that did. The naming half was right and is kept.
      */
      throw new InputError(`No entity named '${requested}'. Entities: ${ENTITIES.join(', ')}.`);
    }

    return {
      entity: found.entity,
      title: found.title,
      actions: found.actions.filter((action) => action.mcp).map((action) => action.name),
      template: templateFor(found, found.actions[0]!.name),
      guide: guideFor(found),
    };
  },
};
