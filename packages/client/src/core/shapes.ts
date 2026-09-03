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
  readonly type: 'string' | 'integer' | 'number' | 'boolean' | 'string[]' | 'string[][]' | 'object[]';
  readonly required?: Requirement;
  /** The permitted values, pinned to {@link enumOf}. */
  readonly enum?: readonly string[];
  /**
   * The contract schema whose `enum` {@link FieldSpec.enum} must equal, checked at test time.
   *
   * Naming the schema rather than repeating "these four strings are the priorities" is what makes
   * the pin mechanical: a value added to `Priority` in the contract reddens `packages/`.
   *
   * Two spellings, because the contract has two. `'Priority'` names a component schema that IS an
   * enum; `'ArtifactInput.scope'` names a property carrying an inline one. The second is not a
   * lesser case to be tidied later — an enum used by one operation is reasonably written where it
   * is used, and a pin that only worked on extracted enums would silently cover none of them.
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
  /**
   * A literal value a caller can copy on its first call.
   *
   * `tool-mcp-builder` step 4: *every field description ends with its example — it is the agent's
   * first-call template*. Derived where the catalogue already knows one (an enum's first member, a
   * `keyExample`), so this is only written out for the fields where neither applies and the format
   * is the thing a caller would otherwise guess — a date, a duration, a commit sha.
   */
  readonly example?: string;
  /** The pitfall. Long-form guide only — it will not fit in a tool schema. */
  readonly note?: string;
  /** `steps`, `preconditions`. */
  readonly fields?: readonly FieldSpec[];
  /**
   * The contract schema an `object[]`'s items render to, so the nested shape is pinned too.
   *
   * Required on every `object[]`, and the reason is a defect this file shipped for one commit:
   * the doc-page blocks were written as `{ type, text }` when `DocBlockInput` declares
   * `blockType` — a field name that does not exist, offered to a model as if it did. The outer
   * check could not see it, because it only ever looked at top-level properties.
   */
  readonly itemsSchema?: string;
}

/** What `WriteDeclaration.kind` already says, restated here so a renderer can read it. */
export type ActionKind = 'create' | 'edit' | 'state' | 'delete';

export interface ActionSpec {
  readonly name: string;
  readonly kind: ActionKind;
  readonly permission: string;
  /** False when this adapter has no such command — see `UNOFFERED` in the conformance check. */
  readonly mcp: boolean;
  readonly cli: boolean;
  /**
   * How the CLI spells this action's fields: as flags, or as the JSON body of `--file`.
   *
   * Recorded because printing the wrong one is actively misleading — `tcms case propose` takes
   * `--file` and no per-field flag, so a help screen listing `--title` names an option that does
   * not exist. The MCP has no equivalent split: every field is a key of one JSON argument there.
   */
  readonly cliSpelling?: 'flags' | 'file';
  readonly describe: string;
}

export interface EntityShape {
  readonly entity: string;
  readonly title: string;
  /**
   * What one of these looks like when it is named — `TC-42`.
   *
   * The positional argument of every command that acts on an existing row, so a worked example
   * that omitted it would not run. Absent on an entity with no display key of its own.
   */
  readonly keyExample?: string;
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
const STEP_KEYWORD = ['GIVEN', 'WHEN', 'THEN', 'AND', 'BUT'] as const;
const AUTOMATION_STATUS = ['MANUAL', 'TO_BE_AUTOMATED', 'AUTOMATED', 'NOT_AUTOMATABLE'] as const;
const PROPOSAL_VERDICT = ['GROUNDED', 'PARTIAL', 'HALLUCINATED', 'UNVERIFIED'] as const;

/**
 * The enums a caller **filters** by, as against the ones it writes.
 *
 * The write side has enumerated its enums since §203, when `verdict`'s description told every
 * caller to send `APPROVED` — a member the schema does not have — and *"the description was the
 * whole of the contract the caller had"*. The fix was stated as a rule and applied to one property:
 * the read tools went on describing `lifecycleStatus` as *"DRAFT, IN_REVIEW, APPROVED,
 * DEPRECATED."* in prose, and a filter value is refused exactly as hard as a body value.
 *
 * Prose is not merely a weaker spelling of an enum, it is an unpinned one, and it had already
 * drifted: the condition-library filter was documented as *"PRE or POST"* while the contract has
 * taken `[PRE, POST, BOTH]` all along, so the one value that narrows nothing was undocumented on
 * the only surface offering the filter.
 *
 * Keyed by the contract site each is pinned to, on the same rule as {@link FieldSpec.enumOf} —
 * `shapesContract.test.ts` reads this map and compares it to `openapi.yaml`, so a member added
 * there reddens `packages/` rather than being discovered as a 400.
 */
export const READ_ENUMS = {
  lifecycleStatus: { of: 'LifecycleStatus', values: ['DRAFT', 'IN_REVIEW', 'APPROVED', 'DEPRECATED'] },
  automationStatus: { of: 'AutomationStatus', values: AUTOMATION_STATUS },
  priority: { of: 'Priority', values: PRIORITY },
  healthSignal: {
    of: 'HealthSignal',
    values: [
      'OUTDATED_SPEC_CHANGED', 'STALE_APPROVAL', 'CONDITION_DRIFT', 'STALE_AUTOMATION',
      'NEVER_EXECUTED', 'LONG_UNEXECUTED', 'UNLINKED', 'FAILING_REPEATEDLY', 'ORPHANED',
      'INCOMPLETE', 'UNDECLARED_AUTOMATION',
    ],
  },
  conditionType: { of: 'Condition.type', values: ['PRE', 'POST', 'BOTH'] },
  proposalStatus: { of: 'ProposalStatus', values: ['PENDING', 'ACCEPTED', 'REJECTED'] },
  artifactScope: { of: 'Artifact.scope', values: ['PROJECT', 'PLAN'] },
} as const satisfies Record<string, { of: string; values: readonly string[] }>;

/* ------------------------------------------------------------- test case ---- */

/**
 * A step, which is **text copied at authoring time** and not a reference.
 *
 * **One set of rows serves both lenses (§255).** `stepFormat` selects how a case is READ, not
 * where its procedure is stored: a GHERKIN case is these same rows with a `keyword` on each, and
 * the free-text scenario blob it used to be is gone from the write contract entirely. So there is
 * no second body to compose and nothing to keep in step with these rows.
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
    enum: STEP_KEYWORD,
    enumOf: 'StepKeyword',
    describe:
      'The Gherkin keyword — Given, When, Then, And, But. Required on every step of a GHERKIN '
      + 'case and optional in a STRUCTURED one.',
    note:
      'The condition is on the CASE, not on this step, which is why it is not spelled as a '
      + 'requirement here: `stepFormat` is a field of the case and a step cannot see it. '
      + '`CaseEdit.validate` collects both lens rules as field errors on `steps` — every step of '
      + 'a GHERKIN case needs one, and the FIRST step may not be AND or BUT, which is a '
      + 'continuation with nothing to continue.',
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
  {
    name: 'docString',
    type: 'string',
    nullable: true,
    example: 'Dear customer,\\nYour order shipped.',
    describe: 'Gherkin\'s `"""` block argument — a multi-line string hanging off this one step.',
    note:
      'At most 64 KiB: a block argument larger than that is a file, not a step argument. The '
      + 'fences are not part of the value — send the text between them.',
  },
  {
    name: 'dataTable',
    type: 'string[][]',
    nullable: true,
    example: '[["name","qty"],["widget","2"]]',
    describe: 'Gherkin\'s `|` table argument, as rows of cells.',
    note:
      'At most 200 rows of at most 50 cells, each cell at most 4 KiB. The first row is a header '
      + 'only by convention and nothing here treats it as one; rows may be ragged. An **empty '
      + 'outer array is refused** — `[]` is not a table, and sending it rather than null is how a '
      + 'client stores a step that renders an empty grid nobody can remove.\n\n'
      + '**At most one of `testData`, `docString` and `dataTable` is non-null** — one step, one '
      + 'data spelling. `ck_test_step_one_data` is the backstop; the field error is the answer a '
      + 'caller actually gets.',
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
    describe: 'STRUCTURED for a step table, GHERKIN for a scenario. It selects a LENS over one set of steps.',
    actions: ['propose'],
    note:
      'Absent from the `edit` arm on both adapters and that is not an omission: flipping the '
      + 'lens alone would leave a GHERKIN case whose steps carry no keyword, which '
      + '`CaseEdit.validate` refuses with a field error on `steps`. Changing it means sending '
      + 'the steps the new lens needs in the same save, and neither adapter\'s edit arm takes '
      + 'steps.',
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
    required: { when: 'stepFormat', is: ['STRUCTURED', 'GHERKIN'] },
    fields: STEP_FIELDS,
    itemsSchema: 'TestStepInput',
    actions: ['propose'],
    describe:
      'The procedure, in order, in either lens. In GHERKIN every step needs a keyword and the '
      + 'first may not be And/But.',
    note:
      'Required in BOTH lenses since §255, where it was STRUCTURED only: a GHERKIN case is '
      + 'these rows with keywords, so there is no longer a second field to send instead. '
      + 'Order is the content — position is assigned from the array and is never sent. The server '
      + 'would accept a stepless case (§200 deferred the procedure, so a case may exist before '
      + 'anybody has written how to run it), and both non-SPA clients refuse one anyway: that '
      + 'deferral was granted to a person typing a title into a plan they are writing, not to a '
      + 'model asked for the case itself. A proposal with no procedure gains the reviewer nothing '
      + 'they could not have got by typing the title.',
  },
  {
    name: 'automationKey',
    type: 'string',
    nullable: true,
    required: { when: 'automationStatus', is: ['AUTOMATED'] },
    example: 'login.smoke.happyPath',
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
    itemsSchema: 'CaseConditionInput',
    actions: ['propose'],
    describe: 'Library conditions that must hold before the steps run, in order.',
  },
  {
    name: 'postconditions',
    type: 'object[]',
    fields: CONDITION_FIELDS,
    itemsSchema: 'CaseConditionInput',
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
    example: '180',
      describe: 'How long one manual run takes, in seconds.',
  },
];

export const CASE_SHAPE: EntityShape = {
  entity: 'case',
  keyExample: 'TC-42',
  title: 'Test case',
  contractSchema: 'TestCaseInput',
  actions: [
    {
      name: 'propose',
      kind: 'create',
      permission: 'case:create',
      mcp: true,
      cli: true,
      cliSpelling: 'file',
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
      cliSpelling: 'flags',
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

/* ------------------------------------------------------------------ suite ---- */

/**
 * A suite is **flat since §98** — a named collection whose members are three arms (filed, added,
 * tagged), not a folder in a tree. The fields below are why that matters to an author: `cases`
 * adds membership directly and `tagRules` resolves it, and the two are different acts.
 */
const SUITE_SHAPE: EntityShape = {
  entity: 'suite',
  keyExample: 'TS-7',
  title: 'Suite',
  contractSchema: 'SuiteInput',
  actions: [
    { name: 'create', kind: 'create', permission: 'suite:manage', mcp: true, cli: true, cliSpelling: 'flags', describe: 'Creates a suite.' },
    { name: 'edit', kind: 'edit', permission: 'suite:manage', mcp: true, cli: true, cliSpelling: 'flags', describe: 'Changes one.' },
    { name: 'status', kind: 'state', permission: 'suite:manage', mcp: true, cli: true, describe: 'Submits it for review, or withdraws it. Approving costs suite:approve, which is not on this surface.' },
    { name: 'delete', kind: 'delete', permission: 'suite:delete', mcp: true, cli: false, describe: 'Removes a suite. The cases in it are not touched.' },
  ],
  fields: [
    { name: 'name', type: 'string', required: 'always', describe: 'What this collection is called.' },
    { name: 'description', type: 'string', nullable: true, describe: 'What belongs in it and why.' },
    {
      name: 'tagRules',
      type: 'string[]',
      set: true,
      cli: { flag: 'tag-rule', multiple: true },
      describe: 'Tags whose cases this suite RESOLVES — the COMPLETE set.',
      note:
        'A rule is not a copy: a case carrying the tag belongs to the suite for as long as it '
        + 'carries it, and stops belonging when the tag comes off. That is the difference from '
        + '`cases`, which is direct membership and survives a retag.',
    },
    {
      name: 'cases',
      type: 'string[]',
      set: true,
      contract: 'caseIds',
      keyExample: 'TC-42',
      cli: { flag: 'case', multiple: true },
      describe: 'Cases added to this suite directly, by display key — the COMPLETE set.',
    },
    {
      name: 'preconditions',
      type: 'object[]',
      fields: CONDITION_FIELDS,
      itemsSchema: 'CaseConditionInput',
      cli: { flag: 'precondition', multiple: true },
      describe: 'Library conditions every case in this suite inherits before its steps.',
      note:
        'Offered on the create as well as the edit, and not for symmetry: a suite\'s conditions '
        + 'reach the cases filed in it, so "create it, then attach the conditions" is a window in '
        + 'which those cases run without a setup step their author intended. A case can decline an '
        + 'inherited condition — see the case shape\'s `declinedConditions`.',
    },
    {
      name: 'postconditions',
      type: 'object[]',
      fields: CONDITION_FIELDS,
      itemsSchema: 'CaseConditionInput',
      cli: { flag: 'postcondition', multiple: true },
      describe: 'Library conditions every case in this suite inherits after its steps.',
    },
  ],
};

/* ------------------------------------------------------------------- plan ---- */

const PLAN_SHAPE: EntityShape = {
  entity: 'plan',
  keyExample: 'TP-3',
  title: 'Test plan',
  contractSchema: 'TestPlanInput',
  actions: [
    { name: 'create', kind: 'create', permission: 'plan:create', mcp: true, cli: true, cliSpelling: 'flags', describe: 'Creates a plan.' },
    { name: 'edit', kind: 'edit', permission: 'plan:edit', mcp: true, cli: true, cliSpelling: 'flags', describe: 'Changes one.' },
    { name: 'status', kind: 'state', permission: 'plan:edit', mcp: true, cli: true, describe: 'Submits it for review, or withdraws it.' },
    { name: 'delete', kind: 'delete', permission: 'plan:delete', mcp: true, cli: false, describe: 'Removes a plan.' },
  ],
  fields: [
    { name: 'name', type: 'string', required: 'always', describe: 'What this plan covers.' },
    { name: 'description', type: 'string', nullable: true, describe: 'Its purpose, in prose.' },
    /*
      The four flag bindings below (`milestone`, and `release`/`build`/`commit` on a run) are
      recorded rather than derived, and they are the defect the catalogue↔surface pin found: the
      commands have shipped these spellings since §137, `flagOf` kebab-cased the field name
      instead, and `tcms format plan` therefore printed `--milestone-name` — a flag `parseArgs`
      refuses in strict mode. The catalogue was telling callers to type something no command
      accepts. Renaming the shipped flags would be the other repair and a worse one; a spelling is
      a promise to whoever already scripted against it.
    */
    { name: 'milestoneName', type: 'string', nullable: true, cli: { flag: 'milestone' }, describe: 'The release or milestone it belongs to.' },
    { name: 'milestoneDate', type: 'string', nullable: true, example: '2026-09-30', describe: 'When that milestone is due, as a date.' },
    {
      name: 'suites',
      type: 'string[]',
      set: true,
      contract: 'suiteIds',
      keyExample: 'TS-7',
      cli: { flag: 'suite', multiple: true },
      describe: 'Suites in scope for this plan — the COMPLETE set.',
    },
    {
      name: 'cases',
      type: 'string[]',
      set: true,
      contract: 'caseIds',
      keyExample: 'TC-42',
      cli: { flag: 'case', multiple: true },
      describe: 'Individual cases in scope, beyond whole suites — the COMPLETE set.',
    },
  ],
};

/* --------------------------------------------------------------- doc page ---- */

const DOC_PAGE_SHAPE: EntityShape = {
  entity: 'docPage',
  keyExample: 'DOC-1',
  title: 'Documentation page',
  contractSchema: 'DocPageInput',
  actions: [
    { name: 'create', kind: 'create', permission: 'doc:edit', mcp: true, cli: true, cliSpelling: 'flags', describe: 'Creates a page.' },
    { name: 'edit', kind: 'edit', permission: 'doc:edit', mcp: true, cli: true, cliSpelling: 'flags', describe: 'Rewrites its blocks.' },
    { name: 'link', kind: 'state', permission: 'doc:edit', mcp: true, cli: true, describe: 'Links a block to a case or a suite.' },
    { name: 'unlink', kind: 'delete', permission: 'doc:edit', mcp: true, cli: true, describe: 'Removes one such link. Nothing about the page changes.' },
    { name: 'publish', kind: 'state', permission: 'doc:publish', mcp: true, cli: false, describe: 'Moves the page through its lifecycle.' },
  ],
  fields: [
    { name: 'title', type: 'string', required: 'always', describe: 'The page heading.' },
    {
      name: 'parent',
      type: 'string',
      nullable: true,
      contract: 'parentId',
      keyExample: 'DOC-1',
      // `--root` is the clearing form, on the same rule as `--unfile` beside `--suite`: a nullable
      // field with no way to send null is one the CLI can never empty. `doc edit` had neither
      // spelling until the catalogue↔surface pin asked for the first.
      cli: { negate: 'root' },
      describe: 'The page this one sits under. Null puts it at the root.',
    },
    {
      name: 'blocks',
      type: 'object[]',
      itemsSchema: 'DocBlockInput',
      describe: 'The page body, in order — the COMPLETE set on an edit.',
      note:
        'A rewrite, not a patch: the blocks you send are the page afterwards. A block a link '
        + 'points at that is not in the new set takes the link with it, which is why `link` and '
        + '`unlink` are separate actions rather than fields here.',
      fields: [
        {
          name: 'blockType',
          type: 'string',
          required: 'always',
          describe: 'The block kind — heading, paragraph, list, table, attachment.',
        },
        { name: 'text', type: 'string', required: 'always', describe: 'Its content.' },
        { name: 'level', type: 'integer', nullable: true, describe: 'Heading depth, for a heading block.' },
        { name: 'attachmentId', type: 'string', nullable: true, describe: 'The file this block embeds.' },
      ],
    },
  ],
};

/* -------------------------------------------------------------------- run ---- */

const RUN_SHAPE: EntityShape = {
  entity: 'run',
  keyExample: 'RUN-12',
  title: 'Test run',
  contractSchema: 'TestRunInput',
  actions: [
    { name: 'create', kind: 'create', permission: 'run:create', mcp: true, cli: true, cliSpelling: 'flags', describe: 'Opens a run over a selection of cases.' },
    { name: 'clone', kind: 'create', permission: 'run:create', mcp: true, cli: false, describe: 'Opens a new run with the same scope as an existing one.' },
    { name: 'status', kind: 'state', permission: 'run:close', mcp: true, cli: true, describe: 'Closes or reopens a run.' },
  ],
  fields: [
    { name: 'name', type: 'string', required: 'always', describe: 'What this run is called.' },
    {
      name: 'plan',
      type: 'string',
      nullable: true,
      contract: 'planId',
      keyExample: 'TP-3',
      describe: 'The plan this run executes, if any.',
    },
    {
      name: 'suites',
      type: 'string[]',
      set: true,
      contract: 'suiteIds',
      keyExample: 'TS-7',
      cli: { flag: 'suite', multiple: true },
      describe: 'Suites whose cases this run covers.',
    },
    {
      name: 'cases',
      type: 'string[]',
      set: true,
      contract: 'caseIds',
      keyExample: 'TC-42',
      cli: { flag: 'case', multiple: true },
      describe: 'Individual cases to include beyond the suites.',
    },
    { name: 'tags', type: 'string[]', set: true, cli: { flag: 'tag', multiple: true }, describe: 'Include every case carrying these tags.' },
    { name: 'releaseVersion', type: 'string', nullable: true, cli: { flag: 'release' }, example: '2026.9.0', describe: 'The release under test.' },
    { name: 'buildNumber', type: 'string', nullable: true, cli: { flag: 'build' }, example: '4821', describe: 'The build under test.' },
    { name: 'commitSha', type: 'string', nullable: true, cli: { flag: 'commit' }, example: '9f2c1ab', describe: 'The commit under test.' },
    { name: 'branch', type: 'string', nullable: true, example: 'release/2026.9', describe: 'The branch under test.' },
  ],
};

/* ----------------------------------------------------------------- result ---- */

const RESULT_SHAPE: EntityShape = {
  entity: 'result',
  title: 'Execution result',
  contractSchema: 'ExecutionResultInput',
  actions: [
    { name: 'record', kind: 'edit', permission: 'result:submit', mcp: true, cli: true, cliSpelling: 'flags', describe: 'Records the outcome of executing one case in one run.' },
  ],
  fields: [
    {
      name: 'status',
      type: 'string',
      required: 'always',
      contract: 'statusId',
      describe: 'A status NAME from tcms_orient\'s executionStatuses, not a UUID.',
      note:
        'Per-project and configurable — a project may rename "Blocked" to "Environment down" — so '
        + 'a status guessed from another project is refused. `tcms_orient` serves this project\'s '
        + 'own list with the fixed aggregation kind beside each name, which is what a rollup reads.',
    },
    { name: 'comment', type: 'string', nullable: true, describe: 'What happened, for a person reading the run later.' },
    { name: 'durationMs', type: 'integer', nullable: true, example: '4200', describe: 'How long the execution took, in milliseconds.' },
  ],
};

/* ------------------------------------------------------------------ issue ---- */

/**
 * ~~`WORK_ITEM_SHAPE`, over `WorkItemInput`.~~ → **superseded by 011 M1**: `work_item` is gone and
 * every stored board card is a `tracker` issue, so the entity is `issue` and the contract schema is
 * `IssueInput`. `workitem:manage` is superseded by the `issue:*` family (roles spec §A3).
 *
 * ## The type and the status are NAMES, and that is the whole design of this entry
 *
 * `IssueInput.typeId` and `IssueTransitionInput.statusId` are UUIDs of per-project rows. Handed a
 * bare UUID field a model will invent one, so both are taken here as a **name** and resolved against
 * `GET /projects/{projectId}/workflow` — the same answer {@link RESULT_SHAPE}'s `status` already
 * gives for the same reason, and the note there is the one this follows.
 *
 * The client checks the **vocabulary** and the server checks the **legality**: a status this project
 * does not have is refused before the request, naming what it does have; a status it has that the
 * workflow forbids from where this issue stands is the server's 409, whose `detail` names the moves
 * that are allowed. A legal-move ladder here would be a second copy of the one rule `tracker` exists
 * to enforce.
 *
 * ## Five arms of `tcms_write_issue` are deliberately NOT actions here
 *
 * `move-card` and `label` (012 T5), `cases` and `release` (013 T4) and `attach-pr` (015 T4) are
 * arms on both adapters and have no `ActionSpec` below. That is a rule rather than five omissions:
 * **the catalogue carries an action only when it has a payload of its own to describe.** Each of
 * those five sends exactly one argument that is not a property of `IssueInput` — a column name, a
 * label set, a case-key set, a version, a PR URL — so a catalogue entry would be an action whose
 * field list is empty, printed by `tcms format` as a shape with nothing in it.
 *
 * `attach-pr` is the sharpest instance and worth naming, because it is the one that has a contract
 * property. `prUrl` is on `IssueUpdate` and on **no** other schema, so cataloguing it would pull
 * `IssueUpdate` into `shapesContract.test.ts`'s accounted set — and every other field of it is
 * already offered at its `IssueInput` site by the `update` action, so the accounting would have to
 * be satisfied with a dozen `UNOFFERED` lines declaring fields unoffered that this surface offers.
 * A check answered by writing down the opposite of what is true is worse than no entry.
 */
export const ISSUE_SHAPE: EntityShape = {
  entity: 'issue',
  title: 'Issue',
  keyExample: 'ACME-124',
  contractSchema: 'IssueInput',
  actions: [
    { name: 'create', kind: 'create', permission: 'issue:create', mcp: true, cli: true, cliSpelling: 'flags', describe: 'Raises an epic, story, bug, spike or task.' },
    // MCP only. A field-by-field update is composed from a read the model has already made; a
    // person at a terminal reaches for `move` and `comment`, which is what the CLI offers.
    { name: 'update', kind: 'edit', permission: 'issue:edit', mcp: true, cli: false, cliSpelling: 'flags', describe: 'Changes its fields. NOT its status.' },
    { name: 'move', kind: 'state', permission: 'issue:edit', mcp: true, cli: true, describe: 'Moves it to another status, if the workflow allows that exact pair.' },
    { name: 'comment', kind: 'state', permission: 'issue:comment', mcp: true, cli: true, describe: 'Says something about it, and starts watching it.' },
  ],
  fields: [
    {
      name: 'typeName',
      type: 'string',
      required: 'always',
      contract: 'typeId',
      actions: ['create'],
      cli: { flag: 'type' },
      example: 'Story',
      describe: 'A type NAME from the project workflow — Epic, Story, Bug, Spike, Task — not a UUID.',
      note:
        'Per-project and configurable, so a type guessed from another project is refused with the '
        + "list this project has. The type's LEVEL decides `parentKey`: an EPIC-level type refuses "
        + 'one, a TASK-level type requires one, and a story-level type takes an optional epic.',
    },
    {
      name: 'statusName',
      type: 'string',
      required: 'always',
      contract: 'statusId',
      contractSchema: 'IssueTransitionInput',
      actions: ['move'],
      cli: { flag: 'status' },
      example: 'In Review',
      describe: 'A status NAME from the project workflow, not a UUID.',
      note:
        'A status this project does not have is refused here, naming the ones it does. A status it '
        + 'has that the workflow forbids FROM WHERE THIS ISSUE STANDS is the server\'s 409, which '
        + 'names the moves that are allowed — read them off `allowedTransitions` first. An Epic is '
        + 'refused whatever the target: its status is derived from its stories.',
    },
    { name: 'title', type: 'string', required: 'always', actions: ['create', 'update'], describe: 'What needs doing, in one line.' },
    { name: 'description', type: 'string', nullable: true, actions: ['create', 'update'], describe: 'The detail.' },
    {
      name: 'parentKey',
      type: 'string',
      nullable: true,
      actions: ['create', 'update'],
      keyExample: 'ACME-12',
      cli: { flag: 'parent' },
      describe: 'The issue this one breaks down. Required for a TASK-level type, refused for an EPIC-level one.',
    },
    { name: 'priority', type: 'string', enum: PRIORITY, enumOf: 'Priority', actions: ['create', 'update'], describe: 'How urgent it is.' },
    {
      name: 'estimateHours',
      type: 'number',
      nullable: true,
      actions: ['create', 'update'],
      example: '6.5',
      describe: 'Hours, never points — the same unit a budget and an invoice use.',
    },
    { name: 'startDate', type: 'string', nullable: true, actions: ['create', 'update'], example: '2026-09-01', describe: 'Epics only. Ignored on every other level.' },
    { name: 'targetDate', type: 'string', nullable: true, actions: ['create', 'update'], example: '2026-10-15', describe: 'Epics only. Ignored on every other level.' },
    { name: 'dueDate', type: 'string', nullable: true, actions: ['create', 'update'], example: '2026-09-30', describe: 'When it is due, as a date.' },
    {
      name: 'acceptanceCriteria',
      type: 'string[]',
      actions: ['create'],
      cli: { flag: 'acceptance-criterion', multiple: true },
      describe: 'What has to be true for this to be done, one line each.',
      note:
        'CREATE ONLY. `IssueUpdate` takes `{text, done}` objects against an INLINE item schema with '
        + 'no component name, and every nested pin in `shapesContract.test.ts` resolves a named one '
        + '— so ticking a criterion off is the story screen\'s, not this surface\'s. Written down '
        + 'rather than discovered as a missing field.',
    },
    {
      name: 'agentEligible',
      type: 'boolean',
      actions: ['create', 'update'],
      describe: 'Flags this as a good candidate for an AI assistant. A hint and a filter, not an assignment.',
      note:
        'D30. It assigns nothing and grants nothing — an assistant is its owner\'s token (D26), so '
        + 'the queue an assistant reads is `agentEligible` AND assigned to that owner. Setting it '
        + 'on an unassigned issue puts it in nobody\'s queue, which is why the marker and the '
        + 'assignee are two separate answers on every screen that shows both.',
    },
    {
      name: 'body',
      type: 'string',
      required: 'always',
      contractSchema: 'IssueCommentInput',
      actions: ['comment'],
      describe: 'The remark. Commenting adds you as a watcher, so you hear about what happens next.',
    },
  ],
};

/* --------------------------------------------------------------- artifact ---- */

const ARTIFACT_SHAPE: EntityShape = {
  entity: 'artifact',
  title: 'Artifact',
  contractSchema: 'ArtifactInput',
  actions: [
    { name: 'register', kind: 'create', permission: 'artifact:edit', mcp: true, cli: false, cliSpelling: 'flags', describe: 'Registers a reference — a doc page, a URL, or a generated report.' },
    { name: 'edit', kind: 'edit', permission: 'artifact:edit', mcp: true, cli: false, cliSpelling: 'flags', describe: 'Changes its title or description.' },
    { name: 'status', kind: 'state', permission: 'artifact:edit', mcp: true, cli: false, describe: 'Archives it, or brings it back.' },
    { name: 'delete', kind: 'delete', permission: 'artifact:delete', mcp: true, cli: false, describe: 'Removes it. Hard for a FILE, which this surface cannot register anyway.' },
  ],
  fields: [
    { name: 'title', type: 'string', required: 'always', describe: 'What this artifact is.' },
    {
      name: 'scope',
      type: 'string',
      required: 'always',
      enum: ['PROJECT', 'PLAN'],
      enumOf: 'ArtifactInput.scope',
      describe: 'PROJECT for one inherited by every plan, PLAN for one belonging to a single plan.',
      note:
        'PLAN requires `plan`; PROJECT requires it to be ABSENT. A project-scope artifact is '
        + 'inherited by every plan, so a plan id on one would name a relationship that does not '
        + 'exist. This is also the field the permission qualifier reads, and it is retained for '
        + 'that reason even though no seeded role is refused by scope since V52.',
    },
    {
      name: 'category',
      type: 'string',
      required: 'always',
      example: 'Strategy',
      describe: 'Strategy, TestData, Environment, SignOff, Compliance, Tooling, Template, Other.',
      note:
        'A free string rather than an enum, deliberately: the list is project-configurable, and '
        + 'the register groups by it rather than switching on it.',
    },
    { name: 'description', type: 'string', nullable: true, describe: 'What it holds.' },
    { name: 'plan', type: 'string', nullable: true, contract: 'planId', keyExample: 'TP-3', describe: 'The plan it belongs to. Required when scope is PLAN.' },
    { name: 'docPage', type: 'string', nullable: true, contract: 'docPageId', keyExample: 'DOC-1', describe: 'The page this artifact points at.' },
    { name: 'url', type: 'string', nullable: true, example: 'https://wiki.example.com/strategy', describe: 'The external address this artifact points at.' },
    { name: 'reportJob', type: 'string', nullable: true, contract: 'reportJobId', describe: 'The generated report this artifact pins.' },
    { name: 'supersedes', type: 'string', nullable: true, describe: 'The artifact this one replaces.' },
    { name: 'writeOnce', type: 'boolean', describe: 'True to make this artifact immutable once registered.' },
  ],
};

/** Every entity the catalogue covers, by name. */
/**
 * The eight entities this catalogue carries — and the three write tools it does not.
 *
 * `tcms_review_comment`, `tcms_review_verdict` and `tcms_report` have no entry, so `tcms_shape`
 * refuses those words. That is a **stated exclusion rather than an oversight**, and each has a
 * different reason:
 *
 *   - The two review tools take no *entity* body. Their payloads are a verdict, a remark and a
 *     resolution flag — arguments to a state transition, which is what their arms declare — so an
 *     entry here would be an `EntityShape` with no create and no edit, checked by nothing that
 *     checks the other eight, existing only to be printed.
 *   - `tcms_report`'s body is an **inline** schema on `POST …/reports/generate` with no component
 *     name, and every pin in `shapesContract.test.ts` resolves a named one. Extracting it is a
 *     contract change whose only driver is this sentence, so it is written down instead.
 *
 * What closed the gap for a caller is on the other side: `tcms_shape`'s refusal names every entity
 * that does exist, and its description routes the three that do not to the tool that owns them.
 */
export const SHAPES: Readonly<Record<string, EntityShape>> = {
  artifact: ARTIFACT_SHAPE,
  case: CASE_SHAPE,
  docPage: DOC_PAGE_SHAPE,
  plan: PLAN_SHAPE,
  result: RESULT_SHAPE,
  run: RUN_SHAPE,
  issue: ISSUE_SHAPE,
  suite: SUITE_SHAPE,
};

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
 * Whether an action's payload is validated as a whole, so its required fields bind the caller.
 *
 * A `create` assembles the whole object and every unconditional field must be in it. An `edit`
 * does **not**: both adapters read the case and send what it already had for anything the caller
 * did not name (`core/rewrite.ts` — the PATCH is not partial, so an omitted field would otherwise
 * be CLEARED). So marking `title` as required on an edit would be false, and it is the kind of
 * false that makes a person pass a field they did not want to change.
 */
export function bindsRequired(shape: EntityShape, action: string): boolean {
  return shape.actions.find((candidate) => candidate.name === action)?.kind !== 'edit';
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
  const required = bindsRequired(shape, action)
    ? fields.filter((field) => field.required === 'always').map((field) => field.name)
    : [];

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
    // Last, always: it is what a model copies, so it must not be buried mid-sentence.
    ...(exampleTextOf(field) ? [`e.g. ${exampleTextOf(field)}`] : []),
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

  /*
    An array's nullability is rendered the same way a scalar's is, and `dataTable` is the first
    field that needs it: a step legitimately has no table, and a host validating `type: 'array'`
    would refuse the null that says so.
  */
  if (field.type === 'string[]' || field.type === 'string[][]') {
    return {
      type: field.nullable ? ['array', 'null'] : 'array',
      items: field.type === 'string[]'
        ? { type: 'string' }
        : { type: 'array', items: { type: 'string' } },
      description,
    };
  }

  const base = field.type;
  return {
    type: field.nullable ? [base, 'null'] : base,
    ...(field.enum ? { enum: field.nullable ? [...field.enum, null] : [...field.enum] } : {}),
    description,
  };
}

/**
 * The literal a field's description ends with, or `undefined` when the field speaks for itself.
 *
 * An enum needs none — its permitted values are already rendered, and repeating one of them as an
 * example is noise. A display key already carries `keyExample`. What is left is the fields whose
 * *format* is guessable-and-wrong: a date, a duration, an opaque key.
 */
function exampleTextOf(field: FieldSpec): string | undefined {
  if (field.enum || field.keyExample) return undefined;
  return field.example;
}

/**
 * Whether argv can carry this field at all.
 *
 * `--file` carries the ones it cannot, which is why a command taking steps or conditions has
 * no per-field flag for them. Asked as a question rather than tested against `object[]` at four
 * call sites, because `string[][]` joined the answer in §255 and three of the four would have
 * kept saying yes.
 */
export function spellableInArgv(field: FieldSpec): boolean {
  return field.type !== 'object[]' && field.type !== 'string[][]';
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
    if (!spellableInArgv(field)) continue;

    spec[flagOf(field)] = field.cli?.multiple
      ? { type: 'string', multiple: true }
      : { type: field.type === 'boolean' ? 'boolean' : 'string' };

    if (field.cli?.negate) spec[field.cli.negate] = { type: 'boolean' };
  }

  const kind = shape.actions.find((candidate) => candidate.name === action)?.kind;
  return { ...spec, ...(kind ? mechanicsFor(kind) : {}) };
}

/**
 * The `--help` body — every field, whether it is required, and what it takes.
 *
 * Two spellings, because the CLI has two. A `flags` action lists `--kebab-case` options and must
 * leave out the object arrays, which argv cannot carry at all; a `file` action lists JSON keys and
 * must put them back in, since a body is the one place `steps` can be written. Rendering one
 * spelling for both is how a help screen ends up naming options that do not exist.
 */
export function usageFor(shape: EntityShape, action: string): string {
  const spelling = shape.actions.find((candidate) => candidate.name === action)?.cliSpelling
    ?? 'flags';
  const asFile = spelling === 'file';

  /*
    A `file` action's BODY keys are the entity's own fields; its wrapper fields stay flags. `tcms
    case propose` is exactly this — `--file` carries the `TestCaseInput` and `--verdict` rides
    beside it, because a claim about whether the case was checked is not part of the case. Listing
    `verdict` as a body key would have a caller put it where the endpoint ignores it.
  */
  const fields = fieldsFor(shape, action, asFile ? 'body' : 'all')
    .filter((field) => asFile || spellableInArgv(field));

  const lines = fields.map((field) => {
    const name = asFile ? field.name : `--${flagOf(field)}`;
    const binds = bindsRequired(shape, action);
    const marks = [
      binds && field.required === 'always' ? 'required' : undefined,
      binds && field.required && field.required !== 'always'
        ? `required when ${asFile ? field.required.when : `--${kebab(field.required.when)}`} `
          + `is ${field.required.is.join(' or ')}`
        : undefined,
      field.set ? 'complete set — omit to leave alone, [] to clear' : undefined,
      field.enum ? field.enum.join(' | ') : undefined,
      field.keyExample ? `e.g. ${field.keyExample}` : undefined,
      field.example ? `e.g. ${field.example}` : undefined,
      asFile && field.fields
        ? `each: ${field.fields.map((inner) => inner.name).join(', ')}`
        : undefined,
    ].filter(Boolean);

    return `  ${name}${!asFile && field.cli?.multiple ? ' (repeatable)' : ''}`
      + `\n      ${field.describe}`
      + (marks.length > 0 ? `\n      [${marks.join('; ')}]` : '')
      + (!asFile && field.cli?.negate ? `\n      --${field.cli.negate} clears it.` : '');
  });

  const excluded = asFile
    ? ''
    : `\n\n${fieldsFor(shape, action).filter((field) => !spellableInArgv(field))
      .map((field) => `  ${field.name}`).join(', ')} cannot be spelled in argv. `
      + 'Pass them in the --file body.';

  const objects = fieldsFor(shape, action).some((field) => !spellableInArgv(field));
  const how = asFile ? ' (JSON keys in the --file body)' : ' (flags)';

  const beside = asFile
    ? fieldsFor(shape, action, 'wrapper').map((field) =>
      `  --${flagOf(field)}\n      ${field.describe}`
        + (field.enum ? `\n      [${field.enum.join(' | ')}]` : ''))
    : [];

  /*
    `tool-cli-builder` step 7: a help text without a worked example makes the reader guess the
    format of exactly the field they are unsure about. One line, from the same template the
    `--json` mode emits, so the two cannot drift.
  */
  const worked = asFile
    ? `\n\nWorked example — save as body.json and pass --file body.json:\n`
      + JSON.stringify(templateFor(shape, action), null, 2).split('\n').map((line) => `  ${line}`).join('\n')
    : `\n\nWorked example:\n  ${workedFlags(shape, action)}`;

  const note = bindsRequired(shape, action)
    ? ''
    : '\n\nNothing here is required: anything you do not name is read from the current row and '
      + 'sent back unchanged. Name at least one field.';

  return `${shape.title} — ${action}${how}\n\n${lines.join('\n')}`
    + (objects && !asFile ? excluded : '')
    + (beside.length > 0 ? `\n\nFlags beside --file:\n${beside.join('\n')}` : '')
    + note
    + worked;
}

/**
 * One invocation a reader can paste, built from the same examples the field list shows.
 *
 * Two flags at most beyond the required ones: a worked example that sets every option is not an
 * example, it is the help text again on one line.
 */
function workedFlags(shape: EntityShape, action: string): string {
  const chosen = fieldsFor(shape, action)
    .filter(spellableInArgv)
    .filter((field) => (bindsRequired(shape, action) ? isRequired(field, {}) : true))
    .slice(0, bindsRequired(shape, action) ? 99 : 2);

  const flags = chosen
    .map((field) => `--${flagOf(field)} ${exampleOf(field) === null ? "''" : String(exampleOf(field))}`)
    .join(' ');

  // The positional first: every command that acts on an existing row takes one, and an example
  // without it is one that does not run.
  const target = bindsRequired(shape, action) ? '' : `${shape.keyExample ?? ''} `;
  return `tcms ${shape.entity} ${action} ${target}${flags}`.replace(/\s+/g, ' ').trim();
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
  if (field.type === 'string[]' || field.type === 'string[][]') return [];
  if (field.type === 'integer' || field.type === 'number') return 0;
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
    // The guide is the carrier a nested field's example can reach at all: `usageFor` lists an
    // object array's inner fields by name only, and `steps[].dataTable` is exactly the field
    // whose nesting a caller guesses wrong without one.
    field.example ? `e.g. \`${field.example}\`` : undefined,
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

  const binds = bindsRequired(shape, action);
  for (const field of fieldsFor(shape, action)) {
    const value = payload[field.name];
    const empty = value === undefined || value === null || value === ''
      || (Array.isArray(value) && value.length === 0);

    if (binds && isRequired(field, payload) && empty) {
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
