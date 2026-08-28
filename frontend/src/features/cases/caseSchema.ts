import { z } from 'zod';

/**
 * What a test case *is* on the client, as one schema.
 *
 * It is deliberately narrower than the read model: it contains only fields the update path
 * actually persists. That is field gating by construction — TypeScript won't let the form bind
 * something the API ignores, so an edit can never show a success toast and quietly discard the
 * value, which is the highest-severity form defect there is.
 *
 * Notably absent: `lifecycleStatus`. Status moves through explicit review and approval actions
 * with their own side effects, so it is not an editable dropdown in the editor — a `<select>`
 * would bypass the transition guards entirely.
 *
 * ## Why this is its own module
 *
 * It lived inside `CaseEditorPage.tsx` and was module-private, which is why its four cross-field
 * refinements went untested through Phase 4 (§36) and stayed named as an open follow-up in §37.
 * Exporting it from the page file works and lints as a warning — a module that exports both a
 * component and a constant defeats Fast Refresh — so the schema moved instead. One consumer today
 * (the editor) and one test file; a second form binding this would be a second case editor, which
 * is not a thing that should exist.
 */

export const StepSchema = z.object({
  id: z.string().nullable().optional(),
  /**
   * The Gherkin keyword, or `''` for a step that has none (§255).
   *
   * **`''` and not `null`**, for `suiteId`'s recorded reason one field over: MUI's `Select` treats
   * `null` as uncontrolled and logs a warning on every render of every keyword-less row, which is
   * every row of every structured case. The submit maps `''` back to the `null` the contract calls
   * a step with no keyword.
   *
   * Not a `z.enum` either. The control only offers the five, so a bad value cannot be produced on
   * screen — but a restored draft is unvalidated by construction and may have been written by an
   * older build, and a schema that refused one would take the editor's whole form down rather than
   * letting the server answer with the sentence it has for exactly this (`CaseEdit.keyword`).
   */
  keyword: z.string(),
  /**
   * Deliberately NOT `.min(1)` — the emptiness rules moved to the two checks below (§94).
   *
   * The editor keeps a blank row permanently at the end of the list so that typing appends the
   * next one, which means "every row has an action" stopped being true of a correct form: the
   * trailing row is an invitation, not a step, and a field-level `min(1)` on it would refuse
   * every save with an error on a row the author never touched.
   *
   * What replaces it is narrower and lands in the same place: a row that carries *anything* must
   * carry an action (the `superRefine` below, path `steps.N.action`), and a form must contain at
   * least one row that carries something.
   */
  action: z.string(),
  testData: z.string().nullable().optional(),
  expectedResult: z.string().nullable().optional(),
  /**
   * Gherkin's `"""` block argument, and **`null` means the row is not in doc-string mode** while
   * `''` means it is and is empty.
   *
   * The distinction is what lets the Data cell's mode be *derived* from the values rather than
   * held as a fourth field: a mode kept beside the content is a second source of truth about the
   * same question, and the two disagree the moment a draft is restored from a payload that carries
   * only the content. `StepDataCell` reads exactly this.
   */
  docString: z.string().nullable(),
  /** Gherkin's `|` table, rows of cells. `null` for a row that is not in table mode — see above. */
  dataTable: z.array(z.array(z.string())).nullable(),
});

/** The authored fields — the shape both emptiness helpers below read. */
type StepLike = {
  action?: string | null;
  testData?: string | null;
  expectedResult?: string | null;
  docString?: string | null;
  dataTable?: string[][] | null;
};

const isBlank = (value: string | null | undefined) => (value ?? '').trim().length === 0;

/**
 * A row nobody has written anything into — including one holding only spaces.
 *
 * Whitespace counts as blank in **both** directions on purpose: it is what makes a row typed into
 * and cleared again disappear at submit rather than reaching the API as a step whose action is
 * `"  "`, and it is what stops such a row satisfying "at least one step".
 */
export const isBlankStep = (step: StepLike): boolean =>
  isBlank(step.action)
  && isBlank(step.testData)
  && isBlank(step.expectedResult)
  // The two block arguments count, and leaving them out would be the §94 defect in a new field:
  // a row carrying only a doc string would be dropped at submit as empty, so the author's pasted
  // payload disappears on Save with a success toast over it.
  && (step.docString ?? null) === null
  && (step.dataTable ?? null) === null;

/**
 * The rows that are actually steps.
 *
 * **One definition, two consumers** — the schema's "at least one step" refine and the editor's
 * submit — because they must agree exactly. A validator that counts rows the submit then drops
 * saves an empty case; a submit that keeps rows the validator ignored posts a whitespace step. The
 * two halves of that disagreement are individually invisible.
 */
export const liveSteps = <T extends StepLike>(steps: readonly T[]): T[] =>
  steps.filter((step) => !isBlankStep(step));

export const CaseSchema = z
  .object({
    title: z.string().min(1, 'Enter a title.').max(300, 'Keep the title under 300 characters.'),
    /**
     * The folder the case is **filed** in, or `''` for an unfiled case (§96).
     *
     * No `.min(1)` any more, and the empty string is the unfiled value rather than a missing one:
     * a suite is a resolvable collection — filed ∪ added ∪ tagged — so filing is one way of
     * belonging rather than a precondition for existing, and a case may be written before anybody
     * has decided where it goes. The submit maps `''` to `null`, which is what the contract calls
     * unfiled; sending `''` would be a 400 on a uuid field.
     *
     * `''` rather than `null` in the form because MUI's `Select` treats `null` as "uncontrolled"
     * and logs a warning on every render of an unfiled case.
     */
    suiteId: z.string(),
    objective: z.string().nullable(),
    description: z.string().nullable(),
    expectedOutcome: z.string().nullable(),
    priority: z.enum(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']),
    automationStatus: z.enum(['MANUAL', 'TO_BE_AUTOMATED', 'AUTOMATED', 'NOT_AUTOMATABLE']),
    automationKey: z.string().nullable(),
    notAutomatableReason: z.string().nullable(),
    // Held as the string the number input actually produces and converted at submit, rather
    // than coerced in the schema: a coercion makes the schema's input and output types
    // diverge, which then fights the form's own typing for no benefit here.
    estimatedDurationSec: z
      .string()
      .nullable()
      .refine((value) => !value || /^\d+$/.test(value), 'Enter a whole number of seconds.'),
    /**
     * ~~A lens the author chooses between.~~ → **§256: there is one format and it is `GHERKIN`.**
     *
     * The field stays on the form because `TestCaseInput` still requires it, but nothing on any
     * screen sets it any more and `STRUCTURED` is unreachable through this SPA. It remains in the
     * enum so a case authored before §256 — or by a client that still sends it — parses rather
     * than being refused on a value the author cannot see or change.
     */
    stepFormat: z.enum(['STRUCTURED', 'GHERKIN']),
    steps: z.array(StepSchema),
    // Every classification value the case should carry, across all four dimensions — Test Type,
    // Test Level, Execution Phase and the project's Feature tree — as one flat array, because that
    // is the shape the contract takes and splitting it per dimension here would mean reassembling
    // it at submit and disagreeing with the server about what "the classification" is.
    //
    // Deliberately NOT validated for cardinality. The SINGLE-dimension rule needs the project's
    // vocabulary to know which dimensions are SINGLE, and a Zod schema that took a runtime argument
    // would stop being a static schema. The picker makes a second value on a SINGLE axis
    // unselectable instead — a radio group, not a checkbox list — so the rule is enforced by the
    // control's shape rather than by refusing a submission the user could not have made. The server
    // and V2's trigger hold the line for anything that is not the SPA.
    classificationValueIds: z.array(z.string()),
    /**
     * The case's OWN attached conditions, by library id, in the author's order — one array per
     * kind (§95).
     *
     * **Ids, not objects.** The contract takes `[{conditionId}]` and the submit maps to it; the
     * form binds the ids because that is what the two controls produce and what reordering moves.
     *
     * **Own, never inherited.** `TestCaseDetail.preconditions` carries the folder's conditions too,
     * marked `inherited` — and those must never be seeded into this array. Submitting one would
     * attach the folder's condition *directly* to the case, permanently and invisibly, since an
     * inherited row and an own row render almost identically. The defaults filter on `inherited`
     * for exactly that.
     *
     * **Deliberately not validated here.** There is no rule a static schema could hold: whether an
     * id is one this project offers needs the library, which is a runtime value. The picker only
     * offers live entries, and the server refuses anything else with a field error.
     *
     * Order is the content — these are arrays and are never sorted.
     */
    preconditionIds: z.array(z.string()),
    postconditionIds: z.array(z.string()),
    /**
     * The INHERITED attachments this case declines (§187) — §6f's opt-out toggle.
     *
     * **Attachment ids, not condition ids**, and the distinction is the feature: "Log in" attached
     * at *Billing* and again at *Refunds* is two attachments, and turning off the first must leave
     * the second in force. The server refuses a library `conditionId` here with a field error for
     * that reason, so sending the wrong one fails loudly rather than declining more than the author
     * asked.
     *
     * **One array for both kinds**, unlike the two above, because an attachment id already
     * determines its kind — splitting it would let a value be filed under the wrong one. The two
     * panels write into the same array; each only ever touches ids it renders.
     *
     * **A set, not a procedure.** Order carries no meaning here (the server sorts it before
     * hashing), which is the other half of why it is not modelled like `preconditionIds`.
     */
    declinedConditionIds: z.array(z.string()),
    /**
     * The case's organisational tags (§96) — free text, a set, and the thing a suite's tag rules
     * resolve against.
     *
     * **Not `automationTags`**, which are CI selection tags on a different table (§6c) and are
     * still refused by the update endpoint. The editor cannot author those.
     *
     * Trimmed and de-duplicated by the control rather than by a refinement: a duplicate is not a
     * mistake to report, it is the same request — `case_tag`'s key says a tag list is a set — and
     * an error message about a chip the author cannot see twice is unactionable.
     *
     * Length is validated because the server caps it, and a silently truncated tag stops matching
     * the suite rule it was written for, which removes the case from a collection with no symptom.
     */
    tags: z.array(z.string().max(60, 'Keep each tag under 60 characters.')),
    changeComment: z.string().nullable(),
  })
  // Cross-field rules the server also enforces. They live in the schema rather than in the submit
  // handler, so the message lands on the field that caused it.
  //
  // The procedure rule is stricter than the server's CREATE path since §200, and deliberately so.
  // The server defers the procedure because the plan's Test cases tab authors a case from a title
  // with no step field on screen; this editor renders one, so an author here has been asked the
  // question and an empty answer is an answer. Relaxing it to match would let the one surface built
  // for writing a case be the surface that saves an empty one. The stub the plan creates is
  // finished HERE, and this rule is what finishing means.
  /*
    NON-EMPTY steps, not `steps.length` — the defect §94's auto-append introduces.

    The editor keeps a blank row at the end of the list at all times, so `steps.length` is never 0
    and this rule would pass on a form nobody has written a step into: an empty case would save
    clean, with a success toast and a version bump. Counting the rows that survive `liveSteps` is
    the same question asked of the rows that will actually be sent.

    UNCONDITIONAL SINCE §255, where it used to exempt `GHERKIN`. A Gherkin case is step rows now, so
    "or switch to Gherkin" named an escape hatch that no longer exists — and the server's own
    sentence lost the same clause in the same slice (`CaseEdit`'s `ProcedureRule.REQUIRED`).
  */
  .refine((data) => liveSteps(data.steps).length > 0, {
    path: ['steps'],
    message: 'Add at least one step.',
  })
  .refine((data) => data.automationStatus !== 'NOT_AUTOMATABLE' || !!data.notAutomatableReason?.trim(), {
    path: ['notAutomatableReason'],
    message: 'Say why this case cannot be automated.',
  })
  .refine((data) => data.automationStatus !== 'AUTOMATED' || !!data.automationKey?.trim(), {
    path: ['automationKey'],
    message: 'Give the automated test that covers this case, so results can be matched to it.',
  })
  /*
    The per-row rules, in one pass. All four are the server's, quoted verbatim from
    `CaseEdit.validate` — a message the client composes itself is §149's defect in the other
    direction: the author fixes the row, saves, and is told the same thing in different words.

    They have to know about their neighbours to be right: the blank trailing row is exempt from all
    of them (it is not a step yet), and R2 is a question about the FIRST live row rather than about
    index 0, which is not the same row on a form whose first row was cleared.

    UNCONDITIONAL where §200's versions were `STRUCTURED`-only, except R1 and R2 which are the two
    the lens actually decides. A Gherkin case sends step rows now, so a half-written one in either
    lens is refused by the server and must be refused here.
  */
  .superRefine((data, ctx) => {
    const live = data.steps.map((step, index) => ({ step, index })).filter((row) => !isBlankStep(row.step));

    live.forEach(({ step, index }, position) => {
      if ((step.action ?? '').trim().length === 0) {
        ctx.addIssue({ code: 'custom', path: ['steps', index, 'action'], message: 'Describe the action.' });
      }

      /*
        R1/R2 — ~~the lens rules~~ → UNCONDITIONAL since §256, because there is no second lens for
        them to be conditional on. `CaseEdit.validate` refuses both on `steps`; here they land on
        the keyword control of the row they are about, which is the whole reason the schema holds
        them.

        Both are now unreachable by typing: `nextKeyword` puts a keyword on every appended row and
        `prefilledKeywords` fills a legacy case's rows on load. They are kept for the two states
        the controls cannot produce — a restored draft written before §256, and a row whose author
        deliberately cleared the select.
      */
      if (step.keyword === '') {
        ctx.addIssue({
          code: 'custom',
          path: ['steps', index, 'keyword'],
          message: `Step ${position + 1} needs a keyword.`,
        });
      } else if (position === 0 && (step.keyword === 'AND' || step.keyword === 'BUT')) {
        ctx.addIssue({
          code: 'custom',
          path: ['steps', index, 'keyword'],
          message: 'The first step cannot be And/But.',
        });
      }

      /*
        R3 — one data spelling per step.

        UNREACHABLE THROUGH THIS SCREEN, and kept anyway: `StepDataCell`'s mode switch clears the
        other two, so nothing an author can do produces a row with both. What can is a restored
        draft, which is unvalidated by construction and may have been written by a build that
        modelled the three fields differently. Landed on `testData` because the three share one
        control and that is the field its helper text is keyed to.
      */
      const spellings =
        ((step.testData ?? '').trim().length > 0 ? 1 : 0)
        + (step.docString !== null && step.docString !== undefined ? 1 : 0)
        + (step.dataTable !== null && step.dataTable !== undefined ? 1 : 0);
      if (spellings > 1) {
        ctx.addIssue({
          code: 'custom',
          path: ['steps', index, 'testData'],
          message: `Step ${position + 1} has more than one data field — keep test data, a doc string, or a table.`,
        });
      }
    });
  });

/**
 * The five keywords, in the order a scenario reads them — what the keyword select renders from.
 *
 * Here rather than in the editor because {@link prefilledKeywords} below is the other consumer and
 * the two must agree about the vocabulary; `lib/labels.ts::stepKeywordLabels` owns the words.
 */
export const STEP_KEYWORDS = ['GIVEN', 'WHEN', 'THEN', 'AND', 'BUT'] as const;

/** One row of the editor's step list, as the form holds it. */
export type StepRow = CaseInput['steps'][number];

/**
 * The keywords a `STRUCTURED → GHERKIN` switch proposes — blueprint §2 rule 4.
 *
 * ## It only ever FILLS
 *
 * A row that already carries a keyword keeps it, and so does the blank trailing row. That is the
 * half that makes the toggle safe to press twice: pre-filling is a suggestion made once, not a
 * normalization applied on every render, and a rule that overwrote would silently rewrite an
 * author's `But` into an `And` the moment they looked at the other lens.
 *
 * ## The rule, and why THEN is chosen last-first
 *
 * The first live row is the setup (`GIVEN`); the **last** row carrying an expected result is the
 * assertion (`THEN`), because a case that checks something checks it at the end and the rows before
 * it that also state an expectation are intermediate observations; everything else is action —
 * `WHEN` for the first of them and `AND` for the continuations, which is how a scenario is written
 * and what a `.feature` render has to reproduce.
 *
 * Nothing here is a claim about correctness: it is a first draft the author edits. What it must not
 * do is leave a row without a keyword, because that is the state the form then refuses to save.
 */
export function prefilledKeywords(steps: readonly StepRow[]): StepRow[] {
  const liveIndexes = steps.map((step, index) => ({ step, index })).filter((row) => !isBlankStep(row.step));
  const lastAsserting = liveIndexes
    .filter((row) => !isBlank(row.step.expectedResult))
    .at(-1)?.index;

  let actionsSeen = 0;
  return steps.map((step, index) => {
    const position = liveIndexes.findIndex((row) => row.index === index);
    if (position < 0 || step.keyword !== '') return step;
    if (position === 0) return { ...step, keyword: 'GIVEN' };
    if (index === lastAsserting) return { ...step, keyword: 'THEN' };
    actionsSeen += 1;
    return { ...step, keyword: actionsSeen === 1 ? 'WHEN' : 'AND' };
  });
}

/**
 * The keyword a newly appended row should arrive carrying (§256).
 *
 * The first live step of a scenario is its setup, so it is `GIVEN`; every row after it continues
 * what came before, so it is `AND`. Both are a first draft the author changes from the select —
 * the point is only that they never have to open it to write an ordinary scenario, and that the
 * editor stops manufacturing the keyword-less row its own validation then refuses.
 *
 * `AND` rather than a guess at `WHEN`/`THEN`: those two are claims about what the step means, and
 * a wrong claim silently attached to somebody's sentence is worse than a neutral continuation.
 * `prefilledKeywords` may guess because a toggle was pressed on a finished case; this fires as the
 * row is being started, where there is nothing yet to read.
 */
export function nextKeyword(steps: readonly StepRow[]): string {
  return liveSteps(steps).length === 0 ? 'GIVEN' : 'AND';
}

export type CaseInput = z.infer<typeof CaseSchema>;

/**
 * The form's values as the API's `TestCaseInput` — **the one mapping**, and it has two consumers
 * that must not disagree.
 *
 * The editor's Save built this inline for as long as Save was the only thing that sent it. §128's
 * autosave is the second, and a second hand-written copy is the defect that shape produces: a field
 * wired into the save and forgotten in the buffer restores as empty, so the author's recovered
 * draft is missing exactly the thing they were typing — with nothing failing at either end, because
 * both requests are individually valid. `documentation`'s `BlockContent.of` is the same rule one
 * module over, arrived at the same way.
 *
 * **It normalizes but does not validate**, which is what lets autosave use it: trimming and
 * dropping blank steps are shape decisions, while "a title is required" is `CaseSchema`'s and is
 * asked only on Save. A buffered draft is half-written by definition.
 *
 * Every collection is sent **including when empty**, because the server reads an absent field as
 * "leave it alone": omitting `tags` when the author has just removed the last one would silently do
 * nothing. That asymmetry is on the contract and is the reason these are not `|| undefined`.
 */
export function toCasePayload(data: CaseInput): Record<string, unknown> {
  /*
    Trim first, then drop the rows that are empty once trimmed — and the order is the point.
    Trimming alone sends a step whose action is `"  "` as a real step: the API accepts it, it
    renders as an empty numbered line for ever, and a run asks a tester to perform it. Dropping
    alone keeps the leading and trailing spaces of every pasted step, which is what makes two
    identical steps two entries in the suggestion corpus.
  */
  const steps = liveSteps(
    data.steps.map((step) => ({
      ...step,
      action: step.action.trim(),
      testData: (step.testData ?? '').trim(),
      expectedResult: (step.expectedResult ?? '').trim(),
      // The two block arguments are NOT trimmed, and the asymmetry is the round trip. Leading
      // whitespace inside a `"""` block and inside a `|`-cell is content in the Gherkin spec, so
      // trimming here would make the `.feature` this case renders to differ from the file it was
      // parsed from — `CaseEdit.normalizeTable` says the same thing from the other end.
    })),
  );

  return {
    title: data.title.trim(),
    // null, not '', for an unfiled case: the field is a uuid on the contract and '' is a 400.
    suiteId: data.suiteId || null,
    objective: data.objective || null,
    description: data.description || null,
    expectedOutcome: data.expectedOutcome || null,
    priority: data.priority,
    severity: data.priority,
    automationStatus: data.automationStatus,
    // Only the chosen branch's content is sent. Posting both would leave the other one stale in
    // the record, and the next reader cannot tell which is authoritative.
    automationKey: data.automationStatus === 'AUTOMATED' ? data.automationKey || null : null,
    notAutomatableReason:
      data.automationStatus === 'NOT_AUTOMATABLE' ? data.notAutomatableReason || null : null,
    estimatedDurationSec: data.estimatedDurationSec ? Number(data.estimatedDurationSec) : null,
    stepFormat: data.stepFormat,
    /*
      THE ROWS ARE SENT IN BOTH LENSES, and that sentence is the whole of §255 on this side.

      Until §255 this read `data.stepFormat === 'STRUCTURED' ? steps : []` — so switching a case to
      Gherkin and saving deleted every step row, and with them the ids every `step_result` anchors
      to, with a success toast over it. `gherkinSource` left `TestCaseInput` in the same slice and
      is deliberately not sent at all: the field is read-only now and a stray one would be ignored.
    */
    steps: steps.map((step) => ({
      id: step.id ?? null,
      // '' is the form's spelling of "no keyword"; the contract's is null.
      keyword: step.keyword || null,
      action: step.action,
      testData: step.testData || null,
      expectedResult: step.expectedResult || null,
      docString: step.docString,
      // An empty outer array is not a table — `CaseEdit.normalizeTable` reads `[]` as null and the
      // schema's description refuses it outright, so the two spellings of "no table" are collapsed
      // into the one the server acts on rather than left to differ by which control produced them.
      dataTable: step.dataTable && step.dataTable.length > 0 ? step.dataTable : null,
    })),
    classificationValueIds: data.classificationValueIds,
    // De-duplicated here as well as in the control, because the control is not the only way a value
    // reaches the form — a restored draft is now one of them.
    tags: [...new Set(data.tags.map((tag) => tag.trim()).filter((tag) => tag !== ''))],
    preconditions: data.preconditionIds.map((conditionId) => ({ conditionId })),
    postconditions: data.postconditionIds.map((conditionId) => ({ conditionId })),
    // Sent on every save, including when it is empty. Absent means "leave the declines alone" and
    // this editor renders every one of them, so absent would be a lie about a screen the author
    // can see — the same reason every other field here is sent whether or not it changed.
    declinedConditionIds: [...new Set(data.declinedConditionIds)],
    changeComment: data.changeComment || null,
  };
}

/** The blank trailing step row the editor always keeps at the end of the list (§94). */
const BLANK_ROW = {
  id: null,
  keyword: '',
  action: '',
  testData: '',
  expectedResult: '',
  docString: null,
  dataTable: null,
};

const str = (value: unknown): string => (typeof value === 'string' ? value : '');
const nullableStr = (value: unknown): string | null => (typeof value === 'string' ? value : null);
const strArray = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];

/**
 * A buffered `dataTable` back into rows of cells, or null.
 *
 * Defensive in the same way as every reader around it, and with one extra: a row that is not an
 * array is **dropped**, not coerced to an empty one, because an empty row in a Gherkin table is a
 * real row of blank cells and inventing one would renumber every row after it.
 */
const table = (value: unknown): string[][] | null =>
  Array.isArray(value)
    ? value
        .filter((row): row is unknown[] => Array.isArray(row))
        .map((row) => row.map((cell) => (typeof cell === 'string' ? cell : '')))
    : null;

/** `[{ conditionId }]` on the wire back to the ids the form binds — see `preconditionIds`. */
const conditionIds = (value: unknown): string[] =>
  Array.isArray(value)
    ? value
        .map((ref) =>
          ref && typeof ref === 'object' ? (ref as { conditionId?: unknown }).conditionId : null,
        )
        .filter((id): id is string => typeof id === 'string')
    : [];

/**
 * A buffered draft's `content` back into the form's own shape — **the inverse of
 * {@link toCasePayload}**, and the thing a restored draft goes through.
 *
 * ## Why an inverse exists at all
 *
 * The form's shape and the API's are genuinely different, and each difference has a reason
 * recorded on `CaseSchema`: an unfiled case is `''` in the form and `null` on the wire (MUI's
 * `Select` warns on `null`), a duration is the string the number input produces, and conditions are
 * ids in the form against `[{conditionId}]` on the contract. The buffer stores the **API** shape —
 * which is what makes it exactly what `PATCH` takes, and therefore what makes "restore" mean "put
 * this back in the form and press Save".
 *
 * ## It decodes DEFENSIVELY, and that is the whole point
 *
 * The buffer is unvalidated by design, and it may also have been written by an older build of this
 * app. So every field is read with a fallback and nothing here can throw: a draft that has lost a
 * field restores without it, rather than taking the editor down with a `TypeError` on the one
 * screen whose job is not losing the user's work. **A wrong-typed field reads as absent** — the
 * honest outcome, since the alternative is putting `[object Object]` in a title.
 *
 * ## The round trip is lossy in exactly one direction, deliberately
 *
 * `toCasePayload` drops blank step rows and this re-adds one, because the editor's list always ends
 * in a blank row — that is how a step is added since §94. So `from(to(x))` is `x` with its trailing
 * blank restored, which is the same normalization opening an existing case performs.
 */
export function fromCasePayload(content: unknown): CaseInput {
  const raw = (content && typeof content === 'object' ? content : {}) as Record<string, unknown>;

  const priority = str(raw.priority);
  const automationStatus = str(raw.automationStatus);
  const duration = raw.estimatedDurationSec;

  const steps = (Array.isArray(raw.steps) ? raw.steps : [])
    .filter((step): step is Record<string, unknown> => !!step && typeof step === 'object')
    .map((step) => ({
      id: typeof step.id === 'string' ? step.id : null,
      // `str`, not `nullableStr`: the form's absence is '' — see `StepSchema.keyword`. A draft
      // written before §255 carries `null` here and reads as "no keyword", which is what it meant.
      keyword: str(step.keyword),
      action: str(step.action),
      testData: str(step.testData),
      expectedResult: str(step.expectedResult),
      // `null` is a MODE and not just an absence (see `StepSchema.docString`), so a value of the
      // wrong type has to read as null rather than as ''. `nullableStr` already does exactly that.
      docString: nullableStr(step.docString),
      dataTable: table(step.dataTable),
    }));

  return {
    title: str(raw.title),
    // '' is UNFILED, not missing — the inverse of the submit's `data.suiteId || null`.
    suiteId: typeof raw.suiteId === 'string' ? raw.suiteId : '',
    objective: nullableStr(raw.objective),
    description: nullableStr(raw.description),
    expectedOutcome: nullableStr(raw.expectedOutcome),
    // An unrecognised enum value falls back to the form's own default rather than being bound and
    // rendering a `Select` with no matching option — which is a control that looks empty and
    // silently submits whatever was there.
    priority: (['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as const).includes(
      priority as 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW',
    )
      ? (priority as CaseInput['priority'])
      : 'MEDIUM',
    automationStatus: (['MANUAL', 'TO_BE_AUTOMATED', 'AUTOMATED', 'NOT_AUTOMATABLE'] as const).includes(
      automationStatus as CaseInput['automationStatus'],
    )
      ? (automationStatus as CaseInput['automationStatus'])
      : 'MANUAL',
    automationKey: nullableStr(raw.automationKey),
    notAutomatableReason: nullableStr(raw.notAutomatableReason),
    // A number on the wire, the string the input produces in the form.
    estimatedDurationSec:
      typeof duration === 'number' && Number.isFinite(duration) ? String(duration) : null,
    /*
      ~~The value the draft carried.~~ → **§256: pinned, and the buffer is why it must be.**

      A draft written before §256 carries `STRUCTURED` and keyword-less rows. Restoring it verbatim
      put the form straight into the state R1 refuses, so Save did nothing and the buffer was never
      cleared — the author's own restored work, unsavable, with no control on screen to explain it.
      Both halves are fixed here rather than at the two call sites: `prefilledKeywords` proposes the
      keywords the rows never had, and the format is what the product now has one of.
    */
    stepFormat: 'GHERKIN',
    // The trailing blank row — see the header. Without it a restored draft has no way to add a
    // step, because the blank last row IS the add control.
    steps: (() => {
      const filled = prefilledKeywords(steps);
      return [...filled, { ...BLANK_ROW, keyword: nextKeyword(filled) }];
    })(),
    classificationValueIds: strArray(raw.classificationValueIds),
    preconditionIds: conditionIds(raw.preconditions),
    postconditionIds: conditionIds(raw.postconditions),
    // Already the ids the form binds — a draft buffers the FORM's shape, and this field is a flat
    // array of attachment ids in both, so it needs no projection the way the two above do.
    declinedConditionIds: strArray(raw.declinedConditionIds),
    tags: strArray(raw.tags),
    changeComment: nullableStr(raw.changeComment),
  };
}
