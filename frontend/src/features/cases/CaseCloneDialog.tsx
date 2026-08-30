import { Banner } from '../../components/kit';
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
import { NativeSelect } from '../../components/ui/native-select';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCloneCase, useSuites } from '../../api/queries';
import type { TestCase } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { copyOf } from '../../lib/clone';
import { describeError } from '../../lib/describeError';
import { plural } from '../../lib/format';

/**
 * Cloning a test case (§129 phase 3) — the first consumer of `POST /cases/{caseId}/clone`, which
 * shipped in phase 1 with `curl` as its only caller.
 *
 * ## The two fields, and why there are only two
 *
 * **A clone is a create.** The server reads the source, builds the input `POST /cases` already
 * takes, and runs it through the same validation, the same key allocator and the same version
 * writer — so everything a form could offer to change here is either already copied or is the
 * editor's job a moment later. What the caller genuinely has to answer is *what is it called* and
 * *where does it go*, and the contract's input is exactly those two.
 *
 * ## The name is suggested here because the server refuses to invent one
 *
 * `title` is required on the wire. `copyOf` fills the field with `Copy of <title>` **in front of a
 * person who can edit it before it becomes a row** — which is a different object from a server-side
 * default: two rows nobody can tell apart in a list is what defaulting ships, and the length limit
 * would have to be decided by whoever wrote the prefix.
 *
 * ## The automation note is the one thing on this dialog that is not obvious
 *
 * `automationKey` is unique per company (§6c), so it is never copied — and dropping it **forces the
 * status down**, because `CaseEdit.validate` refuses `AUTOMATED` without a key. A clone of an
 * automated case is therefore created as `TO_BE_AUTOMATED`. That is the accurate state rather than a
 * compromise, and it is stated *before* the clone is made: a user who reads "Automated" on the
 * source and "To be automated" on the copy, with nothing having said so, reads it as a defect.
 *
 * The note renders **only for a source that is `AUTOMATED`** — for every other status nothing is
 * downgraded, and a permanent sentence about automation on a manual case is noise that teaches
 * readers to skip the box the one time it matters.
 *
 * ## The Folder picker decides whether the source's condition exceptions come across (§195)
 *
 * `CaseCloneService` copies the source's declines **only when the copy lands in the source's own
 * folder** — `clonedDeclines` is guarded by `Objects.equals(targetSuiteId, source.getSuiteId())`,
 * and the contract says the same thing on `TestCase.declinedPreconditions`. So this dialog had a
 * closing caption promising that "conditions come across" and a control two fields above it that
 * decides half of that sentence, with nothing joining the two.
 *
 * That is §194's shape at the other write path, and this is the second half of it: the editor warns
 * when a *move* ends a case's exceptions, and until now the clone said nothing when the copy simply
 * never gets them. Both are the same event to a reader — a case that stops declining a rule it used
 * to decline — and only one of the two screens was mentioning it.
 *
 * **The prediction here is EXACT, where the editor's is a bound.** `CaseEditorPage`'s alert reads
 * §98's flatness to conclude that a move ends every decline, so a widening of inheritance would make
 * it over-state; this one reads a rule the server states unconditionally in one `equals`, so there
 * is nothing for a schema change to falsify. That is why there is no measured counterpart on the way
 * out — the alert cannot drift from the writer, and, unlike a move, **a clone destroys nothing**:
 * the source keeps every exception it had, so there is no event that needs a record after the fact.
 * The dialog is simply the last moment somebody can decide not to, which is §193's argument for the
 * bulk preview.
 *
 * It shares the editor's one bound, and for the same reason: `declinedPreconditions` omits a decline
 * whose library entry has been retired, so the list below is what a list shows and can under-count.
 * Naming the rules rather than counting them is deliberate — "the copy will not carry 2 exceptions"
 * leaves the reader unable to find out which two.
 */

/** Mirrors `TestCaseCloneInput.title.maxLength`, itself `CaseEdit.MAX_TITLE`. */
const MAX_TITLE = 300;

const CloneSchema = z.object({
  title: z
    .string()
    .min(1, 'Enter a title for the copy.')
    .max(MAX_TITLE, `Keep the title under ${MAX_TITLE} characters.`),
  /** `''` means "leave it where the source is", which is what the server does with an absent field. */
  suiteId: z.string(),
});

type CloneFormInput = z.infer<typeof CloneSchema>;

const TestId = 'CaseDetail.Clone';

type FormField = 'title' | 'suiteId';

const FIELDS: readonly FormField[] = ['title', 'suiteId'];

/**
 * Maps a refused clone onto the form.
 *
 * Both of this endpoint's field errors have a control, so nothing should reach the banner from a
 * 400 — but the fallthrough stays, because a 403 (the gate below is an affordance over a snapshot,
 * and a grant can narrow between load and click) carries no `errors` map at all.
 */
function applyServerError(
  error: unknown,
  setError: (field: FormField | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    const stray: string[] = [];
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if ((FIELDS as readonly string[]).includes(field)) {
        setError(field as FormField, messages.join(' '));
        landed = true;
      } else {
        stray.push(messages.join(' '));
      }
    }
    if (stray.length > 0) {
      setError('root', stray.join(' '));
      return;
    }
    if (landed) return;
  }
  setError('root', describeError(error).body);
}

export function CaseCloneDialog({
  projectId,
  testCase,
  open,
  onClose,
  onCloned,
}: {
  projectId: string;
  testCase: TestCase;
  open: boolean;
  onClose: () => void;
  onCloned: (created: TestCase) => void;
}) {
  const clone = useCloneCase(projectId, testCase.id);
  const suites = useSuites(projectId);

  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    watch,
    formState: { errors },
  } = useForm<CloneFormInput>({
    resolver: zodResolver(CloneSchema),
    defaultValues: { title: copyOf(testCase.title, MAX_TITLE), suiteId: '' },
    mode: 'onBlur',
  });

  // Focus only, no `reset` — the dialog is conditionally mounted, so it starts fresh each time it
  // opens, and resetting here is what wiped the suite dialog's server errors in §30.
  useEffect(() => {
    if (open) setFocus('title');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      const created = await clone.mutateAsync({
        title: values.title.trim(),
        // Omitted rather than sent null when the field is untouched. The two mean the same thing to
        // this endpoint, and omitting keeps an ordinary clone's body identical to the smallest
        // request the contract describes.
        ...(values.suiteId ? { suiteId: values.suiteId } : {}),
      });
      onCloned(created);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  const sourceFolder = testCase.suiteName ?? 'Not filed';

  /*
    True while the picker names a folder the source is not in (§195) — the clone's analogue of the
    editor's `movingFolder`, with this dialog's own encoding rather than the editor's.

    '' means "same as the source" HERE, not UNFILED (§96) — the two dialogs spell the empty option
    differently because they are answering different questions, and the difference is load-bearing
    in exactly this expression. Picking the source's own folder from the list is therefore not a
    move either: it resolves to the same id the server compares against.
  */
  const destination = watch('suiteId');
  const landingElsewhere = destination !== '' && destination !== testCase.suiteId;

  /*
    The exceptions the copy will not carry — the case's declined inherited rules, both kinds in one
    list. One alert rather than two for `CaseEditorPage`'s reason: the reader is being told about
    one event, and splitting it by PRE/POST states it twice and names half of it each time.
  */
  const notCarried = landingElsewhere
    ? [...testCase.declinedPreconditions, ...testCase.declinedPostconditions]
    : [];

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={clone.isPending}>
        <DialogHeader>
          <DialogTitle>Clone this test case</DialogTitle>
        </DialogHeader>
        <div className="mt-4 flex flex-col gap-4">
          <div className="flex flex-col mt-2 gap-4">
            <p className="m-0 text-[13px] text-ink-2">
              <Identifier>{testCase.key}</Identifier> {testCase.title}
            </p>

            {errors.root && (
              <div data-testid={`${TestId}.Error`}>
            <Banner tone="bad"><span role="alert">
                {errors.root.message}
              </span></Banner>
          </div>
            )}

            <Field
              label="Title"
              htmlFor="title"
              error={errors.title?.message}
              help="Suggested from the source. Two cases a reader cannot tell apart are worse than one."
            >
            <Input
              id="title"
              autoComplete="off"
              required
              aria-invalid={!!errors.title}
              maxLength={MAX_TITLE}
              data-testid={`${TestId}.Title`}
              {...register('title')}
            />
            </Field>

            <Field
              label="Folder"
              htmlFor="folder"
              error={errors.suiteId?.message}
              help="Where the copy is filed."
            >
            <NativeSelect
              id="folder"
              aria-invalid={!!errors.suiteId}
              data-testid={`${TestId}.Suite`}
              {...register('suiteId')}
            >

                          {/*
                            An explicit row rather than a blank first option, naming the folder the copy lands
                            in — `RunCreateDialog`'s reason: a blank option reads as a field somebody forgot to
                            fill in, and here it would hide the whole default.
                          */}
              <option value="" data-testid={`${TestId}.Suite.Source`}>
                Same as the source — {sourceFolder}
              </option>
              {(suites.data ?? []).map((suite) => (
                <option key={suite.id} value={suite.id} data-testid={`${TestId}.Suite.${suite.key}`}>
                  {suite.key} · {suite.name}
                </option>
              ))}
            </NativeSelect>
            </Field>

            {/*
              Directly under the control that decides it, not down beside the closing caption: the
              consequence of a picker belongs where the picker is, and the caption is the sentence
              this alert is the exception to.

              `warning` on §193's and §194's grounds — a skip is something an operation will not do,
              and this is something it will do that nobody asked for. The rules are named from
              `inheritedFrom`, which is the folder the exception was taken from and therefore the
              answer to "which of my four rules is this".
            */}
            {notCarried.length > 0 && (
              <div data-testid={`${TestId}.NotCarriedDeclines`}>
            <Banner tone="warn">
                <p className="m-0 mb-1 text-[13px] font-semibold text-ink">
                  The copy will not carry {plural(notCarried.length, 'condition exception')}
                </p>
                <p className="m-0 text-[13px]">
                  A folder&rsquo;s rule can only be declined by a case filed in that folder, so the
                  copy follows {notCarried.length === 1 ? 'its' : 'their'} new folder&rsquo;s rules
                  in full. <Identifier>{testCase.key}</Identifier> keeps{' '}
                  {notCarried.length === 1 ? 'its exception' : 'its exceptions'}.
                </p>
                <ul className="pl-6 my-2">
                  {notCarried.map((ref) => (
                    <li className="m-0 text-[13px]" key={ref.id}>
                      {ref.title}
                      {ref.inheritedFrom ? ` — from ${ref.inheritedFrom}` : null}
                    </li>
                  ))}
                </ul>
              </Banner>
          </div>
            )}

            {testCase.automationStatus === 'AUTOMATED' && (
              <div data-testid={`${TestId}.AutomationNote`}>
            <Banner tone="steel">
                The automation key is not copied — it identifies one case to CI, and two cases
                claiming it would make an ingested result match both. The copy is created as{' '}
                <strong>To be automated</strong>; give it its own key once the test exists.
              </Banner>
          </div>
            )}

            {/*
              "the conditions it attaches itself" rather than "conditions" (§195). The unqualified
              word was the claim the Folder picker falsifies: a case's OWN attachments are copied
              whatever folder the copy lands in, while the standing rules it inherits are always the
              destination's — and its exceptions to them are the alert above.
            */}
            <p className="m-0 text-[12px] text-ink-2">
              The steps, classification, tags, attached files and the conditions the case attaches
              itself come across; the standing rules it inherits are its new folder&rsquo;s. The
              review state starts at draft, and the source keeps its version and execution history.
            </p>
          </div>
        </div>
        <DialogFooter>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={clone.isPending}
            data-testid={`${TestId}.Submit`}
          >
            {clone.isPending ? 'Cloning…' : 'Clone case'}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
    </Dialog>
  );
}
