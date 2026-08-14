/**
 * The one check a proposal's author is the only party able to act on.
 *
 * `POST …/ai-proposals` stores its payload **unvalidated, by design** — §138's review boundary
 * keeps generated content out of `test_case` until a person accepts it, and the create-path
 * validation runs at *accept*. That is the right ordering and it has one consequence nobody had
 * driven until §143: a proposal missing a required field is **filed 201, sits PENDING, and
 * answers the human reviewer's accept with a 400** on fields that are not on the proposal and
 * cannot be edited there. The reviewer's only move is Reject, for a case whose content is fine.
 * The contract's word for that 400 is "drift" — a suite deleted since, a value retired since —
 * and this is not drift: the payload was never valid.
 *
 * So the two non-SPA clients refuse it at the boundary, where the author is still holding the
 * thing and can fix it. This is a **presence** check and deliberately nothing more: which values
 * are legal is the server's to say (`CaseEdit.validate`), and a second copy of that here would be
 * a second answer to re-diverge. What it catches is the failure the *contract itself* caused —
 * `TestCaseInput` said `required: [title]` for a year while the server required four, so every
 * generated client, every generated type and `tcms_write_case`'s own tool description told a
 * model three mandatory fields were optional.
 *
 * The contract is fixed too (§144). This stays because the file-time endpoint still validates
 * nothing, so the contract being right only helps a caller that reads it.
 *
 * **Two of the six checks stopped mirroring the server in §200** — `steps` and `gherkinSource`,
 * which the create path now defers — and they are kept anyway, for a reason stated at the checks
 * themselves. The rest of this header still holds: every other field it names is refused by the
 * server at accept exactly as it is here.
 */
import { TcmsClientError } from '../errors.js';

/**
 * What `CaseEdit.validate` demands unconditionally, on create and on edit alike.
 *
 * The conditional four — `steps` for STRUCTURED, `gherkinSource` for GHERKIN, `automationKey`
 * for AUTOMATED, `notAutomatableReason` for NOT_AUTOMATABLE — are checked below rather than
 * listed here, because each is required only given a value of another field.
 */
export const REQUIRED_CASE_FIELDS = [
  'title',
  'stepFormat',
  'priority',
  'automationStatus',
] as const;

/**
 * Refuses a proposal whose case could not be accepted, naming every missing field at once.
 *
 * All of them in one message, never the first: a model that fixes one field and resubmits, three
 * times, has filed three proposals a person has to reject.
 *
 * @param testCase the `TestCaseInput` about to be filed, as the caller supplied it
 * @param source   how to name it in the message — the CLI's `--file`, the MCP tool's `testCase`
 */
export function requireProposableCase(testCase: Record<string, unknown>, source: string): void {
  const missing: string[] = REQUIRED_CASE_FIELDS.filter(
    (field) => testCase[field] === undefined || testCase[field] === null || testCase[field] === '',
  );

  const format = testCase['stepFormat'];
  // THE NEXT TWO ARE STRICTER THAN THE SERVER SINCE §200, and that is now a decision rather than
  // parity. The create path defers the procedure — a case may exist before anybody has written how
  // to run it — so accepting a stepless proposal would succeed and produce a DRAFT stub.
  //
  // They stay because the deferral was granted to a different act. A person typing a title into a
  // plan they are writing has said what the case is FOR and will say how it runs later; a model
  // filing a proposal has been asked for the case itself, and one with no procedure is an empty
  // proposal whose acceptance gains the reviewer nothing they could not have got by typing the
  // title. The refusal is also the only feedback the model gets — see the message below, which is
  // what a caller reads and retries against.
  //
  // Non-empty, not merely present: `CaseEdit` reads null and `[]` alike as "nothing was sent".
  if (format === 'STRUCTURED' && !(Array.isArray(testCase['steps']) && testCase['steps'].length > 0)) {
    missing.push('steps');
  }
  if (format === 'GHERKIN' && !testCase['gherkinSource']) {
    missing.push('gherkinSource');
  }
  if (testCase['automationStatus'] === 'AUTOMATED' && !testCase['automationKey']) {
    missing.push('automationKey');
  }
  if (testCase['automationStatus'] === 'NOT_AUTOMATABLE' && !testCase['notAutomatableReason']) {
    missing.push('notAutomatableReason');
  }

  if (missing.length === 0) return;

  throw new TcmsClientError(
    `${source} is missing ${missing.join(', ')}. Filing it would succeed and the proposal would `
      + 'then be un-acceptable: this endpoint stores the payload unvalidated and the same rules '
      + 'run when a person accepts it, so the refusal would land on a reviewer who cannot edit '
      + 'the proposal to fix it. stepFormat is STRUCTURED or GHERKIN, priority is one of '
      + 'CRITICAL/HIGH/MEDIUM/LOW, automationStatus is one of '
      + 'MANUAL/TO_BE_AUTOMATED/AUTOMATED/NOT_AUTOMATABLE.',
  );
}
