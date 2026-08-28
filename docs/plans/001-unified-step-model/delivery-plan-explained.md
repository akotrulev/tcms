# Unified step model & `.feature` import — Delivery Plan — in plain language

> **This is a plain-language companion to [`delivery-plan.md`](delivery-plan.md).**
> It explains the same thing in simpler words, with examples. It adds nothing new.
> **If the two ever disagree, `delivery-plan.md` is right and this file is out of date.**

**Explains:** `delivery-plan.md` (as of 2026-08-27, commit `60233010`, including amendment AM1) · **Written by:** `be-shared-plain-language-companion`
**Examples in this document are made up** to illustrate the rules. The names, dates and figures in them are not real data.

## What gets built, in what order

Eight pieces, one after another, grouped so that each *capability* is usable by a real person before the next begins.

**Capability 1 — write, paste, view and execute a Gherkin case step by step** (pieces 1–4)

1. **The step model.** The server stores cases whose steps carry a Gherkin word, a block or a table, in either lens; the database gains its few new fields. From here on a Gherkin case has real step rows.
2. **The command-line tool and the AI-assistant connector.** Both move to the new shape so they can create and edit a Gherkin-lens case from start to finish. Lands immediately after piece 1 — the two ship together.
3. **The parser.** Pasted text becomes rows; a case can be viewed as a `.feature` document with all its preconditions as Background lines and its postconditions as a closing comment block; converting there and back gives the same rows.
4. **The screens.** One step editor for both lenses, the *Paste Gherkin* dialog, the lens switch, *View as .feature*, and the execution screen with per-step results and evidence. The case's precondition and postcondition lists already exist on screen and need no new work. *Delivers:* a person can write or paste a Gherkin scenario, save it, and a tester can pass or fail each step with evidence.

**Capability 2 — migrate existing Gherkin cases** (piece 5)

5. **The migration command.** Run once per client company by an operator, naming a member as the author. Readable cases get rows and a fresh version; unreadable ones, and ones containing a Background or a Scenario Outline, are listed for a person. Running it again changes nothing.

**Capability 3 — import feature files** (pieces 6–7)

6. **The import, server side.** Preview and apply: a suite per feature, a shared library precondition per Background line (recognised by its text, so nothing is duplicated across files or re-created on re-import), a case per scenario already labelled for automated reports. An update from the file replaces only the preconditions the import created, never a person's own attachments and never postconditions. Orphans are listed; refusals are rows.
7. **The import screen.** Drop files, review the plan — each scenario row shows the preconditions its case will carry — apply, follow the links. *Delivers:* a test engineer imports a folder and the team's first automated report matches every scenario.

**Closing** (piece 8)

8. **The record.** Handoff notes, the design document's crossed-out superseded rules, the note that the proposal was carried out.

## How the work is checked

Every piece writes its own automated tests, but **the person building a piece does not run the checks**. All checks for a batch are run afterwards, in one sitting, on the team's lab server; a piece counts as done only once that sitting is green. One known moment of red between pieces 1 and 2 is why they land together.

In-browser walkthroughs and "break it on purpose" checks are **deferred**: each piece writes down what should be walked through or broken, as rows in the two verification lists, and nobody runs them until one concentrated pass later. Until then a piece is "built and unverified" and is described that way.

## An example of the order in practice

**Maria** wants to paste a scenario and have **Tom** execute it step by step — pieces 1 to 4, built first and back to back. **Ola**, the operator, wants last year's Gherkin cases to execute step by step too — piece 5, run per company once it ships; that run is also the precondition for the later clean-up, a separate initiative. Maria's import of the automation folder is pieces 6 and 7, last.

## What is blocking us

Nothing. No credential, account or outside party is needed. Three small choices have a planned default each: where the migration command's code lives; the size limits for a step's block and table; and whether an over-long Background line is shortened or refused as a precondition title.

## What is deliberately not in this plan

Removing the old text field and other dead columns (a later initiative, after the migration has run everywhere); running the migration on the lab installation (the operator); independent contract and security testing (separate lanes); the deferred walkthrough pass; attaching automated results to individual steps; Scenario Outlines; suite-level export, which would fold identical Background lists into one; Git synchronisation; importing through the command-line tool.

## What this doesn't cover

The task-by-task file lists, dependencies, acceptance checklists and tool assignments in `delivery-plan.md` §3, the amendment log in §8, and the sections that track progress as work is done (§2, §6, §7).
