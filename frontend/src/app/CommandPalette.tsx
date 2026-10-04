import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { Compass, FileText, FolderKanban, CornerDownLeft, Search, SquareCheckBig, Ticket } from 'lucide-react';

import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  Dialog,
  DialogContent,
  DialogTitle,
} from '../components/ui';
import { useCaseSearch } from '../api/queries/cases';
import { useDocPageSearch } from '../api/queries/documentation';
import { useIssueSearch } from '../api/queries/issues';
import { useProjects } from '../api/queries/projects';
import { useCan, useCanInCompany } from '../auth/useCan';
import { useDebouncedValue } from '../lib/useDebouncedValue';
import { PLACES, needsProject, type Place } from './places';

/**
 * The ⌘K palette (021 §7) — one control over cases, places, projects and a key jump.
 *
 * ~~A cases-only search field in the header.~~ → **superseded (021 §7, R3).** The field answered one
 * question well and was the only global control in the shell, so every other "where is…" had to be
 * solved by hunting the rail — which got harder in T6, not easier: five screens became tabs and
 * eleven became settings cards, and a tab is invisible until you are already on the screen it
 * extends. The palette is what pays for that move.
 *
 * **The palette is not the page.** `/search` and `CaseSearchPage` are untouched: the palette gets
 * you somewhere, the page is where you work through results with filters, paging and a URL you can
 * paste. `Enter` on a query that matches nothing here lands on that page with the query intact.
 *
 * ~~## Phase 1, and it says so — four groups, no contract change; the placeholder reads
 * `Search cases, or jump to…`, which is honest, because issues and doc pages need
 * `GET /issues/search` in `openapi.yaml` first (D-j).~~ → **superseded (021 §7 Phase 2, R3, T8).**
 * T4 served that endpoint, so the placeholder is now the mockup's `Search issues, cases, docs` and
 * the fan-out is three server reads plus two client-side indexes.
 *
 * ## The three searches, and why one of them is narrower than the others
 *
 * `GET /issues/search` and `GET /cases/search` are **cross-project by construction** — neither
 * takes a project and both answer over everything the caller may read. `GET
 * /projects/{id}/doc-pages/search` is **per project**, and this palette does not fan it out over
 * the reader's project list: that would be one request per project on every keystroke, and the
 * answer would then have to be merged and re-ranked by a client that cannot see the scores. So the
 * docs group is the **active project's** and says so in its own heading; with no project in the
 * frame the group is absent and the empty-state line says where docs are searched from. Widening
 * it is a contract question (one cross-project doc search), not a loop.
 *
 * The three run **in parallel and independently** — three `useQuery`s, not a `Promise.all`. Each
 * group is optional to the others: a docs search that fails costs its group and leaves the issue
 * and case rows on screen, which is the composition rule stated as three hooks.
 *
 * ## Gating
 *
 * Places are filtered by **the same gate the rail applies, asked at the same scope** — `useCan` for
 * a `project` gate, `useCanInCompany` for a `company` one. A palette that offered `Billing` to
 * somebody the rail hides it from would undo nine recorded decisions in one keystroke, and it would
 * do it in the one surface that is searched by name.
 *
 * ## Keyboard and a11y (§11)
 *
 * `⌘K` / `Ctrl-K` anywhere, and `/` **only when the caret is not in an input, a textarea or a
 * contenteditable** — otherwise the shortcut eats the character in every text field in the app,
 * which is the way this feature is most often shipped broken. `Escape` closes and focus returns to
 * the trigger, both from Radix's `Dialog`; the input is `role="combobox"` over a `role="listbox"`
 * with `aria-activedescendant`, all from `cmdk`, which is why neither is hand-rolled here.
 */
export function CommandPalette({ projectId }: { projectId: string | undefined }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const triggerRef = useRef<HTMLButtonElement>(null);
  const navigate = useNavigate();

  useGlobalShortcut(setOpen);

  const close = () => {
    setOpen(false);
    setQuery('');
  };

  const go = (to: string) => {
    close();
    navigate(to);
  };

  return (
    <>
      {/*
        The `role="search"` landmark, which moved here from the field this replaced. A landmark is
        how a screen-reader user finds the search without walking the header, and losing it was the
        one thing the field did that a button does not do for free.

        A `<form>` rather than a bare button so `Enter` on the focused control opens the palette the
        same way a click does, and so the landmark sits on the element that would carry it if the
        control were still an input. It submits nowhere: everything happens in the dialog.
      */}
      <form
        role="search"
        className="flex shrink-0 items-center"
        onSubmit={(event) => {
          event.preventDefault();
          setOpen(true);
        }}
      >
        <button
          ref={triggerRef}
          type="submit"
          aria-label={TRIGGER_LABEL}
          data-testid="TopBar.Search"
          className={
            'flex h-[34px] items-center gap-2 rounded-lg border border-line bg-field text-[12.5px] ' +
            'text-ink-4 hover:text-ink-3 max-md:size-8 max-md:justify-center max-md:border-0 ' +
            'max-md:bg-transparent md:w-[280px] md:px-2.5 ' +
            'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[color:var(--color-given)]'
          }
        >
          <Search aria-hidden className="size-4 shrink-0" />
          <span className="min-w-0 truncate max-md:hidden">{TRIGGER_LABEL}</span>
          {/*
            The hint is decoration for a mouse user and noise for a screen reader, which already
            heard the accessible name. `⌘K` is drawn on every platform deliberately — the control
            accepts both, and a hint that changed with the user agent is one more thing to get
            wrong in a screenshot.
          */}
          <kbd
            aria-hidden
            className="ml-auto hidden shrink-0 rounded-sm border border-line px-1 font-mono text-[10px] md:inline"
          >
            ⌘K
          </kbd>
        </button>
      </form>

      <Dialog
        open={open}
        onOpenChange={(next) => {
          if (next) setOpen(true);
          else close();
        }}
      >
        <DialogContent
          className="top-[12%] max-w-[560px] translate-y-0 p-0"
          // Radix traps focus while open and closes on Escape — two of the three things §11 asks
          // for, and the reason this is its `Dialog` rather than a positioned `<div>` with a
          // click-away handler.
          data-testid="Palette"
          /*
            The third is **returning focus to the trigger**, and it has to be done by hand here.
            Radix restores focus to its own `DialogTrigger`, and this dialog has none: the palette
            opens from two keyboard shortcuts as well as from the button, so `open` is state rather
            than a trigger's business. Without this the palette closes and focus is left on the
            document body — a keyboard user is returned to the top of the page every time they
            press Escape, which is the failure a focus trap exists to prevent, one step later.
          */
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            triggerRef.current?.focus();
          }}
        >
          {/* Radix requires a title on every dialog; the palette's is its own placeholder. */}
          <DialogTitle className="sr-only">{PLACEHOLDER}</DialogTitle>
          <PaletteBody projectId={projectId} query={query} onQuery={setQuery} onGo={go} />
        </DialogContent>
      </Dialog>
    </>
  );
}

/**
 * ~~`Search cases, or jump to…`~~ → **`Search issues, cases, docs`** (021 §7 Phase 2, render
 * `06-palette.html`). The old text was accurate for a palette that could only find cases; keeping
 * it now would understate the control by two of its three server reads, and the placeholder is the
 * only place a reader is told what the field covers before they type into it.
 */
const PLACEHOLDER = 'Search issues, cases, docs';
const TRIGGER_LABEL = 'Search or jump to…';

/**
 * A case key is `TC-118`; an issue key is `ACME-118` — the project's key, then the sequence.
 *
 * Two patterns rather than one with an exception, and the case one is tested first: `TC` is a legal
 * project key (2–10 uppercase alphanumerics), so a single "key-shaped" pattern would send `TC-118`
 * to `/issues/TC-118` for any company that happens to have a project called TC. Deciding by shape
 * where the shapes genuinely overlap is how a jump silently starts landing on a 404.
 */
const CASE_KEY = /^TC-\d+$/;
const ISSUE_KEY = /^[A-Z][A-Z0-9]{1,9}-\d+$/;

/**
 * Split out so the whole body **unmounts on close**, which is what resets `cmdk`'s selection and
 * cancels the case query. Keeping it mounted behind `open` left the previous search's highlighted
 * row selected when the palette was reopened, so the first `Enter` went somewhere the reader had
 * not looked at.
 */
function PaletteBody({
  projectId,
  query,
  onQuery,
  onGo,
}: {
  projectId: string | undefined;
  query: string;
  onQuery: (value: string) => void;
  onGo: (to: string) => void;
}) {
  const trimmed = query.trim();
  // 200ms: the case search is `TIMEOUTS.typeahead`, and firing it per keystroke would put a
  // request behind every letter of a word the reader has not finished typing.
  const debounced = useDebouncedValue(trimmed, 200);

  const projects = useProjects();
  const inProject = useCan(projectId);
  const inCompany = useCanInCompany();

  const cases = useCaseSearch({ q: debounced, size: 6 });
  /*
    Six, like the cases: the palette is a jump, not a results page, and a group long enough to
    scroll pushes `Places` and the floor row below the fold — which are the two rows a reader who
    typed a word rather than a key most often wants. The server caps at 20 when `limit` is absent,
    so asking for six is the client saying what it will draw rather than trimming an answer it paid
    for.
  */
  const issues = useIssueSearch(debounced, 6);
  /*
    The docs group is the ACTIVE project's — see the header. `useDocPageSearch` takes a project and
    gates itself on a non-empty query; `''` is the "no project in the frame" spelling and disables
    it, which is why there is no second gate here.
  */
  const docs = useDocPageSearch(projectId ?? '', projectId === undefined ? '' : debounced);

  const places = useMemo(() => {
    const allowed = (place: Place) => {
      if (place.gate === undefined) return true;
      return place.gate.scope === 'company'
        ? inCompany.can(place.gate.key)
        : inProject.can(place.gate.key);
    };
    return PLACES.filter(
      (place) => allowed(place) && (projectId !== undefined || !needsProject(place)),
    ).filter((place) => matches(place.label, trimmed));
  }, [inCompany, inProject, projectId, trimmed]);

  /*
    The project's own name for the docs heading, out of the list this palette is already holding —
    not a `useProject(projectId)` read, which would put a request behind opening the palette on
    every screen for a name the switcher already fetched.
  */
  const activeProjectName = (projects.data ?? []).find((project) => project.id === projectId)?.name;

  const matchingProjects = useMemo(
    () =>
      (projects.data ?? []).filter(
        (project) => matches(project.name, trimmed) || matches(project.key, trimmed),
      ),
    [projects.data, trimmed],
  );

  const upper = trimmed.toUpperCase();
  const caseKey = CASE_KEY.test(upper) ? upper : undefined;
  const issueKey = caseKey === undefined && ISSUE_KEY.test(upper) ? upper : undefined;
  /*
    A case key names a case, but the ROUTE to a case needs a project id and a UUID — neither of
    which is in the key. The search answers both, and it is already running, so the jump resolves
    through its hit when one has arrived. Until it does the row still works: it falls back to the
    results page, which is where `Enter` on any unresolved query goes anyway.
  */
  const keyedCase = cases.data?.items.find((hit) => hit.key.toUpperCase() === caseKey);

  return (
    <Command
      // Filtering is ours: the case arm is answered by the server and the other three are
      // client-side over different fields, so `cmdk`'s own fuzzy pass would filter the server's
      // answer a second time, by a different rule, and drop hits it had already decided matched.
      shouldFilter={false}
      label={PLACEHOLDER}
    >
      <CommandInput
        value={query}
        onValueChange={onQuery}
        placeholder={PLACEHOLDER}
        data-testid="Palette.Input"
      />
      <CommandList>
        {trimmed.length > 0 && (
          <CommandEmpty>
            Nothing here matches <span className="text-ink-2">{trimmed}</span>.
          </CommandEmpty>
        )}

        {issueKey && (
          <CommandGroup heading="Jump to">
            <PaletteRow
              icon={<CornerDownLeft aria-hidden className="size-3.5" />}
              label={issueKey}
              where="issue"
              onSelect={() => onGo(`/issues/${issueKey}`)}
            />
          </CommandGroup>
        )}

        {caseKey && (
          <CommandGroup heading="Jump to">
            <PaletteRow
              icon={<CornerDownLeft aria-hidden className="size-3.5" />}
              label={caseKey}
              where={keyedCase ? keyedCase.projectKey : 'case'}
              onSelect={() =>
                onGo(
                  keyedCase
                    ? `/projects/${keyedCase.projectId}/cases/${keyedCase.id}`
                    : searchHref(caseKey),
                )
              }
            />
          </CommandGroup>
        )}

        {(issues.data?.length ?? 0) > 0 && (
          <CommandGroup heading="Issues">
            {issues.data?.map((hit) => (
              <PaletteRow
                key={hit.key}
                icon={<Ticket aria-hidden className="size-3.5" />}
                identifier={hit.key}
                label={hit.title}
                /*
                  The PROJECT's name, not the status: the search crosses projects and two of them
                  may hold a story with the same title, which is the whole reason `projectName` is
                  on the hit at all (D-j). The status is on the issue's own page, one keystroke
                  away, and a second fact in this slot would make the column unscannable.
                */
                where={hit.projectName}
                onSelect={() => onGo(`/issues/${encodeURIComponent(hit.key)}`)}
              />
            ))}
          </CommandGroup>
        )}

        {(cases.data?.items.length ?? 0) > 0 && (
          <CommandGroup heading="Cases">
            {cases.data?.items.map((hit) => (
              <PaletteRow
                key={hit.id}
                icon={<SquareCheckBig aria-hidden className="size-3.5" />}
                identifier={hit.key}
                label={hit.title}
                where={hit.projectKey}
                onSelect={() => onGo(`/projects/${hit.projectId}/cases/${hit.id}`)}
              />
            ))}
          </CommandGroup>
        )}

        {/*
          **The docs group names its scope in its own heading**, because it is the one group whose
          scope is narrower than the field above it. `Docs` alone over a per-project answer would
          read as "there is no such page anywhere", which is a claim this request cannot make.
        */}
        {(docs.data?.length ?? 0) > 0 && (
          <CommandGroup heading={`Docs in ${activeProjectName ?? 'this project'}`}>
            {docs.data?.map((hit) => (
              <PaletteRow
                key={hit.id}
                icon={<FileText aria-hidden className="size-3.5" />}
                identifier={hit.key}
                label={hit.title}
                where="doc"
                onSelect={() => onGo(`/projects/${projectId ?? ''}/docs/${hit.id}`)}
              />
            ))}
          </CommandGroup>
        )}

        {/*
          Said once, and only when it changes the answer: a reader outside a project who typed
          something is told where doc pages ARE searched from, rather than left to conclude the
          product has none. Not an empty group — an empty group with a heading claims a search ran.
        */}
        {trimmed.length > 0 && projectId === undefined && (
          <CommandGroup heading="Docs">
            <p
              data-testid="Palette.NoProjectDocs"
              className="m-0 px-2 py-1.5 text-[11.5px] leading-[1.45] text-ink-4"
            >
              Doc pages are searched inside one project. Open a project to search its documents.
            </p>
          </CommandGroup>
        )}

        {places.length > 0 && (
          <CommandGroup heading="Places">
            {places.map((place) => (
              <PaletteRow
                key={place.to('_')}
                icon={<Compass aria-hidden className="size-3.5" />}
                label={place.label}
                where={place.where}
                onSelect={() => onGo(place.to(projectId ?? ''))}
              />
            ))}
          </CommandGroup>
        )}

        {matchingProjects.length > 0 && (
          <CommandGroup heading="Projects">
            {matchingProjects.map((project) => (
              <PaletteRow
                key={project.id}
                icon={<FolderKanban aria-hidden className="size-3.5" />}
                identifier={project.key}
                label={project.name}
                where={project.id === projectId ? 'current' : 'switch'}
                onSelect={() => onGo(`/projects/${project.id}`)}
              />
            ))}
          </CommandGroup>
        )}

        {/*
          The floor of the list, and the reason `Enter` is never a dead key: whatever else matched,
          the query can always be taken to the page that owns searching. It is last so it is only
          selected when nothing above it was — which is also what makes it the answer for a query
          the server has not finished answering yet.
        */}
        {trimmed.length > 0 && (
          <CommandGroup heading="Search">
            <PaletteRow
              icon={<Search aria-hidden className="size-3.5" />}
              label={`Search all test cases for “${trimmed}”`}
              where="/search"
              onSelect={() => onGo(searchHref(trimmed))}
            />
          </CommandGroup>
        )}
      </CommandList>

      <div className="flex items-center gap-3 border-t border-line px-3 py-2 text-[11px] text-ink-4">
        <span>↑↓ move</span>
        <span>↵ open</span>
        <span>Esc close</span>
        <span className="ml-auto">Issues · cases · docs · places · projects</span>
      </div>
    </Command>
  );
}

/**
 * `URLSearchParams` rather than `encodeURIComponent`, so the query this writes is byte-identical to
 * the one `CaseSearchPage` writes back through `useSearchParams` — two spellings of one search
 * would show as two history entries and two cache keys. Carried over verbatim from the field this
 * replaced, where it was already the recorded reason.
 */
function searchHref(query: string): string {
  return `/search?${new URLSearchParams({ q: query }).toString()}`;
}

/** Substring, case-insensitive — an empty query matches everything, which is the palette at rest. */
function matches(haystack: string, needle: string): boolean {
  return needle.length === 0 || haystack.toLowerCase().includes(needle.toLowerCase());
}

function PaletteRow({
  icon,
  identifier,
  label,
  where,
  onSelect,
}: {
  icon: ReactNode;
  identifier?: string;
  label: string;
  where: string;
  onSelect: () => void;
}) {
  return (
    <CommandItem
      // `cmdk` dedupes and orders by this, and it must be unique across groups: two rows keyed
      // `Suites` would collapse into one selectable row in two places.
      value={`${where}:${identifier ?? ''}:${label}`}
      onSelect={onSelect}
      data-testid="Palette.Row"
    >
      <span className="shrink-0 text-ink-4">{icon}</span>
      {identifier && <span className="shrink-0 font-mono text-[12px] text-ink-4">{identifier}</span>}
      <span className="min-w-0 truncate">{label}</span>
      <span className="ml-auto shrink-0 text-[11px] text-ink-4">{where}</span>
    </CommandItem>
  );
}

/**
 * `⌘K` / `Ctrl-K` anywhere, and `/` only outside a text field.
 *
 * The `/` guard is the whole reason this is a hook rather than three lines inline. Without it the
 * shortcut is a defect in every input in the app — you type a path, a regex or a date and the
 * palette opens over what you were writing — and it is invisible in every test that does not
 * happen to type a slash. `isContentEditable` covers the case editor's document surface, which is
 * not an `<input>` at all.
 */
function useGlobalShortcut(open: (next: true) => void) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        open(true);
        return;
      }
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) return;
      if (isTyping(event.target)) return;
      event.preventDefault();
      open(true);
    };

    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open]);
}

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  if (target.isContentEditable) return true;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';
}
