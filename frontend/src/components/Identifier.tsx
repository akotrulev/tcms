import { Box, type SxProps, type Theme } from '@mui/material';
import type { ReactNode } from 'react';

/**
 * The design signature.
 *
 * Every identifier in the product — case key, suite key, version, release, build, SHA,
 * percentage — renders in IBM Plex Mono with tabular figures. This is not decoration: it
 * makes `TC-42` and `TC-402` distinguishable at a glance, and it makes keys and numbers
 * align down a column so a scanning eye can compare them. The `.identifier` class in
 * index.css carries the font; this component is the one place that class is applied.
 */
export function Identifier({
  children,
  variant = 'default',
  sx,
  testId,
}: {
  children: ReactNode;
  /** `muted` for secondary identifiers (build numbers, SHAs); `strong` for the page subject. */
  variant?: 'default' | 'muted' | 'strong';
  sx?: SxProps<Theme>;
  testId?: string;
}) {
  return (
    <Box
      component="span"
      className="identifier"
      data-testid={testId}
      sx={[
        {
          color:
            variant === 'muted'
              ? 'text.secondary'
              : variant === 'strong'
                ? 'text.primary'
                : 'inherit',
          fontWeight: variant === 'strong' ? 600 : 400,
          fontSize: variant === 'muted' ? '0.75rem' : 'inherit',
          whiteSpace: 'nowrap',
        },
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    >
      {children}
    </Box>
  );
}
