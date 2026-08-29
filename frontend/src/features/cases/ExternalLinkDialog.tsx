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
import { useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import { useLinkCaseExternally } from '../../api/queries';
import type { ExternalLinkInput, LinkRelationship } from '../../api/types';
import { describeError } from '../../lib/describeError';
import { linkRelationshipHelp, linkRelationshipLabels } from '../../lib/labels';

type Kind = NonNullable<ExternalLinkInput['targetKind']>;

/**
 * Linking a case to code, or to a document published somewhere else (§169; plan §6m, #26).
 *
 * ## The kind is a choice, never inferred from what has been typed
 *
 * The endpoint requires `targetKind` and refuses to guess it, and this dialog is the reason that
 * matters: a form that decided from which inputs were filled would send a git link the moment
 * somebody pasted a URL into the path field, and the server would accept it. The toggle is the
 * first control and it swaps the fields below it — which is `ui-rules` §4 satisfied rather than
 * broken, because the control does not change *meaning*, it selects between two shapes and says
 * which one is selected.
 *
 * ## The commit is required and the copy says why
 *
 * Not "required" — *why*. It is the one field somebody will want to leave out, and #26's whole
 * decision is that an unpinned range points at different code after the next push. A helper text
 * reading "Required" invites the reader to work around it; one naming the failure does not.
 *
 * ## What it does not validate
 *
 * The repository and path are free text and are not checked against anything. TCMS does not talk
 * to a git host — that is an integration and not a field — so a path that does not exist is
 * accepted here and discovered by the reader who clicks it. Pretending otherwise would need a
 * credential this product does not hold. The sha's *shape* is checked, on both sides, because that
 * one is decidable without asking anybody.
 */
export function ExternalLinkDialog({
  open,
  onClose,
  projectId,
  caseId,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  caseId: string;
}) {
  const write = useLinkCaseExternally(projectId, caseId);
  // The bound `reset`, not the mutation object: the object is a fresh literal on every render,
  // so depending on it would clear the refusal the dialog is displaying.
  const { reset } = write;
  const [kind, setKind] = useState<Kind>('EXTERNAL_GIT');
  const [relationship, setRelationship] = useState<LinkRelationship>('VERIFIES');
  const [repo, setRepo] = useState('');
  const [path, setPath] = useState('');
  const [lineStart, setLineStart] = useState('');
  const [lineEnd, setLineEnd] = useState('');
  const [commitSha, setCommitSha] = useState('');
  const [url, setUrl] = useState('');

  // Reopening after a refusal must not show the previous one — `QuarantineDialog`'s rule.
  useEffect(() => {
    if (open) {
      reset();
      setKind('EXTERNAL_GIT');
      setRelationship('VERIFIES');
      setRepo('');
      setPath('');
      setLineStart('');
      setLineEnd('');
      setCommitSha('');
      setUrl('');
    }
  }, [open, caseId, reset]);

  const fieldError = (field: string) =>
    write.error instanceof ApiError ? write.error.fieldErrors?.[field]?.join(' ') : undefined;
  const named = ['targetKind', 'repo', 'path', 'lineStart', 'lineEnd', 'commitSha', 'url'];
  const unnamedFailure =
    write.isError && !named.some((field) => fieldError(field) !== undefined);

  const submit = () => {
    // Only the fields of the chosen kind are sent. The server refuses a request carrying the
    // other kind's coordinates rather than ignoring them, so leaving a typed-then-switched value
    // in the payload would be refused — correctly, and confusingly, since the input is no longer
    // on screen.
    const input: ExternalLinkInput =
      kind === 'EXTERNAL_GIT'
        ? {
            targetKind: 'EXTERNAL_GIT',
            relationship,
            repo: repo.trim(),
            path: path.trim(),
            ...(lineStart.trim() === '' ? {} : { lineStart: Number(lineStart) }),
            ...(lineEnd.trim() === '' ? {} : { lineEnd: Number(lineEnd) }),
            commitSha: commitSha.trim(),
          }
        : { targetKind: 'EXTERNAL_URL', relationship, url: url.trim() };
    write.mutate(input, { onSuccess: onClose });
  };

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid="CaseDetail.ExternalLink">
      <DialogHeader>
          <DialogTitle>Link this case to something outside TCMS</DialogTitle>
        </DialogHeader>
      <div className="mt-4 flex flex-col gap-4">
          {/*
            ~~`ToggleButtonGroup`~~ → two `aria-pressed` buttons, T16. The group was already
            `exclusive` over two values, which is a two-state choice and not a set — so the port is
            the markup MUI was rendering underneath, with the pressed state stated on each button
            rather than inferred from a class.
          */}
          <div role="group" aria-label="What is being linked" className="flex gap-1">
            {(
              [
                ['EXTERNAL_GIT', 'Code', 'CaseDetail.ExternalLink.Git'],
                ['EXTERNAL_URL', 'A URL', 'CaseDetail.ExternalLink.Url'],
              ] as const
            ).map(([value, label, testId]) => (
              <Button
                key={value}
                type="button"
                size="sm"
                variant={kind === value ? 'primary' : 'ghost'}
                aria-pressed={kind === value}
                onClick={() => setKind(value)}
                data-testid={testId}
              >
                {label}
              </Button>
            ))}
          </div>

          {kind === 'EXTERNAL_GIT' ? (
            <>
              <Field label="Repository" htmlFor="link-repo" error={fieldError('repo')}>
                <Input
                  id="link-repo"
                  value={repo}
                  onChange={(event) => setRepo(event.target.value)}
                  placeholder="acme/checkout-service"
                  aria-invalid={fieldError('repo') !== undefined}
                  data-testid="CaseDetail.ExternalLink.Repo"
                />
              </Field>
              <Field label="File" htmlFor="link-path" error={fieldError('path')}>
                <Input
                  id="link-path"
                  value={path}
                  onChange={(event) => setPath(event.target.value)}
                  placeholder="src/main/java/com/acme/Checkout.java"
                  aria-invalid={fieldError('path') !== undefined}
                  data-testid="CaseDetail.ExternalLink.Path"
                />
              </Field>
              <div className="flex gap-4">
                <Field
                  label="First line"
                  htmlFor="link-line-start"
                  error={fieldError('lineStart')}
                  help="Optional — leave both empty for the whole file."
                  className="flex-1"
                >
                  <Input
                    id="link-line-start"
                    type="number"
                    value={lineStart}
                    onChange={(event) => setLineStart(event.target.value)}
                    aria-invalid={fieldError('lineStart') !== undefined}
                    data-testid="CaseDetail.ExternalLink.LineStart"
                  />
                </Field>
                <Field
                  label="Last line"
                  htmlFor="link-line-end"
                  error={fieldError('lineEnd')}
                  className="flex-1"
                >
                  <Input
                    id="link-line-end"
                    type="number"
                    value={lineEnd}
                    onChange={(event) => setLineEnd(event.target.value)}
                    aria-invalid={fieldError('lineEnd') !== undefined}
                    data-testid="CaseDetail.ExternalLink.LineEnd"
                  />
                </Field>
              </div>
              <Field
                label="Commit"
                htmlFor="link-sha"
                error={fieldError('commitSha')}
                help="The lines are pinned to this commit. Without one they point at different code after the next push — a branch name is refused for that reason."
              >
                <Input
                  id="link-sha"
                  value={commitSha}
                  onChange={(event) => setCommitSha(event.target.value)}
                  placeholder="4f9c2b1"
                  aria-invalid={fieldError('commitSha') !== undefined}
                  data-testid="CaseDetail.ExternalLink.Sha"
                />
              </Field>
            </>
          ) : (
            <Field
              label="Address"
              htmlFor="link-url"
              error={fieldError('url')}
              help="Must start with http:// or https://."
            >
              <Input
                id="link-url"
                value={url}
                onChange={(event) => setUrl(event.target.value)}
                placeholder="https://specs.example.com/refunds"
                aria-invalid={fieldError('url') !== undefined}
                data-testid="CaseDetail.ExternalLink.Address"
              />
            </Field>
          )}

          {/*
            Offered rather than defaulted, though the server defaults it. The three readings are the
            field's whole value — LinkRelationship's own header says a link recorded as VERIFIES
            when the case merely REFERENCES the statement is what turns a coverage number into a
            false claim — and a form that always sent the default would make the other two
            unreachable from this product, which is the shape this slice exists to close.
          */}
          <Field
            label="What this case does with it"
            htmlFor="link-relationship"
            error={fieldError('relationship')}
            help={linkRelationshipHelp[relationship]}
          >
            {/*
              `NativeSelect` and not the Radix one, for T15's recorded reason: three words from a
              closed set is exactly what the platform control is for, and `@radix-ui/react-select`
              cannot run under jsdom, whose `hasPointerCapture` it calls.
            */}
            <NativeSelect
              id="link-relationship"
              value={relationship}
              onChange={(event) => setRelationship(event.target.value as LinkRelationship)}
              aria-invalid={fieldError('relationship') !== undefined}
              data-testid="CaseDetail.ExternalLink.Relationship"
            >
              {(Object.keys(linkRelationshipLabels) as LinkRelationship[]).map((value) => (
                <option key={value} value={value}>
                  {linkRelationshipLabels[value]}
                </option>
              ))}
            </NativeSelect>
          </Field>

          {/*
            Stated because it is visible and would otherwise read as a failure: the panel is served
            from a projection refreshed on an event, so a link made here appears immediately from
            the create's own answer and is reconciled a moment later. What this sentence covers is
            the other direction — a link somebody else made, or one made from the documentation
            page, which arrives on the next refresh rather than now.
          */}
          <p className="m-0 text-sm text-ink-2">
            Links made elsewhere can take a minute to appear on this panel.
          </p>

          {unnamedFailure && (
            <div data-testid="CaseDetail.ExternalLink.Error">
            <Banner tone="bad"><span role="alert">
              {describeError(write.error).body}
            </span></Banner>
          </div>
          )}
        </div>
      <DialogFooter>
        <Button onClick={onClose} data-testid="CaseDetail.ExternalLink.Cancel">
          Cancel
        </Button>
        <Button
          variant="primary"
          disabled={write.isPending}
          onClick={submit}
          data-testid="CaseDetail.ExternalLink.Confirm"
        >
          {write.isPending ? 'Linking…' : 'Link'}
        </Button>
      </DialogFooter>
    </DialogContent>
    </Dialog>
  );
}
