import { Box, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { useProjects } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { formatCount } from '../../lib/format';

/**
 * Project picker. Reached explicitly, and also the fallback when a URL names a project the
 * user can't see — better to land here with a way forward than on a bare 403.
 */
export function ProjectsPage() {
  const projects = useProjects();

  return (
    <Box>
      <PageHeader testId="Projects.Header" title="Projects" subtitle="Pick a project to work in." />

      <DataRegion
        testId="Projects.List"
        state={{ ...projects, refetch: () => void projects.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'no-records',
          title: 'No projects',
          body: "You don't have access to any project yet. Ask an administrator to add you to one.",
        }}
      >
        {(rows) => (
          <Box
            sx={{
              display: 'grid',
              // Intrinsically responsive: reflows 3 → 2 → 1 with no media query, so it
              // cannot misalign at a breakpoint.
              gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 18rem), 1fr))',
              gap: 2,
            }}
          >
            {rows.map((project) => (
              <RouterLink
                key={project.id}
                to={`/projects/${project.id}`}
                data-testid="Projects.Card"
                style={{ textDecoration: 'none' }}
              >
                <Box
                  sx={{
                    height: '100%',
                    p: 2,
                    border: '1px solid',
                    borderColor: 'divider',
                    borderRadius: 1,
                    bgcolor: 'background.paper',
                    transition: 'border-color 120ms ease',
                    '&:hover': { borderColor: 'primary.main' },
                  }}
                >
                  <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1, mb: 0.5 }}>
                    <Identifier variant="strong">{project.key}</Identifier>
                    <Typography variant="h4" component="h2" sx={{ color: 'text.primary' }}>
                      {project.name}
                    </Typography>
                  </Stack>
                  <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1.5 }}>
                    {project.description}
                  </Typography>
                  <Stack direction="row" sx={{ gap: 2 }}>
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      <Identifier variant="muted">{formatCount(project.caseCount)}</Identifier> cases
                    </Typography>
                    {/* myRole is null when the caller reaches this project through a
                        COMPANY-scope grant, which is the contract's stated meaning and not a
                        missing value. Rendering the label anyway printed a dangling
                        "Your role:" — invisible in mock mode, where every fixture project
                        carries a role, and visible on the first real Company Admin session.
                        The line is omitted rather than filled with an invented label: the API
                        deliberately does not say what their company role is here, and guessing
                        it would be the UI making a claim the response never made. */}
                    {project.myRole !== null && (
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        Your role: {project.myRole}
                      </Typography>
                    )}
                  </Stack>
                </Box>
              </RouterLink>
            ))}
          </Box>
        )}
      </DataRegion>
    </Box>
  );
}
