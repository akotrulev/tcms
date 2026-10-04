import { Check, ChevronDown } from 'lucide-react';
import { useMemo } from 'react';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { useBoardDoors, useBoards } from '../../api/queries';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../../components/ui';
import { cn } from '../../lib/cn';
import { formatCount, plural } from '../../lib/format';
import { useWorkFilter } from '../board/useWorkFilter';

const TEST_ID = 'WorkTable.BoardPicker';

/**
 * The Work page's lead on the Board view: which board, and what is waiting on the others.
 *
 * The badge is the count of cards in a board's first column, never a total. A board with no answer
 * yet has no entry in `waiting`, so it draws nothing rather than a zero that turns into a number.
 * Choosing a board keeps every other parameter, because a board is not a filter.
 */
export function BoardPicker({ projectId, activeBoardId }: { projectId: string; activeBoardId: string }) {
  const [params] = useSearchParams();
  const { filters } = useWorkFilter();
  const boards = useBoards(projectId);
  const boardIds = useMemo(() => (boards.data ?? []).map((board) => board.id), [boards.data]);
  const doors = useBoardDoors(boardIds, filters.board, boards.data !== undefined);

  const active = boards.data?.find((board) => board.id === activeBoardId);
  const elsewhere = boardIds
    .filter((id) => id !== activeBoardId)
    .reduce((total, id) => total + (doors.waiting[id] ?? 0), 0);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        data-testid={TEST_ID}
        className={cn(
          'flex cursor-pointer items-center gap-1.5 rounded-md border-0 bg-transparent p-0 text-ink',
          'text-[16px] leading-tight font-[650] tracking-[-.015em]',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
        )}
      >
        {active?.name ?? 'Board'}
        <ChevronDown aria-hidden className="size-4 text-ink-3" />
        {elsewhere > 0 && (
          <span
            data-testid={`${TEST_ID}.Waiting`}
            title={`${plural(elsewhere, 'card')} waiting on other boards`}
            className="rounded-full bg-steel px-1.5 font-mono text-[10.5px] leading-[15px] font-semibold text-rail"
          >
            {formatCount(elsewhere)}
          </span>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="min-w-[240px]">
        {(boards.data ?? []).map((board) => {
          const to = new URLSearchParams(params);
          to.set('view', 'board');
          to.set('board', board.id);
          const on = board.id === activeBoardId;
          const waiting = doors.waiting[board.id] ?? 0;
          return (
            <DropdownMenuItem key={board.id} asChild data-testid={`${TEST_ID}.${board.name}`}>
              <RouterLink to={{ search: `?${to.toString()}` }} aria-current={on ? 'page' : undefined}>
                <span className="flex size-3.5 items-center justify-center">
                  {on && <Check aria-hidden className="size-3.5 text-steel" />}
                </span>
                <span className="flex-1">{board.name}</span>
                {waiting > 0 && (
                  <span
                    data-testid={`${TEST_ID}.${board.name}.Badge`}
                    className={cn(
                      'min-w-4 rounded-full bg-steel px-1 text-center',
                      'font-mono text-[10px] font-semibold leading-[15px] text-rail',
                    )}
                  >
                    {formatCount(waiting)}
                  </span>
                )}
              </RouterLink>
            </DropdownMenuItem>
          );
        })}
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild data-testid={`${TEST_ID}.Configure`}>
          <RouterLink to={`/projects/${projectId}/settings/boards`}>Configure boards</RouterLink>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
