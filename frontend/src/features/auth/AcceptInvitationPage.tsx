import { zodResolver } from '@hookform/resolvers/zod';
import { Check } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link as RouterLink, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useAcceptInvitation, useInvitationPreview, useSocialProviders } from '../../api/queries';
import { useAuth } from '../../auth/useAuth';
import { Banner } from '../../components/kit';
import { Button, Input, Skeleton } from '../../components/ui';
import { offerToSaveCredentials } from '../../lib/credentials';
import {
  FieldError,
  FieldHelp,
  FieldLabel,
  ThresholdLayout,
  ThresholdLead,
  ThresholdTitle,
} from './ThresholdLayout';

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
 * ## Accepting does not sign you in — but this page does, when it can (V58)
 *
 * The API still answers 204, not a session — one token-issuing path, untouched. When the
 * acceptance CREATED the account's credential, though, this page momentarily holds both halves
 * of a valid one: the invited address from the preview and the password just chosen. So it
 * follows the 204 with the ordinary `POST /auth/login` itself — through the same `login` seam
 * the sign-in screen uses, which even preserves the 204's stated rationale, since the login
 * round-trip is what proves the password was stored as intended — and lands the new session on
 * the security page's first-run offer when any social provider is configured, or on the app
 * when none is. A failed auto-login falls back to `/login` carrying the destination, never a
 * scarier state: the account exists and the acceptance succeeded.
 *
 * An existing account joining a second company typed no password here, so there is nothing to
 * auto-login with — they keep the sign-in hop, address carried in navigation state.
 *
 * ## What the render asks for and the contract cannot answer
 *
 * `renders/08-threshold.html` frame C puts the invitation's *story* in the left column — who
 * invited you, into which company, as which role, with which projects, and what that role can and
 * cannot do. `GET /auth/invitations/{token}` returns three fields: `email`, `displayName` and
 * `passwordRequired`. So the story column carries the invitee's own name and nothing invented;
 * the rest of that copy needs a contract change, not a component.
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

  const unusable = token === '' || preview.isError;

  return (
    <ThresholdLayout
      headline={
        !unusable && preview.data
          ? `${preview.data.displayName}, you have been invited.`
          : 'Every case is a document, and every result is evidence.'
      }
      blurb={
        !unusable && preview.data
          ? 'Setting a password here creates your account on this instance. From then on this address is how you sign in.'
          : undefined
      }
      footnote={`This link can be used once · ${window.location.host}`}
    >
      <title>Accept your invitation · TCMS</title>

      {unusable ? (
        <UnusableLink />
      ) : preview.isPending ? (
        <div className="flex flex-col gap-4" aria-busy="true" data-testid={`${TestIdPrefix}.Loading`}>
          <Skeleton className="h-8 w-[220px]" />
          <Skeleton className="h-[11px] w-[280px]" />
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : (
        <AcceptForm
          token={token}
          email={preview.data.email}
          displayName={preview.data.displayName}
          passwordRequired={preview.data.passwordRequired}
        />
      )}
    </ThresholdLayout>
  );
}

/**
 * The one rejection panel. No token, an unknown one, a spent one and a revoked membership all
 * land here reading identically — see the note at the top of the file.
 */
function UnusableLink() {
  return (
    <div data-testid={`${TestIdPrefix}.Unusable`}>
      <ThresholdTitle>This link no longer works</ThresholdTitle>
      <ThresholdLead>
        Invitation links can be used once, and they expire. Ask whoever invited you to send a new
        one — if you have already set up your account, sign in below.
      </ThresholdLead>
      <Button asChild variant="primary" className="min-h-[38px] w-full" data-testid={`${TestIdPrefix}.SignInLink`}>
        <RouterLink to="/login">Go to sign in</RouterLink>
      </Button>
    </div>
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
  const { login } = useAuth();
  /** Decides only where the auto-login LANDS — the security page's offer, or the app. */
  const social = useSocialProviders();

  /** Set when the *accept* call 404s — the token was fine at preview and is not any more. */
  const [gone, setGone] = useState(false);

  const {
    register,
    watch,
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

        /*
          The page holds both halves of a valid credential — the invited address and the
          password the server just accepted — so the sign-in hop is removed here, in the SPA,
          while the accept endpoint keeps answering 204 with no session (see the header).
        */
        const destination = (social.data?.providers.length ?? 0) > 0
          ? '/account/security?welcome=1'
          : '/';
        try {
          await login(email, password, true);
          navigate(destination, { replace: true });
        } catch {
          /*
            The account exists and the acceptance succeeded — this branch is only the login
            round-trip failing — so the fallback is the ordinary sign-in screen, carrying the
            destination in the seam LoginPage already honours (`from`), with no scarier copy.
          */
          navigate('/login', {
            replace: true,
            state: { acceptedEmail: email, from: destination },
          });
        }
        return true;
      }
      // An existing account joining a second company: no password was typed, so there is
      // nothing to auto-login with. 204, then sign-in, address carried so the invitee doesn't
      // have to remember which of their addresses was invited.
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

  const longEnough = byteLength(watch('password')) >= 12;

  return (
    <>
      <ThresholdTitle>{passwordRequired ? 'Set your password' : 'Accept your invitation'}</ThresholdTitle>
      <ThresholdLead>
        Signing in as{' '}
        <span className="font-mono text-[12.5px] tracking-[-.01em] text-ink-2" data-testid={`${TestIdPrefix}.Email`}>
          {email}
        </span>
      </ThresholdLead>

      {errors.root && (
        <div role="alert" className="mb-4">
          <Banner tone="bad" data-testid={`${TestIdPrefix}.Error`}>
            <span>{errors.root.message}</span>
          </Banner>
        </div>
      )}

      {passwordRequired ? (
        <form
          onSubmit={onSubmit}
          noValidate
          aria-busy={isSubmitting}
          data-testid={`${TestIdPrefix}.Form`}
          /* See `LoginPage` — declared for the password managers' heuristics, never followed. */
          method="post"
          action="/accept-invitation"
        >
          {/*
            The username field, and it exists for the password manager rather than for the
            invitee — who cannot change it and has already read it in the lead above.

            A password form with no username field is the classic reason a manager declines to
            save, or saves the entry filed under no account at all, which then never autofills.
            Safari has no Credential Management API for passwords, so for iCloud Keychain this
            field *is* the mechanism. `readOnly` rather than `disabled`: a disabled input is not
            submitted and is skipped by the same heuristics this is here to satisfy.
          */}
          <div className="mb-[15px]">
            <FieldLabel htmlFor="email">Email</FieldLabel>
            <Input
              type="email"
              name="email"
              autoComplete="username"
              id="email"
              value={email}
              readOnly
              className="min-h-[38px]"
              aria-describedby="email-help"
              data-testid={`${TestIdPrefix}.EmailField`}
            />
            <FieldHelp id="email-help">Your invitation was sent to this address.</FieldHelp>
          </div>

          <div className="mb-[15px]">
            <FieldLabel htmlFor="new-password">Password</FieldLabel>
            <Input
              type="password"
              autoComplete="new-password"
              id="new-password"
              className="min-h-[38px]"
              aria-invalid={!!errors.password}
              aria-describedby={errors.password ? 'new-password-error' : 'new-password-rule'}
              data-testid={`${TestIdPrefix}.PasswordField`}
              {...register('password')}
            />
            {errors.password ? (
              <FieldError id="new-password-error">{errors.password.message}</FieldError>
            ) : (
              /*
                The render's note: password rules are checks that tick, not a paragraph. One
                requirement rather than the drawing's two — the second, "not a password seen in a
                known breach", is a check neither this app nor the contract performs, and drawing a
                tick for it would be a claim the product cannot make.
              */
              <p
                id="new-password-rule"
                className={
                  longEnough
                    ? 'mt-[5px] flex items-center gap-[7px] text-[11.5px] text-[color:var(--color-passed)]'
                    : 'mt-[5px] flex items-center gap-[7px] text-[11.5px] text-ink-4'
                }
              >
                <Check aria-hidden className="size-[11px] shrink-0" />
                At least 12 characters
              </p>
            )}
          </div>

          <div className="mb-[15px]">
            <FieldLabel htmlFor="confirm-new-password">Confirm password</FieldLabel>
            <Input
              type="password"
              autoComplete="new-password"
              id="confirm-new-password"
              className="min-h-[38px]"
              aria-invalid={!!errors.confirmPassword}
              aria-describedby={errors.confirmPassword ? 'confirm-new-password-error' : undefined}
              data-testid={`${TestIdPrefix}.ConfirmPasswordField`}
              {...register('confirmPassword')}
            />
            {errors.confirmPassword && (
              <FieldError id="confirm-new-password-error">{errors.confirmPassword.message}</FieldError>
            )}
          </div>

          <Button
            type="submit"
            variant="primary"
            className="min-h-[38px] w-full"
            // The pending state is the double-submit guard: a token is single-use, and two
            // racing accepts would leave the loser rendering "this link no longer works" to
            // someone whose account was just created successfully.
            disabled={isSubmitting}
            data-testid={`${TestIdPrefix}.SubmitButton`}
          >
            {isSubmitting ? 'Setting up…' : 'Create the account and sign in'}
          </Button>
        </form>
      ) : (
        <div data-testid={`${TestIdPrefix}.NoPasswordForm`}>
          <p className="m-0 mb-4 text-[13px] leading-[1.6] text-ink-2">
            You already have an account on this instance, so keep using your existing password.
            Accepting adds this company to it.
          </p>
          <Button
            variant="primary"
            className="min-h-[38px] w-full"
            disabled={accept.isPending}
            onClick={() => void submit(undefined)}
            data-testid={`${TestIdPrefix}.SubmitButton`}
          >
            {accept.isPending ? 'Joining…' : 'Accept invitation'}
          </Button>
        </div>
      )}
    </>
  );
}
