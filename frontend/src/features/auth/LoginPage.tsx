import { zodResolver } from '@hookform/resolvers/zod';
import type { ChangeEvent, FocusEvent } from 'react';
import { useEffect, useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { ApiError, BASE } from '../../api/client';
import { useDirectoryLogin, useSocialProviders, useSsoProviders } from '../../api/queries';
import type { SsoProviderChoice } from '../../api/types';
import { Banner } from '../../components/kit';
import { Button, Input, Switch } from '../../components/ui';
import { useAuth } from '../../auth/useAuth';
import { offerToSaveCredentials } from '../../lib/credentials';
import { socialProviderLabels } from '../../lib/labels';
import { rememberSsoReturnPath } from './ssoReturnPath';
import {
  FieldError,
  FieldLabel,
  OrRule,
  ThresholdFootnote,
  ThresholdLayout,
  ThresholdLead,
  ThresholdTitle,
} from './ThresholdLayout';

/**
 * One Zod schema, one inferred type. The same schema would validate on the server if this
 * app had one — no parallel hand-maintained rules.
 */
const LoginSchema = z.object({
  email: z.string().min(1, 'Enter your email address.').email('Enter an email address like name@company.com.'),
  password: z.string().min(1, 'Enter your password.'),
  rememberMe: z.boolean(),
});

type LoginInput = z.infer<typeof LoginSchema>;

const TestIdPrefix = 'Login';

/**
 * One sentence for every rejected sign-in, and it is this screen's job to keep it that way.
 *
 * The API answers **401 with an empty body** on purpose: unknown address, wrong password,
 * not-yet-activated and deactivated are one failure, because told apart they turn this form
 * into a directory of who exists on the instance — and email is unique instance-wide, so that
 * directory spans every client. Rendering `error.detail` here would have shown whatever reason
 * phrase the transport happened to supply ("Unauthorized"), which is both unhelpful and the
 * wrong shape of answer. Anything that is *not* a rejection — a network fault, a 500 — reads
 * differently, because that difference is about our service, not about the account.
 */
function describeSignInFailure(error: unknown): string {
  if (error instanceof ApiError && error.status === 401) {
    return "That email and password don't match an account.";
  }
  return "Couldn't sign you in. Check your connection and try again.";
}

export function LoginPage() {
  const { user, login, adoptSession } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  /**
   * The directory whose password this screen is currently collecting, or null for the local form.
   *
   * An LDAP provider is a MODE SWITCH rather than a navigation — there is no third party to send
   * the browser to, which is the whole difference between the two kinds (§223 D1). One pair of
   * inputs, not two: the email is already typed and is what discovery routed on, so directory mode
   * reuses both controls rather than adding a second password field that differs only by label.
   */
  const [directory, setDirectory] = useState<SsoProviderChoice | null>(null);
  const directoryLogin = useDirectoryLogin();

  const navState = location.state as {
    from?: string;
    acceptedEmail?: string;
    ssoFailed?: boolean;
  } | null;

  /**
   * Which buttons this address reaches, asked on blur.
   *
   * **Every unhappy answer renders as nothing at all**, and that is the point rather than an
   * oversight: `POST /auth/providers` answers 200 with an empty list for an unclaimed domain, a
   * disabled provider and a malformed address alike, because a sign-in surface must not be an
   * oracle for which companies exist here (§6r). A screen that said "no single sign-on for that
   * domain" would hand back exactly the fact the endpoint spends its whole design withholding — so
   * an empty list, a failed request and a question never asked all look identical below.
   */
  const discovery = useSsoProviders();
  const providers = discovery.data?.providers ?? [];

  /**
   * The instance-level social buttons (V58) — unlike discovery these are not an oracle for
   * anything: the answer is operator configuration, identical for every caller, so they render
   * unconditionally rather than per address. An empty answer and a failed read both render as
   * no section at all, because a sign-in screen with a "social sign-in is broken" banner tells
   * an anonymous caller something and helps nobody.
   */
  const social = useSocialProviders();
  const socialProviders = social.data?.providers ?? [];

  /** Set when {@link SsoCallbackPage} sent them back. One sentence, no cause — see that file. */
  const ssoFailed = navState?.ssoFailed === true;

  /**
   * Discovery runs on blur, not per keystroke: the endpoint takes a whole address, and asking on
   * every character posts a stranger's half-typed address to the server a dozen times.
   */
  const onEmailBlur = (event: FocusEvent<HTMLInputElement>) => {
    const email = event.target.value.trim();
    if (email.includes('@')) discovery.mutate(email);
  };

  /**
   * Editing the address takes the buttons down until the next blur asks again. Without this the
   * screen went on offering one address's providers while a different address sat in the field —
   * a button that would start a sign-in the person in front of it did not ask for.
   */
  const onEmailChange = () => {
    if (!discovery.isIdle) discovery.reset();
    // The same fact for the mode switch: a directory chosen for one address must not collect a
    // different address's password.
    setDirectory(null);
  };

  /**
   * Set when they arrived straight from redeeming an invitation. Accepting answers 204 rather
   * than a session (one token-issuing path), so this screen has to explain why they are being
   * asked to sign in immediately after choosing a password — otherwise it reads as the accept
   * having failed.
   */
  const acceptedEmail = navState?.acceptedEmail;

  /**
   * A prefilled address never blurs, so blur alone would leave the one person who arrived with
   * their email already in the field — the just-accepted invitee — as the one person never shown
   * their company's sign-in buttons. Asked once on mount; the ref is for StrictMode's second
   * effect pass, which must not post the address twice.
   */
  const askedForPrefill = useRef(false);
  const mutate = discovery.mutate;
  useEffect(() => {
    if (askedForPrefill.current) return;
    askedForPrefill.current = true;
    if (acceptedEmail?.includes('@')) mutate(acceptedEmail);
  }, [acceptedEmail, mutate]);

  const {
    register,
    control,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors, isSubmitting },
  } = useForm<LoginInput>({
    resolver: zodResolver(LoginSchema),
    defaultValues: {
      /*
        Blank unless the invitee just accepted, and never a seeded address. This used to prefill
        a fixture account under the mock API; against the real backend that is worse than
        nothing, because it prefills an account that does not exist on the instance.
      */
      email: acceptedEmail ?? '',
      password: '',
      rememberMe: true,
    },
    // Feedback on blur, not per keystroke — validating as someone types an email tells
    // them it's invalid before they've finished typing it.
    mode: 'onBlur',
  });

  // Already signed in: don't render a login form behind a valid session.
  if (user) return <Navigate to="/" replace />;

  /** Where the auth gate sent them from, so sign-in returns them there. */
  const from = navState?.from ?? '/';

  const onSubmit = handleSubmit(async (data) => {
    try {
      if (directory) {
        /*
          The directory half. The endpoint answers AuthResult — the same body local sign-in
          returns — and `adoptSession` is the seam that writes it, so `tcms.token` still has
          exactly one writer. The password is NOT offered to the browser's manager: it is the
          customer's directory credential, not an account on this product.
        */
        const result = await directoryLogin.mutateAsync({
          providerId: directory.id,
          email: data.email,
          password: data.password,
          rememberMe: data.rememberMe,
        });
        await adoptSession(result.token);
        navigate(from, { replace: true });
        return;
      }
      const signedIn = await login(data.email, data.password, data.rememberMe);
      /*
        Offer the credential to the browser's password manager, and only now — the contract of the
        Credential Management API is "these worked", so a store before the await would teach the
        manager a password the server had not accepted yet, which then autofills forever.

        Not awaited. The prompt is the browser's and it survives the client-side route change below
        because the document does not unload; awaiting it would hold the user on the sign-in form
        for as long as they took to answer a dialog that is not ours.
      */
      void offerToSaveCredentials({
        id: data.email,
        password: data.password,
        // The label the manager's own account picker shows. The accept-invitation form has always
        // passed one; omitting it here filed sign-in-saved entries under a blank name while
        // signup-saved ones read properly, for no reason beyond the auth API not returning a user.
        name: signedIn.displayName,
      });
      /*
        And this line is the mechanism, not the one above it — `navigate` is a `replaceState` that
        unmounts this route, which is exactly the "emulate navigation and remove the password form"
        that Google documents as how a password manager learns a fetch-driven sign-in succeeded.
      */
      navigate(from, { replace: true });
    } catch (error) {
      if (error instanceof ApiError && error.hasFieldErrors) {
        for (const [field, messages] of Object.entries(error.fieldErrors ?? {})) {
          setError(field as keyof LoginInput, { type: 'server', message: messages.join(' ') });
        }
        return;
      }
      setError('root', {
        type: 'server',
        message: describeSignInFailure(error),
      });
      setFocus('password');
      /*
        A rejected local sign-in is the one other moment somebody has told this screen a whole
        address without blurring the field: a password manager fills both inputs and submits. At a
        company whose accounts exist only through its issuer, every local attempt 401s — so this is
        exactly when the person needs their buttons, and staying silent here left them with a wrong
        sentence and no way in. Same endpoint, same silence about every unhappy answer.
      */
      if (discovery.isIdle && data.email.includes('@')) discovery.mutate(data.email);
    }
  });

  const emailField = register('email');
  const passwordLabel = directory ? 'Directory password' : 'Password';

  return (
    <ThresholdLayout
      headline="Every case is a document, and every result is evidence."
      blurb="A self-hosted test case management system. Write the procedure in the words your team already uses, run it, and keep what happened — with the version that ran, the person who ran it, and the defect it found."
      footnote={`${window.location.host} · ${__APP_VERSION__}`}
    >
      <title>Sign in · TCMS</title>

      <form
        onSubmit={onSubmit}
        noValidate
        /*
          `method`/`action` are never followed — `onSubmit` prevents the default — and they are
          here for the password managers, not for the network. Safari implements no Credential
          Management API for passwords, so iCloud Keychain's offer to save is driven entirely by
          how this form *looks*: a real `<form>` posting to a sign-in path, with a `username`
          field and a `current-password` field. The declared action is what turns the ambiguous
          "some form" into "a login form" for that heuristic, and it costs nothing here because
          submission never reaches it. Chromium is handled explicitly, in `lib/credentials.ts`.
        */
        method="post"
        action="/login"
        aria-busy={isSubmitting}
        data-testid={`${TestIdPrefix}.Form`}
      >
        <ThresholdTitle>Sign in</ThresholdTitle>
        <ThresholdLead>Use your work address.</ThresholdLead>

        {directory && (
          <Banner tone="steel" className="mb-4" data-testid={`${TestIdPrefix}.DirectoryMode`}>
            <span>Signing in with your {directory.displayName} password.</span>
          </Banner>
        )}

        {acceptedEmail && (
          <Banner tone="steel" className="mb-4" data-testid={`${TestIdPrefix}.Accepted`}>
            <span>Your account is ready. Sign in with the password you just chose.</span>
          </Banner>
        )}

        {(errors.root ?? ssoFailed) && (
          <div role="alert" className="mb-4">
            <Banner tone="bad" data-testid={`${TestIdPrefix}.Error`}>
              {/*
                The submitted form's own failure wins when there is one — somebody who has just
                pressed Sign in is asking about that attempt, not about the redirect that brought
                them here. Either way it is one sentence naming no cause, for `describeSignInFailure`'s
                reason and, for the federated half, §6r's: disabled provider, expired attempt,
                replayed `state`, unverified email, deactivated account and no invitation are one
                opaque marker on the wire, and a screen that guessed between them would rebuild the
                oracle the refusal design exists to close.
              */}
              <span>{errors.root?.message ?? "We couldn't sign you in with single sign-on."}</span>
            </Banner>
          </div>
        )}

        <div className="mb-[15px]">
          <FieldLabel htmlFor="email">Email</FieldLabel>
          <Input
            type="email"
            autoComplete="username"
            /*
              `id` and `name` are on Google's list of the signals a manager reads, beside
              `autocomplete` and `type`. `name` arrives from `register('email')` below.
            */
            id="email"
            className="min-h-[38px]"
            aria-invalid={!!errors.email}
            aria-describedby={errors.email ? 'email-error' : undefined}
            data-testid={`${TestIdPrefix}.EmailField`}
            {...emailField}
            /*
              AFTER the spread, and it calls react-hook-form's own handler itself: `register`
              supplies an `onBlur` that `mode: 'onBlur'` validation depends on, so a handler that
              merely replaced it would silently turn validation off for this field.
            */
            onBlur={(event: FocusEvent<HTMLInputElement>) => {
              void emailField.onBlur(event);
              onEmailBlur(event);
            }}
            /*
              Same arrangement as onBlur, for the same reason: `register` supplies the onChange
              that keeps react-hook-form's value current, so replacing it would disconnect the
              field.
            */
            onChange={(event: ChangeEvent<HTMLInputElement>) => {
              void emailField.onChange(event);
              onEmailChange();
            }}
          />
          {errors.email && <FieldError id="email-error">{errors.email.message}</FieldError>}
        </div>

        <div className="mb-[15px]">
          <FieldLabel htmlFor="current-password">{passwordLabel}</FieldLabel>
          <Input
            type="password"
            autoComplete="current-password"
            id="current-password"
            className="min-h-[38px]"
            aria-invalid={!!errors.password}
            aria-describedby={errors.password ? 'current-password-error' : undefined}
            data-testid={`${TestIdPrefix}.PasswordField`}
            {...register('password')}
          />
          {errors.password && (
            <FieldError id="current-password-error">{errors.password.message}</FieldError>
          )}
        </div>

        {/*
          The render puts this on the password path only — a switch for a flow that will not
          happen is noise at the moment noise costs most. It stays unconditional here because the
          password field does; see the note above `Login.PasswordField`'s sibling in the report.
        */}
        <div className="mb-[15px] flex items-center gap-[9px]">
          <Controller
            name="rememberMe"
            control={control}
            render={({ field }) => (
              <Switch
                id="remember-me"
                checked={field.value}
                onCheckedChange={field.onChange}
                onBlur={field.onBlur}
                data-testid={`${TestIdPrefix}.RememberMe`}
              />
            )}
          />
          <label htmlFor="remember-me" className="cursor-pointer text-[13px] text-ink-2">
            Keep me signed in
          </label>
        </div>

        <Button
          type="submit"
          variant="primary"
          className="min-h-[38px] w-full"
          disabled={isSubmitting}
          data-testid={`${TestIdPrefix}.SubmitButton`}
        >
          {isSubmitting ? 'Signing in…' : 'Sign in'}
        </Button>

        {directory && (
          <Button
            variant="quiet"
            size="sm"
            className="mt-2 w-full"
            onClick={() => setDirectory(null)}
            data-testid={`${TestIdPrefix}.UseLocalPassword`}
          >
            Use my TCMS password instead
          </Button>
        )}
      </form>

      {/*
        Nothing renders in the empty case and nothing renders in the failed case. That is the
        whole of the silence this screen owes the discovery endpoint, and it is one `&&`.

        The standing caption that used to sit here — "Single sign-on arrives with the integrations
        release" — went with this block: it had been false since §217 shipped the flow.
      */}
      {providers.length > 0 && (
        <div data-testid={`${TestIdPrefix}.Providers`}>
          <OrRule />
          <div className="flex flex-col gap-2">
            {providers.map((provider) => (
              <Button
                key={provider.id}
                variant="ghost"
                className="min-h-[38px] w-full"
                data-testid={`${TestIdPrefix}.Provider`}
                /*
                  A NAVIGATION, not a fetch. The endpoint answers 302 to a THIRD-PARTY origin; a
                  `fetch` would follow it into a CORS failure at the issuer, and the point is to put
                  the browser itself at that issuer's login page. Against the API origin, which is
                  why `BASE` is exported rather than spelled again here.
                */
                onClick={() => {
                  /*
                    Which CONTROL a provider is follows its kind (§223): an LDAP route has no
                    third party to send the browser to, so it switches this form into directory
                    mode rather than navigating.
                  */
                  if (provider.kind === 'LDAP') {
                    setDirectory(provider);
                    return;
                  }
                  /*
                    The router state dies with this navigation — the browser is leaving for the
                    issuer — so the deep link the auth gate preserved is handed to sessionStorage
                    for the callback page to consume. Local sign-in carries the same fact in
                    `navigate(from)` below.
                  */
                  rememberSsoReturnPath(from);
                  window.location.assign(
                    `${BASE}/auth/sso/${encodeURIComponent(provider.id)}/authorize`,
                  );
                }}
              >
                Continue with {provider.displayName}
              </Button>
            ))}
          </div>
        </div>
      )}

      {socialProviders.length > 0 && (
        <div data-testid={`${TestIdPrefix}.SocialProviders`}>
          <OrRule />
          <div className="flex flex-col gap-2">
            {socialProviders.map((provider) => (
              <Button
                key={provider}
                variant="ghost"
                className="min-h-[38px] w-full"
                data-testid={`${TestIdPrefix}.SocialProvider`}
                /*
                  A NAVIGATION, not a fetch — the company-SSO buttons' reasoning verbatim: the
                  endpoint answers 302 to a third-party origin, and the point is to put the
                  browser itself at that provider's login page.
                */
                onClick={() => {
                  rememberSsoReturnPath(from);
                  window.location.assign(`${BASE}/auth/social/${provider}/authorize`);
                }}
              >
                Continue with {socialProviderLabels[provider]}
              </Button>
            ))}
          </div>
        </div>
      )}

      {/*
        Said, not implied — the render's second note. A missing sign-up link is a puzzle; one
        sentence naming what actually happens is the difference between a support ticket and none.
      */}
      <ThresholdFootnote className="mt-5 text-center">
        No account here? An administrator has to invite you —{' '}
        <b className="font-medium text-ink-3">this instance has no sign-up.</b>
      </ThresholdFootnote>
    </ThresholdLayout>
  );
}
