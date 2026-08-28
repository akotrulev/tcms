# Unified step model & `.feature` import — Data Model Plan — in plain language

> **This is a plain-language companion to [`data-model.md`](data-model.md).**
> It explains the same thing in simpler words, with examples. It adds nothing new.
> **If the two ever disagree, `data-model.md` is right and this file is out of date.**

**Explains:** `data-model.md` (as of 2026-08-27, commit `60233010`, including the Q7 amendment) · **Written by:** `be-shared-plain-language-companion`
**Examples in this document are made up** to illustrate the rules. The names, dates and figures in them are not real data.

## What information the system keeps, and what changes

Almost everything already exists. This initiative changes what is *written* into existing records and adds a handful of fields. Nothing is renamed and nothing is deleted.

**A step.** One line of a case. Each step keeps a permanent identity so results and screenshots stay attached to it. New: a step may carry a Gherkin word in either lens, and its data in one of three forms — a short note, a multi-line block, or a table. **At most one of the three** may be filled; the editor tells you which to keep before the database ever has to refuse it.

**A case.** The old "scenario text" field is no longer written by anything; it is kept, read-only, so unmigrated cases still display and the later clean-up can prove nothing was left behind. Three new fields record where an imported case came from: file path, line number, and the scenario's original text at the last import.

**A case's preconditions and postconditions.** This already exists and does not change: a case holds **any number of preconditions and any number of postconditions**, each a reference to a library entry, each in a chosen position. The import uses this as it is.

**A case version.** Every save takes a full snapshot; executions are pinned to a snapshot. Snapshots now include the Gherkin word and the data block or table on each step. Older snapshots simply lack them, which is correct — they show what that version said.

**A library precondition.** Shared setup text that many cases attach. Importing a feature file turns **each line of its `Background:` section into one library entry**, titled with that line (for example *"Given a paid order in status PACKED"*), holding that one line and any block or table under it. A new field remembers a fingerprint of the line's text. That fingerprint is how a repeat import recognises the entry: the same line in the same file, in a different file, or in a different position always finds the same entry. Only a line that is genuinely new creates one. A line that disappears from every file leaves its entry in the library — a person retires it, the import never does.

**Suites and tags.** Unchanged. One suite per feature; tags copied without their `@`.

## An example

**Maria** imports `orders.feature`, whose Background has two lines: *Given a signed-in customer* and *Given the shop is open*. Each of its five scenarios becomes a case with those two preconditions, in that order. She then imports `refunds.feature`, whose Background also starts with *Given a signed-in customer*. That line is not created again — its scenarios attach the existing entry.

Later, **Tom** attaches a third precondition to one of the order cases by hand — *Given the test card is loaded* — and a postcondition, *Then the order is deleted from the sandbox*. Maria swaps the two Background lines in the file and re-imports with *update*. The case now has the two import-created preconditions in the new order, then Tom's hand-attached one; Tom's postcondition is exactly where it was. Nothing new was created in the library.

## Migrating the existing text cases

The old text field is converted **once, per client company, by an operator** running a command. Each Gherkin case with text and no step rows is parsed into rows and given a new snapshot signed by the person the operator named. Three things are listed for a human rather than migrated: text the parser cannot understand (left exactly as it was); a `Background:` inside the text (the command has no authority to create library entries, so the lines are listed for someone to attach); and a Scenario Outline. Running the command twice changes nothing.

## What can go wrong

- A step saved with two data forms → refused before saving, naming the step.
- Two imports create the same new Background line at the same instant → the second is rolled back and told which line clashed.
- A Background line longer than 150 characters → the entry's title is shortened; the full line is kept in its body; the report says so. (Whether it should instead be refused is an open point for the product owner.)

## What this doesn't cover

The column-by-column tables, keys, indexes and the migration script in `data-model.md` §3 and §7, and the note about which later clean-up removes the old fields.
