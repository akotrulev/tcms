import { useMemo, useState } from 'react';
import { useApiTokens, useProjects, useUsers } from '../../api/queries';
import type { ApiToken } from '../../api/types';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import {
  Badge,
  Banner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  type BadgeTone,
} from '../../components/kit';
import { Button } from '../../components/ui';
import { formatDateTime } from '../../lib/format';
import {
  tokenScopeLabel,
  tokenState,
  tokenStateLabels,
  type ApiTokenState,
} from '../../lib/apiTokens';
import { IssueTokenDialog } from './IssueTokenDialog';
import { RevokeTokenDialog } from './RevokeTokenDialog';

/**
 * API and CI tokens — the screen §116 and §117 both named on their way out (§118).
 *
 * `api_token` got its writer in §116 and CI result ingestion got its caller in §117, and between
 * them the only way to obtain a credential was `curl`. Everything M3 does — a pipeline posting a
 * JUnit report, a run materialised by a machine — starts with a token that until now nobody could
 * issue from the product.
 *
 * ## The read is REFUSED, not narrowed, so this screen asks before it fetches
 *
 * `GET /api-tokens` 403s for a caller without `token:issue` rather than answering `[]`, because an
 * empty array would tell somebody who may simply not ask that this company has no credentials.
 * That is the right server behaviour and it makes an ungated read the wrong client behaviour: a
 * Test Lead opening this page would fire a request that fails and raise a global "Something didn't
 * load" over a screen that is working exactly as designed. So the gate's verdict drives `enabled`,
 * and the refusal is rendered as a sentence naming what is missing.
 *
 * **Deferred while the verdict is pending, never rendered as a denial.** `Gate.can` is fail-closed
 * while loading, which is right for disabling a button and wrong here — it would flash "you do not
 * have permission" at a Company Admin on every visit. `Gate.pending` exists for exactly this.
 *
 * ## ~~`lastUsedAt` is on the wire and is NOT a column~~ → it is one, since §132
 *
 * It was not, and the reason was the `ui-rules` rule against shipping a column the write path never
 * populates: the field was on the wire and **always null**, so "Last used" would have been empty on
 * every row of every deployment for ever. *"The day it gets a writer, this is a one-line change"* —
 * it got one, and it was.
 *
 * What the writer decided still shows here. The value is kept only to within
 * `tcms.api-token.last-used-resolution` (an hour by default), because the naive alternative is one
 * `UPDATE` per authenticated request on a busy CI token's single row. So the cell renders a
 * date-time and never a relative phrase: "3 minutes ago" claims a precision this column does not
 * have, and the question it exists to answer — *which credentials can I revoke* — cannot tell an
 * hour from a second.
 *
 * ## The rows are not re-sorted, and revoked ones stay
 *
 * The server orders newest first and the list is complete, so there is nothing for the client to
 * re-rank — sorting live rows above revoked ones is the tempting version and it puts the row an
 * administrator is looking for (the one they just revoked) somewhere they are not looking. Revoked
 * rows are evidence: a credential that was live in a pipeline yesterday explains a log entry, and
 * dropping it reads as though it never existed.
 */

const TestId = 'Admin.Tokens';
const RowTestId = `${TestId}.Row`;

/**
 * The three states, as badge tones. `lib/apiTokens.ts`' `tokenStateColors` beside the labels is
 * the same three in CSS custom properties and has one consumer, this line; the two collapse when
 * MUI goes and the labels file stops being shared with lanes that still need the old spelling.
 */
const STATE_TONE = {
  LIVE: 'passed',
  EXPIRED: 'blocked',
  REVOKED: 'plain',
} as const satisfies Record<ApiTokenState, BadgeTone>;

export function ApiTokensPage() {
  const gate = useCanInCompany();
  const allowed = gate.can('token:issue');
  const tokens = useApiTokens(allowed);
  const projects = useProjects();
  const users = useUsers();
  const [issuing, setIssuing] = useState(false);
  /*
    The token being revoked, not a boolean: the dialog names it and reads its prefix and expiry, so
    it needs the row. Held here so exactly one confirmation can be open, and cleared on close so
    the next one mounts fresh (§54).
  */
  const [revoking, setRevoking] = useState<ApiToken | null>(null);

  const projectNames = useMemo(
    () => new Map((projects.data ?? []).map((project) => [project.id, project.name])),
    [projects.data],
  );
  const userNames = useMemo(
    () => new Map((users.data ?? []).map((user) => [user.id, user.displayName])),
    [users.data],
  );

  return (
    <div>
      <PageHeader
        testId={`${TestId}.Header`}
        title="API tokens"
        documentTitle="API tokens"
        crumbs={[{ label: 'Administration' }, { label: 'API tokens' }]}
        subtitle="Credentials a CI job presents to post results into this instance. Each one carries a role and belongs to the person who issued it."
        actions={
          /*
            GATED, in §31's ordinary form: the permission is known before anything renders —
            `token:issue`, company scope, always — so the button is disabled with its reason rather
            than hidden. This is `New team`'s shape, not `Grant a role`'s: nothing inside the
            dialog can change which permission the server will check.
          */
          <PermissionGate projectId="company" permission="token:issue">
            {({ allowed: mayIssue, reason }) => (
              <Button
                variant="primary"
                disabled={!mayIssue}
                title={mayIssue ? undefined : reason}
                onClick={() => setIssuing(true)}
                data-testid={`${TestId}.Issue`}
              >
                Issue a token
              </Button>
            )}
          </PermissionGate>
        }
      />

      {/* Conditionally mounted, like every other create dialog in this area — and here it also
          decides the credential's lifetime: unmounting is what discards the issued secret. */}
      {issuing && <IssueTokenDialog open onClose={() => setIssuing(false)} />}

      {revoking && (
        <RevokeTokenDialog open token={revoking} onClose={() => setRevoking(null)} />
      )}

      <section data-testid={`${TestId}.Panel`}>
        <h2 className="mb-2.5 font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
          Tokens
        </h2>
        {gate.pending ? null : !allowed ? (
          <Banner tone="neutral" data-testid={`${TestId}.Denied`}>
            {/* The permission and the role that can grant it — a person cannot be named here. */}
            <span>
              Issuing API tokens needs <Identifier variant="muted">token:issue</Identifier>, which
              you do not hold, so this list is not shown. A company administrator can issue one for
              you.
            </span>
          </Banner>
        ) : (
          <DataRegion
            testId={`${TestId}.List`}
            state={{ ...tokens, refetch: () => void tokens.refetch() }}
            isEmpty={(rows) => rows.length === 0}
            empty={{
              kind: 'no-records',
              title: 'No tokens yet',
              body: 'Issue one so a CI job can post its results into this instance.',
            }}
          >
            {(rows) => (
              <Table data-testid={`${TestId}.Grid`} className="min-w-[1080px]">
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>Name</TableHeaderCell>
                    <TableHeaderCell className="w-[150px]">Starts with</TableHeaderCell>
                    <TableHeaderCell className="w-[150px]">Role</TableHeaderCell>
                    <TableHeaderCell>Where it works</TableHeaderCell>
                    <TableHeaderCell className="w-[160px]">Owner</TableHeaderCell>
                    <TableHeaderCell className="w-[170px]">Expires</TableHeaderCell>
                    <TableHeaderCell className="w-[150px]">Last used</TableHeaderCell>
                    <TableHeaderCell className="w-[110px]">Status</TableHeaderCell>
                    <TableHeaderCell className="w-[110px]" align="right">
                      {/* One action per row; the header names the column, not the verb. */}
                      Credential
                    </TableHeaderCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((token) => (
                    <TokenRow
                      key={token.id}
                      token={token}
                      projectName={token.projectId ? projectNames.get(token.projectId) : undefined}
                      ownerName={userNames.get(token.ownerId)}
                      onRevoke={() => setRevoking(token)}
                    />
                  ))}
                </TableBody>
              </Table>
            )}
          </DataRegion>
        )}
      </section>
    </div>
  );
}

/**
 * One token.
 *
 * The row locator is a hardcoded const and carries no id — `test-ids` rule 6: a test addresses
 * rows by index over the shared selector, and a locator built from a uuid is unaddressable.
 */
function TokenRow({
  token,
  projectName,
  ownerName,
  onRevoke,
}: {
  token: ApiToken;
  projectName: string | undefined;
  ownerName: string | undefined;
  onRevoke: () => void;
}) {
  const state = tokenState(token);
  const revoked = state === 'REVOKED';

  return (
    /*
      Rule 7 of the design language, and the render's own note for this family: *revoked and
      expired stay on screen, at reduced weight, with the date and the reason.* An administration
      table that removes dead rows makes an audit impossible and a repeat mistake likely — this
      row is what explains a log entry from a credential that no longer works.
    */
    <TableRow data-testid={RowTestId} className={revoked ? 'opacity-60' : undefined}>
      <TableCell variant="title">
        {/*
          When it was issued is one hover away rather than a column of its own: a token is
          identified by its name and its prefix, and the date it was minted is a question for the
          audit log, which records it with the actor beside it.
        */}
        <span title={`Issued ${formatDateTime(token.createdAt)}`} data-testid={`${RowTestId}.Name`}>
          {token.name}
        </span>
      </TableCell>
      <TableCell>
        {/*
          The prefix in the identifier font and its own column, so twelve-character fragments align
          down the page — this is the value an administrator visually matches against a CI
          configuration, and it is the whole reason the server discloses part of the secret.
        */}
        <Identifier variant="muted" testId={`${RowTestId}.Prefix`}>
          {token.tokenPrefix}
        </Identifier>
      </TableCell>
      <TableCell data-testid={`${RowTestId}.Role`}>
        {/* The name, expanded by the server, because a uuid says nothing about what a leaked
            credential could do. */}
        {token.roleName}
      </TableCell>
      <TableCell className="text-[11.5px]" data-testid={`${RowTestId}.Scope`}>
        {tokenScopeLabel(token, projectName)}
      </TableCell>
      <TableCell className="text-[11.5px]" data-testid={`${RowTestId}.Owner`}>
        {/* Names, not ids — and a stranger's id rather than a blank, for `tokenScopeLabel`'s
            reason: the owner's own grants cap this token, so an unresolved one is a fact worth
            showing rather than an empty cell. */}
        {ownerName ?? token.ownerId}
      </TableCell>
      <TableCell className="text-[11.5px] text-ink-4" data-testid={`${RowTestId}.Expires`}>
        {/*
          "Does not expire" as a positive statement, never an em dash. A blank here reads as a
          field somebody left empty, when it is in fact the more consequential of the two answers.
        */}
        {token.expiresAt ? formatDateTime(token.expiresAt) : 'Does not expire'}
      </TableCell>
      <TableCell className="text-[11.5px] text-ink-4" data-testid={`${RowTestId}.LastUsed`}>
        {/*
          "Never used" as a positive statement, for the Expires cell's reason and one more: this
          is the column an administrator reads to decide what to revoke, and the answer they are
          looking for is exactly this one. A blank would read as "we don't know".

          The value is coarse by design (`tcms.api-token.last-used-resolution`, an hour by
          default) — the server does not write it on every request, because a CI token would take
          one row update per request. Rendered as a date-time and not "3 minutes ago": a relative
          phrase implies a precision the column does not have.
        */}
        {token.lastUsedAt ? formatDateTime(token.lastUsedAt) : 'Never used'}
      </TableCell>
      <TableCell>
        {/* The date and the reason travel with the dimmed row — rule 7 asks for both, and the
            date is the half a revoked row cannot be read without. */}
        <span title={token.revokedAt ? `Revoked ${formatDateTime(token.revokedAt)}` : undefined}>
          <Badge tone={STATE_TONE[state]} data-testid={`${RowTestId}.Status`}>
            {tokenStateLabels[state]}
          </Badge>
        </span>
        {token.revokedAt && (
          <span className="mt-0.5 block text-[11px] text-ink-4">
            {formatDateTime(token.revokedAt)}
          </span>
        )}
      </TableCell>
      <TableCell align="right">
        {/*
          Two independent reasons this is disabled and they carry DIFFERENT sentences, §31's rule
          being about the reason as much as the state: not holding `token:issue`, which
          `PermissionGate` answers, and the token already being revoked, which only the row can
          answer. An EXPIRED token is deliberately still revocable — see `tokenState`.
        */}
        <PermissionGate projectId="company" permission="token:issue">
          {({ allowed, reason }) => (
            <Button
              size="sm"
              variant="danger"
              disabled={!allowed || revoked}
              title={
                revoked
                  ? 'This token was already revoked. Revoking it again would change nothing.'
                  : allowed
                    ? undefined
                    : reason
              }
              onClick={onRevoke}
              data-testid={`${RowTestId}.Revoke`}
            >
              Revoke
            </Button>
          )}
        </PermissionGate>
      </TableCell>
    </TableRow>
  );
}
