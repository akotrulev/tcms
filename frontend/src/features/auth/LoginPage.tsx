import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Checkbox, FormControlLabel, Stack, TextField, Typography } from '@mui/material';
import { useForm } from 'react-hook-form';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { IS_MOCK_API } from '../../app/apiMode';
import { Identifier } from '../../components/Identifier';
import { useAuth } from '../../auth/useAuth';

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
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const navState = location.state as { from?: string; acceptedEmail?: string } | null;

  /**
   * Set when they arrived straight from redeeming an invitation. Accepting answers 204 rather
   * than a session (one token-issuing path), so this screen has to explain why they are being
   * asked to sign in immediately after choosing a password — otherwise it reads as the accept
   * having failed.
   */
  const acceptedEmail = navState?.acceptedEmail;

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
        A seeded address is a convenience of the mock API, where any password of 4+ characters
        signs you in as that user. Against the real backend it is worse than nothing: it
        prefills an account that does not exist on the instance. The invitee's own address wins
        over both.
      */
      email: acceptedEmail ?? (IS_MOCK_API ? 'ana.petrova@example.com' : ''),
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
      await login(data.email, data.password, data.rememberMe);
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
          <Typography variant="h2" component="h1" sx={{ mb: 2.5 }}>
            Sign in
          </Typography>

          {acceptedEmail && (
            <Alert severity="success" sx={{ mb: 2 }} data-testid={`${TestIdPrefix}.Accepted`}>
              Your account is ready. Sign in with the password you just chose.
            </Alert>
          )}

          {errors.root && (
            <Alert severity="error" role="alert" sx={{ mb: 2 }} data-testid={`${TestIdPrefix}.Error`}>
              {errors.root.message}
            </Alert>
          )}

          <Stack sx={{ gap: 2 }}>
            <TextField
              label="Email"
              type="email"
              autoComplete="username"
              fullWidth
              error={!!errors.email}
              helperText={errors.email?.message}
              slotProps={{
                htmlInput: { 'data-testid': `${TestIdPrefix}.EmailField` },
                formHelperText: { role: errors.email ? 'alert' : undefined },
              }}
              {...register('email')}
            />

            <TextField
              label="Password"
              type="password"
              autoComplete="current-password"
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
          </Stack>
        </Box>

        <Typography variant="caption" sx={{ display: 'block', mt: 2, color: 'text.secondary' }}>
          Local account. Single sign-on arrives with the integrations release.
        </Typography>
      </Box>
    </Box>
  );
}
