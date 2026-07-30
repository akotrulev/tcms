import { Box, Button, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';

/**
 * A real 404 with a way out. A blank page for an unmatched URL leaves the user with nothing
 * but the back button, and stale links into a test repository are common — keys get pasted
 * into tickets and outlive the cases they named.
 */
export function NotFoundPage() {
  return (
    <Box sx={{ py: 8, textAlign: 'center' }} data-testid="NotFound">
      <title>Page not found · TCMS</title>
      <Typography variant="h1" sx={{ mb: 1 }}>
        Page not found
      </Typography>
      <Typography variant="body2" sx={{ color: 'text.secondary', mb: 3, maxWidth: '48ch', mx: 'auto' }}>
        That address doesn't match anything here. The case, plan, or run it pointed at may have been
        deleted or moved to another project.
      </Typography>
      <Stack direction="row" sx={{ gap: 1, justifyContent: 'center' }}>
        <Button component={RouterLink} to="/" variant="contained" data-testid="NotFound.HomeLink">
          Go to projects
        </Button>
      </Stack>
    </Box>
  );
}
