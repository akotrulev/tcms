import { useDeleteAllocation } from '../../api/queries';
import type { Allocation } from '../../api/types';
import { PermissionGate } from '../../components/PermissionGate';
import { Banner, TableCell, TableRow } from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
import { describeError } from '../../lib/describeError';
import { formatDate } from '../../lib/format';
import { allocationLabel } from './capacityCopy';

/**
 * One allocation on `/projects/:projectId/staffing` — who, how much, and for how long.
 *
 * Both writes cost `staffing:allocate` and are **disabled with a reason, never hidden** (§31): a
 * Test Lead who can read this screen and cannot change it learns that allocating is a thing this
 * product does and is worth asking for. `PermissionGate` owns the tooltip-anchor half of that,
 * which is the half that is silently wrong when a call site spells it itself.
 *
 * The dates render through `formatDate` rather than as the ISO strings the wire carries, in their
 * own columns rather than joined: a range in one cell reads as a single value and sorts as one,
 * and the reader scanning for *"who comes off in October"* is scanning one column.
 */
export interface AllocationRowProps {
  projectId: string;
  allocation: Allocation;
  onEdit: () => void;
  onRemove: () => void;
  testId: string;
}

export function AllocationRow({
  projectId,
  allocation,
  onEdit,
  onRemove,
  testId,
}: AllocationRowProps) {
  return (
    <TableRow data-testid={testId}>
      <TableCell variant="title">{allocation.person.displayName}</TableCell>
      <TableCell data-testid={`${testId}.Percent`}>
        {allocationLabel(allocation.percent)}
      </TableCell>
      <TableCell>{formatDate(allocation.startsOn)}</TableCell>
      <TableCell>{formatDate(allocation.endsOn)}</TableCell>
      <TableCell>
        {allocation.note ?
          <span className="text-ink-2">{allocation.note}</span>
        : <span className="text-ink-4">&mdash;</span>}
      </TableCell>
      <TableCell align="right">
        <span className="flex justify-end gap-1">
          <PermissionGate projectId={projectId} permission="staffing:allocate">
            {({ allowed }) => (
              <Button
                size="sm"
                disabled={!allowed}
                onClick={onEdit}
                data-testid={`${testId}.Edit`}
              >
                Edit
              </Button>
            )}
          </PermissionGate>
          <PermissionGate projectId={projectId} permission="staffing:allocate">
            {({ allowed }) => (
              <Button
                size="sm"
                variant="danger"
                disabled={!allowed}
                onClick={onRemove}
                data-testid={`${testId}.Remove`}
              >
                Remove
              </Button>
            )}
          </PermissionGate>
        </span>
      </TableCell>
    </TableRow>
  );
}

/**
 * Confirming a removal, and naming what does *not* go with it.
 *
 * A plan somebody abandoned is not a state worth keeping, so this is a hard delete — but the thing
 * a reader is actually unsure of is how far it reaches, and the answer is *not at all*: the person
 * keeps their profile, their skills, their absences and every other project's claim on them. Saying
 * so is more use than asking "are you sure", which is a question with no information in it.
 */
export function DeleteAllocationDialog({
  projectId,
  allocation,
  onClose,
}: {
  projectId: string;
  allocation: Allocation;
  onClose: () => void;
}) {
  const remove = useDeleteAllocation(projectId);

  return (
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-sm" data-testid="ProjectStaffing.Delete">
        <DialogHeader>
          <DialogTitle>
            Take {allocation.person.displayName} off this project?
          </DialogTitle>
          <DialogDescription>
            This project stops claiming {allocationLabel(allocation.percent)} of their week from{' '}
            {formatDate(allocation.startsOn)} to {formatDate(allocation.endsOn)}. Their profile,
            their skills, their absences and every other project&rsquo;s claim on them are
            untouched.
          </DialogDescription>
        </DialogHeader>

        {remove.isError && (
          <Banner tone="bad" className="mt-3" data-testid="ProjectStaffing.Delete.Error">
            {/* `describeError` is the one place an error becomes user-facing copy. */}
            <span role="alert">{describeError(remove.error).body}</span>
          </Banner>
        )}

        <DialogFooter>
          <Button onClick={onClose} data-testid="ProjectStaffing.Delete.Cancel">
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={remove.isPending}
            onClick={() => {
              remove.mutate(allocation.id, { onSuccess: onClose });
            }}
            data-testid="ProjectStaffing.Delete.Confirm"
          >
            {remove.isPending ? 'Removing…' : 'Remove allocation'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
