# Unified step model & `.feature` import — Integration Plan — in plain language

> **This is a plain-language companion to [`integrations.md`](integrations.md).**
> It explains the same thing in simpler words, with examples. It adds nothing new.
> **If the two ever disagree, `integrations.md` is right and this file is out of date.**

**Explains:** `integrations.md` (as of 2026-08-27, commit `60233010`) · **Written by:** `be-shared-plain-language-companion`
**Examples in this document are made up** to illustrate the rules. The names, dates and figures in them are not real data.

## Which outside things we rely on

**One new component, which is a library, not a service.** Reading Gherkin text correctly is harder than it looks, so we use the official Gherkin parser published by the Cucumber project rather than writing our own. It runs inside our own server. It needs no account, no key, no network connection, and behaves identically in every environment. If it were missing, the software would not even build — so it cannot be "down" at runtime.

**One existing connection, unchanged.** Teams already send their automated test results (Cucumber reports) to the system. Each result is matched to a case by a label made of the feature name and the scenario name. The import writes exactly that label onto every imported case, so the very first report a team sends after importing matches every scenario. To make sure the two halves never drift, the label is spelled in one shared place that both the import and the report reader use.

## Nobody needs to go and get anything

There is no credential to request, no sandbox to set up, no supplier to contact, and no environment to provision. A developer can run everything on their own machine on day one.

The only human step in the whole initiative is operational, not something to obtain: once the migration command exists, an operator runs it on each installation. That step is what unlocks the later clean-up work, and it is recorded there, not here.

## What breaks if something goes wrong

- **The parser cannot understand some text.** It reports the line and column. In the paste dialog you see that message; in an import that file is listed as refused; in the migration command that case is listed. Nothing is saved or changed in any of the three.
- **A future version of the parser understands a construct we do not.** The import refuses that scenario naming the line, rather than crashing. Upgrading the parser is treated as its own piece of work, checked against a test file that contains every construct we support.
- **A team never sends a Cucumber report.** Nothing breaks. The imported cases simply have no automated results yet.

## An example

**Maria's** team keeps its automation in a folder of `.feature` files. She imports the folder on Monday. On Tuesday the team's build server sends its nightly Cucumber report as it always has. Because every imported case already carries the label the report uses, all 14 scenarios are matched and none appear in the "unmatched" list — no one had to pair them by hand.

## What this doesn't cover

The library's version pin, licence and test strategy, the size limits that bound parsing, and the failure matrix in `integrations.md` §2–§4.
