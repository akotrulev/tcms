import { Fragment } from 'react';
import { Link as RouterLink, useLocation } from 'react-router-dom';

import { crumbsFor, type CrumbContext } from './routeMeta';

/**
 * The breadcrumb trail, **owned by the shell** (021 §3, R2).
 *
 * ~~Every page drew its own, from a `crumbs` prop on `PageHeader`.~~ → **superseded (021 R2).**
 * Forty-six screens each composed their own trail, which is forty-six places a rename has to reach
 * and forty-six chances for one of them not to be reached: the audit found `Documentation` in a
 * trail beside `Documents` in the rail, and five screens whose trail began at the project while
 * forty began at the list. It also put the trail *inside* the page, so a failed read took the only
 * non-rail way back up with it — the five document-mode screens each worked around that by lifting
 * their own trail into the top strip, in five slightly different spellings.
 *
 * One trail, from {@link crumbsFor}, in the header, above every route and outside every data
 * region. It cannot be taken down by a page's failure because it does not depend on one.
 *
 * ## Where it sits, and what it gives way to
 *
 * Immediately right of the reserved company slot, so the header reads outward-in — company,
 * project, screen. It truncates rather than wraps: the header is fixed at 52px (see
 * `HeaderActions` for what happens to a group that wraps in it), so `min-w-0` and `truncate` are
 * load-bearing rather than decoration, and the trail is what gives way when a page's action group
 * needs the room.
 *
 * Hidden below `md`, where the space is the search control's and the rail is a drawer away. The
 * trail is a shortcut *up*; on a phone the back gesture is the same shortcut and is already there.
 *
 * ## A11y
 *
 * `<nav aria-label="Breadcrumb">` over an `<ol>` — a trail IS an ordered list, and the separator is
 * `aria-hidden` so a screen reader does not read a slash between every segment. The final crumb
 * carries `aria-current="page"`. All of that is inherited from `PageHeader`'s own trail, which had
 * it right; what moved is where it is rendered from.
 */
export function ShellBreadcrumbs(context: CrumbContext) {
  const { pathname } = useLocation();
  const crumbs = crumbsFor(pathname, context);

  if (crumbs.length === 0) return null;

  return (
    <nav
      aria-label="Breadcrumb"
      data-testid="TopBar.Breadcrumbs"
      className="hidden min-w-0 shrink truncate md:block"
    >
      <ol className="m-0 flex list-none items-center gap-1.5 p-0 text-[12.5px] whitespace-nowrap text-ink-3">
        {crumbs.map((crumb, index) => {
          const last = index === crumbs.length - 1;
          return (
            <Fragment key={`${crumb.label}-${String(index)}`}>
              {index > 0 && (
                <li aria-hidden="true" className="text-ink-4">
                  /
                </li>
              )}
              <li className="min-w-0 truncate">
                {crumb.to && !last ? (
                  <RouterLink
                    to={crumb.to}
                    title={crumb.title}
                    className="text-inherit no-underline hover:underline"
                    data-testid="TopBar.Breadcrumb"
                  >
                    {crumb.label}
                  </RouterLink>
                ) : (
                  <span
                    // Only the LAST crumb is the current page. A middle crumb with no `to` is a
                    // grouping segment (`Company`, `Account`) — real, unclickable, and not here.
                    aria-current={last ? 'page' : undefined}
                    title={crumb.title}
                    className={last ? 'font-medium text-ink-2' : undefined}
                    data-testid="TopBar.Breadcrumb"
                  >
                    {crumb.label}
                  </span>
                )}
              </li>
            </Fragment>
          );
        })}
      </ol>
    </nav>
  );
}
