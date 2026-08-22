import { Box, Dialog, DialogContent, DialogTitle, Stack, Typography } from '@mui/material';
import { EXECUTION_SHORTCUTS, type ShortcutScope } from './executionKeys';

/**
 * What the keys do, on screen (§248).
 *
 * **Every row comes from `EXECUTION_SHORTCUTS`.** There is no key literal in this file and there
 * must not be one: a help panel that names its own keys is the second source of truth this
 * feature's whole design exists to avoid, and its failure mode is silent — a documented shortcut
 * that does nothing, or a working one nobody is told about.
 *
 * A dialog rather than a translucent sheet: it takes focus, `Escape` closes it, and it is
 * reachable by the button beside it. A keyboard feature whose help could only be opened by a
 * keyboard shortcut would be circular for exactly the people who most need it.
 */

const SCOPE_HEADINGS = {
  screen: 'Anywhere on this screen',
  notes: 'While the notes box has focus',
} satisfies Record<ShortcutScope, string>;

const SCOPES = Object.keys(SCOPE_HEADINGS) as ShortcutScope[];

export function ShortcutOverlay({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth data-testid="Execution.Shortcuts.Overlay">
      <DialogTitle>Keyboard shortcuts</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
          Every one of these has a button on the screen too — they are a faster route, never the
          only one. Nothing here fires while you are typing in a box.
        </Typography>

        {SCOPES.map((scope) => {
          const rows = EXECUTION_SHORTCUTS.filter((shortcut) => shortcut.scope === scope);
          if (rows.length === 0) return null;
          return (
            <Box key={scope} sx={{ mb: 2 }}>
              <Typography
                variant="caption"
                sx={{ color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.05em' }}
              >
                {SCOPE_HEADINGS[scope]}
              </Typography>
              <Stack component="dl" sx={{ m: 0, mt: 1, gap: 1 }}>
                {rows.map((shortcut) => (
                  <Stack
                    key={`${shortcut.scope}-${shortcut.key}`}
                    direction="row"
                    sx={{ alignItems: 'baseline', gap: 1.5 }}
                    data-testid="Execution.Shortcuts.Row"
                  >
                    <Box
                      component="dt"
                      sx={{
                        fontFamily: 'var(--font-mono)',
                        fontSize: '0.75rem',
                        fontWeight: 600,
                        minWidth: 40,
                        textAlign: 'center',
                        px: 0.75,
                        py: 0.25,
                        border: '1px solid',
                        borderColor: 'divider',
                        borderRadius: 0.5,
                        flexShrink: 0,
                      }}
                    >
                      {shortcut.display}
                    </Box>
                    <Typography component="dd" variant="body2" sx={{ m: 0 }}>
                      {shortcut.description}
                    </Typography>
                  </Stack>
                ))}
              </Stack>
            </Box>
          );
        })}
      </DialogContent>
    </Dialog>
  );
}
