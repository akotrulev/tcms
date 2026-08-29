import { useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import { useCases, useLinkBlock, useSuites } from '../../api/queries';
import type { BlockLink, DocBlock, LinkRelationship, Suite, TestCaseSummary } from '../../api/types';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { cn } from '../../lib/cn';
import { describeError } from '../../lib/describeError';
import { docBlockTypeLabel, linkRelationshipHelp, linkRelationshipLabels, linkSourceTypeLabels } from '../../lib/labels';

type SourceType = BlockLink['sourceType'];

/**
 * `LinkDialog` and not `Link`: the list this dialog writes into is `Docs.Block.Links`, and two
 * prefixes differing by one character is a locator somebody reads wrong once and a test asserts
 * against the wrong element for ever.
 */
const TestIdPrefix = 'Docs.Block.LinkDialog';

const FIELD =
  'w-full rounded-[6px] border border-white/8 bg-field px-3 py-1.5 text-left text-[13px] text-ink ' +
  'placeholder:text-ink-4 focus-visible:outline-2 focus-visible:outline-offset-2 ' +
  'focus-visible:outline-[color:var(--color-given)]';

const POPOVER =
  'absolute z-50 mt-1 max-h-64 w-full overflow-auto rounded-md border border-white/8 bg-panel p-1 ' +
  'shadow-[0_16px_32px_-8px_rgb(0_0_0/60%)]';

const OPTION =
  'block w-full cursor-pointer rounded-[4px] px-2.5 py-1.5 text-left text-[13px] text-ink-2 ' +
  'hover:bg-white/5 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-[-2px] ' +
  'focus-visible:outline-[color:var(--color-given)]';

/**
 * Claiming that a case — or a whole suite — verifies one block of a specification (§172; plan §6i).
 *
 * `useLinkBlock` has existed since §97 with **no caller anywhere in `src/`**, which is what §169
 * recorded and this dialog closes: the endpoint, the read model, the coverage marker and the case
 * end were all built, and the one screen that could create a `doc_link` from the documentation side
 * was never written. Every block on every page therefore read "Not tested" with no way to say
 * otherwise from here — the marker plan §6i built to make people maintain links, pointing at an
 * affordance that did not exist.
 *
 * ## The source kind is chosen, never inferred from which picker was touched
 *
 * Same rule as `ExternalLinkDialog`, and the same reason: `sourceType` is required on the wire and
 * the server will not guess it. A form that decided from whichever control last had a value would
 * send `TEST_CASE` with a suite's id the moment somebody picked a suite after a case — an id that
 * is a real row of the wrong table, so the server's project check passes and the link points at
 * something nobody chose. The toggle is the first control and it swaps the picker below it.
 *
 * ## Two pickers, and only one of them is a search
 *
 * A project has thousands of cases and a handful of suites, so the case arm is `BindTestDialog`'s
 * server-side type-ahead (open on the first twenty, filter on the server, never re-filter the
 * answer client-side) and the suite arm is a plain list over the whole set. Both queries stay
 * mounted across the toggle: they are two cheap list reads, and gating one on the toggle would make
 * switching kinds show a spinner over data that was already in the cache.
 *
 * ## Both pickers are disclosures this file owns, not `ui/select`
 *
 * ~~MUI's `Autocomplete` and a `TextField select`.~~ → **superseded by 007 T14.** What replaces
 * them is not the kit's Radix `Select` either: that primitive calls `hasPointerCapture` on its
 * trigger, jsdom implements neither it nor a polyfill, and `src/test/setup.ts` is deliberately not
 * a place where the application is bent to suit the harness — `ReviewPanel` records the same call
 * for the same reason. So each picker is a real `role="combobox"` over a real `role="listbox"`:
 * native focus, native keys, and nothing to stub. The case arm's combobox is the **input itself**,
 * because typing into it is what narrows the list.
 *
 * ## Linking twice is not an error, so this dialog does not treat it as one
 *
 * The endpoint answers **200 with the existing link** when the same source is linked to the same
 * block again — re-asserting a recorded fact is not a conflict, and a duplicate row would double
 * every count drawn off `doc_link`. So there is nothing to warn about and nothing to disable: the
 * dialog closes and the block shows the one link it has either way.
 *
 * ## `intendedCase` — arriving here from a case (A8)
 *
 * A block link is creatable **only** from this side, so somebody reading a case who wants to claim
 * a requirement paragraph has to come here, and until §230 they arrived with nothing carried over:
 * the case they were reading had to be found again in a type-ahead over thousands. The prop is the
 * case `DocsPage` read out of the `linkCase` search param — pre-selected, and pre-typed into the
 * search box, because the input is controlled and a `value` with an empty `inputValue` renders as a
 * selection nobody can see.
 *
 * It is **merged into the options** when the server's answer for that query does not contain it, so
 * the carried case is choosable from the list it is already the value of.
 */
export function LinkBlockDialog({
  open,
  onClose,
  projectId,
  pageId,
  block,
  intendedCase = null,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  pageId: string;
  block: DocBlock;
  /** The case this reader came from, or null when they came from the page itself. */
  intendedCase?: TestCaseSummary | null;
}) {
  const write = useLinkBlock(projectId, pageId);
  // The bound `reset`, not the mutation object: the object is a fresh literal on every render,
  // so depending on it would clear the refusal the dialog is displaying.
  const { reset } = write;
  const [sourceType, setSourceType] = useState<SourceType>('TEST_CASE');
  const [relationship, setRelationship] = useState<LinkRelationship>('VERIFIES');
  const [query, setQuery] = useState(intendedCase ? caseLabel(intendedCase) : '');
  const [pickedCase, setPickedCase] = useState<TestCaseSummary | null>(intendedCase);
  const [pickedSuite, setPickedSuite] = useState<string>('');
  const [caseListOpen, setCaseListOpen] = useState(false);
  const [suiteListOpen, setSuiteListOpen] = useState(false);

  const cases = useCases(projectId, { q: query.trim() || undefined, size: 20 }, 'key,asc');
  const suites = useSuites(projectId);

  const served = cases.data?.items ?? [];
  const options =
    pickedCase && !served.some((row) => row.id === pickedCase.id) ? [pickedCase, ...served] : served;

  // Reopening after a refusal must not show the previous one — `QuarantineDialog`'s rule, and
  // `ExternalLinkDialog` two features over makes the same call.
  useEffect(() => {
    if (open) {
      reset();
      setSourceType('TEST_CASE');
      setRelationship('VERIFIES');
      // Back to the *intent*, not to empty: reopening on another block while a case is being
      // carried across the page must still be carrying it.
      setQuery(intendedCase ? caseLabel(intendedCase) : '');
      setPickedCase(intendedCase);
      setPickedSuite('');
      setCaseListOpen(false);
      setSuiteListOpen(false);
    }
    /*
      `intendedCase?.id` is deliberately narrower than `intendedCase`: a parent that rebuilds the
      object each render would re-run this effect and discard the picker selection the reader just
      made, and the id is what actually decides whether a different case is being carried in.
    */
    // oxlint-disable-next-line react/exhaustive-deps
  }, [open, block.id, intendedCase?.id, reset]);

  const fieldError = (field: string) =>
    write.error instanceof ApiError ? write.error.fieldErrors?.[field]?.join(' ') : undefined;
  const named = ['sourceType', 'sourceId', 'relationship'];
  const unnamedFailure = write.isError && !named.some((field) => fieldError(field) !== undefined);

  const sourceId = sourceType === 'TEST_CASE' ? (pickedCase?.id ?? '') : pickedSuite;
  const chosenSuite = (suites.data ?? []).find((suite: Suite) => suite.id === pickedSuite);

  const submit = () => {
    if (!sourceId) return;
    write.mutate({ blockId: block.id, sourceType, sourceId, relationship }, { onSuccess: onClose });
  };

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-xl" data-testid={TestIdPrefix}>
        <DialogHeader>
          <DialogTitle>Link something to this block</DialogTitle>
        </DialogHeader>

        <div className="mt-4 flex flex-col gap-4">
          {/*
            The block restated, because the page has many and the dialog covers the one that was
            clicked. Its type is named as well as its words: a caption and a paragraph read the same
            in one line of prose, and on a `table` the text IS the caption — which is what the
            coverage matrix reads as the statement.
          */}
          <p className="m-0 text-[13px] text-ink-3" data-testid={`${TestIdPrefix}.Subject`}>
            {docBlockTypeLabel[block.blockType]} — {excerpt(block.text)}
          </p>

          <div
            role="group"
            aria-label="What is being linked"
            className="inline-flex w-fit gap-0.5 rounded-lg bg-white/4 p-0.5"
          >
            {(Object.keys(linkSourceTypeLabels) as SourceType[]).map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={sourceType === value}
                onClick={() => setSourceType(value)}
                data-testid={`${TestIdPrefix}.Kind.${value}`}
                className={cn(
                  'cursor-pointer rounded-md border-0 px-[11px] py-1 text-xs font-medium',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
                  sourceType === value
                    ? 'bg-panel text-ink shadow-[0_1px_2px_rgb(0_0_0/40%)]'
                    : 'bg-transparent text-ink-3 hover:text-ink-2',
                )}
              >
                {linkSourceTypeLabels[value]}
              </button>
            ))}
          </div>

          {sourceType === 'TEST_CASE' ? (
            <Field
              label="Case"
              htmlFor="link-block-case"
              error={fieldError('sourceId')}
              help="By key or title."
            >
              <div className="relative">
                <input
                  id="link-block-case"
                  type="text"
                  role="combobox"
                  aria-expanded={caseListOpen}
                  aria-controls={caseListOpen ? "link-block-case-options" : undefined}
                  aria-autocomplete="list"
                  value={query}
                  onChange={(event) => {
                    setQuery(event.target.value);
                    setCaseListOpen(true);
                  }}
                  onClick={() => setCaseListOpen(true)}
                  data-testid={`${TestIdPrefix}.Case`}
                  className={FIELD}
                />
                {caseListOpen && (
                  <>
                    <div aria-hidden className="fixed inset-0 z-40" onClick={() => setCaseListOpen(false)} />
                    <div
                      id="link-block-case-options"
                      role="listbox"
                      onKeyDown={(event) => {
                        if (event.key === 'Escape') setCaseListOpen(false);
                      }}
                      className={POPOVER}
                    >
                      {options.length === 0 ? (
                        <p className="m-0 px-2.5 py-1.5 text-[12.5px] text-ink-4">
                          {cases.isPending ? 'Searching…' : 'No case matches.'}
                        </p>
                      ) : (
                        // The server has already filtered. Filtering again client-side would hide
                        // rows that matched on a field the label does not show — `BindTestDialog`'s
                        // note.
                        options.map((row) => (
                          <button
                            key={row.id}
                            type="button"
                            role="option"
                            aria-selected={row.id === pickedCase?.id}
                            onClick={() => {
                              setPickedCase(row);
                              setQuery(caseLabel(row));
                              setCaseListOpen(false);
                            }}
                            className={OPTION}
                          >
                            {caseLabel(row)}
                          </button>
                        ))
                      )}
                    </div>
                  </>
                )}
              </div>
            </Field>
          ) : (
            <Field
              label="Suite"
              htmlFor="link-block-suite"
              error={fieldError('sourceId')}
              help="Every case the suite resolves to counts, including ones a tag rule pulls in later."
            >
              <div className="relative">
                <button
                  type="button"
                  id="link-block-suite"
                  role="combobox"
                  aria-expanded={suiteListOpen}
                  aria-haspopup="listbox"
                  aria-controls={suiteListOpen ? "link-block-suite-options" : undefined}
                  onClick={() => setSuiteListOpen((current) => !current)}
                  data-testid={`${TestIdPrefix}.Suite`}
                  className={FIELD}
                >
                  {chosenSuite ? (
                    `${chosenSuite.key} — ${chosenSuite.name}`
                  ) : (
                    <span className="text-ink-4">No suite chosen</span>
                  )}
                </button>
                {suiteListOpen && (
                  <>
                    <div aria-hidden className="fixed inset-0 z-40" onClick={() => setSuiteListOpen(false)} />
                    <div
                      id="link-block-suite-options"
                      role="listbox"
                      onKeyDown={(event) => {
                        if (event.key === 'Escape') setSuiteListOpen(false);
                      }}
                      className={POPOVER}
                    >
                      {(suites.data ?? []).map((suite: Suite) => (
                        <button
                          key={suite.id}
                          type="button"
                          role="option"
                          aria-selected={suite.id === pickedSuite}
                          onClick={() => {
                            setPickedSuite(suite.id);
                            setSuiteListOpen(false);
                          }}
                          className={OPTION}
                        >
                          {suite.key} — {suite.name}
                        </button>
                      ))}
                    </div>
                  </>
                )}
              </div>
            </Field>
          )}

          {/*
            Offered rather than defaulted, though the server defaults it — `ExternalLinkDialog`'s
            argument, and it bites harder here: this is the screen that fills the coverage line, so
            a form that always sent VERIFIES would make every link on every specification read as
            proof, including the ones somebody meant as context.
          */}
          <Field
            label="What it does with this block"
            htmlFor="link-block-relationship"
            error={fieldError('relationship')}
            help={linkRelationshipHelp[relationship]}
          >
            <NativeSelect
              id="link-block-relationship"
              value={relationship}
              onChange={(event) => setRelationship(event.target.value as LinkRelationship)}
              data-testid={`${TestIdPrefix}.Relationship`}
            >
              {(Object.keys(linkRelationshipLabels) as LinkRelationship[]).map((value) => (
                <option key={value} value={value}>
                  {linkRelationshipLabels[value]}
                </option>
              ))}
            </NativeSelect>
          </Field>

          {unnamedFailure && (
            <div data-testid={`${TestIdPrefix}.Error`}>
              <Banner tone="bad">
                <span role="alert">{describeError(write.error).body}</span>
              </Banner>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="quiet" onClick={onClose} data-testid={`${TestIdPrefix}.Cancel`}>
            Cancel
          </Button>
          <Button
            variant="primary"
            // Nothing chosen is not a refusal worth a round trip — the server's 404 for a missing
            // source says "the project, page, block or source is not visible here", which is the
            // right answer to a wrong id and a baffling one to an empty picker.
            disabled={!sourceId || write.isPending}
            onClick={submit}
            data-testid={`${TestIdPrefix}.Confirm`}
          >
            {write.isPending ? 'Linking…' : 'Link'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** One spelling of a case in this picker — the option label, the input text, and the seed. */
function caseLabel(row: TestCaseSummary): string {
  return `${row.key} — ${row.title}`;
}

/**
 * The block's words, cut to a line.
 *
 * A `code` block's text is a program and a `paragraph`'s can be a page; either dumped whole turns
 * the dialog's first line into its longest. Cut on a character count rather than a word boundary —
 * the content is not always prose.
 */
function excerpt(text: string): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  if (flat === '') return 'no text';
  return flat.length > 90 ? `${flat.slice(0, 90)}…` : flat;
}
