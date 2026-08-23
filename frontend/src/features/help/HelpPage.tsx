import { Box, Divider, Link, Stack, Typography } from '@mui/material';
import { useEffect, useRef } from 'react';
import { Link as RouterLink, Navigate, useParams } from 'react-router-dom';
import { PageHeader, Panel } from '../../components/PageHeader';
import { helpSections, findHelpSection, type HelpSection } from './sections';

/**
 * The user guide — one screen, one section at a time, reachable by every signed-in reader.
 *
 * ## Why a section per URL rather than one long page
 *
 * The obvious build is every section stacked on one route with anchor links. It was refused for a
 * reason this app has already paid for elsewhere: a single page carrying eleven sections is one
 * render of every table and every code block on it, including the role matrix's fifty-odd rows,
 * for a reader who came to find out how to install the CLI. A section per URL means the browser's
 * back button works between them, a link somebody pastes into a ticket lands on the thing they
 * meant, and the printed or scrolled length of any one screen stays something a person will read.
 *
 * `/help` redirects to the first section rather than rendering a landing page of its own. A
 * contents page whose only content is a list of links the rail already shows is a click the reader
 * pays for and learns nothing from.
 *
 * ## No permission gate, and that is the decision
 *
 * Every section describes capabilities some readers do not hold — the role matrix most of all.
 * Gating any of it would need a key, and a key invented for a help page would be seeded to nobody,
 * which is a refusal no administrator could grant their way out of. It also inverts the product's
 * own rule: this is the screen that exists to tell somebody what they are missing and what to ask
 * for, so it is the last screen that should hide it from them.
 */
export function HelpPage() {
  const { sectionId } = useParams<{ sectionId: string }>();

  if (!sectionId) return <Navigate to={`/help/${helpSections[0]!.id}`} replace />;

  const section = findHelpSection(sectionId);
  if (!section) return <Navigate to={`/help/${helpSections[0]!.id}`} replace />;

  return (
    <Stack sx={{ gap: 3 }} data-testid="HelpPage">
      <PageHeader
        testId="Help.Header"
        title={section.title}
        documentTitle={`Help — ${section.title}`}
        subtitle={section.summary}
        crumbs={[{ label: 'Help', to: `/help/${helpSections[0]!.id}` }, { label: section.title }]}
      />

      <Stack
        direction={{ xs: 'column', md: 'row' }}
        sx={{ gap: 3, alignItems: 'flex-start' }}
      >
        <Contents current={section.id} />
        <SectionBody section={section} />
      </Stack>
    </Stack>
  );
}

/**
 * The contents list.
 *
 * `position: sticky` only from `md` up: on a phone it sits above the prose and scrolls away, which
 * is right — a sticky block eating a third of a small viewport is worse than scrolling back up for
 * it. It is a `<nav>` with a name so a screen-reader user can jump straight to it, and the current
 * section is marked with `aria-current` rather than colour alone.
 */
function Contents({ current }: { current: string }) {
  return (
    <Box
      component="nav"
      aria-label="Help contents"
      data-testid="Help.Contents"
      sx={{
        width: { xs: '100%', md: 260 },
        flexShrink: 0,
        position: { md: 'sticky' },
        top: { md: 16 },
      }}
    >
      <Panel testId="Help.Contents.Panel" dense title="Contents">
        <Stack component="ul" sx={{ listStyle: 'none', m: 0, p: 1, gap: 0.25 }}>
          {helpSections.map((section) => {
            const active = section.id === current;
            return (
              <li key={section.id}>
                <Link
                  component={RouterLink}
                  to={`/help/${section.id}`}
                  aria-current={active ? 'page' : undefined}
                  data-testid={`Help.Contents.Link.${section.id}`}
                  underline="none"
                  sx={{
                    display: 'block',
                    px: 1.5,
                    py: 0.75,
                    borderRadius: 1,
                    fontSize: '0.875rem',
                    fontWeight: active ? 600 : 400,
                    color: active ? 'primary.main' : 'text.primary',
                    bgcolor: active ? 'action.selected' : 'transparent',
                    '&:hover': { bgcolor: 'action.hover' },
                  }}
                >
                  {section.title}
                </Link>
              </li>
            );
          })}
        </Stack>
      </Panel>
    </Box>
  );
}

/**
 * One section's prose.
 *
 * Focus moves to the heading on every section change. Without it, following a contents link
 * replaces the whole right-hand column while the keyboard stays parked in the list — so a
 * screen-reader user hears nothing and a keyboard user's next Tab continues down the contents
 * instead of into what they just asked for. `tabIndex={-1}` makes the heading focusable
 * programmatically without adding it to the tab order.
 */
function SectionBody({ section }: { section: HelpSection }) {
  const heading = useRef<HTMLHeadingElement>(null);
  const first = useRef(true);

  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    heading.current?.focus();
  }, [section.id]);

  return (
    <Box sx={{ flex: 1, minWidth: 0 }} data-testid={`Help.Section.${section.id}`}>
      <Panel testId="Help.Section.Panel">
        <Box sx={{ p: { xs: 2, md: 3 } }}>
          <Typography
            ref={heading}
            variant="h3"
            component="h2"
            tabIndex={-1}
            sx={{ mb: 2, outline: 'none' }}
          >
            {section.title}
          </Typography>
          {section.body}
        </Box>
      </Panel>

      <Divider sx={{ my: 3 }} />
      <Pager current={section.id} />
    </Box>
  );
}

/** Previous and next, so the guide can be read straight through without going back to Contents. */
function Pager({ current }: { current: string }) {
  const index = helpSections.findIndex((section) => section.id === current);
  const previous = index > 0 ? helpSections[index - 1] : undefined;
  const next = index < helpSections.length - 1 ? helpSections[index + 1] : undefined;

  return (
    <Stack
      direction="row"
      sx={{ justifyContent: 'space-between', gap: 2 }}
      data-testid="Help.Pager"
    >
      {previous ? (
        <Link component={RouterLink} to={`/help/${previous.id}`} variant="body2" data-testid="Help.Pager.Previous">
          ← {previous.title}
        </Link>
      ) : (
        <span />
      )}
      {next && (
        <Link component={RouterLink} to={`/help/${next.id}`} variant="body2" data-testid="Help.Pager.Next">
          {next.title} →
        </Link>
      )}
    </Stack>
  );
}
