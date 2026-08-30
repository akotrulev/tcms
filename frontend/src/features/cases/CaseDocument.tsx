import { useEffect, useMemo, useRef, useState } from 'react';

import { useStepSuggestions } from '../../api/queries';
import { Banner, ProcedureLine } from '../../components/kit';
import { cn } from '../../lib/cn';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import type { StepSuggestion } from '../../api/types';
import {
  caretPositionOf,
  completeStepAt,
  scan,
  sectionLine,
  stepFragmentAt,
  type Span,
} from './gherkinText';

/**
 * The Write surface — the case *as text*, which under 003 D1 is the only case editor there is.
 *
 * ## The text is the source of truth; steps are derived
 *
 * This component holds no parsed state and never writes one. It owns a `<textarea>` whose value is
 * the raw text the author typed and a mirror that colours it, and it asks its caller to **commit**
 * on blur — where "commit" means the caller posts the text to `parse-gherkin` and merges what the
 * server read into the form. Re-rendering the textarea from parsed state per keystroke is what
 * jumps the caret, and it is also what would make an unparseable half-typed line disappear as it
 * was being written.
 *
 * The consequence worth stating: **what this component shows is never rewritten by a refusal.**
 * The caller hands a refusal back through `refusal` and it is rendered *beside* the line it names.
 * Nothing here reformats, replaces or rolls back a character the author typed (003 C4).
 *
 * ## One mirror, not two
 *
 * A `<pre>` behind a transparent `<textarea>`, sharing font, size, line height, padding, border
 * and wrapping — and **both** the syntax colour and the caret's pixel position are read off that
 * one element. The row editor got its popup position free from MUI's `Autocomplete`, which anchors
 * to a real input; a textarea has nothing to anchor to, so the caret has to be measured, and the
 * technique for measuring it is the same hidden mirror the colouring already needs. Two mirrors
 * drift the moment one of them gains a padding value the other does not, and the symptom is a
 * popup one line off the caret — which reads as the completion being for the previous step.
 *
 * The caret marker is an empty `<span>` rendered **into the coloured line itself**, split at the
 * caret column. That is what makes it one mirror rather than one mirror and one measurer.
 *
 * ## 007 T7 Step 5 — the document reads as a document, and what that costs the mirror
 *
 * `SHARED` is 15px sans at 1.75 now, per the redesign render. The whole surface was mono at 13px,
 * which is why it read as a config file rather than as a procedure.
 *
 * **The keyword changes colour and nothing else.** The render draws it in mono at 14px inside a
 * 15px sans line, and that is the one thing this surface may not copy: the mirror and the textarea
 * must agree on font, size, weight, padding and wrapping to the pixel, and a mono keyword would
 * shift every character after it on its line — putting the completion popup, which is anchored
 * inside the mirror, somewhere the caret is not. So the mono half of rule 5 lives wherever nothing
 * is typed: the **gutter number**, which is absolutely positioned out of the text flow, the
 * `Feature:` chrome, and the blank state's scaffold. A deliberate deviation from the render,
 * recorded in 007 T7 rather than left to be rediscovered as a caret bug.
 *
 * ## The completion is a search and nothing more
 *
 * `useStepSuggestions` unchanged — same endpoint, same 250 ms debounce, same typeahead budget,
 * same `enabled` gate. `gherkinText.stepFragmentAt` decides what to search for and answers null on
 * every line that is not a step being written, so the popup cannot open over a table row or inside
 * a doc string.
 *
 * ~~The popup is an MUI `Popper` anchored to the caret marker.~~ → **superseded by 007 T7.** It is
 * an absolutely-positioned element inside the mirror's own positioned box, placed from the
 * marker's `offsetTop`/`offsetLeft`. The marker's offset parent *is* that box, so the position is
 * a read rather than a measurement, and it takes the last portal out of this file.
 */

/** A refusal to render against the text — the server's, or one of the two the client owns. */
export interface DocumentRefusal {
  /** The sentence to print, verbatim. Never composed around, per §149. */
  message: string;
  /**
   * The 1-based line it is about, or null when it names none.
   *
   * Read from `Problem.line` (005 S1) for a server refusal and from `gherkinText.sectionLine` for
   * the `library:manage` one, which is the client's own rule about a permission. **Never regexed
   * out of `detail`** — the contract adds this member precisely so that it is not.
   */
  line: number | null;
  /** What the author would have to hold, when the refusal is about a permission. */
  permission?: string | null;
}

/**
 * The properties the textarea and the mirror must agree on **exactly**.
 *
 * Written once and spread into both. Every one of these changes where a character lands: a
 * different padding shifts the whole grid, a different `white-space` re-wraps it, and a different
 * `word-break` moves only the long lines — which is the version of this bug that survives a
 * demo and fails on somebody's real step.
 *
 * The 40px left padding is the render's 26px gutter plus its 14px gap. The line numbers are drawn
 * into it from `position: absolute`, so they cost the shared text flow nothing.
 */
const SHARED = {
  margin: 0,
  padding: '12px 12px 12px 40px',
  border: '1px solid transparent',
  fontFamily: 'var(--font-sans)',
  fontSize: '15px',
  lineHeight: 1.75,
  letterSpacing: 'normal',
  tabSize: 2,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  overflowWrap: 'anywhere',
} as const;

/** Rule 5: `Given`/`When`/`Then` are the same three hues the runner paints results in. */
function keywordColour(text: string): string {
  const word = text.trim().replace(/:$/, '');
  if (word === 'Given') return 'var(--color-given)';
  if (word === 'When') return 'var(--color-when)';
  if (word === 'Then') return 'var(--color-then)';
  // `Feature:`, `Scenario:`, `Background:` — structure the author writes but the case does not own.
  if (text.trim().endsWith(':')) return 'var(--color-steel)';
  return 'var(--color-ink-3)';
}

/**
 * Everything that is not a keyword.
 *
 * Quiet, and quiet in one direction: the render draws a tag in mauve (`--tagc`), a hue this app
 * has no token for and which rule 5 forbids substituting a status colour for. Tags and step data
 * are both subordinate to the sentence, so both stay `ink-2` until that token exists.
 */
const COLOURS: Record<Span['kind'], string> = {
  keyword: 'inherit',
  sentence: 'inherit',
  docstring: 'var(--color-ink-2)',
  table: 'var(--color-ink-2)',
  comment: 'var(--color-ink-4)',
  tag: 'var(--color-ink-2)',
  plain: 'inherit',
};

const spanColour = (span: Span) =>
  span.kind === 'keyword' ? keywordColour(span.text) : COLOURS[span.kind];

/**
 * What the author sees before there is anything to see — 007 §0 item 3, render frame B.
 *
 * It replaces `placeholder="Given …\nWhen …\nThen …"`, and the difference is the item: a
 * placeholder names the three keywords, this says what each one is *for*, on the line it will be
 * typed on. It renders **inside the mirror**, so nothing moves when the first character arrives —
 * the ghost is overwritten in place rather than unmounted above the caret.
 */
const SCAFFOLD = [
  { keyword: 'given', hint: 'the state the test starts from' },
  { keyword: 'when', hint: 'the action under test' },
  { keyword: 'then', hint: 'what must be true afterwards' },
] as const;

function BlankScaffold({ testId }: { testId: string }) {
  return (
    <div data-testid={testId} className="-ml-10 text-ink-4">
      <span className="block max-w-[36ch] text-[24px] leading-[1.25] font-[620] tracking-[-.02em]">
        Name the case on the first line…
      </span>
      <span className="mt-1 block text-[13px]">One sentence stating what is verified</span>
      <span className="my-5 block h-px max-w-[74ch] bg-line" />
      {SCAFFOLD.map((row, index) => (
        <ProcedureLine key={row.keyword} line={index + 1} keyword={row.keyword} className="opacity-55">
          {row.hint}
        </ProcedureLine>
      ))}
    </div>
  );
}

export function CaseDocument({
  projectId,
  text,
  onTextChange,
  onCommit,
  refusal,
  featureName,
  inheritedPreconditionCount,
  readOnly = false,
  testIdPrefix,
}: {
  projectId: string;
  text: string;
  onTextChange: (next: string) => void;
  /**
   * Blur, and only blur.
   *
   * The caller also commits on Save — from its own handler, because a Save is a decision about the
   * whole form rather than an event on this control. Committing per keystroke would post the
   * project's parser a request per character of every step.
   */
  onCommit: () => void;
  refusal: DocumentRefusal | null;
  /** The suite name the server resolved, rendered as chrome. Null on a case filed nowhere. */
  featureName: string | null;
  /** How many preconditions this case inherits from its folder — see the notice below. */
  inheritedPreconditionCount: number;
  readOnly?: boolean;
  testIdPrefix: string;
}) {
  const areaRef = useRef<HTMLTextAreaElement>(null);
  const caretRef = useRef<HTMLSpanElement>(null);
  const [caret, setCaret] = useState(0);
  const [focused, setFocused] = useState(false);
  const [highlighted, setHighlighted] = useState(0);
  const [popup, setPopup] = useState<{ top: number; left: number } | null>(null);

  const lines = useMemo(() => scan(text), [text]);
  const blank = text.length === 0 && !readOnly;

  const fragment = focused && !readOnly ? stepFragmentAt(text, caret) : null;
  /*
    250 ms, the row editor's number, kept: this popup opens under the caret and a third of a second
    there reads as the field being slow. The debounce is over the FRAGMENT rather than over the
    text, so moving the caret along a line the author has stopped typing on does not re-fire.
  */
  const debounced = useDebouncedValue(fragment ?? '', 250);
  const suggestions = useStepSuggestions(projectId, debounced);
  /*
    A failed or timed-out corpus read renders as no suggestions and nothing else — the row editor's
    rule, and the reason is unchanged: this is a text box that happens to complete, the author can
    write the step by hand, and an error banner over the editor because an optional enrichment
    failed is the critical-vs-optional rule broken in the direction that costs most.
  */
  const options = fragment && debounced === fragment ? (suggestions.data ?? []) : [];
  const open = options.length > 0 && popup !== null;

  useEffect(() => {
    setHighlighted(0);
  }, [debounced]);

  /*
    The position is re-read after every render that could have moved it — a keystroke, a caret move,
    a change of options. It is read off the marker inside the MIRROR, whose offset parent is the
    mirror's own box, so the popup follows wrapped lines without this component knowing anything
    about wrapping.
  */
  useEffect(() => {
    const marker = fragment ? caretRef.current : null;
    setPopup(
      marker ? { top: marker.offsetTop + marker.offsetHeight, left: marker.offsetLeft } : null,
    );
  }, [fragment, text, caret, options.length]);

  const { line: caretLine } = caretPositionOf(text, caret);
  const backgroundLine = sectionLine(text, 'Background');

  /*
    React re-renders the value, and a textarea whose value changed keeps its selection at the old
    offset — which is mid-sentence in the text that was just replaced. So the DOM caret is moved
    too, after the paint that carries the new value.
  */
  const putCaret = (position: number) => {
    requestAnimationFrame(() => {
      areaRef.current?.setSelectionRange(position, position);
      areaRef.current?.focus();
    });
  };

  const apply = (suggestion: StepSuggestion) => {
    const next = completeStepAt(text, caret, suggestion.action);
    onTextChange(next.text);
    setCaret(next.caret);
    putCaret(next.caret);
  };

  /**
   * The render's `+ tag` chip, and the only thing on the blank state that writes into the document
   * without the author typing.
   *
   * It writes an `@` and leaves the caret after it rather than offering a vocabulary: `case_tag` is
   * free text on this contract, the completion under the caret is the step corpus and not a tag
   * list, and a picker here would be the namespaced-tag syntax 003's open problem 2 defers.
   */
  const addTag = () => {
    onTextChange(text.length === 0 ? '@' : `@\n${text}`);
    setCaret(1);
    putCaret(1);
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (!open) return;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setHighlighted((current) => (current + 1) % options.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setHighlighted((current) => (current - 1 + options.length) % options.length);
    } else if (event.key === 'Enter') {
      // Enter is a newline in a textarea and stays one whenever the popup is closed. Only an OPEN
      // popup takes it, which is the same bargain every completion in this app strikes.
      event.preventDefault();
      apply(options[highlighted]);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      setPopup(null);
    }
  };

  const sync = () => setCaret(areaRef.current?.selectionStart ?? 0);

  return (
    <div className="flex flex-col gap-2" data-testid={`${testIdPrefix}.Document`}>
      {/*
        THE `Feature:` LINE, as chrome (003 C3).

        It renders the SUITE's name, and the suite is not one of the five fields the text carries —
        so it is shown and not typed. A line the author could retype here would be a rename that
        moved the case out of its folder, from a surface where every other line is theirs.
      */}
      <div
        className="flex flex-wrap items-baseline gap-2 text-[13px]"
        data-testid={`${testIdPrefix}.Document.Feature`}
      >
        <span className="font-mono text-[11px] text-ink-4">Feature:</span>
        <span className="text-ink-2">{featureName ?? 'Unfiled'}</span>
        <span className="text-[12px] text-ink-4">
          — from the suite this case is filed in. Change it in the rail.
        </span>
      </div>

      {blank && (
        <div>
          <button
            type="button"
            onClick={addTag}
            data-testid={`${testIdPrefix}.Document.AddTag`}
            className={cn(
              'rounded-[5px] border border-dashed border-line-strong px-[7px] py-0.5',
              'font-mono text-[12px] text-ink-4 hover:border-line-strong hover:text-ink-2',
              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
            )}
          >
            + tag
          </button>
        </div>
      )}

      <div className="relative">
        {/*
          THE ONE MIRROR. `aria-hidden` because it is a second copy of text the textarea already
          exposes; a screen reader reading both would read the case twice.
        */}
        <pre
          aria-hidden
          data-testid={`${testIdPrefix}.Document.Mirror`}
          style={SHARED}
          className="pointer-events-none absolute inset-0 overflow-hidden rounded-md bg-paper text-ink"
        >
          {blank ? (
            <BlankScaffold testId={`${testIdPrefix}.Document.Scaffold`} />
          ) : (
            lines.map((row) => (
              <span
                key={row.number}
                // The refused line, marked where the author is looking rather than only in a
                // banner they may have scrolled past.
                style={{
                  backgroundColor:
                    refusal?.line === row.number
                      ? 'color-mix(in oklab, var(--color-failed) 14%, transparent)'
                      : undefined,
                }}
                className="relative block min-h-[1.75em]"
              >
                <span className="absolute -left-10 w-[26px] pt-[3px] text-right font-mono text-[11px] tabular-nums text-ink-4">
                  {row.number}
                </span>
                {renderRow(
                  row.spans,
                  row.number === caretLine ? caretPositionOf(text, caret).column : null,
                  caretRef,
                )}
              </span>
            ))
          )}
        </pre>

        <textarea
          ref={areaRef}
          value={text}
          readOnly={readOnly}
          spellCheck={false}
          aria-label="The case as Gherkin"
          data-testid={`${testIdPrefix}.Document.Text`}
          rows={Math.max(12, lines.length + 2)}
          onChange={(event) => {
            onTextChange(event.target.value);
            setCaret(event.target.selectionStart);
          }}
          onKeyUp={sync}
          onClick={sync}
          onSelect={sync}
          onKeyDown={onKeyDown}
          onFocus={() => setFocused(true)}
          onBlur={() => {
            setFocused(false);
            setPopup(null);
            onCommit();
          }}
          style={{
            ...SHARED,
            borderColor: 'rgb(255 255 255/8%)',
            background: 'transparent',
            // Transparent text over the mirror, and a real caret colour so the caret stays visible.
            color: 'transparent',
            caretColor: 'var(--color-ink)',
          }}
          className="relative block w-full resize-y rounded-md outline-none focus-visible:border-[color:var(--color-steel)]"
        />

        {open && popup && (
          <div
            data-testid={`${testIdPrefix}.Document.Suggestions`}
            style={{ top: popup.top, left: popup.left }}
            className="absolute z-30 w-[min(520px,100%)] overflow-hidden rounded-md border border-line-strong bg-panel py-1 shadow-e3"
          >
            {options.map((option, index) => (
              <div
                key={option.action}
                role="option"
                aria-selected={index === highlighted}
                data-testid={`${testIdPrefix}.Document.Suggestion`}
                // `onMouseDown`, not `onClick`: a click would blur the textarea first, which
                // commits the document and closes this popup before the pick is read.
                onMouseDown={(event: React.MouseEvent) => {
                  event.preventDefault();
                  apply(option);
                }}
                className={cn(
                  'flex cursor-pointer items-baseline gap-2.5 px-3 py-1.5 text-[13.5px]',
                  index === highlighted && 'bg-wash-2',
                )}
              >
                <span className="font-medium text-ink">{option.action}</span>
                {/* The ranking signal, shown rather than only sorted by — "41 cases" is what
                    tells an author they are writing the project's twenty-first wording of a
                    step it already has. */}
                <span className="ml-auto font-mono text-[11px] text-ink-4">
                  {option.useCount} case{option.useCount === 1 ? '' : 's'}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/*
        THE COST OF DECISION (a), ON THE SCREEN AND NOT ONLY IN THE PLAN (003 D6).

        The `Background:` block is this case's OWN preconditions. Its folder's are not in it and
        cannot be — the text has no spelling that distinguishes an inherited line from an own one,
        so a deletion would be ambiguous between detaching and declining. The cost is that the
        rendered `.feature` is no longer exactly what a tester runs, which is what §255 built the
        renderer to be, and the honest thing is to say so where the block is rather than in a
        document nobody reading this screen has open.
      */}
      {(backgroundLine !== null || inheritedPreconditionCount > 0) && (
        <p
          className="m-0 text-[12px] leading-[1.55] text-ink-3"
          data-testid={`${testIdPrefix}.Document.BackgroundNotice`}
        >
          <strong className="font-medium text-ink-2">Background:</strong> is this case&rsquo;s own
          setup. The rendered <span className="font-mono text-[11px] text-ink-4">.feature</span>{' '}
          omits setup inherited from the suite
          {inheritedPreconditionCount > 0
            ? ` — ${inheritedPreconditionCount} inherited ${
                inheritedPreconditionCount === 1 ? 'precondition is' : 'preconditions are'
              } listed in the rail.`
            : '.'}
        </p>
      )}

      {refusal && (
        <Banner tone="bad" data-testid={`${testIdPrefix}.Document.Refusal`}>
          <span role="alert">
            <strong className="block font-medium text-ink">
              {refusal.line !== null ? `Line ${refusal.line}` : 'This cannot be saved yet'}
            </strong>
            {/* The server's sentence, quoted. Composing words around "Line 4, column 3: expected a
                step" is how the author gets told two different things about one typo (§149). */}
            {refusal.message}
          </span>
        </Banner>
      )}
    </div>
  );
}

/**
 * One line's spans, with the caret marker spliced in at its column when the caret is on this line.
 *
 * The marker is what the popup anchors to, and it is rendered **inside the coloured line** rather
 * than into a mirror of its own — see the component header on why two mirrors drift.
 */
function renderRow(
  spans: Span[],
  caretColumn: number | null,
  caretRef: React.RefObject<HTMLSpanElement | null>,
) {
  const marker = <span key="caret" ref={caretRef} className="inline-block w-0" />;

  const out: React.ReactNode[] = [];
  let consumed = 0;
  let placed = caretColumn === null;

  spans.forEach((span, index) => {
    const start = consumed;
    consumed += span.text.length;

    if (!placed && caretColumn !== null && caretColumn <= consumed) {
      const at = caretColumn - start;
      out.push(
        <span key={`${index}a`} style={{ color: spanColour(span) }}>
          {span.text.slice(0, at)}
        </span>,
        marker,
        <span key={`${index}b`} style={{ color: spanColour(span) }}>
          {span.text.slice(at)}
        </span>,
      );
      placed = true;
      return;
    }

    out.push(
      <span key={index} style={{ color: spanColour(span) }}>
        {span.text}
      </span>,
    );
  });

  // A caret past the end of the last span — the ordinary case, since that is where typing leaves
  // it — and an empty line, which has one zero-length span and therefore no span to split.
  if (!placed) out.push(marker);
  // `\n` keeps an empty line's box open in the mirror; without it the two grids fall out of step
  // by one line for every blank line in the document.
  if (out.length === 0) out.push('\n');
  return out;
}
