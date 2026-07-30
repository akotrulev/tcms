import { Box } from '@mui/material';

/**
 * Skip-to-content link.
 *
 * A real `<a href="#main-content">`, not a button with a scroll handler: the browser's own
 * fragment navigation moves focus as well as scrolling, and `<main>` carries `tabIndex={-1}`
 * precisely so it can receive that focus.
 *
 * Hidden until focused — the standard treatment. It is positioned off-screen rather than
 * `display: none`, because a hidden-by-display element cannot be focused and so could never
 * reveal itself.
 */
export function SkipToContentLink() {
  return (
    <Box
      component="a"
      href="#main-content"
      data-testid="SkipToContent"
      sx={{
        position: 'absolute',
        zIndex: 1400,
        top: 8,
        left: 8,
        px: 2,
        py: 1,
        borderRadius: 1,
        bgcolor: 'primary.main',
        color: 'primary.contrastText',
        fontSize: '0.875rem',
        fontWeight: 600,
        textDecoration: 'none',
        // Off-screen until focused. `transform` rather than `top` so no layout is affected.
        transform: 'translateY(-200%)',
        '&:focus-visible': { transform: 'translateY(0)' },
        // Some browsers give a fragment link :focus without :focus-visible; honour both so the
        // link cannot end up focused-but-invisible, which is worse than not having it.
        '&:focus': { transform: 'translateY(0)' },
        '@media (prefers-reduced-motion: no-preference)': {
          transition: 'transform 120ms ease-out',
        },
      }}
    >
      Skip to content
    </Box>
  );
}
