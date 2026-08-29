import { createTheme } from '@mui/material/styles';
import { breakpoints } from './breakpoints';

/**
 * One theme object, sourced from the CSS custom properties in index.css so there is a
 * single colour system rather than two diverging ones (styling skill: "bridge to the
 * library, one source of truth").
 *
 * `cssVariables` with `colorSchemeSelector: 'data'` means dark mode is an attribute swap
 * on <html> with no light→dark flash, and our own tokens key off the same attribute.
 */

// MUI needs literal values for palette computation (contrast text, alpha channels), so
// the brand/status hexes are declared once here and mirrored into index.css. Any change
// must be made in both — this is the one accepted duplication, and it's why status colours
// are exported as a map rather than re-typed at each call site.
export const statusColors = {
  PASSED: { light: '#2e7d5b', dark: '#7ec8a0' },
  FAILED: { light: '#c0392b', dark: '#e07568' },
  BLOCKED: { light: '#b7791f', dark: '#e0b95f' },
  SKIPPED: { light: '#6b7280', dark: '#8b94a3' },
  UNTESTED: { light: '#9ca3af', dark: '#6b7280' },
  RETEST: { light: '#3f5aa6', dark: '#8fa9e0' },
} as const;

export const lifecycleColors = {
  DRAFT: { light: '#8593a5', dark: '#6b7a8d' },
  IN_REVIEW: { light: '#b7791f', dark: '#d9a441' },
  APPROVED: { light: '#2e7d5b', dark: '#5aab84' },
  DEPRECATED: { light: '#9ca3af', dark: '#6b7280' },
} as const;

export const theme = createTheme({
  cssVariables: { colorSchemeSelector: 'data' },
  breakpoints: { values: breakpoints },
  colorSchemes: {
    light: {
      palette: {
        primary: { main: '#2e6e8e', dark: '#24596f', contrastText: '#ffffff' },
        secondary: { main: '#1b2a41' },
        success: { main: '#2e7d5b' },
        warning: { main: '#b7791f' },
        error: { main: '#c0392b' },
        info: { main: '#2e6e8e' },
        background: { default: '#f5f7fa', paper: '#ffffff' },
        text: { primary: '#1b2a41', secondary: '#5b6b80' },
        divider: '#dde3ea',
      },
    },
    dark: {
      palette: {
        primary: { main: '#6ba8c4', dark: '#83bad4', contrastText: '#0f141b' },
        secondary: { main: '#e8edf3' },
        success: { main: '#5aab84' },
        warning: { main: '#d9a441' },
        error: { main: '#e07568' },
        info: { main: '#6ba8c4' },
        background: { default: '#0f141b', paper: '#151b24' },
        text: { primary: '#e8edf3', secondary: '#9aa8ba' },
        divider: '#2a3340',
      },
    },
  },
  shape: { borderRadius: 4 },
  typography: {
    fontFamily: "'IBM Plex Sans', system-ui, -apple-system, sans-serif",
    fontSize: 14,
    // Headings carry hierarchy globally so components never re-pick sizes.
    h1: { fontSize: '1.75rem', fontWeight: 600, lineHeight: 1.2, letterSpacing: '-0.011em' },
    h2: { fontSize: '1.375rem', fontWeight: 600, lineHeight: 1.2, letterSpacing: '-0.011em' },
    h3: { fontSize: '1.125rem', fontWeight: 600, lineHeight: 1.25 },
    h4: { fontSize: '1rem', fontWeight: 600, lineHeight: 1.3 },
    h5: { fontSize: '0.875rem', fontWeight: 600 },
    h6: { fontSize: '0.8125rem', fontWeight: 600 },
    body1: { fontSize: '0.875rem', lineHeight: 1.5 },
    body2: { fontSize: '0.8125rem', lineHeight: 1.5 },
    caption: { fontSize: '0.75rem', lineHeight: 1.4 },
    button: { textTransform: 'none', fontWeight: 500, letterSpacing: 0 },
  },
  components: {
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: { root: { paddingInline: 14 } },
    },
    MuiPaper: {
      // One elevated surface per region with hairline dividers inside it — the revision
      // away from card-shadow-everywhere noted in the design plan.
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: ({ theme: t }) => ({ border: `1px solid ${t.palette.divider}` }),
      },
    },
    MuiTableCell: {
      styleOverrides: { root: { fontSize: '0.8125rem' } },
    },
    MuiChip: {
      styleOverrides: {
        root: { fontWeight: 500, fontSize: '0.75rem', height: 22 },
        sizeSmall: { height: 20 },
      },
    },
    MuiTooltip: {
      defaultProps: { arrow: true },
    },
    MuiLink: {
      defaultProps: { underline: 'hover' },
    },
    MuiTextField: {
      defaultProps: { size: 'small' },
    },
    MuiCssBaseline: {
      styleOverrides: {
        // Visible keyboard focus is part of the quality floor, not an accessory.
        ':focus-visible': {
          outline: '2px solid var(--color-action-primary)',
          outlineOffset: 2,
        },
      },
    },
  },
});
