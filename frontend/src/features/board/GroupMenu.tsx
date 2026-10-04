import { ChevronDown } from 'lucide-react';
import { useBoardConfig, useUpdateBoard } from '../../api/queries';
import type { BoardConfig, SwimlaneBy } from '../../api/types';
import { PermissionGate } from '../../components/PermissionGate';
import {
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '../../components/ui';
import { swimlaneByLabels } from '../../lib/labels';

const TEST_ID = 'ProjectBoard.GroupBy';

/**
 * Grouping is the board's `swimlaneBy`, not a view preference, so it costs `board:configure` and is
 * disabled with its reason for a caller who lacks it rather than hidden.
 *
 * The update carries the board's columns back verbatim: `BoardInput.columns` is a replacement, and
 * every column keeps its id, so none is created or deleted.
 */
export function GroupMenu({ projectId, boardId }: { projectId: string; boardId: string }) {
  const config = useBoardConfig(boardId);
  const updateBoard = useUpdateBoard(projectId);
  const board = config.data;

  return (
    <PermissionGate projectId={projectId} permission="board:configure">
      {({ allowed, reason }) => (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              size="sm"
              disabled={!allowed || board === undefined || updateBoard.isPending}
              title={allowed ? undefined : reason}
              data-testid={TEST_ID}
            >
              Group: {swimlaneByLabels[board?.swimlaneBy ?? 'NONE']}
              <ChevronDown aria-hidden className="size-3.5" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-[220px]">
            <DropdownMenuRadioGroup
              value={board?.swimlaneBy ?? 'NONE'}
              onValueChange={(axis) => {
                if (!board) return;
                updateBoard.mutate({
                  boardId: board.id,
                  input: { ...boardInputOf(board), swimlaneBy: axis as SwimlaneBy },
                });
              }}
            >
              {(Object.keys(swimlaneByLabels) as SwimlaneBy[]).map((axis) => (
                <DropdownMenuRadioItem
                  key={axis}
                  value={axis}
                  data-testid={`${TEST_ID}.${axis}`}
                >
                  {swimlaneByLabels[axis]}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
            <p className="mt-1.5 mb-0 border-t border-line px-2.5 pt-2 pb-1 text-[11.5px] text-ink-3">
              Changes this board for everyone.
            </p>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </PermissionGate>
  );
}

function boardInputOf(board: BoardConfig) {
  return {
    name: board.name,
    swimlaneBy: board.swimlaneBy,
    columns: board.columns.map((column) => ({
      id: column.id,
      name: column.name,
      wipLimit: column.wipLimit ?? null,
      statusIds: column.statuses.map((status) => status.id),
      defaultStatusId: column.defaultStatusId,
    })),
  };
}
