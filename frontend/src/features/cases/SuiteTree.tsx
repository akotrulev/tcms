import AddIcon from '@mui/icons-material/Add';
import MoreVertIcon from '@mui/icons-material/MoreVert';
import {
  Box,
  Button,
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
import type { Suite } from '../../api/types';
import { formatCount } from '../../lib/format';
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
 * **The actions are rendered for everyone and refused by the server.** The SPA cannot gate them
 * on the caller's permissions because `CurrentUser` does not carry any: it exposes `isSystemAdmin`
 * and teams, and nothing says whether this user holds `suite:manage` on this project. So a Viewer
 * sees Delete, clicks it, and gets a 403 rendered in the dialog. That is a real violation of
 * disable-don't-hide and it is a **pre-existing class**, not this feature's — "New case" has
 * behaved exactly this way since Phase 2. The fix is one contract change (the caller's effective
 * permissions on a project) that closes it everywhere at once; gating suites alone would leave the
 * rest of the app inconsistent and make the eventual fix touch two patterns instead of one.
 */

interface TreeNode {
  suite: Suite;
  children: TreeNode[];
  /** Descendant-inclusive count, computed here so the display matches the click. */
  totalCases: number;
}

function buildTree(suites: Suite[]): TreeNode[] {
  const byParent = new Map<string | null, Suite[]>();
  for (const suite of suites) {
    const key = suite.parentId ?? null;
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
  const [editing, setEditing] = useState<Editing>(null);
  const [menuFor, setMenuFor] = useState<{ anchor: HTMLElement; suite: Suite } | null>(null);

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
    collapsed folder used to leave it invisible, because nothing expanded the parent. `expanded`
    starts as the ancestors of the selection and is merged forward, so a user's own collapse is
    never undone by a refetch.
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
      <Stack
        direction="row"
        sx={{ alignItems: 'center', justifyContent: 'space-between', px: 1.5, pb: 0.5 }}
      >
        <Typography variant="overline" sx={{ color: 'text.secondary' }}>
          Suites
        </Typography>
        {/*
          Creates under the CURRENT selection, which is the folder the user is already looking at,
          and at the root when the selection is "All cases". Naming the target in the label rather
          than leaving it implicit — a New button that silently picks a parent is how a folder ends
          up three levels from where its author meant it.
        */}
        <Button
          size="small"
          startIcon={<AddIcon />}
          onClick={() => setEditing({ kind: 'create', parent: selected })}
          data-testid="SuiteTree.NewSuite"
        >
          New
        </Button>
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
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            No suites yet. A case needs a suite to live in, so this is the first thing to make.
          </Typography>
          <Button
            size="small"
            variant="contained"
            startIcon={<AddIcon />}
            onClick={() => setEditing({ kind: 'create', parent: null })}
            data-testid="SuiteTree.Empty.NewSuite"
            sx={{ alignSelf: 'flex-start' }}
          >
            New suite
          </Button>
        </Stack>
      )}

      <Menu
        open={menuFor !== null}
        anchorEl={menuFor?.anchor ?? null}
        onClose={() => setMenuFor(null)}
        data-testid="SuiteTree.Menu"
      >
        <MenuItem
          data-testid="SuiteTree.Menu.NewChild"
          onClick={() => {
            if (menuFor) setEditing({ kind: 'create', parent: menuFor.suite });
            setMenuFor(null);
          }}
        >
          <ListItemText>New suite inside</ListItemText>
        </MenuItem>
        <MenuItem
          data-testid="SuiteTree.Menu.Rename"
          onClick={() => {
            if (menuFor) setEditing({ kind: 'rename', suite: menuFor.suite });
            setMenuFor(null);
          }}
        >
          <ListItemText>Rename…</ListItemText>
        </MenuItem>
        <MenuItem
          data-testid="SuiteTree.Menu.Delete"
          onClick={() => {
            if (menuFor) setEditing({ kind: 'delete', suite: menuFor.suite });
            setMenuFor(null);
          }}
        >
          {/* Danger-coloured here as well as on the dialog's confirm, so the destructive item is
              distinguishable before it is chosen and not only after. */}
          <ListItemText sx={{ color: 'error.main' }}>Delete…</ListItemText>
        </MenuItem>
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
