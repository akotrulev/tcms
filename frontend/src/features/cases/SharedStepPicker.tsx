import AddIcon from '@mui/icons-material/Add';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  List,
  ListItemButton,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { useCreateSharedStep, useSharedSteps } from '../../api/queries';
import type { SharedStep } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';

const TestIdPrefix = 'SharedStepPicker';

/**
 * Pick a reusable step block, or write a new one.
 *
 * The library has had a contract entry since the API was first written and nothing ever called
 * it — `useSharedSteps` sat in the data module with zero call sites, on both sides at once. This
 * is its first consumer, and the reason the endpoint's `usageCount` had to be made real: the
 * whole value of the list is that the block four hundred cases already share sorts to the top.
 *
 * **Inserting sets `action` to the entry's title, not its body.** The step row shows one line,
 * and a shared block is a *reference* — the body lives in the library and is resolved when the
 * case is read, so copying it into the step would make the case carry a stale duplicate that
 * stops tracking the entry it came from. That is the same decision the detail page renders as
 * its "Shared" chip.
 */
export function SharedStepPicker({
  projectId,
  open,
  onClose,
  onPick,
}: {
  projectId: string;
  open: boolean;
  onClose: () => void;
  /** Receives the chosen entry; the caller decides what a "step" made from it looks like. */
  onPick: (step: SharedStep) => void;
}) {
  const [q, setQ] = useState('');
  const [creating, setCreating] = useState(false);

  const library = useSharedSteps(projectId, q);

  const close = () => {
    // Reset on the way out rather than on the way in: a picker reopened with the previous
    // search still in the box looks like it has lost the rest of the library.
    setQ('');
    setCreating(false);
    onClose();
  };

  return (
    <Dialog open={open} onClose={close} fullWidth maxWidth="sm" data-testid={TestIdPrefix}>
      <DialogTitle>Insert a shared step</DialogTitle>
      <DialogContent dividers>
        {creating ? (
          <CreateEntry
            projectId={projectId}
            onCancel={() => setCreating(false)}
            onCreated={(step) => {
              onPick(step);
              close();
            }}
          />
        ) : (
          <Stack sx={{ gap: 2 }}>
            <TextField
              label="Search the library"
              value={q}
              onChange={(event) => setQ(event.target.value)}
              fullWidth
              autoFocus
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Search` } }}
            />

            <DataRegion
              testId={`${TestIdPrefix}.Results`}
              state={library}
              isEmpty={(rows) => rows.length === 0}
              empty={{
                title: q ? 'Nothing matches that' : 'The library is empty',
                // Two different empties, said differently. "No results" on a project whose
                // library was never populated sends the reader looking for a filter to clear.
                body: q
                  ? 'Try a shorter search, or write a new block.'
                  : 'Nothing has been added to this project’s step library yet.',
              }}
            >
              {(rows) => (
                <List disablePadding data-testid={`${TestIdPrefix}.List`}>
                  {rows.map((step) => (
                    <ListItemButton
                      key={step.id}
                      onClick={() => {
                        onPick(step);
                        close();
                      }}
                      data-testid={`${TestIdPrefix}.Item`}
                      sx={{ alignItems: 'flex-start', flexDirection: 'column', gap: 0.5, py: 1.5 }}
                    >
                      <Stack
                        direction="row"
                        sx={{ alignItems: 'baseline', gap: 1, width: '100%' }}
                      >
                        <Typography sx={{ fontWeight: 600 }}>{step.title}</Typography>
                        <Box sx={{ flex: 1 }} />
                        <Identifier variant="muted">
                          {/* The ranking signal, shown rather than only sorted by — otherwise
                              the order looks arbitrary to the person reading the list. */}
                          {step.usageCount === 0
                            ? 'unused'
                            : `${step.usageCount} case${step.usageCount === 1 ? '' : 's'}`}
                        </Identifier>
                      </Stack>
                      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                        {step.body.join(' · ')}
                      </Typography>
                    </ListItemButton>
                  ))}
                </List>
              )}
            </DataRegion>

            <Divider />

            {/*
              `library:manage` — Test Engineer and up. Disabled with a reason rather than hidden,
              per §31: a Viewer learns the capability exists and is worth asking for.
            */}
            <PermissionGate projectId={projectId} permission="library:manage">
              {({ allowed }) => (
                <Button
                  startIcon={<AddIcon />}
                  disabled={!allowed}
                  onClick={() => setCreating(true)}
                  data-testid={`${TestIdPrefix}.NewButton`}
                  sx={{ alignSelf: 'flex-start' }}
                >
                  Write a new block
                </Button>
              )}
            </PermissionGate>
          </Stack>
        )}
      </DialogContent>
      {!creating && (
        <DialogActions>
          <Button onClick={close} data-testid={`${TestIdPrefix}.Cancel`}>
            Cancel
          </Button>
        </DialogActions>
      )}
    </Dialog>
  );
}

/**
 * The new-entry form, inline in the same dialog.
 *
 * Inline rather than a second dialog because the two are one intent: you reach for the library,
 * find nothing that fits, and write the block you were about to type into the step anyway. A
 * second modal on top of the first would make "back to the list" a thing to discover.
 */
function CreateEntry({
  projectId,
  onCancel,
  onCreated,
}: {
  projectId: string;
  onCancel: () => void;
  onCreated: (step: SharedStep) => void;
}) {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const create = useCreateSharedStep(projectId);

  // Split on the way out, and only here. The textarea is the honest input for an ordered list of
  // short actions — a field array of one-line inputs is more chrome than the content deserves —
  // but the wire shape is the array, so the split belongs at the boundary rather than in state.
  const lines = body
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

  const refused = create.isError ? describeError(create.error) : undefined;

  const submit = async () => {
    const created = await create.mutateAsync({ title: title.trim(), body: lines });
    onCreated(created);
  };

  return (
    <Stack sx={{ gap: 2 }} data-testid={`${TestIdPrefix}.Create`}>
      {refused && (
        <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Create.Error`}>
          {refused.title}
          {refused.body ? ` ${refused.body}` : ''}
        </Alert>
      )}

      <TextField
        label="Title"
        required
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        fullWidth
        autoFocus
        helperText="What the picker lists, and what the step will read."
        slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Create.Title` } }}
      />
      <TextField
        label="Actions"
        required
        value={body}
        onChange={(event) => setBody(event.target.value)}
        fullWidth
        multiline
        minRows={4}
        helperText="One action per line. Blank lines are dropped."
        slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Create.Body` } }}
      />

      <Stack direction="row" sx={{ gap: 1, justifyContent: 'flex-end' }}>
        <Button onClick={onCancel} data-testid={`${TestIdPrefix}.Create.Back`}>
          Back to the library
        </Button>
        <Button
          variant="contained"
          // The double-submit guard: `isPending` is the mutation's own, so a second click
          // during the round trip cannot create a second entry.
          disabled={create.isPending || !title.trim() || lines.length === 0}
          onClick={submit}
          data-testid={`${TestIdPrefix}.Create.Submit`}
        >
          {create.isPending ? 'Adding…' : 'Add and insert'}
        </Button>
      </Stack>
    </Stack>
  );
}
