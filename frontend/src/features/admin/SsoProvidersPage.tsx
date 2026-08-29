import { useState } from 'react';
import { useEmailDomains, useIdentityProviders, useSaveEmailDomains } from '../../api/queries';
import type { EmailDomains, IdentityProvider } from '../../api/types';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import {
  Badge,
  Banner,
  Chip,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input } from '../../components/ui';
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
    <div>
      <PageHeader
        testId={`${TestId}.Header`}
        title="Single sign-on"
        documentTitle="Single sign-on"
        crumbs={[{ label: 'Administration' }, { label: 'Single sign-on' }]}
        subtitle="The issuers your people sign in through, and the email domains this company claims. A provider is a live sign-in route only once somebody turns it on."
        actions={
          allowed ? (
            <Button
              variant="primary"
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

      <div className="flex flex-col gap-7">
        <section data-testid={`${TestId}.Panel`}>
          <h2 className="mb-2.5 font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
            Providers
          </h2>
          {gate.pending ? null : !allowed ? (
            <Banner tone="neutral" data-testid={`${TestId}.Denied`}>
              {/*
                A sentence, not an empty table and not an error. The list is refused rather than
                narrowed, so "no providers" is a fact this caller is not entitled to and rendering
                it as one would be a confident falsehood. It names the permission and the role
                that holds it — a person cannot be named on this codebase.
              */}
              <span>
                Configuring single sign-on needs <Identifier variant="muted">company:idp</Identifier>
                , which you do not hold, so this list is not shown. Ask a company administrator to
                set it up.
              </span>
            </Banner>
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
                <Table data-testid={`${TestId}.Grid`}>
                  <TableHead>
                    <TableRow>
                      <TableHeaderCell>Name</TableHeaderCell>
                      <TableHeaderCell className="w-[150px]">Key</TableHeaderCell>
                      <TableHeaderCell>Issuer</TableHeaderCell>
                      <TableHeaderCell className="w-[110px]">Status</TableHeaderCell>
                      <TableHeaderCell className="w-[170px]">Changed</TableHeaderCell>
                      <TableHeaderCell className="w-[120px]" align="right">
                        Configuration
                      </TableHeaderCell>
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
              )}
            </DataRegion>
          )}
        </section>

        {allowed && <EmailDomainsPanel query={domains} />}
      </div>
    </div>
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
    // Rule 7: a disabled provider stays listed and reads as retired. A list that hid them could
    // not be used to enable one, and registration always produces a disabled provider.
    <TableRow data-testid={RowTestId} className={provider.enabled ? undefined : 'opacity-60'}>
      <TableCell variant="title" data-testid={`${RowTestId}.Name`}>
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
          "Disabled" as a positive statement rather than a blank or a missing badge. It is the
          state every provider starts in, and it is the one an administrator is looking for when
          they come here wondering why nobody can sign in.
        */}
        <Badge tone={provider.enabled ? 'passed' : 'plain'}>
          {provider.enabled ? 'Live' : 'Disabled'}
        </Badge>
      </TableCell>
      <TableCell className="text-[11.5px] text-ink-4" data-testid={`${RowTestId}.Updated`}>
        {formatDateTime(provider.updatedAt)}
      </TableCell>
      <TableCell align="right">
        <Button size="sm" onClick={onConfigure} data-testid={`${RowTestId}.Configure`}>
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
    <section data-testid={`${TestId}.Domains`}>
      <h2 className="mb-2 font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
        Claimed email domains
      </h2>

      <div className="flex flex-col gap-3">
        <p className="max-w-[74ch] text-[12.5px] leading-[1.55] text-ink-3">
          Somebody typing an address at one of these domains on the sign-in screen is offered this
          company’s providers. A claim is not proof of ownership — a domain may be claimed by more
          than one company, and each of them is offered its own.
        </p>

        {save.isError && (
          <Banner tone="bad" data-testid={`${TestId}.Domains.Error`}>
            <span role="alert">{describeError(save.error).body}</span>
          </Banner>
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
            <div className="flex flex-wrap gap-1.5" data-testid={`${TestId}.Domains.List`}>
              {/*
                A chip and not a badge: a claim is a value this reader owns and can take back, and
                the kit makes that distinction a type rather than a review comment. `onClick` is
                required by `Chip` and there is nothing behind the label itself, so it selects the
                domain into the field beside the list — which is also the fastest way to re-add one
                removed by mistake.
              */}
              {value.domains.map((domain) => (
                <Chip
                  key={domain}
                  data-testid={`${TestId}.Domains.Chip`}
                  onClick={() => setAdding(domain)}
                  onRemove={() => replaceWith(value.domains.filter((other) => other !== domain))}
                >
                  {domain}
                </Chip>
              ))}
            </div>
          )}
        </DataRegion>

        <div className="flex items-end gap-2">
          <Field
            label="Add a domain"
            htmlFor="sso-domain"
            help="A bare domain — acme.test, not an address."
            className="w-[260px]"
          >
            <Input
              id="sso-domain"
              value={adding}
              onChange={(event) => setAdding(event.target.value)}
              data-testid={`${TestId}.Domains.Field`}
            />
          </Field>
          <Button
            className="mb-5"
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
        </div>
      </div>
    </section>
  );
}
