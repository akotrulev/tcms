import { Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { useEffect, useMemo } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import type { DocBlock, DocPage, DocPageSummary } from '../../api/types';
import { useDocPage, useDocPages, useProject } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { formatCount, formatDateTime } from '../../lib/format';

/**
 * The documentation space (plan §6i).
 *
 * Requirements are documentation blocks — there is no separate requirement entity — so this
 * screen is where traceability is actually visible, and the mechanism that makes people
 * *maintain* their links is the **inline coverage marker**: every block shows how many cases
 * verify it and how those cases last did. A paragraph with no linked cases is visibly
 * untested right there in the spec, which is far more motivating than a coverage report
 * somebody has to go and look for.
 *
 * Block anchors are stable ids, never derived from heading text. A link to
 * `#b-204` survives someone fixing a typo in the heading; a link to `#full-refund` breaks
 * *silently* the moment the word changes, and nobody finds out until an audit.
 */
export function DocsPage() {
  const { projectId = '', pageId } = useParams();
  const navigate = useNavigate();

  const project = useProject(projectId);
  const pages = useDocPages(projectId);
  const page = useDocPage(projectId, pageId);

  // With no page selected, land on the first published one rather than showing an empty
  // pane next to a populated tree.
  const firstPageId = pages.data?.[0]?.id;
  useEffect(() => {
    if (!pageId && firstPageId) {
      navigate(`/projects/${projectId}/docs/${firstPageId}`, { replace: true });
    }
  }, [pageId, firstPageId, projectId, navigate]);

  return (
    <Box>
      <PageHeader
        testId="Docs.Header"
        title="Documentation"
        documentTitle={project.data ? `Documentation · ${project.data.name}` : 'Documentation'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Documentation' },
        ]}
        subtitle="Specifications live here. Cases link to individual blocks, and each block shows what verifies it."
      />

      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ gap: 2, alignItems: 'flex-start' }}>
        <Box sx={{ width: { xs: '100%', md: 260 }, flexShrink: 0 }}>
          <Panel title="Pages" testId="Docs.TreePanel" dense>
            <Box sx={{ p: 1 }}>
              <DataRegion
                testId="Docs.Tree"
                state={{ ...pages, refetch: () => void pages.refetch() }}
                isEmpty={(rows) => rows.length === 0}
                empty={{ kind: 'no-records', title: 'No pages', body: 'Write the first specification page.' }}
              >
                {(rows) => <PageTree pages={rows} projectId={projectId} activeId={pageId} />}
              </DataRegion>
            </Box>
          </Panel>
        </Box>

        <Box sx={{ flex: 1, minWidth: 0, width: '100%' }}>
          <DataRegion testId="Docs.Page" state={{ ...page, refetch: () => void page.refetch() }}>
            {(data) => <PageView page={data} projectId={projectId} />}
          </DataRegion>
        </Box>
      </Stack>
    </Box>
  );
}

function PageTree({
  pages,
  projectId,
  activeId,
}: {
  pages: DocPageSummary[];
  projectId: string;
  activeId: string | undefined;
}) {
  const tree = useMemo(() => {
    const byParent = new Map<string | null, DocPageSummary[]>();
    for (const page of pages) {
      const key = page.parentId ?? null;
      const bucket = byParent.get(key);
      if (bucket) bucket.push(page);
      else byParent.set(key, [page]);
    }
    const build = (parentId: string | null, depth: number): { page: DocPageSummary; depth: number }[] =>
      [...(byParent.get(parentId) ?? [])]
        .sort((a, b) => a.title.localeCompare(b.title) || a.key.localeCompare(b.key))
        .flatMap((page) => [{ page, depth }, ...build(page.id, depth + 1)]);
    return build(null, 0);
  }, [pages]);

  return (
    <Stack component="ul" sx={{ listStyle: 'none', m: 0, p: 0, gap: 0.25 }}>
      {tree.map(({ page, depth }) => {
        const active = page.id === activeId;
        return (
          <Box component="li" key={page.id}>
            <RouterLink
              to={`/projects/${projectId}/docs/${page.id}`}
              data-testid="Docs.Tree.Item"
              style={{ textDecoration: 'none' }}
            >
              <Box
                aria-current={active ? 'page' : undefined}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1,
                  pl: 1 + depth * 1.5,
                  pr: 1,
                  py: 0.625,
                  borderRadius: 1,
                  fontSize: '0.8125rem',
                  color: active ? 'primary.main' : 'text.primary',
                  fontWeight: active ? 600 : 400,
                  bgcolor: active ? 'action.selected' : 'transparent',
                  '&:hover': { bgcolor: 'action.hover' },
                }}
              >
                <Box sx={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {page.title}
                </Box>
                {page.status === 'DRAFT' && (
                  <Chip size="small" variant="outlined" label="draft" sx={{ flexShrink: 0 }} />
                )}
              </Box>
            </RouterLink>
          </Box>
        );
      })}
    </Stack>
  );
}

function PageView({ page, projectId }: { page: DocPage; projectId: string }) {
  const blocks = [...(page.blocks ?? [])].sort((a, b) => a.position - b.position);

  // Coverage summed over blocks that *can* be covered. Headings are structure, not
  // requirements, so counting them as uncovered would make every page look worse than it is.
  const coverable = blocks.filter((block) => block.blockType !== 'heading');
  const uncovered = coverable.filter((block) => (block.coverage?.linkedCases ?? 0) === 0);

  return (
    <Panel
      testId="Docs.Content"
      title={
        <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1, flexWrap: 'wrap' }}>
          <Identifier variant="muted">{page.key}</Identifier>
          <Typography variant="h4" component="h2">
            {page.title}
          </Typography>
          <Identifier variant="muted">v{page.currentVersionNo}</Identifier>
          {page.status === 'DRAFT' && <Chip size="small" variant="outlined" label="draft" />}
        </Stack>
      }
      actions={
        coverable.length > 0 && (
          <Tooltip
            title={
              uncovered.length === 0
                ? 'Every substantive block on this page has at least one case linked to it.'
                : `${uncovered.length} blocks on this page have no case verifying them.`
            }
          >
            <Chip
              size="small"
              label={
                uncovered.length === 0
                  ? 'Fully linked'
                  : `${formatCount(uncovered.length)} of ${formatCount(coverable.length)} unlinked`
              }
              data-testid="Docs.Content.CoverageSummary"
              sx={{
                bgcolor: 'transparent',
                color: uncovered.length === 0 ? 'var(--color-passed)' : 'var(--color-blocked)',
                border: `1px solid color-mix(in srgb, ${
                  uncovered.length === 0 ? 'var(--color-passed)' : 'var(--color-blocked)'
                } 45%, transparent)`,
              }}
            />
          </Tooltip>
        )
      }
    >
      {blocks.length === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid="Docs.Content.Empty">
          This page has no content yet.
        </Typography>
      ) : (
        <>
          <Box sx={{ maxWidth: '78ch' }}>
            {blocks.map((block) => (
              <BlockView key={block.id} block={block} projectId={projectId} />
            ))}
          </Box>
          <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mt: 3 }}>
            Updated {formatDateTime(page.updatedAt)}
          </Typography>
        </>
      )}
    </Panel>
  );
}

function BlockView({ block, projectId }: { block: DocBlock; projectId: string }) {
  const isHeading = block.blockType === 'heading';

  return (
    <Box
      // The stable block id is the anchor. It never changes when the text does, which is the
      // whole reason a case's link to it can be trusted years later.
      id={block.id}
      data-testid="Docs.Block"
      sx={{
        scrollMarginTop: 72,
        mb: isHeading ? 1 : 2,
        mt: isHeading ? 3 : 0,
        '&:target': {
          // A deep-linked block identifies itself, so arriving from a case's traceability
          // panel lands the reader on the exact paragraph rather than near it.
          outline: '2px solid var(--color-action-primary)',
          outlineOffset: 6,
          borderRadius: '2px',
        },
      }}
    >
      {isHeading ? (
        <Typography
          variant={block.level === 1 ? 'h2' : block.level === 2 ? 'h3' : 'h4'}
          component={block.level === 1 ? 'h2' : block.level === 2 ? 'h3' : 'h4'}
        >
          {block.text}
        </Typography>
      ) : (
        <Stack direction="row" sx={{ gap: 2, alignItems: 'flex-start' }}>
          <Typography variant="body1" sx={{ flex: 1, minWidth: 0 }}>
            {block.text}
          </Typography>
          <CoverageMarker coverage={block.coverage} projectId={projectId} />
        </Stack>
      )}
    </Box>
  );
}

/**
 * The inline coverage marker. Deliberately small and in the margin — the spec text is what
 * the reader came for, and the marker is an annotation on it. But an *uncovered* block says
 * so in words rather than showing a zero, because "0" reads as a data point and "Not tested"
 * reads as a problem.
 */
function CoverageMarker({
  coverage,
  projectId,
}: {
  coverage: DocBlock['coverage'];
  projectId: string;
}) {
  if (!coverage) return <Box sx={{ width: 132, flexShrink: 0 }} />;

  const { linkedCases, passed, failed, notRun, caseKeys } = coverage;

  if (linkedCases === 0) {
    return (
      <Tooltip title="No test case verifies this statement. Nothing would catch it if the behaviour broke.">
        <Box sx={{ width: 132, flexShrink: 0 }}>
          <Chip
            size="small"
            label="Not tested"
            data-testid="Docs.Block.Coverage.Untested"
            sx={{
              bgcolor: 'transparent',
              color: 'var(--color-blocked)',
              border: '1px solid color-mix(in srgb, var(--color-blocked) 45%, transparent)',
            }}
          />
        </Box>
      </Tooltip>
    );
  }

  const state = failed > 0 ? 'failed' : notRun > 0 ? 'partial' : 'passed';
  const color =
    state === 'failed' ? 'var(--color-failed)' : state === 'partial' ? 'var(--color-blocked)' : 'var(--color-passed)';

  return (
    <Tooltip
      title={
        <Box>
          <Box sx={{ mb: 0.5 }}>
            {formatCount(passed)} passed · {formatCount(failed)} failed · {formatCount(notRun)} not run
          </Box>
          <Box sx={{ fontFamily: 'var(--font-mono)', fontSize: '0.6875rem' }}>{caseKeys.join(', ')}</Box>
        </Box>
      }
    >
      {/* Links to the case list filtered to the cases linked here, so the marker is an
          entrance to the work and not just a readout. */}
      <Box
        component={RouterLink}
        to={`/projects/${projectId}/cases?q=${encodeURIComponent(caseKeys[0] ?? '')}`}
        data-testid="Docs.Block.Coverage"
        sx={{ width: 132, flexShrink: 0, textDecoration: 'none' }}
      >
        <Stack direction="row" sx={{ alignItems: 'center', gap: 0.75 }}>
          <Box
            aria-hidden="true"
            sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: color, flexShrink: 0 }}
          />
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            <Identifier variant="muted">{formatCount(linkedCases)}</Identifier>{' '}
            {linkedCases === 1 ? 'case' : 'cases'}
            {failed > 0 && (
              <Box component="span" sx={{ color: 'var(--color-failed)', display: 'block' }}>
                {formatCount(failed)} failing
              </Box>
            )}
          </Typography>
        </Stack>
      </Box>
    </Tooltip>
  );
}
