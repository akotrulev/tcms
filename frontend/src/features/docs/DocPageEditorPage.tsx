import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowDown, ArrowUp, GripVertical, Trash2 } from 'lucide-react';
import { useEffect } from 'react';
import { Controller, useFieldArray, useForm } from 'react-hook-form';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { useAnnounce } from '../../a11y/useAnnounce';
import { ApiError } from '../../api/client';
import { useDocPage, useProject, useUpdateDocPage } from '../../api/queries';
import { useUnsavedWork } from '../../auth/useSessionRecovery';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Banner, DocumentSurface } from '../../components/kit';
import { Button, Textarea } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { cn } from '../../lib/cn';
import { describeError } from '../../lib/describeError';
import { docBlockTypeLabel, docPageStatusLabel } from '../../lib/labels';
import type { DocPage } from '../../api/types';
import { toast } from '../../notifications/toast';

/**
 * Editing a documentation page — the title and the blocks, against `renders/05-authoring.html`
 * frame B at the 70ch prose measure.
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
 *
 * # Blocks get a gutter, not a toolbar each
 *
 * The render's one new component for this screen: a 22px gutter holding the drag handle and the
 * block's type, at 35% opacity until the row is hovered or focused within. *"The affordance
 * arrives where the work is"* — the same idea as the case editor's caret completion. The controls
 * live in that gutter rather than in a *This block* rail, because every block on the page is
 * editable at once here and a rail can only ever describe one.
 */

const MAX_TITLE = 200;

const BLOCK_TYPES = ['heading', 'paragraph', 'list', 'code', 'table', 'image'] as const;

type BlockType = (typeof BLOCK_TYPES)[number];

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

/** `.sec h3` from `renders/tcms.css` — one spelling of a rail section heading on this screen. */
const SECTION_HEADING =
  'm-0 mb-2.5 flex items-baseline gap-2 text-[11px] font-semibold tracking-[.08em] text-ink-3 uppercase';

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
} as const satisfies Record<BlockType, string>;

const TEXT_HINT = {
  heading: undefined,
  paragraph: undefined,
  list: undefined,
  code: undefined,
  table: 'Names the table for anyone reading it, and is what a case links to.',
  image: 'Required. What a reader who cannot see the image gets instead.',
} as const satisfies Record<BlockType, string | undefined>;

/** The gutter's type label — the render's `.ty`, upper-cased mono, never the full word. */
const GUTTER_LABEL = {
  heading: 'H',
  paragraph: 'P',
  list: 'List',
  code: 'Code',
  table: 'Table',
  image: 'Image',
} as const satisfies Record<BlockType, string>;

export function DocPageEditorPage() {
  const { projectId = '', pageId = '' } = useParams();
  const project = useProject(projectId);
  const page = useDocPage(projectId, pageId);

  /*
    ~~Raised by `PageHeader`.~~ → superseded by 007 T14, which replaced that header with the
    render's own top strip. Kept rather than dropped: a route change that announces nothing leaves
    a screen-reader user on a page they have no way to know has changed.
  */
  const announce = useAnnounce();
  useEffect(() => {
    announce('Edit page loaded');
  }, [announce]);

  return (
    <div>
      <title>{page.data ? `Edit · ${page.data.title} · TCMS` : 'Edit page · TCMS'}</title>

      <nav aria-label="Breadcrumb" className="mb-3 text-[12.5px] text-ink-3">
        <RouterLink
          to={`/projects/${projectId}`}
          className="text-ink-3 no-underline hover:text-ink-2"
        >
          {project.data?.name ?? 'Project'}
        </RouterLink>
        <span className="px-1.5 text-ink-4">/</span>
        <RouterLink
          to={`/projects/${projectId}/docs`}
          className="text-ink-3 no-underline hover:text-ink-2"
        >
          Documentation
        </RouterLink>
        <span className="px-1.5 text-ink-4">/</span>
        <RouterLink
          to={`/projects/${projectId}/docs/${pageId}`}
          className="text-ink-3 no-underline hover:text-ink-2"
        >
          {page.data?.title ?? 'Page'}
        </RouterLink>
        <span className="px-1.5 text-ink-4">/</span>
        <b className="font-medium text-ink-2">Edit</b>
      </nav>

      <DataRegion
        testId={`${TestIdPrefix}.Region`}
        state={{ ...page, refetch: () => void page.refetch() }}
      >
        {(data) => <DocPageEditor projectId={projectId} page={data} />}
      </DataRegion>
    </div>
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

  /** What already claims a block on this page — the render's *What links here*. */
  const linkedCases = new Set(
    (page.blocks ?? []).flatMap((block) => block.links.map((link) => link.sourceKey)),
  );

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
          attachmentId: block.blockType === 'image' ? block.attachmentId || null : null,
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
      <div className="mb-3 flex flex-wrap items-center gap-2 border-b border-line pb-3">
        <Identifier variant="muted">{page.key}</Identifier>
        <span className="flex-1" />
        <Button
          onClick={() => navigate(`/projects/${projectId}/docs/${page.id}`)}
          data-testid={`${TestIdPrefix}.Cancel`}
        >
          Discard
        </Button>
        <Button
          type="submit"
          variant="primary"
          disabled={isSubmitting}
          data-testid={`${TestIdPrefix}.Save`}
        >
          {isSubmitting ? 'Saving…' : `Save · v${page.currentVersionNo + 1}`}
        </Button>
      </div>

      <div className="flex flex-col items-start gap-4 xl:flex-row">
        <DocumentSurface
          measure="prose"
          className="min-w-0 flex-1 rounded-lg border border-line"
        >
          {errors.root && (
            <div className="mb-4" data-testid={`${TestIdPrefix}.Error`}>
              <Banner tone="bad">
                <span role="alert">{errors.root.message}</span>
              </Banner>
            </div>
          )}

          {/*
            The title, as the title — no box and no label. It is the first line of the page's
            content rather than a field about it, and the reader who presses Edit should find the
            words they were reading in the place they were reading them.
          */}
          <input
            {...register('title')}
            aria-label="Title"
            required
            maxLength={MAX_TITLE}
            data-testid={`${TestIdPrefix}.Title`}
            className={
              'w-full border-0 bg-transparent p-0 text-2xl leading-[1.25] font-[620] ' +
              'tracking-[-.02em] text-ink placeholder:text-ink-4 ' +
              'focus-visible:outline-2 focus-visible:outline-offset-4 ' +
              'focus-visible:outline-[color:var(--color-given)]'
            }
          />
          {errors.title && (
            <p role="alert" className="m-0 mt-1 text-xs text-[color:var(--color-failed)]">
              {errors.title.message}
            </p>
          )}
          <div className="my-[18px] h-px bg-line" />

          <div data-testid={`${TestIdPrefix}.Blocks`}>
            {blocks.fields.length === 0 && (
              <p className="m-0 mb-3 text-[13px] text-ink-3">
                This page has no content yet. Add a block to start writing — a case can only link
                to a block, so what you split out here is what becomes traceable.
              </p>
            )}

            {blocks.fields.map((field, index) => {
              const type = watched?.[index]?.blockType ?? 'paragraph';
              const blockErrors = errors.blocks?.[index];
              return (
                <div
                  // `field.id` is react-hook-form's render key, which is exactly what it is for.
                  // The DATA id is `blocks[index].blockId` and is never read from here.
                  key={field.id}
                  data-testid={`${TestIdPrefix}.Block`}
                  className="group grid grid-cols-[22px_minmax(0,1fr)] gap-3 py-1"
                >
                  {/*
                    The render's `.gut` — quiet at 35% and full at hover or focus-within, so the
                    handle and the type are there when the author reaches for them and out of the
                    way of the words when they are not.
                  */}
                  <div className="flex flex-col items-center gap-1 pt-1.5 opacity-35 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100">
                    <GripVertical aria-hidden className="size-3.5 text-ink-4" />
                    <span className="font-mono text-[9px] tracking-[.04em] text-ink-4 uppercase">
                      {GUTTER_LABEL[type]}
                    </span>
                  </div>

                  <div className="min-w-0">
                    {/*
                      `text` means three different things across the six types, so the label says
                      which one. On a table it is the caption and on an image the alt text — and
                      the alt text is the field the server refuses when blank, which a control
                      labelled "Text" gives somebody no reason to fill in.
                    */}
                    <Textarea
                      {...register(`blocks.${index}.text`)}
                      aria-label={TEXT_LABEL[type]}
                      required={type === 'image'}
                      rows={type === 'heading' || type === 'image' ? 1 : 3}
                      data-testid={`${TestIdPrefix}.Block.Text`}
                      className={cn(
                        'resize-y',
                        // The monospace face follows the content, so a code block is edited in
                        // the shape it is read in — two clicks away on the same page.
                        type === 'code' && 'font-mono text-[12.5px]',
                        type === 'heading' && 'text-[17px] font-semibold',
                      )}
                    />
                    {(blockErrors?.text?.message || TEXT_HINT[type]) && (
                      <p
                        role={blockErrors?.text ? 'alert' : undefined}
                        className={cn(
                          'm-0 mt-1 text-xs',
                          blockErrors?.text ? 'text-[color:var(--color-failed)]' : 'text-ink-4',
                        )}
                      >
                        {blockErrors?.text?.message ?? TEXT_HINT[type]}
                      </p>
                    )}

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

                    <div className="mt-1.5 flex flex-wrap items-center gap-2 opacity-45 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100">
                      <Controller
                        control={control}
                        name={`blocks.${index}.blockType`}
                        render={({ field: select }) => (
                          <NativeSelect
                            {...select}
                            aria-label="Block type"
                            data-testid={`${TestIdPrefix}.Block.Type`}
                            className="w-[130px]"
                          >
                            {BLOCK_TYPES.map((value) => (
                              <option key={value} value={value}>
                                {docBlockTypeLabel[value]}
                              </option>
                            ))}
                          </NativeSelect>
                        )}
                      />
                      {/*
                        Rendered for the two types that HAVE a depth — the server refuses a level
                        on the other four rather than ignoring it, so offering the control
                        everywhere would invite a rejection the client could have prevented.

                        The list arm is §93's other half arriving: a bullet's indent is what makes
                        a nested scenario outline authorable, the reader has rendered it since, and
                        this control was heading-only, so the depth could be read and never
                        written. The label differs because the two are different things — H3 is a
                        rank, an indent is a position.
                      */}
                      {(type === 'heading' || type === 'list') && (
                        <Controller
                          control={control}
                          name={`blocks.${index}.level`}
                          render={({ field: select }) => (
                            <NativeSelect
                              {...select}
                              value={select.value || '1'}
                              aria-label={type === 'heading' ? 'Depth' : 'Indent'}
                              data-testid={`${TestIdPrefix}.Block.Level`}
                              className="w-[86px]"
                            >
                              {[1, 2, 3, 4, 5, 6].map((value) => (
                                <option key={value} value={String(value)}>
                                  {type === 'heading' ? `H${value}` : value}
                                </option>
                              ))}
                            </NativeSelect>
                          )}
                        />
                      )}

                      <span className="flex-1" />

                      <Button
                        size="icon"
                        variant="quiet"
                        aria-label="Move block up"
                        disabled={index === 0}
                        onClick={() => blocks.move(index, index - 1)}
                        data-testid={`${TestIdPrefix}.Block.Up`}
                      >
                        <ArrowUp aria-hidden className="size-3.5" />
                      </Button>
                      <Button
                        size="icon"
                        variant="quiet"
                        aria-label="Move block down"
                        disabled={index === blocks.fields.length - 1}
                        onClick={() => blocks.move(index, index + 1)}
                        data-testid={`${TestIdPrefix}.Block.Down`}
                      >
                        <ArrowDown aria-hidden className="size-3.5" />
                      </Button>
                      {/*
                        Removing a block here is a local edit until Save; the server is what
                        refuses it when something links to it, and that refusal arrives in the
                        banner above. Warning here instead would mean the client holding a second,
                        staler copy of the rule.
                      */}
                      <Button
                        size="icon"
                        variant="quiet"
                        aria-label="Remove block"
                        title="Remove this block"
                        onClick={() => blocks.remove(index)}
                        data-testid={`${TestIdPrefix}.Block.Remove`}
                      >
                        <Trash2 aria-hidden className="size-3.5" />
                      </Button>
                    </div>
                  </div>
                </div>
              );
            })}

            {/*
              The render's insert row — a hairline with the six types on it, rather than one
              "Add block" that always means paragraph and leaves the author to change it after.
              Paragraph keeps the `AddBlock` locator because it is the same act the old single
              button performed.
            */}
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <span aria-hidden className="h-px flex-1 bg-line" />
              {BLOCK_TYPES.map((value) => (
                <button
                  key={value}
                  type="button"
                  onClick={() =>
                    // blockId null — the server issues one. This is the ONLY way a new id is
                    // created, which is what keeps "the client sent no id" and "this is new" the
                    // same fact.
                    blocks.append({
                      blockId: null,
                      blockType: value,
                      level: value === 'heading' || value === 'list' ? '1' : '',
                      text: '',
                      rows: [],
                      attachmentId: '',
                    })
                  }
                  data-testid={
                    value === 'paragraph'
                      ? `${TestIdPrefix}.AddBlock`
                      : `${TestIdPrefix}.AddBlock.${value}`
                  }
                  className={
                    'cursor-pointer rounded-[6px] border border-dashed border-line-strong px-2.5 py-0.5 ' +
                    'font-mono text-[11px] text-ink-4 hover:border-line-strong hover:text-ink-2 ' +
                    'focus-visible:outline-2 focus-visible:outline-offset-2 ' +
                    'focus-visible:outline-[color:var(--color-given)]'
                  }
                >
                  + {docBlockTypeLabel[value].toLowerCase()}
                </button>
              ))}
              <span aria-hidden className="h-px flex-1 bg-line" />
            </div>
          </div>
        </DocumentSurface>

        <aside className="w-full shrink-0 rounded-lg border border-line bg-panel p-4 xl:w-[280px]">
          {linkedCases.size > 0 && (
            <section className="mb-5">
              <h2 className={SECTION_HEADING}>
                What links here
                <span className="font-sans text-[11px] font-normal tracking-normal text-ink-4 normal-case">
                  {linkedCases.size === 1 ? '1 case' : `${linkedCases.size} cases`}
                </span>
              </h2>
              {/*
                The consequence of saving, counted against the page in front of the author — not a
                warning about something that is currently wrong, but the render's own sentence
                about what this act will cause. Amber because a *Linked spec changed* signal is a
                state somebody then has to clear.
              */}
              <Banner tone="warn">
                <span>
                  Saving marks the cases that cite these blocks as <em>Linked spec changed</em>.
                  They stay approved; they gain a health signal.
                </span>
              </Banner>
            </section>
          )}

          <section>
            <h2 className={SECTION_HEADING}>Publishing</h2>
            <div className="flex items-baseline justify-between gap-2 py-1 text-[12.5px]">
              <span className="text-ink-3">Currently</span>
              <span className="text-ink">
                {docPageStatusLabel[page.status]} · v{page.currentVersionNo}
              </span>
            </div>
            <p className="mt-2 mb-0 text-[12px] leading-[1.5] text-ink-4">
              Saving creates v{page.currentVersionNo + 1}. Publishing it is a separate act, on the
              page itself.
            </p>
          </section>

          <p className="mt-5 border-t border-line pt-3 text-[12px] text-ink-4">
            {blocks.fields.length === 1 ? '1 block' : `${blocks.fields.length} blocks`}
          </p>
        </aside>
      </div>
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
      <div className="mt-2 flex flex-col items-start gap-2">
        <p className="m-0 text-[13px] text-ink-3">
          This table has no rows yet. The first row you add names the columns.
        </p>
        <Button size="sm" onClick={() => onChange([['', '']])} data-testid={`${testId}.Start`}>
          Start the table
        </Button>
      </div>
    );
  }

  return (
    <div className="mt-2 flex flex-col gap-1.5" data-testid={testId}>
      {rows.map((row, rowIndex) => (
        <div
          // Index keys, and correct here: a cell is a bare string with no identity, and rows are
          // only ever appended or removed — there is no reorder for a key to attach to the wrong
          // row, and the inputs are controlled by the value above rather than by their own state.
          key={rowIndex}
          className="flex items-center gap-1.5"
          data-testid={`${testId}.Row`}
        >
          {row.map((cell, cellIndex) => (
            <input
              key={cellIndex}
              type="text"
              value={cell}
              onChange={(event) => setCell(rowIndex, cellIndex, event.target.value)}
              // The header row labels itself, which is how the author learns the rule without a
              // switch that could disagree with it.
              aria-label={rowIndex === 0 ? `Column ${cellIndex + 1}` : `Row ${rowIndex}, cell ${cellIndex + 1}`}
              placeholder={rowIndex === 0 ? 'Column name' : undefined}
              data-testid={`${testId}.Cell`}
              className={
                'min-w-[90px] flex-1 rounded-[6px] border border-line bg-field px-2 py-1 ' +
                'font-mono text-[12px] text-ink placeholder:text-ink-4 ' +
                'focus-visible:outline-2 focus-visible:outline-offset-2 ' +
                'focus-visible:outline-[color:var(--color-given)]'
              }
            />
          ))}
          <Button
            size="icon"
            variant="quiet"
            aria-label="Remove row"
            title={rowIndex === 0 ? 'The header row cannot be removed' : 'Remove this row'}
            disabled={rowIndex === 0}
            onClick={() => removeRow(rowIndex)}
            data-testid={`${testId}.RemoveRow`}
          >
            <Trash2 aria-hidden className="size-3.5" />
          </Button>
        </div>
      ))}
      <div className="flex flex-wrap gap-1.5">
        <Button size="sm" onClick={addRow} data-testid={`${testId}.AddRow`}>
          Add row
        </Button>
        <Button size="sm" onClick={addColumn} data-testid={`${testId}.AddColumn`}>
          Add column
        </Button>
        <Button
          size="sm"
          disabled={columns <= 1}
          onClick={removeColumn}
          data-testid={`${testId}.RemoveColumn`}
        >
          Remove last column
        </Button>
      </div>
    </div>
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
      <div className="mt-2" data-testid={`${testId}.None`}>
        <Banner tone="steel">
          <span>
            This page has no files yet. Attach the image on the{' '}
            <RouterLink
              to={`/projects/${projectId}/docs/${pageId}`}
              className="text-steel underline-offset-4 hover:underline"
            >
              page itself
            </RouterLink>
            , then come back and choose it here.
          </span>
        </Banner>
      </div>
    );
  }

  return (
    <div className="mt-2">
      <NativeSelect
        name={name}
        value={value}
        onChange={onChange}
        onBlur={onBlur}
        aria-label="File"
        data-testid={testId}
      >
        {/*
          An explicit empty option, because a native `<select>` has no null: without it a block
          whose `attachmentId` is still `''` would *look* like the first file was chosen while the
          form held nothing, and Save would send null for an image the author believed they had
          picked. ~~MUI's `displayEmpty` rendered a blank row for this.~~ → 007 T14.
        */}
        <option value="">Choose a file</option>
        {attachments.map((attachment) => (
          <option key={attachment.id} value={attachment.id}>
            {attachment.filename}
          </option>
        ))}
      </NativeSelect>
      <p className="m-0 mt-1 text-xs text-ink-4">One of this page&rsquo;s attachments.</p>
    </div>
  );
}
