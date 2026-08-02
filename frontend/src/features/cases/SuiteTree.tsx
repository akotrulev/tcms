import AddIcon from '@mui/icons-material/Add';
import MoreVertIcon from '@mui/icons-material/MoreVert';
import {
  Alert,
  Box,
  Button,
  Divider,
  IconButton,
  ListItemText,
  Menu,
  MenuItem,
  Stack,
  Typography,
} from '@mui/material';
import { SimpleTreeView } from '@mui/x-tree-view/SimpleTreeView';
import { TreeItem } from '@mui/x-tree-view/TreeItem';
import { useMemo, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { useSetSuiteStatus } from '../../api/queries';
import type { LifecycleStatus, Suite } from '../../api/types';
import { useCan } from '../../auth/useCan';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { formatCount } from '../../lib/format';
import { lifecycleLabels } from '../../lib/labels';
import { CreateSuiteDialog, DeleteSuiteDialog, RenameSuiteDialog } from './SuiteDialogs';

/**
 * The suite tree — the repository's spine, and the primary way people navigate a thousand
 * cases. Selecting a suite scopes the case list to that suite *and its descendants*, which
 * is what people mean when they click "Payments".
 *
 * Counts shown here are descendant-inclusive for the same reason: a parent suite reading
 * "0 cases" while its children hold two hundred is technically true and completely useless.
 *
 * The tree also OWNS the folder writes — new, rename, delete — because a folder has no page of
 * its own to put them on and never should: the tree is where its structure is visible, so it is
 * where structure is edited.
 *
 * **The actions are gated on the caller's permissions**, through `PermissionGate` — create and
 * rename on `suite:manage`, delete on `suite:delete`, which is the split the roles spec fixed and
 * the tree must not re-collapse. They used to render for everyone and be refused by the server,
 * because `CurrentUser` carries no permissions; `GET /me/permissions` answers that question and
 * `useCan` reads it. The three menu items are gated individually rather than the kebab as a whole:
 * a Test Engineer holds `suite:manage` and not `suite:delete`, so the menu they get is two live
 * items and one greyed one — which is the truth, where a disabled kebab would claim they may not
 * touch folders at all.
 */

interface TreeNode {
  suite: Suite;
  children: TreeNode[];
  /** Descendant-inclusive count, computed here so the display matches the click. */
  totalCases: number;
}

/**
 * Builds the tree, and — deliberately — **surfaces orphans at the root rather than dropping them**.
 *
 * A suite whose `parentId` names a suite that is not in this payload has no place to hang. The
 * obvious implementation buckets by parent and walks from `null`, which never reaches it: the
 * folder renders nowhere, with no empty state and no error, and every case inside it is
 * unreachable through the tree. §36 pinned that as a *characterisation* test — "documented, not
 * endorsed" — on the argument that the endpoint returns a project's whole suite list, so the input
 * cannot occur today.
 *
 * Phase 5's verdict is that documenting it is not enough. The condition arrives the first time the
 * list is paginated, filtered, permission-scoped, or served mid-way through a parent's soft
 * delete, and it arrives *silently* — data loss with no signal is the one failure mode a tree must
 * not have. Re-parenting to the root is wrong about hierarchy and right about existence, and being
 * wrong visibly is strictly better than being wrong invisibly.
 */
function buildTree(suites: Suite[]): TreeNode[] {
  const present = new Set(suites.map((suite) => suite.id));
  const byParent = new Map<string | null, Suite[]>();
  for (const suite of suites) {
    // An unresolvable parent is treated as no parent. A cycle is untouched by this: every node in
    // one has a parent that *is* present, so it stays unreachable and is still dropped — which is
    // the correct answer for a structure that cannot be drawn.
    const key = suite.parentId && present.has(suite.parentId) ? suite.parentId : null;
    const bucket = byParent.get(key);
    if (bucket) bucket.push(suite);
    else byParent.set(key, [suite]);
  }

  // Deterministic order: siblings by name, so the tree doesn't reshuffle between loads.
  const build = (parentId: string | null): TreeNode[] =>
    [...(byParent.get(parentId) ?? [])]
      .sort((a, b) => a.name.localeCompare(b.name) || a.key.localeCompare(b.key))
      .map((suite) => {
        const children = build(suite.id);
        return {
          suite,
          children,
          totalCases: suite.caseCount + children.reduce((sum, child) => sum + child.totalCases, 0),
        };
      });

  return build(null);
}

/**
 * §6g's ladder, from each status, with the key each edge costs.
 *
 * **The permission column mirrors the server's `LifecycleLadder.permissionFor` and is a disabling
 * hint, never the gate** — the same relationship `planMoves` has to `PlanLifecycle`. Getting it
 * wrong here shows a control that 403s, which is §31's failure and not a security one; the server
 * decides, from a status only it holds.
 *
 * The three approve-keyed rows are the ones a target-decides reading gets wrong: returning an
 * approved folder to draft costs `suite:approve`, because leaving an approval is an approval act,
 * and deprecating costs it because retiring is the soft form of a delete that Test Engineer is
 * withheld from outright.
 *
 * `satisfies Record<LifecycleStatus, …>` so a fifth status is a compile error here rather than a
 * folder with no moves at all.
 */
const suiteMoves = {
  DRAFT: [
    { target: 'IN_REVIEW', label: 'Submit for review', permission: 'suite:manage' },
    { target: 'DEPRECATED', label: 'Deprecate…', permission: 'suite:approve' },
  ],
  IN_REVIEW: [
    { target: 'APPROVED', label: 'Approve', permission: 'suite:approve' },
    { target: 'DRAFT', label: 'Return to draft', permission: 'suite:manage' },
    { target: 'DEPRECATED', label: 'Deprecate…', permission: 'suite:approve' },
  ],
  APPROVED: [
    { target: 'DRAFT', label: 'Return to draft', permission: 'suite:approve' },
    { target: 'DEPRECATED', label: 'Deprecate…', permission: 'suite:approve' },
  ],
  // Only one way back, and it lands on DRAFT: approval attaches to reviewed content by a named
  // person, so a retired folder returning straight to APPROVED would be a sign-off nobody gave.
  DEPRECATED: [{ target: 'DRAFT', label: 'Restore to draft', permission: 'suite:approve' }],
} as const satisfies Record<
  LifecycleStatus,
  readonly { target: LifecycleStatus; label: string; permission: string }[]
>;

/** Which dialog is open, and on which folder. One value, so two cannot be open at once. */
type Editing =
  | { kind: 'create'; parent: Suite | null }
  | { kind: 'rename'; suite: Suite }
  | { kind: 'delete'; suite: Suite }
  | null;

export function SuiteTree({
  suites,
  selectedSuiteId,
  onSelect,
  projectId,
}: {
  suites: Suite[];
  selectedSuiteId: string | undefined;
  onSelect: (suiteId: string | undefined) => void;
  projectId: string;
}) {
  const tree = useMemo(() => buildTree(suites), [suites]);
  const gate = useCan(projectId);
  const [editing, setEditing] = useState<Editing>(null);
  const [menuFor, setMenuFor] = useState<{ anchor: HTMLElement; suite: Suite } | null>(null);
  const setStatus = useSetSuiteStatus(projectId);
  const [statusError, setStatusError] = useState<string | null>(null);

  /**
   * Moves one folder along the ladder.
   *
   * The refusal renders **in the rail**, not as a toast: both sentences the server can answer are
   * about the folder the menu was just open on — "only a test lead can approve" and "return this
   * suite to draft first" — and a notification detached from the tree names neither. Hence
   * `handled` on the hook.
   */
  const move = (suite: Suite, target: LifecycleStatus) => {
    setStatusError(null);
    setStatus.mutate(
      { suiteId: suite.id, status: target },
      {
        onError: (failure: unknown) =>
          setStatusError(`${suite.name}: ${describeError(failure).body}`),
      },
    );
  };

  // Ancestors of the selection start expanded, so a deep-linked filter shows its context
  // rather than a collapsed tree with an invisible selection.
  const ancestorsOfSelection = useMemo(() => {
    if (!selectedSuiteId) return tree.map((node) => node.suite.id);
    const parents: string[] = [];
    let current = suites.find((s) => s.id === selectedSuiteId);
    while (current?.parentId) {
      parents.push(current.parentId);
      current = suites.find((s) => s.id === current?.parentId);
    }
    return [...parents, selectedSuiteId];
  }, [selectedSuiteId, suites, tree]);

  /*
    Expansion is CONTROLLED, and that changed with the write affordances.

    It used to be `defaultExpandedItems`, computed from the suite list. That is an uncontrolled
    default, so MUI warns when it changes after mount — and before this slice it changed rarely
    enough to go unnoticed. Every create, rename and delete now invalidates the suite query, so the
    "default" moved on every write and the warning became routine.

    Controlling it also fixes the behaviour underneath the warning: creating a suite inside a
    collapsed folder used to leave it invisible, because nothing expanded the parent.

    `expanded` is seeded ONCE, from the ancestors of the selection at mount — which is what makes a
    deep-linked `?suiteId=` arrive with its context open. It is deliberately not re-seeded when the
    selection changes afterwards: every later selection either comes from the tree itself (already
    visible) or from a write, which expands its own parent explicitly below. Re-seeding would undo
    a collapse the user performed on purpose, which is the behaviour this replaced.
  */
  const [expanded, setExpanded] = useState<string[]>(ancestorsOfSelection);


  const renderNode = (node: TreeNode) => (
    <TreeItem
      key={node.suite.id}
      itemId={node.suite.id}
      data-testid="SuiteTree.Item"
      label={
        <Stack
          direction="row"
          sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 1, py: 0.25, pr: 0.5 }}
        >
          <Box
            component="span"
            sx={{
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              fontSize: '0.8125rem',
              fontWeight: node.suite.id === selectedSuiteId ? 600 : 400,
            }}
            title={node.suite.name}
          >
            {node.suite.name}
          </Box>
          <Stack direction="row" sx={{ alignItems: 'center', gap: 0.5, flexShrink: 0 }}>
            {/*
              Rendered only when the folder is NOT a draft, and that is a deliberate asymmetry
              rather than a hidden state: DRAFT is where every folder starts and where most stay,
              so a badge on all of them costs the width the name needs in a narrow rail and says
              nothing. The three that mean something — under review, signed off, retired — are
              exactly the ones worth the space, and the menu shows the status either way.
            */}
            {node.suite.lifecycleStatus !== 'DRAFT' && (
              <Box
                component="span"
                data-testid="SuiteTree.Item.Status"
                title={lifecycleLabels[node.suite.lifecycleStatus]}
                sx={{
                  fontSize: '0.625rem',
                  textTransform: 'uppercase',
                  letterSpacing: '0.04em',
                  color: node.suite.lifecycleStatus === 'DEPRECATED' ? 'text.disabled' : 'text.secondary',
                  border: '1px solid',
                  borderColor: 'divider',
                  borderRadius: 0.5,
                  px: 0.5,
                }}
              >
                {lifecycleLabels[node.suite.lifecycleStatus]}
              </Box>
            )}
            <Box
              component="span"
              className="identifier"
              sx={{ fontSize: '0.6875rem', color: 'text.secondary' }}
            >
              {formatCount(node.totalCases)}
            </Box>
            {/*
              One kebab per node rather than three inline buttons: a tree rail is narrow, and
              three icons per row at every depth would crowd out the name the row exists to show.
              `stopPropagation` because the label is the tree item's own select target — without
              it, opening the menu also re-filters the case list underneath.
            */}
            <IconButton
              size="small"
              aria-label={`Actions for ${node.suite.name}`}
              data-testid="SuiteTree.ItemMenu"
              onClick={(event) => {
                event.stopPropagation();
                setMenuFor({ anchor: event.currentTarget, suite: node.suite });
              }}
              sx={{ p: 0.25 }}
            >
              <MoreVertIcon sx={{ fontSize: '1rem' }} />
            </IconButton>
          </Stack>
        </Stack>
      }
    >
      {node.children.map(renderNode)}
    </TreeItem>
  );

  const selected = suites.find((suite) => suite.id === selectedSuiteId) ?? null;

  return (
    <Box>
      {/*
        Dismissible, and above the tree rather than inside the menu, which is closed by the time the
        server answers. Both sentences name a folder, so the folder's name is prefixed — a bare
        "return this suite to draft first" in a rail of thirty folders names none of them.
      */}
      {statusError && (
        <Alert
          severity="warning"
          onClose={() => setStatusError(null)}
          data-testid="SuiteTree.StatusError"
          sx={{ mx: 1.5, mb: 1, py: 0, fontSize: '0.75rem' }}
        >
          {statusError}
        </Alert>
      )}
      {/*
        No heading here. The panel this tree lives in is already titled "Suites", and an overline
        repeating the word directly beneath it was the app's clearest case of an accessory that
        earns nothing — two labels for one thing, one of them shouting. The row survives because
        the action does; the label does not.
      */}
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'flex-end', px: 1.5, pb: 0.5 }}>
        {/*
          Creates under the CURRENT selection, which is the folder the user is already looking at,
          and at the root when the selection is "All cases". Naming the target in the label rather
          than leaving it implicit — a New button that silently picks a parent is how a folder ends
          up three levels from where its author meant it.
        */}
        <PermissionGate projectId={projectId} permission="suite:manage">
          {({ allowed }) => (
            <Button
              size="small"
              startIcon={<AddIcon />}
              disabled={!allowed}
              onClick={() => setEditing({ kind: 'create', parent: selected })}
              data-testid="SuiteTree.NewSuite"
            >
              New
            </Button>
          )}
        </PermissionGate>
      </Stack>

      {/*
        "All cases" is a real selection, not the absence of one — it gives the user an
        explicit way back out of a suite filter without hunting for a Clear button.
      */}
      <Box
        component="button"
        type="button"
        onClick={() => onSelect(undefined)}
        data-testid="SuiteTree.AllCases"
        aria-current={selectedSuiteId ? undefined : 'true'}
        sx={{
          display: 'flex',
          width: '100%',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 1,
          px: 1.5,
          py: 0.75,
          border: 'none',
          borderRadius: 1,
          cursor: 'pointer',
          font: 'inherit',
          fontSize: '0.8125rem',
          textAlign: 'left',
          color: 'text.primary',
          fontWeight: selectedSuiteId ? 400 : 600,
          bgcolor: selectedSuiteId ? 'transparent' : 'action.selected',
          '&:hover': { bgcolor: 'action.hover' },
        }}
      >
        All cases
      </Box>

      <SimpleTreeView
        selectedItems={selectedSuiteId ?? null}
        onSelectedItemsChange={(_event, itemId) => onSelect(itemId ?? undefined)}
        expandedItems={expanded}
        onExpandedItemsChange={(_event, items) => setExpanded(items)}
        data-testid="SuiteTree"
        sx={{ mt: 0.5 }}
      >
        {tree.map(renderNode)}
      </SimpleTreeView>

      {tree.length === 0 && (
        <Stack spacing={1} sx={{ px: 1.5, py: 2 }}>
          {/*
            The empty state's copy is gated too, not just its button. "This is the first thing to
            make" beside a button they cannot press is an instruction the reader cannot follow —
            an empty state that names the wrong next step is worse than one that names none.
          */}
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            {gate.can('suite:manage')
              ? 'No suites yet. A case needs a suite to live in, so this is the first thing to make.'
              : 'No suites yet. A case needs a suite to live in — ask a project lead to create the first one.'}
          </Typography>
          <PermissionGate projectId={projectId} permission="suite:manage">
            {({ allowed }) => (
              <Button
                size="small"
                variant="contained"
                startIcon={<AddIcon />}
                disabled={!allowed}
                onClick={() => setEditing({ kind: 'create', parent: null })}
                data-testid="SuiteTree.Empty.NewSuite"
                sx={{ alignSelf: 'flex-start' }}
              >
                New suite
              </Button>
            )}
          </PermissionGate>
        </Stack>
      )}

      <Menu
        open={menuFor !== null}
        anchorEl={menuFor?.anchor ?? null}
        onClose={() => setMenuFor(null)}
        data-testid="SuiteTree.Menu"
      >
        {/*
          The way into the suite's execution history — and until §52 there was no way at all: the
          route and the page had existed since Phase 1 with nothing anywhere linking to them, so
          the only way to reach the screen was to type its URL.

          First in the menu and ungated, because it is the only READ here and it is the item most
          people want. A folder's history needs no permission beyond seeing the project, which is
          already true of anyone looking at this tree — wrapping it in a PermissionGate would gate
          it on nothing and imply otherwise.
        */}
        <MenuItem
          component={RouterLink}
          to={menuFor ? `/projects/${projectId}/suites/${menuFor.suite.id}/history` : '#'}
          data-testid="SuiteTree.Menu.History"
          onClick={() => setMenuFor(null)}
        >
          <ListItemText>Execution history</ListItemText>
        </MenuItem>
        <PermissionGate projectId={projectId} permission="suite:manage" wrapper="block">
          {({ allowed }) => (
            <MenuItem
              disabled={!allowed}
              data-testid="SuiteTree.Menu.NewChild"
              onClick={() => {
                if (menuFor) setEditing({ kind: 'create', parent: menuFor.suite });
                setMenuFor(null);
              }}
            >
              <ListItemText>New suite inside</ListItemText>
            </MenuItem>
          )}
        </PermissionGate>
        <PermissionGate projectId={projectId} permission="suite:manage" wrapper="block">
          {({ allowed }) => (
            <MenuItem
              disabled={!allowed}
              data-testid="SuiteTree.Menu.Rename"
              onClick={() => {
                if (menuFor) setEditing({ kind: 'rename', suite: menuFor.suite });
                setMenuFor(null);
              }}
            >
              <ListItemText>Rename…</ListItemText>
            </MenuItem>
          )}
        </PermissionGate>
        <Divider />
        {/*
          The ladder. The moves SWAP rather than accumulate, as on the plan: a folder offers only
          the edges it actually has, because offering one the server refuses with a 409 is what
          §31 exists to stop. Each is gated on its own key, so a Test Engineer sees "Submit for
          review" live beside a greyed "Deprecate…" — which is the shape of the rule, visible.
        */}
        {menuFor
          && suiteMoves[menuFor.suite.lifecycleStatus].map((option) => (
            <PermissionGate
              key={option.target}
              projectId={projectId}
              permission={option.permission}
              wrapper="block"
            >
              {({ allowed }) => (
                <MenuItem
                  disabled={!allowed || setStatus.isPending}
                  data-testid={`SuiteTree.Menu.Move.${option.target}`}
                  onClick={() => {
                    if (menuFor) move(menuFor.suite, option.target);
                    setMenuFor(null);
                  }}
                >
                  <ListItemText>{option.label}</ListItemText>
                </MenuItem>
              )}
            </PermissionGate>
          ))}
        <Divider />
        <PermissionGate projectId={projectId} permission="suite:delete" wrapper="block">
          {({ allowed }) => (
            <MenuItem
              disabled={!allowed}
              data-testid="SuiteTree.Menu.Delete"
              onClick={() => {
                if (menuFor) setEditing({ kind: 'delete', suite: menuFor.suite });
                setMenuFor(null);
              }}
            >
              {/* Danger-coloured here as well as on the dialog's confirm, so the destructive item
                  is distinguishable before it is chosen and not only after. Muted when the item
                  is disabled, so a greyed row doesn't shout in red about something it won't do. */}
              <ListItemText sx={{ color: allowed ? 'error.main' : undefined }}>Delete…</ListItemText>
            </MenuItem>
          )}
        </PermissionGate>
      </Menu>

      {editing?.kind === 'create' && (
        <CreateSuiteDialog
          open
          onClose={() => setEditing(null)}
          projectId={projectId}
          parent={editing.parent}
          // Selecting the new folder is the useful next step: the user made it in order to put
          // something in it, and the case list beside the tree is now scoped to it.
          onCreated={(created) => {
            // The parent may be collapsed — a folder created inside it would otherwise be
            // selected, filtering the case list, while invisible in the tree.
            if (editing.parent) {
              setExpanded((open) => [...new Set([...open, editing.parent!.id])]);
            }
            onSelect(created.id);
          }}
        />
      )}
      {editing?.kind === 'rename' && (
        <RenameSuiteDialog
          open
          onClose={() => setEditing(null)}
          projectId={projectId}
          suite={editing.suite}
          onRenamed={() => setEditing(null)}
        />
      )}
      {editing?.kind === 'delete' && (
        <DeleteSuiteDialog
          open
          onClose={() => setEditing(null)}
          projectId={projectId}
          suite={editing.suite}
          // A deleted folder must not stay selected — the case list would filter on a suite that
          // is gone and read as "this project has no cases".
          onDeleted={() => {
            if (selectedSuiteId === editing.suite.id) onSelect(undefined);
          }}
        />
      )}
    </Box>
  );
}
