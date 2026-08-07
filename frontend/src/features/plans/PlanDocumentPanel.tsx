import {
  Alert,
  Box,
  Button,
  Chip,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import type { DocBlock, DocPage } from '../../api/types';
import {
  useDocPages,
  useLinkPlanDocument,
  usePlanDocument,
  useUnlinkPlanDocument,
} from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PermissionGate } from '../../components/PermissionGate';
import { Panel } from '../../components/PageHeader';
import { DocBlockBody } from '../../components/DocBlockBody';
import { describeError } from '../../lib/describeError';
import { docPageStatusLabel } from '../../lib/labels';

/**
 * The **Business requirements** tab — a page this plan *adopted*, rendered read-only (§97 D4′).
 *
 * ## What changed, and why the editor left
 *
 * §93 shipped this panel as an in-place editor: every block a text field saving on blur, plus a bar
 * that created a test case, appended a bullet and linked the two. Both halves are gone.
 *
 * **The prose is edited where prose is edited.** The document is an ordinary `DocPage` living in
 * the project's documentation space, versioned as documentation, found by everybody who searches
 * documentation — so it is written at `/projects/{id}/docs/{pageId}/edit` under `doc:edit`, and a
 * second editor here would be a second place to write one page, with its own lock version and its
 * own idea of what a block list is. What this tab offers instead is the way *to* that editor.
 *
 * **The Add-a-case bar contradicted three later decisions.** It required a suite (§97 D2: none),
 * it created the case immediately (§97 §5: nothing is created until Save), and it put the plan's
 * cases in a document's bullet list rather than in the plan's own scope — which is the Test cases
 * tab. Deleted rather than disabled; `useCreateDraftCase` went with it, since this was its only
 * caller.
 *
 * ## Three states and the first one is not an error
 *
 * A plan with no document is the ordinary starting state — a plan is created when somebody
 * schedules a cycle, and the specification it is about was often written weeks earlier by somebody
 * else. So the empty state is a **picker over the pages that already exist**, and
 * `usePlanDocument` turns the server's 404 into `null` precisely so it never reaches the global
 * error surface.
 */
export function PlanDocumentPanel({
  projectId,
  planId,
}: {
  projectId: string;
  planId: string;
}) {
  const document = usePlanDocument(projectId, planId);

  return (
    <DataRegion
      testId="PlanDocument.Region"
      state={{ ...document, refetch: () => void document.refetch() }}
    >
      {(page) =>
        page ? (
          <DocumentBody projectId={projectId} planId={planId} page={page} />
        ) : (
          <LinkPagePanel projectId={projectId} planId={planId} />
        )
      }
    </DataRegion>
  );
}

/**
 * Adopting a page.
 *
 * **A picker over pages that exist, and no "create one" button.** The product has a documentation
 * section with its own create, its own templates and its own tree; a second create here would be
 * the retired template `POST` growing back under another name, and a page created from a plan tab
 * with a title nobody chose is exactly the orphan §6n calls a graveyard.
 *
 * Pages already spoken for are **shown and disabled with the reason**, not filtered out (§31): a
 * reader who cannot find "Payments v4 requirements" in the list would conclude it had been deleted,
 * where the truth is that another plan documents it and the fix is to unlink it there.
 */
function LinkPagePanel({ projectId, planId }: { projectId: string; planId: string }) {
  const pages = useDocPages(projectId);
  const link = useLinkPlanDocument(projectId, planId);
  const [pageId, setPageId] = useState('');
  const [failure, setFailure] = useState<string | null>(null);

  const adopt = () => {
    setFailure(null);
    link.mutate(pageId, {
      // Rendered here rather than as a toast: the 409 names the plan already holding this page and
      // the whole answer is about the row just chosen in the select above it.
      onError: (error) => setFailure(describeError(error).body),
    });
  };

  return (
    <Panel testId="PlanDocument.Empty">
      <Stack sx={{ gap: 1.5, alignItems: 'flex-start' }}>
        <Typography variant="h6">No page is linked to this plan</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary', maxWidth: 640 }}>
          A plan&rsquo;s requirements are an ordinary documentation page that this plan adopts —
          written where specifications are written, keeping its own history, its links and its place
          in search. Link one that exists, or write it in the documentation section first.
        </Typography>

        {failure && (
          <Alert severity="error" data-testid="PlanDocument.LinkError">
            {failure}
          </Alert>
        )}

        <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1, alignSelf: 'stretch' }}>
          <TextField
            select
            label="Page"
            size="small"
            value={pageId}
            onChange={(event) => setPageId(event.target.value)}
            sx={{ minWidth: 320 }}
            data-testid="PlanDocument.PagePicker"
            helperText="Any page in this project's documentation."
          >
            {(pages.data ?? []).map((page) => {
              const spokenFor = page.subjectType === 'TEST_PLAN' && page.subjectId !== planId;
              return (
                <MenuItem key={page.id} value={page.id} disabled={spokenFor}>
                  <Stack direction="row" sx={{ gap: 1, alignItems: 'baseline' }}>
                    <Identifier variant="muted">{page.key}</Identifier>
                    <span>{page.title}</span>
                    {spokenFor && (
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        — already another plan&rsquo;s document
                      </Typography>
                    )}
                  </Stack>
                </MenuItem>
              );
            })}
            {pages.data?.length === 0 && (
              <MenuItem value="" disabled>
                This project has no documentation pages yet.
              </MenuItem>
            )}
          </TextField>
          <PermissionGate projectId={projectId} permission="doc:edit">
            {({ allowed }) => (
              <Box>
                <Button
                  variant="contained"
                  disabled={!allowed || !pageId || link.isPending}
                  onClick={adopt}
                  data-testid="PlanDocument.Link"
                >
                  {link.isPending ? 'Linking…' : 'Link this page'}
                </Button>
              </Box>
            )}
          </PermissionGate>
          <Button
            component={RouterLink}
            to={`/projects/${projectId}/docs`}
            variant="outlined"
            data-testid="PlanDocument.OpenDocumentation"
          >
            Open documentation
          </Button>
        </Stack>
      </Stack>
    </Panel>
  );
}

/** The adopted page, read-only, with the two ways out of it. */
function DocumentBody({
  projectId,
  planId,
  page,
}: {
  projectId: string;
  planId: string;
  page: DocPage;
}) {
  const unlink = useUnlinkPlanDocument(projectId, planId);
  const [failure, setFailure] = useState<string | null>(null);

  return (
    <Stack sx={{ gap: 2 }}>
      {failure && (
        <Alert severity="error" data-testid="PlanDocument.UnlinkError">
          {failure}
        </Alert>
      )}

      <Panel
        title={page.title}
        testId="PlanDocument.Body"
        actions={
          <Stack direction="row" sx={{ gap: 1, alignItems: 'center' }}>
            <Chip
              size="small"
              variant="outlined"
              label={docPageStatusLabel[page.status]}
              data-testid="PlanDocument.Status"
            />
            <Button
              size="small"
              component={RouterLink}
              to={`/projects/${projectId}/docs/${page.id}`}
              data-testid="PlanDocument.OpenPage"
            >
              Open in documentation
            </Button>
            {/*
              Says what it does in its own label, because the two acts are one click apart and only
              one of them is reversible. Unlink clears the link; the page keeps its blocks, its
              history and its links, and deleting it is a different control on a different screen.
            */}
            <PermissionGate projectId={projectId} permission="doc:edit">
              {({ allowed }) => (
                <Box>
                  <Button
                    size="small"
                    color="warning"
                    disabled={!allowed || unlink.isPending}
                    data-testid="PlanDocument.Unlink"
                    onClick={() => {
                      setFailure(null);
                      unlink.mutate(undefined, {
                        onError: (error) => setFailure(describeError(error).body),
                      });
                    }}
                  >
                    Unlink (keeps the page)
                  </Button>
                </Box>
              )}
            </PermissionGate>
          </Stack>
        }
      >
        <Stack sx={{ gap: 0.5 }}>
          {page.blocks.length === 0 ? (
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
              This page has no content yet. Open it in documentation to write it.
            </Typography>
          ) : (
            page.blocks.map((block) => (
              <BlockView
                key={block.id}
                projectId={projectId}
                block={block}
                attachments={page.attachments}
              />
            ))
          )}
        </Stack>
      </Panel>
    </Stack>
  );
}

/**
 * One block, rendered rather than edited.
 *
 * Headings carry their depth as type scale and bullets carry theirs as indent — both off the same
 * `level`, which is what the contract widened to make this outline possible. The links are kept
 * (they are what a requirement being *covered* looks like) and are **navigable, not removable**:
 * unlinking a case from a block is the documentation editor's affordance, on the screen that owns
 * the page.
 */
function BlockView({
  projectId,
  block,
  attachments,
}: {
  projectId: string;
  block: DocBlock;
  attachments: DocPage['attachments'];
}) {
  const isHeading = block.blockType === 'heading';

  return (
    <Box
      data-testid="PlanDocument.Block"
      sx={{
        display: 'flex',
        alignItems: 'flex-start',
        gap: 1,
        mt: isHeading ? 2 : 0,
      }}
    >
      <Stack sx={{ flex: 1, gap: 0.5, minWidth: 0 }}>
        {/*
          The bullet, its indent and the monospace face used to be spelled out here and nowhere
          else — so this panel rendered an outline while the documentation page rendered the same
          blocks as flat prose. `DocBlockBody` is the one renderer now; what stays here is the
          chrome this screen owns, which is the link chips below.
        */}
        <DocBlockBody block={block} attachments={attachments} testId="PlanDocument.Block" />
        {block.links && block.links.length > 0 && (
          <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
            {block.links.map((link) => (
              <Chip
                key={link.id}
                size="small"
                variant="outlined"
                data-testid="PlanDocument.Link.Chip"
                component={RouterLink}
                to={
                  link.sourceType === 'TEST_CASE'
                    ? `/projects/${projectId}/cases/${link.sourceId}`
                    : `/projects/${projectId}/suites/${link.sourceId}`
                }
                clickable
                label={
                  <Stack direction="row" sx={{ gap: 0.75, alignItems: 'center' }}>
                    <Identifier>{link.sourceKey}</Identifier>
                    <span>{link.sourceTitle}</span>
                  </Stack>
                }
              />
            ))}
          </Stack>
        )}
      </Stack>
    </Box>
  );
}
