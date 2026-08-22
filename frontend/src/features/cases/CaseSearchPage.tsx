import SearchOutlinedIcon from '@mui/icons-material/SearchOutlined';
import {
  Box,
  Chip,
  InputAdornment,
  Link,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useSearchParams } from 'react-router-dom';
import { Link as RouterLink } from 'react-router-dom';
import { isSearchable, useCaseSearch, useProjects } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { formatCount, formatDateTime } from '../../lib/format';
import { automationLabels, lifecycleLabels, priorityLabels } from '../../lib/labels';

const TestId = 'CaseSearch';
const DEFAULT_SIZE = 25;

/**
 * Find a case across every project the caller can see (§249).
 *
 * <h2>Why the route is `/search` and not under `/projects/:projectId`</h2>
 *
 * `MyWorkPage`'s argument, and it transfers exactly: the project segment is a real navigation
 * boundary, every screen under it reads its project for breadcrumbs, the rail and permission
 * scoping — and **this screen has no project to read**. Nesting it would put an arbitrary project
 * in the URL of a list that spans all of them, and switching projects in the rail would appear to
 * change a result set that cannot change. The whole premise here is that the reader does not know
 * which project holds the case.
 *
 * <h2>The empty state is TWO states, and this is the row that matters</h2>
 *
 * "No case matches" and "you can see no projects" are the **same HTTP response** — `200` with
 * `total: 0`, because the server refuses to make them distinguishable (§6t: a 403 would answer the
 * question the empty answer exists to withhold). They are not the same sentence. Rendering the
 * first for a member holding no grants makes a permissions problem look like a product with no
 * data in it, and the person waits for data that will never arrive instead of asking an
 * administrator for a grant. So the page asks `useProjects` how many projects the reader can open
 * and says which emptiness this is.
 *
 * <h2>What it does not do</h2>
 *
 * **No request until the search is answerable.** The server 400s a search naming neither `q`,
 * `ownerId` nor `tag`, and 400s a one-character `q`; `isSearchable` is the same predicate the
 * hook's `enabled` uses, so an empty box asks for nothing rather than rendering a control and then
 * being refused (`ui-rules` §4).
 *
 * **No client-side paging.** The pager is driven by the server's `total`, which §249 exists to
 * make trustworthy — it is computed over the same narrowed set as the page. A client that
 * re-derived the count would discard the one number the area was built for.
 *
 * **`Table`, not `DataGrid`** — §241 measured why: DataGrid does not render in jsdom, so a
 * component test of this screen is only possible on a `Table`.
 */
export function CaseSearchPage() {
  const [params, setParams] = useSearchParams();

  const q = params.get('q') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);
  const size = Number(params.get('size') ?? '') || DEFAULT_SIZE;

  const filters = { q: q.trim() || undefined, page, size };
  const results = useCaseSearch(filters);

  /*
    Only to tell the two empty states apart — never to filter, and never to narrow the search. The
    server's own gate decides what is visible; this is the same question asked for a sentence.
  */
  const projects = useProjects();
  const readerHasNoProjects = projects.data?.length === 0;

  const setParam = (key: string, value: string | undefined) => {
    const next = new URLSearchParams(params);
    if (value === undefined || value === '') next.delete(key);
    else next.set(key, value);
    // Any change to the query resets to the first page: page 3 of the previous search is not a
    // page of this one, and keeping it is how a search lands on an empty page that looks broken.
    if (key !== 'page') next.delete('page');
    setParams(next, { replace: true });
  };

  return (
    <Box>
      <PageHeader
        testId={`${TestId}.Header`}
        title="Search cases"
        documentTitle="Search cases"
        subtitle="Across every project you can open."
      />

      <Box>
        <TextField
          fullWidth
          value={q}
          onChange={(event) => setParam('q', event.target.value)}
          placeholder="Title, description, or a case key such as TC-412"
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchOutlinedIcon fontSize="small" />
                </InputAdornment>
              ),
            },
            htmlInput: { 'data-testid': `${TestId}.Query`, 'aria-label': 'Search cases' },
          }}
          sx={{ mb: 2 }}
        />

        {!isSearchable(filters) ? (
          /*
            Not an EmptyState: nothing was searched for, so nothing is empty. Saying "no results"
            here would report a result for a question nobody asked.
          */
          <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid={`${TestId}.Prompt`}>
            {q.trim().length === 1
              ? 'Type at least two characters — a single letter matches most of the repository.'
              : 'Type something to search for.'}
          </Typography>
        ) : (
          <DataRegion
            testId={TestId}
            state={results}
            isEmpty={(data) => data.items.length === 0}
            empty={
              readerHasNoProjects
                ? {
                    kind: 'by-design',
                    title: 'You cannot open any projects yet',
                    body:
                      'A search only covers projects you have been granted access to, and you '
                      + 'have none. Ask an administrator for access to a project.',
                    testId: `${TestId}.NoProjects`,
                  }
                : {
                    kind: 'no-filter-match',
                    title: 'No case matches',
                    body: 'Try fewer words, or a case key such as TC-412.',
                    onClearFilters: () => setParam('q', undefined),
                    testId: `${TestId}.NoMatch`,
                  }
            }
          >
            {(data) => (
              <>
                <Typography
                  variant="body2"
                  sx={{ color: 'text.secondary', mb: 1 }}
                  data-testid={`${TestId}.Count`}
                >
                  {formatCount(data.total)} matching {data.total === 1 ? 'case' : 'cases'}
                </Typography>

                <TableContainer sx={{ overflowX: 'auto' }}>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell sx={{ color: 'text.secondary', width: 110 }}>Case</TableCell>
                        <TableCell sx={{ color: 'text.secondary' }}>Title</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 210 }}>Project</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 110 }}>Status</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 130 }}>Automation</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 90 }}>Priority</TableCell>
                        <TableCell sx={{ color: 'text.secondary', width: 150 }}>Updated</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {data.items.map((hit) => (
                        <TableRow key={hit.id} data-testid={`${TestId}.Row`} hover>
                          <TableCell>
                            <Identifier>{hit.key}</Identifier>
                          </TableCell>
                          <TableCell>
                            <Link
                              component={RouterLink}
                              to={`/projects/${hit.projectId}/cases/${hit.id}`}
                              data-testid={`${TestId}.Row.Link`}
                            >
                              {hit.title}
                            </Link>
                          </TableCell>
                          {/*
                            The project is part of every row rather than a column somebody can
                            hide: the reader does not know which project a hit is from, and that
                            is the premise of the screen.
                          */}
                          <TableCell>
                            <Stack direction="row" sx={{ alignItems: 'center', gap: 0.75, minWidth: 0 }}>
                              <Identifier variant="muted">{hit.projectKey}</Identifier>
                              <Typography variant="body2" noWrap title={hit.projectName}>
                                {hit.projectName}
                              </Typography>
                            </Stack>
                          </TableCell>
                          <TableCell>
                            <Chip size="small" label={lifecycleLabels[hit.lifecycleStatus]} />
                          </TableCell>
                          <TableCell>{automationLabels[hit.automationStatus]}</TableCell>
                          <TableCell>{priorityLabels[hit.priority]}</TableCell>
                          <TableCell>{formatDateTime(hit.updatedAt)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>

                {/* Driven by the server's total — see the header. */}
                <TablePagination
                  component="div"
                  count={data.total}
                  page={data.page}
                  rowsPerPage={data.size}
                  rowsPerPageOptions={[25, 50, 100]}
                  onPageChange={(_, next) => setParam('page', String(next))}
                  onRowsPerPageChange={(event) => setParam('size', event.target.value)}
                  data-testid={`${TestId}.Pager`}
                />
              </>
            )}
          </DataRegion>
        )}
      </Box>
    </Box>
  );
}
