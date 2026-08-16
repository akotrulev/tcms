import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownwardOutlined';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpwardOutlined';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import { useMemo, useState } from 'react';
import { useCases, useProjectTags, useSuites, type CaseReference } from '../../api/queries';
import type { TestCaseSummary, TestPlan } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { PermissionGate } from '../../components/PermissionGate';
import { Panel } from '../../components/PageHeader';
import { formatCount } from '../../lib/format';
import { ResolvedScope } from './ResolvedScope';
import {
  addReference,
  addSection,
  caseRowsIn,
  groupIds,
  moveSection,
  removeCaseRow,
  removeReference,
  removeSection,
  renameSection,
  typeInCaseRow,
  type GroupId,
  type PlanDraft,
  type Reference,
  type SectionDeletion,
} from './planDraft';

/**
 * The **Test cases** tab — the plan's scope, as the author wrote it (§97 §5, D7′, D8).
 *
 * ## Everything here is a REFERENCE, and that is the whole model
 *
 * A group holds case ids, suite ids and tags — never cases. Its membership is a query the server
 * re-runs on every read, so this plan grows when a referenced suite grows and when somebody tags a
 * case, with nothing written to the plan. TestNG's `<test>` block is the shape the user validated
 * the model against, and it is why D7's add-time snapshots were reversed.
 *
 * The screen therefore shows **what defines a group** ~~rather than what it currently catches~~, and
 * the count is beside it rather than instead of it.
 *
 * → **§103: it shows both.** The selector is still the model and still the only thing saved, but the
 * cases it currently resolves to are listed beneath it, read-only, re-read on every render and gone
 * the moment a reference comes off. That is the opposite of D7's add-time snapshot rather than a
 * partial return of it — nothing here or on the server remembers one of those case ids. See
 * `ResolvedScope`, and note that the suite arm **cannot** be a client-side filter on `suiteId`.
 *
 * ## The counts do not sum, and the tooltip says so
 *
 * A case can be named directly in *Create* and arrive through a suite in *Navigation*. The plan's
 * total dedupes; the sections' counts do not. §96 paid for this lesson one slice earlier: a reader
 * who can subtract will report a bug in a correct number unless the screen says which is which.
 *
 * **A served count is only shown while the tab is clean.** Every count on this screen is the
 * server's arithmetic over the references *it* holds; the moment the draft diverges, the honest
 * answer is that it will be recomputed on save. Rendering the old number beside a section the author
 * has just filled would be a number that is wrong in the direction of the work they just did.
 *
 * ## Four ways to fill a group, and only one of them writes anything
 *
 * A new case row is **created on Save** (`usePlanSave`'s ordered pair) and nothing before it, which
 * is what retires §93's create-on-click bar. The other three are references written with the plan
 * itself.
 */
export function PlanCasesTab({
  projectId,
  draft,
  plan,
  dirty,
  onChange,
}: {
  projectId: string;
  draft: PlanDraft;
  /** The plan as served — the only source of a resolved count. */
  plan: TestPlan;
  /** Whether this tab's draft has diverged from `plan`; see the header on counts. */
  dirty: boolean;
  onChange: (draft: PlanDraft) => void;
}) {
  const suites = useSuites(projectId);
  const tags = useProjectTags(projectId);
  const [newSection, setNewSection] = useState('');
  const [deleting, setDeleting] = useState<number | null>(null);

  /*
    A lookup for the case ids the plan already names, so a chip can say `TC-41` rather than a UUID.

    ~~A client-side join over the case list's first page, and its limit is stated rather than
    hidden: there is no by-ids read on the contract, so an id outside this page renders as an
    unresolved chip.~~ → **B8 built that read.** This asks for exactly the ids the plan names —
    the unsectioned arm plus every section's — so a chip is resolved because it was asked about,
    not because it happened to be in the project's first 200 cases by key.

    The ids come from the DRAFT rather than from the plan, so a case added in this session gets its
    name as soon as the answer lands, and the query key changes with them (`keys.cases` includes
    the filters), which is what makes that refetch happen at all.

    Still bounded, and the bound is the server's: 200 ids per request, dropped rather than refused.
    A plan naming more than 200 individual cases is one the model steers away from — that is what
    suites and tags are for — and the chip for a dropped id still removes, which needs the id and
    nothing else.
  */
  const referencedCaseIds = useMemo(() => {
    const ids = new Set<string>(draft.caseIds);
    for (const section of draft.sections) for (const id of section.caseIds) ids.add(id);
    return [...ids];
  }, [draft]);
  const catalogue = useCases(
    projectId,
    { id: referencedCaseIds, size: 200 },
    'key,asc',
  );
  const casesById = useMemo(() => {
    const index = new Map<string, TestCaseSummary>();
    for (const row of catalogue.data?.items ?? []) index.set(row.id, row);
    return index;
  }, [catalogue.data]);

  const suitesById = useMemo(() => {
    const index = new Map<string, { key: string; name: string }>();
    for (const suite of suites.data ?? []) index.set(suite.id, { key: suite.key, name: suite.name });
    return index;
  }, [suites.data]);

  const servedCount = (group: GroupId) =>
    group === null ? null : (plan.sections.find((s) => s.name === group)?.caseCount ?? null);

  return (
    <Panel
      title="Test cases"
      testId="PlanCases"
      actions={
        <Tooltip title="A case can be named directly in one section and arrive through a suite or a tag in another, so the sections' counts add up to more than the plan's total.">
          <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid="PlanCases.Total">
            {formatCount(plan.caseCount)} cases in scope
          </Typography>
        </Tooltip>
      }
    >
      <Stack sx={{ gap: 2 }}>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          Every reference below is resolved on each read: this plan grows when a referenced suite
          grows and when somebody tags a case. Nothing typed here is created until you press Save.
        </Typography>

        {dirty && (
          <Alert severity="info" data-testid="PlanCases.CountsStale">
            The counts are the server&rsquo;s, over the plan as it was loaded. They are recomputed
            when this plan is saved.
          </Alert>
        )}

        {groupIds(draft).map((group, position) => (
          <GroupCard
            // The name IS the identity — there are no section ids — so a rename remounts the card,
            // which is correct: it is a different section as far as the server is concerned.
            key={group ?? '__unsectioned__'}
            projectId={projectId}
            group={group}
            index={position - 1}
            draft={draft}
            count={dirty ? null : servedCount(group)}
            dirty={dirty}
            suitesById={suitesById}
            casesById={casesById}
            suiteOptions={suites.data ?? []}
            tagOptions={(tags.data ?? []).map((usage) => usage.tag)}
            onChange={onChange}
            onDelete={() => setDeleting(position - 1)}
          />
        ))}

        <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 1, alignItems: 'flex-start' }}>
          <TextField
            size="small"
            label="New section"
            value={newSection}
            onChange={(event) => setNewSection(event.target.value)}
            helperText="A sub-feature of this release — “Create an object”, “Navigation”."
            slotProps={{ htmlInput: { 'data-testid': 'PlanCases.NewSection' } }}
            sx={{ minWidth: 280 }}
          />
          <Button
            size="small"
            variant="outlined"
            data-testid="PlanCases.AddSection"
            disabled={newSection.trim() === ''}
            onClick={() => {
              onChange(addSection(draft, newSection.trim()));
              setNewSection('');
            }}
            sx={{ mt: 0.5 }}
          >
            Add section
          </Button>
        </Stack>
      </Stack>

      {deleting !== null && (
        <DeleteSectionDialog
          name={draft.sections[deleting]?.name ?? ''}
          onClose={() => setDeleting(null)}
          onConfirm={(disposition) => {
            onChange(removeSection(draft, deleting, disposition));
            setDeleting(null);
          }}
        />
      )}
    </Panel>
  );
}

/**
 * Deleting a section **asks what happens to its references** rather than guessing (§97 §5).
 *
 * The two answers differ by whether those cases stay in the plan at all, and both are defensible:
 * an author tidying their headings means one, an author cutting scope means the other. A delete that
 * chose silently would be right half the time and indistinguishable either way — the plan's total
 * moves in one case and not the other, on a screen where the totals do not sum.
 */
function DeleteSectionDialog({
  name,
  onClose,
  onConfirm,
}: {
  name: string;
  onClose: () => void;
  onConfirm: (disposition: SectionDeletion) => void;
}) {
  return (
    <Dialog open onClose={onClose} data-testid="PlanCases.DeleteSection">
      <DialogTitle>Delete “{name}”?</DialogTitle>
      <DialogContent>
        <DialogContentText>
          This section holds references — suites, tags and cases. Deleting the heading does not have
          to mean dropping them from the plan.
        </DialogContentText>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="PlanCases.DeleteSection.Cancel">
          Cancel
        </Button>
        <Button
          onClick={() => onConfirm('unsection')}
          data-testid="PlanCases.DeleteSection.Keep"
        >
          Keep them in the plan
        </Button>
        <Button
          color="error"
          onClick={() => onConfirm('remove')}
          data-testid="PlanCases.DeleteSection.Remove"
        >
          Remove them from the plan
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** One group: its heading, what defines it, and the four ways to add to it. */
function GroupCard({
  projectId,
  group,
  index,
  draft,
  count,
  dirty,
  suitesById,
  casesById,
  suiteOptions,
  tagOptions,
  onChange,
  onDelete,
}: {
  projectId: string;
  group: GroupId;
  /** The section's index in `draft.sections`; `-1` for the unsectioned group. */
  index: number;
  draft: PlanDraft;
  /** The server's resolved count, or null when the draft has moved past it. */
  count: number | null;
  /**
   * Whether this tab's draft has diverged from the plan as served.
   *
   * Passed as well as `count` because the two react to it in OPPOSITE directions, which is the
   * point worth not collapsing: the served count is the server's arithmetic over the plan it holds,
   * so it is hidden; the resolved list is computed from the DRAFT's references, so it stays and
   * changes its caption to say the plan does not hold them yet.
   */
  dirty: boolean;
  suitesById: Map<string, { key: string; name: string }>;
  casesById: Map<string, TestCaseSummary>;
  suiteOptions: { id: string; key: string; name: string }[];
  tagOptions: string[];
  onChange: (draft: PlanDraft) => void;
  onDelete: () => void;
}) {
  const section = index >= 0 ? draft.sections[index] : null;
  const references =
    section ?? { caseIds: draft.caseIds, suiteIds: draft.suiteIds, tags: draft.tags };
  const rows = caseRowsIn(draft, group);

  /*
    The two arms whose membership is a SERVER query, in the order they are drawn as chips. A case
    id is not one of them: it names exactly one case, that case is already fully drawn by its chip,
    and there is no by-ids read to resolve it with (`?q=` matches a key, not an id).
  */
  const collectionReferences: CaseReference[] = [
    ...references.suiteIds.map((id) => ({ kind: 'suite' as const, id })),
    ...references.tags.map((value) => ({ kind: 'tag' as const, value })),
  ];

  const add = (reference: Reference) => onChange(addReference(draft, group, reference));
  const remove = (reference: Reference) => onChange(removeReference(draft, group, reference));

  return (
    <Box
      data-testid={section ? 'PlanCases.Section' : 'PlanCases.Unsectioned'}
      sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1.5 }}
    >
      <Stack sx={{ gap: 1.5 }}>
        <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          {section ? (
            <TextField
              size="small"
              variant="standard"
              value={section.name}
              onChange={(event) => onChange(renameSection(draft, index, event.target.value))}
              error={section.name.trim() === ''}
              slotProps={{
                htmlInput: {
                  'data-testid': `PlanCases.SectionName.${index}`,
                  'aria-label': `Name of section ${index + 1}`,
                },
              }}
              sx={{ '& .MuiInputBase-input': { fontWeight: 600 } }}
            />
          ) : (
            <Typography variant="subtitle2">Not in a section</Typography>
          )}

          {count !== null && (
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              {formatCount(count)} cases
            </Typography>
          )}

          <Box sx={{ flex: 1 }} />

          {section && (
            <>
              <IconButton
                size="small"
                aria-label={`Move ${section.name} up`}
                data-testid={`PlanCases.MoveUp.${index}`}
                disabled={index === 0}
                onClick={() => onChange(moveSection(draft, index, -1))}
              >
                <ArrowUpwardIcon fontSize="small" />
              </IconButton>
              <IconButton
                size="small"
                aria-label={`Move ${section.name} down`}
                data-testid={`PlanCases.MoveDown.${index}`}
                disabled={index === draft.sections.length - 1}
                onClick={() => onChange(moveSection(draft, index, 1))}
              >
                <ArrowDownwardIcon fontSize="small" />
              </IconButton>
              <IconButton
                size="small"
                aria-label={`Delete section ${section.name}`}
                data-testid={`PlanCases.DeleteSection.${index}`}
                onClick={onDelete}
              >
                <DeleteOutlineIcon fontSize="small" />
              </IconButton>
            </>
          )}
        </Stack>

        {/* What defines this group — the references, never the cases they resolve to. */}
        <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
          {references.suiteIds.map((id) => (
            <Chip
              key={`suite-${id}`}
              size="small"
              variant="outlined"
              data-testid="PlanCases.SuiteChip"
              // The suite tree is served whole, so an unresolved id here means the list has not
              // arrived yet — never that the suite is missing. `…` says loading; a sentence about
              // a suite not being found would be a claim this component cannot make.
              label={`Suite ${suitesById.get(id)?.name ?? '…'}`}
              onDelete={() => remove({ kind: 'suite', id })}
            />
          ))}
          {references.tags.map((tag) => (
            <Chip
              key={`tag-${tag}`}
              size="small"
              variant="outlined"
              data-testid="PlanCases.TagChip"
              label={`Tag ${tag}`}
              onDelete={() => remove({ kind: 'tag', value: tag })}
            />
          ))}
          {references.caseIds.map((id) => {
            const known = casesById.get(id);
            return (
              <Chip
                key={`case-${id}`}
                size="small"
                variant="outlined"
                data-testid="PlanCases.CaseChip"
                label={
                  known ? (
                    <Stack direction="row" sx={{ gap: 0.75, alignItems: 'center' }}>
                      <Identifier>{known.key}</Identifier>
                      <span>{known.title}</span>
                    </Stack>
                  ) : (
                    // Since B8 this is a case the by-ids read did not answer for: deleted since
                    // the plan named it, or past the 200-id cap. It can still be removed, which
                    // is what the chip is for. Named honestly rather than shown as a UUID.
                    'A case this project no longer lists'
                  )
                }
                onDelete={() => remove({ kind: 'case', id })}
              />
            );
          })}
          {references.suiteIds.length + references.tags.length + references.caseIds.length === 0
            && rows.length <= 1 && (
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Nothing referenced yet.
              </Typography>
            )}
        </Stack>

        {/*
          …and what those references currently CATCH (§103). Read-only, resolved by the server on
          every read, and gone the moment a reference comes off. See `ResolvedScope` for why this
          does not reintroduce D7's add-time snapshot and why the suite arm cannot be a client-side
          filter on `suiteId`.
        */}
        <ResolvedScope
          projectId={projectId}
          group={group}
          references={collectionReferences}
          labelFor={(reference) =>
            reference.kind === 'suite'
              ? `Suite ${suitesById.get(reference.id)?.name ?? '…'}`
              : `Tag ${reference.value}`
          }
          namedCaseCount={references.caseIds.length}
          dirty={dirty}
        />

        <Stack direction={{ xs: 'column', md: 'row' }} sx={{ gap: 1 }}>
          <SuitePicker
            group={group}
            options={suiteOptions}
            chosen={references.suiteIds}
            onPick={(id) => add({ kind: 'suite', id })}
          />
          <TagPicker group={group} options={tagOptions} onPick={(tag) => add({ kind: 'tag', value: tag })} />
          <CasePicker
            projectId={projectId}
            group={group}
            onPick={(testCase) => add({ kind: 'case', id: testCase.id })}
          />
        </Stack>

        <NewCaseRows
          projectId={projectId}
          group={group}
          rows={rows}
          draft={draft}
          onChange={onChange}
        />
      </Stack>
    </Box>
  );
}

/** Suites already referenced are offered **disabled**, so nobody hunts for one that is already in. */
function SuitePicker({
  group,
  options,
  chosen,
  onPick,
}: {
  group: GroupId;
  options: { id: string; key: string; name: string }[];
  chosen: string[];
  onPick: (id: string) => void;
}) {
  return (
    <TextField
      select
      size="small"
      label="Add a suite"
      value=""
      onChange={(event) => onPick(event.target.value)}
      sx={{ minWidth: 200 }}
      data-testid={`PlanCases.AddSuite.${group ?? 'unsectioned'}`}
      helperText="Everything the suite resolves to, now and later."
    >
      {options.map((suite) => (
        <MenuItem key={suite.id} value={suite.id} disabled={chosen.includes(suite.id)}>
          {suite.name}
        </MenuItem>
      ))}
      {options.length === 0 && (
        <MenuItem value="" disabled>
          This project has no suites yet.
        </MenuItem>
      )}
    </TextField>
  );
}

/**
 * A tag, from the project's own vocabulary **or a new one**.
 *
 * `freeSolo` because tags are free text everywhere else in the product (§96) — the list is a
 * suggestion source, and a picker that refused an unlisted tag would make a plan unable to claim a
 * tag the team is about to start using.
 */
function TagPicker({
  group,
  options,
  onPick,
}: {
  group: GroupId;
  options: string[];
  onPick: (tag: string) => void;
}) {
  const [value, setValue] = useState('');

  return (
    <Autocomplete
      freeSolo
      size="small"
      options={options}
      inputValue={value}
      onInputChange={(_event, next) => setValue(next)}
      value={null}
      onChange={(_event, picked) => {
        const tag = (picked ?? '').trim();
        if (tag === '') return;
        onPick(tag);
        setValue('');
      }}
      sx={{ minWidth: 200 }}
      renderInput={(params) => (
        <TextField
          {...params}
          label="Add a tag"
          helperText="Every case carrying it, now and later."
          slotProps={{
            // `...params.slotProps` FIRST — §96's defect, whose fix is this line: an object that
            // merely adds a testid REPLACES the input wiring MUI put there.
            ...params.slotProps,
            htmlInput: {
              ...params.slotProps.htmlInput,
              'data-testid': `PlanCases.AddTag.${group ?? 'unsectioned'}`,
            },
          }}
        />
      )}
    />
  );
}

/** An existing case, searched by key or title — the server short-circuits a key to an exact lookup. */
function CasePicker({
  projectId,
  group,
  onPick,
}: {
  projectId: string;
  group: GroupId;
  onPick: (testCase: TestCaseSummary) => void;
}) {
  const [query, setQuery] = useState('');
  const term = query.trim();
  /*
    Queried with no term as well as with one, which is deliberate: the first twenty cases are a
    useful thing for a picker to open on, and the alternative — a control that stays empty until two
    characters are typed — reads as broken to somebody who does not yet know a key. `useCases` keeps
    the previous key's rows while the next load, so the list narrows rather than flashing empty.
  */
  const search = useCases(projectId, { q: term || undefined, size: 20 }, 'key,asc');
  const options = search.data?.items ?? [];

  return (
    <Autocomplete
      size="small"
      options={options}
      filterOptions={(all) => all}
      getOptionLabel={(option) => `${option.key} ${option.title}`}
      inputValue={query}
      onInputChange={(_event, next) => setQuery(next)}
      value={null}
      onChange={(_event, picked) => {
        if (!picked) return;
        onPick(picked);
        setQuery('');
      }}
      noOptionsText={search.isPending ? 'Searching…' : 'No case matches.'}
      sx={{ minWidth: 260, flex: 1 }}
      renderInput={(params) => (
        <TextField
          {...params}
          label="Add an existing case"
          helperText="By key or title. One case, named directly."
          slotProps={{
            ...params.slotProps,
            htmlInput: {
              ...params.slotProps.htmlInput,
              'data-testid': `PlanCases.AddCase.${group ?? 'unsectioned'}`,
            },
          }}
        />
      )}
    />
  );
}

/**
 * The first fill mechanism: **a case that does not exist yet, by title alone**.
 *
 * Three rules, all of them §97 §5's and all of them §94's step list one screen over:
 *
 *   - the list always ends in a **blank row**, and typing in the last one appends the next, so
 *     adding a case is a place to type rather than a button;
 *   - the append is `NO_FOCUS` in spirit — nothing steals the caret, because the row appears *while*
 *     the author is writing in the row above it;
 *   - **nothing is created until Save** (`usePlanSave`), which is what makes the trailing blank
 *     harmless and what retires §93's create-on-click bar.
 *
 * `case:create` disables the rows **with a reason** rather than hiding them (§31): a caller with
 * `plan:edit` and not `case:create` can still add existing cases, reorder sections and save.
 */
function NewCaseRows({
  projectId,
  group,
  rows,
  draft,
  onChange,
}: {
  projectId: string;
  group: GroupId;
  rows: { rowId: string; title: string }[];
  draft: PlanDraft;
  onChange: (draft: PlanDraft) => void;
}) {
  return (
    <PermissionGate projectId={projectId} permission="case:create">
      {({ allowed, reason }) => (
        <Stack sx={{ gap: 0.5 }}>
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            New cases {allowed ? '— created when you save this plan, filed in no suite.' : `— ${reason}`}
          </Typography>
          {rows.map((row, index) => (
            <Stack key={row.rowId} direction="row" sx={{ gap: 1, alignItems: 'center' }}>
              <TextField
                size="small"
                fullWidth
                placeholder={index === rows.length - 1 ? 'Write a case title…' : ''}
                value={row.title}
                disabled={!allowed}
                onChange={(event) => onChange(typeInCaseRow(draft, row.rowId, event.target.value))}
                slotProps={{
                  htmlInput: {
                    'data-testid': `PlanCases.NewCase.${group ?? 'unsectioned'}.${index}`,
                    'aria-label': `New case ${index + 1}`,
                  },
                }}
              />
              <IconButton
                size="small"
                aria-label={`Remove new case ${index + 1}`}
                data-testid={`PlanCases.RemoveNewCase.${group ?? 'unsectioned'}.${index}`}
                disabled={!allowed || rows.length === 1}
                onClick={() => onChange(removeCaseRow(draft, row.rowId))}
              >
                <DeleteOutlineIcon fontSize="small" />
              </IconButton>
            </Stack>
          ))}
        </Stack>
      )}
    </PermissionGate>
  );
}
