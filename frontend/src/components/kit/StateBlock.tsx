import { CircleAlert, Inbox, Lock, RefreshCcw } from 'lucide-react';
import type { ReactNode } from 'react';

import { cn } from '../../lib/cn';
import { Button, Skeleton } from '../ui';

/**
 * Design language §7's six states, minus `loaded` (that is just the page). `.state` in the render
 * kit draws one generic shell for all of them — this deliberately diverges from it for `failed` and
 * `empty`: §7 requires they be "unmistakable — different icon, colour, verb and offer", because a
 * 200 with the wrong shape rendering as *no results* is the quietest data-loss bug a client can
 * have (`tcmsFaults.drift()` exists to prove exactly this). So `failed` alone carries the red ink
 * and the reference/timestamp; `empty` stays neutral and counts what its filters hid.
 *
 * `refused` is not an error — neutral colour, no retry, and it names the permission id and a
 * grantee, because retrying a permission you do not hold is not a recovery path.
 *
 * `stale` does not use the centred `.state` shell at all: §7 says a failed refetch "keeps its data
 * and marks it stale", which means the old rows keep rendering underneath — a full-block
 * replacement would contradict that. It renders as an inline strip instead, reusing `Banner`'s warn
 * tone.
 */
export type StateKind = 'loading' | 'empty' | 'failed' | 'stale' | 'refused';

interface BaseProps {
  className?: string;
  'data-testid'?: string;
}

export interface LoadingStateProps extends BaseProps {
  kind: 'loading';
  rows?: number;
}

export interface EmptyStateProps extends BaseProps {
  kind: 'empty';
  title?: string;
  hidden?: number;
  filters?: number;
  onClearFilters?: () => void;
}

export interface FailedStateProps extends BaseProps {
  kind: 'failed';
  reference: string;
  timestamp: string;
  message?: string;
  onRetry?: () => void;
}

export interface StaleStateProps extends BaseProps {
  kind: 'stale';
  message?: string;
  onRetry?: () => void;
}

export interface RefusedStateProps extends BaseProps {
  kind: 'refused';
  permission: string;
  grantee: string;
}

export type StateBlockProps =
  | LoadingStateProps
  | EmptyStateProps
  | FailedStateProps
  | StaleStateProps
  | RefusedStateProps;

function Shell({
  icon: Icon,
  iconColor,
  title,
  description,
  actions,
  className,
  ...rest
}: {
  icon: typeof Inbox;
  iconColor?: string;
  title: ReactNode;
  description: ReactNode;
  actions?: ReactNode;
  className?: string;
  'data-testid'?: string;
}) {
  return (
    <div className={cn('mx-auto max-w-[56ch] px-[34px] py-[52px] text-center', className)} {...rest}>
      <Icon aria-hidden style={iconColor ? { color: iconColor } : undefined} className="mx-auto mb-3.5 size-[34px] text-ink-4" />
      <h3 className="mb-[7px] text-base font-semibold text-ink-2">{title}</h3>
      <p className="mb-[18px] text-[13.5px] leading-[1.6] text-ink-4">{description}</p>
      {actions && <div className="flex flex-wrap justify-center gap-2.5">{actions}</div>}
    </div>
  );
}

export function StateBlock(props: StateBlockProps) {
  const { className, ...rest } = props;

  if (props.kind === 'loading') {
    const rows = props.rows ?? 4;
    return (
      <div className={cn('mx-auto flex max-w-[56ch] flex-col gap-2.5 px-[34px] py-[52px]', className)} data-testid={props['data-testid']}>
        {Array.from({ length: rows }).map((_, index) => (
          <Skeleton key={index} className={cn('h-[11px]', index === rows - 1 && 'w-2/3')} />
        ))}
      </div>
    );
  }

  if (props.kind === 'empty') {
    const hasFilterInfo = props.hidden != null && props.filters != null;
    const description = hasFilterInfo
      ? `${props.hidden} cases exist. ${props.filters} filter${props.filters === 1 ? '' : 's'} ${
          props.filters === 1 ? 'is' : 'are'
        } hiding all of them.`
      : 'Nothing here yet.';

    return (
      <Shell
        icon={Inbox}
        title={props.title ?? 'No results'}
        description={description}
        className={className}
        data-testid={props['data-testid']}
        actions={
          props.onClearFilters && (
            <Button variant="ghost" onClick={props.onClearFilters} data-testid={props['data-testid'] && `${props['data-testid']}-clear`}>
              Remove filters
            </Button>
          )
        }
      />
    );
  }

  if (props.kind === 'failed') {
    return (
      <Shell
        icon={CircleAlert}
        iconColor="var(--color-failed)"
        title="Couldn't load this"
        description={
          <>
            {props.message ?? 'Something went wrong on our end.'}
            <span className="mt-1.5 block font-mono text-[11px] text-ink-4">
              Ref {props.reference} · {props.timestamp}
            </span>
          </>
        }
        className={className}
        data-testid={props['data-testid']}
        actions={
          props.onRetry && (
            <Button variant="primary" onClick={props.onRetry} data-testid={props['data-testid'] && `${props['data-testid']}-retry`}>
              Retry
            </Button>
          )
        }
      />
    );
  }

  if (props.kind === 'refused') {
    return (
      <Shell
        icon={Lock}
        title="You don't have access"
        description={`This needs the ${props.permission} permission. Ask ${props.grantee} to grant it.`}
        className={className}
        data-testid={props['data-testid']}
      />
    );
  }

  return (
    <div
      style={{
        borderColor: 'color-mix(in oklab, var(--color-blocked) 26%, transparent)',
        backgroundColor: 'color-mix(in oklab, var(--color-blocked) 7%, transparent)',
      }}
      className={cn('flex items-center gap-2.5 rounded-lg border px-3.5 py-2.5 text-[13px] text-ink-2', className)}
      data-testid={rest['data-testid']}
    >
      <RefreshCcw aria-hidden style={{ color: 'var(--color-blocked)' }} className="size-4 shrink-0" />
      <span className="flex-1">{props.message ?? 'Showing saved data — the last refresh failed.'}</span>
      {props.onRetry && (
        <Button variant="ghost" size="sm" onClick={props.onRetry} data-testid={rest['data-testid'] && `${rest['data-testid']}-retry`}>
          Retry
        </Button>
      )}
    </div>
  );
}
