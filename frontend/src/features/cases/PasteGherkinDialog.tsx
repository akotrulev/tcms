import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { ApiError } from '../../api/client';
import { useCreateCondition, useParseGherkin } from '../../api/queries';
import type { Condition, GherkinParseResult } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { plural } from '../../lib/format';
import { stepKeywordLabels } from '../../lib/labels';

/**
 * Pasting a `.feature` scenario into the case editor (§255).
 *
 * ## What it does, and the one thing it does not
 *
 * Text in, rows out. `POST .../cases/parse-gherkin` is a pure function — it persists nothing — so
 * everything on this dialog is a **proposal** until the author presses Append or Replace, and even
 * then it lands in the form rather than on the server. Save is still Save.
 *
 * The exception is the Background, and it is an exception because Gherkin has no other home for it:
 * a Background step is a **precondition** in this product's model, one per step (amendment AM1),
 * and a precondition is a library entry that other cases attach. So each entry offers *Create
 * precondition and attach*, which really does write — `POST .../conditions`, under
 * `library:manage`. Nothing else here writes anything.
 *
 * ## The four refusals are the answer, not a failure
 *
 * `gherkin-syntax`, `gherkin-outline`, `gherkin-many-scenarios` and `gherkin-empty` are the first
 * non-`about:blank` problem types this API sends, and the sentence a caller needs is on
 * `errors.text` — keyed to the textarea, which is why it is rendered against the box rather than as
 * a toast. **The sentence is the server's, quoted** (§149): composing words around "Line 4, column
 * 3: expected a step" is how the author gets told two different things about one typo.
 *
 * ## Disabled, not hidden
 *
 * A caller without `library:manage` sees the Background entries and a disabled button with the
 * reason on it — the parse still worked, and the rows still apply. Hiding the offer would make a
 * Background look like something the paste silently dropped.
 */
export function PasteGherkinDialog({
  projectId,
  open,
  onClose,
  onApply,
  onConditionCreated,
  hasSteps,
}: {
  projectId: string;
  open: boolean;
  onClose: () => void;
  /**
   * The parsed scenario, and whether the author asked to add to the list or start again.
   *
   * The whole result rather than just the rows: the editor decides what to do with the title, the
   * objective and the tags, because it is the only side that knows what is already in those fields.
   */
  onApply: (result: GherkinParseResult, mode: 'APPEND' | 'REPLACE') => void;
  /** A precondition created from a Background step — the editor attaches it to the case. */
  onConditionCreated: (condition: Condition) => void;
  /** Whether the form already holds a step, which is what makes Replace a destructive choice. */
  hasSteps: boolean;
}) {
  const [text, setText] = useState('');
  const [result, setResult] = useState<GherkinParseResult | null>(null);
  /*
    Which Background entries have already been created, by their index in `background`.

    Kept here rather than read back off the case's conditions, because two Background steps can
    legitimately have the same title — "Given a user" appearing twice is a file somebody wrote —
    and matching on text would tick both when one was created.
  */
  const [created, setCreated] = useState<number[]>([]);

  const parse = useParseGherkin(projectId);
  const createCondition = useCreateCondition(projectId);

  const close = () => {
    // The dialog is unmounted by its caller, so nothing here would survive anyway — except that a
    // failed mutation's error is what the next open would render before anybody typed. Reset
    // rather than rely on the unmount, because a caller that keeps it mounted is a one-line change.
    setText('');
    setResult(null);
    setCreated([]);
    parse.reset();
    createCondition.reset();
    onClose();
  };

  const run = async () => {
    setResult(null);
    try {
      setResult(await parse.mutateAsync(text));
    } catch {
      // Rendered from `parse.error` below. Swallowed here so an unparseable paste — the ordinary
      // case on this dialog — is not an unhandled rejection in the console beside its own message.
    }
  };

  /** The server's own sentence for a refused paste, or nothing when the failure was not a refusal. */
  const refusal =
    parse.error instanceof ApiError ? parse.error.fieldErrors?.text?.join(' ') : undefined;

  return (
    <Dialog open={open} onClose={close} maxWidth="md" fullWidth>
      <DialogTitle data-testid="PasteGherkin.Title">Paste a Gherkin scenario</DialogTitle>
      <DialogContent>
        <Stack sx={{ gap: 2, pt: 1 }}>
          <TextField
            label="Scenario"
            fullWidth
            multiline
            minRows={10}
            value={text}
            onChange={(event) => setText(event.target.value)}
            error={!!refusal}
            helperText={
              refusal
              ?? 'A bare step list, a Scenario: block, or a Feature: with a Background and one Scenario.'
            }
            slotProps={{
              htmlInput: {
                'data-testid': 'PasteGherkin.Text',
                style: { fontFamily: 'var(--font-mono)', fontSize: '0.8125rem', lineHeight: 1.7 },
              },
              formHelperText: { role: refusal ? 'alert' : undefined },
            }}
          />

          {/* A failure that is NOT one of the four refusals — a timeout, a 404, a 413. The four
              carry their sentence on the field above, so showing both would state one fault twice. */}
          {parse.error && !refusal && (
            <Alert severity="error" role="alert" data-testid="PasteGherkin.Error">
              {describeError(parse.error).body}
            </Alert>
          )}

          {result && (
            <Stack sx={{ gap: 2 }} data-testid="PasteGherkin.Result">
              <Alert severity="success" icon={false}>
                <AlertTitle>
                  {plural(result.steps.length, 'step')} read
                  {result.title ? ` from “${result.title}”` : ''}
                </AlertTitle>
                {/* The tags and the objective are shown because the editor merges them, and an
                    author who cannot see what a paste is about to change to their tag list finds
                    out on the version timeline. */}
                {result.tags.length > 0 && (
                  <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap', mt: 1 }}>
                    {result.tags.map((tag) => (
                      <Chip key={tag} size="small" label={tag} data-testid="PasteGherkin.Tag" />
                    ))}
                  </Stack>
                )}
              </Alert>

              <Box>
                {result.steps.map((step, index) => (
                  <Stack
                    key={index}
                    direction="row"
                    sx={{ gap: 1, alignItems: 'baseline' }}
                    data-testid="PasteGherkin.Step"
                  >
                    <Typography
                      variant="caption"
                      sx={{ color: 'primary.main', fontWeight: 600, minWidth: 44 }}
                    >
                      {/* `*` is Gherkin's own spelling for a step with no keyword, which is what a
                          bare step list parses to — not a blank, which reads as a missing value. */}
                      {step.keyword ? stepKeywordLabels[step.keyword] : '*'}
                    </Typography>
                    <Typography variant="body2">{step.action}</Typography>
                  </Stack>
                ))}
              </Box>

              {result.background.length > 0 && (
                <Alert severity="info" icon={false} data-testid="PasteGherkin.Background">
                  <AlertTitle>
                    {plural(result.background.length, 'Background step')} — one precondition each
                  </AlertTitle>
                  <Typography variant="body2" sx={{ mb: 1 }}>
                    A Background is setup every scenario in the file shares, which is what a
                    precondition is here. Each becomes a library entry other cases can attach.
                  </Typography>
                  <Stack sx={{ gap: 1 }}>
                    {result.background.map((entry, index) => (
                      <Stack
                        key={index}
                        direction="row"
                        sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}
                        data-testid="PasteGherkin.Background.Row"
                      >
                        <Identifier variant="muted">{entry.title}</Identifier>
                        <Box sx={{ flex: 1 }} />
                        {created.includes(index) ? (
                          <Chip
                            size="small"
                            color="success"
                            label="Attached"
                            data-testid="PasteGherkin.Background.Attached"
                          />
                        ) : (
                          <PermissionGate projectId={projectId} permission="library:manage">
                            {({ allowed }) => (
                              <Button
                                size="small"
                                disabled={!allowed || createCondition.isPending}
                                onClick={async () => {
                                  try {
                                    const condition = await createCondition.mutateAsync({
                                      title: entry.title,
                                      type: 'PRE',
                                      body: entry.body,
                                    });
                                    onConditionCreated(condition);
                                    setCreated((current) => [...current, index]);
                                  } catch {
                                    // Rendered from `createCondition.error` below. Caught so a
                                    // refused create — a title over 150 characters, a revoked
                                    // grant — is a message beside the row rather than an
                                    // unhandled rejection in the console.
                                  }
                                }}
                                data-testid="PasteGherkin.Background.Create"
                              >
                                Create precondition and attach
                              </Button>
                            )}
                          </PermissionGate>
                        )}
                      </Stack>
                    ))}
                  </Stack>
                  {createCondition.error && (
                    <Typography
                      variant="body2"
                      role="alert"
                      sx={{ color: 'error.main', mt: 1 }}
                      data-testid="PasteGherkin.Background.Error"
                    >
                      {describeError(createCondition.error).body}
                    </Typography>
                  )}
                </Alert>
              )}
            </Stack>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} data-testid="PasteGherkin.Cancel">
          Cancel
        </Button>
        <Button
          onClick={() => void run()}
          disabled={text.trim().length === 0 || parse.isPending}
          data-testid="PasteGherkin.Parse"
        >
          {parse.isPending ? 'Reading…' : 'Read the scenario'}
        </Button>
        {/*
          Append is the plain button and Replace is the contained one only while the list is empty,
          because that is the moment Replace is not destructive. On a case that already has rows,
          Replace discards the author's steps AND their ids — which is what a `step_result` anchors
          to — so it stays the quieter of the two and says what it will do.
        */}
        <Button
          onClick={() => {
            if (result) onApply(result, 'APPEND');
            close();
          }}
          disabled={!result}
          variant={hasSteps ? 'contained' : 'text'}
          data-testid="PasteGherkin.Append"
        >
          Add to the steps
        </Button>
        <Button
          onClick={() => {
            if (result) onApply(result, 'REPLACE');
            close();
          }}
          disabled={!result}
          color={hasSteps ? 'warning' : 'primary'}
          variant={hasSteps ? 'text' : 'contained'}
          title={hasSteps ? 'The steps already written are discarded, with their ids.' : undefined}
          data-testid="PasteGherkin.Replace"
        >
          Replace the steps
        </Button>
      </DialogActions>
    </Dialog>
  );
}
