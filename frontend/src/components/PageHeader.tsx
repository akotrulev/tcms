import { Link as RouterLink } from 'react-router-dom';
import { Fragment, useEffect, type ReactNode } from 'react';
import { useAnnounce } from '../a11y/useAnnounce';
import { cn } from '../lib/cn';

/**
 * One page-header treatment, one breadcrumb source, and a title on every route.
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
 * ## `MuiBreadcrumbs` → a plain `<nav><ol>`
 *
 * MUI's component inserted its own separators and its own list semantics. Ported in T16 to the
 * markup underneath it: an ordered list is what a breadcrumb trail is, the separator is
 * `aria-hidden` so it is not read out between every segment, and the last crumb carries
 * `aria-current="page"` — which the MUI version never did.
 */

export interface Crumb {
  label: string;
  /** Omit on the final crumb — the current page is not a link to itself. */
  to?: string;
}

export function PageHeader({
  title,
  /** Browser-tab title. Defaults to `title`; pass a shorter form when the heading is long. */
  documentTitle,
  subtitle,
  crumbs,
  actions,
  meta,
  announcement,
  testId,
}: {
  title: ReactNode;
  documentTitle?: string;
  subtitle?: ReactNode;
  crumbs?: Crumb[];
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
    <header data-testid={testId} className="mb-6">
      {tabTitle && <title>{`${tabTitle} · TCMS`}</title>}

      {crumbs && crumbs.length > 0 && (
        <nav aria-label="Breadcrumb" data-testid={`${testId}.Breadcrumbs`} className="mb-2">
          <ol className="m-0 flex list-none flex-wrap items-center gap-1.5 p-0 text-[12.5px] text-ink-3">
            {crumbs.map((crumb, index) => (
              <Fragment key={crumb.label}>
                {index > 0 && (
                  <li aria-hidden="true" className="text-ink-4">
                    /
                  </li>
                )}
                <li>
                  {crumb.to ? (
                    <RouterLink
                      to={crumb.to}
                      className="text-inherit no-underline hover:underline"
                      data-testid={`${testId}.Breadcrumb`}
                    >
                      {crumb.label}
                    </RouterLink>
                  ) : (
                    <span
                      aria-current="page"
                      className="font-medium text-ink-2"
                      data-testid={`${testId}.Breadcrumb`}
                    >
                      {crumb.label}
                    </span>
                  )}
                </li>
              </Fragment>
            ))}
          </ol>
        </nav>
      )}

      <div className="flex flex-col items-stretch gap-4 md:flex-row md:items-start">
        <div className="min-w-0 flex-1">
          <h1
            className="m-0 text-[22px] leading-tight font-[620] tracking-[-.02em] text-balance text-ink"
            data-testid={`${testId}.Title`}
          >
            {title}
          </h1>
          {meta && (
            <div
              className="mt-2 flex flex-wrap items-center gap-3"
              data-testid={`${testId}.Meta`}
            >
              {meta}
            </div>
          )}
          {subtitle && (
            <p className="mt-2 mb-0 max-w-[80ch] text-sm text-ink-2">{subtitle}</p>
          )}
        </div>

        {actions && (
          <div
            className="flex shrink-0 flex-wrap gap-2"
            data-testid={`${testId}.Actions`}
          >
            {actions}
          </div>
        )}
      </div>
    </header>
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
 */
export function Panel({
  title,
  actions,
  children,
  testId,
  dense,
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  testId?: string;
  dense?: boolean;
}) {
  return (
    <section
      data-testid={testId}
      className="overflow-hidden rounded-lg border border-white/8 bg-panel"
    >
      {(title || actions) && (
        <div
          className={cn(
            'flex items-center justify-between gap-2 border-b border-white/8',
            dense ? 'px-3 py-2' : 'px-4 py-3',
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
      <div className={dense ? '' : 'p-4'}>{children}</div>
    </section>
  );
}
