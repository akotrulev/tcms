# How the liquidator works

The liquidator is the component that intervenes when a customer trading on borrowed money loses more than their own cash covers. This page states what it watches, when it acts, and what it deliberately refuses to do.

This page is the plain-language specification. The formulas live on the parent page, "Margin Call & Auto Liquidation".

## Why it exists at all

A margin account lets a customer control more stock than their cash alone would buy; the firm lends the difference. If the position rises, the customer keeps the gain on borrowed money. If it falls far enough, the loss consumes the customer's cash and then the firm's loan, and a customer left alone could end up owing more than they deposited.

The liquidator closes that gap early, by selling part of the position while its value still covers the loan, so that neither the customer nor the firm is left with an unrecoverable debt. {#purpose}

Liquidation is a brake, not a punishment and not a trading decision: it engages only when the margin level says the loan is no longer safely covered.

## The one number everything hangs on

The margin level of an account is the account's value divided by its maintenance requirement, expressed as a percentage. {#margin-level-formula}

100% is the line: at or above it the account holds at least the required cushion; below it, the cushion is gone. {#margin-level-line}

The maintenance requirement is a percentage of each position's value, configured per account group and per instrument. {#maintenance-requirement}

When an account's maintenance rate and an instrument's maintenance rate differ, the system applies the stricter of the two: an instrument can tighten an account's terms but never loosen them. {#stricter-rate-wins}

A customer who borrows the maximum permitted opens at the same margin level regardless of instrument, size or currency; on standard terms that opening level is about 151%. {#opening-margin-level}

## The four bands

The system places every account carrying exposure into one of four bands by its margin level, and each band has a fixed effect:

- Healthy (150% and above): no action, no email, no restriction. {#band-healthy}
- Warning (120 – 150%): the account is shown to operators as a watch state; the customer is neither restricted nor emailed. {#band-warning}
- Critical, the margin call (100 – 120%): the system emails the customer and freezes withdrawals, transfers and new buys; it sells nothing, so the customer can still resolve it themselves. {#band-critical}
- Auto-liquidation (below 100%): the system sells part of the position, enough to return the account above the line, stops at the 105% target, and hands the account back. {#band-liquidation}

The band thresholds are configuration: an operator can change them within the guardrails stated under "The guards". {#bands-configurable}

An account always passes through the Warning and Critical bands, and the customer is notified, before the system sells anything. {#bands-before-selling}

## What happens every ten seconds

A background job re-evaluates every account carrying exposure once every ten seconds. {#cycle-every-ten-seconds}

On a cluster, exactly one server performs a given cycle, so two servers never act on the same account at the same time. {#cycle-single-server}

Each cycle, for each account:

- Recalculates: values every position at current market prices, converts every value into the account's currency, and computes the margin level. {#cycle-recalculate}
- Bands: compares the level against the four thresholds and records whether the band changed since the previous cycle. {#cycle-band}
- Acts on the band: raises a margin call, clears one, starts selling, or does nothing. {#cycle-act}
- Records: writes the resulting state, the margin level and the reason, so that every transition is auditable afterwards. {#cycle-record}

## What it sells, and how much

Liquidation trims positions; it never closes the account and never sells the whole portfolio. {#sells-partially}

- Position choice: the system ranks the account's positions by market value in the account's currency and sells from the largest. {#sell-biggest-first}
- One position per cycle: the system sells one position, waits, re-measures the margin level, and sells again only if the account is still below the line. {#sell-one-per-cycle}
- Quantity: the system sells the quantity calculated to bring the margin level to the 105% target, and no more. {#sell-to-target}
- Whole units: for an instrument that cannot be traded in fractions, the system rounds the quantity up to the next whole unit. {#sell-whole-units}
- Cap: the quantity never exceeds the quantity held, so liquidation can never open a short position. {#sell-capped-at-holding}
- Skip: the system never sells a holding that carries no maintenance requirement, whatever its size, because selling it releases no cushion. {#sell-skip-no-requirement}

### A real example, with real numbers

Measured on the development environment, September 2026.

Table: One liquidation, before and after.
| | Before | After |
|---|---|---|
| Shares held | 2.55 | 2.16 |
| Margin level | 88.9% (below the line) | 105% (over the line) |
| Sold | – | 0.39 shares, 15% of the holding |

One sale, 2.4 seconds start to finish, 85% of the position untouched. The account was 11 points under the line; it gave up 15% of the holding and kept the rest.

## The guards: what it refuses to do

Most of the engineering is in the refusals rather than the selling. Each guard exists because the alternative fails in a specific, expensive way.

The system sells to the 105% target, never to exactly 100%. {#guard-stop-above-line}

Selling back to exactly 100% would leave the trigger armed, and the next cycle would sell again. With the cushion once applied the wrong way round, one test account generated 5,461 emails before anyone noticed.

The system places no sell order while the instrument's exchange is closed; the account stays flagged and selling resumes on the first cycle after the market opens. {#guard-closed-market}

The system places no sell order for a position when its prices are more than a few hours stale, when a required currency rate is missing, or when the instrument catalogue is unreachable; the position is treated as un-sellable and held. {#guard-untrusted-data}

Before its first sale on an account, the system cancels the customer's own pending buy orders; it never cancels operator orders or scheduled-investment orders. {#guard-cancel-buys-first}

An open buy order reserves cushion the account needs.

The system holds a lock on the account while it acts, so it never has two sell orders open at once, whether from concurrent cycles or from two servers. {#guard-one-sell-order}

When a sell order for a position fails, the next cycle selects a different position rather than retrying the same one. {#guard-move-on}

When nothing on the account can be sold for a reason that will not clear on its own, the system parks the account with a named cause, surfaces it to operators, and stops retrying it every cycle. {#guard-park}

A closed market is temporary and the system simply waits; a holding too small to trade needs a person.

The system sends a customer at most one notification per account per notification type per hour; an operator can tune that limit. {#guard-email-throttle}

The throttle applies only to the customer's inbox: the platform's own records, alerts and audit trail are never throttled, so a future fault cannot be hidden by this guard. {#guard-throttle-inbox-only}

An instrument counts toward an account's cushion only when all three conditions hold: the account has margin enabled, the account has margin terms assigned, and the instrument is marginable; if any fails, there is nothing to liquidate, so a cash account is never touched. {#guard-cash-account}

The system refuses a configuration change that would delete the band that triggers selling, delete the band that warns customers, or set a cushion so large that liquidation would end above the margin-call threshold; each refusal states the consequence it prevented. {#guard-config-validation}

An account with no maintenance requirement has no margin level: the system shows it as blank everywhere and never as 0%. {#guard-no-requirement-blank}

On this scale 0% would be the deepest possible breach and would liquidate a perfectly funded account.

The system records every band change, margin call, sale and park together with the margin level at that moment and a full snapshot of the account, so any decision can be reconstructed afterwards. {#guard-audit}

## Who can intervene, and how

- Pause the engine: an operator can stop all evaluation and selling, platform-wide. {#control-pause}
  - A pause survives a restart, applies on every server, and is recorded with the operator's stated reason. {#control-pause-safeguard}
- Force a liquidation: an operator can start a liquidation batch on one account immediately, without waiting for the next cycle. {#control-force}
  - The system requires a written reason of at least 10 characters and captures the account's prior state before acting. {#control-force-safeguard}
- Close a position by hand: an operator can sell a specific holding from the admin panel. {#control-close-position}
  - The panel shows the projected margin level before and after the sale and requires a reason. {#control-close-position-safeguard}
- Change the bands or the cushion: an operator can re-tune when warnings and selling happen. {#control-change-bands}
  - The system refuses a change that would leave the ladder unusable; an accepted change takes effect on the next cycle without a restart. {#control-change-bands-safeguard}
- Change margin terms: an operator can adjust how much cushion an account or an instrument demands. {#control-change-terms}
  - The stricter of the account's and the instrument's terms always applies, so a change can never silently loosen an instrument's terms. {#control-change-terms-safeguard}

## What this does not do

- The system does not close accounts: it trims positions and returns the account still open and still trading. {#not-close-accounts}
- The system does not choose positions by any view of their prospects: position choice is by size only. {#not-pick-winners}
- The system does not guarantee that no loss occurs: a large overnight gap can take an account below the line before any cycle can act. {#not-guarantee}
- The system does not replace the customer's own action: the Warning and Critical bands exist so the customer can add funds or close positions first, and selling is the fallback. {#not-replace-customer}
