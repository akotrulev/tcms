import { useState } from 'react';
import { useRevokeApiToken } from '../../api/queries';
import type { ApiToken } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Banner } from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
import { describeError } from '../../lib/describeError';
import { tokenState } from '../../lib/apiTokens';

/**
 * Withdrawing a credential — a destructive action, confirmed by name (§118).
 *
 * ## It names the token AND its prefix, because the name is not what the pipeline knows
 *
 * `ui-rules` requires a destructive confirmation to name its object by its friendly label. Here
 * that is necessary and not sufficient: two people can call a token "CI" a year apart, and the
 * thing an administrator can actually match against a CI configuration is the **prefix**. A
 * confirmation that named only "CI" would be answerable with confidence by somebody about to
 * break the wrong pipeline.
 *
 * ## What it warns about is the consequence, which is not reversible and is not local
 *
 * Revoking cannot be undone — there is no un-revoke, and the replacement is a *different*
 * credential that has to be pasted into whatever holds this one. The copy says so, because the
 * mental model this dialog is up against is a settings toggle.
 *
 * ## An expired token can still be revoked, and that is why this dialog exists for it too
 *
 * The row offers Revoke on an expired token and not on a revoked one (`tokenState`'s whole
 * reason). Revoking a lapsed credential is a real act — it closes it deliberately rather than
 * leaving it one date edit away from working again — so the copy acknowledges the token is
 * already dead rather than warning about an interruption that cannot happen.
 */

const TestId = 'Admin.TokenRevoke';

export function RevokeTokenDialog({
  open,
  token,
  onClose,
}: {
  open: boolean;
  token: ApiToken;
  onClose: () => void;
}) {
  const revoke = useRevokeApiToken();
  const [failure, setFailure] = useState<string | null>(null);
  const expired = tokenState(token) === 'EXPIRED';

  const submit = async () => {
    setFailure(null);
    try {
      await revoke.mutateAsync(token.id);
      onClose();
    } catch (error) {
      setFailure(describeError(error).body);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={TestId}>
        <DialogHeader>
          <DialogTitle>Revoke {token.name}?</DialogTitle>
        </DialogHeader>

        <div className="mt-4 flex flex-col gap-3 text-[13px] leading-[1.55] text-ink-2">
          {failure && (
            <div data-testid={`${TestId}.Error`}>
              <Banner tone="bad">
                <span role="alert">{failure}</span>
              </Banner>
            </div>
          )}

          <p>
            The credential starting <Identifier variant="strong">{token.tokenPrefix}</Identifier>{' '}
            {expired
              ? 'has already expired, so nothing is using it. Revoking it closes it for good and keeps the row as a record.'
              : 'stops working immediately. Anything using it — a CI job, a script — starts failing on its next run.'}
          </p>

          <p>
            This cannot be undone. Issuing a replacement produces a different token that has to be
            stored wherever this one is configured.
          </p>

          {/*
            Stated because the row not disappearing is the surprising part, and an administrator
            who expected it gone will otherwise click Revoke a second time looking for the effect.
          */}
          <div data-testid={`${TestId}.Kept`}>
            <Banner tone="neutral">
              <span>
                The row stays in the list, marked revoked — it is the record of what this credential
                did while it was live.
              </span>
            </Banner>
          </div>
        </div>

        <DialogFooter>
          <Button variant="quiet" onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            variant="danger"
            onClick={() => void submit()}
            disabled={revoke.isPending}
            data-testid={`${TestId}.Confirm`}
          >
            {revoke.isPending ? 'Revoking…' : 'Revoke token'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
