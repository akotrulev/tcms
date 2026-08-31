import type { IssueStatusKind } from '../../api/types';
import { Badge } from '../../components/kit';
import { issueStatusKindTones } from '../../lib/labels';

/**
 * A status, in the project's own words, toned by what it MEANS.
 *
 * The two halves are deliberately separate and this component is the only place they meet. The
 * **name** is a row (`issue_status.name`) — `In Review`, `Ready for QA`, whatever the project
 * called it — and there is no label map behind it, because the words are not the SPA's to choose.
 * The **tone** comes from `kind`, which is what a status means (D2), and is the whole reason that
 * field exists on the wire: a percentage and a colour both have to be computable without knowing
 * what a project named its columns.
 *
 * ~~`workItemStatusLabels`, a five-member map.~~ → deleted with `WorkItemStatus` (011 D-c). The
 * failure that map guarded against is now impossible in a different way: there is no code to
 * mistranslate, because the server sends the words.
 */
export function StatusBadge({
  status,
  ...rest
}: {
  status: { name: string; kind: IssueStatusKind };
  'data-testid'?: string;
}) {
  return (
    <Badge tone={issueStatusKindTones[status.kind]} data-testid={rest['data-testid']}>
      {status.name}
    </Badge>
  );
}
