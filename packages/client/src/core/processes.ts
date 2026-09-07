/**
 * The process catalogue — what an agent is expected to *do* with this surface, in order.
 *
 * `docs/specs/2026-09-07-agent-specification-formatter.md` §3. The shape catalogue answers *what
 * does one of these look like*; nothing answered *what is the sequence*, and the sequence is where
 * the surface is actually got wrong: a page is written, cases are proposed against blocks whose
 * ids only the write's response carries, a plan adopts the page. The server's `instructions`
 * string carried one sentence of it ("ground yourself before you write") and every other step was
 * folklore.
 *
 * ## Beside the shapes, and rendered the same way
 *
 * One catalogue, four carriers — the tool, the resource, the MCP prompts and the CLI — exactly as
 * `core/shapes.ts` has five. The discipline is the one `guideCopies.test.ts` already enforces for
 * the tool list: **computed, not hand-maintained**, so a step that names a tool this surface does
 * not have is a red test rather than a model's dead end.
 *
 * ## What a process is not
 *
 * It is not a macro and it is not a transaction. Every one of them **ends where a person's act
 * begins** — nothing here publishes a page, accepts a proposal or approves a plan, because the
 * product's review boundary is structural (`core/proposal.ts`) rather than a default somebody
 * could turn off. A step's `check` is what the agent reads before taking the next one, which is
 * the difference between a sequence an agent can recover from and one it can only restart.
 */

export interface ProcessArgument {
  readonly name: string;
  readonly describe: string;
  readonly required: boolean;
}

export interface ProcessStep {
  /** What this step accomplishes. */
  readonly does: string;
  /** The MCP tool, or absent where the step is the model's own work. */
  readonly tool?: string;
  /**
   * The CLI spelling of the same step.
   *
   * Absent where the CLI genuinely has no such command, which is a real gap rather than a rendering
   * convenience — `tcms_orient` and `tcms_report` reach no command at all — so
   * {@link processGuideFor} says so in as many words instead of leaving a step looking complete.
   */
  readonly cli?: string;
  /** The permission the step costs, so a refusal maps to a sentence rather than a surprise. */
  readonly permission?: string;
  /** What to read in the answer before taking the next step. */
  readonly check: string;
  /** The way this step is got wrong. */
  readonly trap?: string;
}

export interface ProcessSpec {
  readonly name: string;
  readonly title: string;
  readonly summary: string;
  readonly arguments: readonly ProcessArgument[];
  readonly steps: readonly ProcessStep[];
}

const PAGE_ARGUMENT: ProcessArgument = {
  name: 'page',
  describe: 'The documentation page, by display key — DOC-7.',
  required: true,
};

/**
 * The five, in the order a specification travels through them.
 *
 * P1 → P2 → P3 is one arc (a specification becomes a page, cases claim its statements, a plan
 * adopts it); P4 and P5 are what happens to that page afterwards, which is most of its life.
 */
export const PROCESSES: readonly ProcessSpec[] = [
  {
    name: 'ingest-spec',
    title: 'Turn a client specification into a documentation page',
    summary:
      'Rewrite a specification of any shape into TCMS spec markdown — one testable statement per '
      + 'block, each labelled — write it as a page, and report what was dropped, reworded and left '
      + 'open. It stops before publishing: that is a person\'s signal.',
    arguments: [
      { name: 'spec', describe: 'The specification, in whatever shape it was written.', required: true },
      { name: 'title', describe: 'What the page is called.', required: true },
      { name: 'parent', describe: 'The page it nests under, by display key — DOC-1.', required: false },
    ],
    steps: [
      {
        does: 'Find out who you are and what this project calls things.',
        tool: 'tcms_whoami',
        cli: 'tcms whoami',
        check: 'The project you are scoped to, and the permissions you hold.',
      },
      {
        does: 'Read the project\'s vocabulary — the priorities, statuses and classification values '
          + 'the cases will need, and the page tree the new page will sit in.',
        tool: 'tcms_orient',
        permission: 'project:read',
        check: 'Whether a page for this specification already exists. Ingesting a second copy is '
          + 'how one requirement ends up with two ids and half the coverage on each.',
      },
      {
        does: 'Translate the specification into TCMS spec markdown: one statement per block, every '
          + 'requirement rewritten to a named actor + an explicit condition + an observable '
          + 'outcome, every requirement labelled `{#like-this}` and nothing else labelled.',
        check: 'Every number, threshold, name and formula is the source\'s. Rewording is free; '
          + 'correcting the specification is not.',
        trap: 'A rule living in a table row is unlinkable — a cell is data. Lift it out as a list '
          + 'item and keep the table only for examples.',
      },
      {
        does: 'Write the page, with the Markdown as its whole body.',
        tool: 'tcms_write_doc_page {action: "create", title, parent?, markdown}',
        cli: 'tcms doc create --title T [--parent DOC-1] --from spec.md',
        permission: 'doc:edit',
        check: 'The response\'s `anchors` — label → block id. That map is what the next process '
          + 'writes proposals against, and it exists nowhere else.',
        trap: 'An image cannot be written on a create: the page has no attachments until it '
          + 'exists, so the alt text is carried as a paragraph and the report says so.',
      },
      {
        does: 'Return the report: what was dropped, what was reconstructed, what was lifted or '
          + 'split, what was reworded, the labelled-block count, every figure the source\'s own '
          + 'formula does not reproduce, and the open questions.',
        check: 'The open questions are the half of the answer a reviewer cannot get from the page. '
          + 'A specification that reads cleanly and contradicts itself in three places is the '
          + 'normal case.',
        trap: 'Do not publish. Publishing is a separate act, a separate key, and a person\'s.',
      },
    ],
  },
  {
    name: 'cover-spec',
    title: 'Propose test cases for a page\'s requirements',
    summary:
      'Read a specification page, and file one proposal per labelled statement naming the block it '
      + 'verifies. Nothing here becomes a test case: a person accepts, and the links are written at '
      + 'that moment.',
    arguments: [
      PAGE_ARGUMENT,
      {
        name: 'scope',
        describe: 'Which statements to cover — a section heading, or all of them.',
        required: false,
      },
    ],
    steps: [
      {
        does: 'Read the page, with every block\'s id, links and coverage.',
        tool: 'tcms_open_doc_page {page}',
        cli: 'tcms doc show DOC-7',
        permission: 'doc:read',
        check: 'A block whose `links` already name a case has a claimant. Propose against it only '
          + 'when asked to extend the coverage, never to duplicate it.',
      },
      {
        does: 'Read the case shape and the project\'s own vocabulary.',
        tool: 'tcms_shape case',
        cli: 'tcms format case',
        check: 'The conditional requirements — `steps` in either lens, `automationKey` when '
          + 'AUTOMATED, `notAutomatableReason` when NOT_AUTOMATABLE.',
      },
      {
        does: 'File one proposal per statement, naming the block it verifies and tagging it '
          + '`spec:<slug>`.',
        tool: 'tcms_write_case {action: "propose", testCase, verifies: [{page, block}], tags}',
        cli: 'tcms case propose --file case.json --verifies DOC-7:<blockId>',
        permission: 'case:create',
        check: 'The proposal key, and that `verifies` came back on it.',
        trap: 'The tag is how the plan finds these later: a proposal is not a case and cannot be '
          + 'put in a plan, so membership resolves through the tag as each one is accepted.',
      },
      {
        does: 'Report the proposals filed, by block, and the labelled statements you did not cover '
          + 'with the reason.',
        check: 'Nothing here created a test case. A person accepts; the links to the blocks are '
          + 'written then, by the same path a person\'s link takes.',
        trap: 'A block deleted between the proposal and the acceptance is a field error on '
          + '`verifies[n].blockId` at accept — on a reviewer\'s screen, not yours.',
      },
    ],
  },
  {
    name: 'plan-spec',
    title: 'Write a test plan for a specification page',
    summary:
      'Turn a page into a plan: scope and out-of-scope from its own sections, one plan section per '
      + 'top-level heading, machine-evaluable entry and exit criteria, and the page adopted as the '
      + 'plan\'s document.',
    arguments: [
      PAGE_ARGUMENT,
      { name: 'name', describe: 'What the plan is called.', required: false },
      { name: 'milestone', describe: 'The release or milestone it belongs to.', required: false },
    ],
    steps: [
      {
        does: 'Read the page, and the plans this project already has.',
        tool: 'tcms_open_doc_page {page}',
        cli: 'tcms doc show DOC-7',
        permission: 'doc:read',
        check: 'Whether a plan already adopts this page. Two plans for one specification is two '
          + 'sets of exit criteria nobody can reconcile.',
      },
      {
        does: 'Read the plan shape, for the criteria vocabularies.',
        tool: 'tcms_shape plan',
        cli: 'tcms format plan',
        check: '`metric` and `op` are closed vocabularies validated on the way in — a plan gated '
          + 'on `pass_rte` renders exactly like one gated on `open_critical`, as a criterion with '
          + 'no verdict.',
      },
      {
        does: 'Create the plan: `scopeText` from the page\'s purpose, `outOfScopeText` from its '
          + '*Out of scope* section, one `section` per top-level heading, the criteria, and the '
          + '`spec:<slug>` tag.',
        tool: 'tcms_write_plan {action: "create", name, scopeText, outOfScopeText, entryCriteria, '
          + 'exitCriteria, sections, tags}',
        cli: 'tcms plan create --name N --scope-text … --tag spec:liquidator',
        permission: 'plan:create',
        check: 'The plan key, and that the criteria came back as you sent them — a metric or an '
          + 'operator outside the vocabulary is refused, not stored.',
        trap: 'Leave `cases` empty. Membership resolves through the tag as proposals are accepted; '
          + 'naming cases that do not exist yet is a plan that is wrong the moment it is written.',
      },
      {
        does: 'Adopt the page as the plan\'s document.',
        tool: 'tcms_write_doc_page {action: "adopt", page, plan}',
        cli: 'tcms doc adopt DOC-7 --plan TP-3',
        permission: 'doc:edit',
        check: 'The page\'s `subjectType` reads TEST_PLAN.',
        trap: 'Adopting costs `doc:edit`, not a plan key: it is the same act as editing the page, '
          + 'seen from the other end, which is why it lives on the doc tool.',
      },
      {
        does: 'Stop at DRAFT unless you were told to submit it.',
        tool: 'tcms_write_plan {action: "status", plan, status: "IN_REVIEW"}',
        cli: 'tcms plan status TP-3 --status IN_REVIEW',
        permission: 'plan:edit',
        check: 'APPROVED is not on this surface and is not meant to be.',
      },
    ],
  },
  {
    name: 'revise-spec',
    title: 'Revise a specification page without breaking its links',
    summary:
      'Read the page back as Markdown with every block carrying its id, edit the text, and write '
      + 'it back. A block that cases claim and this edit drops is refused, naming them.',
    arguments: [
      PAGE_ARGUMENT,
      { name: 'change', describe: 'What the revision has to say.', required: true },
    ],
    steps: [
      {
        does: 'Read the page as Markdown.',
        tool: 'tcms_open_doc_page {page, as: "markdown"}',
        cli: 'tcms doc show DOC-7 --markdown',
        permission: 'doc:read',
        check: 'Every block ends with `{#<uuid>}`. That anchor is the block\'s identity — keep it '
          + 'and the block keeps its links however much you rewrite the words.',
      },
      {
        does: 'Edit the text. Keep every anchor you are keeping; give a new statement a `{#label}`.',
        check: 'A statement you split into two keeps the id on the half the existing cases '
          + 'actually verify, and the other half gets a label.',
        trap: 'Deleting a block means deleting the claims against it. That is a decision, not an '
          + 'edit.',
      },
      {
        does: 'Write it back with the lock version the read returned.',
        tool: 'tcms_write_doc_page {action: "edit", page, markdown, lockVersion}',
        cli: 'tcms doc edit DOC-7 --from spec.md --lock-version N',
        permission: 'doc:edit',
        check: 'The response\'s `anchors` for the labels you added.',
        trap: 'A refusal naming dropped linked blocks is the format doing its job. Put the block '
          + 'back, or repeat with `dropLinked` and say in your report why the claim no longer '
          + 'holds.',
      },
    ],
  },
  {
    name: 'report-spec',
    title: 'Report coverage with the specification as its spine',
    summary:
      'Answer *what of this specification is tested* — from the page for the in-conversation '
      + 'answer, and as a generated coverage report for the artifact somebody keeps.',
    arguments: [
      PAGE_ARGUMENT,
      { name: 'format', describe: 'The report format this deployment offers.', required: false },
    ],
    steps: [
      {
        does: 'Read the page and its coverage markers.',
        tool: 'tcms_open_doc_page {page}',
        cli: 'tcms doc show DOC-7',
        permission: 'doc:read',
        check: 'A labelled block with no `VERIFIES` link is an uncovered requirement, and that is '
          + 'the answer — it reads the same column the report does and cannot disagree with it.',
      },
      {
        does: 'Ask what this deployment can generate.',
        tool: 'tcms_report {action: "capabilities"}',
        permission: 'report:generate',
        check: 'The types and formats offered. Asking for one that is not there is a refusal you '
          + 'could have read.',
      },
      {
        does: 'Generate the coverage matrix, poll it, and return the URL.',
        tool: 'tcms_report {action: "generate"} then {action: "poll"}',
        permission: 'report:generate',
        check: 'The job reaches a terminal state before you answer. A queued job is not a report.',
      },
    ],
  },
];

export function processNamed(name: string): ProcessSpec | undefined {
  return PROCESSES.find((process) => process.name === name);
}

/**
 * The long-form guide for one process — the MCP resource, the prompt body, and `tcms process`.
 *
 * Markdown, and structured Markdown only: no width, no colour, on `guideFor`'s rule. Presentation
 * belongs to the CLI's `output.ts`, which may decorate what it is handed.
 */
export function processGuideFor(process: ProcessSpec): string {
  const out: string[] = [`# ${process.title}`, '', process.summary, ''];

  if (process.arguments.length > 0) {
    out.push('## Arguments', '');
    for (const argument of process.arguments) {
      out.push(`- \`${argument.name}\`${argument.required ? ' **required**' : ''} — ${argument.describe}`);
    }
    out.push('');
  }

  out.push('## Steps', '');
  for (const [index, step] of process.steps.entries()) {
    out.push(`### ${index + 1}. ${step.does}`, '');
    if (step.tool) out.push(`- MCP: \`${step.tool}\``);
    if (step.cli) out.push(`- CLI: \`${step.cli}\``);
    if (step.tool && !step.cli) out.push('- CLI: no command reaches this step; it is MCP-only.');
    if (step.permission) out.push(`- Costs \`${step.permission}\``);
    out.push(`- Then read: ${step.check}`);
    if (step.trap) out.push(`- Trap: ${step.trap}`);
    out.push('');
  }

  return out.join('\n');
}

/** Every process as one document, for a caller that asked for the catalogue rather than one of it. */
export function processCatalogue(): string {
  return [
    '# Processes',
    '',
    'What to do with this surface, in order. Each one ends where a person\'s act begins — nothing '
    + 'here publishes, accepts or approves.',
    '',
    ...PROCESSES.map((process) => `- **${process.name}** — ${process.title}. ${process.summary}`),
    '',
  ].join('\n');
}
