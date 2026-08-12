import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  MenuItem,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  DialogTitle,
} from '@mui/material';
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
      write.reset();
      setKind('EXTERNAL_GIT');
      setRelationship('VERIFIES');
      setRepo('');
      setPath('');
      setLineStart('');
      setLineEnd('');
      setCommitSha('');
      setUrl('');
    }
    // `write` is a stable mutation object; depending on it would reset on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, caseId]);

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
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid="CaseDetail.ExternalLink">
      <DialogTitle>Link this case to something outside TCMS</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 0.5 }}>
          <ToggleButtonGroup
            exclusive
            size="small"
            value={kind}
            onChange={(_event, chosen: Kind | null) => chosen && setKind(chosen)}
            aria-label="What is being linked"
          >
            <ToggleButton value="EXTERNAL_GIT" data-testid="CaseDetail.ExternalLink.Git">
              Code
            </ToggleButton>
            <ToggleButton value="EXTERNAL_URL" data-testid="CaseDetail.ExternalLink.Url">
              A URL
            </ToggleButton>
          </ToggleButtonGroup>

          {kind === 'EXTERNAL_GIT' ? (
            <>
              <TextField
                label="Repository"
                value={repo}
                onChange={(event) => setRepo(event.target.value)}
                placeholder="acme/checkout-service"
                error={fieldError('repo') !== undefined}
                helperText={fieldError('repo')}
                slotProps={{ htmlInput: { 'data-testid': 'CaseDetail.ExternalLink.Repo' } }}
              />
              <TextField
                label="File"
                value={path}
                onChange={(event) => setPath(event.target.value)}
                placeholder="src/main/java/com/acme/Checkout.java"
                error={fieldError('path') !== undefined}
                helperText={fieldError('path')}
                slotProps={{ htmlInput: { 'data-testid': 'CaseDetail.ExternalLink.Path' } }}
              />
              <Stack direction="row" spacing={2}>
                <TextField
                  label="First line"
                  value={lineStart}
                  onChange={(event) => setLineStart(event.target.value)}
                  type="number"
                  error={fieldError('lineStart') !== undefined}
                  helperText={fieldError('lineStart') ?? 'Optional — leave both empty for the whole file.'}
                  slotProps={{ htmlInput: { 'data-testid': 'CaseDetail.ExternalLink.LineStart' } }}
                />
                <TextField
                  label="Last line"
                  value={lineEnd}
                  onChange={(event) => setLineEnd(event.target.value)}
                  type="number"
                  error={fieldError('lineEnd') !== undefined}
                  helperText={fieldError('lineEnd')}
                  slotProps={{ htmlInput: { 'data-testid': 'CaseDetail.ExternalLink.LineEnd' } }}
                />
              </Stack>
              <TextField
                label="Commit"
                value={commitSha}
                onChange={(event) => setCommitSha(event.target.value)}
                placeholder="4f9c2b1"
                error={fieldError('commitSha') !== undefined}
                helperText={
                  fieldError('commitSha') ??
                  'The lines are pinned to this commit. Without one they point at different code after the next push — a branch name is refused for that reason.'
                }
                slotProps={{ htmlInput: { 'data-testid': 'CaseDetail.ExternalLink.Sha' } }}
              />
            </>
          ) : (
            <TextField
              label="Address"
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              placeholder="https://specs.example.com/refunds"
              error={fieldError('url') !== undefined}
              helperText={fieldError('url') ?? 'Must start with http:// or https://.'}
              slotProps={{ htmlInput: { 'data-testid': 'CaseDetail.ExternalLink.Address' } }}
            />
          )}

          {/*
            Offered rather than defaulted, though the server defaults it. The three readings are the
            field's whole value — LinkRelationship's own header says a link recorded as VERIFIES
            when the case merely REFERENCES the statement is what turns a coverage number into a
            false claim — and a form that always sent the default would make the other two
            unreachable from this product, which is the shape this slice exists to close.
          */}
          <TextField
            select
            label="What this case does with it"
            value={relationship}
            onChange={(event) => setRelationship(event.target.value as LinkRelationship)}
            error={fieldError('relationship') !== undefined}
            helperText={fieldError('relationship') ?? linkRelationshipHelp[relationship]}
            slotProps={{ htmlInput: { 'data-testid': 'CaseDetail.ExternalLink.Relationship' } }}
          >
            {(Object.keys(linkRelationshipLabels) as LinkRelationship[]).map((value) => (
              <MenuItem key={value} value={value}>
                {linkRelationshipLabels[value]}
              </MenuItem>
            ))}
          </TextField>

          {/*
            Stated because it is visible and would otherwise read as a failure: the panel is served
            from a projection refreshed on an event, so a link made here appears immediately from
            the create's own answer and is reconciled a moment later. What this sentence covers is
            the other direction — a link somebody else made, or one made from the documentation
            page, which arrives on the next refresh rather than now.
          */}
          <DialogContentText variant="body2">
            Links made elsewhere can take a minute to appear on this panel.
          </DialogContentText>

          {unnamedFailure && (
            <Alert severity="error" role="alert" data-testid="CaseDetail.ExternalLink.Error">
              {describeError(write.error).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="CaseDetail.ExternalLink.Cancel">
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={write.isPending}
          onClick={submit}
          data-testid="CaseDetail.ExternalLink.Confirm"
        >
          {write.isPending ? 'Linking…' : 'Link'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
