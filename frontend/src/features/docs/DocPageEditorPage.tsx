import {
  Alert,
  Box,
  Button,
  IconButton,
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
import { useSnackbar } from 'notistack';
import { Controller, useFieldArray, useForm } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
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
  /** A string because it is bound to a `<select>`; empty on everything but a heading. */
  level: z.string(),
  text: z.string(),
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
  const { enqueueSnackbar } = useSnackbar();
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
          // Only a heading carries a depth, and the server refuses one on anything else rather
          // than dropping it. Derived from the type here rather than from whatever the select
          // last held, so switching a heading to a paragraph cannot leave a stale 2 behind.
          level: block.blockType === 'heading' ? Number(block.level || '1') : null,
          text: block.text,
        })),
      });
      enqueueSnackbar(`Saved ${saved.key} as v${saved.currentVersionNo}`, { variant: 'success' });
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
                      Rendered only for a heading, because only a heading has one — the server
                      refuses a level on anything else rather than ignoring it, so offering the
                      control everywhere would invite a rejection the client could have prevented.
                    */}
                    {type === 'heading' && (
                      <Controller
                        control={control}
                        name={`blocks.${index}.level`}
                        render={({ field: select }) => (
                          <TextField
                            {...select}
                            value={select.value || '1'}
                            select
                            size="small"
                            label="Depth"
                            sx={{ width: 96 }}
                            error={!!blockErrors?.level}
                            slotProps={{
                              htmlInput: { 'data-testid': `${TestIdPrefix}.Block.Level` },
                            }}
                          >
                            {[1, 2, 3, 4, 5, 6].map((value) => (
                              <MenuItem key={value} value={String(value)}>
                                H{value}
                              </MenuItem>
                            ))}
                          </TextField>
                        )}
                      />
                    )}
                  </Stack>

                  <TextField
                    {...register(`blocks.${index}.text`)}
                    label="Text"
                    fullWidth
                    multiline
                    minRows={type === 'heading' ? 1 : 2}
                    size="small"
                    error={!!blockErrors?.text}
                    helperText={blockErrors?.text?.message}
                    slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Block.Text` } }}
                  />

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
                  blocks.append({ blockId: null, blockType: 'paragraph', level: '', text: '' })
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
