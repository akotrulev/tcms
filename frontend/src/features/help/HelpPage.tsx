import { useEffect, useRef } from 'react';
import { Link as RouterLink, Navigate, useParams } from 'react-router-dom';
import { useAnnounce } from '../../a11y/useAnnounce';
import { DocumentSurface } from '../../components/kit';
import { PageStrip } from '../../components/PageStrip';
import { cn } from '../../lib/cn';
import { helpSections, findHelpSection, type HelpSection } from './sections';

/**
 * The user guide — one screen, one section at a time, reachable by every signed-in reader.
 *
 * ## Why a section per URL rather than one long page
 *
 * The obvious build is every section stacked on one route with anchor links. It was refused for a
 * reason this app has already paid for elsewhere: a single page carrying eleven sections is one
 * render of every table and every code block on it, including the role matrix's fifty-odd rows,
 * for a reader who came to find out how to install the CLI. A section per URL means the browser's
 * back button works between them, a link somebody pastes into a ticket lands on the thing they
 * meant, and the printed or scrolled length of any one screen stays something a person will read.
 *
 * `/help` redirects to the first section rather than rendering a landing page of its own. A
 * contents page whose only content is a list of links the rail already shows is a click the reader
 * pays for and learns nothing from.
 *
 * ## No permission gate, and that is the decision
 *
 * Every section describes capabilities some readers do not hold — the role matrix most of all.
 * Gating any of it would need a key, and a key invented for a help page would be seeded to nobody,
 * which is a refusal no administrator could grant their way out of. It also inverts the product's
 * own rule: this is the screen that exists to tell somebody what they are missing and what to ask
 * for, so it is the last screen that should hide it from them.
 *
 * ## It is the fourth document surface
 *
 * Design language §3: help renders on `DocumentSurface` at the prose measure, the same 70ch, the
 * same gutter and the same hairline rule as a case, a doc page and a proposal — *"written in the
 * product, on the product's own document surface, using the product's own chart components"*. That
 * is what keeps it from drifting away from the thing it describes, and it is why ~~`PageHeader` +
 * `Panel`~~ are gone from this file: a page header's `<h1>` above a document's own title is two
 * titles for one thing, and a panel around the only thing on screen is rule 1's exact prohibition.
 * The section title is the `<h2>` the document draws; the rail and the top strip carry the rest.
 */
export function HelpPage() {
  const { sectionId } = useParams<{ sectionId: string }>();

  if (!sectionId) return <Navigate to={`/help/${helpSections[0]!.id}`} replace />;

  const section = findHelpSection(sectionId);
  if (!section) return <Navigate to={`/help/${helpSections[0]!.id}`} replace />;

  return (
    <>
      <title>{`Help — ${section.title} · TCMS`}</title>
      <PageStrip title="User guide" testId="Help.Strip" />
      <div className="flex flex-col items-start gap-6 md:flex-row" data-testid="HelpPage">
        <Contents current={section.id} />
        <SectionBody section={section} />
      </div>
    </>
  );
}

/**
 * The contents list.
 *
 * `position: sticky` only from `md` up: on a phone it sits above the prose and scrolls away, which
 * is right — a sticky block eating a third of a small viewport is worse than scrolling back up for
 * it. It is a `<nav>` with a name so a screen-reader user can jump straight to it, and the current
 * section is marked with `aria-current` rather than colour alone.
 */
function Contents({ current }: { current: string }) {
  return (
    <nav
      aria-label="Help contents"
      data-testid="Help.Contents"
      className="w-full shrink-0 md:sticky md:top-28 md:w-[232px]"
    >
      <h2 className="mb-2 px-3 font-mono text-[11px] font-medium uppercase tracking-[.07em] text-ink-4">
        Contents
      </h2>
      <ul className="m-0 flex list-none flex-col gap-px p-0">
        {helpSections.map((section) => {
          const active = section.id === current;
          return (
            <li key={section.id}>
              <RouterLink
                to={`/help/${section.id}`}
                aria-current={active ? 'page' : undefined}
                data-testid={`Help.Contents.Link.${section.id}`}
                className={cn(
                  'block rounded-md px-3 py-1.5 text-[13px] no-underline',
                  active ? 'bg-wash-2 font-medium text-ink' : 'text-ink-3 hover:bg-wash-1 hover:text-ink-2',
                )}
              >
                {section.title}
              </RouterLink>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

/**
 * One section's prose.
 *
 * Focus moves to the heading on every section change. Without it, following a contents link
 * replaces the whole right-hand column while the keyboard stays parked in the list — so a
 * screen-reader user hears nothing and a keyboard user's next Tab continues down the contents
 * instead of into what they just asked for. `tabIndex={-1}` makes the heading focusable
 * programmatically without adding it to the tab order.
 *
 * The arrival announcement is raised here rather than by a shared header, because this is the
 * component that knows which section actually rendered — the route alone does not, since an
 * unknown id redirects.
 */
function SectionBody({ section }: { section: HelpSection }) {
  const heading = useRef<HTMLHeadingElement>(null);
  const first = useRef(true);
  const announce = useAnnounce();

  useEffect(() => {
    announce(`Help — ${section.title} page loaded`);
  }, [announce, section.title]);

  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    heading.current?.focus();
  }, [section.id]);

  return (
    <DocumentSurface measure="prose" className="min-w-0 flex-1" data-testid={`Help.Section.${section.id}`}>
      <h2
        ref={heading}
        tabIndex={-1}
        className="m-0 mb-1.5 max-w-[36ch] text-[24px] leading-[1.25] font-[620] tracking-[-.02em] text-ink outline-none"
      >
        {section.title}
      </h2>
      <p className="m-0 max-w-[62ch] text-[14.5px] leading-[1.6] text-ink-3">{section.summary}</p>
      <div className="my-[18px] h-px bg-line" />

      {section.body}

      <div className="mt-8 h-px bg-line" />
      <Pager current={section.id} />
    </DocumentSurface>
  );
}

/** Previous and next, so the guide can be read straight through without going back to Contents. */
function Pager({ current }: { current: string }) {
  const index = helpSections.findIndex((section) => section.id === current);
  const previous = index > 0 ? helpSections[index - 1] : undefined;
  const next = index < helpSections.length - 1 ? helpSections[index + 1] : undefined;

  return (
    <div className="flex justify-between gap-4 pt-4 text-[13px]" data-testid="Help.Pager">
      {previous ? (
        <RouterLink to={`/help/${previous.id}`} className="text-steel no-underline hover:underline" data-testid="Help.Pager.Previous">
          ← {previous.title}
        </RouterLink>
      ) : (
        <span />
      )}
      {next && (
        <RouterLink to={`/help/${next.id}`} className="text-steel no-underline hover:underline" data-testid="Help.Pager.Next">
          {next.title} →
        </RouterLink>
      )}
    </div>
  );
}
