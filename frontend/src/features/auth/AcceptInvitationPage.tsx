import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Skeleton, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link as RouterLink, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useAcceptInvitation, useInvitationPreview } from '../../api/queries';
import { useAuth } from '../../auth/useAuth';
import { Identifier } from '../../components/Identifier';
import { offerToSaveCredentials } from '../../lib/credentials';

/**
 * Redeeming an invitation — the other public route, and the only screen a stranger can reach.
 *
 * The emailed link is `/accept-invitation?token=<secret>`, which is the path the backend's
 * `tcms.invitation.accept-path` builds. Until this page existed the link worked against the
 * API and dead-ended in the SPA.
 *
 * ## Every failure is the same failure, on purpose
 *
 * Unknown token, expired, already redeemed, membership since removed, account since
 * deactivated — the API answers all five with a bodiless 404, because told apart they would
 * let anyone holding a token-shaped string learn which addresses exist on this instance (email
 * is unique instance-wide, so that spans every client). This page must not un-collapse them:
 * there is exactly one "this link no longer works" panel, and it does not speculate about why.
 *
 * ## Accepting does not sign you in
 *
 * The API answers 204, not a session. That is deliberate — one token-issuing path — so this
 * page finishes by sending the invitee to `/login` with their address carried in navigation
 * state, where signing in proves the password was stored as intended.
 */

const TestIdPrefix = 'AcceptInvitation';

/**
 * BCrypt only reads the first 72 **bytes**, and silently ignores the rest — two passphrases
 * sharing a 72-byte prefix would open the same account. Counted in bytes rather than
 * characters, or the limit lands in a different place for exactly the users whose script is
 * not Latin. The server enforces the same 12–72 window; this is the fast, local half of it.
 */
const encoder = new TextEncoder();
const byteLength = (value: string) => encoder.encode(value).length;

const PasswordSchema = z
  .object({
    password: z
      .string()
      .refine((value) => byteLength(value) >= 12, 'Use at least 12 characters.')
      .refine((value) => byteLength(value) <= 72, 'That is too long — 72 bytes is the maximum.'),
    confirmPassword: z.string(),
  })
  .refine((values) => values.password === values.confirmPassword, {
    message: 'Both entries must match.',
    path: ['confirmPassword'],
  });

type PasswordInput = z.infer<typeof PasswordSchema>;

export function AcceptInvitationPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const { user } = useAuth();

  const preview = useInvitationPreview(token);

  // Someone already signed in who follows an invitation link is a real case — a second company
  // invites an existing account. Sign-out first is the honest sequence: accepting changes which
  // memberships the session's token was issued against.
  if (user) return <Navigate to="/" replace />;

  return (
    <Box
      component="main"
      sx={{
        minHeight: '100%',
        display: 'grid',
        placeItems: 'center',
        px: 2,
        py: 6,
        bgcolor: 'background.default',
      }}
    >
      <title>Accept your invitation · TCMS</title>

      <Box sx={{ width: '100%', maxWidth: 440 }}>
        <Stack sx={{ mb: 4, gap: 0.5 }}>
          <Identifier variant="strong" sx={{ fontSize: '1.5rem', letterSpacing: '0.06em' }}>
            TCMS
          </Identifier>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Test case management
          </Typography>
        </Stack>

        <Box
          sx={{
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 1,
            bgcolor: 'background.paper',
            p: 3,
          }}
        >
          {token === '' || preview.isError ? (
            <UnusableLink />
          ) : preview.isPending ? (
            <Stack sx={{ gap: 2 }} aria-busy="true" data-testid={`${TestIdPrefix}.Loading`}>
              <Skeleton variant="text" width={220} height={32} />
              <Skeleton variant="text" width={280} />
              <Skeleton variant="rounded" height={56} />
              <Skeleton variant="rounded" height={40} />
            </Stack>
          ) : (
            <AcceptForm
              token={token}
              email={preview.data.email}
              displayName={preview.data.displayName}
              passwordRequired={preview.data.passwordRequired}
            />
          )}
        </Box>
      </Box>
    </Box>
  );
}

/**
 * The one rejection panel. No token, an unknown one, a spent one and a revoked membership all
 * land here reading identically — see the note at the top of the file.
 */
function UnusableLink() {
  return (
    <Stack sx={{ gap: 2 }} data-testid={`${TestIdPrefix}.Unusable`}>
      <Typography variant="h2" component="h1">
        This link no longer works
      </Typography>
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        Invitation links can be used once, and they expire. Ask whoever invited you to send a new
        one — if you have already set up your account, sign in below.
      </Typography>
      <Button
        component={RouterLink}
        to="/login"
        variant="contained"
        data-testid={`${TestIdPrefix}.SignInLink`}
        sx={{ alignSelf: 'flex-start' }}
      >
        Go to sign in
      </Button>
    </Stack>
  );
}

interface AcceptFormProps {
  token: string;
  email: string;
  displayName: string;
  passwordRequired: boolean;
}

function AcceptForm({ token, email, displayName, passwordRequired }: AcceptFormProps) {
  const navigate = useNavigate();
  const accept = useAcceptInvitation(token);

  /** Set when the *accept* call 404s — the token was fine at preview and is not any more. */
  const [gone, setGone] = useState(false);

  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors, isSubmitting },
  } = useForm<PasswordInput>({
    resolver: zodResolver(PasswordSchema),
    defaultValues: { password: '', confirmPassword: '' },
    mode: 'onBlur',
  });

  async function submit(password?: string) {
    try {
      await accept.mutateAsync(password === undefined ? {} : { password });
      /*
        Offer the password they just chose to the browser's manager, before sending them to sign in
        with it — this is the one moment in the product where a *new* password exists, so it is the
        moment worth capturing. Missing it means the invitee types it once here, then again on the
        very next screen, with nothing offering to remember it.

        Only when they actually set one: the no-password branch below is an existing account
        joining a second company, and storing its old password over again would offer to save a
        credential this page never saw.
      */
      if (password !== undefined) {
        void offerToSaveCredentials({ id: email, password, name: displayName });
      }
      // 204, not a session. Sign-in is the next step, and the address travels so the invitee
      // doesn't have to remember which of their addresses was invited.
      navigate('/login', { replace: true, state: { acceptedEmail: email } });
      return true;
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        setGone(true);
        return false;
      }
      if (error instanceof ApiError && error.status === 422) {
        // The server describes the password the caller just submitted — nothing about the
        // account or the instance — so its wording is safe to show, and it belongs on the field.
        setError('password', { type: 'server', message: error.detail });
        setFocus('password');
        return false;
      }
      setError('root', {
        type: 'server',
        message: "Couldn't set up your account. Check your connection and try again.",
      });
      return false;
    }
  }

  if (gone) return <UnusableLink />;

  const onSubmit = handleSubmit(async (data) => {
    await submit(data.password);
  });

  return (
    <Stack sx={{ gap: 2.5 }}>
      <Stack sx={{ gap: 0.5 }}>
        <Typography variant="h2" component="h1">
          Welcome, {displayName}
        </Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid={`${TestIdPrefix}.Email`}>
          {email}
        </Typography>
      </Stack>

      {errors.root && (
        <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Error`}>
          {errors.root.message}
        </Alert>
      )}

      {passwordRequired ? (
        <Box
          component="form"
          onSubmit={onSubmit}
          noValidate
          aria-busy={isSubmitting}
          data-testid={`${TestIdPrefix}.Form`}
          /* See `LoginPage` — declared for the password managers' heuristics, never followed. */
          method="post"
          action="/accept-invitation"
        >
          <Stack sx={{ gap: 2 }}>
            <Typography variant="body2">Choose a password to finish setting up your account.</Typography>

            {/*
              The username field, and it exists for the password manager rather than for the
              invitee — who cannot change it and has already read it in the heading above.

              A password form with no username field is the classic reason a manager declines to
              save, or saves the entry filed under no account at all, which then never autofills.
              Safari has no Credential Management API for passwords, so for iCloud Keychain this
              field *is* the mechanism. `readOnly` rather than `disabled`: a disabled input is not
              submitted and is skipped by the same heuristics this is here to satisfy.

              It also stops being a caption pretending to be data — the address the account is
              being created under now renders as the field it is.
            */}
            <TextField
              label="Email"
              type="email"
              name="email"
              autoComplete="username"
              value={email}
              fullWidth
              slotProps={{
                htmlInput: { readOnly: true, 'data-testid': `${TestIdPrefix}.EmailField` },
              }}
              helperText="Your invitation was sent to this address."
            />

            <TextField
              label="Password"
              type="password"
              autoComplete="new-password"
              fullWidth
              error={!!errors.password}
              helperText={errors.password?.message ?? 'At least 12 characters.'}
              slotProps={{
                htmlInput: { 'data-testid': `${TestIdPrefix}.PasswordField` },
                formHelperText: { role: errors.password ? 'alert' : undefined },
              }}
              {...register('password')}
            />

            <TextField
              label="Confirm password"
              type="password"
              autoComplete="new-password"
              fullWidth
              error={!!errors.confirmPassword}
              helperText={errors.confirmPassword?.message}
              slotProps={{
                htmlInput: { 'data-testid': `${TestIdPrefix}.ConfirmPasswordField` },
                formHelperText: { role: errors.confirmPassword ? 'alert' : undefined },
              }}
              {...register('confirmPassword')}
            />

            <Button
              type="submit"
              variant="contained"
              size="large"
              // The pending state is the double-submit guard: a token is single-use, and two
              // racing accepts would leave the loser rendering "this link no longer works" to
              // someone whose account was just created successfully.
              disabled={isSubmitting}
              data-testid={`${TestIdPrefix}.SubmitButton`}
            >
              {isSubmitting ? 'Setting up…' : 'Set password and continue'}
            </Button>
          </Stack>
        </Box>
      ) : (
        <Stack sx={{ gap: 2 }} data-testid={`${TestIdPrefix}.NoPasswordForm`}>
          <Typography variant="body2">
            You already have an account on this instance, so keep using your existing password.
            Accepting adds this company to it.
          </Typography>
          <Button
            variant="contained"
            size="large"
            disabled={accept.isPending}
            onClick={() => void submit(undefined)}
            data-testid={`${TestIdPrefix}.SubmitButton`}
          >
            {accept.isPending ? 'Joining…' : 'Accept invitation'}
          </Button>
        </Stack>
      )}
    </Stack>
  );
}
