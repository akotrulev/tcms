import { Box, Button, Chip, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import AddIcon from '@mui/icons-material/AddOutlined';
import AddLinkIcon from '@mui/icons-material/AddLinkOutlined';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import { useEffect, useMemo, useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import type { BlockLink, DocPage, DocBlock, DocPageStatus, DocPageSummary } from '../../api/types';
import {
  useAttachDocPageFile,
  useDeleteDocPageFile,
  useDocPage,
  useDocPages,
  useProject,
  useSetDocPageStatus,
  useRelabelBlockLink,
  useUnlinkBlock,
} from '../../api/queries';
import { AttachmentsPanel } from '../../components/AttachmentList';
import { DocBlockBody } from '../../components/DocBlockBody';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { LinkRelationshipControl } from '../../components/LinkRelationshipControl';
import { PermissionGate } from '../../components/PermissionGate';
import { UnlinkDialog } from '../../components/UnlinkDialog';
import { formatCount, formatDateTime } from '../../lib/format';
import { docPageStatusLabel } from '../../lib/labels';
import { CreateDocPageDialog, DeleteDocPageDialog } from './DocPageDialogs';
import { LinkBlockDialog } from './LinkBlockDialog';

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
  const [creating, setCreating] = useState(false);
  /*
    The page being deleted lives HERE, not inside the panel that renders it — and that placement
    is a fix rather than a preference.

    Deleting the page you are reading removes its query, so the region re-renders as "not found"
    and unmounts `PageView` with it. A dialog rendered inside `PageView` therefore vanishes
    mid-`await`, and the `navigate` that was supposed to follow the delete never runs: the URL
    stays on the deleted page, the SPA refetches it, and a delete that SUCCEEDED reports "Not
    found" with an error notification. That is what the live pass saw.

    Hoisting the dialog above the data-dependent subtree makes the sequence survive its own
    success. The same shape as the notification rule in `error-handling` — a message about a thing
    must not be rendered inside the thing.
  */
  const [deleting, setDeleting] = useState<DocPageSummary | null>(null);

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

  /*
    A page id that is no longer in the space sends the reader back to it.

    Declarative rather than a callback on the delete, because "the page you are reading no longer
    exists" has more than one cause: you deleted it, a colleague deleted it while you were reading,
    or you followed a bookmark to something removed last month. A redirect wired into the delete
    handler fixes exactly one of those and leaves the other two showing a bare "Not found" where a
    working screen belongs — and it is also the version that breaks when the dialog unmounts before
    its own callback runs, which is what the live pass caught.
  */
  const pageMissing =
    !!pageId && pages.data !== undefined && !pages.data.some((row) => row.id === pageId);
  useEffect(() => {
    if (pageMissing) {
      navigate(`/projects/${projectId}/docs`, { replace: true });
    }
  }, [pageMissing, projectId, navigate]);

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
        actions={
          <PermissionGate projectId={projectId} permission="doc:edit">
            {({ allowed }) => (
              <Button
                variant="contained"
                startIcon={<AddIcon />}
                disabled={!allowed}
                onClick={() => setCreating(true)}
                data-testid="Docs.NewPage"
              >
                New page
              </Button>
            )}
          </PermissionGate>
        }
      />

      {creating && (
        <CreateDocPageDialog
          open
          onClose={() => setCreating(false)}
          projectId={projectId}
          pages={pages.data ?? []}
          onCreated={(id) => navigate(`/projects/${projectId}/docs/${id}`)}
        />
      )}

      {deleting && (
        <DeleteDocPageDialog
          open
          onClose={() => setDeleting(null)}
          projectId={projectId}
          page={deleting}
          // Back to the space, which then lands on whatever page is left — or on the empty state.
          onDeleted={() => navigate(`/projects/${projectId}/docs`, { replace: true })}
        />
      )}

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
          {/*
            No page selected means no page to load, and a query that is not enabled is *pending* —
            which `DataRegion` renders, correctly, as a loading skeleton. On a project with pages
            that state lasts a frame, because the effect above redirects to the first one. On a
            project with NONE it lasts forever: an empty space showed "No pages" beside a skeleton
            that would never resolve.

            Invisible until this slice, because until a real project could have an empty
            documentation space nobody had seen one — every mock fixture ships pages.
          */}
          {pageId ? (
            <DataRegion testId="Docs.Page" state={{ ...page, refetch: () => void page.refetch() }}>
              {(data) => (
                <Stack sx={{ gap: 2 }}>
                  <PageView page={data} projectId={projectId} onDelete={setDeleting} />
                  <PageAttachments page={data} projectId={projectId} />
                </Stack>
              )}
            </DataRegion>
          ) : (
            <Panel testId="Docs.NoSelection">
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                {(pages.data?.length ?? 0) === 0
                  ? 'No pages yet. A specification here is what test cases link to — start with one page per area of the product.'
                  : 'Pick a page from the list to read it.'}
              </Typography>
            </Panel>
          )}
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
                {/*
                  Any status but PUBLISHED, not just DRAFT. ARCHIVED became reachable when the
                  status endpoint landed, and the old `=== 'DRAFT'` check would have rendered an
                  archived page with no chip at all — indistinguishable from a published one, in
                  the tree that is the only way back to it.
                */}
                {page.status !== 'PUBLISHED' && (
                  <Chip
                    size="small"
                    variant="outlined"
                    label={docPageStatusLabel[page.status].toLowerCase()}
                    sx={{ flexShrink: 0 }}
                  />
                )}
              </Box>
            </RouterLink>
          </Box>
        );
      })}
    </Stack>
  );
}

function PageView({
  page,
  projectId,
  onDelete,
}: {
  page: DocPage;
  projectId: string;
  onDelete: (page: DocPageSummary) => void;
}) {
  const blocks = [...(page.blocks ?? [])].sort((a, b) => a.position - b.position);

  /*
    Coverage summed over blocks that can be covered AND have actually been measured.

    Headings are excluded because they are structure, not requirements — counting them as
    uncovered would make every page look worse than it is.

    `block.coverage != null` is the half the live pass added, and it is the same distinction the
    server makes one layer down: the field is **absent** until something can create a `doc_link`,
    because a zeroed marker states that nothing verifies this block when the true statement is
    that nothing has asked. Reading absent as zero put that claim back — as an amber "1 of 1
    unlinked" on a page whose only heading demonstrably *had* a link. The per-block marker was
    already correct (it renders nothing without coverage); this aggregate was the one place the
    decision had not been applied, which is exactly the shape §47 and §49 kept finding.
  */
  const coverable = blocks.filter(
    (block) => block.blockType !== 'heading' && block.coverage != null,
  );
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
          {page.status !== 'PUBLISHED' && (
            <Chip size="small" variant="outlined" label={docPageStatusLabel[page.status].toLowerCase()} />
          )}
        </Stack>
      }
      actions={
        <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          {coverable.length > 0 && (
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
          )}
          <PageActions page={page} projectId={projectId} onDelete={onDelete} />
        </Stack>
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
              <BlockView
                key={block.id}
                block={block}
                projectId={projectId}
                pageId={page.id}
                attachments={page.attachments}
              />
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

/**
 * Edit, the publication transitions, and Delete.
 *
 * **Two permissions, so two gates with different sentences.** `doc:edit` opens the editor and the
 * delete; `doc:publish` moves the publication state. ~~Test Engineer holds the first and not the
 * second~~ → **`V52`, 2026-08-15: Test Engineer holds both.** The two gates stay, because the keys
 * are still separate and any caller may hold one without the other — a Viewer holds neither, and a
 * company that wants publishing separated from writing has the key to do it. So Edit enabled beside
 * Publish disabled is still a shape this page must render, and `PermissionGate` gives each its own
 * reason rather than one blanket "you cannot do that".
 *
 * **Transitions are labeled actions, not a status dropdown.** `ui-rules` is explicit that a
 * `<select>` bypasses the transition's side effects and its gate; here it would also let one
 * control mean "publish" and "archive" depending on where it was left. Each target state gets its
 * own verb, and only the ones that make sense from the current state are offered — from `DRAFT`
 * there is nothing to unpublish.
 */
/**
 * A page's files.
 *
 * **Its own `Panel` beneath the page rather than a section inside `Docs.Content`**, because the page
 * body is prose with a coverage margin and an attachment strip is neither — dropping rows into the
 * block flow would put a file list where a reader is scanning for the next paragraph. Below the
 * content for the same reason a case's sits below its steps: it is supporting material.
 *
 * Mounted only for a loaded page, so `pageId` is real and both hooks have something to write to.
 */
function PageAttachments({ page, projectId }: { page: DocPage; projectId: string }) {
  const attach = useAttachDocPageFile(projectId, page.id);
  const remove = useDeleteDocPageFile(projectId, page.id);

  return (
    <AttachmentsPanel
      projectId={projectId}
      // `doc:edit` — the key that already rewrites every block on this page. Not `doc:publish`:
      // an image is part of what the page says, and publication is a separate act. (~~which Test
      // Engineer does not hold~~ → both are a contributor's since `V52`, so the choice is about
      // which act this is, not about who may perform it — see the header above.)
      permission="doc:edit"
      attachments={page.attachments}
      onAttach={(file) => attach.mutate(file)}
      attaching={attach.isPending}
      attachError={attach.isError ? attach.error : null}
      onDelete={(id) => remove.mutate(id)}
      deleting={remove.isPending}
      testId="Docs.Attachments"
      emptyBody="No files attached. Diagrams, exports and anything this specification refers to go here."
    />
  );
}

function PageActions({
  page,
  projectId,
  onDelete,
}: {
  page: DocPage;
  projectId: string;
  /** Raised to the page, which owns the dialog — see `DocsPage`. */
  onDelete: (page: DocPageSummary) => void;
}) {
  const navigate = useNavigate();
  const setStatus = useSetDocPageStatus(projectId, page.id);

  // From each state, the transitions that mean something. ARCHIVED offers only the way back,
  // because "archive an archived page" is a no-op the server would answer 200 to — an action
  // that is always available and never does anything is how a user learns to distrust a toolbar.
  const transitions: { to: DocPageStatus; label: string }[] =
    page.status === 'DRAFT'
      ? [
          { to: 'PUBLISHED', label: 'Publish' },
          { to: 'ARCHIVED', label: 'Archive' },
        ]
      : page.status === 'PUBLISHED'
        ? [
            { to: 'DRAFT', label: 'Unpublish' },
            { to: 'ARCHIVED', label: 'Archive' },
          ]
        : [{ to: 'DRAFT', label: 'Restore to draft' }];

  return (
    <>
      <PermissionGate projectId={projectId} permission="doc:edit">
        {({ allowed }) => (
          <Button
            size="small"
            variant="outlined"
            disabled={!allowed}
            onClick={() => navigate(`/projects/${projectId}/docs/${page.id}/edit`)}
            data-testid="Docs.Content.Edit"
          >
            Edit
          </Button>
        )}
      </PermissionGate>

      {transitions.map((transition) => (
        <PermissionGate key={transition.to} projectId={projectId} permission="doc:publish">
          {({ allowed }) => (
            <Button
              size="small"
              disabled={!allowed || setStatus.isPending}
              onClick={() => setStatus.mutate(transition.to)}
              data-testid={`Docs.Content.Status.${transition.to}`}
            >
              {transition.label}
            </Button>
          )}
        </PermissionGate>
      ))}

      <PermissionGate projectId={projectId} permission="doc:edit">
        {({ allowed }) => (
          <Button
            size="small"
            color="error"
            disabled={!allowed}
            onClick={() => onDelete(page)}
            data-testid="Docs.Content.Delete"
          >
            Delete
          </Button>
        )}
      </PermissionGate>

    </>
  );
}

function BlockView({
  block,
  projectId,
  pageId,
  attachments,
}: {
  block: DocBlock;
  projectId: string;
  pageId: string;
  attachments: DocPage['attachments'];
}) {
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
        // No coverage margin on a heading, which is not a styling choice: a heading is structure
        // rather than a requirement, and the page's own aggregate excludes it for the same reason.
        <DocBlockBody block={block} attachments={attachments} testId="Docs.Block" />
      ) : (
        <Stack direction="row" sx={{ gap: 2, alignItems: 'flex-start' }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <DocBlockBody block={block} attachments={attachments} testId="Docs.Block" />
          </Box>
          <CoverageMarker coverage={block.coverage} projectId={projectId} />
        </Stack>
      )}
      <BlockLinks block={block} projectId={projectId} pageId={pageId} />
    </Box>
  );
}

/**
 * What claims to verify this block, and the only way in the product to say so (§172).
 *
 * ## Below the body and outside the coverage margin
 *
 * The margin is 132px of annotation and a case reads `TC-1284 — Refund is refused after 30 days`;
 * one does not fit in the other. So the links sit under the block at full width, and the margin
 * keeps saying how the linked cases are *doing* — two different questions, which is the same split
 * `DocBlock.links` and `DocBlock.coverage` make on the wire: `links` is present and possibly empty
 * because the server knows exactly who claims this, `coverage` is null when nobody does.
 *
 * ## Offered on a heading too
 *
 * A heading gets no coverage marker — it is structure, not a requirement — and it gets a Link
 * button anyway, because the server accepts a link to any block and a control the client withholds
 * on its own authority is the client holding a second, staler copy of the rule (the block editor's
 * Remove makes the same argument one file over). Withholding it would also make an existing
 * heading link unremovable: `links` renders on every block precisely so nothing can be stranded.
 *
 * ## Remove confirms, and so does the case end — §173
 *
 * ~~`ui-rules` §4 wants a named-object confirmation … so this half matches the half that already
 * shipped; the pair is recorded as one follow-up rather than spot-fixed here.~~ → **the follow-up
 * landed.** Both ends now raise the same {@link UnlinkDialog}, which is the fix at the level the
 * defect lived at: one act, one behaviour, one component, so a third link surface inherits it.
 * The consequence sentence is this screen's own, because withdrawing a link here changes what the
 * block's coverage marker says and withdrawing one from the case end does not.
 */
function BlockLinks({
  block,
  projectId,
  pageId,
}: {
  block: DocBlock;
  projectId: string;
  pageId: string;
}) {
  const [linking, setLinking] = useState(false);
  // The link being withdrawn, not a boolean: the dialog names it, and the list mounts one control
  // per row. A boolean would confirm "a link" and remove whichever one was clicked last.
  const [withdrawing, setWithdrawing] = useState<BlockLink | null>(null);
  const remove = useUnlinkBlock(projectId, pageId);
  const relabel = useRelabelBlockLink(projectId, pageId);

  return (
    <Stack
      direction="row"
      sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap', mt: 0.5 }}
      data-testid="Docs.Block.Links"
    >
      {block.links.map((link) => (
        <Stack
          // A static row locator: the set is addressed by index, never by the link's id.
          key={link.id}
          direction="row"
          sx={{ gap: 0.5, alignItems: 'center' }}
          data-testid="Docs.Block.Links.Item"
        >
          <Chip
            size="small"
            variant="outlined"
            component={RouterLink}
            clickable
            // The source's own screen, not a filtered list: a case has a detail route and a suite
            // does not, so the suite arm lands on the case list narrowed to it — the same two
            // destinations the board and the plan statistics page already use for the same pair.
            to={
              link.sourceType === 'TEST_CASE'
                ? `/projects/${projectId}/cases/${link.sourceId}`
                : `/projects/${projectId}/cases?suiteId=${link.sourceId}`
            }
            label={
              <>
                <Identifier variant="muted">{link.sourceKey}</Identifier> {link.sourceTitle}
              </>
            }
            data-testid="Docs.Block.Links.Item.Source"
          />
          {/*
            Beside the chip and not inside its label (§174). The source chip is a router link, and
            an interactive control nested in an anchor is invalid markup whose click target depends
            on the browser — so the claim gets its own element the moment it stops being text.
          */}
          <PermissionGate projectId={projectId} permission="doc:edit">
            {({ allowed }) => (
              <LinkRelationshipControl
                value={link.relationship}
                onChange={(relationship) => relabel.mutate({ linkId: link.id, relationship })}
                disabled={!allowed}
                pending={relabel.isPending}
                testId="Docs.Block.Links.Relationship"
              />
            )}
          </PermissionGate>
          <PermissionGate projectId={projectId} permission="doc:edit">
            {({ allowed }) =>
              allowed ? (
                <Tooltip title={`Withdraw ${link.sourceKey}'s claim on this block`}>
                  <span>
                    <IconButton
                      aria-label={`Remove the link to ${link.sourceKey}`}
                      size="small"
                      disabled={remove.isPending}
                      onClick={() => setWithdrawing(link)}
                      data-testid="Docs.Block.Links.Remove"
                    >
                      <DeleteOutlineIcon fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              ) : null
            }
          </PermissionGate>
        </Stack>
      ))}

      {/* Disabled rather than hidden (§31) — a Viewer should be able to see that this is a thing
          the product does. `doc:edit`, which is the endpoint's own gate on both verbs: a link is a
          property of the documentation, so its holder is everyone who writes docs. */}
      <PermissionGate projectId={projectId} permission="doc:edit">
        {({ allowed }) => (
          <Button
            size="small"
            startIcon={<AddLinkIcon />}
            disabled={!allowed}
            onClick={() => setLinking(true)}
            sx={{ color: 'text.secondary' }}
            data-testid="Docs.Block.Links.AddButton"
          >
            {block.links.length === 0 ? 'Link a case' : 'Link'}
          </Button>
        )}
      </PermissionGate>

      {/* Mounted only while open, so the dialog's reset-on-open effect starts from a fresh mount
          rather than from whatever the last block left in it. */}
      {linking && (
        <LinkBlockDialog
          open
          onClose={() => setLinking(false)}
          projectId={projectId}
          pageId={pageId}
          block={block}
        />
      )}

      {withdrawing && (
        <UnlinkDialog
          open
          onClose={() => setWithdrawing(null)}
          target={`${withdrawing.sourceKey} — ${withdrawing.sourceTitle}`}
          // This screen's own sentence: the marginal marker is computed from these rows, so a
          // withdrawal here has a visible effect on the page the reader is looking at. The `OWN`
          // of the two consequences that is worth spending a line on.
          consequence="The link is withdrawn and this block's coverage is recounted. The case itself is not touched."
          onConfirm={() =>
            remove.mutate(withdrawing.id, { onSuccess: () => setWithdrawing(null) })
          }
          pending={remove.isPending}
          error={remove.isError ? remove.error : null}
          reset={remove.reset}
          testId="Docs.Block.Links.Unlink"
        />
      )}
    </Stack>
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
  // Null and zero are ONE branch since §168, and the server only ever sends the first. It sends
  // `coverage: null` for a block no case claims to verify and never a row of zeros — the absence of
  // a claim is not a claim — so this component owns the wording that turns that absence into
  // something a reader can act on. Rendering an empty margin for it instead, which is what this did
  // while the field was served by nothing, would hide the marker in exactly the case plan §6i
  // built it for: the statement nobody has linked.
  if (!coverage || coverage.linkedCases === 0) {
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

  const { linkedCases, passed, failed, notRun, caseKeys } = coverage;

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
