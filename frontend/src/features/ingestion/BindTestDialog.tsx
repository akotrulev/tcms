import {
  Alert,
  Autocomplete,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material';
import { useState } from 'react';
import { useCases, useConfirmAutomationMapping } from '../../api/queries';
import type { TestCaseSummary, UnmatchedTest } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { describeError } from '../../lib/describeError';

/**
 * *"This test name means this case."* — §6c's third ladder step, written by a person.
 *
 * ## The name is sent RAW, and this component must never normalize it
 *
 * `testName` goes to the server exactly as the report wrote it. The normalization rule (trim,
 * collapse internal whitespace, lower-case under `Locale.ROOT`) lives on the server beside the
 * parser that applies it to every incoming report, and a copy of it here is a second implementation
 * of the one string that must agree exactly or **every mapping in the company silently stops
 * matching** — with no error anywhere, because a mapping that matches nothing looks exactly like a
 * test nobody has bound.
 *
 * So the dialog shows the name and posts it. There is no field to edit it in, which is also why:
 * the name is a fact about the report, not an input.
 *
 * ## Re-pointing is not a separate thing
 *
 * `POST` answers 200 whether it created the binding or **moved** one — the row is keyed on the test
 * name, so a second confirmation of the same name is the same resource re-pointed. The dialog says
 * so in the case where it matters (a name already bound), rather than offering a "replace?"
 * confirmation for an operation the server does not distinguish.
 *
 * ## Why the picker is a search and not a list
 *
 * A project has thousands of cases and the person binding a test usually knows its key or a word
 * from its title. `useCases` with `size: 20` opens on the first twenty rather than staying empty
 * until two characters are typed, because a control that shows nothing on open reads as broken to
 * somebody who does not yet know a key.
 */
export function BindTestDialog({
  open,
  projectId,
  test,
  onClose,
}: {
  open: boolean;
  projectId: string;
  test: UnmatchedTest;
  onClose: () => void;
}) {
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<TestCaseSummary | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const search = useCases(projectId, { q: query.trim() || undefined, size: 20 }, 'key,asc');
  const confirm = useConfirmAutomationMapping(projectId);

  const submit = async () => {
    if (!picked) return;
    setFailure(null);
    try {
      await bind();
      onClose();
    } catch (error) {
      // Caught and shown on the dialog rather than left to the global handler: the refusal this
      // most often produces is a 400 on `caseId` ("that case is not one of this project's"), which
      // is a statement about the control the reader just used.
      setFailure(describeError(error).body);
    }
  };

  const bind = async () => {
    if (!picked) return;
    await confirm.mutateAsync({
      // RAW — see the class header. Pasting a served `normalizedName` back would also work
      // (it is already canonical), and it is deliberately not what is sent: `name` is what the
      // report said, and the server is the one thing that decides what that means.
      testName: test.name,
      caseId: picked.id,
    });
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Bind this test to a case</DialogTitle>
      <DialogContent>
        <DialogContentText component="div" sx={{ mb: 2 }}>
          Results for this test will be recorded against the case you choose, in this build and
          every later one — through renames of the case, its suite and the project. Only renaming
          the test itself breaks the binding.
        </DialogContentText>

        <Stack sx={{ gap: 2 }}>
          <TextField
            label="Test"
            value={test.name}
            fullWidth
            size="small"
            // Read-only rather than absent: the reader has to be able to check they are binding
            // the row they clicked. Not editable, because the name is a fact about the report.
            slotProps={{ input: { readOnly: true }, htmlInput: { 'data-testid': 'BindTest.Name' } }}
            helperText="As the report named it. The server normalizes; this screen does not."
          />

          <Autocomplete
            size="small"
            options={search.data?.items ?? []}
            // The server has already filtered. Filtering again client-side would hide rows that
            // matched on a field the option label does not show.
            filterOptions={(all) => all}
            getOptionLabel={(option) => `${option.key} — ${option.title}`}
            inputValue={query}
            onInputChange={(_event, next) => setQuery(next)}
            value={picked}
            onChange={(_event, next) => setPicked(next)}
            noOptionsText={search.isPending ? 'Searching…' : 'No case matches.'}
            renderInput={(params) => (
              <TextField
                {...params}
                label="Case"
                helperText="By key or title."
                slotProps={{
                  ...params.slotProps,
                  htmlInput: {
                    ...params.slotProps.htmlInput,
                    'data-testid': 'BindTest.Case',
                  },
                }}
              />
            )}
          />

          {failure && (
            <Alert severity="error" role="alert" data-testid="BindTest.Error">
              {failure}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="BindTest.Cancel">
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={!picked || confirm.isPending}
          onClick={() => void submit()}
          data-testid="BindTest.Confirm"
        >
          {picked ? <>Bind to <Identifier>{picked.key}</Identifier></> : 'Bind'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
