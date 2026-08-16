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
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  useMySocialIdentities,
  useSocialProviders,
  useStartSocialLink,
  useUnlinkSocialIdentity,
} from '../../api/queries';
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
    // `reset` is stable on a mutation object; depending on it would fire on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, provider]);

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
