import { Fragment, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';

import { useShellFrame } from '../app/shellFrame';
import { crumbsFor, type Crumb } from '../app/routeMeta';
import { cn } from '../lib/cn';

export interface PageStripProps {
  /** The page's name. Rendered as the page's <h1>; visually hidden when `lead` is given. */
  title?: ReactNode;
  /** Replaces the visible title — the Work page's board picker. */
  lead?: ReactNode;
  subtitle?: ReactNode;
  meta?: ReactNode;
  /** A TabStrip, a Segmented, or a ui/tabs list. */
  views?: ReactNode;
  /** Right side: HeaderActions-shaped. */
  actions?: ReactNode;
  /** Draw the ancestors trail. Default true. */
  trail?: boolean;
  testId: string;
}

const PROJECT_ROOT = /^\/projects\/[^/]+$/;

function ancestorsOf(crumbs: Crumb[]): Crumb[] {
  return crumbs
    .slice(0, -1)
    .filter((crumb) => crumb.to !== '/' && !(crumb.to && PROJECT_ROOT.test(crumb.to)));
}

export function PageStrip({
  title,
  lead,
  subtitle,
  meta,
  views,
  actions,
  trail = true,
  testId,
}: PageStripProps) {
  const { pathname } = useLocation();
  const frame = useShellFrame();
  const ancestors = trail ? ancestorsOf(crumbsFor(pathname, frame)) : [];

  return (
    <header
      data-testid={testId}
      className="sticky top-12 z-10 -mx-3 mb-4 flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line bg-paper px-3 py-2.5 md:-mx-4 md:px-4"
    >
      <div className="min-w-0">
        {ancestors.length > 0 && (
          <nav
            aria-label="Breadcrumb"
            data-testid={`${testId}.Trail`}
            className="hidden md:block"
          >
            <ol className="m-0 flex list-none items-center gap-1.5 p-0 text-xs whitespace-nowrap text-ink-3">
              {ancestors.map((crumb, index) => (
                <Fragment key={`${crumb.label}-${String(index)}`}>
                  {index > 0 && (
                    <li aria-hidden="true" className="text-ink-4">
                      /
                    </li>
                  )}
                  <li>
                    {crumb.to ? (
                      <Link
                        to={crumb.to}
                        title={crumb.title}
                        className="text-inherit no-underline hover:text-steel"
                      >
                        {crumb.label}
                      </Link>
                    ) : (
                      <span title={crumb.title}>{crumb.label}</span>
                    )}
                  </li>
                </Fragment>
              ))}
            </ol>
          </nav>
        )}
        {lead}
        {title && (
          <h1
            data-testid={`${testId}.Title`}
            className={cn(
              'm-0 text-[17px] leading-tight font-[650] tracking-[-.015em] text-ink',
              lead && 'sr-only',
            )}
          >
            {title}
          </h1>
        )}
        {(subtitle || meta) && (
          <div className="mt-0.5 flex flex-wrap items-center gap-x-3 text-[12.5px] text-ink-3">
            {subtitle && <p className="m-0 max-w-[74ch]">{subtitle}</p>}
            {meta && (
              <div className="flex flex-wrap items-center gap-3" data-testid={`${testId}.Meta`}>
                {meta}
              </div>
            )}
          </div>
        )}
      </div>
      {views && <div data-testid={`${testId}.Views`}>{views}</div>}
      <div className="flex-1" />
      {actions && (
        <div className="flex shrink-0 flex-wrap gap-2" data-testid={`${testId}.Actions`}>
          {actions}
        </div>
      )}
    </header>
  );
}
