import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useMemo, useState } from 'react';
import { ApiError } from '../../api/client';
import { useIssueApiToken, useProjects, useRoles } from '../../api/queries';
import type { IssuedApiToken } from '../../api/types';
import { describeError } from '../../lib/describeError';
import { CopyField } from '../../components/CopyField';

/**
 * Issuing an API token — **the only screen in this product that displays a credential**, and the
 * only one that has to be right about showing something exactly once.
 *
 * ## The reveal is a second step of this dialog, not a toast and not a row
 *
 * `POST /api-tokens` answers with the plaintext and nothing can ever answer with it again. So the
 * three places this obviously *could* go are all wrong:
 *
 *   - a **toast** dismisses itself, which for a value that cannot be recovered means the product
 *     destroys the thing it just made while the user is reading the form they filled in;
 *   - a **row in the list** would mean the secret entered the query cache — a store the whole app
 *     reads, kept alive by every other consumer of that key (`useIssueApiToken` says so too);
 *   - **closing on success** and mailing it later is the invitation flow's shape and there is no
 *     second delivery here.
 *
 * So the form is replaced in place by the secret, the dialog cannot be dismissed by accident from
 * that state, and its only exit is a button that says what it costs. The value lives in the
 * mutation's own `data` — memory belonging to this hook instance — and the dialog is mounted
 * conditionally by its opener, so closing it unmounts the component and the plaintext goes with
 * it. **Nothing persists it**: no cache write, no `localStorage`, no URL.
 *
 * ## The role filter is `scope !== 'SYSTEM'` and it is NOT `GrantRoleDialog`'s filter
 *
 * That dialog narrows to PROJECT-scope roles once a project is chosen, because a company-scope
 * grant pinned to one project is a contradiction. **A token is not a grant.** Pinning it to a
 * project is a hard *ceiling* on where the credential works, not a statement about the role's
 * scope, and the server refuses exactly two things — a SYSTEM-scope role, and the membership
 * baseline that never reaches the wire. Copying the neighbouring filter would hide roles the API
 * accepts, which is §62's unselectable-option defect arriving from the other side: an
 * administrator who cannot offer Company Admin to a project-pinned token has no way to learn the
 * product allows it.
 *
 * ## What the form does NOT ask
 *
 * There is no owner field, and its absence is a security property rather than a simplification:
 * the owner is the caller, a token's permissions are the role's **intersected** with the owner's
 * own, and a nominated owner would be a way to mint a credential more powerful than yourself by
 * naming somebody more powerful. The dialog states the intersection instead, because it is the
 * one thing an administrator cannot infer from the two selects — choosing "Company Admin" does
 * not necessarily produce a Company Admin token.
 */

/** `ApiTokenService.MAX_NAME`. Mirrored, not guessed — the server's message quotes the number. */
const MAX_NAME = 120;

/** The wide answer, distinct from an unset select. Not a uuid, so it cannot collide with one. */
const ALL_PROJECTS = 'ALL';

const TestId = 'Admin.TokenIssue';

type FieldName = 'name' | 'roleId' | 'expiresAt';

/**
 * `datetime-local` yields `2026-09-01T14:00` in the browser's zone; the contract wants an instant.
 * Converting through `Date` is what applies the offset — sending the literal string would post a
 * local wall-clock time as if it were UTC, so a token issued in Sofia would expire three hours
 * early and nothing on either side would say why.
 */
function toInstant(local: string): string {
  return new Date(local).toISOString();
}

export function IssueTokenDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const issue = useIssueApiToken();
  const roles = useRoles();
  const projects = useProjects();

  const [name, setName] = useState('');
  const [roleId, setRoleId] = useState('');
  const [projectId, setProjectId] = useState<string>(ALL_PROJECTS);
  const [expiresAt, setExpiresAt] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldName, string>>>({});
  const [formError, setFormError] = useState<string | undefined>();
  /*
    Held here rather than read from `issue.data` so the reveal cannot flicker away: a background
    refetch or a re-render that resets the mutation would take the credential off screen with no
    way to get it back. Once it is set, this component is the reveal.
  */
  const [issued, setIssued] = useState<IssuedApiToken | null>(null);

  const issuableRoles = useMemo(
    /*
      Every role the API accepts, and only those. See the header on why this is not the grant
      dialog's scope-dependent filter.

      DO NOT ADD `role.assignable` HERE. It is the filter the grant dialog and RoleReference both
      apply, and copying it into this one is the change that looks like consistency: Agent (V40) is
      `assignable = false` precisely because nobody is ever an Agent, and this form is the ONLY
      place in the product where it can be chosen. Adding the term makes the role unobtainable and
      nothing else on any screen changes.
    */
    () => (roles.data ?? []).filter((role) => role.scope !== 'SYSTEM'),
    [roles.data],
  );
  const selectedRole = issuableRoles.find((role) => role.id === roleId);

  useEffect(() => {
    if (open && !issued) setFieldErrors({});
  }, [open, issued]);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setFieldErrors({});
    setFormError(undefined);
    try {
      setIssued(
        await issue.mutateAsync({
          name: name.trim(),
          roleId,
          projectId: projectId === ALL_PROJECTS ? null : projectId,
          // Absent and blank are the same intent here — "no expiry" — and the contract spells it
          // `null`. An empty string on a date-time field is a 400 nobody typed.
          expiresAt: expiresAt ? toInstant(expiresAt) : null,
        }),
      );
    } catch (error) {
      // The server keys `Problem.errors` by the input schema's field names, so each message lands
      // on the control that caused it. A 403 has no field and goes to the form-level alert through
      // `describeError`, the one place an error becomes user-facing copy.
      if (error instanceof ApiError && error.fieldErrors) {
        const landed: Partial<Record<FieldName, string>> = {};
        for (const [field, messages] of Object.entries(error.fieldErrors)) {
          if (field === 'name' || field === 'roleId' || field === 'expiresAt') {
            landed[field] = messages.join(' ');
          }
        }
        if (Object.keys(landed).length > 0) {
          setFieldErrors(landed);
          return;
        }
      }
      setFormError(describeError(error).body);
    }
  };

  if (issued) {
    return (
      <Dialog
        open={open}
        /*
          No `onClose`, and this is the one dialog in the app that refuses one. Esc and a click on
          the backdrop are the two ways a person dismisses a dialog without meaning to, and here
          that destroys a value nothing can produce again. The Done button is the only exit and it
          says what pressing it costs.
        */
        fullWidth
        maxWidth="sm"
        data-testid={`${TestId}.Secret`}
      >
        <DialogTitle>Copy the token for {issued.token.name}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Alert severity="warning" role="alert" data-testid={`${TestId}.Secret.Warning`}>
              This is the only time this token is shown. Store it in your CI system's secret store
              now — it cannot be recovered, and nothing here can show it again.
            </Alert>

            <CopyField
              label="Token"
              value={issued.secret}
              testId={`${TestId}.Secret.Value`}
              /*
                Rendered as text rather than a masked field. A reveal that hides what it just
                created makes the person paste blind into a secret store and gives them nothing to
                check against afterwards — and the `tokenPrefix` on the row below exists precisely
                so this value can be matched later. It is on screen because they asked for it.
              */
            />

            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
              The list will show its first 12 characters —{' '}
              <strong>{issued.token.tokenPrefix}</strong> — so you can tell which CI configuration
              this row belongs to. If you lose the token, revoke this one and issue another.
            </Typography>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button
            variant="contained"
            onClick={onClose}
            data-testid={`${TestId}.Secret.Done`}
          >
            I've stored it — close
          </Button>
        </DialogActions>
      </Dialog>
    );
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={(event) => void submit(event)} noValidate aria-busy={issue.isPending}>
        <DialogTitle>Issue an API token</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {formError && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {formError}
              </Alert>
            )}

            <TextField
              label="Name"
              required
              autoFocus
              value={name}
              onChange={(event) => setName(event.target.value)}
              error={!!fieldErrors.name}
              helperText={
                fieldErrors.name ??
                'What this token is for — "Nightly regression, staging". You will read this when deciding which one to revoke.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Name`, maxLength: MAX_NAME } }}
            />

            <TextField
              select
              label="Role"
              required
              value={roleId}
              onChange={(event) => setRoleId(event.target.value)}
              error={!!fieldErrors.roleId}
              helperText={
                fieldErrors.roleId ??
                (selectedRole?.description ??
                  'The role the token carries — not your own. Automation Service is the one built for CI.')
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Role` } }}
            >
              {issuableRoles.map((role) => (
                <MenuItem key={role.id} value={role.id}>
                  {role.name}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select
              label="Where it works"
              value={projectId}
              onChange={(event) => setProjectId(event.target.value)}
              helperText={
                projectId === ALL_PROJECTS
                  ? 'Every project you can reach, including ones created later.'
                  : 'A hard limit: the token is inert on every other project.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Project` } }}
            >
              <MenuItem value={ALL_PROJECTS}>Every project I can reach</MenuItem>
              {(projects.data ?? []).map((project) => (
                <MenuItem key={project.id} value={project.id}>
                  {project.name}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              type="datetime-local"
              label="Expires"
              value={expiresAt}
              onChange={(event) => setExpiresAt(event.target.value)}
              error={!!fieldErrors.expiresAt}
              helperText={
                fieldErrors.expiresAt ??
                /*
                  Empty is the default and the copy says so rather than leaving a blank field
                  reading as one somebody forgot. §116 made expiry optional deliberately: a
                  credential that dies mid-release without anybody deciding to withdraw it is an
                  outage, and the withdrawal that IS a decision is Revoke.
                */
                'Optional. Leave it empty and the token lasts until it is revoked.'
              }
              slotProps={{
                inputLabel: { shrink: true },
                htmlInput: { 'data-testid': `${TestId}.Expires` },
              }}
            />

            {/*
              Said before the token exists rather than discovered when a pipeline 403s. The
              intersection is the one rule an administrator cannot see from the selects above —
              picking a powerful role does not produce a powerful token if the issuer's own grants
              do not reach that far.
            */}
            <Alert severity="info" data-testid={`${TestId}.Hint`}>
              The token can do what this role allows <em>and</em> what you can already do —
              whichever is narrower. It never exceeds your own access, and it shrinks if yours does.
            </Alert>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={issue.isPending || !name.trim() || !roleId}
            data-testid={`${TestId}.Submit`}
          >
            {issue.isPending ? 'Issuing…' : 'Issue token'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
