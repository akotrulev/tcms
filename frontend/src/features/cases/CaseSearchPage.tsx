import { Search, X } from 'lucide-react';
import { useSearchParams } from 'react-router-dom';
import { Link as RouterLink } from 'react-router-dom';
import { isSearchable, useCaseSearch, useProjects } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import {
  Button,
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
  Input,
} from '../../components/ui';
import { formatCount, formatDateTime } from '../../lib/format';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { useAnnouncedResults } from '../../a11y/useAnnouncedResults';
import { automationLabels, lifecycleLabels, priorityLabels } from '../../lib/labels';

const TestId = 'CaseSearch';
const DEFAULT_SIZE = 25;

/**
 * 300ms, the case filter bar's figure rather than the step-suggestion popup's 250: this box sits
 * at the top of a page whose table refills below it, where a third of a second reads as the result
 * arriving rather than the field lagging.
 */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * Find a case across every project the caller can see (§249).
 *
 * <h2>Why the route is `/search` and not under `/projects/:projectId`</h2>
 *
 * `MyWorkPage`'s argument, and it transfers exactly: the project segment is a real navigation
 * boundary, every screen under it reads its project for breadcrumbs, the rail and permission
 * scoping — and **this screen has no project to read**. Nesting it would put an arbitrary project
 * in the URL of a list that spans all of them, and switching projects in the rail would appear to
 * change a result set that cannot change. The whole premise here is that the reader does not know
 * which project holds the case.
 *
 * <h2>The empty state is THREE states, and the third is the one that was missing</h2>
 *
 * "No case matches" and "you can see no projects" are the **same HTTP response** — `200` with
 * `total: 0`, because the server refuses to make them distinguishable (§6t: a 403 would answer the
 * question the empty answer exists to withhold). They are not the same sentence. Rendering the
 * first for a member holding no grants makes a permissions problem look like a product with no
 * data in it, and the person waits for data that will never arrive instead of asking an
 * administrator for a grant. So the page asks `useProjects` how many projects the reader can open
 * and says which emptiness this is.
 *
 * ~~Two states.~~ → superseded (§250): the disambiguating read can itself fail, and a boolean
 * derived from `projects.data?.length === 0` answers **false** when it does — silently choosing
 * "No case matches", which is the precise falsehood the design exists to prevent. There is a third
 * answer, "cannot say", and it is the honest one whenever `useProjects` has not succeeded.
 *
 * <h2>What it does not do</h2>
 *
 * **No request until the search is answerable.** The server 400s a search naming neither `q`,
 * `ownerId` nor `tag`, and 400s a one-character `q`; `isSearchable` is the same predicate the
 * hook's `enabled` uses, so an empty box asks for nothing rather than rendering a control and then
 * being refused (`ui-rules` §4).
 *
 * **No client-side paging.** The pager is driven by the server's `total`, which §249 exists to
 * make trustworthy — it is computed over the same narrowed set as the page. A client that
 * re-derived the count would discard the one number the area was built for.
 *
 * **`Table`, not `DataGrid`** — §241 measured why: DataGrid does not render in jsdom, so a
 * component test of this screen is only possible on a `Table`. That table is now `kit/Table`, and
 * the pager `TablePagination` used to draw is `Pager` below; both are ports of
 * `renders/01-index-tables.html` frame B, whose one distinguishing mark is that Project is a
 * column here and nowhere else.
 */
export function CaseSearchPage() {
  const [params, setParams] = useSearchParams();

  const q = params.get('q') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);
  const size = Number(params.get('size') ?? '') || DEFAULT_SIZE;

  /*
    The URL stays the single source of truth for the box — a search must be linkable, and the page
    reset below has to see every change — but the REQUEST is debounced. Without this, typing
    "payment gateway" issues fourteen requests against the only company-wide, multi-project read in
    the app, thirteen of whose answers are discarded on arrival. `replace: true` already keeps the
    keystrokes out of history; this keeps them off the network.
  */
  const debouncedQ = useDebouncedValue(q, SEARCH_DEBOUNCE_MS);

  const lifecycleStatus = params.getAll('lifecycleStatus');
  const automationStatus = params.getAll('automationStatus');
  const priority = params.getAll('priority');

  const filters = {
    q: debouncedQ.trim() || undefined,
    lifecycleStatus: lifecycleStatus.length > 0 ? lifecycleStatus : undefined,
    automationStatus: automationStatus.length > 0 ? automationStatus : undefined,
    priority: priority.length > 0 ? priority : undefined,
    page,
    size,
  };
  const results = useCaseSearch(filters);
  useAnnouncedResults(
    results,
    `${formatCount(results.data?.total ?? 0)} matching ${(results.data?.total ?? 0) === 1 ? 'case' : 'cases'}`,
  );

  /*
    Only to tell the empty states apart — never to filter, and never to narrow the search. The
    server's own gate decides what is visible; this is the same question asked for a sentence.
  */
  const projects = useProjects();

  /*
    THREE answers, not two. `projects.data?.length === 0` is also false while the query is loading
    and false when it FAILED, and either of those rendering "No case matches" is the exact
    confident falsehood this screen exists to avoid — a member holding no grants told the product
    is empty. Undefined means the question has no answer yet, and the empty state says so rather
    than picking the friendlier of two lies.
  */
  const readerHasNoProjects = projects.isSuccess ? projects.data.length === 0 : undefined;

  /*
    Filter parity (`ui-rules` §2): the three enum columns this table renders are the three the
    endpoint filters on, so a reader who can SEE a status can narrow by it. Owner and tag are
    declared by the contract and deliberately not offered — both need a picker over a population
    that is itself cross-project (§6u owner resolution across N projects, and a tag vocabulary that
    belongs to each project separately), which is the reason this screen has no owner column
    either. `isSearchable`'s `ownerId`/`tag` arms therefore stay unreachable from any control here,
    and that is recorded rather than half-built.
  */
  const setMultiParam = (key: string, values: string[]) => {
    const next = new URLSearchParams(params);
    next.delete(key);
    for (const value of values) next.append(key, value);
    next.delete('page');
    setParams(next, { replace: true });
  };

  const setParam = (key: string, value: string | undefined) => {
    const next = new URLSearchParams(params);
    if (value === undefined || value === '') next.delete(key);
    else next.set(key, value);
    // Any change to the query resets to the first page: page 3 of the previous search is not a
    // page of this one, and keeping it is how a search lands on an empty page that looks broken.
    if (key !== 'page') next.delete('page');
    setParams(next, { replace: true });
  };

  return (
    <div>
      <PageHeader
        testId={`${TestId}.Header`}
        title="Search cases"
        documentTitle="Search cases"
        subtitle="Across every project you can open."
      />

      <div>
        <div className="relative mb-3 max-w-[760px]">
          <Search aria-hidden className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-ink-4" />
          <Input
            value={q}
            onChange={(event) => setParam('q', event.target.value)}
            placeholder="Title, description, or a case key such as TC-412"
            className="min-h-10 pl-9 text-[14px]"
            data-testid={`${TestId}.Query`}
            aria-label="Search cases"
          />
        </div>

        {/*
          One row of chips under the box — design language rule 2, and rule 3's *a quiet control is
          still a control*: every one is a real button with a name and a focus ring, and an applied
          filter carries its own remove, so a table filtered to nothing always names what emptied it.
        */}
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <EnumFilter
            testId={`${TestId}.Lifecycle`}
            label="Status"
            labels={lifecycleLabels}
            selected={lifecycleStatus}
            onChange={(next) => setMultiParam('lifecycleStatus', next)}
          />
          <EnumFilter
            testId={`${TestId}.Automation`}
            label="Automation"
            labels={automationLabels}
            selected={automationStatus}
            onChange={(next) => setMultiParam('automationStatus', next)}
          />
          <EnumFilter
            testId={`${TestId}.Priority`}
            label="Priority"
            labels={priorityLabels}
            selected={priority}
            onChange={(next) => setMultiParam('priority', next)}
          />
        </div>

        {!isSearchable(filters) ? (
          /*
            Not an EmptyState: nothing was searched for, so nothing is empty. Saying "no results"
            here would report a result for a question nobody asked.
          */
          <p className="text-[13px] text-ink-4" data-testid={`${TestId}.Prompt`}>
            {q.trim().length === 1
              ? 'Type at least two characters — a single letter matches most of the repository.'
              : 'Type something to search for.'}
          </p>
        ) : (
          <DataRegion
            testId={TestId}
            state={results}
            isEmpty={(data) => data.items.length === 0}
            empty={
              readerHasNoProjects === true
                ? {
                    kind: 'by-design',
                    title: 'You cannot open any projects yet',
                    body:
                      'A search only covers projects you have been granted access to, and you '
                      + 'have none. Ask an administrator for access to a project.',
                    testId: `${TestId}.NoProjects`,
                  }
                : readerHasNoProjects === false
                  ? {
                      kind: 'no-filter-match',
                      title: 'No case matches',
                      /*
                        §7 asks this state to count what its filters hid, and `EmptyState` writes
                        that sentence from `hidden`/`filters`. Neither is passed here and that is
                        not an omission: this search spans every project the reader can open, and
                        no endpoint serves a corpus total across them. A count invented from the
                        current page would be a number about the wrong set — worse than the honest
                        instruction below, which is what a cross-project text search can offer.
                      */
                      body: 'Try fewer words, or a case key such as TC-412.',
                      onClearFilters: () => setParam('q', undefined),
                      testId: `${TestId}.NoMatch`,
                    }
                  : {
                      kind: 'by-design',
                      title: 'Nothing came back for that search',
                      body:
                        'This search covers only the projects you can open, and that list could '
                        + 'not be loaded — so we cannot tell you whether nothing matched or you '
                        + 'have no project access yet. Reload the page, and ask an administrator '
                        + 'about project access if it keeps saying this.',
                      testId: `${TestId}.CannotSay`,
                    }
            }
          >
            {(data) => (
              <>
                <p className="mb-2 text-[12px] text-ink-4" data-testid={`${TestId}.Count`}>
                  {formatCount(data.total)} matching {data.total === 1 ? 'case' : 'cases'}
                </p>

                <Table>
                  <TableHead>
                    <TableRow>
                      <TableHeaderCell className="w-[110px]">Case</TableHeaderCell>
                      <TableHeaderCell>Title</TableHeaderCell>
                      <TableHeaderCell className="w-[210px]">Project</TableHeaderCell>
                      <TableHeaderCell className="w-[110px]">Status</TableHeaderCell>
                      <TableHeaderCell className="w-[130px]">Automation</TableHeaderCell>
                      <TableHeaderCell className="w-[90px]">Priority</TableHeaderCell>
                      <TableHeaderCell className="w-[150px]">Updated</TableHeaderCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {data.items.map((hit) => (
                      <TableRow key={hit.id} data-testid={`${TestId}.Row`}>
                        <TableCell>
                          <Identifier>{hit.key}</Identifier>
                        </TableCell>
                        <TableCell variant="title">
                          <RouterLink
                            to={`/projects/${hit.projectId}/cases/${hit.id}`}
                            className="text-steel underline-offset-4 hover:underline"
                            data-testid={`${TestId}.Row.Link`}
                          >
                            {hit.title}
                          </RouterLink>
                        </TableCell>
                        {/*
                          The project is part of every row rather than a column somebody can
                          hide: the reader does not know which project a hit is from, and that
                          is the premise of the screen.
                        */}
                        <TableCell>
                          <span className="flex min-w-0 items-center gap-1.5">
                            <Identifier variant="muted">{hit.projectKey}</Identifier>
                            <span className="truncate" title={hit.projectName}>
                              {hit.projectName}
                            </span>
                          </span>
                        </TableCell>
                        <TableCell>
                          <Badge tone="plain">{lifecycleLabels[hit.lifecycleStatus]}</Badge>
                        </TableCell>
                        <TableCell>{automationLabels[hit.automationStatus]}</TableCell>
                        <TableCell>{priorityLabels[hit.priority]}</TableCell>
                        <TableCell className="font-mono text-[12px] tabular-nums text-ink-4">
                          {formatDateTime(hit.updatedAt)}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>

                {/* Driven by the server's total — see the header. */}
                <Pager
                  total={data.total}
                  page={data.page}
                  size={data.size}
                  onPage={(next) => setParam('page', String(next))}
                  onSize={(next) => setParam('size', String(next))}
                />
              </>
            )}
          </DataRegion>
        )}
      </div>
    </div>
  );
}

/**
 * The pager `TablePagination` used to supply.
 *
 * It states the range and the total in words rather than drawing a page-number strip: the total is
 * the server's, computed over the same narrowed set as the page (§249), and a strip of numbers
 * over an unknown number of pages is the one thing this screen must not invent.
 */
function Pager({
  total,
  page,
  size,
  onPage,
  onSize,
}: {
  total: number;
  page: number;
  size: number;
  onPage: (next: number) => void;
  onSize: (next: number) => void;
}) {
  const first = total === 0 ? 0 : page * size + 1;
  const last = Math.min(total, (page + 1) * size);
  const lastPage = Math.max(0, Math.ceil(total / size) - 1);

  return (
    <div
      className="flex flex-wrap items-center justify-end gap-3 border-t border-line px-3 py-2 text-[12px] text-ink-4"
      data-testid={`${TestId}.Pager`}
    >
      <label className="flex items-center gap-1.5">
        Rows
        <select
          value={size}
          onChange={(event) => onSize(Number(event.target.value))}
          className="rounded-[5px] border border-line bg-field px-1.5 py-0.5 font-mono text-[12px] text-ink-2"
          data-testid={`${TestId}.Pager.Size`}
        >
          {[25, 50, 100].map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      </label>
      <span className="font-mono tabular-nums">
        {first}–{last} of {formatCount(total)}
      </span>
      <Button
        size="sm"
        onClick={() => onPage(page - 1)}
        disabled={page <= 0}
        data-testid={`${TestId}.Pager.Prev`}
      >
        Previous
      </Button>
      <Button
        size="sm"
        onClick={() => onPage(page + 1)}
        disabled={page >= lastPage}
        data-testid={`${TestId}.Pager.Next`}
      >
        Next
      </Button>
    </div>
  );
}

/**
 * One multi-select over an enum's label map, so the three read identically and a new member of any
 * of them appears here the moment `labels.ts` names it.
 *
 * ~~A `TextField select` with `multiple`.~~ → **superseded** by `renders/01-index-tables.html`: an
 * applied filter is a *chip* the reader can see and remove, not a value folded inside a closed
 * select. The chip is two sibling buttons — the menu trigger and the remove — which is exactly
 * `kit/Chip`'s removable shape; it is rebuilt here rather than reused because the first of the two
 * has to be the Radix trigger itself, and a Radix trigger cannot be nested inside another button.
 */
function EnumFilter({
  testId,
  label,
  labels,
  selected,
  onChange,
}: {
  testId: string;
  label: string;
  labels: Record<string, string>;
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  const applied = selected.length > 0;
  const summary = applied
    ? selected.length === 1
      ? `${label} \u00b7 ${labels[selected[0]!] ?? selected[0]}`
      : `${label} \u00b7 ${selected.length} selected`
    : label;

  const toggle = (key: string) =>
    onChange(selected.includes(key) ? selected.filter((value) => value !== key) : [...selected, key]);

  return (
    <span
      style={
        applied
          ? {
              borderColor: 'color-mix(in oklab, var(--color-steel) 22%, transparent)',
              backgroundColor: 'color-mix(in oklab, var(--color-steel) 12%, transparent)',
              color: 'var(--color-steel)',
            }
          : undefined
      }
      className="inline-flex items-center gap-1.5 rounded-[5px] border border-line px-2.5 py-[3px] font-sans text-xs text-ink-2"
    >
      <DropdownMenu>
        <DropdownMenuTrigger
          className="inline-flex items-center text-inherit focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]"
          data-testid={testId}
        >
          {summary}
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          {Object.entries(labels).map(([key, text]) => (
            <DropdownMenuCheckboxItem
              key={key}
              checked={selected.includes(key)}
              onCheckedChange={() => toggle(key)}
              onSelect={(event) => event.preventDefault()}
              data-testid={`${testId}.${key}`}
            >
              {text}
            </DropdownMenuCheckboxItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
      {applied && (
        <button
          type="button"
          onClick={() => onChange([])}
          aria-label={`Remove ${label} filter`}
          className="text-ink-4 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]"
          data-testid={`${testId}.Remove`}
        >
          <X aria-hidden className="size-3" />
        </button>
      )}
    </span>
  );
}
