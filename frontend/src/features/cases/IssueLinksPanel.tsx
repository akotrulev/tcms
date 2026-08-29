import { buttonVariants } from '../../components/ui/buttonVariants';
import { Link2 } from 'lucide-react';
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
} from '../../components/ui';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { useCreateIssueLink, useDeleteIssueLink, useIssueLinks, useTrackerIssueSearch } from '../../api/queries';
import type { IssueLink, TrackerIssue } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { IssueChip } from '../../components/IssueChip';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { UnlinkDialog } from '../../components/UnlinkDialog';
import { describeError } from '../../lib/describeError';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { trackerUnavailableReasons } from '../../lib/labels';

/**
 * The tracker issues a case is linked to (004 Phase 3) — the `EXTERNAL_ISSUE` `doc_link` rows.
 *
 * ## Separate from Traceability, because it answers a different question
 *
 * `DocLinksPanel` above it is about **what this case covers** — a requirement, a specification, the
 * code under test. This is about **what is wrong with the thing it covers**, which is a different
 * relationship with a different lifetime: a defect is opened, fixed and closed while the case it is
 * about does not move. Folding the two into one list would put a closed bug beside the requirement
 * it was found against and call both "related".
 *
 * ## The rows are ours; only what a chip can say about one is not
 *
 * These come from our database and render whether or not anybody can reach the tracker. A binding
 * deleted, a token expired, a tracker down: the list is unchanged, the hrefs still work, and the
 * chips degrade. That is 004 D18, and it is the reason this region has no tracker-shaped failure
 * state of its own.
 */
export function IssueLinksPanel({
  projectId,
  caseId,
}: {
  projectId: string;
  caseId: string;
}) {
  const links = useIssueLinks(projectId, caseId);
  const [linking, setLinking] = useState(false);
  const [unlinking, setUnlinking] = useState<IssueLink | null>(null);
  const remove = useDeleteIssueLink(projectId, caseId);

  return (
    <Panel
      title="Issues"
      testId="CaseDetail.IssueLinks"
      actions={
        // Disabled rather than hidden (§31): a Viewer should be able to see the product can do
        // this. `case:edit` is the endpoint's gate, so it is the one asked about here.
        <PermissionGate projectId={projectId} permission="case:edit">
          {({ allowed }) => (
            <Button
              size="sm"
              disabled={!allowed}
              onClick={() => setLinking(true)}
              data-testid="CaseDetail.IssueLinks.AddButton"
            >
              <Link2 aria-hidden className="size-3.5" />
              Link an issue
            </Button>
          )}
        </PermissionGate>
      }
    >
      <DataRegion
        testId="CaseDetail.IssueLinks.Region"
        state={{ ...links, refetch: () => void links.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'no-records',
          title: 'No issues linked',
          body: 'Link a defect or a story here, and it shows its live title and status to anybody who has connected their tracker account.',
        }}
      >
        {(rows) => (
          <div className="flex flex-col gap-2 p-3" data-testid="CaseDetail.IssueLinks.List">
            {rows.map((link) => (
              <div className="flex items-center gap-2 justify-between" key={link.id} data-testid="CaseDetail.IssueLinks.Row">
                <IssueChip projectId={projectId} link={link} />
                <PermissionGate projectId={projectId} permission="case:edit">
                  {({ allowed }) => (
                    <Button
                      disabled={!allowed}
                      onClick={() => setUnlinking(link)}
                      data-testid="CaseDetail.IssueLinks.Remove"
                    >
                      Remove
                    </Button>
                  )}
                </PermissionGate>
              </div>
            ))}
          </div>
        )}
      </DataRegion>

      {linking && (
        <LinkIssueDialog projectId={projectId} caseId={caseId} onClose={() => setLinking(false)} />
      )}
      <UnlinkDialog
        open={unlinking !== null}
        /*
          The **id**, never anything parsed out of the href (D17). It is the one string on the row
          that is never wrong, and a confirmation that named a stale key would be asking somebody to
          confirm the removal of an issue that is not the one they clicked.
        */
        target={unlinking ? `${unlinking.provider} issue ${unlinking.issueId}` : ''}
        consequence="The issue stays exactly as it is in the tracker. What is removed is the record that it is about this case."
        onClose={() => setUnlinking(null)}
        onConfirm={() =>
          unlinking && remove.mutate(unlinking.id, { onSuccess: () => setUnlinking(null) })
        }
        pending={remove.isPending}
        error={remove.error}
        reset={remove.reset}
        testId="CaseDetail.IssueLinks.UnlinkDialog"
      />
    </Panel>
  );
}

/**
 * Finding an issue and linking it.
 *
 * **The typeahead is the only way in, and that is a consequence of id-only rather than a
 * simplification.** What gets stored is the tracker's stable id and the binding it belongs to, and
 * neither is something a person can type: what they know is `PAY-991`, which is a key, which moves.
 * The search returns both, so picking a result is what makes the link well-formed.
 *
 * A viewer who has not connected therefore cannot link, and the dialog says so with the way out
 * rather than offering a box that will never return a result. That is the hidden-with-a-reason rule
 * the defect panel applies to filing, applied to linking.
 */
function LinkIssueDialog({
  projectId,
  caseId,
  onClose,
}: {
  projectId: string;
  caseId: string;
  onClose: () => void;
}) {
  const [term, setTerm] = useState('');
  // The typeahead's own budget is 3s; debouncing keeps the request count to what somebody typing
  // actually intends, which matters more here than usual — every one reaches somebody else's server.
  const debounced = useDebouncedValue(term, 250);
  const search = useTrackerIssueSearch(projectId, undefined, debounced);
  const create = useCreateIssueLink(projectId, caseId);

  const link = (issue: TrackerIssue) =>
    create.mutate(
      {
        // All three from the search result, none from anything the person typed: the id is the
        // identity, the binding is what makes it one, and the URL is the tracker's own.
        bindingId: issue.bindingId,
        issueId: issue.id,
        url: issue.url,
      },
      { onSuccess: onClose },
    );

  const unavailable =
    search.data?.state === 'NOT_CONNECTED' || search.data?.state === 'UNAVAILABLE';

  return (
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid="CaseDetail.IssueLinks.Dialog">
      <DialogHeader>
        <DialogTitle>Link an issue</DialogTitle>
      </DialogHeader>
      <div>
        <div className="flex flex-col gap-4 mt-2">
          <Field
            label="Search your tracker"
            htmlFor="search-your-tracker"
            help="Searched as you, with your own credential — you will only find issues you can already see."
          >
          <Input
            id="search-your-tracker"
            value={term}
            onChange={(e) => setTerm(e.target.value)}
            data-testid="CaseDetail.IssueLinks.Search"
          />
          </Field>
          {unavailable && (
            <div data-testid="CaseDetail.IssueLinks.Unavailable">
<Banner tone="steel">
              <div className="flex flex-col gap-2 items-start">
                <p className="m-0 text-sm">
                  {search.data?.state === 'NOT_CONNECTED'
                    ? trackerUnavailableReasons.NOT_CONNECTED
                    : trackerUnavailableReasons.UNAVAILABLE}
                </p>
                {search.data?.state === 'NOT_CONNECTED' && (
                  <RouterLink
                    to="/account/trackers"
                    data-testid="CaseDetail.IssueLinks.Connect"
                    className={buttonVariants({ size: 'sm' })}
                  >
                    Connect your tracker
                  </RouterLink>
                )}
              </div>
            </Banner>
</div>
          )}
          {search.data?.state === 'AVAILABLE' && (
            <ul className="m-0 list-none p-0" data-testid="CaseDetail.IssueLinks.Results">
              {search.data.issues.map((issue) => (
                <li key={`${issue.bindingId}:${issue.id}`}>
                  <button
                    type="button"
                    onClick={() => link(issue)}
                    disabled={create.isPending}
                    data-testid="CaseDetail.IssueLinks.Result"
                    className="block w-full cursor-pointer px-4 py-1.5 text-left hover:bg-wash-2 disabled:cursor-not-allowed disabled:opacity-45"
                  >
                    <span className="block text-[13px] text-ink">
                      {issue.key} · {issue.title}
                    </span>
                    <span className="block text-xs text-ink-2">{issue.statusName}</span>
                  </button>
                </li>
              ))}
              {search.data.issues.length === 0 && (
                <div className="px-4 py-2">
                  <p className="m-0 text-sm text-ink-2">
                    Nothing matched.
                  </p>
                </div>
              )}
            </ul>
          )}
          {create.error != null && (
            <div data-testid="CaseDetail.IssueLinks.Error">
<Banner tone="bad"><span role="alert">
              {describeError(create.error).body}
            </span></Banner>
</div>
          )}
        </div>
      </div>
      <DialogFooter>
        <Button onClick={onClose} data-testid="CaseDetail.IssueLinks.Cancel">
          Close
        </Button>
      </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
