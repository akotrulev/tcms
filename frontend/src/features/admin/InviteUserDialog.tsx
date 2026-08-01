import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useInviteUser } from '../../api/queries';
import { describeError } from '../../lib/describeError';

/**
 * Inviting a colleague — the affordance that ends "a provisioned company plus hand-written SQL"
 * for its *people*, the way `GrantRoleDialog` ended it for their access.
 *
 * Until this existed, `provision-company` produced exactly one account and there was no way over
 * HTTP to add a second person to a company at all. The product could show you who was on a
 * project (§54) and let you staff one (§39), and the only way to get somebody into the list in
 * the first place was an operator with a psql prompt.
 *
 * ## Two fields, and the second one is not the person's name
 *
 * `displayName` is **this company's** name for them (§6u), stored on the membership rather than
 * on the account, because the same contractor is legitimately "A. Kotrulev" to one client and
 * "Aleks (Vendor)" to another. The helper text says so — an admin who reads it as "their real
 * name" will type one, which is right for the common case and quietly wrong for the case the
 * field exists for.
 *
 * ## This opener IS gated, unlike the one beside it
 *
 * `GrantRoleDialog`'s opener is the app's one deliberately ungated button, because the permission
 * its server call checks is chosen by a field inside that form. Here it is known before anything
 * renders — `company:members`, company scope, always — so §31's disable-don't-hide rule applies
 * in its ordinary form and the gate sits on the button that opens this.
 *
 * ## What the response says, and why the dialog stays open to say it
 *
 * Three outcomes, not two, and the third is the one the live pass had to find:
 *
 *   - `emailed` — a link is on its way. The plain success; the dialog closes.
 *   - `issued && !emailed` — a link was minted and **this install has no SMTP relay**, so an
 *     operator has to carry it across by hand.
 *   - `!issued` — nothing was minted. Either they were already invited (their existing link is
 *     untouched, deliberately: a fresh token would invalidate the one in their inbox) or they are
 *     already a member, which `expiresAt` tells apart.
 *
 * The first draft read the middle one off `emailed` alone, which is right on an install with no
 * relay and **wrong on every second click of one that has one** — it told an administrator with
 * working mail that their mail was broken. That is why `issued` is on the wire at all.
 *
 * A toast would flatten all three into "Invited", which is how somebody ends up waiting for a
 * message nobody sent — so the dialog reports them in place and closes only on the first.
 */

/** Mirrors `UserInvitationInput` in the contract, and `MembershipInviteService`'s own bounds. */
const MAX_EMAIL = 320;
const MAX_DISPLAY_NAME = 200;

const InviteSchema = z.object({
  // Deliberately as permissive as the server's own check. A stricter client pattern rejects
  // addresses that work, and the form would then be refusing something the API accepts — the
  // disagreement is invisible until somebody with an unusual address cannot be invited.
  email: z
    .string()
    .min(1, 'Enter the email address to invite.')
    .max(MAX_EMAIL, 'That email address is too long.')
    .regex(/^[^@\s]+@[^@\s]+\.[^@\s]+$/, 'Enter a valid email address.'),
  displayName: z
    .string()
    .min(1, 'Enter the name to show for this person.')
    .max(MAX_DISPLAY_NAME, `Keep the name to ${MAX_DISPLAY_NAME} characters or fewer.`),
});

type InviteFormInput = z.infer<typeof InviteSchema>;

const TestId = 'Admin.Invite';

type FormField = 'email' | 'displayName';

/**
 * Maps a rejected invitation onto the form.
 *
 * The server keys its `Problem.errors` by the input schema's field names, so both land on the
 * input that caused them. A 403 has no field and goes to the form-level alert through
 * `describeError`, the one place an error becomes user-facing copy.
 */
function applyServerError(
  error: unknown,
  setError: (field: FormField | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if (field === 'email' || field === 'displayName') {
        setError(field, messages.join(' '));
        landed = true;
      }
    }
    if (landed) return;
  }
  setError('root', describeError(error).body);
}

/**
 * No `onInvited` callback, unlike the create dialogs beside it. Those hand back a row the caller
 * navigates to; there is nowhere to navigate here, and the people list refreshes itself from the
 * mutation's own invalidation. An unused seam is one more thing to keep working.
 */
export function InviteUserDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const invite = useInviteUser();
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors },
  } = useForm<InviteFormInput>({
    resolver: zodResolver(InviteSchema),
    defaultValues: { email: '', displayName: '' },
    mode: 'onBlur',
  });

  /*
    Focus only — no `reset`. The dialog is conditionally rendered by its opener, so it mounts fresh
    each time and `defaultValues` already carries the right values. Resetting here is what wiped
    the suite dialog's server errors before anyone could read them (handoff §30).
  */
  useEffect(() => {
    if (open) setFocus('email');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      const invitation = await invite.mutateAsync({
        email: values.email.trim(),
        displayName: values.displayName.trim(),
      });
      // Held open when there is something to read that the caller did not ask for: nothing was
      // sent, or nothing could be. See the header — the alternative is a success toast over an
      // invitation that is sitting in a log file.
      if (invitation.emailed) onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  const outcome = invite.data;

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={invite.isPending}>
        <DialogTitle>Invite a person</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {errors.root.message}
              </Alert>
            )}

            {outcome && !outcome.emailed && (
              <Alert severity="warning" role="status" data-testid={`${TestId}.NotEmailed`}>
                {/*
                  Three states, not two, and reading them off `emailed` alone is the defect the
                  live pass found: on an install WITH a relay, a second click answers
                  `emailed: false` and the copy claimed the mail was broken. `issued` is what
                  separates "there was nowhere to send it" from "there was nothing to send".
                */}
                {outcome.issued
                  ? `${outcome.user.displayName} is invited, but this instance has no mail relay ` +
                    'configured — nothing was sent. Ask your operator for the activation link.'
                  : outcome.expiresAt
                    ? `${outcome.user.displayName} was already invited, so their existing link is ` +
                      'still valid and nothing new was sent.'
                    : `${outcome.user.displayName} is already a member here, so nothing was sent.`}
              </Alert>
            )}

            <TextField
              label="Email"
              type="email"
              required
              autoComplete="off"
              error={!!errors.email}
              helperText={
                errors.email?.message ?? 'Where the activation link goes. They choose their own password.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Email`, maxLength: MAX_EMAIL } }}
              {...register('email')}
            />

            <TextField
              label="Name in this company"
              required
              autoComplete="off"
              error={!!errors.displayName}
              helperText={
                errors.displayName?.message ??
                'How colleagues here will see them — only this company sees this name.'
              }
              slotProps={{
                htmlInput: { 'data-testid': `${TestId}.DisplayName`, maxLength: MAX_DISPLAY_NAME },
              }}
              {...register('displayName')}
            />

            {/*
              Said before the invitation is sent rather than discovered afterwards. Membership
              conveys authentication and zero project visibility (roles spec §A1), so an admin who
              stops here has added somebody who signs in to an empty product and reasonably
              concludes it is broken.
            */}
            <Alert severity="info" data-testid={`${TestId}.Hint`}>
              They will be able to sign in and see nothing until you grant them a role.
            </Alert>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            {outcome ? 'Done' : 'Cancel'}
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={invite.isPending}
            data-testid={`${TestId}.Submit`}
          >
            {invite.isPending ? 'Inviting…' : 'Send invitation'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
