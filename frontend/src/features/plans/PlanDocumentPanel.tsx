import {
  Alert,
  Box,
  Button,
  Chip,
  IconButton,
  Link as MuiLink,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/AddOutlined';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import { useMemo, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import type { DocBlock, DocPage } from '../../api/types';
import {
  useCreateDraftCase,
  useLinkBlock,
  usePlanDocument,
  useSuites,
  useUnlinkBlock,
  useUpdateDocPage,
} from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PermissionGate } from '../../components/PermissionGate';
import { Panel } from '../../components/PageHeader';
import { describeError } from '../../lib/describeError';

/**
 * The heading that opens the section a case can be added to.
 *
 * Matched by text, which is the weak point of this screen and is written down rather than hidden.
 * The document is an ordinary page whose blocks are free text, so there is no marker on a block
 * saying "cases go here" — and inventing one would mean a block type, a migration, and a document
 * whose structure the author cannot rearrange.
 *
 * What makes the weakness tolerable is the failure mode: rename the heading and the "Add test case"
 * control moves to the end of the document rather than disappearing. Nothing breaks, nothing is
 * lost, and the author who renamed it can see where it went.
 */
const CASE_SECTION = 'test cases';

/**
 * A test plan's narrative half — the document, rendered and edited in place.
 *
 * ## Three states, and the first one is not an error
 *
 * A plan with no document is the ordinary starting state, not a failure: a plan is created when
 * somebody schedules a cycle and its document is written afterwards, by a different person, often
 * on a different day. So the empty state is an invitation with a button, and `usePlanDocument`
 * turns the server's 404 into `null` precisely so that it never reaches the global error surface.
 *
 * ## Editing is in place, one block at a time
 *
 * There is no "edit mode" switch. Each block is a text field that saves on blur, which suits what
 * this document is for — somebody filling in one section, closing the tab, and coming back to
 * another. A modal editor would make "add one case I just thought of" a five-click operation.
 *
 * Every save sends the WHOLE block list back with every existing id intact. That is not an
 * optimisation opportunity: a block sent without its id is deleted and recreated, and every link
 * pointing at it is silently destroyed (plan §6i/§6m). `blocksOf` is the one place the list is
 * built, for that reason.
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
          <DocumentBody projectId={projectId} page={page} />
        ) : (
          <Panel testId="PlanDocument.Empty">
            <Stack sx={{ gap: 1.5, alignItems: 'flex-start' }}>
              <Typography variant="h6">No page is linked to this plan</Typography>
              <Typography variant="body2" sx={{ color: 'text.secondary', maxWidth: 640 }}>
                A plan&rsquo;s requirements are an ordinary documentation page that this plan
                adopts — written where specifications are written, keeping its own history and
                links. Write it in the documentation section, then link it here.
              </Typography>
              <Button
                component={RouterLink}
                to={`/projects/${projectId}/docs`}
                variant="outlined"
                data-testid="PlanDocument.OpenDocumentation"
              >
                Open documentation
              </Button>
            </Stack>
          </Panel>
        )
      }
    </DataRegion>
  );
}

/**
 * The whole block list, as the edit endpoint requires it.
 *
 * The ONE place a payload is built. Every mutation below goes through it, because the field that
 * must never be dropped is `id` and a second construction site is where it eventually is.
 */
type BlockPayload = {
  /** Absent ⇒ a new block, whose id the server issues. Present ⇒ edit it in place. */
  id?: string;
  blockType: DocBlock['blockType'];
  level: number | null | undefined;
  text: string;
};

function blocksOf(page: DocPage): BlockPayload[] {
  return page.blocks.map((block) => ({
    id: block.id,
    blockType: block.blockType,
    // Sent only where the server accepts it: required on a heading, optional on a list bullet,
    // refused on everything else. Sending `null` on a paragraph is fine; sending a number is a 400.
    level: block.blockType === 'heading' || block.blockType === 'list' ? block.level : null,
    text: block.text,
  }));
}

function DocumentBody({ projectId, page }: { projectId: string; page: DocPage }) {
  const save = useUpdateDocPage(projectId, page.id);
  const unlink = useUnlinkBlock(projectId, page.id);

  /** Replaces one block's text and saves the page. */
  const editText = (blockId: string, text: string) => {
    const blocks = blocksOf(page).map((block) =>
      block.id === blockId ? { ...block, text } : block,
    );
    save.mutate({ title: page.title, blocks, lockVersion: page.lockVersion });
  };

  const removeBlock = (blockId: string) => {
    save.mutate({
      title: page.title,
      blocks: blocksOf(page).filter((block) => block.id !== blockId),
      lockVersion: page.lockVersion,
    });
  };

  // Where a new case bullet is inserted: the end of the "Test cases" section, which runs from its
  // heading to the next heading of the same or shallower depth, or to the end of the document.
  const caseSection = useMemo(() => findCaseSection(page.blocks), [page.blocks]);

  return (
    <Stack sx={{ gap: 2 }}>
      {save.isError && (
        <Alert severity="error" data-testid="PlanDocument.SaveError">
          {describeError(save.error).body}
        </Alert>
      )}

      <Panel testId="PlanDocument.Body">
        <Stack sx={{ gap: 0.5 }}>
          {page.blocks.map((block) => (
            <BlockRow
              key={block.id}
              projectId={projectId}
              block={block}
              onEdit={editText}
              onRemove={removeBlock}
              onUnlink={(linkId) => unlink.mutate(linkId)}
            />
          ))}
        </Stack>
      </Panel>

      <AddCaseBar
        projectId={projectId}
        page={page}
        insertAt={caseSection.insertAt}
        sectionFound={caseSection.found}
      />
    </Stack>
  );
}

/**
 * Where the "Test cases" section ends.
 *
 * @returns `insertAt` — the index a new bullet goes at — and whether the section was found at all.
 *          Not found means the author renamed or removed the heading, and the bullet goes at the
 *          end of the document rather than nowhere: a create that silently did nothing is worse
 *          than one that puts the case somewhere visible.
 */
function findCaseSection(blocks: DocBlock[]) {
  const start = blocks.findIndex(
    (block) =>
      block.blockType === 'heading' && block.text.trim().toLowerCase() === CASE_SECTION,
  );
  if (start < 0) return { found: false, insertAt: blocks.length };

  const depth = blocks[start].level ?? 1;
  for (let index = start + 1; index < blocks.length; index += 1) {
    const block = blocks[index];
    if (block.blockType === 'heading' && (block.level ?? 1) <= depth) {
      return { found: true, insertAt: index };
    }
  }
  return { found: true, insertAt: blocks.length };
}

/** One block: its text, its indent, and whatever is linked to it. */
function BlockRow({
  projectId,
  block,
  onEdit,
  onRemove,
  onUnlink,
}: {
  projectId: string;
  block: DocBlock;
  onEdit: (blockId: string, text: string) => void;
  onRemove: (blockId: string) => void;
  onUnlink: (linkId: string) => void;
}) {
  const [draft, setDraft] = useState(block.text);

  // Headings carry their depth as type scale; bullets carry theirs as indent. Both come off the
  // same `level`, which is what the contract widened to make this outline possible.
  const depth = block.level ?? 1;
  const isHeading = block.blockType === 'heading';
  const isBullet = block.blockType === 'list';

  return (
    <Box
      data-testid="PlanDocument.Block"
      sx={{
        display: 'flex',
        alignItems: 'flex-start',
        gap: 1,
        pl: isBullet ? (depth - 1) * 3 : 0,
        mt: isHeading ? 2 : 0,
      }}
    >
      {isBullet && (
        <Typography sx={{ color: 'text.secondary', pt: 1.2, lineHeight: 1 }} aria-hidden>
          •
        </Typography>
      )}
      <Stack sx={{ flex: 1, gap: 0.5 }}>
        <TextField
          value={draft}
          multiline={!isHeading}
          fullWidth
          size="small"
          variant="standard"
          onChange={(event) => setDraft(event.target.value)}
          // Saved on blur rather than per keystroke. Every save is a PATCH of the whole page and a
          // version decision on the server; per-keystroke would fill the history with one entry
          // per letter, which is the same thing as having no history.
          onBlur={() => {
            if (draft !== block.text) onEdit(block.id, draft);
          }}
          slotProps={{
            htmlInput: { 'aria-label': isHeading ? `Heading: ${block.text}` : 'Text' },
          }}
          sx={{
            '& .MuiInputBase-input': {
              fontSize: isHeading ? `${1.5 - (depth - 1) * 0.18}rem` : '0.95rem',
              fontWeight: isHeading ? 600 : 400,
            },
          }}
        />
        {block.links && block.links.length > 0 && (
          <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
            {block.links.map((link) => (
              <Chip
                key={link.id}
                size="small"
                variant="outlined"
                data-testid="PlanDocument.Link"
                // The chip navigates to the case; its delete removes the link and never the case.
                // Two destructive-looking affordances on one row is a real risk here, which is why
                // the tooltip says which one this is.
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
                onDelete={() => onUnlink(link.id)}
              />
            ))}
          </Stack>
        )}
      </Stack>
      <Tooltip title="Remove this line">
        <span>
          <IconButton
            size="small"
            aria-label="Remove this line"
            data-testid="PlanDocument.RemoveBlock"
            onClick={() => onRemove(block.id)}
          >
            <DeleteOutlineIcon fontSize="small" />
          </IconButton>
        </span>
      </Tooltip>
    </Box>
  );
}

/**
 * Adds a test case to the plan, by title alone.
 *
 * ## Three requests, and the order is the design
 *
 * 1. Create the case — a real `test_case`, `DRAFT`, in the chosen suite.
 * 2. Add a bullet to the document carrying the title.
 * 3. Link the bullet to the case.
 *
 * The case is created **first** because it is the durable half: if step 2 or 3 fails, the case
 * exists in the repository and is findable by title, whereas a bullet with no case behind it is a
 * note that looks like a test case and is not one. The reverse order fails the other way, and this
 * screen exists precisely so that a plan's list of intended cases is more than a note.
 *
 * ## Why the client orchestrates it and the server does not
 *
 * `documentation` may not write `test_case` — it may depend only on `tenancy`, `identity` and
 * `project` — so a single endpoint doing all three cannot live in the module that owns the page.
 * Nor may `repository` assemble a document. A server-side version needs a domain event, of which
 * this product has none, or a fourth module. Three ordinary requests from the client is the honest
 * shape and its partial-failure behaviour is the one described above rather than one hidden inside
 * a transaction that was never atomic across two modules anyway.
 */
function AddCaseBar({
  projectId,
  page,
  insertAt,
  sectionFound,
}: {
  projectId: string;
  page: DocPage;
  insertAt: number;
  sectionFound: boolean;
}) {
  const suites = useSuites(projectId);
  const createCase = useCreateDraftCase(projectId);
  const save = useUpdateDocPage(projectId, page.id);
  const link = useLinkBlock(projectId, page.id);

  const [title, setTitle] = useState('');
  const [suiteId, setSuiteId] = useState('');
  const [failure, setFailure] = useState<string | null>(null);
  /**
   * The case this bar just made, so it can offer the way into writing it up (§94).
   *
   * This bar creates a title and a suite and nothing else — deliberately, because it is a place to
   * capture intent while reading a plan, not a place to write a test. The steps are written in the
   * **case editor**, which since §94 completes each action from the project's own corpus, appends
   * rows as you type and reorders by drag. Growing a second step editor beside this bar would be a
   * second implementation of all of it — `ui-rules` §8, and CLAUDE.md's "hundred slightly
   * different pages" in one screen.
   *
   * Held rather than navigated to: the author is usually adding several cases in a row, and
   * leaving the plan on the first one would make the bar's whole shape wrong.
   */
  const [written, setWritten] = useState<{ id: string; key: string } | null>(null);

  const busy = createCase.isPending || save.isPending || link.isPending;
  const ready = title.trim().length > 0 && suiteId.length > 0 && !busy;

  const add = async () => {
    setFailure(null);
    setWritten(null);
    try {
      // 1. The durable half. DRAFT is the server's default and is not sent — a status an ordinary
      // create could set is an approval its author granted themselves (§6g).
      const created = await createCase.mutateAsync({ title: title.trim(), suiteId });

      // 2. The bullet, at depth 1, inserted at the end of the Test cases section.
      const blocks = blocksOf(page);
      blocks.splice(insertAt, 0, { blockType: 'list', level: 1, text: title.trim() });
      const saved = await save.mutateAsync({
        title: page.title,
        blocks,
        lockVersion: page.lockVersion,
      });

      // 3. The link. The new block is the one at `insertAt` with no counterpart in the old list —
      // identified by position rather than by text, because two cases may legitimately share a
      // title and matching on text would link the wrong one.
      const bullet = saved.blocks[insertAt];
      if (bullet) {
        await link.mutateAsync({
          blockId: bullet.id,
          sourceType: 'TEST_CASE',
          sourceId: created.id,
          // Spelled out rather than left to the server's default: this is the one relationship a
          // case created FROM a plan bullet can have, and a default that changed would silently
          // reclassify every case this screen ever made.
          relationship: 'VERIFIES',
        });
      }
      setTitle('');
      setWritten({ id: created.id, key: created.key });
    } catch (error) {
      // Named in place, and it says what DID happen: the case may well exist even though the
      // document does not show it, and a bare "couldn't save" would send the author to create it
      // again.
      setFailure(
        `${describeError(error).body} The test case may already have been created — check the ` +
          'case list before adding it again.',
      );
    }
  };

  return (
    <Panel testId="PlanDocument.AddCase">
      <Stack sx={{ gap: 1.5 }}>
        <Typography variant="subtitle1">Add a test case</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Give it a title now and write it up later. It is created as a draft in the case
          repository and listed under Test cases in this document.
        </Typography>
        {!sectionFound && (
          <Alert severity="info" data-testid="PlanDocument.NoCaseSection">
            This document has no “Test cases” heading, so new cases are added at the end.
          </Alert>
        )}
        {failure && (
          <Alert severity="error" data-testid="PlanDocument.AddCaseError">
            {failure}
          </Alert>
        )}
        {written && (
          <Alert severity="success" data-testid="PlanDocument.AddCaseWritten">
            {written.key} is in the repository as a draft.{' '}
            <MuiLink
              component={RouterLink}
              to={`/projects/${projectId}/cases/${written.id}/edit`}
              data-testid="PlanDocument.AddCaseWrite"
            >
              Write the steps
            </MuiLink>
            .
          </Alert>
        )}
        <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1 }}>
          <TextField
            label="Title"
            value={title}
            size="small"
            fullWidth
            onChange={(event) => setTitle(event.target.value)}
            data-testid="PlanDocument.CaseTitle"
          />
          <TextField
            select
            label="Suite"
            value={suiteId}
            size="small"
            sx={{ minWidth: 220 }}
            onChange={(event) => setSuiteId(event.target.value)}
            data-testid="PlanDocument.CaseSuite"
            // Required by the contract: a case belongs to a suite, and there is no "unfiled". The
            // author picks once and the field keeps its value for the next case.
            helperText="Where the draft is filed"
          >
            {(suites.data ?? []).map((suite) => (
              <MenuItem key={suite.id} value={suite.id}>
                {suite.name}
              </MenuItem>
            ))}
          </TextField>
          <PermissionGate permission="case:create" projectId={projectId}>
            {({ allowed }) => (
              <Button
                variant="contained"
                startIcon={<AddIcon />}
                disabled={!allowed || !ready}
                onClick={() => void add()}
                data-testid="PlanDocument.AddCaseSubmit"
                sx={{ whiteSpace: 'nowrap' }}
              >
                Add case
              </Button>
            )}
          </PermissionGate>
        </Stack>
      </Stack>
    </Panel>
  );
}
