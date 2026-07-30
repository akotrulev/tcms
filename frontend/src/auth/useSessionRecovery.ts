import { use, useEffect } from 'react';
import { SessionRecoveryContext, type SessionRecoveryValue } from './sessionRecoveryContextValue';

export function useSessionRecovery(): SessionRecoveryValue {
  const value = use(SessionRecoveryContext);
  if (!value) {
    throw new Error('useSessionRecovery must be used inside <SessionRecoveryProvider>.');
  }
  return value;
}

/**
 * Declares that this component is holding work the user would lose.
 *
 * Wire it to a form's `formState.isDirty`, or to a pending mutation. Both destructive recoveries
 * in the app — the deploy-skew reload and any fallback redirect on session expiry — check this
 * first, so a dirty editor gets a prompt where a clean list page gets a silent reload.
 *
 * The cleanup runs on unmount as well as on the flag going false, so navigating away from a
 * dirty form does not leave the app permanently believing there is unsaved work.
 */
export function useUnsavedWork(active: boolean, label: string): void {
  const { registerUnsavedWork } = useSessionRecovery();

  useEffect(() => {
    if (!active) return;
    return registerUnsavedWork(label);
  }, [active, label, registerUnsavedWork]);
}
