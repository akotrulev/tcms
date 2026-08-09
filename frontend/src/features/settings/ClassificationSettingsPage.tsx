import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  IconButton,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined';
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import SubdirectoryArrowRightIcon from '@mui/icons-material/SubdirectoryArrowRight';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useClassification, useProject, useRetireClassificationValue } from '../../api/queries';
import { ApiError } from '../../api/client';
import type { ClassificationDimension, ClassificationValue, PermissionKey } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { buildValueTree, type ClassificationValueNode } from '../../lib/classification';
import { ClassificationValueDialog } from './ClassificationValueDialog';

/**
 * The classification vocabulary editor — **the screen that makes a provisioned project's Feature
 * tree fillable.**
 *
 * `tcms_seed_project_defaults` creates the Feature dimension with no values in it on purpose
 * ("inventing a client's feature taxonomy for them is how a controlled vocabulary gets worked
 * around"), and until this page existed there was no way to put any in: handoff §68 built the case
 * editor's tree picker against a dimension nothing could fill, and said so on its way out.
 *
 * ## One page, two permissions, and the flag that decides which
 *
 * `GET /classification` returns the company's dimensions and the project's own in one array, and
 * `locked` is what tells them apart. It is **not** "read-only" — that reading made
 * `company:vocabulary` a permission covering nothing — it means the dimension belongs to the
 * company, so its values are edited by a Company Admin at company scope while the project's own
 * Feature tree is edited by a Test Lead here. So every gated control on this page reads the
 * dimension's flag to choose which question to ask, and both branches are ordinary
 * disable-don't-hide (§31): a Test Lead sees the company's axes with their controls greyed and a
 * tooltip saying why, rather than not seeing that the axes exist.
 *
 * ## Why the whole vocabulary and not just Feature
 *
 * Feature is the only dimension anyone needs to fill today, and a page that showed only Feature
 * would be a page that quietly disagrees with the picker beside it — the case editor offers four
 * axes, and a settings screen listing one implies the other three are not configurable rather than
 * not *yours*. Showing all four with the right control state says the true thing.
 */
export function ClassificationSettingsPage() {
  const { projectId = '' } = useParams();

  const project = useProject(projectId);
  const classification = useClassification(projectId);

  const [adding, setAdding] = useState<{
    dimension: ClassificationDimension;
    parent: ClassificationValue | null;
  } | null>(null);
  const [editing, setEditing] = useState<{
    dimension: ClassificationDimension;
    value: ClassificationValue;
  } | null>(null);
  const [retiring, setRetiring] = useState<ClassificationValue | null>(null);

  return (
    <Box>
      <PageHeader
        testId="Classification.Header"
        title="Classification"
        documentTitle={
          project.data ? `Classification · ${project.data.name}` : 'Classification'
        }
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Classification' },
        ]}
        subtitle="The axes a test case is classified on. Feature belongs to this project; Test Type, Test Level and Execution Phase are shared by every project in the company."
      />

      <DataRegion
        testId="Classification.Dimensions"
        state={{
          ...classification,
          refetch: () => void classification.refetch(),
        }}
        isEmpty={(dimensions) => dimensions.length === 0}
        empty={{
          kind: 'no-records',
          title: 'No classification dimensions',
          // Not a failure and not a state anyone can fix from here — which is why it says what
          // produced it. An empty array is a valid answer meaning "this company was provisioned
          // before the vocabulary existed", and a generic "no data" would read as a broken screen.
          body: 'This project has no classification axes. Re-running provision-company for this company adds them.',
        }}
      >
        {(dimensions) => (
          <Stack sx={{ gap: 3 }}>
            {dimensions.map((dimension) => (
              <DimensionPanel
                key={dimension.id}
                projectId={projectId}
                dimension={dimension}
                onAdd={(parent) => setAdding({ dimension, parent })}
                onEdit={(value) => setEditing({ dimension, value })}
                onRetire={setRetiring}
              />
            ))}
          </Stack>
        )}
      </DataRegion>

      {/* Conditionally mounted, like every other form in this app: a dialog behind `open={false}`
          keeps one instance — and therefore one set of field state and one mutation result —
          across every use of it (§54). Here it would also mean the name box still holding the
          previous value's name. */}
      {adding && (
        <ClassificationValueDialog
          projectId={projectId}
          dimension={adding.dimension}
          parent={adding.parent}
          value={null}
          onClose={() => setAdding(null)}
        />
      )}
      {editing && (
        <ClassificationValueDialog
          projectId={projectId}
          dimension={editing.dimension}
          parent={null}
          value={editing.value}
          onClose={() => setEditing(null)}
        />
      )}
      {retiring && (
        <RetireValueDialog
          projectId={projectId}
          value={retiring}
          onClose={() => setRetiring(null)}
        />
      )}
    </Box>
  );
}

/**
 * Which permission edits this dimension's values, and at which scope.
 *
 * The client half of `ClassificationValueWriteService.permissionFor`, and it reads `locked`
 * because that is the only thing on the wire that says whose the dimension is — the server reads
 * `project_id`, which is deliberately not serialized (a consumer picking values has no use for it,
 * and it is the field a future author would try to *send*).
 */
function gateFor(dimension: ClassificationDimension): {
  projectId: string | 'company';
  permission: PermissionKey;
} {
  return dimension.locked
    ? { projectId: 'company', permission: 'company:vocabulary' }
    : { projectId: '', permission: 'project:settings' };
}

function DimensionPanel({
  projectId,
  dimension,
  onAdd,
  onEdit,
  onRetire,
}: {
  projectId: string;
  dimension: ClassificationDimension;
  onAdd: (parent: ClassificationValue | null) => void;
  onEdit: (value: ClassificationValue) => void;
  onRetire: (value: ClassificationValue) => void;
}) {
  const gate = gateFor(dimension);
  const gateProjectId = gate.projectId === 'company' ? 'company' : projectId;

  return (
    <Panel
      title={dimension.name}
      testId={`Classification.${dimension.key}`}
      dense
      actions={
        <Stack direction="row" sx={{ gap: 1, alignItems: 'center' }}>
          <Chip
            size="small"
            variant="outlined"
            label={dimension.locked ? 'Company-wide' : 'This project'}
            data-testid={`Classification.${dimension.key}.Scope`}
          />
          <PermissionGate projectId={gateProjectId} permission={gate.permission}>
            {({ allowed }) => (
              <Button
                size="small"
                startIcon={<AddIcon />}
                disabled={!allowed}
                onClick={() => onAdd(null)}
                data-testid={`Classification.${dimension.key}.Add`}
              >
                Add value
              </Button>
            )}
          </PermissionGate>
        </Stack>
      }
    >
      {dimension.values.length === 0 ? (
        <Typography color="text.secondary" data-testid={`Classification.${dimension.key}.Empty`}>
          {dimension.hierarchical
            ? 'No features yet. Add the top level of your product’s taxonomy, then add underneath it.'
            : 'No values yet.'}
        </Typography>
      ) : (
        <Stack component="ul" sx={{ gap: 0.5, listStyle: 'none', p: 0, m: 0 }}>
          {buildValueTree(dimension.values).map((node) => (
            <ValueRow
              key={node.value.id}
              node={node}
              depth={0}
              dimension={dimension}
              gateProjectId={gateProjectId}
              permission={gate.permission}
              onAdd={onAdd}
              onEdit={onEdit}
              onRetire={onRetire}
            />
          ))}
        </Stack>
      )}
    </Panel>
  );
}

/**
 * One value and everything under it.
 *
 * The tree comes from `buildValueTree`, which surfaces orphans **and the members of a `parentId`
 * cycle** at the root rather than dropping them — the same property the picker depends on, and it
 * matters here for the mirror-image reason: a value nothing renders is one nobody can retire.
 *
 * "Add underneath" appears only on a hierarchical dimension. On a flat one the server would accept
 * a parent — nothing in the schema forbids it — and the product has no way to show the result, so
 * the control that would create it does not exist.
 */
function ValueRow({
  node,
  depth,
  dimension,
  gateProjectId,
  permission,
  onAdd,
  onEdit,
  onRetire,
}: {
  node: ClassificationValueNode;
  depth: number;
  dimension: ClassificationDimension;
  gateProjectId: string | 'company';
  permission: PermissionKey;
  onAdd: (parent: ClassificationValue | null) => void;
  onEdit: (value: ClassificationValue) => void;
  onRetire: (value: ClassificationValue) => void;
}) {
  const { value } = node;

  return (
    <Box component="li" sx={{ listStyle: 'none' }}>
      <Stack
        direction="row"
        sx={{
          gap: 1,
          alignItems: 'center',
          pl: depth * 3,
          py: 0.5,
          borderRadius: 1,
          '&:hover': { bgcolor: 'action.hover' },
        }}
        data-testid={`Classification.Value.${value.id}`}
      >
        {depth > 0 && (
          <SubdirectoryArrowRightIcon fontSize="small" sx={{ color: 'text.disabled' }} />
        )}
        {value.color && (
          <Box
            aria-hidden
            sx={{
              width: 12,
              height: 12,
              borderRadius: '50%',
              bgcolor: value.color,
              border: '1px solid',
              borderColor: 'divider',
            }}
          />
        )}
        <Typography sx={{ flexGrow: 1 }}>{value.name}</Typography>

        <PermissionGate projectId={gateProjectId} permission={permission}>
          {({ allowed }) => (
            <Stack direction="row" sx={{ gap: 0.5 }}>
              {dimension.hierarchical && (
                <Tooltip title="Add underneath">
                  <span>
                    <IconButton
                      size="small"
                      disabled={!allowed}
                      onClick={() => onAdd(value)}
                      aria-label={`Add a value under ${value.name}`}
                      data-testid={`Classification.Value.${value.id}.AddChild`}
                    >
                      <AddIcon fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              )}
              <Tooltip title="Rename">
                <span>
                  <IconButton
                    size="small"
                    disabled={!allowed}
                    onClick={() => onEdit(value)}
                    aria-label={`Rename ${value.name}`}
                    data-testid={`Classification.Value.${value.id}.Edit`}
                  >
                    <EditOutlinedIcon fontSize="small" />
                  </IconButton>
                </span>
              </Tooltip>
              <Tooltip title="Retire">
                <span>
                  <IconButton
                    size="small"
                    disabled={!allowed}
                    onClick={() => onRetire(value)}
                    aria-label={`Retire ${value.name}`}
                    data-testid={`Classification.Value.${value.id}.Retire`}
                  >
                    <DeleteOutlineIcon fontSize="small" />
                  </IconButton>
                </span>
              </Tooltip>
            </Stack>
          )}
        </PermissionGate>
      </Stack>

      {node.children.length > 0 && (
        <Stack component="ul" sx={{ gap: 0.5, listStyle: 'none', p: 0, m: 0 }}>
          {node.children.map((child) => (
            <ValueRow
              key={child.value.id}
              node={child}
              depth={depth + 1}
              dimension={dimension}
              gateProjectId={gateProjectId}
              permission={permission}
              onAdd={onAdd}
              onEdit={onEdit}
              onRetire={onRetire}
            />
          ))}
        </Stack>
      )}
    </Box>
  );
}

/**
 * Confirming a retire — and rendering the one refusal that is not a field error.
 *
 * A value with live children answers **409 with a sentence on `errors.children`**, rendered as it
 * arrives — the server counted, and a second phrasing here is a copy that drifts (`DeleteTeamDialog`,
 * same shape, same key). ~~The 409 carries the bare count and this dialog composes the sentence~~ →
 * **superseded §149**: an `errors` value is a message every client prints verbatim, and the CLI and
 * the MCP server were printing `children: 3` at a caller who cannot see this screen.
 *
 * This dialog stays open to show it. That is why there is no optimistic removal anywhere in this
 * flow: taking the row off screen and then putting it back with an error beside it is the worst
 * possible treatment of the one action whose failure the user most needs to read.
 *
 * The copy says what a retire *is*, because the word does not carry it: cases already classified
 * with the value keep it in their history and past runs still resolve it. The row survives; the
 * vocabulary simply stops offering it.
 */
function RetireValueDialog({
  projectId,
  value,
  onClose,
}: {
  projectId: string;
  value: ClassificationValue;
  onClose: () => void;
}) {
  const retire = useRetireClassificationValue(projectId);
  const blocked =
    retire.error instanceof ApiError ? retire.error.fieldErrors?.children?.[0] : undefined;

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Retire {value.name}?</DialogTitle>
      <DialogContent>
        {blocked ? (
          <Alert severity="warning" data-testid="Classification.Retire.Blocked">
            {blocked}
          </Alert>
        ) : retire.error ? (
          <Alert severity="error" data-testid="Classification.Retire.Error">
            {retire.error instanceof ApiError && retire.error.status === 403
              ? 'You do not have permission to edit this vocabulary.'
              : 'That did not work. Try again.'}
          </Alert>
        ) : (
          <DialogContentText>
            It stops being offered when classifying a case. Cases already classified with it keep
            it, and past runs still resolve it — nothing is deleted.
          </DialogContentText>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="Classification.Retire.Cancel">
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          disabled={retire.isPending}
          onClick={() => retire.mutate(value.id, { onSuccess: () => onClose() })}
          data-testid="Classification.Retire.Confirm"
        >
          Retire
        </Button>
      </DialogActions>
    </Dialog>
  );
}
