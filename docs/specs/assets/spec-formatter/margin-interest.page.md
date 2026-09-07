# Margin interest

## Overview

This document specifies how margin interest is calculated, accrued and posted.

A margin trade is a loan from the firm to the client. The firm charges interest on the amount loaned.

The effective interest rate for a loan is the base rate of the loan's currency, plus the account markup that applies to the client's account, plus the instrument markup that applies to the instrument consuming the margin. {#interest-composition}

Margin interest is:

- Calculated per trade on the margin that trade utilizes, using the base rate, the account markup and the instrument markup. {#calc-basis}
- Accrued once per day, on the sum of the margin utilized by all open trades that day. {#accrue-daily}
- Posted to the client's account on the first business day of each month, for the previous month. {#post-monthly}

## Configurable parameters and levels

Every parameter in this section is operator-configurable, not fixed in code. {#params-configurable}

### Base rate and total days

The system holds one base rate and one day-count ("total days") per currency. {#rate-per-currency}

Table: Base rate and day count per currency. The base rate source carries a "Question Mark" status marker in the source, meaning it is not yet confirmed.
| Currency | Base rate | Total days |
|---|---|---|
| USD | SOFR (to be confirmed) | 360 |
| EUR | ESTR (to be confirmed) | 360 |
| GBP | SONIA (to be confirmed) | 365 |

### Account markup

The account markup is a percentage added to the base rate for a specific account, and it varies by currency and by loan amount tier. {#account-markup}

An account markup plan can be defined at three levels:

- A default plan, applying to every account. {#account-markup-default}
- An account-group plan, applying to every account in that group. {#account-markup-group}
- An account-specific plan, applying to one account. {#account-markup-account}

When more than one plan could apply to an account, the system uses the account-specific plan; if there is none, the account-group plan; if there is none, the default plan. {#account-markup-precedence}

Table: Sample account markup setup, by currency and loan amount tier.
| Currency | Loan amount | Markup |
|---|---|---|
| USD | 0 – 100,000 | 2% |
| EUR | 0 – 100,000 | 2.5% |
| GBP | 0 – 100,000 | 3% |
| USD | 100,000.01 – 1,000,000 | 1.75% |
| EUR | 100,000.01 – 1,000,000 | 2.25% |
| GBP | 100,000.01 – 1,000,000 | 2.75% |

### Instrument markup

The instrument markup is a percentage added, on top of the account markup, for the instrument whose margin is being charged. {#instrument-markup}

An instrument markup can be defined at two levels:

- On one specific instrument. {#instrument-markup-instrument}
- On an instrument markup group, which is then assigned to a set of instruments. {#instrument-markup-group}

When resolving an instrument's markup, the system uses the instrument-level markup; if there is none, the markup of the instrument's group; if there is none, 0%. {#instrument-markup-precedence}

## Calculation engine logic

The engine handles three events:

- Daily accrual: the system calculates the day's interest and records it internally, without charging the client. {#event-accrual}
- Monthly posting: on the first business day of the month, the system charges the accrued interest to the client's account. {#event-posting}
- Event-driven posting: on defined key events, such as the client withdrawing money, the system accrues and charges immediately. {#event-key-events}

### Accrual

The system runs the accrual for an account at end of day. {#accrual-trigger}

For each currency, the base-and-account component of the day's interest is the sum of the day's loaned amount across all instruments in that currency, multiplied by (base rate + account markup), divided by the currency's total days. {#accrual-base-formula}

For each currency, the instrument component of the day's interest is the sum, over every instrument that had a position during the day, of that instrument's loaned amount for the day multiplied by its instrument markup, divided by the currency's total days. {#accrual-instrument-formula}

The accrued daily interest for a currency is the base-and-account component plus the instrument component. {#accrual-total}

The system adds the accrued daily interest to the account's ledger in that currency. {#accrual-ledger}

#### Loan amount per instrument for a day

An instrument's loaned amount for a day is its loaned amount at start of day plus the loaned amount of the trades opened on that instrument during the day; reductions during the day do not lower it. {#loan-amount-per-day}

Example:

- Day 1: a position is opened with a loan amount of $10,000.
- Day 2: nothing changes.
- Day 3: part of the position is closed, reducing the loan amount to $7,000, and a new position is opened on the same instrument for a further $5,000.
- Day 4: the whole position is closed.
- Day 5: nothing is open.

Table: Loan amount per instrument over five days, from the source's example.
| Day | Loan amount SOD | Loan amount opened | Total loan amount |
|---|---|---|---|
| 1 | $0 | $10,000 | $10,000 |
| 2 | $10,000 | $0 | $10,000 |
| 3 | $10,000 | $5,000 | $15,000 |
| 4 | $15,000 | $0 | $15,000 |
| 5 | $0 | $0 | $0 |

### Posting

The system runs monthly posting at 00:00:00 on the first trading day of the month. {#posting-trigger}

Monthly posting, per account and currency:

- Sums every accrual that has not yet been processed. {#posting-sum}
- Posts that sum as one debit transaction against the account's "Available Cash". {#posting-debit}
- Marks each of those accruals as processed. {#posting-mark-processed}

### Event driven

The system runs event-driven posting when a defined key event occurs on the account. {#event-trigger}

Event-driven posting, per account and currency:

- Runs today's accrual on the account's current "Margin Loan Amount". {#event-accrue-today}
- Posts the unprocessed accruals, including today's, as one debit transaction against "Available Cash". {#event-debit}
- Marks those accruals as processed. {#event-mark-processed}

## Example

### Setup

- Currencies: USD, GBP.
- Base rates: USD 5.1%, GBP 5.5%.
- Day count conventions: USD 360, GBP 365.
- Account markups (USD): Tier 1 ($0 – $100k) 2.5%; Tier 2 ($100k+) 2%.
- Account markup (GBP): one tier (£0+) 3%.
- Instrument markups: US1 5%, US2 0%, GB1 3%.

### Accruing

Day 1: positions are opened with the following utilized margin (the part not covered by the client's cash): US1 $90k, US2 $50k, GB1 £10k.

Table: Day 1 loaned amounts per instrument.
| Currency | Instrument | Loaned amount SOD | Loaned amount opened | Total loaned amount |
|---|---|---|---|---|
| USD | US1 | $0.00 | $90,000.00 | $90,000.00 |
| USD | US2 | $0.00 | $50,000.00 | $50,000.00 |
| GBP | GB1 | £0.00 | £10,000.00 | £10,000.00 |

Day 1, USD:

- The applicable account tier is Tier 2 (2%), because the USD loaned amount is $90k + $50k = $140k.
- Base and account component = ($90k + $50k) × (5.1% + 2%) / 360 = $27.62.
- Instrument component: US1 = ($90,000 × 5%) / 360 = $12.50; US2 = ($50,000 × 0%) / 360 = $0.
- USD accrual = $27.62 + $12.50 = $40.12.

Day 1, GBP:

- One account tier (3%) on a base rate of 5.5%.
- Base and account component = £10k × (5.5% + 3%) / 365 = £2.33.
- Instrument component = (£10k × 3%) / 365 = £0.83.
- GBP accrual = £2.33 + £0.83 = £3.16.

Day 2: the US1 position is closed; an additional US2 position of $10k is opened; GB1 is unchanged.

Table: Day 2 loaned amounts per instrument.
| Currency | Instrument | Loaned amount SOD | Loaned amount opened | Total loaned amount |
|---|---|---|---|---|
| USD | US1 | $90,000.00 | $0.00 | $90,000.00 |
| USD | US2 | $50,000.00 | $10,000.00 | $60,000.00 |
| GBP | GB1 | £10,000.00 | £0.00 | £10,000.00 |

Day 2, USD:

- The applicable account tier is Tier 2 (2%), because the USD loaned amount is $90k + $60k = $150k (the source writes "$90k + $80k").
- Base and account component = ($90k + $60k) × (5.1% + 2%) / 360 = $29.59.
- Instrument component: US1 = ($90,000 × 5%) / 360 = $12.50; US2 = ($60,000 × 0%) / 360 = $0.
- USD accrual = $29.59 + $12.50 = $42.09.

Day 2, GBP: unchanged from Day 1; GBP accrual = £3.16.

Day 3: all positions are closed.

Table: Day 3 loaned amounts per instrument.
| Currency | Instrument | Loaned amount SOD | Loaned amount opened | Total loaned amount |
|---|---|---|---|---|
| USD | US1 | $0.00 | $0.00 | $0.00 |
| USD | US2 | $60,000.00 | $0.00 | $60,000.00 |
| GBP | GB1 | £10,000.00 | £0.00 | £10,000.00 |

Day 3, USD:

- The applicable account tier is Tier 1 (2.5%), because the USD loaned amount is $60k.
- Base and account component = $60k × (5.1% + 2.5%) / 360 = $12.67.
- Instrument component: US2 = ($60,000 × 0%) / 360 = $0.
- USD accrual = $12.67.

Day 3, GBP: unchanged from Day 1; GBP accrual = £3.16.

Day 4: no trades; every loaned amount is zero; USD accrual $0, GBP accrual £0.

### Posting

On the first trading day of the next month:

- A debit of $94.88 ($40.12 + $42.09 + $12.67) is posted on the USD wallet.
- A debit of £9.48 is posted on the GBP wallet.
