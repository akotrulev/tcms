# Specification formatter — worked examples

Two client specifications, run through the format in
[`../../2026-09-07-agent-specification-formatter.md`](../../2026-09-07-agent-specification-formatter.md) §2
by hand, exactly as the `ingest-spec` process would have a model do it. Each has the page
(`*.page.md`, valid TCMS spec markdown), the bundle a `tcms spec import` would take (`*.bundle.json`,
cases and plan with `verifies` written against the page's labels), and below, **the formatter's
report** — the part of the answer that is not the page: what was dropped, what was reconstructed,
and what the source itself gets wrong or leaves open. The report is the deliverable a reviewer reads
first; the page is what they accept.

## `margin-interest` — a Confluence export, pasted as text

**Input shape.** No heading markers, a table of contents duplicated at the top (13 lines), every
table flattened to one cell per line, a status macro rendered as the words "Question Mark".

**Dropped.** The table of contents. The Confluence status macro's colour.

**Reconstructed.** Nine tables, from the header row's column count: 3 columns for the rate table,
3 for the markup tiers, 4 for the five-day example, 5 for each day's positions. Blank first cells
under "Date" were the source's own row grouping and are not carried; the day is stated in the
caption instead.

**Requirements: 32 labelled blocks.** The source uses declarative present tense throughout
("Interest rate is defined per currency"), which the format reads as a requirement; there is no
*shall* anywhere. The example section is unlabelled: it is the test data, and it became five of the
six cases.

**Reworded.** Every labelled block was rewritten to §2.4a. The ones that changed more than
cosmetically:

| Before (source) | After (page) | What changed |
|---|---|---|
| "Account Markup is used to add an additional markup on top of the base rate for a specific account." | "The account markup is a percentage added to the base rate for a specific account, and it varies by currency and by loan amount tier." | The tier dimension, stated only by the sample table, is now in the sentence |
| "The most specific plan is taken into consideration (account, if not present - account group, if not present - default)" | "When more than one plan could apply to an account, the system uses the account-specific plan; if there is none, the account-group plan; if there is none, the default plan." | Actor and condition made explicit |
| "Trigger - EOD" + "Formula: Base & Account Markup = SUM(…)" | "The system runs the accrual for an account at end of day." and "For each currency, the base-and-account component … is the sum of the day's loaned amount across all instruments in that currency, multiplied by …" | Trigger became a sentence; the formula's implicit per-currency grouping (visible only in the example) is now stated |
| "Loan amount per instrument is calculated as the sum of: … at start of the day; … on opening trades for this day." | "… plus the loaned amount of the trades opened on that instrument during the day; reductions during the day do not lower it." | The second half is what the five-day table shows and the prose never says; it is stated, and open question 6 asks to confirm it |
| "Generated/Posted - On the first business day of the month. That's when it impacts the client's account." | "Monthly posting: on the first business day of the month, the system charges the accrued interest to the client's account." | Outcome made observable |
| "Post it as a debit transaction impacting 'Available Cash'" (event driven) | "Posts the unprocessed accruals, including today's, as one debit transaction …" | Whether earlier unprocessed accruals are included is implied by "mark accruals as processed"; stated, and worth confirming |

**Arithmetic that does not reproduce from the source's own formula:**

| Where | Source says | Formula gives | Difference |
|---|---|---|---|
| Day 1 USD base and account | $27.62 | 140,000 × 7.1% / 360 = $27.61 | rounding |
| Day 2 USD base and account | $29.59 | 150,000 × 7.1% / 360 = $29.58 | rounding |
| GBP instrument, each day | £0.83 | 10,000 × 3% / 365 = £0.82 | rounding |
| Day 2 USD narrative | "$90k + $80k = $150k" | table shows $60k for US2 | typo; the total is right |
| Monthly USD posting | $94.88 | from recomputed days: $94.86 | follows from the above |
| Monthly GBP posting | £9.48 | 3 × £3.15 = £9.45 | follows from the above |

The expected values in the bundle are the **source's**, and the case objective says so: the
formatter does not correct a specification, it reports where the specification disagrees with
itself and lets the owner decide. The rounding rule (per component, per day, or at posting) is the
question to ask.

**Open questions for the owner.**
1. The three base-rate sources are marked "Question Mark" in the source — SOFR, ESTR, SONIA are
   unconfirmed.
2. "First business day" (Overview) versus "first trading day" (Posting): which calendar?
3. Is the account markup tier chosen by the **total** loaned amount in the currency? The example
   says so ($140k → Tier 2); the configuration table's "Loan amount" column does not.
4. The example's Tier 2 is "$100k+", the configuration's is "100,000.01 – 1,000,000". What applies
   above 1,000,000?
5. "Utilized margin" and "loaned amount" are used interchangeably. Confirm they are one quantity.
6. Day 3 says "Close all positions" and still accrues on the start-of-day amounts. Consistent with
   the loan-amount rule; confirm that closing intra-day never reduces that day's accrual.

## `liquidator` — plain-language prose

**Input shape.** Headings present as bare lines, three tables flattened, a twelve-paragraph guard
list where every paragraph opens with the requirement and continues with why, a status section, a
related-pages section and four Jira keys.

**Dropped.** The byline ("REFLEX · Risk Operations · September 2026"). *Where it currently stands*
and *Related pages* — status and navigation, not specification. The Jira keys FI-5455, FI-5622,
FI-5629 are carried into the plan's out-of-scope text and this report, not into the page: they are
the client's tracker, not a page of this project.

**Lifted out of tables.** All three. The bands table (4 rows), the selling rules table (6 rows) and
the operator controls table (5 rows) are each a list of requirements with a column of rationale, so
every row became a labelled list item and the tables are gone — a rule in a cell is unlinkable.
The controls table's *Safeguard* column became a nested item under each control, so a case can
verify the safeguard without claiming the control. The one table kept is the before/after example:
that is data.

**Split.** Nine of the twelve guards. Each paragraph's obligation is the labelled block; the reason
that follows it ("we learned this the hard way: 5,461 emails") is an unlabelled paragraph after it.
Coverage of the guard is then coverage of the rule, not of the anecdote.

**Reworded.** The source is prose written for people, so nearly every labelled block changed. The
pattern, with three of the fifty-two:

| Before (source) | After (page) | What changed |
|---|---|---|
| "It stops above the line, not on it. Selling back to exactly 100% would leave the trigger armed … So it aims for 105%. We learned this the hard way: … 5,461 emails …" | "The system sells to the 105% target, never to exactly 100%." followed by the reason as an unlabelled paragraph | "It" resolved; rule split from anecdote (§2.4.10) |
| "It will not sell on data it does not trust. If prices are more than a few hours stale, or a currency rate is missing, or the instrument catalogue is unreachable, it treats the position as un-sellable and holds." | "The system places no sell order for a position when its prices are more than a few hours stale, when a required currency rate is missing, or when the instrument catalogue is unreachable; the position is treated as un-sellable and held." | Three conditions kept as three; "a few hours" kept unhedged (§2.4a) |
| Controls table row: "Force a liquidation / Starts a batch on one account immediately … / Requires a written reason of at least 10 characters. The account's prior state is captured before the change." | "Force a liquidation: an operator can start a liquidation batch on one account immediately, without waiting for the next cycle." with a nested "The system requires a written reason of at least 10 characters and captures the account's prior state before acting." | Row lifted (§2.4.12); control and safeguard are two blocks so a case can verify the safeguard alone |

**Requirements: 52 labelled blocks**, including the four *What this does not do* statements —
negative requirements are requirements, and "it does not close accounts" is a test.

**Not requirements, kept as text.** 2.4 seconds, 5,461 emails, 2.55 shares, "about 151%": measured
figures from one environment, in the source's own words. The 151% opening level is labelled because
the source presents it as a consequence of the rule, but a case against it should read the parent
page's formula first.

**Open questions for the owner.**
1. Band boundaries overlap: "150% and above" is Healthy and "120 – 150%" is Warning. Which band is
   exactly 150%? Same at 120% and 100%.
2. "More than a few hours stale" — the threshold is not stated; the parent page may hold it.
3. "One notification per account per type per hour, tunable" — tunable per what, and by whom?
4. The 105% target is called both "the cushion" and a band edge. Is it configuration separate from
   the bands?
5. The parent page holds the formulas. A page for it should be ingested first, so this page's
   `margin-level-formula` block can be `DERIVED_FROM` the precise one rather than standing alone.
