import { Alert, Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { useSnackbar } from 'notistack';
import { useState } from 'react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import type { WorkItem } from '../../api/types';
import { useMoveWorkItem, useProject, useWorkItems } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { formatCount } from '../../lib/format';
import { priorityLabels } from '../../lib/labels';

/**
 * The kanban board (plan §6l).
 *
 * The decision that makes this board trustworthy: **derived items are never materialized.** An
 * assigned execution, a pending review and a stale case all appear as cards, but their status
 * is computed from the source on every read — so a card physically cannot say "Done" while its
 * execution is still unexecuted. The cost is that a derived card can't be dragged freely; it
 * only moves where its source legally allows, and the board says so rather than accepting the
 * drop and silently reverting it on the next refresh.
 */

const COLUMNS = ['To do', 'In progress', 'In review', 'Done'] as const;
type Column = (typeof COLUMNS)[number];

const kindLabels: Record<WorkItem['kind'], string> = {
  TASK: 'Task',
  EXECUTION: 'Execution',
  REVIEW: 'Review',
  DOC_UPDATE: 'Documentation',
  CASE_MAINTENANCE: 'Maintenance',
  DEFECT: 'Defect',
};

export function BoardPage() {
  const { projectId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const mine = params.get('mine') === 'true';

  const project = useProject(projectId);
  const items = useWorkItems(projectId, mine);

  return (
    <Box>
      <PageHeader
        testId="Board.Header"
        title="Board"
        documentTitle={project.data ? `Board · ${project.data.name}` : 'Board'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Board' },
        ]}
        subtitle="Tasks alongside the work the repository already knows about — assigned executions, pending reviews and cases that need attention."
        actions={
          <Stack direction="row" sx={{ gap: 0.5 }}>
            {(
              [
                { value: false, label: 'Everyone' },
                { value: true, label: 'Assigned to me' },
              ]
            ).map((option) => (
              <Chip
                key={String(option.value)}
                label={option.label}
                onClick={() => {
                  const next = new URLSearchParams(params);
                  if (option.value) next.set('mine', 'true');
                  else next.delete('mine');
                  setParams(next, { replace: true });
                }}
                variant={mine === option.value ? 'filled' : 'outlined'}
                color={mine === option.value ? 'primary' : 'default'}
                aria-pressed={mine === option.value}
                data-testid={`Board.Filter.${option.value ? 'Mine' : 'Everyone'}`}
                sx={{ cursor: 'pointer' }}
              />
            ))}
          </Stack>
        }
      />

      <DataRegion
        testId="Board.Content"
        state={{ ...items, refetch: () => void items.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'no-records',
          title: mine ? 'Nothing assigned to you' : 'Nothing on the board',
          body: mine
            ? 'No open executions, reviews or tasks are assigned to you right now.'
            : 'No open work in this project.',
        }}
      >
        {(rows) => <Board items={rows} projectId={projectId} mine={mine} />}
      </DataRegion>
    </Box>
  );
}

function Board({ items, projectId, mine }: { items: WorkItem[]; projectId: string; mine: boolean }) {
  const { enqueueSnackbar } = useSnackbar();
  const move = useMoveWorkItem(projectId, mine);
  const [dragging, setDragging] = useState<WorkItem | null>(null);
  const [rejected, setRejected] = useState<string | null>(null);

  const handleDrop = async (item: WorkItem, column: Column) => {
    setDragging(null);
    if (item.status === column) return;

    // Checked before the request, not just after: a card the source won't allow to move
    // should never appear to move at all.
    if (!item.allowedTransitions.includes(column)) {
      setRejected(
        item.derived
          ? `${item.linkedKey ?? 'That card'} mirrors ${
              item.kind === 'EXECUTION' ? 'an execution' : 'a review'
            }, so it can't be moved to ${column}. Update the ${
              item.kind === 'EXECUTION' ? 'execution' : 'review'
            } and the card follows.`
          : `That card can't move to ${column} from ${item.status}.`,
      );
      return;
    }

    setRejected(null);
    try {
      await move.mutateAsync({ id: item.id, status: column });
    } catch (error) {
      setRejected(
        error instanceof ApiError ? error.detail : "Couldn't move that card. Check your connection and try again.",
      );
      enqueueSnackbar("Couldn't move that card", { variant: 'error' });
    }
  };

  return (
    <Stack sx={{ gap: 2 }}>
      {rejected && (
        <Alert severity="warning" role="alert" onClose={() => setRejected(null)} data-testid="Board.RejectedMove">
          <Typography variant="body2">{rejected}</Typography>
        </Alert>
      )}

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', lg: 'repeat(4, 1fr)' },
          gap: 2,
          alignItems: 'start',
        }}
      >
        {COLUMNS.map((column) => {
          const columnItems = items
            .filter((item) => item.status === column)
            // Overdue first, then by priority, with the id as a unique tiebreaker so cards
            // don't reshuffle on every refetch.
            .sort((a, b) => {
              const rank = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
              return (
                Number(b.overdue) - Number(a.overdue) ||
                rank[a.priority] - rank[b.priority] ||
                a.id.localeCompare(b.id)
              );
            });

          const canAccept = dragging ? dragging.allowedTransitions.includes(column) : false;

          return (
            <Box
              key={column}
              onDragOver={(event) => {
                if (canAccept) event.preventDefault();
              }}
              onDrop={() => {
                if (dragging) void handleDrop(dragging, column);
              }}
              data-testid={`Board.Column.${column.replace(/\s/g, '')}`}
            >
              <Panel
                dense
                title={
                  <Stack direction="row" sx={{ alignItems: 'center', gap: 1 }}>
                    <Typography variant="h5" component="h2">
                      {column}
                    </Typography>
                    <Identifier variant="muted">{formatCount(columnItems.length)}</Identifier>
                  </Stack>
                }
              >
                <Stack
                  sx={{
                    p: 1,
                    gap: 1,
                    minHeight: 120,
                    // Only a column that can legally accept the dragged card highlights, so
                    // the invalid drop is discouraged before it's attempted.
                    bgcolor: dragging ? (canAccept ? 'action.hover' : 'transparent') : 'transparent',
                    opacity: dragging && !canAccept ? 0.5 : 1,
                    transition: 'background-color 120ms ease',
                  }}
                >
                  {columnItems.length === 0 ? (
                    <Typography variant="caption" sx={{ color: 'text.secondary', p: 1 }}>
                      Nothing here
                    </Typography>
                  ) : (
                    columnItems.map((item) => (
                      <WorkCard
                        key={item.id}
                        item={item}
                        projectId={projectId}
                        onDragStart={() => setDragging(item)}
                        onDragEnd={() => setDragging(null)}
                        onMove={(target) => void handleDrop(item, target)}
                      />
                    ))
                  )}
                </Stack>
              </Panel>
            </Box>
          );
        })}
      </Box>
    </Stack>
  );
}

function WorkCard({
  item,
  projectId,
  onDragStart,
  onDragEnd,
  onMove,
}: {
  item: WorkItem;
  projectId: string;
  onDragStart: () => void;
  onDragEnd: () => void;
  onMove: (column: Column) => void;
}) {
  const priorityColor =
    item.priority === 'CRITICAL'
      ? 'var(--color-failed)'
      : item.priority === 'HIGH'
        ? 'var(--color-blocked)'
        : 'var(--color-border-strong)';

  return (
    <Box
      draggable
      onDragStart={onDragStart}
      onDragEnd={onDragEnd}
      data-testid="Board.Card"
      sx={{
        p: 1.25,
        border: '1px solid',
        borderColor: 'divider',
        borderLeft: '3px solid',
        borderLeftColor: priorityColor,
        borderRadius: 1,
        bgcolor: 'background.paper',
        cursor: 'grab',
      }}
    >
      <Stack direction="row" sx={{ alignItems: 'center', gap: 0.75, mb: 0.5, flexWrap: 'wrap' }}>
        <Chip size="small" variant="outlined" label={kindLabels[item.kind]} data-testid="Board.Card.Kind" />
        {/*
          Derived cards are marked. A user who knows a card mirrors an execution understands
          why it won't drag, and where to go instead.
        */}
        {item.derived && (
          <Tooltip title="This card mirrors an execution, review or case. Its status comes from the source, so update the source to move it.">
            <Chip
              size="small"
              label="linked"
              data-testid="Board.Card.DerivedMarker"
              sx={{
                bgcolor: 'transparent',
                color: 'var(--color-info)',
                border: '1px solid color-mix(in srgb, var(--color-info) 45%, transparent)',
              }}
            />
          </Tooltip>
        )}
        {item.overdue && (
          <Chip
            size="small"
            label="overdue"
            data-testid="Board.Card.Overdue"
            sx={{
              bgcolor: 'color-mix(in srgb, var(--color-failed) 12%, transparent)',
              color: 'var(--color-failed)',
              border: '1px solid color-mix(in srgb, var(--color-failed) 40%, transparent)',
            }}
          />
        )}
      </Stack>

      <Typography variant="body2" sx={{ fontWeight: 500, mb: 0.5 }} data-testid="Board.Card.Title">
        {item.title}
      </Typography>

      {item.linkedKey && (
        <RouterLink
          to={
            item.kind === 'EXECUTION' || item.kind === 'REVIEW' || item.kind === 'CASE_MAINTENANCE'
              ? `/projects/${projectId}/cases?q=${encodeURIComponent(item.linkedKey)}`
              : `/projects/${projectId}/cases`
          }
          data-testid="Board.Card.LinkedKey"
        >
          <Identifier variant="muted">{item.linkedKey}</Identifier>
        </RouterLink>
      )}

      <Stack direction="row" sx={{ alignItems: 'center', gap: 1, mt: 0.75, flexWrap: 'wrap' }}>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {priorityLabels[item.priority]}
        </Typography>
        {item.assignee && (
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            · {item.assignee.displayName}
          </Typography>
        )}
        {item.dueDate && (
          <Typography variant="caption" sx={{ color: item.overdue ? 'var(--color-failed)' : 'text.secondary' }}>
            · due <Identifier variant="muted">{item.dueDate}</Identifier>
          </Typography>
        )}
      </Stack>

      {/*
        Dragging is not the only way to move a card. A keyboard user gets the same capability
        from these buttons, and they carry the disabled reason rather than disappearing.
      */}
      <Stack direction="row" sx={{ gap: 0.5, mt: 1, flexWrap: 'wrap' }}>
        {COLUMNS.filter((column) => column !== item.status).map((column) => {
          const allowed = item.allowedTransitions.includes(column);
          return (
            <Tooltip
              key={column}
              title={
                allowed
                  ? `Move to ${column}`
                  : item.derived
                    ? `This card follows its source, so it can't be moved to ${column}`
                    : `Not a valid move from ${item.status}`
              }
            >
              <Box
                component="button"
                type="button"
                disabled={!allowed}
                aria-disabled={!allowed}
                onClick={() => onMove(column)}
                data-testid={`Board.Card.MoveTo.${column.replace(/\s/g, '')}`}
                sx={{
                  font: 'inherit',
                  fontSize: '0.6875rem',
                  px: 0.75,
                  py: 0.25,
                  borderRadius: 0.5,
                  border: '1px solid',
                  borderColor: 'divider',
                  bgcolor: 'transparent',
                  color: allowed ? 'text.secondary' : 'text.disabled',
                  cursor: allowed ? 'pointer' : 'not-allowed',
                  '&:hover:not(:disabled)': { borderColor: 'primary.main', color: 'primary.main' },
                }}
              >
                → {column}
              </Box>
            </Tooltip>
          );
        })}
      </Stack>
    </Box>
  );
}
