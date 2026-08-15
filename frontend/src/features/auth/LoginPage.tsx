import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Checkbox, FormControlLabel, Stack, TextField, Typography } from '@mui/material';
import type { ChangeEvent, FocusEvent } from 'react';
import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { ApiError, BASE } from '../../api/client';
import { useDirectoryLogin, useSsoProviders } from '../../api/queries';
import type { SsoProviderChoice } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { useAuth } from '../../auth/useAuth';
import { offerToSaveCredentials } from '../../lib/credentials';
import { rememberSsoReturnPath } from './ssoReturnPath';

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

  /** Set when {@link SsoCallbackPage} sent them back. One sentence, no cause — see that file. */
  const ssoFailed = navState?.ssoFailed === true;

  /**
   * Discovery runs on blur, not per keystroke: the endpoint takes a whole address, and asking on
   * every character posts a stranger's half-typed address to the server a dozen times.
   */
  const onEmailBlur = (event: FocusEvent<HTMLInputElement | HTMLTextAreaElement>) => {
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
      <title>Sign in · TCMS</title>

      <Box sx={{ width: '100%', maxWidth: 400 }}>
        {/*
          The wordmark uses the identifier font: the product's own subject matter is keys and
          versions, so the mono lettering is the brand rather than an accessory on it.
        */}
        <Stack sx={{ mb: 4, gap: 0.5 }}>
          <Identifier variant="strong" sx={{ fontSize: '1.5rem', letterSpacing: '0.06em' }}>
            TCMS
          </Identifier>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Test case management
          </Typography>
        </Stack>

        <Box
          component="form"
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
          sx={{
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 1,
            bgcolor: 'background.paper',
            p: 3,
          }}
        >
          <Typography variant="h2" component="h1" sx={{ mb: directory ? 1 : 2.5 }}>
            Sign in
          </Typography>

          {directory && (
            <Typography
              variant="body2"
              sx={{ mb: 2, color: 'text.secondary' }}
              data-testid={`${TestIdPrefix}.DirectoryMode`}
            >
              Signing in with your {directory.displayName} password.
            </Typography>
          )}

          {acceptedEmail && (
            <Alert severity="success" sx={{ mb: 2 }} data-testid={`${TestIdPrefix}.Accepted`}>
              Your account is ready. Sign in with the password you just chose.
            </Alert>
          )}

          {(errors.root ?? ssoFailed) && (
            <Alert severity="error" role="alert" sx={{ mb: 2 }} data-testid={`${TestIdPrefix}.Error`}>
              {/*
                The submitted form's own failure wins when there is one — somebody who has just
                pressed Sign in is asking about that attempt, not about the redirect that brought
                them here. Either way it is one sentence naming no cause, for `describeSignInFailure`'s
                reason and, for the federated half, §6r's: disabled provider, expired attempt,
                replayed `state`, unverified email, deactivated account and no invitation are one
                opaque marker on the wire, and a screen that guessed between them would rebuild the
                oracle the refusal design exists to close.
              */}
              {errors.root?.message ?? "We couldn't sign you in with single sign-on."}
            </Alert>
          )}

          <Stack sx={{ gap: 2 }}>
            <TextField
              label="Email"
              type="email"
              autoComplete="username"
              /*
                `id` and `name` are on Google's list of the signals a manager reads, beside
                `autocomplete` and `type` — and MUI would otherwise generate an opaque `:r3:`-style
                id. `name` arrives from `register('email')` below.
              */
              id="email"
              fullWidth
              error={!!errors.email}
              helperText={errors.email?.message}
              slotProps={{
                htmlInput: { 'data-testid': `${TestIdPrefix}.EmailField` },
                formHelperText: { role: errors.email ? 'alert' : undefined },
              }}
              {...register('email')}
              /*
                AFTER the spread, and it calls react-hook-form's own handler itself: `register`
                supplies an `onBlur` that `mode: 'onBlur'` validation depends on, so a handler that
                merely replaced it would silently turn validation off for this field.
              */
              onBlur={(event) => {
                void register('email').onBlur(event);
                onEmailBlur(event);
              }}
              /*
                Same arrangement as onBlur, for the same reason: `register` supplies the onChange
                that keeps react-hook-form's value current, so replacing it would disconnect the
                field.
              */
              onChange={(event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
                void register('email').onChange(event);
                onEmailChange();
              }}
            />

            <TextField
              label={directory ? 'Directory password' : 'Password'}
              type="password"
              autoComplete="current-password"
              id="current-password"
              fullWidth
              error={!!errors.password}
              helperText={errors.password?.message}
              slotProps={{
                htmlInput: { 'data-testid': `${TestIdPrefix}.PasswordField` },
                formHelperText: { role: errors.password ? 'alert' : undefined },
              }}
              {...register('password')}
            />

            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  defaultChecked
                  data-testid={`${TestIdPrefix}.RememberMe`}
                  {...register('rememberMe')}
                />
              }
              label={<Typography variant="body2">Keep me signed in</Typography>}
            />

            <Button
              type="submit"
              variant="contained"
              size="large"
              disabled={isSubmitting}
              data-testid={`${TestIdPrefix}.SubmitButton`}
            >
              {isSubmitting ? 'Signing in…' : 'Sign in'}
            </Button>

            {directory && (
              <Button
                size="small"
                onClick={() => setDirectory(null)}
                data-testid={`${TestIdPrefix}.UseLocalPassword`}
              >
                Use my TCMS password instead
              </Button>
            )}
          </Stack>
        </Box>

        {/*
          Nothing renders in the empty case and nothing renders in the failed case. That is the
          whole of the silence this screen owes the discovery endpoint, and it is one `&&`.

          The standing caption that used to sit here — "Single sign-on arrives with the integrations
          release" — went with this block: it had been false since §217 shipped the flow.
        */}
        {providers.length > 0 && (
          <Stack sx={{ gap: 1, mt: 2 }} data-testid={`${TestIdPrefix}.Providers`}>
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              Or continue with
            </Typography>
            {providers.map((provider) => (
              <Button
                key={provider.id}
                variant="outlined"
                size="large"
                fullWidth
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
          </Stack>
        )}
      </Box>
    </Box>
  );
}
