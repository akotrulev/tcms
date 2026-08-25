/**
 * The authoring shape catalogue — one answer to *"what does this object look like"*.
 *
 * `docs/plans/2026-08-26-authoring-shape-catalogue.md`. Before this module the question was
 * answered in four places and no two agreed on how much: a hand-written `JsonSchema` per MCP tool,
 * an `OptionSpec` per CLI command carrying types and nothing else, `openapi.yaml`, and
 * `core/proposal.ts` — which covered one entity, checked presence, and was the only shared
 * fragment. The sharpest instance is the one that started the slice: `tcms_write_case`'s
 * `testCase` argument was declared `{ type: 'object' }` with **no properties at all**, so a model
 * was handed four field names in prose and had to guess the step shape, the complete-set fields
 * and the conditional requirements.
 *
 * ## This is NOT generated from the contract, and that is the decision
 *
 * `packages/mcp/src/tool.ts` argues it and is right: a tool argument takes a **display key** where
 * the API takes a UUID, flattens `CaseConditionInput` to a bare id, and is written for a model
 * rather than for a person reading API docs. A schema generated from `openapi.yaml` produces a
 * surface no caller on either transport can use.
 *
 * So the catalogue is written by hand **and pinned to the contract by a computed check** —
 * `shapesContract.test.ts` compares every {@link FieldSpec.enumOf} against the contract's enum
 * and every `required: 'always'` against the contract's `required` array, both ways. That is what
 * keeps this from becoming the fifth copy: CLAUDE.md's rule is that a catalogue-wide invariant
 * needs a computed check rather than a careful reading, and reading V1 top to bottom is how five
 * join tables lost their `company_id`.
 *
 * ## What a renderer may and may not do
 *
 * Everything here is data. The renderers below turn it into a JSON Schema fragment, a CLI
 * `OptionSpec`, a usage block, a worked template and a markdown guide — five carriers, one source,
 * so the two adapters cannot describe the same object differently. They emit **structured text
 * only**: no terminal width, no colour, no styling. The CLI's `output.ts` is still where
 * presentation happens and may decorate what it is handed.
 */

/** How a field is spelled at a terminal, where `parseArgs` cannot say `null` and has no objects. */
export interface CliBinding {
  /** The flag, without `--`. Defaults to the field's own name in kebab-case. */
  flag?: string;
  /** Repeated flag rather than one comma-joined value — `--tag a --tag b`. */
  multiple?: boolean;
  /**
   * The second flag that means "send null".
   *
   * `--unfile` beside `--suite`. `packages/mcp/src/writes.ts` records why only one adapter needs
   * it: *"the CLI needed two flags to express this; here it is the shape the caller already
   * sent"* — JSON distinguishes absent from null and an argv string cannot.
   */
  negate?: string;
}

/**
 * A field is required either unconditionally, or given a value of another field.
 *
 * The conditional four are the whole reason this is not a boolean. `openapi.yaml`'s own header on
 * `TestCaseInput` says it cannot state them without *"a `oneOf` that would split the shape in four
 * for four fields"*, and declines — so the contract is honest rather than complete, and the
 * conditions live here instead of being discovered as a 400.
 */
export type Requirement = 'always' | { readonly when: string; readonly is: readonly string[] };

export interface FieldSpec {
  /** The name this surface takes, which is not always the contract's — see {@link contract}. */
  readonly name: string;
  readonly type: 'string' | 'integer' | 'boolean' | 'string[]' | 'object[]';
  readonly required?: Requirement;
  /** The permitted values, pinned to {@link enumOf}. */
  readonly enum?: readonly string[];
  /**
   * The contract schema whose `enum` {@link FieldSpec.enum} must equal, checked at test time.
   *
   * Naming the schema rather than repeating "these four strings are the priorities" is what makes
   * the pin mechanical: a value added to `Priority` in the contract reddens `packages/`.
   */
  readonly enumOf?: string;
  /** `null` is a meaningful value here, and means something other than "absent". */
  readonly nullable?: boolean;
  /**
   * **Complete-set semantics.** Omitting the field leaves it alone; `[]` clears it.
   *
   * Marked rather than described per field because getting it wrong is silent in the dangerous
   * direction: `openapi.yaml` records that reading absent as "clear" would have every bulk move
   * quietly strip a case out of every tag-resolved suite it was in.
   */
  readonly set?: boolean;
  /** A display key is accepted here, and this is what one looks like. */
  readonly keyExample?: string;
  /** The contract property this renders to, when the surface spells it differently. */
  readonly contract?: string;
  /**
   * The contract schema this field belongs to, when it is not the entity's own.
   *
   * `verdict` is the case's only one: it rides on `AiProposalInput`, the wrapper, rather than on
   * the `TestCaseInput` inside it — a claim *about* the proposal is not a property of the case.
   * The conformance check follows this rather than reporting the field as surface-only, which
   * would be a licence to invent one.
   */
  readonly contractSchema?: string;
  /**
   * Why this field exists on the surface with no contract property behind it.
   *
   * Required when there is none, so the conformance check can tell a deliberate surface-only
   * argument from a typo. There is no default: an unexplained field is the failure.
   */
  readonly surfaceOnly?: string;
  /** The actions that accept it. Absent means every body-carrying action of the entity. */
  readonly actions?: readonly string[];
  readonly cli?: CliBinding;
  /** One line, for a tool schema and for `--help`. */
  readonly describe: string;
  /** The pitfall. Long-form guide only — it will not fit in a tool schema. */
  readonly note?: string;
  /** `steps`, `preconditions`. */
  readonly fields?: readonly FieldSpec[];
}

/** What `WriteDeclaration.kind` already says, restated here so a renderer can read it. */
export type ActionKind = 'create' | 'edit' | 'state' | 'delete';

export interface ActionSpec {
  readonly name: string;
  readonly kind: ActionKind;
  readonly permission: string;
  /** False when this adapter has no such command — see `EXPOSURE` in the conformance check. */
  readonly mcp: boolean;
  readonly cli: boolean;
  readonly describe: string;
}

export interface EntityShape {
  readonly entity: string;
  readonly title: string;
  /** The contract schema the body renders to, which the conformance check reads. */
  readonly contractSchema: string;
  readonly actions: readonly ActionSpec[];
  readonly fields: readonly FieldSpec[];
}

/* ------------------------------------------------------------------ enums ---- */

/*
  Declared once and shared by every entity that takes them, rather than repeated per field: the
  same four priorities are a case's `priority`, a case's `severity` and a work item's, and three
  copies is three chances for one to be missed when the contract moves. Each is pinned by name.
*/
const PRIORITY = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const;
const STEP_FORMAT = ['STRUCTURED', 'GHERKIN'] as const;
const AUTOMATION_STATUS = ['MANUAL', 'TO_BE_AUTOMATED', 'AUTOMATED', 'NOT_AUTOMATABLE'] as const;
const PROPOSAL_VERDICT = ['GROUNDED', 'PARTIAL', 'HALLUCINATED', 'UNVERIFIED'] as const;

/* ------------------------------------------------------------- test case ---- */

/**
 * A step, which is **text copied at authoring time** and not a reference.
 *
 * The deliberate opposite of a condition — `openapi.yaml`'s `CaseConditionInput` puts the contrast
 * in as many words. Editing a shared step does not propagate here; editing a library condition
 * does. A model that treats the two the same writes a case that silently stops tracking its own
 * setup.
 */
const STEP_FIELDS: readonly FieldSpec[] = [
  {
    name: 'action',
    type: 'string',
    required: 'always',
    describe: 'What the tester does. The only required field on a step.',
  },
  {
    name: 'keyword',
    type: 'string',
    nullable: true,
    describe: 'Gherkin keyword for a structured step that wants one — Given, When, Then.',
  },
  {
    name: 'testData',
    type: 'string',
    nullable: true,
    describe: 'The input this step uses, when naming it in the action would bury it.',
  },
  {
    name: 'expectedResult',
    type: 'string',
    nullable: true,
    describe: 'What must be true after the action. Absent means the step only sets up.',
  },
];

/**
 * A condition attachment, which is **reference-only**.
 *
 * `inlineBody` is deliberately omitted rather than declared-and-refused. The contract declares it
 * so the server can answer a field error instead of swallowing it silently, which is the right
 * call one layer down; repeating it here would offer a model a field whose only outcome is a 400.
 */
const CONDITION_FIELDS: readonly FieldSpec[] = [
  {
    name: 'conditionId',
    type: 'string',
    required: 'always',
    describe: 'A live entry of this project\'s condition library — tcms_list_library serves them.',
    note:
      'Only the reference form is accepted. Editing the library entry propagates to every case '
      + 'attached to it, with no prompt — that is the whole point of a condition and the reason '
      + 'it is not a step. An `inlineBody` is refused with a field error: nothing can render one, '
      + 'so accepting it would be a save whose content the author can never see again.',
  },
];

/** The order fields are rendered in, and it is the order an author fills them in. */
const CASE_FIELDS: readonly FieldSpec[] = [
  {
    name: 'title',
    type: 'string',
    required: 'always',
    describe: 'What the case verifies, as one line.',
  },
  {
    name: 'stepFormat',
    type: 'string',
    required: 'always',
    enum: STEP_FORMAT,
    enumOf: 'StepFormat',
    describe: 'STRUCTURED for a step table, GHERKIN for a scenario. It decides which body field is required.',
    actions: ['propose'],
    note:
      'Absent from the `edit` arm on both adapters and that is not an omission: `TestCaseInput` '
      + 'carries no way to change it without also supplying the body in the new format, so an '
      + 'edit that flipped the format alone would leave a STRUCTURED case with no steps.',
  },
  {
    name: 'priority',
    type: 'string',
    required: 'always',
    enum: PRIORITY,
    enumOf: 'Priority',
    describe: 'How much it matters that this case is run.',
  },
  {
    name: 'automationStatus',
    type: 'string',
    required: 'always',
    enum: AUTOMATION_STATUS,
    enumOf: 'AutomationStatus',
    describe: 'Where this case stands with respect to automation.',
  },
  {
    name: 'steps',
    type: 'object[]',
    required: { when: 'stepFormat', is: ['STRUCTURED'] },
    fields: STEP_FIELDS,
    actions: ['propose'],
    describe: 'The procedure, in order. Required when stepFormat is STRUCTURED.',
    note:
      'Order is the content — position is assigned from the array and is never sent. The server '
      + 'would accept a stepless case (§200 deferred the procedure, so a case may exist before '
      + 'anybody has written how to run it), and both non-SPA clients refuse one anyway: that '
      + 'deferral was granted to a person typing a title into a plan they are writing, not to a '
      + 'model asked for the case itself. A proposal with no procedure gains the reviewer nothing '
      + 'they could not have got by typing the title.',
  },
  {
    name: 'gherkinSource',
    type: 'string',
    nullable: true,
    required: { when: 'stepFormat', is: ['GHERKIN'] },
    actions: ['propose'],
    describe: 'The scenario text. Required when stepFormat is GHERKIN.',
  },
  {
    name: 'automationKey',
    type: 'string',
    nullable: true,
    required: { when: 'automationStatus', is: ['AUTOMATED'] },
    describe: 'The identifier the runner reports results under. Required when AUTOMATED.',
    note:
      'This is the handle result ingestion matches on. A case marked AUTOMATED without one is '
      + 'refused, and a case whose key does not match what the runner emits is silently never '
      + 'updated — the second is worse, and nothing here can catch it.',
  },
  {
    name: 'notAutomatableReason',
    type: 'string',
    nullable: true,
    required: { when: 'automationStatus', is: ['NOT_AUTOMATABLE'] },
    describe: 'Why this cannot be automated. Required when NOT_AUTOMATABLE.',
  },
  {
    name: 'description',
    type: 'string',
    nullable: true,
    describe: 'Context a reader needs before the steps make sense.',
  },
  {
    name: 'objective',
    type: 'string',
    nullable: true,
    describe: 'What running this case is meant to establish.',
  },
  {
    name: 'expectedOutcome',
    type: 'string',
    nullable: true,
    describe: 'The overall result, where the per-step expectations do not say it.',
  },
  {
    name: 'severity',
    type: 'string',
    nullable: true,
    enum: PRIORITY,
    enumOf: 'Priority',
    describe: 'How bad it is when this case fails, as against priority — how much it matters that it runs.',
  },
  {
    name: 'suite',
    type: 'string',
    nullable: true,
    contract: 'suiteId',
    keyExample: 'TS-7',
    cli: { flag: 'suite', negate: 'unfile' },
    describe: 'The suite to file it in, by display key. Null unfiles it.',
    note:
      'Filing is one way of belonging, not a precondition for existing — a suite is flat since '
      + '§98 and resolves three arms (filed, added, tagged), so a case may be written before '
      + 'anybody has decided where it goes. Absent and null differ on an edit: omitting leaves '
      + 'the case where it is, sending null unfiles it.',
  },
  {
    name: 'tags',
    type: 'string[]',
    set: true,
    cli: { flag: 'tag', multiple: true },
    describe: 'The COMPLETE tag set after this save, not an addition.',
    note:
      'Matched exactly where a suite\'s tag rules resolve — same spelling, same case. Not '
      + '`automationTags`, which are the selection tags a CI runner filters on; merging the two '
      + 'would make "run the smoke suite" and "cases the release manager flagged" one query.',
  },
  {
    name: 'preconditions',
    type: 'object[]',
    fields: CONDITION_FIELDS,
    actions: ['propose'],
    describe: 'Library conditions that must hold before the steps run, in order.',
  },
  {
    name: 'postconditions',
    type: 'object[]',
    fields: CONDITION_FIELDS,
    actions: ['propose'],
    describe: 'Library conditions to restore afterwards, in order.',
  },
  {
    name: 'declinedConditions',
    type: 'string[]',
    set: true,
    contract: 'declinedConditionIds',
    actions: ['edit'],
    cli: { flag: 'decline', multiple: true },
    describe: 'The COMPLETE set of inherited conditions this case declines.',
    note:
      'These are ATTACHMENT ids — the `id` on an entry of tcms_open_case\'s preconditions / '
      + 'postconditions / declinedPreconditions / declinedPostconditions — and NOT the '
      + '`conditionId` beside them. The same library entry attached at two suites is two '
      + 'attachments, and declining one leaves the other in force. Sending `[]` re-imposes every '
      + 'condition this case had turned off; omitting the field leaves the declines alone.',
  },
  {
    name: 'verdict',
    type: 'string',
    enum: PROPOSAL_VERDICT,
    enumOf: 'ProposalVerdict',
    contractSchema: 'AiProposalInput',
    actions: ['propose'],
    describe: 'Your own claim about whether you checked this case against the spec.',
    note:
      'Leave it out if you did not check. **Absent and `UNVERIFIED` are different answers** and '
      + 'the difference is recorded: both store the verdict `UNVERIFIED`, and they differ in '
      + '`verdictSource` — nobody claimed anything, against an agent claiming it had not checked. '
      + 'The reviewer sees a different sentence for each. `verdictSource` itself is derived by the '
      + 'server and can never be sent; an agent\'s claim is recorded, shown, attributed, and '
      + 'never counted as verified, because TCMS cannot see that agent\'s critic or its prompt.',
  },
  {
    name: 'classificationValueIds',
    type: 'string[]',
    set: true,
    actions: ['propose'],
    describe: 'The COMPLETE classification after this save — Test Type, Test Level, Feature.',
    note:
      'Ids, and tcms_orient serves the dimensions with their value PATHS rather than bare names '
      + 'because a hierarchical dimension can hold two values called "Login" under different '
      + 'parents. At most one value per SINGLE-cardinality dimension (Test Type, Test Level); a '
      + 'second is a 400 on this field. A parent value in the Feature tree is assignable, not '
      + 'only a leaf.',
  },
  {
    name: 'automationTags',
    type: 'string[]',
    set: true,
    actions: ['propose'],
    describe: 'Selection tags a CI runner filters on. Not the organisational `tags`.',
  },
  {
    name: 'estimatedDurationSec',
    type: 'integer',
    nullable: true,
    actions: ['propose'],
    describe: 'How long one manual run takes, in seconds.',
  },
];

export const CASE_SHAPE: EntityShape = {
  entity: 'case',
  title: 'Test case',
  contractSchema: 'TestCaseInput',
  actions: [
    {
      name: 'propose',
      kind: 'create',
      permission: 'case:create',
      mcp: true,
      cli: true,
      describe:
        'Files a GENERATED case for a person to accept. It does not create a test case, and '
        + 'nothing on either surface can: a credential cannot accept its own proposal.',
    },
    {
      name: 'edit',
      kind: 'edit',
      permission: 'case:edit',
      mcp: true,
      cli: true,
      describe: 'Changes a case that already exists.',
    },
    {
      name: 'submit',
      kind: 'state',
      permission: 'case:edit',
      mcp: true,
      cli: false,
      describe: 'Puts a case into review.',
    },
    {
      name: 'delete',
      kind: 'delete',
      permission: 'case:delete',
      mcp: true,
      cli: false,
      describe:
        'Soft-deletes a case. Past runs keep resolving what they covered, the display and '
        + 'automation keys stay reserved for ever, and an MCP host asks a person first.',
    },
  ],
  fields: CASE_FIELDS,
};

/** Every entity the catalogue covers, by name. */
export const SHAPES: Readonly<Record<string, EntityShape>> = { case: CASE_SHAPE };

export function shapeOf(entity: string): EntityShape | undefined {
  return SHAPES[entity];
}

/* -------------------------------------------------------------- selection ---- */

/**
 * Which half of a payload a field belongs to.
 *
 * `body` is the entity itself — what `TestCaseInput` carries. `wrapper` is everything the surface
 * sends *about* the write rather than as part of it, which today is `verdict` alone: a claim about
 * whether a proposal was checked is not a property of the case, and rendering it inside the case
 * body would offer a model a field the endpoint would reject.
 */
export type FieldScope = 'body' | 'wrapper' | 'all';

/** The fields one action accepts, in catalogue order. */
export function fieldsFor(
  shape: EntityShape,
  action: string,
  scope: FieldScope = 'all',
): readonly FieldSpec[] {
  return shape.fields.filter((field) => {
    if (field.actions && !field.actions.includes(action)) return false;
    const wrapper = field.contractSchema !== undefined && field.contractSchema !== shape.contractSchema;
    return scope === 'all' || (scope === 'wrapper') === wrapper;
  });
}

/**
 * Whether a field must be present, given what the caller has already filled in.
 *
 * Answers `false` for a conditional whose governing field is absent, which is the honest reading:
 * a caller who has not said `stepFormat` has not yet made `steps` required, and reporting it as
 * missing would name a field the caller cannot act on.
 */
export function isRequired(field: FieldSpec, payload: Record<string, unknown>): boolean {
  if (field.required === 'always') return true;
  if (!field.required) return false;
  return field.required.is.includes(String(payload[field.required.when]));
}

/* -------------------------------------------------------------- renderers ---- */

/**
 * JSON Schema, as an MCP tool's `inputSchema` takes it.
 *
 * `required` is emitted for the unconditional fields only. The conditional four are described in
 * their `description` text instead, because JSON Schema can state them only through a `oneOf` that
 * splits the shape in four — the same trade `openapi.yaml` records making and declining. A host
 * that validated against a `oneOf` here would reject a half-filled draft the caller was still
 * assembling, which is worse than a sentence a model reads.
 *
 * `nullable` renders as a type union rather than the OpenAPI keyword: this is JSON Schema on the
 * wire to a host, not OpenAPI, and `{"type": "string", "nullable": true}` means nothing there —
 * it would read as a plain string and a legitimate `null` would look like a type error.
 */
export function jsonSchemaFor(
  shape: EntityShape,
  action: string,
  scope: FieldScope = 'all',
): Record<string, unknown> {
  const fields = fieldsFor(shape, action, scope);
  const required = fields.filter((field) => field.required === 'always').map((field) => field.name);

  return {
    type: 'object',
    additionalProperties: false,
    ...(required.length > 0 ? { required } : {}),
    properties: Object.fromEntries(fields.map((field) => [field.name, propertyOf(field)])),
  };
}

function propertyOf(field: FieldSpec): Record<string, unknown> {
  const description = [
    field.describe,
    ...(field.required && field.required !== 'always'
      ? [`Required when ${field.required.when} is ${field.required.is.join(' or ')}.`]
      : []),
    ...(field.set ? ['A COMPLETE set: omit to leave alone, [] to clear.'] : []),
    ...(field.keyExample ? [`A display key, e.g. ${field.keyExample}.`] : []),
  ].join(' ');

  if (field.type === 'object[]') {
    return {
      type: 'array',
      description,
      items: {
        type: 'object',
        additionalProperties: false,
        required: (field.fields ?? []).filter((inner) => inner.required === 'always').map((inner) => inner.name),
        properties: Object.fromEntries((field.fields ?? []).map((inner) => [inner.name, propertyOf(inner)])),
      },
    };
  }

  if (field.type === 'string[]') return { type: 'array', items: { type: 'string' }, description };

  const base = field.type === 'integer' ? 'integer' : field.type;
  return {
    type: field.nullable ? [base, 'null'] : base,
    ...(field.enum ? { enum: field.nullable ? [...field.enum, null] : [...field.enum] } : {}),
    description,
  };
}

/** The flag a field is spelled with at a terminal. */
export function flagOf(field: FieldSpec): string {
  return field.cli?.flag ?? kebab(field.name);
}

function kebab(name: string): string {
  return name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`);
}

/**
 * The mechanics flags a command takes on top of its entity's fields — **a pure function of the
 * kind it already declares.**
 *
 * Walking all sixteen CLI write commands found this holds without exception: every `create` takes
 * `--idempotency-key` (5/5), every `edit` takes `--lock-version` (4/4), and no `state` or `delete`
 * takes either (7/7). That is not a coincidence to be preserved by hand — it is surface spec §4.2
 * and §4.3 restated, the same two rules `writeConformance.test.ts` already asserts over both
 * registries — so it is derived here rather than repeated per command.
 *
 * **`--file` is deliberately not in this list.** It is on six of the ten body-carrying commands
 * and missing from `suite create`, `plan create`, `run create` and `result record`, which reads as
 * an accident rather than a decision. Deriving it would silently widen the CLI surface, which is
 * not what this slice was asked for; it stays declared per command and the conformance check
 * reports the gap instead.
 */
export function mechanicsFor(kind: ActionKind): Record<string, { type: 'string' }> {
  if (kind === 'create') return { 'idempotency-key': { type: 'string' } };
  if (kind === 'edit') return { 'lock-version': { type: 'string' } };
  return {};
}

/**
 * A CLI `OptionSpec`, in `util.parseArgs` shape.
 *
 * Every flag is `'string'` unless the field is a boolean, including the enums: `parseArgs` has no
 * notion of a permitted value, so the enum reaches a user through `usageFor` and through the
 * refusal `validateShape` composes, not through the parse.
 *
 * A `negate` binding contributes a second, boolean flag — `--unfile` beside `--suite`. It is the
 * only way argv can say `null`, and the asymmetry with the MCP schema is real rather than an
 * oversight; see {@link CliBinding.negate}.
 */
export function optionSpecFor(
  shape: EntityShape,
  action: string,
): Record<string, { type: 'string' | 'boolean'; multiple?: boolean }> {
  const spec: Record<string, { type: 'string' | 'boolean'; multiple?: boolean }> = {};

  for (const field of fieldsFor(shape, action)) {
    // An object array cannot be spelled in argv at all — `--file` carries those, which is why a
    // command taking steps or conditions has no per-field flag for them.
    if (field.type === 'object[]') continue;

    spec[flagOf(field)] = field.cli?.multiple
      ? { type: 'string', multiple: true }
      : { type: field.type === 'boolean' ? 'boolean' : 'string' };

    if (field.cli?.negate) spec[field.cli.negate] = { type: 'boolean' };
  }

  const kind = shape.actions.find((candidate) => candidate.name === action)?.kind;
  return { ...spec, ...(kind ? mechanicsFor(kind) : {}) };
}

/** The `--help` body: every flag, whether it is required, and what it takes. */
export function usageFor(shape: EntityShape, action: string): string {
  const lines = fieldsFor(shape, action)
    .filter((field) => field.type !== 'object[]')
    .map((field) => {
      const marks = [
        field.required === 'always' ? 'required' : undefined,
        field.required && field.required !== 'always'
          ? `required when --${kebab(field.required.when)} is ${field.required.is.join(' or ')}`
          : undefined,
        field.set ? 'complete set' : undefined,
        field.enum ? field.enum.join(' | ') : undefined,
        field.keyExample ? `e.g. ${field.keyExample}` : undefined,
      ].filter(Boolean);

      return `  --${flagOf(field)}${field.cli?.multiple ? ' (repeatable)' : ''}`
        + `\n      ${field.describe}`
        + (marks.length > 0 ? `\n      [${marks.join('; ')}]` : '')
        + (field.cli?.negate ? `\n      --${field.cli.negate} clears it.` : '');
    });

  const objects = fieldsFor(shape, action).filter((field) => field.type === 'object[]');
  const viaFile = objects.length > 0
    ? `\n\n${objects.map((field) => `  ${field.name}`).join(', ')} cannot be spelled in argv. `
      + 'Pass them in the --file body.'
    : '';

  return `${shape.title} — ${action}\n\n${lines.join('\n')}${viaFile}`;
}

/**
 * A filled worked skeleton — what `tcms_shape` and `tcms format` hand back.
 *
 * Placeholders rather than plausible content, and the difference matters: a template carrying a
 * realistic title is one a model will file verbatim. Every value here is either the field's first
 * enum member or an angle-bracketed slot that could not be mistaken for an answer.
 */
export function templateFor(shape: EntityShape, action: string): Record<string, unknown> {
  const body: Record<string, unknown> = {};

  for (const field of fieldsFor(shape, action, 'body')) {
    if (field.required !== 'always') continue;
    body[field.name] = exampleOf(field);
  }
  return body;
}

function exampleOf(field: FieldSpec): unknown {
  if (field.enum) return field.enum[0];
  if (field.type === 'string[]') return [];
  if (field.type === 'integer') return 0;
  if (field.type === 'boolean') return false;
  if (field.type === 'object[]') {
    return [Object.fromEntries(
      (field.fields ?? [])
        .filter((inner) => inner.required === 'always')
        .map((inner) => [inner.name, exampleOf(inner)]),
    )];
  }
  return field.keyExample ? `<${field.keyExample}>` : `<${field.name}>`;
}

/**
 * The long-form guide — the MCP resource, and `tcms format --long`.
 *
 * Markdown, and structured markdown only: no width, no colour. This is the one carrier with room
 * for {@link FieldSpec.note}, which is where the pitfalls live — the attachment-versus-condition
 * id, the complete-set rules, why a step is copied and a condition is referenced. None of them fit
 * in a tool schema and all of them are things a model gets wrong once and cannot see it did.
 */
export function guideFor(shape: EntityShape): string {
  const out: string[] = [`# ${shape.title}`, ''];

  out.push('## Actions', '');
  for (const action of shape.actions) {
    const where = [action.mcp ? 'MCP' : undefined, action.cli ? 'CLI' : undefined]
      .filter(Boolean).join(', ') || 'neither adapter';
    out.push(`- **${action.name}** (\`${action.permission}\`, ${where}) — ${action.describe}`);
  }

  for (const action of shape.actions.filter((candidate) => candidate.kind === 'create' || candidate.kind === 'edit')) {
    out.push('', `## Fields — \`${action.name}\``, '');
    for (const field of fieldsFor(shape, action.name)) out.push(...fieldSection(field, 3));
  }

  out.push('', '## Template', '', '```json', JSON.stringify(templateFor(shape, shape.actions[0]!.name), null, 2), '```');
  return out.join('\n');
}

function fieldSection(field: FieldSpec, level: number): string[] {
  const marks = [
    field.required === 'always' ? '**required**' : undefined,
    field.required && field.required !== 'always'
      ? `**required when \`${field.required.when}\` is ${field.required.is.join(' or ')}**`
      : undefined,
    field.set ? '**complete set** — omit to leave alone, `[]` to clear' : undefined,
    field.nullable ? '`null` is meaningful' : undefined,
    field.enum ? `one of ${field.enum.map((value) => `\`${value}\``).join(', ')}` : undefined,
    field.keyExample ? `display key, e.g. \`${field.keyExample}\`` : undefined,
  ].filter(Boolean);

  const out = [
    `${'#'.repeat(level)} \`${field.name}\` — ${field.type}`,
    '',
    field.describe,
    ...(marks.length > 0 ? ['', marks.map((mark) => `- ${mark}`).join('\n')] : []),
    ...(field.note ? ['', field.note] : []),
    '',
  ];

  for (const inner of field.fields ?? []) out.push(...fieldSection(inner, level + 1));
  return out;
}

/* -------------------------------------------------------------- validation ---- */

/**
 * Refuses a payload that could not be accepted, naming every missing field at once.
 *
 * The generalisation of `requireProposableCase`, whose argument this inherits wholesale: all of
 * them in one message and never the first, because a caller that fixes one field and resubmits
 * three times has filed three proposals a person has to reject.
 *
 * **A presence check and deliberately nothing more.** Which *values* are legal is the server's to
 * say; a second copy of `CaseEdit.validate` here would be a second answer to re-diverge. Enum
 * membership is the one exception, and only because the permitted values are already in the
 * catalogue and pinned to the contract — checking them costs nothing and turns a round trip into
 * a local message.
 */
export function violationsOfShape(
  shape: EntityShape,
  action: string,
  payload: Record<string, unknown>,
): string[] {
  const problems: string[] = [];

  for (const field of fieldsFor(shape, action)) {
    const value = payload[field.name];
    const empty = value === undefined || value === null || value === ''
      || (Array.isArray(value) && value.length === 0);

    if (isRequired(field, payload) && empty) {
      problems.push(
        field.required === 'always'
          ? field.name
          : `${field.name} (required when ${(field.required as { when: string }).when} is `
            + `${String(payload[(field.required as { when: string }).when])})`,
      );
      continue;
    }

    if (!empty && field.enum && typeof value === 'string' && !field.enum.includes(value)) {
      problems.push(`${field.name} must be one of ${field.enum.join(', ')} — not '${value}'`);
    }
  }

  return problems;
}
