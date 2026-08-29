import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';

import { safeStorage } from '../lib/safeStorage';
import {
  ColorSchemeContext,
  type ColorSchemeMode,
  type ColorSchemeValue,
} from './colorSchemeContextValue';

/**
 * The colour scheme — the app's own, replacing MUI's `useColorScheme` in T16.
 *
 * ## Why this had to be written before `ThemeProvider` could be deleted
 *
 * `index.css` defines the whole dark palette under one attribute selector on `<html>`, and
 * **MUI's `ThemeProvider defaultMode="system"` was the only thing writing that attribute.**
 * Deleting the provider and leaving `AppShell`'s toggle in place would have compiled, linted and
 * passed every test while the button did nothing — CLAUDE.md names the no-op theme toggle as one
 * of the three defects that got through the gates before. So the toggle moved here first.
 *
 * ## `data-mui-color-scheme` → `data-color-scheme`
 *
 * The old name was MUI's contract, not a description. Renamed with the port; `index.css`'s dark
 * block and `tokenSystem.test.ts` move with it, and those are the only two readers there have
 * ever been. **The stored key moved too** (`mui-mode` → `tcms-color-scheme`), which means one
 * reset per user: anyone who had explicitly chosen light or dark falls back to `system` on their
 * next visit and re-picks once. Migrating the old key would mean shipping a reader for a
 * vocabulary this app no longer has, permanently, to save a single click.
 *
 * ## Three modes, not two
 *
 * `system` is a real, distinct value and is the default — it is not "light". The toggle in the
 * top bar only ever moves between the two explicit values, and it reads `resolved` rather than
 * `mode` for the reason `TopBar` documents: on a machine set to dark, `mode === 'system'` while
 * the page is plainly dark, and a control labelled from the *setting* offers "switch to dark" on
 * an already-dark page.
 */
const STORAGE_KEY = 'tcms-color-scheme';
const ATTRIBUTE = 'data-color-scheme';

function readStoredMode(): ColorSchemeMode {
  const stored = safeStorage.get(STORAGE_KEY);
  return stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system';
}

/**
 * `matchMedia` is absent in jsdom unless a test polyfills it, and this provider mounts under
 * every test that renders the shell — so its absence is a supported state that resolves to
 * light, not a crash.
 */
function readSystemMode(): 'light' | 'dark' {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return 'light';
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function ColorSchemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<ColorSchemeMode>(readStoredMode);
  const [systemMode, setSystemMode] = useState<'light' | 'dark'>(readSystemMode);

  // The OS setting can change while the tab is open, and a `system` reader must follow it.
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = (event: MediaQueryListEvent) => setSystemMode(event.matches ? 'dark' : 'light');
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);

  const resolved = mode === 'system' ? systemMode : mode;

  /*
    The attribute is written on `<html>`, which is where `index.css`'s selector expects it and
    where it must be for the palette to reach a portalled dialog — Radix renders those into
    `document.body`, outside every React tree this provider wraps. `color-scheme` alongside it is
    what makes the browser's own chrome (scrollbars, form controls, the canvas behind the page)
    follow, which is the one thing MUI's `CssBaseline` did that no utility class can.
  */
  useEffect(() => {
    const root = document.documentElement;
    root.setAttribute(ATTRIBUTE, resolved);
    root.style.colorScheme = resolved;
  }, [resolved]);

  const setMode = useCallback((next: ColorSchemeMode) => {
    setModeState(next);
    safeStorage.set(STORAGE_KEY, next);
  }, []);

  const value = useMemo<ColorSchemeValue>(
    () => ({ mode, systemMode, resolved, setMode }),
    [mode, systemMode, resolved, setMode],
  );

  return <ColorSchemeContext value={value}>{children}</ColorSchemeContext>;
}

