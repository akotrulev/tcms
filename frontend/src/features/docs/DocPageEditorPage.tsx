import {
  Alert,
  Box,
  Button,
  IconButton,
  Link as MuiLink,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/AddOutlined';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownwardOutlined';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpwardOutlined';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import { zodResolver } from '@hookform/resolvers/zod';
import { Controller, useFieldArray, useForm } from 'react-hook-form';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useDocPage, useProject, useUpdateDocPage } from '../../api/queries';
import { useUnsavedWork } from '../../auth/useSessionRecovery';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { describeError } from '../../lib/describeError';
import { docBlockTypeLabel } from '../../lib/labels';
import type { DocPage } from '../../api/types';
import { toast } from '../../notifications/toast';

/**
 * Editing a documentation page — the title and the blocks.
 *
 * A route rather than a dialog: this is a long, multi-block form that benefits from a URL, which
 * is `ui-rules`' own test. It follows `CaseEditorPage`, down to registering with
 * {@link useUnsavedWork} so a deploy-skew reload or a mid-session 401 prompts instead of
 * destroying a half-written specification.
 *
 * # The one thing this file must not get wrong
 *
 * **Every block that already exists is sent back with its id.** A block's id is what `doc_link`
 * targets, so an edit that omits it makes the server issue a new one — which is a delete and a
 * create, and every case that verified that paragraph now verifies nothing, silently (plan
 * §6i/§6m). The server refuses an id it does not recognise, but it cannot refuse an id that was
 * never sent: from its side that is indistinguishable from a new block, which is exactly what the
 * user did when they added one.
 *
 * So the client is the only thing standing between "fixed a typo" and "voided the traceability of
 * a page", and the mechanism is one field: `blockId`.
 *
 * ## Why it is called `blockId` and not `id`
 *
 * `useFieldArray` puts **its own `id`** on every entry of `fields` — a render key with no relation
 * to the data. A data property called `id` is therefore shadowed in `fields`, and reading
 * `field.id` gives the framework's key. It would look completely right, submit a fresh uuid for
 * every block on every save, and pass any test that only checks the text came back. Naming the
 * data field differently makes the mistake unavailable rather than merely discouraged.
 */

const MAX_TITLE = 200;

const BLOCK_TYPES = ['heading', 'paragraph', 'list', 'code', 'table', 'image'] as const;

const BlockSchema = z.object({
  /** Null for a block being added here. See the header: NOT `id`. */
  blockId: z.string().nullable(),
  blockType: z.enum(BLOCK_TYPES),
  /** A string because it is bound to a `<select>`; empty on the four types with no depth. */
  level: z.string(),
  text: z.string(),
  /**
   * A `table`'s cells, row-major, first row the header.
   *
   * Held as the real shape rather than as delimited text in one field. A textarea where lines are
   * rows and tabs are cells is a quarter of the code and it is the mistake §93 already refused one
   * field over: a cell containing the delimiter silently splits into two, and a table cannot be
   * indented, re-columned or validated per cell. Empty on every other type.
   */
  rows: z.array(z.array(z.string())),
  /** An `image`'s file. Empty string means none chosen — a `<select>` has no null. */
  attachmentId: z.string(),
});

const PageSchema = z.object({
  title: z
    .string()
    .min(1, 'Enter a title.')
    .max(MAX_TITLE, `Keep the title under ${MAX_TITLE} characters.`),
  blocks: z.array(BlockSchema),
});

type PageFormInput = z.infer<typeof PageSchema>;

const TestIdPrefix = 'DocEditor';

/**
 * What `text` is called, per type — because it is three different fields wearing one name.
 *
 * `satisfies Record<...>` for `lib/labels.ts`' reason: a seventh block type becomes a compile error
 * here rather than a control silently labelled "Text" on the one screen that authors it.
 */
const TEXT_LABEL = {
  heading: 'Heading',
  paragraph: 'Text',
  list: 'Bullet',
  code: 'Code',
  table: 'Caption',
  image: 'Alt text',
} as const satisfies Record<(typeof BLOCK_TYPES)[number], string>;

const TEXT_HINT = {
  heading: undefined,
  paragraph: undefined,
  list: undefined,
  code: undefined,
  table: 'Names the table for anyone reading it, and is what a case links to.',
  image: 'Required. What a reader who cannot see the image gets instead.',
} as const satisfies Record<(typeof BLOCK_TYPES)[number], string | undefined>;

export function DocPageEditorPage() {
  const { projectId = '', pageId = '' } = useParams();
  const project = useProject(projectId);
  const page = useDocPage(projectId, pageId);

  return (
    <Box>
      <PageHeader
        testId={`${TestIdPrefix}.Header`}
        title="Edit page"
        documentTitle={page.data ? `Edit · ${page.data.title}` : 'Edit page'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Documentation', to: `/projects/${projectId}/docs` },
          { label: page.data?.title ?? 'Page', to: `/projects/${projectId}/docs/${pageId}` },
          { label: 'Edit' },
        ]}
      />
      <DataRegion
        testId={`${TestIdPrefix}.Region`}
        state={{ ...page, refetch: () => void page.refetch() }}
      >
        {(data) => <DocPageEditor projectId={projectId} page={data} />}
      </DataRegion>
    </Box>
  );
}

/**
 * The form itself, exported so it can be tested without a router match.
 *
 * Same split as `BoardPage`/`Board`: the outer component resolves route params and the data
 * region, and everything worth asserting is in here — which is what lets the payload assertions
 * in `DocPageEditorPage.test.tsx` drive the real component rather than a re-implementation of it.
 */
export function DocPageEditor({ projectId, page }: { projectId: string; page: DocPage }) {
  const navigate = useNavigate();
  const update = useUpdateDocPage(projectId, page.id);

  const {
    control,
    register,
    handleSubmit,
    setError,
    watch,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<PageFormInput>({
    resolver: zodResolver(PageSchema),
    mode: 'onBlur',
    defaultValues: {
      title: page.title,
      blocks: [...(page.blocks ?? [])]
        .sort((a, b) => a.position - b.position)
        .map((block) => ({
          // The line the whole file is about.
          blockId: block.id,
          blockType: block.blockType,
          level: block.level == null ? '' : String(block.level),
          text: block.text,
          // Copied out of the response rather than referenced: the form owns its own array from
          // here, and mutating the cached page's would edit what the reader beside it renders.
          rows: (block.rows ?? []).map((row) => [...row]),
          attachmentId: block.attachmentId ?? '',
        })),
    },
  });

  useUnsavedWork(isDirty && !isSubmitting, 'an edited documentation page');

  const blocks = useFieldArray({ control, name: 'blocks' });
  const watched = watch('blocks');

  const submit = handleSubmit(async (values) => {
    try {
      const saved = await update.mutateAsync({
        title: values.title.trim(),
        lockVersion: page.lockVersion,
        blocks: values.blocks.map((block) => ({
          // Omitted rather than sent as null when the block is new: the contract types it
          // nullable so either would be accepted, and omission is the shape that says "no id"
          // without asserting one.
          ...(block.blockId ? { id: block.blockId } : {}),
          blockType: block.blockType,
          /*
            Every type-specific field is derived from the TYPE here, never from whatever the
            control last held. That is one rule applied three times and it is the reason all three
            lines look alike: the server refuses each of these on a type that does not have it, so
            a table turned into a paragraph with its cells still in form state is a 400 the user
            cannot see the cause of. Switching type is an ordinary thing to do while writing.

            `level` is now sent for a LIST as well as a heading, which is a fix rather than a new
            feature: §93 widened the contract to make a nested outline authorable, the reader has
            indented bullets ever since, and this form has never sent the depth — so the control
            existed on one side of a round trip only.
          */
          level:
            block.blockType === 'heading' || block.blockType === 'list'
              ? Number(block.level || '1')
              : null,
          text: block.text,
          rows: block.blockType === 'table' ? block.rows : null,
          attachmentId:
            block.blockType === 'image' ? block.attachmentId || null : null,
        })),
      });
      toast(`Saved ${saved.key} as v${saved.currentVersionNo}`, { variant: 'success' });
      navigate(`/projects/${projectId}/docs/${saved.id}`);
    } catch (error) {
      /*
        Field-keyed server errors land on their fields. The block keys arrive as
        `blocks[3].text`, which is the path RHF already uses, so they land on the right row of a
        long page rather than as one banner saying a block somewhere is wrong.

        The 409s have no field at all and are the two most informative failures this form can
        get — a stale lockVersion, and "3 links point at content you are removing". Both go to
        the banner, which is where the user can read them beside the page they are about.
      */
      if (error instanceof ApiError && error.hasFieldErrors) {
        let landed = false;
        for (const [field, messages] of Object.entries(error.fieldErrors ?? {})) {
          if (field === 'title' || field.startsWith('blocks[')) {
            setError(field as 'title', { type: 'server', message: messages.join(' ') });
            landed = true;
          }
        }
        if (landed) return;
      }
      setError('root', { type: 'server', message: describeError(error).body });
    }
  });

  return (
    <form onSubmit={submit} noValidate aria-busy={isSubmitting}>
      <Panel
        testId={`${TestIdPrefix}.Panel`}
        title={
          <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1 }}>
            <Identifier variant="muted">{page.key}</Identifier>
            <Typography variant="h4" component="h2">
              Edit page
            </Typography>
            <Identifier variant="muted">v{page.currentVersionNo}</Identifier>
          </Stack>
        }
        actions={
          <Stack direction="row" sx={{ gap: 1 }}>
            <Button
              onClick={() => navigate(`/projects/${projectId}/docs/${page.id}`)}
              data-testid={`${TestIdPrefix}.Cancel`}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              variant="contained"
              disabled={isSubmitting}
              data-testid={`${TestIdPrefix}.Save`}
            >
              {isSubmitting ? 'Saving…' : 'Save page'}
            </Button>
          </Stack>
        }
      >
        <Stack spacing={3}>
          {errors.root && (
            <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Error`}>
              {errors.root.message}
            </Alert>
          )}

          <TextField
            {...register('title')}
            label="Title"
            fullWidth
            required
            error={!!errors.title}
            helperText={errors.title?.message}
            slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Title`, maxLength: MAX_TITLE } }}
          />

          <Stack spacing={2} data-testid={`${TestIdPrefix}.Blocks`}>
            {blocks.fields.length === 0 && (
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                This page has no content yet. Add a block to start writing — a case can only link
                to a block, so what you split out here is what becomes traceable.
              </Typography>
            )}

            {blocks.fields.map((field, index) => {
              const type = watched?.[index]?.blockType ?? 'paragraph';
              const blockErrors = errors.blocks?.[index];
              return (
                <Stack
                  // `field.id` is react-hook-form's render key, which is exactly what it is for.
                  // The DATA id is `blocks[index].blockId` and is never read from here.
                  key={field.id}
                  direction={{ xs: 'column', md: 'row' }}
                  spacing={1}
                  data-testid={`${TestIdPrefix}.Block`}
                  sx={{
                    alignItems: 'flex-start',
                    p: 1.5,
                    borderRadius: 1,
                    border: '1px solid',
                    borderColor: 'divider',
                  }}
                >
                  <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
                    <Controller
                      control={control}
                      name={`blocks.${index}.blockType`}
                      render={({ field: select }) => (
                        <TextField
                          {...select}
                          select
                          size="small"
                          label="Type"
                          sx={{ width: 140 }}
                          slotProps={{
                            htmlInput: { 'data-testid': `${TestIdPrefix}.Block.Type` },
                          }}
                        >
                          {BLOCK_TYPES.map((value) => (
                            <MenuItem key={value} value={value}>
                              {docBlockTypeLabel[value]}
                            </MenuItem>
                          ))}
                        </TextField>
                      )}
                    />
                    {/*
                      Rendered for the two types that HAVE a depth — the server refuses a level on
                      the other four rather than ignoring it, so offering the control everywhere
                      would invite a rejection the client could have prevented.

                      The list arm is §93's other half arriving: a bullet's indent is what makes a
                      nested scenario outline authorable, the reader has rendered it since, and
                      this control was heading-only, so the depth could be read and never written.
                      The label differs because the two are different things — H3 is a rank, an
                      indent is a position.
                    */}
                    {(type === 'heading' || type === 'list') && (
                      <Controller
                        control={control}
                        name={`blocks.${index}.level`}
                        render={({ field: select }) => (
                          <TextField
                            {...select}
                            value={select.value || '1'}
                            select
                            size="small"
                            label={type === 'heading' ? 'Depth' : 'Indent'}
                            sx={{ width: 96 }}
                            error={!!blockErrors?.level}
                            slotProps={{
                              htmlInput: { 'data-testid': `${TestIdPrefix}.Block.Level` },
                            }}
                          >
                            {[1, 2, 3, 4, 5, 6].map((value) => (
                              <MenuItem key={value} value={String(value)}>
                                {type === 'heading' ? `H${value}` : value}
                              </MenuItem>
                            ))}
                          </TextField>
                        )}
                      />
                    )}
                  </Stack>

                  <Stack sx={{ flex: 1, gap: 1, minWidth: 0 }}>
                    {/*
                      `text` means three different things across the six types, so the label says
                      which one. On a table it is the caption and on an image the alt text — and
                      the alt text is the field the server refuses when blank, which a control
                      labelled "Text" gives somebody no reason to fill in.
                    */}
                    <TextField
                      {...register(`blocks.${index}.text`)}
                      label={TEXT_LABEL[type]}
                      fullWidth
                      multiline
                      minRows={type === 'heading' || type === 'image' ? 1 : 2}
                      size="small"
                      required={type === 'image'}
                      error={!!blockErrors?.text}
                      helperText={blockErrors?.text?.message ?? TEXT_HINT[type]}
                      slotProps={{
                        htmlInput: {
                          'data-testid': `${TestIdPrefix}.Block.Text`,
                          // The monospace face follows the content, so a code block is edited in
                          // the shape it is read in — two clicks away on the same page.
                          style: type === 'code' ? { fontFamily: 'var(--font-mono)' } : undefined,
                        },
                      }}
                    />

                    {type === 'table' && (
                      <Controller
                        control={control}
                        name={`blocks.${index}.rows`}
                        render={({ field: cells }) => (
                          <TableRowsEditor
                            rows={cells.value}
                            onChange={cells.onChange}
                            testId={`${TestIdPrefix}.Block.Rows`}
                          />
                        )}
                      />
                    )}

                    {type === 'image' && (
                      <Controller
                        control={control}
                        name={`blocks.${index}.attachmentId`}
                        render={({ field: select }) => (
                          <ImageFilePicker
                            {...select}
                            attachments={page.attachments}
                            projectId={projectId}
                            pageId={page.id}
                            testId={`${TestIdPrefix}.Block.File`}
                          />
                        )}
                      />
                    )}
                  </Stack>

                  <Stack direction="row" sx={{ flexShrink: 0 }}>
                    <IconButton
                      aria-label="Move block up"
                      size="small"
                      disabled={index === 0}
                      onClick={() => blocks.move(index, index - 1)}
                      data-testid={`${TestIdPrefix}.Block.Up`}
                    >
                      <ArrowUpwardIcon fontSize="small" />
                    </IconButton>
                    <IconButton
                      aria-label="Move block down"
                      size="small"
                      disabled={index === blocks.fields.length - 1}
                      onClick={() => blocks.move(index, index + 1)}
                      data-testid={`${TestIdPrefix}.Block.Down`}
                    >
                      <ArrowDownwardIcon fontSize="small" />
                    </IconButton>
                    {/*
                      Removing a block here is a local edit until Save; the server is what refuses
                      it when something links to it, and that refusal arrives in the banner above.
                      Warning here instead would mean the client holding a second, staler copy of
                      the rule.
                    */}
                    <Tooltip title="Remove this block">
                      <IconButton
                        aria-label="Remove block"
                        size="small"
                        onClick={() => blocks.remove(index)}
                        data-testid={`${TestIdPrefix}.Block.Remove`}
                      >
                        <DeleteOutlineIcon fontSize="small" />
                      </IconButton>
                    </Tooltip>
                  </Stack>
                </Stack>
              );
            })}

            <Box>
              <Button
                startIcon={<AddIcon />}
                onClick={() =>
                  // blockId null — the server issues one. This is the ONLY way a new id is
                  // created, which is what keeps "the client sent no id" and "this is new" the
                  // same fact.
                  blocks.append({
                    blockId: null,
                    blockType: 'paragraph',
                    level: '',
                    text: '',
                    rows: [],
                    attachmentId: '',
                  })
                }
                data-testid={`${TestIdPrefix}.AddBlock`}
              >
                Add block
              </Button>
            </Box>
          </Stack>
        </Stack>
      </Panel>
    </form>
  );
}

/**
 * A table's cells, edited as a grid.
 *
 * ## Why a grid rather than a textarea of delimited lines
 *
 * A single field where lines are rows and tabs are cells is a quarter of this code, and it is the
 * mistake §93 already refused one field over. A cell containing the delimiter splits into two with
 * nothing reported; a column cannot be inserted; and a ragged row — which the server refuses by
 * naming its index — is something the author can only find by counting characters. The grid makes
 * ragged **unconstructable**: rows are added and removed whole, columns across every row at once.
 *
 * ## The first row is the header, and it says so on screen
 *
 * There is no "this table has a header" switch, on the server or here. A boolean would have two
 * states that look identical in an editor and differ only in what a screen reader announces for
 * every cell below it, so it gets set wrong and nothing reports it. What the editor owes the author
 * instead is to be plain about the rule, which is the first row's label.
 *
 * Uncontrolled-to-controlled is deliberately avoided: every cell always has a string, because a
 * `undefined` cell would make its input switch modes mid-edit and lose the caret.
 */
function TableRowsEditor({
  rows,
  onChange,
  testId,
}: {
  rows: string[][];
  onChange: (rows: string[][]) => void;
  testId: string;
}) {
  const columns = rows[0]?.length ?? 0;

  const setCell = (rowIndex: number, cellIndex: number, value: string) =>
    onChange(
      rows.map((row, r) => (r === rowIndex ? row.map((cell, c) => (c === cellIndex ? value : cell)) : row)),
    );

  // Every mutation below rebuilds the WHOLE grid rather than editing one row, which is what keeps
  // the rectangle a rectangle. Adding a column to "the current row" is the shape that produces the
  // ragged payload the server refuses.
  const addRow = () => onChange([...rows, Array.from({ length: Math.max(columns, 1) }, () => '')]);
  const removeRow = (rowIndex: number) => onChange(rows.filter((_, r) => r !== rowIndex));
  const addColumn = () => onChange(rows.map((row) => [...row, '']));
  const removeColumn = () => onChange(rows.map((row) => row.slice(0, -1)));

  if (rows.length === 0) {
    return (
      <Stack sx={{ gap: 1, alignItems: 'flex-start' }}>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          This table has no rows yet. The first row you add names the columns.
        </Typography>
        <Button
          size="small"
          startIcon={<AddIcon />}
          onClick={() => onChange([['', '']])}
          data-testid={`${testId}.Start`}
        >
          Start the table
        </Button>
      </Stack>
    );
  }

  return (
    <Stack sx={{ gap: 1 }} data-testid={testId}>
      {rows.map((row, rowIndex) => (
        <Stack
          // Index keys, and correct here: a cell is a bare string with no identity, and rows are
          // only ever appended or removed — there is no reorder for a key to attach to the wrong
          // row, and the inputs are controlled by the value above rather than by their own state.
          key={rowIndex}
          direction="row"
          sx={{ gap: 1, alignItems: 'center' }}
          data-testid={`${testId}.Row`}
        >
          {row.map((cell, cellIndex) => (
            <TextField
              key={cellIndex}
              value={cell}
              onChange={(event) => setCell(rowIndex, cellIndex, event.target.value)}
              size="small"
              // The header row labels itself, which is how the author learns the rule without a
              // switch that could disagree with it.
              label={rowIndex === 0 ? `Column ${cellIndex + 1}` : undefined}
              placeholder={rowIndex === 0 ? 'Column name' : undefined}
              sx={{ flex: 1, minWidth: 90 }}
              slotProps={{ htmlInput: { 'data-testid': `${testId}.Cell` } }}
            />
          ))}
          <Tooltip title={rowIndex === 0 ? 'The header row cannot be removed' : 'Remove this row'}>
            {/* A span, because a disabled button fires no events and MUI's tooltip needs one to
                listen to — so the reason for the refusal would otherwise be unreachable. */}
            <span>
              <IconButton
                aria-label="Remove row"
                size="small"
                disabled={rowIndex === 0}
                onClick={() => removeRow(rowIndex)}
                data-testid={`${testId}.RemoveRow`}
              >
                <DeleteOutlineIcon fontSize="small" />
              </IconButton>
            </span>
          </Tooltip>
        </Stack>
      ))}
      <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
        <Button size="small" onClick={addRow} data-testid={`${testId}.AddRow`}>
          Add row
        </Button>
        <Button size="small" onClick={addColumn} data-testid={`${testId}.AddColumn`}>
          Add column
        </Button>
        <Button
          size="small"
          disabled={columns <= 1}
          onClick={removeColumn}
          data-testid={`${testId}.RemoveColumn`}
        >
          Remove last column
        </Button>
      </Stack>
    </Stack>
  );
}

/**
 * Which of the page's files an image block shows.
 *
 * **A picker over what is already attached, and deliberately not an upload control.** A block names
 * an `attachment` the page owns — the server refuses an id that is not on this page — so the file
 * arrives through the page's own attachment panel, which owns the size limit, the empty-file
 * refusal and the 413. An upload here would be a second copy of all three, and it would have to
 * happen *before* Save while everything else on this form happens at Save.
 *
 * The empty state therefore says where to go rather than offering a way out of it, and the control
 * stays visible-but-empty instead of disappearing: a block that showed no file chooser at all would
 * read as an image block that does not take a file.
 */
function ImageFilePicker({
  value,
  onChange,
  onBlur,
  name,
  attachments,
  projectId,
  pageId,
  testId,
}: {
  value: string;
  onChange: (event: unknown) => void;
  onBlur: () => void;
  name: string;
  attachments: DocPage['attachments'];
  projectId: string;
  pageId: string;
  testId: string;
}) {
  if (attachments.length === 0) {
    return (
      <Alert severity="info" data-testid={`${testId}.None`}>
        This page has no files yet. Attach the image on the{' '}
        <MuiLink component={RouterLink} to={`/projects/${projectId}/docs/${pageId}`}>
          page itself
        </MuiLink>
        , then come back and choose it here.
      </Alert>
    );
  }

  return (
    <TextField
      select
      name={name}
      value={value}
      onChange={onChange}
      onBlur={onBlur}
      size="small"
      label="File"
      helperText="One of this page's attachments."
      slotProps={{ htmlInput: { 'data-testid': testId } }}
    >
      {attachments.map((attachment) => (
        <MenuItem key={attachment.id} value={attachment.id}>
          {attachment.filename}
        </MenuItem>
      ))}
    </TextField>
  );
}
