/**
 * A **presentation** scanner for Gherkin text — colour, line numbers, and the fragment under the
 * caret. It has no authority over anything.
 *
 * ## Read this before adding a rule to it (003 C2)
 *
 * `featureFiles.ts` states the standing rule: *"No Gherkin parsing. The one parser is
 * `GherkinScenarios` on the server, and `POST …/cases/parse-gherkin` exists precisely so the SPA
 * does not grow a second one."* That rule is amended in place rather than deleted, and the
 * amendment is narrow: **a non-authoritative colouring scanner is allowed; a parser whose output is
 * committed is not.**
 *
 * So, concretely, what this module may and may not do:
 *
 * | May | May not |
 * |---|---|
 * | Say which spans of a line are a keyword, a sentence, a comment, a tag, a docstring, a table | Decide that text *is* a valid scenario |
 * | Number the lines, so a server refusal can land on the one it names | Produce `steps`, a title, tags or conditions for a save |
 * | Answer what fragment sits under the caret, for the completion popup | Be consulted when the two disagree |
 *
 * Nothing here reaches a payload. The Write surface commits by posting the **text** to
 * `parse-gherkin` and using what the server read; if this scanner and the server disagree about a
 * line, the server is right and the only visible consequence is a word painted the wrong colour.
 * That asymmetry is the whole reason the amendment is safe, and it is why every function here is
 * pure and returns spans rather than domain objects.
 *
 * ## English only, deliberately
 *
 * `GherkinScenarios` renders and parses English keywords — `openapi.yaml` says the `.feature` text
 * is *"always in English"* — so a localized keyword table here would colour text the server then
 * refuses, which is worse than colouring nothing.
 */

/** The five step keywords, plus Gherkin's own spelling for a step that has none. */
const STEP_KEYWORDS = ['Given', 'When', 'Then', 'And', 'But'] as const;

/** The block keywords that open a section rather than a step. */
const SECTION_KEYWORDS = ['Feature', 'Background', 'Scenario Outline', 'Scenario', 'Examples', 'Rule'] as const;

export type SpanKind =
  /** `Given`, `When`, …, or a section keyword up to and including its colon. */
  | 'keyword'
  /** The step's or section's own words. */
  | 'sentence'
  /** A `"""` fence, or a line inside the block it opens. */
  | 'docstring'
  /** A `|…|` row. */
  | 'table'
  /** A `#` line — including the `# data:` and `# expected:` spellings the renderer emits. */
  | 'comment'
  /** An `@tag` line. */
  | 'tag'
  /** Anything else: free text under a `Scenario:`, indentation, a blank line. */
  | 'plain';

export interface Span {
  kind: SpanKind;
  text: string;
}

export interface ScannedLine {
  /** 1-based, because that is what `Problem.line` counts in. */
  number: number;
  /** The line's own text, without its terminator. */
  text: string;
  spans: Span[];
}

const stepPattern = new RegExp(`^(\\s*)(${STEP_KEYWORDS.join('|')}|\\*)(\\s+)(.*)$`);
const sectionPattern = new RegExp(`^(\\s*)(${SECTION_KEYWORDS.join('|')}:)(.*)$`);

/**
 * The text as coloured lines, one entry per line, **always at least one**.
 *
 * Empty text scans to a single empty line rather than to nothing: the mirror renders one box per
 * line and an empty document still has a line 1 for the caret to sit on and for a refusal to name.
 *
 * The only state carried between lines is whether a `"""` fence is open, which is what makes a
 * step sentence inside a doc string render as content rather than as a step.
 */
export function scan(text: string): ScannedLine[] {
  const lines = text.split('\n');
  let insideDocString = false;

  return lines.map((line, index) => {
    const fence = line.trim().startsWith('"""');
    if (fence) {
      // The fence itself belongs to the block in both directions — an opening one because it
      // starts it, a closing one because dropping it out early would leave the block's last line
      // painted as prose while the author is still typing inside it.
      insideDocString = !insideDocString;
      return { number: index + 1, text: line, spans: [{ kind: 'docstring' as const, text: line }] };
    }
    if (insideDocString) {
      return { number: index + 1, text: line, spans: [{ kind: 'docstring' as const, text: line }] };
    }
    return { number: index + 1, text: line, spans: spansOf(line) };
  });
}

function spansOf(line: string): Span[] {
  const trimmed = line.trimStart();
  const indent = line.slice(0, line.length - trimmed.length);
  const lead: Span[] = indent ? [{ kind: 'plain', text: indent }] : [];

  if (trimmed.length === 0) return [{ kind: 'plain', text: line }];
  if (trimmed.startsWith('#')) return [...lead, { kind: 'comment', text: trimmed }];
  if (trimmed.startsWith('@')) return [...lead, { kind: 'tag', text: trimmed }];
  if (trimmed.startsWith('|')) return [...lead, { kind: 'table', text: trimmed }];

  const section = sectionPattern.exec(line);
  if (section) {
    const [, space, keyword, rest] = section;
    return [
      ...(space ? [{ kind: 'plain' as const, text: space }] : []),
      { kind: 'keyword', text: keyword },
      ...(rest ? [{ kind: 'sentence' as const, text: rest }] : []),
    ];
  }

  const step = stepPattern.exec(line);
  if (step) {
    const [, space, keyword, gap, sentence] = step;
    return [
      ...(space ? [{ kind: 'plain' as const, text: space }] : []),
      { kind: 'keyword', text: keyword },
      { kind: 'plain', text: gap },
      ...(sentence ? [{ kind: 'sentence' as const, text: sentence }] : []),
    ];
  }

  return [{ kind: 'plain', text: line }];
}

/**
 * The 1-based line a section keyword opens on, or null when the text has no such section.
 *
 * Used to anchor two refusals the server cannot place for us: the `library:manage` refusal on a
 * `Background:` edit, which is a client-side rule about a permission (003 D6), and the reminder
 * that the rendered `.feature` omits inherited setup. Everything the *server* refuses is anchored
 * on `Problem.line` instead and never on this.
 */
export function sectionLine(text: string, keyword: 'Feature' | 'Background' | 'Scenario'): number | null {
  const lines = text.split('\n');
  for (let index = 0; index < lines.length; index += 1) {
    if (lines[index].trimStart().startsWith(`${keyword}:`)) return index + 1;
  }
  return null;
}

/** Where a caret offset lands, as the line and column a human counts in. */
export interface CaretPosition {
  /** 1-based. */
  line: number;
  /** 0-based offset within the line. */
  column: number;
}

export function caretPositionOf(text: string, caret: number): CaretPosition {
  const before = text.slice(0, Math.max(0, Math.min(caret, text.length)));
  const lines = before.split('\n');
  return { line: lines.length, column: lines[lines.length - 1].length };
}

/**
 * The step sentence being typed under the caret — what the completion popup searches for.
 *
 * **The keyword is stripped and the search is the sentence**, because that is what the corpus
 * holds: `test_step.action` never contains `Given`, so sending the keyword with it would narrow a
 * `LIKE '%…%'` to nothing on the first word of every step and the popup would open empty exactly
 * when it is most wanted.
 *
 * Null — meaning *do not search* — on every line that is not a step being written: a comment, a
 * tag, a table row, a section header, a line inside a doc string, and the empty sentence. The last
 * is `useStepSuggestions`'s own gate restated where the fragment is produced, so a caret resting at
 * the end of `Given ` asks for nothing rather than for the project's whole corpus.
 */
export function stepFragmentAt(text: string, caret: number): string | null {
  const { line, column } = caretPositionOf(text, caret);
  const lines = scan(text);
  const current = lines[line - 1];
  if (!current) return null;
  if (current.spans.some((span) => span.kind === 'docstring')) return null;

  const upToCaret = current.text.slice(0, column);
  const step = stepPattern.exec(current.text);
  if (!step) return null;

  const [, space, keyword, gap] = step;
  const sentenceStart = space.length + keyword.length + gap.length;
  if (column < sentenceStart) return null;

  const fragment = upToCaret.slice(sentenceStart).trim();
  return fragment.length > 0 ? fragment : null;
}

/**
 * The text with the sentence under the caret replaced by a corpus entry, and where the caret then
 * belongs.
 *
 * A **replace**, not an insert: the author has typed part of the step this completes, so inserting
 * would leave that fragment in front of the finished sentence. The keyword and the indentation are
 * left exactly as typed — the corpus carries no keyword, and choosing one here would be this
 * module making a decision, which its header says it does not do.
 */
export function completeStepAt(
  text: string,
  caret: number,
  action: string,
): { text: string; caret: number } {
  const { line, column } = caretPositionOf(text, caret);
  const lines = text.split('\n');
  const current = lines[line - 1] ?? '';
  const step = stepPattern.exec(current);
  if (!step) return { text, caret };

  const [, space, keyword, gap] = step;
  const sentenceStart = space.length + keyword.length + gap.length;
  if (column < sentenceStart) return { text, caret };

  const replaced = `${current.slice(0, sentenceStart)}${action}`;
  const before = lines.slice(0, line - 1);
  const after = lines.slice(line);
  const next = [...before, replaced, ...after].join('\n');
  const caretAt = before.reduce((total, row) => total + row.length + 1, 0) + replaced.length;
  return { text: next, caret: caretAt };
}

/**
 * The `Feature:` line lifted out of the text, because the author does not type it (003 C3).
 *
 * The renderer emits `Feature: <suite name>`, and the suite is **not** one of the five fields the
 * text carries: parsing that line back into `suiteId` would let somebody rename a line and move
 * their case out of the folder it is filed in, silently, from a surface where every other line is
 * theirs to change. So the line is chrome — shown, never editable — and the body below it is what
 * the author writes and what is posted to `parse-gherkin`.
 *
 * Only the **first** such line is taken. A second one further down is left in the body exactly as
 * typed, where the server's own refusal is the thing that reports it; swallowing it here would
 * make a malformed document look well-formed right up until Save.
 */
export function splitFeatureLine(text: string): { featureName: string | null; body: string } {
  const lines = text.split('\n');
  const index = lines.findIndex((line) => line.trimStart().startsWith('Feature:'));
  if (index < 0) return { featureName: null, body: text };

  const name = lines[index].trimStart().slice('Feature:'.length).trim();
  const rest = [...lines.slice(0, index), ...lines.slice(index + 1)];
  /*
    The blank line the renderer puts under the header goes with it. Left in, every open of the
    editor would push the document down by one line — and after a render → commit → render round
    trip the author would be looking at a growing stack of blank lines they never typed.
  */
  while (rest.length > 0 && rest[0].trim() === '') rest.shift();
  return { featureName: name.length > 0 ? name : null, body: rest.join('\n') };
}
