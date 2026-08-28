import { Alert, AlertTitle, Box, Paper, Popper, Stack, Typography } from '@mui/material';
import { useEffect, useMemo, useRef, useState } from 'react';

import { useStepSuggestions } from '../../api/queries';
import { Identifier } from '../../components/Identifier';
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
 * ## The completion is a search and nothing more
 *
 * `useStepSuggestions` unchanged — same endpoint, same 250 ms debounce, same typeahead budget,
 * same `enabled` gate. `gherkinText.stepFragmentAt` decides what to search for and answers null on
 * every line that is not a step being written, so the popup cannot open over a table row or inside
 * a doc string.
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

const MONO = {
  fontFamily: 'var(--font-mono)',
  fontSize: '0.8125rem',
  lineHeight: 1.7,
  letterSpacing: 'normal',
  tabSize: 2,
} as const;

/**
 * The properties the textarea and the mirror must agree on **exactly**.
 *
 * Written once and spread into both. Every one of these changes where a character lands: a
 * different padding shifts the whole grid, a different `white-space` re-wraps it, and a different
 * `word-break` moves only the long lines — which is the version of this bug that survives a
 * demo and fails on somebody's real step.
 */
const SHARED = {
  ...MONO,
  m: 0,
  p: 1.5,
  border: '1px solid transparent',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  overflowWrap: 'anywhere',
} as const;

const COLOURS: Record<Span['kind'], string> = {
  keyword: 'var(--color-accent, #6750a4)',
  sentence: 'inherit',
  docstring: 'var(--color-status-info, #0b6bcb)',
  table: 'var(--color-status-info, #0b6bcb)',
  comment: 'text.disabled',
  tag: 'var(--color-status-warning, #9a6700)',
  plain: 'inherit',
};

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
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  const lines = useMemo(() => scan(text), [text]);

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
  const open = options.length > 0 && !!anchor;

  useEffect(() => {
    setHighlighted(0);
  }, [debounced]);

  /*
    The anchor is re-read after every render that could have moved it — a keystroke, a caret move,
    a change of options. `getBoundingClientRect` is measured off the marker inside the MIRROR, so
    the popup follows wrapped lines without this component knowing anything about wrapping.
  */
  useEffect(() => {
    setAnchor(fragment ? caretRef.current : null);
  }, [fragment, text, caret, options.length]);

  const { line: caretLine } = caretPositionOf(text, caret);
  const backgroundLine = sectionLine(text, 'Background');

  const apply = (suggestion: StepSuggestion) => {
    const next = completeStepAt(text, caret, suggestion.action);
    onTextChange(next.text);
    setCaret(next.caret);
    // The DOM caret has to be moved too: React re-renders the value, and a textarea whose value
    // changed keeps its selection at the old offset — which is mid-sentence in the text that was
    // just replaced.
    requestAnimationFrame(() => {
      areaRef.current?.setSelectionRange(next.caret, next.caret);
      areaRef.current?.focus();
    });
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
      setAnchor(null);
    }
  };

  const sync = () => setCaret(areaRef.current?.selectionStart ?? 0);

  return (
    <Stack sx={{ gap: 1 }} data-testid={`${testIdPrefix}.Document`}>
      {/*
        THE `Feature:` LINE, as chrome (003 C3).

        It renders the SUITE's name, and the suite is not one of the five fields the text carries —
        so it is shown and not typed. A line the author could retype here would be a rename that
        moved the case out of its folder, from a surface where every other line is theirs.
      */}
      <Stack
        direction="row"
        sx={{ gap: 1, alignItems: 'baseline', px: 1.5 }}
        data-testid={`${testIdPrefix}.Document.Feature`}
      >
        <Identifier variant="muted">Feature:</Identifier>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          {featureName ?? 'Unfiled'}
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.disabled' }}>
          — from the suite this case is filed in. Change it in the rail.
        </Typography>
      </Stack>

      <Box sx={{ position: 'relative' }}>
        {/*
          THE ONE MIRROR. `aria-hidden` because it is a second copy of text the textarea already
          exposes; a screen reader reading both would read the case twice.
        */}
        <Box
          component="pre"
          aria-hidden
          data-testid={`${testIdPrefix}.Document.Mirror`}
          sx={{
            ...SHARED,
            position: 'absolute',
            inset: 0,
            overflow: 'hidden',
            pointerEvents: 'none',
            borderRadius: 1,
            bgcolor: 'background.default',
            color: 'text.primary',
          }}
        >
          {lines.map((row) => (
            <Box
              key={row.number}
              component="span"
              sx={{
                display: 'block',
                minHeight: '1.7em',
                // The refused line, marked where the author is looking rather than only in a
                // banner they may have scrolled past.
                bgcolor: refusal?.line === row.number ? 'var(--color-status-error-bg, #fdecea)' : undefined,
              }}
            >
              {renderRow(row.spans, row.number === caretLine ? caretPositionOf(text, caret).column : null, caretRef)}
            </Box>
          ))}
        </Box>

        <Box
          component="textarea"
          ref={areaRef}
          value={text}
          readOnly={readOnly}
          spellCheck={false}
          aria-label="The case as Gherkin"
          data-testid={`${testIdPrefix}.Document.Text`}
          placeholder={'Given …\nWhen …\nThen …'}
          rows={Math.max(12, lines.length + 2)}
          onChange={(event: React.ChangeEvent<HTMLTextAreaElement>) => {
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
            setAnchor(null);
            onCommit();
          }}
          sx={{
            ...SHARED,
            position: 'relative',
            display: 'block',
            width: '100%',
            resize: 'vertical',
            borderRadius: 1,
            borderColor: 'divider',
            background: 'transparent',
            // Transparent text over the mirror, and a real caret colour so the caret stays visible.
            color: 'transparent',
            caretColor: 'var(--color-text-primary, currentColor)',
            outline: 'none',
            '&:focus-visible': { borderColor: 'primary.main' },
            '&::placeholder': { color: 'text.disabled' },
          }}
        />

        <Popper open={open} anchorEl={anchor} placement="bottom-start" style={{ zIndex: 1300 }}>
          <Paper elevation={4} sx={{ maxWidth: 520, py: 0.5 }} data-testid={`${testIdPrefix}.Document.Suggestions`}>
            {options.map((option, index) => (
              <Box
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
                sx={{
                  px: 1.5,
                  py: 0.75,
                  cursor: 'pointer',
                  bgcolor: index === highlighted ? 'action.hover' : undefined,
                }}
              >
                <Stack direction="row" sx={{ gap: 1, alignItems: 'baseline' }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {option.action}
                  </Typography>
                  {/* The ranking signal, shown rather than only sorted by — "41 cases" is what
                      tells an author they are writing the project's twenty-first wording of a
                      step it already has. */}
                  <Identifier variant="muted">
                    {option.useCount} case{option.useCount === 1 ? '' : 's'}
                  </Identifier>
                </Stack>
              </Box>
            ))}
          </Paper>
        </Popper>
      </Box>

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
        <Typography
          variant="caption"
          sx={{ color: 'text.secondary', px: 1.5 }}
          data-testid={`${testIdPrefix}.Document.BackgroundNotice`}
        >
          <strong>Background:</strong> is this case&rsquo;s own setup. The rendered{' '}
          <Identifier variant="muted">.feature</Identifier> omits setup inherited from the suite
          {inheritedPreconditionCount > 0
            ? ` — ${inheritedPreconditionCount} inherited ${
                inheritedPreconditionCount === 1 ? 'precondition is' : 'preconditions are'
              } listed in the rail.`
            : '.'}
        </Typography>
      )}

      {refusal && (
        <Alert severity="error" role="alert" data-testid={`${testIdPrefix}.Document.Refusal`}>
          <AlertTitle>
            {refusal.line !== null ? `Line ${refusal.line}` : 'This cannot be saved yet'}
          </AlertTitle>
          {/* The server's sentence, quoted. Composing words around "Line 4, column 3: expected a
              step" is how the author gets told two different things about one typo (§149). */}
          {refusal.message}
        </Alert>
      )}
    </Stack>
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
  const marker = (
    <Box key="caret" component="span" ref={caretRef} sx={{ display: 'inline-block', width: 0 }} />
  );

  const out: React.ReactNode[] = [];
  let consumed = 0;
  let placed = caretColumn === null;

  spans.forEach((span, index) => {
    const start = consumed;
    consumed += span.text.length;

    if (!placed && caretColumn !== null && caretColumn <= consumed) {
      const at = caretColumn - start;
      out.push(
        <Box key={`${index}a`} component="span" sx={{ color: COLOURS[span.kind] }}>
          {span.text.slice(0, at)}
        </Box>,
        marker,
        <Box key={`${index}b`} component="span" sx={{ color: COLOURS[span.kind] }}>
          {span.text.slice(at)}
        </Box>,
      );
      placed = true;
      return;
    }

    out.push(
      <Box key={index} component="span" sx={{ color: COLOURS[span.kind] }}>
        {span.text}
      </Box>,
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
