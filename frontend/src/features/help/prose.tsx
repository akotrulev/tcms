import { Alert, Box, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { ScrollX } from '../../components/ScrollX';

/**
 * The prose primitives the help sections are written with.
 *
 * **Why a component set and not a markdown renderer.** The obvious shape for a help page is
 * markdown plus `react-markdown`, and it was refused on two counts. It is ~40 kB of parser and
 * sanitizer shipped to every reader for content that is fixed at build time and never
 * user-supplied, on a page whose entire job is to be cheap and always available; and it would put
 * the copy outside TypeScript, where a rename in `lib/permissions.ts` or a route change in
 * `router.tsx` cannot reach it. Sections are authored as TSX instead, so a link to a route that
 * stops existing is a compile error and every identifier on the page can come from the same
 * catalogue the rest of the app reads.
 *
 * These are deliberately thin. Anything a section needs that is more than a paragraph, a list or
 * a command block belongs in a real component beside `RoleMatrix` — not in another primitive here.
 */

/** A paragraph. `maxWidth` is a measure, not a layout: prose past ~78ch stops being readable. */
export function P({ children }: { children: ReactNode }) {
  return (
    <Typography variant="body2" sx={{ maxWidth: '78ch', mb: 1.5, lineHeight: 1.7 }}>
      {children}
    </Typography>
  );
}

/** A sub-heading inside a section. `h3`, because the section itself renders the `h2`. */
export function H({ children }: { children: ReactNode }) {
  return (
    <Typography variant="h5" component="h3" sx={{ mt: 3, mb: 1 }}>
      {children}
    </Typography>
  );
}

/**
 * An unordered list, or a numbered one with `ordered`.
 *
 * Takes `ReactNode[]` rather than children so a section cannot accidentally nest a paragraph
 * inside a list item and get two different vertical rhythms on the same page.
 */
export function List({ items, ordered }: { items: ReactNode[]; ordered?: boolean }) {
  return (
    <Box
      component={ordered ? 'ol' : 'ul'}
      sx={{ maxWidth: '78ch', pl: 3, mt: 0, mb: 2, '& li': { mb: 0.75 } }}
    >
      {items.map((item, index) => (
        <li key={index}>
          <Typography variant="body2" component="span" sx={{ lineHeight: 1.7 }}>
            {item}
          </Typography>
        </li>
      ))}
    </Box>
  );
}

/** Inline monospace, for a command, a path, an environment variable or a permission key. */
export function Code({ children }: { children: ReactNode }) {
  return (
    <Box
      component="code"
      className="identifier"
      sx={{
        fontSize: '0.8125rem',
        px: 0.5,
        py: 0.125,
        borderRadius: 0.5,
        bgcolor: 'action.hover',
        whiteSpace: 'nowrap',
      }}
    >
      {children}
    </Box>
  );
}

/**
 * A block of commands or configuration, meant to be copied.
 *
 * Wrapped in `ScrollX` rather than wrapped soft: a broken command line is a command that fails
 * when pasted, and a wrapped JSON block hides which brace closed what. Long lines scroll.
 */
export function Terminal({ children }: { children: string }) {
  return (
    <Box
      sx={{
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        bgcolor: 'action.hover',
        mb: 2,
        maxWidth: '78ch',
      }}
    >
      <ScrollX>
        <Box
          component="pre"
          className="identifier"
          sx={{ m: 0, px: 2, py: 1.5, fontSize: '0.8125rem', lineHeight: 1.6 }}
        >
          {children}
        </Box>
      </ScrollX>
    </Box>
  );
}

/** A callout for the one sentence in a section a reader must not skim past. */
export function Note({
  severity = 'info',
  children,
}: {
  severity?: 'info' | 'warning';
  children: ReactNode;
}) {
  return (
    <Alert severity={severity} variant="outlined" sx={{ maxWidth: '78ch', mb: 2 }}>
      <Typography variant="body2" sx={{ lineHeight: 1.7 }}>
        {children}
      </Typography>
    </Alert>
  );
}

/**
 * A term and what it means — the shape most of this page's reference material takes.
 *
 * A definition list rather than a table because the right-hand side is a sentence and the
 * left-hand side is one or two words: a two-column table gives that pairing a column width to
 * fight over at every breakpoint, and loses on a phone.
 */
export function Definitions({ items }: { items: { term: ReactNode; detail: ReactNode }[] }) {
  return (
    <Stack component="dl" sx={{ maxWidth: '78ch', m: 0, mb: 2, gap: 1.25 }}>
      {items.map((item, index) => (
        <Box key={index}>
          <Typography component="dt" variant="body2" sx={{ fontWeight: 600 }}>
            {item.term}
          </Typography>
          <Typography
            component="dd"
            variant="body2"
            sx={{ m: 0, color: 'text.secondary', lineHeight: 1.7 }}
          >
            {item.detail}
          </Typography>
        </Box>
      ))}
    </Stack>
  );
}
