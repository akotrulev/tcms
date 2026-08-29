import { Banner, Combobox } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
} from '../../components/ui';
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
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg">
      <DialogHeader>
          <DialogTitle>Bind this test to a case</DialogTitle>
        </DialogHeader>
      <div className="mt-4 flex flex-col gap-4">
        <p className="m-0 text-ink">
          Results for this test will be recorded against the case you choose, in this build and
          every later one — through renames of the case, its suite and the project. Only renaming
          the test itself breaks the binding.
        </p>

        <Field
          label="Test"
          htmlFor="bind-test-name"
          help="As the report named it. The server normalizes; this screen does not."
        >
          {/*
            Read-only rather than absent: the reader has to be able to check they are binding the
            row they clicked. Not editable, because the name is a fact about the report.
          */}
          <Input id="bind-test-name" value={test.name} readOnly data-testid="BindTest.Name" />
        </Field>

        <Field label="Case" htmlFor="bind-test-case" help="By key or title.">
          <Combobox
            id="bind-test-case"
            query={query}
            onQueryChange={setQuery}
            options={search.data?.items ?? []}
            optionKey={(option) => option.id}
            optionLabel={(option) => `${option.key} — ${option.title}`}
            selected={picked}
            onSelect={setPicked}
            emptyLabel={search.isPending ? 'Searching…' : 'No case matches.'}
            data-testid="BindTest.Case"
          />
        </Field>

          {failure && (
            <div data-testid="BindTest.Error">
            <Banner tone="bad"><span role="alert">
              {failure}
            </span></Banner>
          </div>
          )}
        </div>
      <DialogFooter>
        <Button onClick={onClose} data-testid="BindTest.Cancel">
          Cancel
        </Button>
        <Button
          variant="primary"
          disabled={!picked || confirm.isPending}
          onClick={() => void submit()}
          data-testid="BindTest.Confirm"
        >
          {picked ? <>Bind to <Identifier>{picked.key}</Identifier></> : 'Bind'}
        </Button>
      </DialogFooter>
    </DialogContent>
    </Dialog>
  );
}
