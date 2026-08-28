import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  useChangeMyPassword,
  useCurrentUser,
  useMySocialIdentities,
  useSignOutOtherSessions,
  useSocialProviders,
  useStartSocialLink,
  useUnlinkSocialIdentity,
} from '../../api/queries';
import { ApiError } from '../../api/client';
import { useAuth } from '../../auth/useAuth';
import { Panel } from '../../components/PageHeader';
import type { SocialIdentity, SocialProvider } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';
import { socialProviderLabels } from '../../lib/labels';

/**
 * The account's sign-in identities (V58) — one row per provider the operator configured,
 * linkable and unlinkable by the signed-in person themselves. No permission gates any of it:
 * managing your own sign-in identities is self-service on your own account, like changing a
 * password, which is also why this screen lives outside every project and company segment.
 *
 * ## Two arrivals carry state, two different ways
 *
 * **`?welcome=1`** is the first-run offer — a brand-new invitee lands here auto-signed-in
 * (the accept page composed the login). It is URL state per the house rule, so a reload keeps
 * the mode. **Social is an optional extra**: the intro says so, the Skip is one click, and
 * nothing anywhere is gated on having linked — password sign-in remains sufficient forever.
 *
 * **`location.state.linked`** is a completed link round-trip, routed here by the SSO callback
 * page from the `#linked=` fragment marker. Router state, deliberately: it is a one-time
 * acknowledgement, and a reload that drops it drops nothing the list itself does not show.
 * Note the two never co-occur — the OAuth round-trip does not carry `?welcome=1` back, so a
 * first-run link lands with the acknowledgement and without the intro. That is correct: the
 * offer was taken.
 *
 * ## The link leg navigates, the list leg fetches
 *
 * Linking POSTs for a URL and then hands the whole browser to it — a top-level navigation
 * cannot carry the Authorization header, so the authenticated step is the POST and the
 * navigation that follows holds only the single-use `state`.
 *
 * ## The password and the sign-out (005 S9a) — and the list that is deliberately not here
 *
 * Two panels below the providers, and both are about the credential rather than the session: one
 * sets or changes the password, the other ends every *other* session of this account.
 *
 * **There is no session list, and there will not be one** (005 N12). Nothing in this product
 * records sessions — the token is stateless by an argued decision, and `/auth/logout` says so — so
 * a list would be an invention rather than a view, and every row on it would be a claim nothing
 * behind it could support. All-or-nothing is what the mechanism *is*: the one revocation this
 * product has is bumping the account's `pv` epoch, which invalidates every session at once.
 */
const TestIdPrefix = 'AccountSecurity';

export function AccountSecurityPage() {
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const providers = useSocialProviders();
  const identities = useMySocialIdentities();
  const startLink = useStartSocialLink();
  const unlink = useUnlinkSocialIdentity();

  const welcome = searchParams.get('welcome') === '1';
  const justLinked = (location.state as { linked?: string } | null)?.linked ?? null;

  /** The provider whose Unlink is awaiting confirmation, or null. */
  const [confirming, setConfirming] = useState<SocialProvider | null>(null);

  return (
    <Stack sx={{ gap: 3, maxWidth: 720 }} data-testid={TestIdPrefix}>
      <title>Security · TCMS</title>
      <Stack sx={{ gap: 0.5 }}>
        <Typography variant="h1">Security</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          How you sign in to this account.
        </Typography>
      </Stack>

      {welcome && (
        <Alert severity="info" data-testid={`${TestIdPrefix}.Welcome`}>
          <Stack sx={{ gap: 1, alignItems: 'flex-start' }}>
            <Typography variant="body2">
              Your account is ready. Want to sign in with GitHub, GitLab or Google next time?
              Link one now — or skip this; your password always works.
            </Typography>
            <Button
              size="small"
              data-testid={`${TestIdPrefix}.Skip`}
              onClick={() => navigate('/', { replace: true })}
            >
              Skip for now
            </Button>
          </Stack>
        </Alert>
      )}

      {justLinked !== null && (
        <Alert severity="success" data-testid={`${TestIdPrefix}.Linked`}>
          {labelFor(justLinked)} is now linked to your account. You can use it to sign in.
        </Alert>
      )}

      <DataRegion
        testId={`${TestIdPrefix}.Providers`}
        state={providers}
        isEmpty={(data) => data.providers.length === 0}
        empty={{
          kind: 'by-design',
          title: 'No social sign-in here',
          body: 'This instance has no social sign-in providers configured, so your password '
            + 'is how you sign in. An operator can enable GitHub, GitLab or Google.',
        }}
        skeleton={<Skeleton variant="rounded" height={160} />}
      >
        {(data) => (
          <DataRegion
            testId={`${TestIdPrefix}.Identities`}
            state={identities}
            skeleton={<Skeleton variant="rounded" height={160} />}
          >
            {(mine) => (
              <Stack sx={{ gap: 1.5 }}>
                {data.providers.map((provider) => (
                  <ProviderRow
                    key={provider}
                    provider={provider}
                    identity={mine.identities.find((i) => i.provider === provider) ?? null}
                    onLink={() =>
                      startLink.mutate(provider, {
                        onSuccess: ({ url }) => window.location.assign(url),
                      })
                    }
                    linkPending={startLink.isPending && startLink.variables === provider}
                    onUnlink={() => setConfirming(provider)}
                  />
                ))}
              </Stack>
            )}
          </DataRegion>
        )}
      </DataRegion>

      <PasswordPanel />
      <SignOutOthersPanel />

      <UnlinkIdentityDialog
        provider={confirming}
        onClose={() => setConfirming(null)}
        onConfirm={(provider) =>
          unlink.mutate(provider, { onSuccess: () => setConfirming(null) })
        }
        pending={unlink.isPending}
        error={unlink.error}
        reset={unlink.reset}
      />
    </Stack>
  );
}

/** The catalogue's spelling for a marker that arrived as text; the raw value is a legible bug. */
function labelFor(provider: string): string {
  return (socialProviderLabels as Record<string, string>)[provider] ?? provider;
}

function ProviderRow({
  provider,
  identity,
  onLink,
  linkPending,
  onUnlink,
}: {
  provider: SocialProvider;
  identity: SocialIdentity | null;
  onLink: () => void;
  linkPending: boolean;
  onUnlink: () => void;
}) {
  return (
    <Box
      data-testid={`${TestIdPrefix}.Row`}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 2,
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        bgcolor: 'background.paper',
        px: 2,
        py: 1.5,
      }}
    >
      <Stack sx={{ flex: 1, gap: 0.25 }}>
        <Typography variant="body1" sx={{ fontWeight: 600 }}>
          {socialProviderLabels[provider]}
        </Typography>
        {identity ? (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Linked {formatDateTime(identity.linkedAt)}
            {identity.emailAtLink ? ` · ${identity.emailAtLink}` : ''}
          </Typography>
        ) : (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Not linked
          </Typography>
        )}
      </Stack>
      {identity ? (
        <Button
          variant="outlined"
          color="error"
          size="small"
          onClick={onUnlink}
          data-testid={`${TestIdPrefix}.Unlink`}
        >
          Unlink
        </Button>
      ) : (
        <Button
          variant="outlined"
          size="small"
          onClick={onLink}
          disabled={linkPending}
          data-testid={`${TestIdPrefix}.Link`}
        >
          Link
        </Button>
      )}
    </Box>
  );
}

/**
 * The unlink confirmation — `UnlinkDialog`'s shape (named object, consequence, verb-labeled
 * danger button, refusal on the dialog), written locally because the shared one's copy is
 * about traceability links and a vague shared sentence would be worse than none.
 *
 * The refusal this can produce is the 409 last-credential guard, whose detail names the remedy
 * ("set a password or link another provider first") — it stays here, on the thing it is about.
 */
function UnlinkIdentityDialog({
  provider,
  onClose,
  onConfirm,
  pending,
  error,
  reset,
}: {
  provider: SocialProvider | null;
  onClose: () => void;
  onConfirm: (provider: SocialProvider) => void;
  pending: boolean;
  error: unknown;
  reset: () => void;
}) {
  const open = provider !== null;
  // Reopening after a refusal must not show the previous one — the house dialog rule.
  useEffect(() => {
    if (open) reset();
  }, [open, provider, reset]);

  return (
    <Dialog
      open={open}
      onClose={onClose}
      fullWidth
      maxWidth="xs"
      data-testid={`${TestIdPrefix}.UnlinkDialog`}
    >
      <DialogTitle>Unlink {provider ? socialProviderLabels[provider] : ''}?</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <DialogContentText variant="body2">
            You will no longer be able to sign in with it. Linking it again later re-runs the
            provider's authorization.
          </DialogContentText>
          {error != null && (
            <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.UnlinkError`}>
              {describeError(error).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid={`${TestIdPrefix}.UnlinkCancel`}>
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          disabled={pending}
          onClick={() => provider && onConfirm(provider)}
          data-testid={`${TestIdPrefix}.UnlinkConfirm`}
        >
          Unlink
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * Setting or changing this account's password (005 S9a).
 *
 * ## One panel, two intents, told apart by `CurrentUser.hasPassword`
 *
 * An account with no password **sets** one and sends `currentPassword: null` — the state a person
 * who joined through SSO is in, and the one that closes the impossible half of the
 * "unlink your last credential" refusal: until this existed, somebody with one social identity and
 * no password was told to "set a password or link another provider first" by a product that gave
 * them no way to set a password.
 *
 * An account with one **changes** it and must send the current one. Possession of a session is not
 * possession of the credential, and a walked-away-from laptop must not be enough to lock its owner
 * out of their own account.
 *
 * ## Nothing is signed out
 *
 * Changing a password bumps no epoch. Somebody rotating a password on a schedule should not be
 * signed out of their phone for it — that is the panel below, which says so on its own terms.
 */
function PasswordPanel() {
  const me = useCurrentUser();
  const change = useChangeMyPassword();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [done, setDone] = useState(false);

  const hasPassword = me.data?.hasPassword ?? true;
  const fieldErrors = change.error instanceof ApiError ? change.error.fieldErrors : null;

  const submit = () => {
    setDone(false);
    change.mutate(
      { currentPassword: hasPassword ? currentPassword : null, newPassword },
      {
        onSuccess: () => {
          setCurrentPassword('');
          setNewPassword('');
          setDone(true);
        },
      },
    );
  };

  return (
    <Panel title={hasPassword ? 'Change your password' : 'Set a password'} testId={`${TestIdPrefix}.Password`}>
      <Stack sx={{ gap: 2, p: 2 }}>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          {hasPassword
            ? 'Your other sessions keep working — sign them out below if you want them gone.'
            : 'You signed in without one. Setting a password gives you a way in that does not depend on a provider.'}
        </Typography>
        {hasPassword && (
          <TextField
            label="Current password"
            type="password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            error={!!fieldErrors?.currentPassword}
            helperText={fieldErrors?.currentPassword?.join(' ')}
            slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.CurrentPassword` } }}
          />
        )}
        <TextField
          label="New password"
          type="password"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
          error={!!fieldErrors?.newPassword}
          helperText={
            fieldErrors?.newPassword?.join(' ') ?? 'At least 12 characters.'
          }
          slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.NewPassword` } }}
        />
        {change.isError && !fieldErrors && (
          <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.PasswordError`}>
            {describeError(change.error).body}
          </Alert>
        )}
        {done && (
          <Alert severity="success" data-testid={`${TestIdPrefix}.PasswordSaved`}>
            Your password is set. Nothing was signed out.
          </Alert>
        )}
        <Box>
          <Button
            variant="contained"
            disabled={change.isPending || newPassword.length < 12 || (hasPassword && currentPassword === '')}
            onClick={submit}
            data-testid={`${TestIdPrefix}.SavePassword`}
          >
            {hasPassword ? 'Change password' : 'Set password'}
          </Button>
        </Box>
      </Stack>
    </Panel>
  );
}

/**
 * Ending every other session of this account (005 S9a, N12).
 *
 * **The caller's own token dies too, and that is why this answers a body rather than a 204.** The
 * epoch invalidates every session at once, the caller's included, so the response carries a fresh
 * `AuthResult` and this panel hands it to `adoptSession` — keeping one writer of `tcms.token`, the
 * same discipline the directory sign-in follows. A screen that ignored the body would sign the
 * reader out one request after they pressed "sign out everywhere else", which is the exact defect
 * the contract's 200 exists to prevent.
 *
 * No list of what is about to end, and no count: see this file's header.
 */
function SignOutOthersPanel() {
  const signOutOthers = useSignOutOtherSessions();
  const { adoptSession } = useAuth();
  const [confirming, setConfirming] = useState(false);
  const [done, setDone] = useState(false);

  const run = () => {
    setDone(false);
    signOutOthers.mutate(undefined, {
      onSuccess: (result) => {
        // Adopted, not merely stored: the provider is the one writer of the token, and it also
        // refreshes the session's user. Failing to adopt would leave this tab holding a token the
        // epoch has already invalidated.
        void adoptSession(result.token).then(() => {
          setConfirming(false);
          setDone(true);
        });
      },
    });
  };

  return (
    <Panel title="Sign out everywhere else" testId={`${TestIdPrefix}.SignOutOthers`}>
      <Stack sx={{ gap: 2, p: 2, alignItems: 'flex-start' }}>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Ends every other session of this account — every browser, every device. This one keeps
          working. There is no list of them: nothing here records sessions, so ending all of them at
          once is the only thing this product can honestly offer.
        </Typography>
        {signOutOthers.isError && (
          <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.SignOutOthersError`}>
            {describeError(signOutOthers.error).body}
          </Alert>
        )}
        {done && (
          <Alert severity="success" data-testid={`${TestIdPrefix}.SignedOutOthers`}>
            Every other session is now signed out. This one carried on with a new token.
          </Alert>
        )}
        <Button
          variant="outlined"
          color="error"
          disabled={signOutOthers.isPending}
          onClick={() => setConfirming(true)}
          data-testid={`${TestIdPrefix}.SignOutOthersOpen`}
        >
          Sign out other sessions
        </Button>
      </Stack>

      <Dialog
        open={confirming}
        onClose={() => setConfirming(false)}
        fullWidth
        maxWidth="xs"
        data-testid={`${TestIdPrefix}.SignOutOthersDialog`}
      >
        <DialogTitle>Sign out every other session?</DialogTitle>
        <DialogContent>
          <DialogContentText variant="body2">
            Anyone signed in as you anywhere else will have to sign in again. Nothing you have
            typed here is affected.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirming(false)} data-testid={`${TestIdPrefix}.SignOutOthersCancel`}>
            Cancel
          </Button>
          <Button
            color="error"
            variant="contained"
            disabled={signOutOthers.isPending}
            onClick={run}
            data-testid={`${TestIdPrefix}.SignOutOthersConfirm`}
          >
            Sign them out
          </Button>
        </DialogActions>
      </Dialog>
    </Panel>
  );
}
