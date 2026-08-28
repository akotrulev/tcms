# Unified step model & `.feature` import — Solution Blueprint — in plain language

> **This is a plain-language companion to [`blueprint.md`](blueprint.md).**
> It explains the same thing in simpler words, with examples. It adds nothing new.
> **If the two ever disagree, `blueprint.md` is right and this file is out of date.**

**Explains:** `blueprint.md` (as of 2026-08-27, commit `60233010`, including the Q7 amendment) · **Written by:** `be-shared-plain-language-companion`
**Examples in this document are made up** to illustrate the rules. The names, dates and figures in them are not real data.

## What is wrong today

A test case can be written in two styles. The *structured* style is a numbered list of steps, each with an action, optional test data and an optional expected result. The *Gherkin* style uses the Given / When / Then words that automation teams use.

Today the two styles are stored completely differently. A structured case is a list of step records, and each step has a permanent identity. That identity is what lets a tester mark step 3 as failed, attach a screenshot to it, and still find that screenshot on step 3 after someone reorders the steps. A Gherkin case is stored as **one block of text**. It has no step records at all. So a Gherkin case cannot be marked step by step, cannot hold evidence per step, and the system cannot tell whether it says what should happen.

## What we are building

One rule: **every case is a list of step records.** A step may carry a Gherkin word (Given, When, Then, And, But). A step may carry its data in one of three forms: a short test-data note, a multi-line block, or a small table. The "Gherkin" or "structured" choice becomes a *lens* — it decides what the editor asks for, not how the case is stored.

A case also keeps what it already has: **any number of preconditions and any number of postconditions**, each a reference to a shared entry in the project's library, in a chosen order. The editor already shows both lists with *Attach a precondition* and *Attach a postcondition*. Nothing about that changes.

Four pieces of work, in order:

1. **One step model.** Gherkin cases get real step records. Testers get per-step results and evidence for the first time. Switching lenses loses nothing.
2. **Text in, text out.** An author can paste a scenario and get step rows back. Anyone can view a case as a standard `.feature` document generated from the rows.
3. **Migrating what already exists.** An operator runs a command, once per client company, that turns each existing Gherkin text block into step rows. Cases whose text cannot be understood are listed for a person; nothing is guessed.
4. **Importing feature files.** A team drops its `.feature` files onto a project. Each file becomes a suite, each scenario a case, and each case is already labelled so the team's first automated test report matches it.

## An example

**Maria** writes tests for an online shop. She pastes a four-step scenario in the Gherkin lens, adds a concrete check to the third step, and saves. **Tom** runs it the next day, sees four rows, passes three and fails the fourth with a screenshot. A week later Maria rewords step two; Tom's screenshot is still on step four, because the step kept its identity.

## The decisions we made, and why

- **A file's `Background:` section becomes shared preconditions — one library entry per Background line**, attached to every scenario from that file, in the same order. If Maria's `orders.feature` has a three-line Background, each of its scenarios gets those three preconditions. The same line appearing in two different files becomes **one** shared entry, not two. Reordering the Background in the file reorders the attachments and creates nothing new. Creating those entries needs the *manage library* right; a person without it sees every scenario from that file refused, with the missing right named, and other files still go ahead.
- **Postconditions are untouched by importing.** Gherkin has no way to write a postcondition, so an import never creates one. When a re-import updates a case, it replaces only the preconditions it created itself; it never removes a precondition a person attached by hand, and it never touches postconditions.
- **Viewing a case as `.feature` shows everything.** Its preconditions become `Background:` lines, in order. Its postconditions, which Gherkin cannot express, are written as a comment block at the end, so nothing on the case is lost in the text.
- **Nothing is removed from the database in this initiative.** The old text column stays, read-only, until every installation has run the migration command. Removing it is a later, separate piece of work.
- **The migration command records who ran it.** It takes the email of a member of that company and refuses to start otherwise.
- **Importing the same files twice does nothing the second time.** Every scenario is recognised by its label; every Background line by its text.
- **The old text field disappears from the "write a case" request.** Our command-line tool and AI-assistant connector are updated in the same initiative. Anything else still sending it is ignored, as the system already treats fields it does not know.
- **Scenario Outlines are refused, with the line number.** **Imports never delete** — vanished scenarios are listed as orphans for a person to decide.

## What can go wrong, and what happens

- Pasted text has a mistake → the dialog shows the line and column. Nothing is saved.
- An imported file has a mistake → that file is listed as refused; the others still import.
- Two suites already have the feature's name → that file is refused, naming both suites.
- A scenario's label already belongs to a case in **another** project → refused, naming that case.
- Two people import the same new scenario or the same new Background line at the same instant → the second attempt is rolled back entirely and told what clashed.
- The migration command meets a case it cannot parse → listed and left untouched; the rest are migrated.

## What this doesn't cover

The architecture choice and its rejected alternatives, the flow diagrams and decision tables, the routing of work to specific tools, and the risk table in `blueprint.md`. The full list of decisions taken at approval, including the amendment, is in `blueprint.md` §10a.
