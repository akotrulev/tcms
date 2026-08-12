import {
  Alert,
  Autocomplete,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import { useCases, useLinkBlock, useSuites } from '../../api/queries';
import type { BlockLink, DocBlock, LinkRelationship, Suite, TestCaseSummary } from '../../api/types';
import { describeError } from '../../lib/describeError';
import { docBlockTypeLabel, linkRelationshipHelp, linkRelationshipLabels, linkSourceTypeLabels } from '../../lib/labels';

type SourceType = BlockLink['sourceType'];

/**
 * `LinkDialog` and not `Link`: the list this dialog writes into is `Docs.Block.Links`, and two
 * prefixes differing by one character is a locator somebody reads wrong once and a test asserts
 * against the wrong element for ever.
 */
const TestIdPrefix = 'Docs.Block.LinkDialog';

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
 * answer client-side) and the suite arm is a plain select over the whole list. Both queries stay
 * mounted across the toggle: they are two cheap list reads, and gating one on the toggle would make
 * switching kinds show a spinner over data that was already in the cache.
 *
 * ## Linking twice is not an error, so this dialog does not treat it as one
 *
 * The endpoint answers **200 with the existing link** when the same source is linked to the same
 * block again — re-asserting a recorded fact is not a conflict, and a duplicate row would double
 * every count drawn off `doc_link`. So there is nothing to warn about and nothing to disable: the
 * dialog closes and the block shows the one link it has either way.
 */
export function LinkBlockDialog({
  open,
  onClose,
  projectId,
  pageId,
  block,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  pageId: string;
  block: DocBlock;
}) {
  const write = useLinkBlock(projectId, pageId);
  const [sourceType, setSourceType] = useState<SourceType>('TEST_CASE');
  const [relationship, setRelationship] = useState<LinkRelationship>('VERIFIES');
  const [query, setQuery] = useState('');
  const [pickedCase, setPickedCase] = useState<TestCaseSummary | null>(null);
  const [pickedSuite, setPickedSuite] = useState<string>('');

  const cases = useCases(projectId, { q: query.trim() || undefined, size: 20 }, 'key,asc');
  const suites = useSuites(projectId);

  // Reopening after a refusal must not show the previous one — `QuarantineDialog`'s rule, and
  // `ExternalLinkDialog` two features over makes the same call.
  useEffect(() => {
    if (open) {
      write.reset();
      setSourceType('TEST_CASE');
      setRelationship('VERIFIES');
      setQuery('');
      setPickedCase(null);
      setPickedSuite('');
    }
    // `write` is a stable mutation object; depending on it would reset on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, block.id]);

  const fieldError = (field: string) =>
    write.error instanceof ApiError ? write.error.fieldErrors?.[field]?.join(' ') : undefined;
  const named = ['sourceType', 'sourceId', 'relationship'];
  const unnamedFailure = write.isError && !named.some((field) => fieldError(field) !== undefined);

  const sourceId = sourceType === 'TEST_CASE' ? (pickedCase?.id ?? '') : pickedSuite;

  const submit = () => {
    if (!sourceId) return;
    write.mutate({ blockId: block.id, sourceType, sourceId, relationship }, { onSuccess: onClose });
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestIdPrefix}>
      <DialogTitle>Link something to this block</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 0.5 }}>
          {/*
            The block restated, because the page has many and the dialog covers the one that was
            clicked. Its type is named as well as its words: a caption and a paragraph read the same
            in one line of prose, and on a `table` the text IS the caption — which is what the
            coverage matrix reads as the statement.
          */}
          <DialogContentText component="div" data-testid={`${TestIdPrefix}.Subject`}>
            {docBlockTypeLabel[block.blockType]} — {excerpt(block.text)}
          </DialogContentText>

          <ToggleButtonGroup
            exclusive
            size="small"
            value={sourceType}
            onChange={(_event, chosen: SourceType | null) => chosen && setSourceType(chosen)}
            aria-label="What is being linked"
          >
            {(Object.keys(linkSourceTypeLabels) as SourceType[]).map((value) => (
              <ToggleButton key={value} value={value} data-testid={`${TestIdPrefix}.Kind.${value}`}>
                {linkSourceTypeLabels[value]}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>

          {sourceType === 'TEST_CASE' ? (
            <Autocomplete
              size="small"
              options={cases.data?.items ?? []}
              // The server has already filtered. Filtering again client-side would hide rows that
              // matched on a field the option label does not show — `BindTestDialog`'s note.
              filterOptions={(all) => all}
              getOptionLabel={(option) => `${option.key} — ${option.title}`}
              inputValue={query}
              onInputChange={(_event, next) => setQuery(next)}
              value={pickedCase}
              onChange={(_event, next) => setPickedCase(next)}
              noOptionsText={cases.isPending ? 'Searching…' : 'No case matches.'}
              renderInput={(params) => (
                <TextField
                  {...params}
                  label="Case"
                  error={fieldError('sourceId') !== undefined}
                  helperText={fieldError('sourceId') ?? 'By key or title.'}
                  slotProps={{
                    ...params.slotProps,
                    htmlInput: {
                      ...params.slotProps.htmlInput,
                      'data-testid': `${TestIdPrefix}.Case`,
                    },
                  }}
                />
              )}
            />
          ) : (
            <TextField
              select
              label="Suite"
              value={pickedSuite}
              onChange={(event) => setPickedSuite(event.target.value)}
              error={fieldError('sourceId') !== undefined}
              helperText={
                fieldError('sourceId') ??
                'Every case the suite resolves to counts, including ones a tag rule pulls in later.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Suite` } }}
            >
              {(suites.data ?? []).map((suite: Suite) => (
                <MenuItem key={suite.id} value={suite.id}>
                  {suite.key} — {suite.name}
                </MenuItem>
              ))}
            </TextField>
          )}

          {/*
            Offered rather than defaulted, though the server defaults it — `ExternalLinkDialog`'s
            argument, and it bites harder here: this is the screen that fills the coverage marker,
            so a form that always sent VERIFIES would make every link on every specification read as
            proof, including the ones somebody meant as context.
          */}
          <TextField
            select
            label="What it does with this block"
            value={relationship}
            onChange={(event) => setRelationship(event.target.value as LinkRelationship)}
            error={fieldError('relationship') !== undefined}
            helperText={fieldError('relationship') ?? linkRelationshipHelp[relationship]}
            slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Relationship` } }}
          >
            {(Object.keys(linkRelationshipLabels) as LinkRelationship[]).map((value) => (
              <MenuItem key={value} value={value}>
                {linkRelationshipLabels[value]}
              </MenuItem>
            ))}
          </TextField>

          {unnamedFailure && (
            <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Error`}>
              {describeError(write.error).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid={`${TestIdPrefix}.Cancel`}>
          Cancel
        </Button>
        <Button
          variant="contained"
          // Nothing chosen is not a refusal worth a round trip — the server's 404 for a missing
          // source says "the project, page, block or source is not visible here", which is the
          // right answer to a wrong id and a baffling one to an empty picker.
          disabled={!sourceId || write.isPending}
          onClick={submit}
          data-testid={`${TestIdPrefix}.Confirm`}
        >
          {write.isPending ? 'Linking…' : 'Link'}
        </Button>
      </DialogActions>
    </Dialog>
  );
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
