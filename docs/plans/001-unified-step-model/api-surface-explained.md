# Unified step model & `.feature` import — API Surface Plan — in plain language

> **This is a plain-language companion to [`api-surface.md`](api-surface.md).**
> It explains the same thing in simpler words, with examples. It adds nothing new.
> **If the two ever disagree, `api-surface.md` is right and this file is out of date.**

**Explains:** `api-surface.md` (as of 2026-08-27, commit `60233010`, including the Q7 amendment) · **Written by:** `be-shared-plain-language-companion`
**Examples in this document are made up** to illustrate the rules. The names, dates and figures in them are not real data.

## What can be asked of the system

Three new requests, and a change to the shape of an existing one.

**1. Creating or editing a case (changed).** Each step may now carry a Gherkin word, a multi-line block, or a table. The old "scenario text" field is no longer part of the request; an out-of-date tool that still sends it is ignored, as the system already treats fields it does not recognise. Reading a case back shows the old text **only** for a case not yet migrated; otherwise it is empty. An imported case also tells you its file and line. A case's lists of preconditions and postconditions are unchanged — any number of each, in order.

In the Gherkin lens the system insists that every step has a word and the first is not *And* or *But*; in either lens a step may use only one of the three data forms. All problems are reported together, each naming the step.

**2. Parse pasted text.** You send Gherkin text; you get back the title, description, tags, the steps as rows, and — if the text has a `Background:` — **one entry per Background line**, each ready to become a library precondition. Nothing is saved. Text with a mistake comes back with the line and column; text with a Scenario Outline, more than one scenario, or no steps is refused with a sentence saying which.

**3. View a case as a `.feature` document.** You get standard Gherkin text: tags, a `Feature:` line named after the suite, a `Background:` made of **every** precondition on the case in order (an entry with several lines contributes all of them), the scenario, and one line per step. A step with no Gherkin word is written with `*`. A check or test-data note becomes a comment right after its step. Because Gherkin has no way to say "postcondition", the case's postconditions are written as a comment block at the very end — so the text carries everything the case has. Pasting the text back gives the same step rows; the postcondition comments are not turned back into anything.

**4. Import feature files.** You send up to 200 files (at most 1 MiB each, 8 MiB in total, no more than 500 scenarios), say whether existing scenarios should be *skipped* or *updated*, and choose *preview* or *apply*. Both return the same report: one row per scenario with the action (create, update, skip, refuse), a reason where there is one, and **the list of precondition titles that case will have, in order**; the suites to create or reuse; **one row per distinct Background line** saying whether it is new or already in the library; the orphans; and totals. *Preview* changes nothing. *Apply* does everything in one all-or-nothing operation, then fills in each new case's key.

When *update* refreshes a case from its file, it replaces **only the preconditions the import itself created**. Preconditions a person attached stay, after the imported ones. Postconditions are never touched, and none are ever created by an import.

## Who may do what

- Reading, parsing, viewing as `.feature`, and **previewing** an import need only the right to read the project's cases.
- Creating a case needs *create case*; updating one needs *edit case*; creating a suite needs *manage suites*; creating the Background's library entries needs *manage library*.
- A missing right does **not** fail an import. Each affected row is refused with the right named, and the rest go ahead. A file with a Background, imported by someone without *manage library*, has every one of its scenarios refused — a scenario is never attached to half its Background.

## An example

**Maria** previews three files with *update existing*. The report shows 14 scenario rows: 11 *create*, 2 *update*, 1 *skip* with *"approved — not touched by UPDATE"*. Each row lists its preconditions — for the order scenarios, *"Given a signed-in customer"*, *"Given the shop is open"*. The Background section shows those two lines as *new* and a third, shared with another file, as *already in the library*. One orphan is listed: a scenario she renamed. She fixes the old case by hand and clicks *apply*.

If **Tom**, who can read cases but not create them, tried the same apply, every *create* row would come back *refused — needs case:create*.

## What can go wrong, and what you see

- Syntax mistake → *"Line 4, column 3: expected a step"*.
- A feature name matching two suites → *"two suites are named 'Orders' (SUI-3, SUI-9) — pass suiteId"*.
- A scenario's label already used in another project → refused, naming that case and project.
- Two people apply the same new scenario or Background line at once → the second apply is rolled back completely and told what clashed.
- A request over the size limits → refused before parsing, naming the limit.

## What this doesn't cover

The field-by-field tables, the fixed reason sentences, the status codes and the validation catalogue (with its initiative addendum) in `api-surface.md` §3–§5 and `validation-catalog.md`, and the two open points in `api-surface.md` §10.
