import type { AutomationStatus, Priority, StepFormat } from '../api/types';

/**
 * What a case is when nobody has chosen anything — the three values `TestCaseInput` requires beside
 * the title, and the only three the SPA is ever entitled to invent.
 *
 * ## Why this is one constant and not two sets of `??`
 *
 * There are two places a case comes into existence from something less than a filled-in form: the
 * editor opened on nothing (`CaseEditorPage`'s `defaultValues`), and a row typed on the plan's Test
 * cases tab, which is a **title and nothing else** (§97 D2). Both have to answer the same question —
 * *what does this case say before anybody has said otherwise* — and answering it twice is how the
 * two drift into two products' worth of defaults, which is §183's finding about a colour and §179's
 * about a severity, arriving on a payload instead of on a screen.
 *
 * ## Why these three values
 *
 * `MEDIUM` and `MANUAL` are the neutral member of their enum: neither claims anything about the case
 * that the author did not.
 *
 * `stepFormat` is no longer a choice. ~~`STRUCTURED` is the neutral one... the default is still the
 * permissive one — a structured row needs no keyword~~ → **superseded in §256: there is one format
 * and it is `GHERKIN`.** The permissiveness that argument rested on is what made the editor mint
 * keyword-less rows and then refuse them, and the keyword is now supplied by `nextKeyword` as each
 * row is started rather than asked for. This constant keeps the field because `TestCaseInput` still
 * requires it on the wire, not because anything still decides it.
 *
 * ## What this does NOT make legal, and it is deliberately not hidden here
 *
 * The four fields the contract lists as `required` are exactly the four `CaseEdit.validate` refuses
 * unconditionally. It refuses **more** conditionally — a case with no steps among them —
 * and §144 decided those stay off the contract rather than being spelled with a four-way `oneOf`.
 * So a payload built from a title plus these three is everything the *contract* can ask for and one
 * server rule short of a case: see §199 for the decision that is still open about a case authored
 * from a plan row.
 */
export const NEW_CASE_DEFAULTS: {
  stepFormat: StepFormat;
  priority: Priority;
  automationStatus: AutomationStatus;
} = {
  stepFormat: 'GHERKIN',
  priority: 'MEDIUM',
  automationStatus: 'MANUAL',
};
