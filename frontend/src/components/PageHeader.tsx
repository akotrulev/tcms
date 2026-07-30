import { Box, Breadcrumbs, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { useEffect, type ReactNode } from 'react';
import { useAnnounce } from '../a11y/useAnnounce';

/**
 * One page-header treatment, one breadcrumb source, and a title on every route.
 *
 * The title is emitted with a plain `<title>` element: React 19 hoists it to `<head>`, so
 * this SPA needs no `react-helmet`. Browser tabs full of "TCMS" are unusable, and a title
 * per route is the cheapest fix in the app.
 *
 * The `<PageHeader>` name, the nav label, the breadcrumb segment, and the page heading all
 * carry the *same* words for a destination — that consistency is the signposting people
 * use to learn their way around.
 */

export interface Crumb {
  label: string;
  /** Omit on the final crumb — the current page is not a link to itself. */
  to?: string;
}

export function PageHeader({
  title,
  /** Browser-tab title. Defaults to `title`; pass a shorter form when the heading is long. */
  documentTitle,
  subtitle,
  crumbs,
  actions,
  meta,
  testId,
}: {
  title: ReactNode;
  documentTitle?: string;
  subtitle?: ReactNode;
  crumbs?: Crumb[];
  /** Primary and secondary actions for this page, right-aligned. */
  actions?: ReactNode;
  /** Identifiers and counts that belong with the heading rather than in the body. */
  meta?: ReactNode;
  testId: string;
}) {
  const tabTitle = documentTitle ?? (typeof title === 'string' ? title : undefined);

  /**
   * The route-change announcement is raised from here rather than from the routing hook.
   *
   * The reason is `lazy()`: when the pathname changes, the new page's chunk may still be
   * downloading, so nothing at the router level yet knows what the page is called. This header
   * mounts once the page has actually rendered, and it holds the title — so this is the first
   * moment an announcement can be truthful.
   */
  const announce = useAnnounce();
  useEffect(() => {
    if (tabTitle) announce(`${tabTitle} page loaded`);
  }, [tabTitle, announce]);

  return (
    <Box component="header" data-testid={testId} sx={{ mb: 3 }}>
      {tabTitle && <title>{`${tabTitle} · TCMS`}</title>}

      {crumbs && crumbs.length > 0 && (
        <Breadcrumbs
          aria-label="Breadcrumb"
          data-testid={`${testId}.Breadcrumbs`}
          sx={{ mb: 1, fontSize: '0.8125rem' }}
        >
          {crumbs.map((crumb) =>
            crumb.to ? (
              <RouterLink
                key={crumb.label}
                to={crumb.to}
                style={{ color: 'inherit' }}
                data-testid={`${testId}.Breadcrumb`}
              >
                {crumb.label}
              </RouterLink>
            ) : (
              <Typography
                key={crumb.label}
                variant="body2"
                sx={{ color: 'text.primary', fontWeight: 500 }}
                data-testid={`${testId}.Breadcrumb`}
              >
                {crumb.label}
              </Typography>
            ),
          )}
        </Breadcrumbs>
      )}

      <Stack
        direction={{ xs: 'column', md: 'row' }}
        sx={{ alignItems: { xs: 'stretch', md: 'flex-start' }, gap: 2 }}
      >
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography variant="h1" data-testid={`${testId}.Title`}>
            {title}
          </Typography>
          {meta && (
            <Stack
              direction="row"
              sx={{ alignItems: 'center', flexWrap: 'wrap', gap: 1.5, mt: 1 }}
              data-testid={`${testId}.Meta`}
            >
              {meta}
            </Stack>
          )}
          {subtitle && (
            <Typography variant="body2" sx={{ color: 'text.secondary', mt: 1, maxWidth: '80ch' }}>
              {subtitle}
            </Typography>
          )}
        </Box>

        {actions && (
          <Stack
            direction="row"
            sx={{ gap: 1, flexShrink: 0, flexWrap: 'wrap' }}
            data-testid={`${testId}.Actions`}
          >
            {actions}
          </Stack>
        )}
      </Stack>
    </Box>
  );
}

/**
 * One elevated surface per region, with hairline dividers *inside* it — the revision away
 * from "white cards with shadows on grey", which is the safe SaaS default this design
 * deliberately walked away from.
 */
export function Panel({
  title,
  actions,
  children,
  testId,
  dense,
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  testId?: string;
  dense?: boolean;
}) {
  return (
    <Box
      component="section"
      data-testid={testId}
      sx={{
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        bgcolor: 'background.paper',
        overflow: 'hidden',
      }}
    >
      {(title || actions) && (
        <Stack
          direction="row"
          sx={{
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 1,
            px: dense ? 1.5 : 2,
            py: dense ? 1 : 1.5,
            borderBottom: '1px solid',
            borderColor: 'divider',
          }}
        >
          {typeof title === 'string' ? (
            <Typography variant="h4" component="h2">
              {title}
            </Typography>
          ) : (
            title
          )}
          {actions && (
            <Stack direction="row" sx={{ gap: 0.5, alignItems: 'center' }}>
              {actions}
            </Stack>
          )}
        </Stack>
      )}
      <Box sx={{ p: dense ? 0 : 2 }}>{children}</Box>
    </Box>
  );
}
