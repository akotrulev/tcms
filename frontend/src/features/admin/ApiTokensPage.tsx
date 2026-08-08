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
  Tooltip,
  Typography,
} from '@mui/material';
import { useMemo, useState } from 'react';
import { useApiTokens, useProjects, useUsers } from '../../api/queries';
import type { ApiToken } from '../../api/types';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import { formatDateTime } from '../../lib/format';
import { tokenScopeLabel, tokenState, tokenStateColors, tokenStateLabels } from '../../lib/apiTokens';
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
    <Box>
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
              <Tooltip title={mayIssue ? '' : reason}>
                {/* A disabled button fires no events, so the tooltip needs a wrapper that does. */}
                <Box component="span">
                  <Button
                    variant="contained"
                    disabled={!mayIssue}
                    onClick={() => setIssuing(true)}
                    data-testid={`${TestId}.Issue`}
                  >
                    Issue a token
                  </Button>
                </Box>
              </Tooltip>
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

      <Stack sx={{ gap: 3 }}>
        <Panel title="Tokens" testId={`${TestId}.Panel`} dense>
          {gate.pending ? null : !allowed ? (
            <Alert severity="info" data-testid={`${TestId}.Denied`}>
              You do not have permission to issue API tokens in this company, so this list is not
              shown. A company administrator can issue one for you.
            </Alert>
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
                <ScrollX>
                  <Table size="small" data-testid={`${TestId}.Grid`}>
                    <TableHead>
                      <TableRow>
                        <TableCell sx={{ color: 'text.secondary' }}>Name</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 150 }}>Starts with</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 150 }}>Role</TableCell>
                        <TableCell sx={{ color: 'text.secondary' }}>Where it works</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 160 }}>Owner</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 170 }}>Expires</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 150 }}>Last used</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 110 }}>Status</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 110 }} align="right">
                          {/* One action per row; the header names the column, not the verb. */}
                          Credential
                        </TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {rows.map((token) => (
                        <TokenRow
                          key={token.id}
                          token={token}
                          projectName={
                            token.projectId ? projectNames.get(token.projectId) : undefined
                          }
                          ownerName={userNames.get(token.ownerId)}
                          onRevoke={() => setRevoking(token)}
                        />
                      ))}
                    </TableBody>
                  </Table>
                </ScrollX>
              )}
            </DataRegion>
          )}
        </Panel>
      </Stack>
    </Box>
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
    <TableRow data-testid={RowTestId} hover sx={{ opacity: revoked ? 0.6 : 1 }}>
      <TableCell sx={{ fontWeight: 500 }}>
        {/*
          When it was issued is one hover away rather than a column of its own: a token is
          identified by its name and its prefix, and the date it was minted is a question for the
          audit log, which records it with the actor beside it.
        */}
        <Tooltip title={`Issued ${formatDateTime(token.createdAt)}`}>
          <Box component="span" data-testid={`${RowTestId}.Name`}>
            {token.name}
          </Box>
        </Tooltip>
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
      <TableCell sx={{ fontSize: '0.8125rem' }} data-testid={`${RowTestId}.Role`}>
        {/* The name, expanded by the server, because a uuid says nothing about what a leaked
            credential could do. */}
        {token.roleName}
      </TableCell>
      <TableCell sx={{ fontSize: '0.75rem' }} data-testid={`${RowTestId}.Scope`}>
        {tokenScopeLabel(token, projectName)}
      </TableCell>
      <TableCell sx={{ fontSize: '0.75rem' }} data-testid={`${RowTestId}.Owner`}>
        {/* Names, not ids — and a stranger's id rather than a blank, for `tokenScopeLabel`'s
            reason: the owner's own grants cap this token, so an unresolved one is a fact worth
            showing rather than an empty cell. */}
        {ownerName ?? token.ownerId}
      </TableCell>
      <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }} data-testid={`${RowTestId}.Expires`}>
        {token.expiresAt ? (
          formatDateTime(token.expiresAt)
        ) : (
          /*
            "Does not expire" as a positive statement, never an em dash. A blank here reads as a
            field somebody left empty, when it is in fact the more consequential of the two answers.
          */
          <Typography component="span" variant="caption">
            Does not expire
          </Typography>
        )}
      </TableCell>
      <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }} data-testid={`${RowTestId}.LastUsed`}>
        {token.lastUsedAt ? (
          formatDateTime(token.lastUsedAt)
        ) : (
          /*
            "Never used" as a positive statement, for the Expires cell's reason and one more: this
            is the column an administrator reads to decide what to revoke, and the answer they are
            looking for is exactly this one. A blank would read as "we don't know".

            The value is coarse by design (`tcms.api-token.last-used-resolution`, an hour by
            default) — the server does not write it on every request, because a CI token would take
            one row update per request. Rendered as a date-time and not "3 minutes ago": a relative
            phrase implies a precision the column does not have.
          */
          <Typography component="span" variant="caption">
            Never used
          </Typography>
        )}
      </TableCell>
      <TableCell>
        <Tooltip title={token.revokedAt ? `Revoked ${formatDateTime(token.revokedAt)}` : ''}>
          <Chip
            size="small"
            label={tokenStateLabels[state]}
            data-testid={`${RowTestId}.Status`}
            sx={{
              bgcolor: 'transparent',
              color: tokenStateColors[state],
              border: `1px solid color-mix(in srgb, ${tokenStateColors[state]} 45%, transparent)`,
            }}
          />
        </Tooltip>
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
            <Tooltip
              title={
                revoked
                  ? 'This token was already revoked. Revoking it again would change nothing.'
                  : allowed
                    ? ''
                    : reason
              }
            >
              <Box component="span">
                <Button
                  size="small"
                  color="error"
                  disabled={!allowed || revoked}
                  onClick={onRevoke}
                  data-testid={`${RowTestId}.Revoke`}
                >
                  Revoke
                </Button>
              </Box>
            </Tooltip>
          )}
        </PermissionGate>
      </TableCell>
    </TableRow>
  );
}
