import { createContext } from 'react';

/** `system` is a real, distinct value and the default — see `colorScheme.tsx`'s header. */
export type ColorSchemeMode = 'light' | 'dark' | 'system';

export interface ColorSchemeValue {
  /** What the user chose. `system` is a choice, not an absence. */
  mode: ColorSchemeMode;
  /** What the OS is currently asking for. */
  systemMode: 'light' | 'dark';
  /** What is actually on screen — `mode` with `system` resolved. */
  resolved: 'light' | 'dark';
  setMode: (next: ColorSchemeMode) => void;
}

export const ColorSchemeContext = createContext<ColorSchemeValue | null>(null);
