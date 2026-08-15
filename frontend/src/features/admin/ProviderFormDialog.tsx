import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { ApiError } from '../../api/client';
import { useSaveIdentityProvider } from '../../api/queries';
import type { IdentityProvider, IdentityProviderInput } from '../../api/types';
import { describeError } from '../../lib/describeError';
import { inputTestId } from '../../lib/muiTestIds';

/**
 * Registering an issuer, and reconfiguring one — one form, because the only difference between the
 * two is whether an id exists.
 *
 * ## A null `clientSecret` is a keep, and it must be spelled as a null
 *
 * No read path in this API echoes the stored secret back, so an administrator renaming a provider
 * has nothing to resend and the field is never pre-filled. `IdentityProviderInput.clientSecret` is
 * **required and nullable**, and the contract says null keeps the stored value — so the keep is
 * `null` here, not an absent property. That is the opposite arrangement from
 * `NotificationChannelDialog`, where the same intent is expressed by *omitting* the key, and
 * copying either one into the other is a body the server refuses or a credential blanked in
 * silence.
 *
 * ## Three controls are conditional and each absence is a statement
 *
 * **`key` and `kind` are disabled on an edit rather than hidden.** Both are set once at
 * registration and the `PUT` ignores them; a live field the server discards is a lie the form
 * tells, and hiding them would lose the two facts an administrator most needs to recognise which
 * provider this is. §31's disable-don't-hide, applied to a field.
 *
 * **`enabled` renders only on an edit**, because registration cannot enable: `IdentityProvider`
 * is always created disabled, deliberately, since an issuer URL typed wrong is a sign-in route
 * that refuses everybody. A switch on the registration form would be a control whose value the
 * server throws away.
 *
 * **The https check is client-side as well as server-side, and that duplication is on purpose.**
 * The server refuses anything else, and it is refusing for a reason a 400 field error cannot
 * carry: the issuer URL is where the signing keys are fetched from, so over plain HTTP whoever is
 * on the path chooses who may sign in. Checked here so the message can say that.
 */

const TestId = 'Admin.Sso';

/** `IdentityProviderService`'s own rule, mirrored so the message can be a sentence. */
const KEY_PATTERN = /^[a-z0-9][a-z0-9-]{0,58}[a-z0-9]$/;

type FieldName = 'key' | 'displayName' | 'issuerUri' | 'clientId' | 'clientSecret' | 'groupClaim';

export function ProviderFormDialog({
  provider,
  onClose,
}: {
  /** The provider being reconfigured, or `null` to register one. */
  provider: IdentityProvider | null;
  onClose: () => void;
}) {
  const editing = provider !== null;
  const save = useSaveIdentityProvider();

  const [key, setKey] = useState(provider?.key ?? '');
  const [displayName, setDisplayName] = useState(provider?.displayName ?? '');
  const [issuerUri, setIssuerUri] = useState(provider?.issuerUri ?? '');
  const [clientId, setClientId] = useState(provider?.clientId ?? '');
  /*
    ALWAYS EMPTY on mount, including on an edit, because there is nothing to pre-fill it with — the
    value has never left the server. What the form does with the emptiness is in the header.
  */
  const [clientSecret, setClientSecret] = useState('');
  const [jitProvisioning, setJitProvisioning] = useState(provider?.jitProvisioning ?? false);
  const [autoLinkVerifiedEmail, setAutoLinkVerifiedEmail] = useState(
    provider?.autoLinkVerifiedEmail ?? false,
  );
  const [groupClaim, setGroupClaim] = useState(provider?.groupClaim ?? '');
  const [enabled, setEnabled] = useState(provider?.enabled ?? false);

  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldName, string>>>({});
  const [formError, setFormError] = useState<string | undefined>(undefined);

  const submittable =
    displayName.trim() !== '' && issuerUri.trim() !== '' && clientId.trim() !== '' && !save.isPending;

  const localRefusal = (): Partial<Record<FieldName, string>> => {
    const refusals: Partial<Record<FieldName, string>> = {};
    if (!editing && !KEY_PATTERN.test(key.trim())) {
      refusals.key = 'Lowercase letters, digits and hyphens, starting and ending with one of those.';
    }
    if (!issuerUri.trim().startsWith('https://')) {
      refusals.issuerUri =
        'The issuer must be https — the signing keys every sign-in is checked against are fetched from this URL.';
    }
    return refusals;
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setFormError(undefined);

    const refusals = localRefusal();
    if (Object.keys(refusals).length > 0) {
      setFieldErrors(refusals);
      return;
    }
    setFieldErrors({});

    const trimmedSecret = clientSecret.trim();
    const input: IdentityProviderInput = {
      // The provider's own key and kind on an edit — both controls are disabled, and the server
      // ignores these values there. Required by the input schema either way.
      key: editing ? provider.key : key.trim(),
      kind: provider?.kind ?? 'OIDC',
      displayName: displayName.trim(),
      issuerUri: issuerUri.trim(),
      clientId: clientId.trim(),
      /*
        THE line this dialog exists to get right. `null` — never `''` — because the contract reads
        null as "keep what is stored" and an empty string as a rotation to an empty credential.
        Omitting the property is not available: it is on the schema's `required` list.
      */
      clientSecret: trimmedSecret === '' ? null : trimmedSecret,
      // The directory half of the schema. This dialog still only registers issuers, so it sends
      // the eight of them empty — the controls that populate them are the next phase.
      bindDn: null,
      bindPassword: null,
      startTls: false,
      userSearchBase: null,
      userSearchFilter: null,
      userIdAttribute: null,
      emailAttribute: null,
      displayNameAttribute: null,
      jitProvisioning,
      autoLinkVerifiedEmail,
      groupClaim: groupClaim.trim() === '' ? null : groupClaim.trim(),
      // Ignored on registration, which always produces a disabled provider.
      enabled,
    };

    try {
      await save.mutateAsync({ providerId: provider?.id, input });
      onClose();
    } catch (error) {
      // The server keys `Problem.errors` by the input schema's field names, so each message lands
      // on the control that caused it. Anything without a field — a 403, a 409 — goes to the
      // form-level alert through `describeError`, the one place an error becomes user-facing copy.
      if (error instanceof ApiError && error.fieldErrors) {
        const landed: Partial<Record<FieldName, string>> = {};
        for (const [field, messages] of Object.entries(error.fieldErrors)) {
          if (isFieldName(field)) landed[field] = messages.join(' ');
        }
        if (Object.keys(landed).length > 0) {
          setFieldErrors(landed);
          return;
        }
      }
      setFormError(describeError(error).body);
    }
  };

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" data-testid={`${TestId}.Dialog`}>
      <form onSubmit={(event) => void submit(event)} noValidate aria-busy={save.isPending}>
        <DialogTitle>
          {editing ? `Configure ${provider.displayName}` : 'Register an identity provider'}
        </DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {formError && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {formError}
              </Alert>
            )}

            <TextField
              select
              label="Kind"
              required
              value={provider?.kind ?? 'OIDC'}
              // OIDC is the only implemented kind, and the select exists so the form says which
              // one this is rather than leaving the reader to assume. Disabled on an edit for
              // `key`'s reason: the server ignores it there.
              disabled
              helperText="OpenID Connect. A provider's kind is set once."
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Kind` } }}
            >
              <MenuItem value="OIDC">OpenID Connect</MenuItem>
            </TextField>

            <TextField
              label="Key"
              required
              value={editing ? provider.key : key}
              disabled={editing}
              onChange={(event) => setKey(event.target.value)}
              error={!!fieldErrors.key}
              helperText={
                fieldErrors.key ??
                (editing
                  ? 'A provider’s key is set once at registration and cannot be changed.'
                  : 'Your own short name for this issuer — “acme-sso”. Lowercase letters, digits and hyphens.')
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Key`, maxLength: 60 } }}
            />

            <TextField
              label="Name"
              required
              autoFocus
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              error={!!fieldErrors.displayName}
              helperText={
                fieldErrors.displayName ??
                'What the sign-in button says. This is the one string this product shows to somebody who has not signed in.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.DisplayName`, maxLength: 120 } }}
            />

            <TextField
              label="Issuer URL"
              required
              value={issuerUri}
              onChange={(event) => setIssuerUri(event.target.value)}
              error={!!fieldErrors.issuerUri}
              helperText={
                fieldErrors.issuerUri ??
                'Like https://issuer.acme.test/realms/acme. Its discovery document is what this instance reads the signing keys from, so it must be https.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.IssuerUri` } }}
            />

            <TextField
              label="Client id"
              required
              value={clientId}
              onChange={(event) => setClientId(event.target.value)}
              error={!!fieldErrors.clientId}
              helperText={fieldErrors.clientId ?? 'The client id this issuer gave you for TCMS.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.ClientId`, maxLength: 500 } }}
            />

            <TextField
              label={provider?.clientSecretSet ? 'Replace the client secret' : 'Client secret'}
              type="password"
              value={clientSecret}
              onChange={(event) => setClientSecret(event.target.value)}
              error={!!fieldErrors.clientSecret}
              helperText={
                fieldErrors.clientSecret ??
                (provider?.clientSecretSet
                  ? /*
                      The sentence the dialog rests on. An administrator who reads "leave blank to
                      keep" and does so must be right — and they are, because the property goes out
                      as null, which the server reads as unchanged.
                    */
                    'Leave blank to keep the stored secret. Type a new one only to replace it — this product will never show you the current value.'
                  : 'Public clients have none. Leave blank if this issuer authenticates TCMS with PKCE alone.')
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.ClientSecret` } }}
            />

            <TextField
              label="Group claim"
              value={groupClaim}
              onChange={(event) => setGroupClaim(event.target.value)}
              error={!!fieldErrors.groupClaim}
              helperText={
                fieldErrors.groupClaim ??
                'Optional. Stored and not yet consulted — mapping groups to roles is its own slice, and this is the field it will read.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.GroupClaim` } }}
            />

            <FormControlLabel
              sx={{ alignItems: 'flex-start' }}
              control={
                <Switch
                  checked={jitProvisioning}
                  onChange={(event) => setJitProvisioning(event.target.checked)}
                  slotProps={inputTestId(`${TestId}.JitProvisioning`)}
                />
              }
              label={
                <Box sx={{ pt: 0.75 }}>
                  <Typography variant="body2">Create accounts on first sign-in</Typography>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    {/* Off by default, and the sentence says which direction is the safe one. */}
                    Anyone your issuer authenticates gets an account here, with exactly what
                    accepting an invitation grants and nothing more. Off means only people who were
                    invited can get in.
                  </Typography>
                </Box>
              }
            />

            <FormControlLabel
              sx={{ alignItems: 'flex-start' }}
              control={
                <Switch
                  checked={autoLinkVerifiedEmail}
                  onChange={(event) => setAutoLinkVerifiedEmail(event.target.checked)}
                  slotProps={inputTestId(`${TestId}.AutoLink`)}
                />
              }
              label={
                <Box sx={{ pt: 0.75 }}>
                  <Typography variant="body2">Link by verified email address</Typography>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Your issuer’s word about an email address is enough to reach an account that
                    already exists here. An unverified address never links, whatever this says.
                  </Typography>
                </Box>
              }
            />

            {editing && (
              <FormControlLabel
                sx={{ alignItems: 'flex-start' }}
                control={
                  <Switch
                    checked={enabled}
                    onChange={(event) => setEnabled(event.target.checked)}
                    slotProps={inputTestId(`${TestId}.Enabled`)}
                  />
                }
                label={
                  <Box sx={{ pt: 0.75 }}>
                    <Typography variant="body2">Live sign-in route</Typography>
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      Turning this on makes it a live sign-in route for everyone at your claimed
                      domains. Turning it off stops new sign-ins through it and keeps every account
                      that already came in.
                    </Typography>
                  </Box>
                }
              />
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={!submittable}
            data-testid={`${TestId}.Save`}
          >
            {save.isPending ? 'Saving…' : editing ? 'Save provider' : 'Register provider'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

function isFieldName(field: string): field is FieldName {
  return (
    field === 'key'
    || field === 'displayName'
    || field === 'issuerUri'
    || field === 'clientId'
    || field === 'clientSecret'
    || field === 'groupClaim'
  );
}
