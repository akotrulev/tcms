import { Link2, Plus, Search, Trash2, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useAnnounce } from '../../a11y/useAnnounce';
import type {
  BlockLink,
  DocPage,
  DocBlock,
  DocPageSearchHit,
  DocPageStatus,
  DocPageSummary,
  TestCaseSummary,
} from '../../api/types';
import {
  useAttachDocPageFile,
  useCase,
  useDeleteDocPageFile,
  useDocPage,
  useDocPageSearch,
  useDocPages,
  useProject,
  useSetDocPageStatus,
  useRelabelBlockLink,
  useUnlinkBlock,
} from '../../api/queries';
import { AttachmentsPanel } from '../../components/AttachmentList';
import { DocBlockBody } from '../../components/DocBlockBody';
import { ReviewPanel } from '../review/ReviewPanel';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Badge, Banner, DocumentSurface, ResultBar } from '../../components/kit';
import { LinkRelationshipControl } from '../../components/LinkRelationshipControl';
import { PermissionGate } from '../../components/PermissionGate';
import { UnlinkDialog } from '../../components/UnlinkDialog';
import { Button } from '../../components/ui';
import { cn } from '../../lib/cn';
import { formatCount, formatDateTime } from '../../lib/format';
import { docPageStatusLabel } from '../../lib/labels';
import { CreateDocPageDialog, DeleteDocPageDialog } from './DocPageDialogs';
import { LinkBlockDialog } from './LinkBlockDialog';

/**
 * The render's `.r.on` — the tint marking the page currently open, in both the tree and the
 * results. An inline style rather than an arbitrary Tailwind value, matching `kit/Table`'s
 * selected row: one spelling of `color-mix` per surface, and the kit already chose this one.
 */
const SELECTED_ROW = {
  backgroundColor: 'color-mix(in oklab, var(--color-steel) 12%, transparent)',
} as const;

/** `.sec h3` from `renders/tcms.css` — one spelling of a rail section heading on this screen. */
const SECTION_HEADING =
  'm-0 mb-2.5 flex items-baseline gap-2 text-[11px] font-semibold tracking-[.08em] text-ink-3 uppercase';

/**
 * The documentation space (plan §6i), against `renders/05-authoring.html` frame A.
 *
 * Requirements are documentation blocks — there is no separate requirement entity — so this
 * screen is where traceability is actually visible, and the mechanism that makes people
 * *maintain* their links is the **inline coverage marker**: every block shows how many cases
 * verify it and how those cases last did. A paragraph with no linked cases is visibly
 * untested right there in the spec, which is far more motivating than a coverage report
 * somebody has to go and look for.
 *
 * ## Coverage is per paragraph, not per page — and it moved under the clause to say so
 *
 * ~~The marker was a 132px right margin beside the block.~~ → **superseded by 007 T14.** The render
 * puts the verdict *under the sentence it judges*, because a margin is something a reader scanning
 * prose skips and a line under the clause is something they have already read. The page-level
 * aggregate survives, but only in the rail and only as a count — *"a page-level percentage would
 * have averaged the one gap away"*.
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

  /*
    ~~Raised by `PageHeader`.~~ → superseded by 007 T14, which replaced that header with the
    render's own top strip. Kept rather than dropped: a route change that announces nothing leaves
    a screen-reader user on a page they have no way to know has changed.
  */
  const announce = useAnnounce();
  useEffect(() => {
    announce('Documentation page loaded');
  }, [announce]);

  /*
    A8 — the case somebody arrived here to claim coverage for.

    In the URL rather than in a store, which is this SPA's rule for anything that narrows what a
    screen is doing: the reader browses the tree, deep-links a colleague at a paragraph, or reloads,
    and the intent survives all three. It is also the only place it *can* live — the jump comes from
    another route entirely, and a block link is creatable from this side alone (openapi.yaml's
    rationale at the case end), so the trip is the product's one path from a case to a requirement.
  */
  const [searchParams, setSearchParams] = useSearchParams();
  const intendedCaseId = searchParams.get('linkCase') ?? undefined;
  /*
    §235 — the search, in the URL for the same reason the carried case is: a reader deep-links a
    colleague at "everything we say about chargebacks", reloads, or presses Back out of a page they
    opened from the results, and all three have to survive. It is also what makes the rail's state
    and the request agree — there is one q, and the query key is built from it.
  */
  const searchQuery = searchParams.get('q') ?? '';
  const hits = useDocPageSearch(projectId, searchQuery);
  const intended = useCase(projectId, intendedCaseId);
  const stopLinking = () => {
    const next = new URLSearchParams(searchParams);
    next.delete('linkCase');
    setSearchParams(next, { replace: true });
  };

  // With no page selected, land on the first published one rather than showing an empty
  // pane next to a populated tree.
  const firstPageId = pages.data?.[0]?.id;
  // Both redirects below carry the query string with them. They are the *first* thing that happens
  // to a reader arriving from a case — `/docs?linkCase=…` has no page id — so a redirect that
  // rebuilt only the path would drop the intent before anything could read it, which is a whole
  // feature failing in the one route it was built for.
  const query = searchParams.toString();
  const suffix = query ? `?${query}` : '';
  useEffect(() => {
    if (!pageId && firstPageId) {
      navigate(`/projects/${projectId}/docs/${firstPageId}${suffix}`, { replace: true });
    }
  }, [pageId, firstPageId, projectId, suffix, navigate]);

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
      navigate(`/projects/${projectId}/docs${suffix}`, { replace: true });
    }
  }, [pageMissing, projectId, suffix, navigate]);

  const openPage = pages.data?.find((row) => row.id === pageId);

  return (
    <div>
      <title>
        {project.data ? `Documentation · ${project.data.name} · TCMS` : 'Documentation · TCMS'}
      </title>

      <header className="mb-3 flex flex-wrap items-start gap-3 border-b border-white/8 pb-3">
        <div className="min-w-0 flex-1">
          <nav aria-label="Breadcrumb" className="mb-1 text-[12.5px] text-ink-3">
            <RouterLink
              to={`/projects/${projectId}`}
              className="text-ink-3 no-underline hover:text-ink-2"
            >
              {project.data?.name ?? 'Project'}
            </RouterLink>
            <span className="px-1.5 text-ink-4">/</span>
            <b className="font-medium text-ink-2">Documentation</b>
            {openPage && (
              <>
                <span className="px-1.5 text-ink-4">/</span>
                <b className="font-medium text-ink-2">{openPage.title}</b>
              </>
            )}
          </nav>
          <h1 className="m-0 text-[19px] font-semibold tracking-[-.018em] text-ink">
            Documentation
          </h1>
          <p className="m-0 mt-1 max-w-[74ch] text-[13px] text-ink-3">
            Specifications live here. Cases link to individual blocks, and each block shows what
            verifies it.
          </p>
        </div>

        <PermissionGate projectId={projectId} permission="doc:edit">
          {({ allowed }) => (
            <Button
              variant="primary"
              size="sm"
              disabled={!allowed}
              onClick={() => setCreating(true)}
              data-testid="Docs.NewPage"
            >
              <Plus aria-hidden className="size-3.5" />
              New page
            </Button>
          )}
        </PermissionGate>
      </header>

      {/*
        The carried case, named. It is a banner rather than a highlighted control somewhere in the
        page because the reader has to *navigate* — pick a page from the tree, scroll to the
        paragraph — before the thing they came to do is in reach, and an intent that is only visible
        on the screen it started on is an intent that has already been forgotten by the time it
        matters. It says how to finish and how to stop, which are the only two ways out.
      */}
      {intendedCaseId && (
        <div className="mb-3" data-testid="Docs.LinkIntent">
          <Banner tone="steel">
            <span className="flex flex-1 flex-wrap items-center gap-2">
              <span className="flex-1">
                {intended.data ? (
                  <>
                    Linking{' '}
                    {/* The way back, on the name itself: the reader came from this case and will
                        want to return to it, and a banner that can only be dismissed strands them
                        here. */}
                    <RouterLink
                      to={`/projects/${projectId}/cases/${intended.data.id}`}
                      data-testid="Docs.LinkIntent.Case"
                      className="text-steel underline-offset-4 hover:underline"
                    >
                      {intended.data.key}
                    </RouterLink>{' '}
                    — {intended.data.title}. Open the page it verifies and press Link on that block.
                  </>
                ) : (
                  'Linking a case. Open the page it verifies and press Link on that block.'
                )}
              </span>
              <Button size="sm" onClick={stopLinking} data-testid="Docs.LinkIntent.Stop">
                Stop linking
              </Button>
            </span>
          </Banner>
        </div>
      )}

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

      {/* The render's `.split.three`: the tree, the reading column, and the page's own facts. */}
      <div className="flex flex-col items-start gap-4 lg:flex-row">
        <aside
          className="w-full shrink-0 rounded-lg border border-white/8 bg-panel p-3 lg:w-[260px]"
          data-testid="Docs.TreePanel"
        >
          <h2 className={SECTION_HEADING}>{searchQuery ? 'Results' : 'Pages'}</h2>

          <DocsSearchField
            query={searchQuery}
            count={pages.data?.length}
            onSearch={(next) => {
              const params = new URLSearchParams(searchParams);
              if (next) params.set('q', next);
              else params.delete('q');
              setSearchParams(params);
            }}
          />

          {searchQuery ? (
            <DataRegion
              testId="Docs.Results"
              state={{ ...hits, refetch: () => void hits.refetch() }}
              isEmpty={(rows) => rows.length === 0}
              empty={{
                kind: 'no-filter-match',
                title: 'No pages match',
                body: 'Whole words only — a half-typed one finds nothing. Try a different term.',
              }}
            >
              {(rows) => (
                <SearchResults hits={rows} projectId={projectId} activeId={pageId} suffix={suffix} />
              )}
            </DataRegion>
          ) : (
            <DataRegion
              testId="Docs.Tree"
              state={{ ...pages, refetch: () => void pages.refetch() }}
              isEmpty={(rows) => rows.length === 0}
              empty={{
                kind: 'no-records',
                title: 'No pages',
                body: 'Write the first specification page.',
              }}
            >
              {(rows) => (
                <PageTree pages={rows} projectId={projectId} activeId={pageId} suffix={suffix} />
              )}
            </DataRegion>
          )}
        </aside>

        <div className="min-w-0 flex-1">
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
                <div className="flex min-w-0 flex-col items-start gap-4 xl:flex-row">
                  <div className="min-w-0 flex-1">
                    <PageView
                      page={data}
                      projectId={projectId}
                      onDelete={setDeleting}
                      intendedCase={intended.data ?? null}
                    />
                    <div className="mt-6">
                      <PageAttachments page={data} projectId={projectId} />
                    </div>
                    {/*
                      §6g's reviewer apparatus, now that a page is a reviewable subject (005 S9b).
                      Below the attachments for the same reason a case's sits below its steps: it is
                      about the page rather than part of it.

                      Mounted unconditionally — `ReviewPanel` renders **nothing** when
                      `reviewEnabledDoc` is off, which is §6g's "a project that never turns it on
                      should see no extra UI at all" enforced in one place rather than at each of
                      the four call sites. `currentVersionNo` is passed so the panel can say the
                      page has moved on since the reviewer was asked.
                    */}
                    <div className="mt-6">
                      <ReviewPanel
                        projectId={projectId}
                        subjectType="DOC"
                        subjectId={data.id}
                        currentVersionNo={data.currentVersionNo}
                      />
                    </div>
                  </div>

                  <PageFacts page={data} />
                </div>
              )}
            </DataRegion>
          ) : (
            <p
              className="m-0 rounded-lg border border-white/8 bg-panel p-4 text-[13px] text-ink-3"
              data-testid="Docs.NoSelection"
            >
              {(pages.data?.length ?? 0) === 0
                ? 'No pages yet. A specification here is what test cases link to — start with one page per area of the product.'
                : 'Pick a page from the list to read it.'}
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * The search box — **submit, not as-you-type**, and that is the feature rather than a shortcut.
 *
 * The server matches lexemes, so a half-typed word matches nothing at all: results-as-you-type
 * would show "No pages match" for every keystroke until the moment a word completed, which reads as
 * a broken search rather than as an unfinished query. It also sidesteps the debounce-and-detached-
 * node trap that cost this repo a flaky test for weeks (§186).
 *
 * The draft is local and the committed query is the URL's. They differ only between typing and
 * Enter, and re-syncing on the URL is what makes Back — which changes `query` under this component
 * — put the box back in step with the results beside it.
 */
function DocsSearchField({
  query,
  count,
  onSearch,
}: {
  query: string;
  /** The size of the space, in the placeholder — the render's *Filter 37 pages*. */
  count: number | undefined;
  onSearch: (next: string) => void;
}) {
  const [draft, setDraft] = useState(query);
  useEffect(() => setDraft(query), [query]);

  return (
    <form
      className="relative mb-2.5"
      onSubmit={(event) => {
        event.preventDefault();
        onSearch(draft.trim());
      }}
    >
      <Search
        aria-hidden
        className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-ink-4"
      />
      <input
        type="text"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        aria-label="Search documentation"
        data-testid="Docs.Search.Input"
        placeholder={count === undefined ? 'Search documentation' : `Filter ${count} pages`}
        className={
          'w-full rounded-[6px] border border-white/8 bg-field py-1.5 pr-8 pl-8 text-[12.5px] ' +
          'text-ink placeholder:text-ink-4 focus-visible:outline-2 focus-visible:outline-offset-2 ' +
          'focus-visible:outline-[color:var(--color-given)]'
        }
      />
      {draft && (
        <button
          type="button"
          aria-label="Clear search"
          data-testid="Docs.Search.Clear"
          onClick={() => {
            setDraft('');
            onSearch('');
          }}
          className={
            'absolute top-1/2 right-2 -translate-y-1/2 cursor-pointer text-ink-4 hover:text-ink ' +
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]'
          }
        >
          <X aria-hidden className="size-3.5" />
        </button>
      )}
    </form>
  );
}

/**
 * The hits, in the order the server ranked them.
 *
 * Not re-sorted here, unlike {@link PageTree}, which sorts siblings by title: the ordering *is* the
 * answer a ranked search gives, and a client that re-sorted it would be throwing away the weights
 * `V2` stores and the reason the endpoint computes a rank at all.
 */
function SearchResults({
  hits,
  projectId,
  activeId,
  suffix,
}: {
  hits: DocPageSearchHit[];
  projectId: string;
  activeId: string | undefined;
  suffix: string;
}) {
  return (
    <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
      {hits.map((hit) => {
        const active = hit.id === activeId;
        return (
          <li key={hit.id}>
            <RouterLink
              to={`/projects/${projectId}/docs/${hit.id}${suffix}`}
              data-testid="Docs.Results.Item"
              aria-current={active ? 'page' : undefined}
              style={active ? SELECTED_ROW : undefined}
              className="block rounded-md px-2 py-1.5 no-underline hover:bg-white/5"
            >
              <span
                className={cn(
                  'block truncate text-[13px]',
                  active ? 'font-semibold text-ink' : 'font-medium text-ink-2',
                )}
              >
                {hit.title}
              </span>
              <Identifier variant="muted">{hit.key}</Identifier>
              {/*
                Clamped rather than truncated to one line: the fragment exists to show WHICH
                mention this is, and a single ellipsised line is usually the words either side of
                the match and none of the match itself.
              */}
              {hit.snippet && (
                <span
                  data-testid="Docs.Results.Snippet"
                  className="mt-0.5 line-clamp-2 block text-[11.5px] text-ink-4"
                >
                  {hit.snippet}
                </span>
              )}
            </RouterLink>
          </li>
        );
      })}
    </ul>
  );
}

function PageTree({
  pages,
  projectId,
  activeId,
  suffix,
}: {
  pages: DocPageSummary[];
  projectId: string;
  activeId: string | undefined;
  /**
   * The query string, carried onto every page link. This tree is how a reader gets from the page
   * they landed on to the one their case actually verifies, so a link that dropped it would end the
   * intent on the first click of the journey it exists for.
   */
  suffix: string;
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
    <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
      {tree.map(({ page, depth }) => {
        const active = page.id === activeId;
        /*
          Rule 3 of the render: **a draft stays in the tree, dimmed.** Hiding drafts made people
          write the same page twice — so the row is quieter, never absent, and it says which state
          it is in rather than only that it is not published.

          Any status but PUBLISHED, not just DRAFT. ARCHIVED became reachable when the status
          endpoint landed, and the old `=== 'DRAFT'` check would have rendered an archived page with
          no chip at all — indistinguishable from a published one, in the tree that is the only way
          back to it.
        */
        const unpublished = page.status !== 'PUBLISHED';
        return (
          <li key={page.id}>
            <RouterLink
              to={`/projects/${projectId}/docs/${page.id}${suffix}`}
              data-testid="Docs.Tree.Item"
              aria-current={active ? 'page' : undefined}
              style={{ paddingLeft: `${8 + depth * 14}px`, ...(active ? SELECTED_ROW : {}) }}
              className={cn(
                'flex items-center gap-2 rounded-md py-1 pr-2 text-[13px] no-underline hover:bg-white/5',
                active ? 'font-semibold text-ink' : 'text-ink-2',
                unpublished && !active && 'opacity-60',
              )}
            >
              <span className="min-w-0 flex-1 truncate">{page.title}</span>
              {unpublished && (
                <span className="shrink-0 font-mono text-[10.5px] text-ink-4">
                  {docPageStatusLabel[page.status].toLowerCase()}
                </span>
              )}
            </RouterLink>
          </li>
        );
      })}
    </ul>
  );
}

/**
 * A page's coverage, counted **per block** and never turned into a percentage.
 *
 * Headings are excluded because they are structure, not requirements — counting them as
 * uncovered would make every page look worse than it is.
 *
 * `block.coverage != null` is the half the live pass added, and it is the same distinction the
 * server makes one layer down: the field is **absent** until something can create a `doc_link`,
 * because a zeroed marker states that nothing verifies this block when the true statement is
 * that nothing has asked. Reading absent as zero put that claim back — as an amber "1 of 1
 * unlinked" on a page whose only heading demonstrably *had* a link.
 */
function coverageOf(page: DocPage) {
  const blocks = [...(page.blocks ?? [])].sort((a, b) => a.position - b.position);
  const coverable = blocks.filter((block) => block.blockType !== 'heading' && block.coverage != null);
  const untested = coverable.filter((block) => (block.coverage?.linkedCases ?? 0) === 0);
  const failing = coverable.filter((block) => (block.coverage?.failed ?? 0) > 0);
  const passing = coverable.filter(
    (block) =>
      (block.coverage?.linkedCases ?? 0) > 0 &&
      (block.coverage?.failed ?? 0) === 0 &&
      (block.coverage?.notRun ?? 0) === 0,
  );
  const notRun = coverable.length - untested.length - failing.length - passing.length;

  return { blocks, coverable, untested, failing, passing, notRun };
}

function PageView({
  page,
  projectId,
  onDelete,
  intendedCase,
}: {
  page: DocPage;
  projectId: string;
  onDelete: (page: DocPageSummary) => void;
  /**
   * Drilled from the page rather than read per block: it is one case for the whole screen, and a
   * block reading the search param for itself would issue one case request per paragraph.
   */
  intendedCase: TestCaseSummary | null;
}) {
  const { blocks } = coverageOf(page);

  return (
    <DocumentSurface
      measure="prose"
      data-testid="Docs.Content"
      className="rounded-lg border border-white/8"
    >
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Identifier variant="strong">{page.key}</Identifier>
        <Identifier variant="muted">v{page.currentVersionNo}</Identifier>
        {page.status !== 'PUBLISHED' && (
          <Badge tone="plain">{docPageStatusLabel[page.status].toLowerCase()}</Badge>
        )}
      </div>

      <h1 className="m-0 mb-1.5 text-2xl leading-[1.25] font-[620] tracking-[-.02em] text-ink text-balance">
        {page.title}
      </h1>
      <p className="m-0 text-[12.5px] text-ink-4">Updated {formatDateTime(page.updatedAt)}</p>

      <div className="my-[18px] flex flex-wrap items-center gap-2 border-t border-white/8 pt-3">
        <PageActions page={page} projectId={projectId} onDelete={onDelete} />
      </div>

      {blocks.length === 0 ? (
        <p className="m-0 text-[13px] text-ink-3" data-testid="Docs.Content.Empty">
          This page has no content yet.
        </p>
      ) : (
        blocks.map((block) => (
          <BlockView
            key={block.id}
            block={block}
            projectId={projectId}
            pageId={page.id}
            attachments={page.attachments}
            intendedCase={intendedCase}
          />
        ))
      )}
    </DocumentSurface>
  );
}

/**
 * The render's right column — what the page's coverage adds up to, who claims it, and where it is
 * in its publication life.
 *
 * A **count, never a percentage.** The whole reason the verdict moved under each clause is that a
 * page-level ratio averages the one gap away; putting the ratio back in the rail would undo it in
 * the one place a reader looks for a summary.
 */
function PageFacts({ page }: { page: DocPage }) {
  const { coverable, untested, failing, passing, notRun } = coverageOf(page);

  const linked = new Map<string, BlockLink>();
  for (const block of page.blocks ?? []) {
    for (const link of block.links) linked.set(link.id, link);
  }

  return (
    <aside className="w-full shrink-0 rounded-lg border border-white/8 bg-panel p-4 xl:w-[280px]">
      {coverable.length > 0 && (
        <section className="mb-5">
          <h2 className={SECTION_HEADING}>
            Coverage
            <span className="font-sans text-[11px] font-normal tracking-normal text-ink-4 normal-case">
              <span data-testid="Docs.Content.CoverageSummary">
                {formatCount(coverable.length - untested.length)} of{' '}
                {formatCount(coverable.length)}
              </span>
            </span>
          </h2>
          <ResultBar
            className="mb-3"
            passed={passing.length}
            failed={failing.length}
            blocked={notRun}
            untested={untested.length}
          />
          <FactRow label="Tested and passing" value={formatCount(passing.length)} />
          <FactRow
            label="Tested and failing"
            value={formatCount(failing.length)}
            tone="var(--color-failed)"
          />
          <FactRow
            label="Not tested"
            value={formatCount(untested.length)}
            tone="var(--color-blocked)"
          />
        </section>
      )}

      {linked.size > 0 && (
        <section className="mb-5">
          <h2 className={SECTION_HEADING}>
            Cases linked here
            <span className="font-sans text-[11px] font-normal tracking-normal text-ink-4 normal-case">
              {formatCount(linked.size)}
            </span>
          </h2>
          {[...linked.values()].map((link) => (
            <p key={link.id} className="m-0 truncate py-1 text-[12.5px] text-ink-3">
              <Identifier variant="muted">{link.sourceKey}</Identifier> {link.sourceTitle}
            </p>
          ))}
          <p className="mt-2 mb-0 text-[12px] leading-[1.5] text-ink-4">
            Editing a block marks the cases that cite it <em>Linked spec changed</em> until someone
            re-reads them.
          </p>
        </section>
      )}

      <section>
        <h2 className={SECTION_HEADING}>History</h2>
        <FactRow label={`Version v${page.currentVersionNo}`} value={docPageStatusLabel[page.status]} />
        <FactRow label="Updated" value={formatDateTime(page.updatedAt)} />
      </section>
    </aside>
  );
}

/** The render's `.kv` — a claim on the left, its number on the right. */
function FactRow({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-2 py-1 text-[12.5px]">
      <span className="text-ink-3">{label}</span>
      <span className="font-mono text-[12px] tabular-nums text-ink" style={tone ? { color: tone } : undefined}>
        {value}
      </span>
    </div>
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
            size="sm"
            variant="primary"
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
              size="sm"
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
            size="sm"
            variant="danger"
            disabled={!allowed}
            onClick={() => onDelete(page)}
            data-testid="Docs.Content.Delete"
          >
            <Trash2 aria-hidden className="size-3.5" />
            Delete
          </Button>
        )}
      </PermissionGate>
    </>
  );
}

/**
 * A page's files.
 *
 * **Its own region beneath the page rather than a section inside `Docs.Content`**, because the page
 * body is prose with a coverage line under every clause and an attachment strip is neither —
 * dropping rows into the block flow would put a file list where a reader is scanning for the next
 * paragraph. Below the content for the same reason a case's sits below its steps: it is supporting
 * material.
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

function BlockView({
  block,
  projectId,
  pageId,
  attachments,
  intendedCase,
}: {
  block: DocBlock;
  projectId: string;
  pageId: string;
  attachments: DocPage['attachments'];
  intendedCase: TestCaseSummary | null;
}) {
  const isHeading = block.blockType === 'heading';

  return (
    <div
      // The stable block id is the anchor. It never changes when the text does, which is the
      // whole reason a case's link to it can be trusted years later.
      id={block.id}
      data-testid="Docs.Block"
      className={cn(
        'scroll-mt-[72px] target:rounded-[2px] target:outline-2 target:outline-offset-[6px]',
        'target:outline-[color:var(--color-action-primary)]',
        isHeading ? 'mt-6 mb-1' : 'mb-4',
      )}
    >
      <DocBlockBody block={block} attachments={attachments} testId="Docs.Block" />
      {/*
        No coverage line on a heading, which is not a styling choice: a heading is structure rather
        than a requirement, and the page's own aggregate excludes it for the same reason.
      */}
      {!isHeading && <CoverageLine block={block} projectId={projectId} />}
      <BlockLinks block={block} projectId={projectId} pageId={pageId} intendedCase={intendedCase} />
    </div>
  );
}

/**
 * What claims to verify this block, and the only way in the product to say so (§172).
 *
 * ## Below the body, under the coverage line
 *
 * ~~The margin is 132px of annotation and a case reads `TC-1284 — Refund is refused after 30 days`;
 * one does not fit in the other.~~ → **superseded by 007 T14**, which deleted the margin: the
 * verdict is now a line under the clause and the claims are the line under that. The two are still
 * two different questions, which is the same split `DocBlock.links` and `DocBlock.coverage` make on
 * the wire: `links` is present and possibly empty because the server knows exactly who claims this,
 * `coverage` is null when nobody does.
 *
 * ## Offered on a heading too
 *
 * A heading gets no coverage line — it is structure, not a requirement — and it gets a Link
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
 * block's coverage line says and withdrawing one from the case end does not.
 */
function BlockLinks({
  block,
  projectId,
  pageId,
  intendedCase,
}: {
  block: DocBlock;
  projectId: string;
  pageId: string;
  intendedCase: TestCaseSummary | null;
}) {
  const [linking, setLinking] = useState(false);
  // The link being withdrawn, not a boolean: the dialog names it, and the list mounts one control
  // per row. A boolean would confirm "a link" and remove whichever one was clicked last.
  const [withdrawing, setWithdrawing] = useState<BlockLink | null>(null);
  const remove = useUnlinkBlock(projectId, pageId);
  const relabel = useRelabelBlockLink(projectId, pageId);

  return (
    <div className="mt-1 flex flex-wrap items-center gap-2" data-testid="Docs.Block.Links">
      {block.links.map((link) => (
        <span
          // A static row locator: the set is addressed by index, never by the link's id.
          key={link.id}
          className="flex items-center gap-1"
          data-testid="Docs.Block.Links.Item"
        >
          <RouterLink
            // The source's own screen, not a filtered list: a case has a detail route and a suite
            // does not, so the suite arm lands on the case list narrowed to it — the same two
            // destinations the board and the plan statistics page already use for the same pair.
            to={
              link.sourceType === 'TEST_CASE'
                ? `/projects/${projectId}/cases/${link.sourceId}`
                : `/projects/${projectId}/cases?suiteId=${link.sourceId}`
            }
            data-testid="Docs.Block.Links.Item.Source"
            className={
              'inline-flex items-center gap-1.5 rounded-[5px] border border-white/8 px-2.5 py-[3px] ' +
              'text-xs text-ink-2 no-underline hover:border-white/14 hover:bg-white/4 hover:text-ink'
            }
          >
            <Identifier variant="muted">{link.sourceKey}</Identifier> {link.sourceTitle}
          </RouterLink>
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
                <Button
                  size="icon"
                  variant="quiet"
                  aria-label={`Remove the link to ${link.sourceKey}`}
                  title={`Withdraw ${link.sourceKey}'s claim on this block`}
                  disabled={remove.isPending}
                  onClick={() => setWithdrawing(link)}
                  data-testid="Docs.Block.Links.Remove"
                >
                  <Trash2 aria-hidden className="size-3.5" />
                </Button>
              ) : null
            }
          </PermissionGate>
        </span>
      ))}

      {/* Disabled rather than hidden (§31) — a Viewer should be able to see that this is a thing
          the product does. `doc:edit`, which is the endpoint's own gate on both verbs: a link is a
          property of the documentation, so its holder is everyone who writes docs. */}
      <PermissionGate projectId={projectId} permission="doc:edit">
        {({ allowed }) => (
          <Button
            size="sm"
            variant="quiet"
            disabled={!allowed}
            onClick={() => setLinking(true)}
            data-testid="Docs.Block.Links.AddButton"
          >
            <Link2 aria-hidden className="size-3.5" />
            {/* Named while a case is being carried: the reader is scanning a page of paragraphs
                for the one their case verifies, and every Link on it does something different
                from what it did before they arrived. */}
            {intendedCase
              ? `Link ${intendedCase.key}`
              : block.links.length === 0
                ? 'Link a case'
                : 'Link'}
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
          intendedCase={intendedCase}
        />
      )}

      {withdrawing && (
        <UnlinkDialog
          open
          onClose={() => setWithdrawing(null)}
          target={`${withdrawing.sourceKey} — ${withdrawing.sourceTitle}`}
          // This screen's own sentence: the line under the clause is computed from these rows, so a
          // withdrawal here has a visible effect on the page the reader is looking at. The `OWN`
          // of the two consequences that is worth spending a line on.
          consequence="The link is withdrawn and this block's coverage is recounted. The case itself is not touched."
          onConfirm={() => remove.mutate(withdrawing.id, { onSuccess: () => setWithdrawing(null) })}
          pending={remove.isPending}
          error={remove.isError ? remove.error : null}
          reset={remove.reset}
          testId="Docs.Block.Links.Unlink"
        />
      )}
    </div>
  );
}

/**
 * The verdict on **this clause**, under it — the render's central rule for family 5.
 *
 * *"The chip sits under the clause it judges, so a reader learns which sentence is untested without
 * leaving the sentence. A page-level percentage would have averaged the one gap away."*
 *
 * An *uncovered* block says so in words rather than showing a zero, because "0" reads as a data
 * point and "Not tested" reads as a problem — and it carries **the button that fixes it** (UX#80),
 * so the gap and its remedy are never more than one click apart.
 *
 * Null and zero are ONE branch since §168, and the server only ever sends the first. It sends
 * `coverage: null` for a block no case claims to verify and never a row of zeros — the absence of
 * a claim is not a claim.
 */
function CoverageLine({ block, projectId }: { block: DocBlock; projectId: string }) {
  const coverage = block.coverage;

  if (!coverage || coverage.linkedCases === 0) {
    return (
      <div className="mt-1.5 flex flex-wrap items-center gap-2">
        <Badge tone="blocked" data-testid="Docs.Block.Coverage.Untested">
          Not tested
        </Badge>
        <span className="text-[12px] text-ink-4">no case links to this paragraph</span>
        {/*
          `objective`, carried on the query string. `CaseEditorPage` reads `suiteId` from the URL
          today and not this — the seeding half is one line in a file 007 T7 owns, recorded as T14's
          one open item. The link is written carrying it so that line is all that is left to add.
        */}
        <Button asChild size="sm" data-testid="Docs.Block.Coverage.WriteOne">
          <RouterLink
            to={`/projects/${projectId}/cases/new?objective=${encodeURIComponent(block.text)}`}
          >
            Write one
          </RouterLink>
        </Button>
      </div>
    );
  }

  const { linkedCases, passed, failed, notRun, caseKeys } = coverage;
  const tone = failed > 0 ? 'failed' : notRun > 0 ? 'blocked' : 'passed';
  const label = failed > 0 ? 'Failing' : notRun > 0 ? 'Not run' : 'Tested';

  return (
    <div className="mt-1.5 flex flex-wrap items-center gap-2">
      <Badge tone={tone}>{label}</Badge>
      {/* Links to the case list filtered to the cases linked here, so the line is an entrance to
          the work and not just a readout. */}
      <RouterLink
        to={`/projects/${projectId}/cases?q=${encodeURIComponent(caseKeys[0] ?? '')}`}
        data-testid="Docs.Block.Coverage"
        className="text-[12px] text-ink-4 no-underline hover:text-ink-3"
      >
        by{' '}
        {caseKeys.length > 0 ? (
          caseKeys.map((key, index) => (
            <span key={key}>
              {index > 0 && ', '}
              <Identifier variant="muted">{key}</Identifier>
            </span>
          ))
        ) : (
          <>
            {formatCount(linkedCases)} {linkedCases === 1 ? 'case' : 'cases'}
          </>
        )}{' '}
        · {formatCount(passed)} passed · {formatCount(failed)} failed · {formatCount(notRun)} not run
      </RouterLink>
    </div>
  );
}
