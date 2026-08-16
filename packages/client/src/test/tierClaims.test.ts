/**
 * A sentence that says somebody cannot do something must be right about who holds the key.
 *
 * ## The shape, and the three times it has been found by hand
 *
 * §210 moved seven permissions down a tier (`V52`) and closed with the lesson that made this file
 * necessary: **copy that names a role is a claim about the catalogue, and nothing checks it.** It
 * found three such sentences and swept `backend/` and `frontend/`. §211 found the same lesson was
 * true of four more in `packages/`, two of them tool descriptions, and wrote an eight-line rule over
 * that one corpus — closing, in its own "what is NOT done", that *"the `identifying` defect was a
 * class, and only one instance was looked for."*
 *
 * This is the third pass at the same population and the first one a machine makes. It is a rule
 * rather than a fourth careful reading for the reason the working agreement already states: a
 * catalogue-wide invariant needs a computed check. Reading `V1` top to bottom missed five join
 * tables; reading for tier claims twice in two days missed a whole corpus each time.
 *
 * ## Why a wrong one is expensive, and it is not the same expense everywhere
 *
 * In `backend/` and `frontend/` a stale tier claim is an argument a maintainer inherits. It is how
 * `V51` came to restate a principle `V52` then deleted, and how a gate written against old data
 * stops narrowing silently.
 *
 * On the MCP surface it is worse in kind, and that half is §211's find: **a tool description is read
 * by the model as the statement of what it may attempt.** A false one produces no error and no
 * symptom — the agent simply does not call, every session, and nothing anywhere records that it
 * declined. `tcms_write_run` said closing a run was out of reach in the same slice that granted
 * `run:close`, and the surface went on not offering it.
 *
 * And in `docs/specs/openapi.yaml` it is both at once plus a multiplier: `npm run gen:api` copies
 * every description verbatim into `schema.ts` and `schema.d.ts`, so one sentence is three on disk,
 * and the SPA renders a 403's explanation from the contract's own words.
 *
 * ## What counts as a claim
 *
 * Three forms, and the narrowness is the design. A rule over every sentence that mentions a role and
 * a key would need an ignore list bigger than the finding, which is the shape that gets a check
 * silenced rather than fixed ({@link JAVADOC_TEST_CLASS}'s argument one file over, verbatim).
 *
 * 1. **{@link TIER_PHRASE}** — `Lead-only`, `Lead and up`, `leads and above`. A tier line, and the
 *    thing it says about every key in the sentence is that the contributor below it does not hold
 *    them. No role has to be named; the phrase names one.
 * 2. **{@link EXCLUSION}** — `does not hold`, `not held by`, `withheld from`, in any of the
 *    emphasis spellings this repository writes them in. Here a role *is* named, and the check is
 *    against that role's row: `Viewer and Automation Service do not hold it` is a true sentence and
 *    the commonest instance of the form.
 * 3. **{@link QUALIFIER_CLAIM}** — a grant asserted as *narrowed*: `artifact:edit` is `PLAN_SCOPE`,
 *    `case:delete` is `OWN`-qualified, held plan-scope-only. Added 2026-08-16 (audit C11): `V51`
 *    and `V52` emptied both restrictive qualifiers, and seven of §212's hand-found repairs were this
 *    exact shape — a claim the first two forms structurally cannot see, because it names a qualifier
 *    and not a tier. Checked against the spec's own cells: a qualifier narrows a grant *in the
 *    matrix* (`quarantine only`, the one live instance), so a claim whose key has no narrowing cell
 *    is asserting data the catalogue does not hold.
 *
 * **Past tense is not a claim.** `suite:delete` *was* Lead-only is the true and load-bearing first
 * half of every migration header that moved it, and this repository's strikethrough notation
 * (already excluded by {@link liveSentences}) is for a claim that was made and is now wrong — not
 * for a sentence written in the past tense on purpose. So {@link PAST} skips a phrase whose verb
 * puts it in history. What survives both is a present-tense statement about the catalogue as it is.
 *
 * ## The reach it does not have
 *
 * The same measure §206 gave its own rule: **this catches a claim that names a key, not a claim
 * about a tier.** `openapi.yaml`'s *"Test Engineer holding the first and not the second"* is
 * exactly as false and names neither permission, and no wordlist reaches it. So the population here
 * is a floor on what a sweep by hand still has to do, which is why every repair this rule prompted
 * is recorded in §212 rather than in an ignore list.
 *
 * There is also one corpus it deliberately does not read: **the roles spec itself.** That is the
 * document {@link roleMatrix} parses, and a rule that read its prose against its own tables would be
 * settling a question with the answer.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { CORPUS, REPO, filesOf, liveSentences, proseOf } from './corpus.js';
import { NOT_GRANTED, ROLES_SPEC, roleMatrix, specMatrix, type Role } from './roleMatrix.js';

/** A tier line. Whatever key it is written about is being put above the contributor below it. */
const TIER_PHRASE = /(?:\*\*)?[Ll]eads?[- ](?:only|and up|and above)(?:\*\*)?/g;

/**
 * An exclusion, in the emphasis spellings this repository writes it in, and in **two voices**.
 *
 * The voice is not a detail — it says which side of the phrase the role is on, and reading it is
 * what stopped the rule reporting the sentence that taught it. *"The only built-in withheld from
 * them is Automation Service, which also holds no `project:read`"* is true; a first cut that took
 * every role and every key within reach of the phrase paired `project:read` with an
 * `Automation Service` that does hold it, from a clause that is about neither.
 *
 * So: **active** — `X does not hold` — puts the role in front of the phrase. **Passive** —
 * `not held by X`, `withheld from X` — puts it after. Both are matched, and {@link NAMED} is asked
 * only about the side the grammar says the subject is on.
 */
const ACTIVE = /(?:does|do)\s+(?:\*+)?not(?:\*+)?\s+hold\b/g;
const PASSIVE = /(?:is|are)?\s*(?:\*+)?not(?:\*+)?\s*held by|withheld from/g;

/**
 * A qualifier claim — form 3 in the header. A linking verb in front of the enum name (backticks,
 * bold and the `-qualified` suffix all optional), or the hyphenated compound `plan-scope-only`.
 *
 * The SPACED `plan scope only` is deliberately not matched: in this repository's prose it appears
 * only inside quotation marks, as the cell text `matrixCopies.test.ts` and `roleMatrix.ts` give as
 * their example of a wrong copy — quoting a claim is not making one, and the hyphenated compound is
 * the spelling every live claim was actually written in.
 */
const QUALIFIER_CLAIM =
  /(?:is|are|stays?|remains?)\s+(?:\*+)?`?(PLAN_SCOPE|OWN)`?(?:-qualified)?|\bplan-scope-only\b/g;

/**
 * History or negation in front of a qualifier phrase — {@link PAST}'s job, with a wider wordlist,
 * because qualifier prose narrates its own emptying far more often than tier prose does: `granted
 * it plan-scope-only` tells `V19`'s story, `carried "case:edit is OWN-qualified" until 2026-07-31`
 * quotes a line the spec reversed, and `No grant is OWN-qualified` is the repair itself. Kept
 * separate from {@link PAST} rather than merged, because `granted` in *that* list would excuse a
 * present-passive tier line (`is granted Lead-only`) the first form exists to catch.
 */
const NOT_A_QUALIFIER_CLAIM =
  /\b(?:was|were|been|used to|had|granted|carried|until|before|no|not|nothing|never|stopped|ceased)\b[^.]{0,60}$/;

/**
 * What a cell narrowing a grant by this qualifier would have to mention. The spec's cells spell a
 * narrowing in words (`quarantine only`) rather than enum names, so the check is a hint word, not
 * equality — loose on purpose, because the cost of looseness is a missed finding and the cost of
 * exactness is re-flagging every true sentence the day a migration qualifies a grant again.
 */
const NARROWED_BY: Record<string, RegExp> = { OWN: /own/i, PLAN_SCOPE: /plan/i };

/**
 * A verb that puts the phrase after it in history.
 *
 * Looked for in the sentence's own text *before* the phrase, so `run:close` was Lead-only as a
 * judgement about the work stays out and `run:close` is Lead-only does not. Three words of slack
 * because the repository writes `was, until V52, Lead-only`.
 */
const PAST = /\b(?:was|were|been|used to|had|stopped|ceased|before)\b(?:[^.]{0,40})$/;

/** How the six roles are spelled in prose, longest first so `Test Lead` wins over `lead`. */
const NAMED: { role: Role; spellings: RegExp }[] = [
  { role: 'AS', spellings: /Automation Service|automation service|a CI token|CI credential/ },
  { role: 'V', spellings: /\bViewers?\b|\bviewers?\b/ },
  { role: 'TL', spellings: /Test Leads?\b|\bleads?\b|\bLeads?\b/ },
  { role: 'TE', spellings: /Test Engineers?\b|\bengineers?\b|\bcontributors?\b/ },
  { role: 'CA', spellings: /Company Admin/ },
  { role: 'IA', spellings: /Instance Admin/ },
];

/** A catalogue key, by its namespace — narrow enough not to match `note:` or a URL scheme. */
const KEY = new RegExp(
  '\\b(?:suite|case|library|plan|run|result|evidence|doc|artifact|project|defect|workitem'
  + '|report|integration|company|token|audit|user|role|system|instance):[a-z]+\\b',
  'g',
);

/**
 * How far from the phrase a role still belongs to it, and how far a key does.
 *
 * A claim is a clause, not a paragraph, and the first cut of this rule read the whole sentence:
 * *"`V3` grants `report:view` and `report:export` together to Instance Admin, Company Admin, Test
 * Lead, Test Engineer and Viewer; the only built-in withheld from them is Automation Service"* is
 * one sentence, entirely true, and produced fifteen findings by pairing every role in it with every
 * key in it.
 *
 * **The key is the nearest one and only the nearest one**, which is the single change that took the
 * population from noise to a list somebody could work through. The sentences that broke a wider
 * reading are all the same shape — a true tier claim about one key, with a contributor's key in the
 * clause next to it: *"targeting `APPROVED` needs `case:approve`, which the roles spec grants to
 * Lead and up …, while everything else here needs `case:edit`"*. Reading forwards from the phrase
 * finds `case:edit` and reports a sentence that is right.
 *
 * **The role is the nearest one on the subject side, in the same span.** Grammar pins the side and
 * distance pins the clause, and both are needed: `CaseController` says *"the caller does not hold
 * `case:delete` here at all"* — true, about a caller and not a role — three clauses after the word
 * "contributor", and a subject side that reached that far read the two as one sentence.
 *
 * What one-key costs is stated rather than hidden: a sentence naming three keys under one tier line
 * — `PlanLifecycle`'s *"`run:close`, `suite:delete` and `plan:delete` are all Lead-only"* — is
 * reported once, against the nearest. That is enough. The output is a sentence for a person to
 * rewrite, not a count.
 */
const NEAR = 60;

type Finding = { path: string; key: string; role: Role; sentence: string };

type QualifierFinding = { path: string; key: string; qualifier: string; sentence: string };

/**
 * Claims that read as false and are not, with the reason.
 *
 * Empty, and kept as the escape hatch this repository's rules all carry — pinned both ways by the
 * test below, so an entry that stopped being needed fails rather than accumulating.
 */
const CLAIMED_BUT_TRUE_BY_DECISION: Record<string, string> = {};

describe('a tier claim in this repository\'s prose', () => {
  const held = roleMatrix();

  /*
    This file is not in its own corpus, and the exemption is narrow on purpose.

    §154's defect was a check that exempted its own SUBJECT, which is the one thing an exemption
    must never reach. What is excused here is the INSTRUMENT: a rule that hunts a phrase has to
    print the phrase, and every occurrence above is inside quotation marks giving an example of the
    form or of a sentence that was measured and is true. Quoting a claim is not making one.

    It is one file, named rather than pattern-matched, so a second file cannot join it by accident —
    and `roleMatrix.ts` beside it is deliberately NOT excused: it makes real statements about the
    catalogue, and if one of them goes stale this rule should say so.
  */
  const SELF = 'packages/client/src/test/tierClaims.test.ts';

  const files = CORPUS.flatMap((entry) => filesOf(entry)).filter((path) => path !== SELF);

  /**
   * Nearest either side, and either side because a relative clause moves the key in front of its
   * own verb — `run:close`, which a Test Engineer does not hold — about as often as the plain
   * order leaves it behind.
   */
  const nearestKey = (sentence: string, at: number, length: number): string | undefined => {
    const before = sentence.slice(Math.max(0, at - NEAR), at);
    const after = sentence.slice(at + length).slice(0, NEAR);
    const behind = [...before.matchAll(KEY)].at(-1)?.[0];
    const ahead = [...after.matchAll(KEY)].at(0);
    return behind !== undefined
      && (ahead === undefined || before.length - before.lastIndexOf(behind) <= ahead.index)
      ? behind
      : ahead?.[0];
  };

  const matrix = specMatrix();
  const qualifierFindings: QualifierFinding[] = [];

  const findings: Finding[] = files.flatMap((path) => {
    const prose = proseOf(path, readFileSync(join(REPO, path), 'utf8'));
    return liveSentences(prose).flatMap(({ text }) => {
      // Whitespace-normalised before any matching, so {@link NEAR} measures words and not
      // indentation: a YAML description wrapped under twelve spaces of indent put sixty characters
      // between a key and the qualifier written about it — one line apart on screen, and outside
      // the window as raw text. The offsets inside this copy answer nothing about the file, and
      // nothing here needs them to.
      const sentence = text.replace(/\s+/g, ' ');
      const found: Finding[] = [];
      const say = (key: string, role: Role) => {
        if (!held[role].has(key)) return;
        found.push({ path, key, role, sentence: sentence.trim() });
      };

      for (const match of sentence.matchAll(QUALIFIER_CLAIM)) {
        if (NOT_A_QUALIFIER_CLAIM.test(sentence.slice(0, match.index))) continue;
        const key = nearestKey(sentence, match.index, match[0].length);
        if (key === undefined) continue;
        const qualifier = match[1] ?? 'PLAN_SCOPE';
        const cells = matrix.get(key);
        const narrowed = cells !== undefined && [...cells.values()].some(
          (cell) => cell !== NOT_GRANTED && NARROWED_BY[qualifier]?.test(cell),
        );
        if (narrowed) continue;
        qualifierFindings.push({ path, key, qualifier, sentence: sentence.trim() });
      }

      for (const pattern of [TIER_PHRASE, ACTIVE, PASSIVE]) {
        for (const match of sentence.matchAll(pattern)) {
          if (PAST.test(sentence.slice(0, match.index))) continue;
          const before = sentence.slice(Math.max(0, match.index - NEAR), match.index);
          const after = sentence.slice(match.index + match[0].length).slice(0, NEAR);
          const key = nearestKey(sentence, match.index, match[0].length);
          if (key === undefined) continue;

          if (pattern === TIER_PHRASE) {
            // A tier line names no role: the phrase IS the role, and what it says about the key
            // beside it is that the tier below is out. Which is Test Engineer, the only
            // contributor tier there is.
            say(key, 'TE');
            continue;
          }

          // Subject on the side the voice puts it — see the two patterns.
          const subject = pattern === ACTIVE ? before : after;
          for (const { role, spellings } of NAMED) if (spellings.test(subject)) say(key, role);
        }
      }
      return found;
    });
  });

  it('reads every corpus', () => {
    // The vacuity guard §205 got wrong by counting the whole population: a root whose directory was
    // renamed contributes nothing and the total still clears a threshold the biggest root meets
    // alone. Per corpus, therefore, and on the sentences rather than the files — a walk that finds
    // files whose text nothing splits is the same silent nothing one layer down.
    for (const entry of CORPUS) {
      const corpus = filesOf(entry);
      expect(corpus.length, `${entry.root} contributed no files`).toBeGreaterThan(0);
      const sentences = corpus.reduce(
        (total, path) => total + liveSentences(readFileSync(join(REPO, path), 'utf8')).length,
        0,
      );
      expect(sentences, `${entry.root} contributed no sentences`).toBeGreaterThan(100);
    }
  });

  it('reads the catalogue from the document that decides it', () => {
    // `roleMatrix` throws rather than returning a thin answer, so reaching here means the tables
    // parsed. What is asserted here is the other half — that the file is where this says it is,
    // since a rule that pointed at a moved spec would fail loudly and for the wrong reason.
    expect(readFileSync(join(REPO, ROLES_SPEC), 'utf8')).toContain('### A3. Permission matrix');
    expect(held.TE.has('run:close'), 'V52 granted run:close to Test Engineer').toBe(true);
    expect(held.TE.has('case:approve'), 'approvals are the tier').toBe(false);
    expect(held.V.has('case:edit'), 'a Viewer writes nothing').toBe(false);
  });

  it('is true of the role it names', () => {
    const unresolved = [...new Set(findings
      .filter((finding) => CLAIMED_BUT_TRUE_BY_DECISION[finding.sentence] === undefined)
      .map((finding) => `  ${finding.path}\n    says ${finding.role} lacks \`${finding.key}\`: `
        + `"${finding.sentence.slice(0, 220)}"`))];

    expect(unresolved.join('\n'), `${unresolved.length} sentences claim a role lacks a permission `
      + `the roles spec grants it:\n\n${unresolved.join('\n')}\n\n`
      + 'A permission moving down a tier does not edit the prose that argued for the old line. '
      + 'Correct the sentence, or — if it is deliberately about the past — write it in the past '
      + 'tense or strike it through (`~~x~~ → y`), which is what this repository already does with a '
      + 'claim that has been overruled.')
      .toBe('');
  });

  it('still recognises the qualifier form', () => {
    // The vacuity guard the third form needs and the corpora cannot give it: a repository whose
    // prose is clean matches nothing, which reads exactly like a pattern that stopped matching.
    // These are the three live spellings §C11's sweep actually found, quoted as instrument.
    for (const spelling of [
      'a Test Engineer\'s `artifact:edit` is `PLAN_SCOPE`, so a project write is refused',
      '`case:delete` is OWN-qualified for a Test Engineer (roles spec §A5)',
      '`artifact:edit` is held outright by a Test Lead and plan-scope-only by a Test Engineer',
    ]) {
      expect([...spelling.matchAll(QUALIFIER_CLAIM)].length, spelling).toBeGreaterThan(0);
    }
    // And the spaced quote-form stays out — see the pattern's own header.
    expect([...'a copy that says `plan scope only` where the spec says `✓`'
      .matchAll(QUALIFIER_CLAIM)]).toHaveLength(0);
  });

  it('claims no narrowing the spec does not print', () => {
    const unresolved = [...new Set(qualifierFindings
      .filter((finding) => CLAIMED_BUT_TRUE_BY_DECISION[finding.sentence] === undefined)
      .map((finding) => `  ${finding.path}\n    says \`${finding.key}\` is ${finding.qualifier}`
        + `-narrowed: "${finding.sentence.slice(0, 220)}"`))];

    expect(unresolved.join('\n'), `${unresolved.length} sentences claim a grant carries a `
      + `qualifier the roles spec's matrix does not print:\n\n${unresolved.join('\n')}\n\n`
      + '`V51` emptied `OWN` and `V52` emptied `PLAN_SCOPE`; the mechanism is retained (spec §A5) '
      + 'and the data is gone. Correct the sentence, write it in the past tense, or strike it '
      + 'through (`~~x~~ → y`) — or, if a migration really re-qualified a grant, the spec\'s own '
      + 'cell moves first and this rule reads it from there.')
      .toBe('');
  });

  it('excuses nothing that has stopped needing it', () => {
    // §162's rule. A one-directional ignore list cannot report a member that came back.
    for (const [sentence, reason] of Object.entries(CLAIMED_BUT_TRUE_BY_DECISION)) {
      expect(reason.length, `${sentence} is excused without a reason`).toBeGreaterThan(40);
      expect(
        findings.some((finding) => finding.sentence === sentence)
          || qualifierFindings.some((finding) => finding.sentence === sentence),
        `${sentence.slice(0, 80)} is excused and nothing says it any more — take the entry out`,
      ).toBe(true);
    }
  });
});
