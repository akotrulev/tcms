import {
  Alert,
  Box,
  Button,
  Chip,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { useEmailDomains, useIdentityProviders, useSaveEmailDomains } from '../../api/queries';
import type { EmailDomains, IdentityProvider } from '../../api/types';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { ScrollX } from '../../components/ScrollX';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';
import { ProviderFormDialog } from './ProviderFormDialog';
import type { UseQueryResult } from '@tanstack/react-query';

/**
 * A company's external issuers (§222) — `company:idp`'s first screen, one slice after the contract
 * that shipped without one.
 *
 * ## The read is refused, not narrowed, so this screen asks before it fetches
 *
 * `GET /identity-providers` 403s for a caller without `company:idp` rather than answering `[]`.
 * `ApiTokensPage`'s reasoning applies unchanged: an ungated read turns a correct refusal into
 * "Something didn't load" over a page that is working as designed, so the gate's verdict drives
 * `enabled` and the refusal is rendered here as a sentence. **Deferred while the verdict is
 * pending, never rendered as a denial** — `Gate.can` is fail-closed while loading, which is right
 * for a button and would flash "you do not have permission" at a Company Admin on every visit.
 *
 * ## There is no delete, and its absence is the design
 *
 * `user_identity` rows reference a provider, so removing one would orphan or cascade away every
 * federated account in the company. `enabled: false` is the off switch, and a company that turned
 * single sign-on off for a week gets its people back with their authorship intact. The contract has
 * no `DELETE` for this resource at all — this screen is not hiding one.
 *
 * ## Disabled rows stay, and registration cannot enable
 *
 * A list that hid disabled providers could not be used to enable one — and registration always
 * produces a disabled provider, because an issuer URL typed wrong is a sign-in route that refuses
 * everybody. Going live is a second, deliberate act on the edit form.
 */

const TestId = 'Admin.Sso';
const RowTestId = `${TestId}.Row`;

export function SsoProvidersPage() {
  const gate = useCanInCompany();
  const allowed = !gate.pending && gate.can('company:idp');
  const providers = useIdentityProviders(allowed);
  const domains = useEmailDomains(allowed);
  /*
    `undefined` is "no dialog", `null` is "registering", a row is "configuring that one". Three
    states in one variable because the dialog is one form and its mode is exactly this distinction;
    cleared on close so the next one mounts fresh (§54).
  */
  const [editing, setEditing] = useState<IdentityProvider | null | undefined>(undefined);

  return (
    <Box>
      <PageHeader
        testId={`${TestId}.Header`}
        title="Single sign-on"
        documentTitle="Single sign-on"
        crumbs={[{ label: 'Administration' }, { label: 'Single sign-on' }]}
        subtitle="The issuers your people sign in through, and the email domains this company claims. A provider is a live sign-in route only once somebody turns it on."
        actions={
          allowed ? (
            <Button
              variant="contained"
              onClick={() => setEditing(null)}
              data-testid={`${TestId}.Register`}
            >
              Register a provider
            </Button>
          ) : null
        }
      />

      {editing !== undefined && (
        <ProviderFormDialog provider={editing} onClose={() => setEditing(undefined)} />
      )}

      <Stack sx={{ gap: 3 }}>
        <Panel title="Providers" testId={`${TestId}.Panel`} dense>
          {gate.pending ? null : !allowed ? (
            <Alert severity="info" data-testid={`${TestId}.Denied`}>
              {/*
                A sentence, not an empty table and not an error. The list is refused rather than
                narrowed, so "no providers" is a fact this caller is not entitled to and rendering
                it as one would be a confident falsehood.
              */}
              Configuring identity providers is a company administrator’s job, so this list is not
              shown. Ask one of yours to set single sign-on up.
            </Alert>
          ) : (
            <DataRegion
              testId={`${TestId}.List`}
              state={{ ...providers, refetch: () => void providers.refetch() }}
              isEmpty={(rows) => rows.length === 0}
              empty={{
                kind: 'no-records',
                title: 'No provider yet',
                body: 'Register your issuer to let this company sign in with its own accounts.',
              }}
            >
              {(rows) => (
                <ScrollX>
                  <Table size="small" data-testid={`${TestId}.Grid`}>
                    <TableHead>
                      <TableRow>
                        <TableCell sx={{ color: 'text.secondary' }}>Name</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 150 }}>Key</TableCell>
                        <TableCell sx={{ color: 'text.secondary' }}>Issuer</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 110 }}>Status</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 170 }}>Changed</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 120 }} align="right">
                          Configuration
                        </TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {rows.map((provider) => (
                        <ProviderRow
                          key={provider.id}
                          provider={provider}
                          onConfigure={() => setEditing(provider)}
                        />
                      ))}
                    </TableBody>
                  </Table>
                </ScrollX>
              )}
            </DataRegion>
          )}
        </Panel>

        {allowed && <EmailDomainsPanel query={domains} />}
      </Stack>
    </Box>
  );
}

/**
 * One provider.
 *
 * The row locator is a hardcoded const and carries no id — `test-ids` rule 6: a test addresses rows
 * by index over the shared selector, and a locator built from a uuid is unaddressable.
 *
 * **Nothing here shows or offers to reveal the client secret**, because nothing in this app has it:
 * the read shape carries `clientSecretSet` and no property that could hold the value.
 */
function ProviderRow({
  provider,
  onConfigure,
}: {
  provider: IdentityProvider;
  onConfigure: () => void;
}) {
  return (
    <TableRow data-testid={RowTestId} hover sx={{ opacity: provider.enabled ? 1 : 0.6 }}>
      <TableCell sx={{ fontWeight: 500 }} data-testid={`${RowTestId}.Name`}>
        {provider.displayName}
      </TableCell>
      <TableCell>
        <Identifier variant="muted" testId={`${RowTestId}.Key`}>
          {provider.key}
        </Identifier>
      </TableCell>
      <TableCell>
        <Identifier variant="muted" testId={`${RowTestId}.Issuer`}>
          {provider.issuerUri}
        </Identifier>
      </TableCell>
      <TableCell data-testid={`${RowTestId}.Status`}>
        {/*
          "Disabled" as a positive statement rather than a blank or a missing chip. It is the state
          every provider starts in, and it is the one an administrator is looking for when they
          come here wondering why nobody can sign in.
        */}
        <Chip
          size="small"
          variant="outlined"
          color={provider.enabled ? 'success' : 'default'}
          label={provider.enabled ? 'Live' : 'Disabled'}
        />
      </TableCell>
      <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }} data-testid={`${RowTestId}.Updated`}>
        {formatDateTime(provider.updatedAt)}
      </TableCell>
      <TableCell align="right">
        <Button size="small" onClick={onConfigure} data-testid={`${RowTestId}.Configure`}>
          Configure
        </Button>
      </TableCell>
    </TableRow>
  );
}

/**
 * The domains this company claims — the input side of what `POST /auth/providers` resolves.
 *
 * **A claim, not proof of ownership.** Several companies may claim one domain, and the endpoint
 * answers every provider reached by every claim: a rejection would leak that somebody else already
 * had it, which is the client roster discovery exists to withhold. So the panel says what a claim
 * does rather than implying exclusivity.
 *
 * The whole set is replaced on save, which is the unit an administrator thinks in and the shape the
 * endpoint takes — there is no lifecycle on a claim to preserve.
 */
function EmailDomainsPanel({ query }: { query: UseQueryResult<EmailDomains> }) {
  const save = useSaveEmailDomains();
  const [adding, setAdding] = useState('');

  const domains = query.data?.domains ?? [];

  const replaceWith = (next: string[]) => {
    save.mutate(next);
  };

  return (
    <Panel title="Claimed email domains" testId={`${TestId}.Domains`} dense>
      <Stack sx={{ gap: 2 }}>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Somebody typing an address at one of these domains on the sign-in screen is offered this
          company’s providers. A claim is not proof of ownership — a domain may be claimed by more
          than one company, and each of them is offered its own.
        </Typography>

        {save.isError && (
          <Alert severity="error" role="alert" data-testid={`${TestId}.Domains.Error`}>
            {describeError(save.error).body}
          </Alert>
        )}

        <DataRegion
          testId={`${TestId}.Domains.Region`}
          state={{ ...query, refetch: () => void query.refetch() }}
          isEmpty={(value) => value.domains.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No domain claimed',
            body: 'Until one is claimed, nobody reaches these providers from the sign-in screen.',
          }}
        >
          {(value) => (
            <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }} data-testid={`${TestId}.Domains.List`}>
              {value.domains.map((domain) => (
                <Chip
                  key={domain}
                  label={domain}
                  data-testid={`${TestId}.Domains.Chip`}
                  onDelete={() => replaceWith(value.domains.filter((other) => other !== domain))}
                />
              ))}
            </Stack>
          )}
        </DataRegion>

        <Stack direction="row" sx={{ gap: 1, alignItems: 'flex-start' }}>
          <TextField
            size="small"
            label="Add a domain"
            value={adding}
            onChange={(event) => setAdding(event.target.value)}
            helperText="A bare domain — acme.test, not an address."
            slotProps={{ htmlInput: { 'data-testid': `${TestId}.Domains.Field` } }}
          />
          <Button
            sx={{ mt: 0.5 }}
            disabled={adding.trim() === '' || save.isPending}
            onClick={() => {
              const domain = adding.trim().toLowerCase();
              // Sent as the whole set, deduped here only so pressing Add twice does not put the
              // same claim in the array twice — the server stores a set either way.
              if (!domains.includes(domain)) replaceWith([...domains, domain]);
              setAdding('');
            }}
            data-testid={`${TestId}.Domains.Add`}
          >
            Add
          </Button>
        </Stack>
      </Stack>
    </Panel>
  );
}
