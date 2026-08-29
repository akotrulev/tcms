import { useState } from 'react';
import { ApiError } from '../../api/client';
import { useSaveIdentityProvider } from '../../api/queries';
import type { IdentityProvider, IdentityProviderInput, IdentityProviderKind } from '../../api/types';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Switch,
} from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';

/**
 * Registering a provider, and reconfiguring one — one form, because the only difference between the
 * two is whether an id exists. Two kinds since §223: an issuer the browser is sent to, and a
 * directory whose password is collected here.
 *
 * ## A null credential is a keep, and it must be spelled as a null
 *
 * No read path in this API echoes a stored credential back, so an administrator renaming a provider
 * has nothing to resend and neither secret field is ever pre-filled. `clientSecret` and
 * `bindPassword` are **required and nullable**, and the contract says null keeps the stored value —
 * so the keep is `null` here, not an absent property. That is the opposite arrangement from
 * `NotificationChannelDialog`, where the same intent is expressed by *omitting* the key, and
 * copying either one into the other is a body the server refuses or a credential blanked in
 * silence.
 *
 * ## Three controls are conditional and each absence is a statement
 *
 * **`key` and `kind` are disabled on an edit rather than hidden.** Both are set once at
 * registration and the `PUT` ignores them; a live field the server discards is a lie the form
 * tells, and hiding them would lose the two facts an administrator most needs to recognise which
 * provider this is. §31's disable-don't-hide, applied to a field. On a registration the kind is
 * live — it is the choice everything below it follows.
 *
 * **`enabled` renders only on an edit**, because registration cannot enable: `IdentityProvider`
 * is always created disabled, deliberately, since an issuer URL typed wrong is a sign-in route
 * that refuses everybody.
 *
 * **The transport checks are client-side as well as server-side, and that duplication is on
 * purpose.** The server refuses for reasons a 400 field error cannot carry: an issuer over plain
 * HTTP lets whoever is on the path choose who may sign in, and an unencrypted directory bind puts
 * the person's own directory password on the wire. Checked here so each message can say so.
 */

const TestId = 'Admin.Sso';

/** `IdentityProviderService`'s own rule, mirrored so the message can be a sentence. */
const KEY_PATTERN = /^[a-z0-9][a-z0-9-]{0,58}[a-z0-9]$/;

type FieldName =
  | 'key'
  | 'displayName'
  | 'issuerUri'
  | 'clientId'
  | 'clientSecret'
  | 'groupClaim'
  | 'bindDn'
  | 'bindPassword'
  | 'userSearchBase'
  | 'userSearchFilter'
  | 'userIdAttribute'
  | 'emailAttribute';

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

  const [kind, setKind] = useState<IdentityProviderKind>(provider?.kind ?? 'OIDC');
  const directory = kind === 'LDAP';

  const [key, setKey] = useState(provider?.key ?? '');
  const [displayName, setDisplayName] = useState(provider?.displayName ?? '');
  const [issuerUri, setIssuerUri] = useState(provider?.issuerUri ?? '');
  const [clientId, setClientId] = useState(provider?.clientId ?? '');
  /*
    ALWAYS EMPTY on mount, including on an edit, because there is nothing to pre-fill it with — the
    value has never left the server. What the form does with the emptiness is in the header.
  */
  const [clientSecret, setClientSecret] = useState('');
  const [bindDn, setBindDn] = useState(provider?.bindDn ?? '');
  /* ALWAYS EMPTY on mount, for `clientSecret`'s reason — the value has never left the server. */
  const [bindPassword, setBindPassword] = useState('');
  const [startTls, setStartTls] = useState(provider?.startTls ?? false);
  const [userSearchBase, setUserSearchBase] = useState(provider?.userSearchBase ?? '');
  const [userSearchFilter, setUserSearchFilter] = useState(
    provider?.userSearchFilter ?? '(|(userPrincipalName={0})(mail={0}))',
  );
  const [userIdAttribute, setUserIdAttribute] = useState(
    provider?.userIdAttribute ?? 'objectGUID',
  );
  const [emailAttribute, setEmailAttribute] = useState(provider?.emailAttribute ?? 'mail');
  const [displayNameAttribute, setDisplayNameAttribute] = useState(
    provider?.displayNameAttribute ?? 'displayName',
  );
  const [jitProvisioning, setJitProvisioning] = useState(provider?.jitProvisioning ?? false);
  const [autoLinkVerifiedEmail, setAutoLinkVerifiedEmail] = useState(
    provider?.autoLinkVerifiedEmail ?? false,
  );
  const [groupClaim, setGroupClaim] = useState(provider?.groupClaim ?? '');
  const [enabled, setEnabled] = useState(provider?.enabled ?? false);

  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldName, string>>>({});
  const [formError, setFormError] = useState<string | undefined>(undefined);

  const submittable =
    displayName.trim() !== ''
    && issuerUri.trim() !== ''
    && (directory || clientId.trim() !== '')
    && !save.isPending;

  const localRefusal = (): Partial<Record<FieldName, string>> => {
    const refusals: Partial<Record<FieldName, string>> = {};
    if (!editing && !KEY_PATTERN.test(key.trim())) {
      refusals.key = 'Lowercase letters, digits and hyphens, starting and ending with one of those.';
    }
    const url = issuerUri.trim();
    if (directory) {
      /*
        Client-side as well as server-side, and the duplication is on purpose — the same reason the
        https check is duplicated one branch down. The server refuses this for a reason a 400 field
        error cannot carry, so it is checked here to say it: an unencrypted bind puts the person's
        own directory password on the wire, which is the credential to everything else the customer
        owns. There is deliberately no flag that permits it.
      */
      if (!/^ldaps:\/\/./.test(url) && !(/^ldap:\/\/./.test(url) && startTls)) {
        refusals.issuerUri =
          'A directory URL must be ldaps://, or ldap:// with StartTLS — a plain bind puts your people’s directory passwords on the wire.';
      }
      if (!userSearchFilter.includes('{0}')) {
        refusals.userSearchFilter =
          'The filter must contain {0} — that is where the typed address is substituted.';
      }
      if (userSearchBase.trim() === '') {
        refusals.userSearchBase = 'A search base is required — “ou=people,dc=acme,dc=test”.';
      }
      if (userIdAttribute.trim() === '') {
        refusals.userIdAttribute =
          'Name the attribute holding the directory’s own identifier — “objectGUID” on Active Directory.';
      }
    } else if (!url.startsWith('https://')) {
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
    const trimmedBindPassword = bindPassword.trim();
    const input: IdentityProviderInput = {
      // The provider's own key and kind on an edit — both controls are disabled, and the server
      // ignores these values there. Required by the input schema either way.
      key: editing ? provider.key : key.trim(),
      kind,
      displayName: displayName.trim(),
      issuerUri: issuerUri.trim(),
      // The server refuses a client id on a directory provider — ck_identity_provider_shape
      // requires it to be null — and refuses its absence on an issuer.
      clientId: directory ? null : clientId.trim(),
      /*
        THE line this dialog exists to get right, twice over. `null` — never `''` — because the
        contract reads null as "keep what is stored" and an empty string as a rotation to an empty
        credential. Omitting the property is not available: both are on the schema's `required`
        list.
      */
      clientSecret: directory || trimmedSecret === '' ? null : trimmedSecret,
      bindDn: directory && bindDn.trim() !== '' ? bindDn.trim() : null,
      bindPassword: directory && trimmedBindPassword !== '' ? trimmedBindPassword : null,
      startTls: directory ? startTls : false,
      userSearchBase: directory ? userSearchBase.trim() : null,
      userSearchFilter: directory ? userSearchFilter.trim() : null,
      userIdAttribute: directory ? userIdAttribute.trim() : null,
      emailAttribute: directory && emailAttribute.trim() !== '' ? emailAttribute.trim() : null,
      displayNameAttribute:
        directory && displayNameAttribute.trim() !== '' ? displayNameAttribute.trim() : null,
      jitProvisioning,
      autoLinkVerifiedEmail,
      groupClaim: directory || groupClaim.trim() === '' ? null : groupClaim.trim(),
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
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={`${TestId}.Dialog`}>
        <form onSubmit={(event) => void submit(event)} noValidate aria-busy={save.isPending}>
          <DialogHeader>
            <DialogTitle>
              {editing ? `Configure ${provider.displayName}` : 'Register an identity provider'}
            </DialogTitle>
          </DialogHeader>

          <div className="mt-4 flex max-h-[62vh] flex-col gap-4 overflow-y-auto pr-1">
            {formError && (
              <div data-testid={`${TestId}.Error`}>
                <Banner tone="bad">
                  <span role="alert">{formError}</span>
                </Banner>
              </div>
            )}

            <Field
              label="Kind"
              htmlFor="sso-kind"
              help={
                editing
                  ? 'A provider’s kind is set once.'
                  : 'Which protocol this provider speaks — everything below follows it.'
              }
            >
              <NativeSelect
                id="sso-kind"
                required
                value={kind}
                onChange={(event) => setKind(event.target.value as IdentityProviderKind)}
                // A kind is set once at registration, which is why it is disabled on an edit and
                // live on a registration — `key`'s reason: the server ignores it on the PUT.
                disabled={editing}
                /*
                  `aria-disabled` beside the native `disabled`, which is belt and braces on purpose:
                  a disabled control is removed from the accessibility tree's interaction model but
                  several screen readers announce the ARIA state where they announce nothing for the
                  native one, and this is the field whose disabled-ness is the answer to "why can't
                  I change this".
                */
                aria-disabled={editing || undefined}
                data-testid={`${TestId}.Kind`}
              >
                <option value="OIDC">OpenID Connect</option>
                <option value="LDAP">Directory (LDAP / Active Directory)</option>
              </NativeSelect>
            </Field>

            <Field
              label="Key"
              htmlFor="sso-key"
              error={fieldErrors.key}
              help={
                editing
                  ? 'A provider’s key is set once at registration and cannot be changed.'
                  : 'Your own short name for this provider — “acme-sso”. Lowercase letters, digits and hyphens.'
              }
            >
              <Input
                id="sso-key"
                required
                maxLength={60}
                value={editing ? provider.key : key}
                disabled={editing}
                onChange={(event) => setKey(event.target.value)}
                data-testid={`${TestId}.Key`}
              />
            </Field>

            <Field
              label="Name"
              htmlFor="sso-display-name"
              error={fieldErrors.displayName}
              help="What the sign-in button says. This is the one string this product shows to somebody who has not signed in."
            >
              <Input
                id="sso-display-name"
                required
                autoFocus
                maxLength={120}
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                data-testid={`${TestId}.DisplayName`}
              />
            </Field>

            <Field
              label={directory ? 'Directory URL' : 'Issuer URL'}
              htmlFor="sso-issuer"
              error={fieldErrors.issuerUri}
              help={
                directory
                  ? 'Like ldaps://dc.acme.test:636. A plain ldap:// URL is only accepted with StartTLS, because a bind sends your people’s own passwords.'
                  : 'Like https://issuer.acme.test/realms/acme. Its discovery document is what this instance reads the signing keys from, so it must be https.'
              }
            >
              <Input
                id="sso-issuer"
                required
                value={issuerUri}
                onChange={(event) => setIssuerUri(event.target.value)}
                data-testid={`${TestId}.IssuerUri`}
              />
            </Field>

            {!directory && (
              <Field
                label="Client id"
                htmlFor="sso-client-id"
                error={fieldErrors.clientId}
                help="The client id this issuer gave you for TCMS."
              >
                <Input
                  id="sso-client-id"
                  required
                  maxLength={500}
                  value={clientId}
                  onChange={(event) => setClientId(event.target.value)}
                  data-testid={`${TestId}.ClientId`}
                />
              </Field>
            )}

            {!directory && (
              <Field
                label={provider?.clientSecretSet ? 'Replace the client secret' : 'Client secret'}
                htmlFor="sso-client-secret"
                error={fieldErrors.clientSecret}
                help={
                  provider?.clientSecretSet
                    ? /*
                        The sentence the dialog rests on. An administrator who reads "leave blank to
                        keep" and does so must be right — and they are, because the property goes out
                        as null, which the server reads as unchanged.
                      */
                      'Leave blank to keep the stored secret. Type a new one only to replace it — this product will never show you the current value.'
                    : 'Public clients have none. Leave blank if this issuer authenticates TCMS with PKCE alone.'
                }
              >
                <Input
                  id="sso-client-secret"
                  type="password"
                  value={clientSecret}
                  onChange={(event) => setClientSecret(event.target.value)}
                  data-testid={`${TestId}.ClientSecret`}
                />
              </Field>
            )}

            {directory && (
              <>
                <Field
                  label="Bind DN"
                  htmlFor="sso-bind-dn"
                  error={fieldErrors.bindDn}
                  help="The service account this instance searches as — “cn=tcms,ou=services,dc=acme,dc=test”. Leave blank for an anonymous search, which Active Directory does not permit."
                >
                  <Input
                    id="sso-bind-dn"
                    maxLength={500}
                    value={bindDn}
                    onChange={(event) => setBindDn(event.target.value)}
                    data-testid={`${TestId}.BindDn`}
                  />
                </Field>

                <Field
                  label={provider?.bindPasswordSet ? 'Replace the bind password' : 'Bind password'}
                  htmlFor="sso-bind-password"
                  error={fieldErrors.bindPassword}
                  help={
                    provider?.bindPasswordSet
                      ? 'Leave blank to keep the stored password. Type a new one only to replace it — this product will never show you the current value.'
                      : 'The service account’s password. Leave blank for an anonymous search.'
                  }
                >
                  <Input
                    id="sso-bind-password"
                    type="password"
                    value={bindPassword}
                    onChange={(event) => setBindPassword(event.target.value)}
                    data-testid={`${TestId}.BindPassword`}
                  />
                </Field>

                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <p className="text-[13px] text-ink-2">StartTLS</p>
                    <p className="text-[12px] leading-[1.5] text-ink-4">
                      Upgrades a plain ldap:// connection to an encrypted one. An ldaps:// URL is
                      already encrypted and does not use this.
                    </p>
                  </div>
                  <Switch
                    checked={startTls}
                    onCheckedChange={setStartTls}
                    aria-label="StartTLS"
                    data-testid={`${TestId}.StartTls`}
                  />
                </div>

                <Field
                  label="User search base"
                  htmlFor="sso-user-search-base"
                  error={fieldErrors.userSearchBase}
                  help="The subtree searched for the person signing in — “ou=people,dc=acme,dc=test”."
                >
                  <Input
                    id="sso-user-search-base"
                    required
                    maxLength={500}
                    value={userSearchBase}
                    onChange={(event) => setUserSearchBase(event.target.value)}
                    data-testid={`${TestId}.UserSearchBase`}
                  />
                </Field>

                <Field
                  label="User search filter"
                  htmlFor="sso-user-search-filter"
                  error={fieldErrors.userSearchFilter}
                  help="Must contain {0}, where the typed address is substituted with escaping applied. The default matches Active Directory."
                >
                  <Input
                    id="sso-user-search-filter"
                    required
                    maxLength={500}
                    value={userSearchFilter}
                    onChange={(event) => setUserSearchFilter(event.target.value)}
                    data-testid={`${TestId}.UserSearchFilter`}
                  />
                </Field>

                <Field
                  label="User id attribute"
                  htmlFor="sso-user-id-attribute"
                  error={fieldErrors.userIdAttribute}
                  help="“objectGUID” on Active Directory, “entryUUID” on OpenLDAP. Never a DN — a DN changes when somebody moves between OUs."
                >
                  <Input
                    id="sso-user-id-attribute"
                    required
                    maxLength={100}
                    value={userIdAttribute}
                    onChange={(event) => setUserIdAttribute(event.target.value)}
                    data-testid={`${TestId}.UserIdAttribute`}
                  />
                </Field>

                <Field
                  label="Email attribute"
                  htmlFor="sso-email-attribute"
                  error={fieldErrors.emailAttribute}
                  help="Which attribute carries the address — “mail” on both common directories."
                >
                  <Input
                    id="sso-email-attribute"
                    maxLength={100}
                    value={emailAttribute}
                    onChange={(event) => setEmailAttribute(event.target.value)}
                    data-testid={`${TestId}.EmailAttribute`}
                  />
                </Field>

                <Field
                  label="Display name attribute"
                  htmlFor="sso-display-name-attribute"
                  help="Which attribute carries the person’s name, for the membership a sign-in creates."
                >
                  <Input
                    id="sso-display-name-attribute"
                    maxLength={100}
                    value={displayNameAttribute}
                    onChange={(event) => setDisplayNameAttribute(event.target.value)}
                    data-testid={`${TestId}.DisplayNameAttribute`}
                  />
                </Field>
              </>
            )}

            {!directory && (
              <Field
                label="Group claim"
                htmlFor="sso-group-claim"
                error={fieldErrors.groupClaim}
                help="Optional. Stored and not yet consulted — mapping groups to roles is its own slice, and this is the field it will read."
              >
                <Input
                  id="sso-group-claim"
                  value={groupClaim}
                  onChange={(event) => setGroupClaim(event.target.value)}
                  data-testid={`${TestId}.GroupClaim`}
                />
              </Field>
            )}

            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <p className="text-[13px] text-ink-2">Create accounts on first sign-in</p>
                <p className="text-[12px] leading-[1.5] text-ink-4">
                  {/* Off by default, and the sentence says which direction is the safe one. */}
                  Anyone this provider authenticates gets an account here, with exactly what
                  accepting an invitation grants and nothing more. Off means only people who were
                  invited can get in.
                </p>
              </div>
              <Switch
                checked={jitProvisioning}
                onCheckedChange={setJitProvisioning}
                aria-label="Create accounts on first sign-in"
                data-testid={`${TestId}.JitProvisioning`}
              />
            </div>

            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <p className="text-[13px] text-ink-2">Link by verified email address</p>
                <p className="text-[12px] leading-[1.5] text-ink-4">
                  This provider’s word about an email address is enough to reach an account that
                  already exists here. An unverified address never links, whatever this says.
                </p>
              </div>
              <Switch
                checked={autoLinkVerifiedEmail}
                onCheckedChange={setAutoLinkVerifiedEmail}
                aria-label="Link by verified email address"
                data-testid={`${TestId}.AutoLink`}
              />
            </div>

            {editing && (
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <p className="text-[13px] text-ink-2">Live sign-in route</p>
                  <p className="text-[12px] leading-[1.5] text-ink-4">
                    Turning this on makes it a live sign-in route for everyone at your claimed
                    domains. Turning it off stops new sign-ins through it and keeps every account
                    that already came in.
                  </p>
                </div>
                <Switch
                  checked={enabled}
                  onCheckedChange={setEnabled}
                  aria-label="Live sign-in route"
                  data-testid={`${TestId}.Enabled`}
                />
              </div>
            )}
          </div>

          <DialogFooter>
            <Button variant="quiet" onClick={onClose} data-testid={`${TestId}.Cancel`}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={!submittable}
              data-testid={`${TestId}.Save`}
            >
              {save.isPending ? 'Saving…' : editing ? 'Save provider' : 'Register provider'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
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
    || field === 'bindDn'
    || field === 'bindPassword'
    || field === 'userSearchBase'
    || field === 'userSearchFilter'
    || field === 'userIdAttribute'
    || field === 'emailAttribute'
  );
}
