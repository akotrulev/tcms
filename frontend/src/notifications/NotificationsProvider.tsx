import { Copy, X } from 'lucide-react';
import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { Button } from '../components/ui';
import { cn } from '../lib/cn';
import {
  NotificationsContext,
  type NotifyErrorInput,
  type NotifyPromptInput,
  type NotifyValue,
} from './notificationsContextValue';

/**
 * The persistent error notification surface.
 *
 * The inline `DataRegion` failed state says *this data is missing*; this says *why, and how to
 * report it*. They are not alternatives — a region shows both.
 *
 * Four properties are the whole point, and each one is a defect class if it's missing:
 *
 *   1. **Errors never auto-dismiss.** A timed-out error the user was scrolling past is an
 *      unreported bug. Dismissal is the close button, and nothing else. (Success toasts *may*
 *      auto-dismiss — those go through ~~notistack~~ `notifications/toast.ts`, which is why this
 *      surface handles only failures and prompts.)
 *   2. **Deduped by cause, with a count.** A call that fails three times is one entry saying
 *      "3 times", not three stacked copies whose own close buttons scroll off-screen.
 *   3. **Rolled up by cause, not by call.** One backend 503 failing six regions is *one*
 *      message. The per-region inline states already say which regions are affected; six
 *      toasts add no information and hide their own controls.
 *   4. **Capped.** At most three groups are visible; the rest collapse behind "+N more".
 *
 * It is also the single "new version available" affordance (`prompt`), so the deploy-skew
 * recovery and any future service-worker update prompt cannot become two competing messages.
 */

const MAX_VISIBLE_GROUPS = 3;

/** Entries raised within this window are eligible to roll up together under one cause. */
const ROLLUP_WINDOW_MS = 10_000;

interface Entry {
  /** The dedupe key: `correlationId ?? code|region`. */
  id: string;
  kind: 'error' | 'prompt';
  message: string;
  title: string | null;
  /** Grouping cause — the HTTP status or error name. Entries sharing one roll up together. */
  code: string;
  region: string | null;
  correlationId: string | null;
  count: number;
  firstAt: number;
  lastAt: number;
  onRetry?: () => void;
  /** Prompt-only. */
  actionLabel?: string;
  onAction?: () => void;
}

export function NotificationsProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [expanded, setExpanded] = useState(false);

  /**
   * Held in a ref as well as state so `error()` stays a stable callback. If it depended on
   * `entries`, every raise would return a new function identity, and the global query/mutation
   * error handlers that close over it would need rebuilding on every notification.
   */
  const entriesRef = useRef<Entry[]>([]);

  const commit = useCallback((next: Entry[]) => {
    entriesRef.current = next;
    setEntries(next);
  }, []);

  const error = useCallback(
    (input: NotifyErrorInput) => {
      const code = String(input.code ?? 'unknown');
      const region = input.region ?? null;
      const id = input.correlationId ?? `${code}|${region ?? 'app'}`;
      const now = Date.now();
      const current = entriesRef.current;

      const existing = current.find((entry) => entry.id === id);
      if (existing) {
        // Dedupe: same failure again. Update in place with a count rather than stacking. The
        // newest retry handler wins, because it closes over the current query state.
        commit(
          current.map((entry) =>
            entry.id === id
              ? { ...entry, count: entry.count + 1, lastAt: now, onRetry: input.onRetry ?? entry.onRetry }
              : entry,
          ),
        );
        return;
      }

      commit([
        ...current,
        {
          id,
          kind: 'error',
          message: input.message,
          title: input.title ?? null,
          code,
          region,
          correlationId: input.correlationId ?? null,
          count: 1,
          firstAt: now,
          lastAt: now,
          onRetry: input.onRetry,
        },
      ]);
    },
    [commit],
  );

  const prompt = useCallback(
    (input: NotifyPromptInput) => {
      const current = entriesRef.current;
      const next: Entry = {
        id: input.id,
        kind: 'prompt',
        message: input.message,
        title: null,
        // Prompts never roll up with anything — a unique cause keyed on the id.
        code: `prompt:${input.id}`,
        region: null,
        correlationId: null,
        count: 1,
        firstAt: Date.now(),
        lastAt: Date.now(),
        actionLabel: input.actionLabel,
        onAction: input.onAction,
      };
      commit(
        current.some((entry) => entry.id === input.id)
          ? current.map((entry) => (entry.id === input.id ? { ...next, firstAt: entry.firstAt } : entry))
          : [...current, next],
      );
    },
    [commit],
  );

  const dismiss = useCallback(
    (id: string) => commit(entriesRef.current.filter((entry) => entry.id !== id)),
    [commit],
  );

  const dismissAll = useCallback(() => {
    commit([]);
    setExpanded(false);
  }, [commit]);

  const value = useMemo<NotifyValue>(() => ({ error, prompt, dismiss, dismissAll }), [
    error,
    prompt,
    dismiss,
    dismissAll,
  ]);

  const groups = useMemo(() => groupByCause(entries), [entries]);
  const visible = expanded ? groups : groups.slice(0, MAX_VISIBLE_GROUPS);
  const hiddenCount = groups.length - visible.length;

  return (
    <NotificationsContext value={value}>
      {children}

      {/*
        The region exists in the DOM unconditionally, empty or not. A live region mounted at
        announce time does not announce — the screen reader has to be observing the node
        before its content changes.

        `z-1250` is above the app bar (1100) and the drawer (1200) and below modals (1300): a
        dialog the user is working in must not be covered by a notification about something else.
      */}
      <div
        data-testid="Notifications.Region"
        aria-live="polite"
        aria-label="Notifications"
        className={
          'pointer-events-none fixed top-3 right-3 z-[1250] max-h-[calc(100vh-24px)] ' +
          'w-[calc(100vw-24px)] overflow-y-auto sm:w-100'
        }
      >
        <div className="flex flex-col gap-2">
          {visible.map((group) => (
            <NotificationCard key={group.key} group={group} onDismiss={dismiss} />
          ))}

          {hiddenCount > 0 && (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => setExpanded(true)}
              data-testid="Notifications.ShowMore"
              className="pointer-events-auto bg-panel"
            >
              {hiddenCount} more {hiddenCount === 1 ? 'issue' : 'issues'}
            </Button>
          )}

          {groups.length > 1 && (
            <Button
              size="sm"
              variant="quiet"
              onClick={dismissAll}
              data-testid="Notifications.DismissAll"
              className="pointer-events-auto self-end"
            >
              Dismiss all
            </Button>
          )}
        </div>
      </div>
    </NotificationsContext>
  );
}

// ----------------------------------------------------------------- roll-up ----

interface Group {
  key: string;
  kind: 'error' | 'prompt';
  /** The message to show for the group as a whole. */
  message: string;
  /** Null when the group's entries disagree, or when no caller supplied one. */
  title: string | null;
  entries: Entry[];
  /** Total occurrences across the group, so a rolled-up group still reports its volume. */
  count: number;
}

/**
 * Groups entries by cause. Two or more *different* regions failing on the same code within the
 * roll-up window become one message — "the service is unavailable" is one fact about the
 * backend, not six facts about six regions.
 *
 * Entries whose cause is shared but which arrived far apart stay separate: a 503 now and a 503
 * twenty minutes later are two incidents, and merging them would hide the second.
 */
function groupByCause(entries: Entry[]): Group[] {
  const byCause = new Map<string, Entry[]>();
  for (const entry of entries) {
    const bucket = byCause.get(entry.code);
    if (bucket && entry.firstAt - bucket[bucket.length - 1]!.firstAt <= ROLLUP_WINDOW_MS) {
      bucket.push(entry);
    } else if (bucket) {
      // Same cause, but outside the window — keep it as its own group under a suffixed key.
      byCause.set(`${entry.code}#${entry.firstAt}`, [entry]);
    } else {
      byCause.set(entry.code, [entry]);
    }
  }

  return [...byCause.entries()].map(([key, group]) => {
    const count = group.reduce((sum, entry) => sum + entry.count, 0);
    const messages = new Set(group.map((entry) => entry.message));
    const titles = new Set(group.map((entry) => entry.title));
    return {
      key,
      kind: group[0]!.kind,
      // A rolled-up group only keeps the headline its entries agree on; a shared cause with two
      // different titles has no single true headline, and the generic one is the honest answer.
      title: titles.size === 1 ? group[0]!.title : null,
      // One distinct message across the group reads correctly as-is. Several different ones
      // must not be concatenated into a paragraph — state the shared cause instead, and let
      // the inline region states say which parts are affected.
      message:
        messages.size === 1
          ? group[0]!.message
          : `Some data couldn't be loaded (${group.length} parts of this page).`,
      entries: group,
      count,
    };
  });
}

// -------------------------------------------------------------------- card ----

function NotificationCard({ group, onDismiss }: { group: Group; onDismiss: (id: string) => void }) {
  const isPrompt = group.kind === 'prompt';
  const primary = group.entries[0]!;

  // Only one correlation id is worth showing; with several, the first identifies the incident
  // well enough for support to find the rest around it in the log.
  const correlationId = group.entries.find((entry) => entry.correlationId)?.correlationId ?? null;

  const regions = group.entries.map((entry) => entry.region).filter((r): r is string => !!r);

  const dismissGroup = () => group.entries.forEach((entry) => onDismiss(entry.id));

  return (
    <div
      // `role="alert"` for a failure so it interrupts; a prompt is informational and rides the
      // container's polite region instead of talking over whatever the user is doing.
      role={isPrompt ? 'status' : 'alert'}
      data-testid={isPrompt ? 'Notifications.Prompt' : 'Notifications.Error'}
      style={{
        borderLeftColor: isPrompt ? 'var(--color-steel)' : 'var(--color-failed)',
      }}
      className={cn(
        'pointer-events-auto flex items-start gap-[11px] rounded-lg border border-white/8',
        'border-l-[3px] bg-ground px-3.5 py-3 text-[13px] leading-[1.5] text-ink-2',
        'shadow-[0_16px_32px_-8px_rgb(0_0_0/60%)]',
      )}
    >
      <div className="min-w-0 flex-1">
        <b className="mb-0.5 block font-[550] text-ink">
          {isPrompt ? 'Update available' : (group.title ?? "Couldn't load this")}
        </b>

        <p className="m-0">{group.message}</p>

        {/* Volume, so a repeated failure reads as repeated rather than as one blip. */}
        {group.count > 1 && (
          <p className="m-0 mt-1 text-[11.5px] text-ink-4">
            Occurred {group.count} times
            {regions.length > 1 ? ` across ${regions.length} areas` : ''}
          </p>
        )}

        {correlationId && (
          <div className="mt-1.5 flex items-center gap-1.5 font-mono text-[10.5px] text-ink-4">
            <span>ref</span>
            <span data-testid="Notifications.Error.CorrelationId">{correlationId}</span>
            <button
              type="button"
              aria-label="Copy reference"
              data-testid="Notifications.Error.CopyCorrelationId"
              className="cursor-pointer text-ink-4 hover:text-ink-2"
              onClick={() => {
                // Clipboard access is unavailable on an insecure origin and can reject when the
                // document isn't focused. A failed copy must not take the notification down.
                void navigator.clipboard?.writeText(correlationId).catch(() => {});
              }}
            >
              <Copy aria-hidden className="size-3.5" />
            </button>
          </div>
        )}

        {(isPrompt || primary.onRetry) && (
          <Button
            size="sm"
            variant={isPrompt ? 'primary' : 'ghost'}
            data-testid={isPrompt ? 'Notifications.Prompt.Action' : 'Notifications.Error.Retry'}
            className="mt-2.5"
            onClick={() => {
              if (isPrompt) {
                primary.onAction?.();
                return;
              }
              // Retrying every entry in the group: one 503 that took out six regions should
              // recover all six from one click, not make the user dismiss and retry each.
              group.entries.forEach((entry) => entry.onRetry?.());
              dismissGroup();
            }}
          >
            {isPrompt ? primary.actionLabel : 'Try again'}
          </Button>
        )}
      </div>

      <button
        type="button"
        aria-label="Close"
        onClick={dismissGroup}
        data-testid={isPrompt ? 'Notifications.Prompt.Close' : 'Notifications.Error.Close'}
        className="mt-px cursor-pointer text-ink-4 hover:text-ink-2"
      >
        <X aria-hidden className="size-3.5" />
      </button>
    </div>
  );
}
