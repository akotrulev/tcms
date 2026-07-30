import { Box, Stack, Typography } from '@mui/material';
import { SimpleTreeView } from '@mui/x-tree-view/SimpleTreeView';
import { TreeItem } from '@mui/x-tree-view/TreeItem';
import { useMemo } from 'react';
import type { Suite } from '../../api/types';
import { formatCount } from '../../lib/format';

/**
 * The suite tree — the repository's spine, and the primary way people navigate a thousand
 * cases. Selecting a suite scopes the case list to that suite *and its descendants*, which
 * is what people mean when they click "Payments".
 *
 * Counts shown here are descendant-inclusive for the same reason: a parent suite reading
 * "0 cases" while its children hold two hundred is technically true and completely useless.
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

export function SuiteTree({
  suites,
  selectedSuiteId,
  onSelect,
}: {
  suites: Suite[];
  selectedSuiteId: string | undefined;
  onSelect: (suiteId: string | undefined) => void;
}) {
  const tree = useMemo(() => buildTree(suites), [suites]);

  // Ancestors of the selection start expanded, so a deep-linked filter shows its context
  // rather than a collapsed tree with an invisible selection.
  const defaultExpanded = useMemo(() => {
    if (!selectedSuiteId) return tree.map((node) => node.suite.id);
    const parents: string[] = [];
    let current = suites.find((s) => s.id === selectedSuiteId);
    while (current?.parentId) {
      parents.push(current.parentId);
      current = suites.find((s) => s.id === current?.parentId);
    }
    return [...parents, selectedSuiteId];
  }, [selectedSuiteId, suites, tree]);

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
          <Box
            component="span"
            className="identifier"
            sx={{ fontSize: '0.6875rem', color: 'text.secondary', flexShrink: 0 }}
          >
            {formatCount(node.totalCases)}
          </Box>
        </Stack>
      }
    >
      {node.children.map(renderNode)}
    </TreeItem>
  );

  return (
    <Box>
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
        defaultExpandedItems={defaultExpanded}
        data-testid="SuiteTree"
        sx={{ mt: 0.5 }}
      >
        {tree.map(renderNode)}
      </SimpleTreeView>

      {tree.length === 0 && (
        <Typography variant="body2" sx={{ px: 1.5, py: 2, color: 'text.secondary' }}>
          No suites yet.
        </Typography>
      )}
    </Box>
  );
}
