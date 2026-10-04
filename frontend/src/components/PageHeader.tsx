import { useEffect, type ReactNode } from 'react';
import { useAnnounce } from '../a11y/useAnnounce';
import { cn } from '../lib/cn';
import { PageStrip } from './PageStrip';

/**
 * One page-header treatment, and a title on every route.
 *
 * The title is emitted with a plain `<title>` element: React 19 hoists it to `<head>`, so
 * this SPA needs no `react-helmet`. Browser tabs full of "TCMS" are unusable, and a title
 * per route is the cheapest fix in the app.
 *
 * The `<PageHeader>` name, the nav label, the breadcrumb segment, and the page heading all
 * carry the *same* words for a destination — that consistency is the signposting people
 * use to learn their way around.
 *
 * ## Not every screen has one any more
 *
 * T10 recorded that the render says an index screen should carry *no page heading repeating the
 * nav label*, and T12/T13/T14 ported their families onto the render's own `.top` strip, which
 * raises its own `<title>` and its own arrival announcement. So this component is now the
 * **seven-screen** treatment T11 kept — a record with a real title row over a table — rather than
 * the thing every route mounts. The `<title>` and the announcement stayed here because the
 * reasoning below about `lazy()` is unchanged for the screens that still use it.
 *
 * ## ~~`MuiBreadcrumbs` → a plain `<nav><ol>`~~ → the trail left this component entirely
 *
 * ~~MUI's component inserted its own separators and its own list semantics. Ported in T16 to the
 * markup underneath it: an ordered list is what a breadcrumb trail is, the separator is
 * `aria-hidden` so it is not read out between every segment, and the last crumb carries
 * `aria-current="page"` — which the MUI version never did.~~ → **superseded (021 R2).** The markup
 * was right and its address was not: forty-six screens each composed their own trail, which is
 * forty-six places a rename has to reach, and the audit found two of them it had not. The trail is
 * `app/ShellBreadcrumbs.tsx` now, fed by `app/routeMeta.ts`, drawn in the header once for every
 * route — and it survives a page's failed read, which a trail rendered inside the page could not.
 *
 * **`crumbs` is gone from this component and from every caller.** A page that wants a different
 * trail changes `routeMeta.ts`, where the whole trail is visible beside its siblings.
 */

export function PageHeader({
  title,
  /** Browser-tab title. Defaults to `title`; pass a shorter form when the heading is long. */
  documentTitle,
  subtitle,
  actions,
  meta,
  announcement,
  views,
  lead,
  testId,
}: {
  title: ReactNode;
  documentTitle?: string;
  subtitle?: ReactNode;
  /** Primary and secondary actions for this page, right-aligned. */
  actions?: ReactNode;
  /** Identifiers and counts that belong with the heading rather than in the body. */
  meta?: ReactNode;
  /**
   * What to announce on arrival, instead of "<title> page loaded".
   *
   * For a screen where the generic sentence is the *less* useful of two truths. The execution
   * screen is the case: arriving there by `j`/`k` or from the rail, what a screen-reader user
   * needs is which case and how far through the run it is — and §248 announced exactly that from
   * its move handler, where it was overwritten a moment later by this component's own line, every
   * time. One announcement per arrival, raised from the one place that knows the page has
   * rendered, is the shape that cannot race with itself.
   */
  announcement?: string;
  /** A TabStrip, a Segmented or a ui/tabs list, drawn in the strip. */
  views?: ReactNode;
  /** Replaces the visible title; the title stays as the page's visually hidden h1. */
  lead?: ReactNode;
  testId: string;
}) {
  const tabTitle = documentTitle ?? (typeof title === 'string' ? title : undefined);

  /**
   * The route-change announcement is raised from here rather than from the routing hook.
   *
   * The reason is `lazy()`: when the pathname changes, the new page's chunk may still be
   * downloading, so nothing at the router level yet knows what the page is called. This header
   * mounts once the page has actually rendered, and it holds the title — so this is the first
   * moment an announcement can be truthful.
   */
  const announce = useAnnounce();
  useEffect(() => {
    const sentence = announcement ?? (tabTitle ? `${tabTitle} page loaded` : undefined);
    if (sentence) announce(sentence);
  }, [announcement, tabTitle, announce]);

  return (
    <>
      {tabTitle && <title>{`${tabTitle} · TCMS`}</title>}
      <PageStrip
        title={title}
        lead={lead}
        subtitle={subtitle}
        meta={meta}
        views={views}
        actions={actions}
        testId={testId}
      />
    </>
  );
}

/**
 * One elevated surface per region, with hairline dividers *inside* it — the revision away
 * from "white cards with shadows on grey", which is the safe SaaS default this design
 * deliberately walked away from.
 *
 * The render's `.card` (`tcms.css:251`) is the same object at kit values: `--panel` ground, a
 * hairline border, `--r` corners. `overflow-hidden` is what the rounded corners need and is why
 * `ScrollX` exists — a table wider than this box is clipped at the border unless it is wrapped.
 *
 * `plain` keeps the heading and drops the surface. Design language rule 1 forbids a card around the
 * subject of the page, and the renders audit (2.4) found that rule applied to four of six blocks on
 * one screen: the case detail's Preconditions, Steps, Postconditions and Traceability are bare
 * while Attachments, Issues, Reliability and Version history sat in cards — two idioms for one kind
 * of thing, on one column. A caller inside a document surface or a rail passes `plain`; a caller
 * where the panel genuinely is one region among several on a page does not.
 */
export function Panel({
  title,
  actions,
  children,
  testId,
  dense,
  plain,
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  testId?: string;
  dense?: boolean;
  /** Heading kept, surface dropped — for a block inside a document column or a rail. */
  plain?: boolean;
}) {
  const bare = plain === true;
  return (
    <section
      data-testid={testId}
      className={bare ? undefined : 'overflow-hidden rounded-xl border border-line bg-panel'}
    >
      {(title || actions) && (
        <div
          className={cn(
            'flex items-center justify-between gap-2',
            bare ? 'mb-2' : 'border-b border-line px-4 py-2.5',
          )}
        >
          {typeof title === 'string' ? (
            <h2 className="m-0 text-[13px] font-semibold tracking-[-.01em] text-ink">{title}</h2>
          ) : (
            title
          )}
          {actions && <div className="flex items-center gap-1">{actions}</div>}
        </div>
      )}
      <div className={bare ? '' : dense ? 'p-3' : 'p-4'}>{children}</div>
    </section>
  );
}
