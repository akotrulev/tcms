import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { useProjects } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { formatCount, pluralNoun } from '../../lib/format';
import { ProjectCreateDialog } from './ProjectCreateDialog';

/**
 * Project picker. Reached explicitly, and also the fallback when a URL names a project the
 * user can't see — better to land here with a way forward than on a bare 403.
 */
export function ProjectsPage() {
  const projects = useProjects();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);

  return (
    <Box>
      <PageHeader
        testId="Projects.Header"
        title="Projects"
        subtitle="Pick a project to work in."
        actions={
          /*
            The app's only COMPANY-scope affordance, and therefore the only `PermissionGate` that
            asks the company question: `project:create` is held by Company Admin and above, and
            there is no project for it to be held on. Disabled with a reason rather than hidden —
            a Test Lead seeing a greyed "New project" learns the capability exists and who to ask;
            seeing nothing at all learns the product cannot do it, which is a lie.
          */
          <PermissionGate projectId="company" permission="project:create">
            {({ allowed }) => (
              <Button
                variant="contained"
                disabled={!allowed}
                onClick={() => setCreating(true)}
                data-testid="Projects.New"
              >
                New project
              </Button>
            )}
          </PermissionGate>
        }
      />

      {/*
        Conditionally rendered, so the form mounts fresh each time — see the dialog's own note on
        why it must not `reset()` itself.
      */}
      {creating && (
        <ProjectCreateDialog
          open
          onClose={() => setCreating(false)}
          /*
            Straight into the project the user just made. They created it to work in it, and the
            picker they would otherwise land back on now has one more card on it that they would
            have to find and click.
          */
          onCreated={(project) => void navigate(`/projects/${project.id}`)}
        />
      )}

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
                    {/*
                      The archived marker, and the reason the list is not filtered instead: the
                      server returns retired projects deliberately, because omitting them would
                      make one unreachable rather than marked — and this card is the only route
                      back to the screen that can un-archive it. `archived` was a required field
                      of this response from M0 and nothing in the SPA read it until §159, for the
                      honest reason that nothing could set it. Same chip decision as the docs tree
                      makes for an archived page.
                    */}
                    {project.archived && (
                      <Chip
                        size="small"
                        variant="outlined"
                        label="Archived"
                        sx={{ flexShrink: 0 }}
                        data-testid="Projects.Card.Archived"
                      />
                    )}
                  </Stack>
                  <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1.5 }}>
                    {project.description}
                  </Typography>
                  <Stack direction="row" sx={{ gap: 2 }}>
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      <Identifier variant="muted">{formatCount(project.caseCount)}</Identifier>{' '}
                      {pluralNoun(project.caseCount, 'case')}
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
