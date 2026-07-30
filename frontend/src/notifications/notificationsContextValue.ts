import { createContext } from 'react';

/**
 * The Context object and its types, split from the provider component so that file exports
 * only a component — the same Fast Refresh constraint that shaped `authContextValue.ts`.
 */

export interface NotifyErrorInput {
  /**
   * Already-categorized, user-facing copy. Never a raw `error.message` or response body —
   * the categorization happens in `describeError`, and this surface only renders the result.
   */
  message: string;
  /** Which part of the app failed. Feeds the dedupe key and the rolled-up summary. */
  region?: string;
  /** Ties the notification to the server-side log entry. Rendered, copyable. */
  correlationId?: string | null;
  /**
   * The *cause* — an HTTP status or error name. The rollup groups on this, which is what
   * turns one 503 failing six regions into one message instead of six.
   */
  code?: string | number | null;
  /** Offered on the notification when retrying is plausibly useful. */
  onRetry?: () => void;
}

export interface NotifyPromptInput {
  /** Stable id, so a repeated prompt (a skew check on every focus) updates rather than stacks. */
  id: string;
  message: string;
  actionLabel: string;
  onAction: () => void;
}

export interface NotifyValue {
  /**
   * Raises a persistent error notification. Deduped by `correlationId ?? code|region`, so a
   * call that fails three times leaves one entry with a count rather than three copies.
   */
  error: (input: NotifyErrorInput) => void;
  /**
   * A persistent, actionable prompt that is not itself a failure — the "new version
   * available, reload" affordance. Shares this one stack, per the rule that there must not
   * be two competing new-version prompts.
   */
  prompt: (input: NotifyPromptInput) => void;
  dismiss: (id: string) => void;
  dismissAll: () => void;
}

export const NotificationsContext = createContext<NotifyValue | null>(null);
