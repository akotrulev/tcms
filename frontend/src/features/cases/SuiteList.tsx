import AddIcon from '@mui/icons-material/Add';
import MoreVertIcon from '@mui/icons-material/MoreVert';
import {
  Alert,
  Box,
  Button,
  Divider,
  IconButton,
  ListItemText,
  Menu,
  MenuItem,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { useSetSuiteStatus } from '../../api/queries';
import type { LifecycleStatus, Suite } from '../../api/types';
import { useCan } from '../../auth/useCan';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { formatCount } from '../../lib/format';
import { lifecycleLabels } from '../../lib/labels';
import { CreateSuiteDialog, DeleteSuiteDialog, RenameSuiteDialog } from './SuiteDialogs';

/**
 * The suite list — the repository's spine, and the primary way people navigate a thousand cases.
 * Selecting a suite scopes the case list to that suite's **resolved membership**: filed here,
 * added here individually, or carrying one of its tags.
 *
 * **This was a tree until §98, and the tree is the thing that went.** A suite is a named
 * collection, not a folder — the model the product now states — so there is no `parentId`, no
 * `path`, no rollup, no expand state, and no `SimpleTreeView`. What replaced them is a flat
 * `<ul>` of buttons, which is what the data is.
 *
 * Four behaviours died with it and none of them left a hole:
 *
 * - **The descendant-inclusive count.** A count is now exactly what the server sent, so the
 *   display cannot disagree with the click. The old rollup existed because a parent reading "0
 *   cases" over two hundred was useless; a flat suite that says 0 holds 0.
 * - **The orphan-surfacing rule.** §36's characterisation, promoted to real behaviour in phase 5:
 *   a suite whose parent was absent from the payload rendered nowhere, silently. With no parent
 *   there is nothing to be orphaned *from* — every row in the payload is a row on screen, which is
 *   the property that rule was reaching for.
 * - **Cycle guarding.** Same reason. It survives in `lib/classification.ts`, which still walks a
 *   real tree.
 * - **Controlled expansion**, and the MUI warning that came with it.
 *
 * The list still OWNS the suite writes — new, rename, delete — because a suite has no page of its
 * own to put them on: this is where they are visible, so this is where they are edited.
 *
 * **The actions are gated on the caller's permissions**, through `PermissionGate` — create and
 * rename on `suite:manage`, delete on `suite:delete`, which is the split the roles spec fixed and
 * this list must not re-collapse. They used to render for everyone and be refused by the server,
 * because `CurrentUser` carries no permissions; `GET /me/permissions` answers that question and
 * `useCan` reads it. The menu items are gated individually rather than the kebab as a whole:
 * ~~a Test Engineer holds `suite:manage` and not `suite:delete`, so the menu they get is live items
 * beside one greyed one~~ → **`V52`, 2026-08-15: a Test Engineer holds both.** The per-item gating
 * stays, and the reason it stays is the general one rather than that example — a Viewer still sees
 * live items beside greyed ones, and a disabled kebab would claim they may not touch suites at all.
 * The keys remain split so a company that wants them separated has them.
 */

/**
 * Deterministic order, by name then key, and it is now the **only** ordering decision this
 * component makes.
 *
 * The endpoint's order is not relied on — `ui-rules` wants a deterministic sort with a unique
 * tiebreaker on every list, and `key` is unique per company. It is done here rather than left to
 * the server because the old tree sorted siblings the same way, so leaving it out would be a
 * visible reshuffle presented as a flattening.
 */
function ordered(suites: Suite[]): Suite[] {
  return [...suites].sort((a, b) => a.name.localeCompare(b.name) || a.key.localeCompare(b.key));
}

/**
 * §6g's ladder, from each status, with the key each edge costs.
 *
 * **The permission column mirrors the server's `LifecycleLadder.permissionFor` and is a disabling
 * hint, never the gate** — the same relationship `planMoves` has to `PlanLifecycle`. Getting it
 * wrong here shows a control that 403s, which is §31's failure and not a security one; the server
 * decides, from a status only it holds.
 *
 * **The rule in one sentence, since §208: `suite:approve` buys a sign-off, and it buys moving a
 * folder that has one.** Every approve-keyed row below is therefore an `APPROVED` row or an
 * `APPROVED` target — the reading a target-decides rule gets wrong is that leaving an approval is
 * an approval act whatever the target is called.
 *
 * ~~Deprecating costs `suite:approve` from every state, because retiring is the soft form of a
 * delete that Test Engineer is withheld from outright.~~ → **superseded in §208.** `suite:delete`
 * *removes* the folder; deprecating keeps it, keeps its members and can be undone, and it is the
 * only way out of a folder that cannot be deleted while it holds cases. Retiring a draft folder is
 * the same kind of act as renaming one.
 *
 * `satisfies Record<LifecycleStatus, …>` so a fifth status is a compile error here rather than a
 * folder with no moves at all.
 */
const suiteMoves = {
  DRAFT: [
    { target: 'IN_REVIEW', label: 'Submit for review', permission: 'suite:manage' },
    { target: 'DEPRECATED', label: 'Deprecate…', permission: 'suite:manage' },
  ],
  IN_REVIEW: [
    { target: 'APPROVED', label: 'Approve', permission: 'suite:approve' },
    { target: 'DRAFT', label: 'Return to draft', permission: 'suite:manage' },
    { target: 'DEPRECATED', label: 'Deprecate…', permission: 'suite:manage' },
  ],
  APPROVED: [
    { target: 'DRAFT', label: 'Return to draft', permission: 'suite:approve' },
    { target: 'DEPRECATED', label: 'Deprecate…', permission: 'suite:approve' },
  ],
  // Only one way back, and it lands on DRAFT: approval attaches to reviewed content by a named
  // person, so a retired folder returning straight to APPROVED would be a sign-off nobody gave.
  //
  // `suite:manage`, symmetric with the retire above it (§208): nothing is unmade coming back — the
  // sign-off, if there was one, ended at the retirement and was ended by somebody holding
  // `suite:approve`. Reviving at the approve key would make retiring a one-way door for exactly the
  // caller who may now walk through it.
  DEPRECATED: [{ target: 'DRAFT', label: 'Restore to draft', permission: 'suite:manage' }],
} as const satisfies Record<
  LifecycleStatus,
  readonly { target: LifecycleStatus; label: string; permission: string }[]
>;

/** Which dialog is open, and on which suite. One value, so two cannot be open at once. */
type Editing =
  | { kind: 'create' }
  | { kind: 'rename'; suite: Suite }
  | { kind: 'delete'; suite: Suite }
  | null;

export function SuiteList({
  suites,
  selectedSuiteId,
  onSelect,
  projectId,
}: {
  suites: Suite[];
  selectedSuiteId: string | undefined;
  onSelect: (suiteId: string | undefined) => void;
  projectId: string;
}) {
  const rows = ordered(suites);
  const gate = useCan(projectId);
  const [editing, setEditing] = useState<Editing>(null);
  const [menuFor, setMenuFor] = useState<{ anchor: HTMLElement; suite: Suite } | null>(null);
  const setStatus = useSetSuiteStatus(projectId);
  const [statusError, setStatusError] = useState<string | null>(null);

  /**
   * Moves one folder along the ladder.
   *
   * The refusal renders **in the rail**, not as a toast: both sentences the server can answer are
   * about the folder the menu was just open on — "only a test lead can approve" and "return this
   * suite to draft first" — and a notification detached from the tree names neither. Hence
   * `handled` on the hook.
   */
  const move = (suite: Suite, target: LifecycleStatus) => {
    setStatusError(null);
    setStatus.mutate(
      { suiteId: suite.id, status: target },
      {
        onError: (failure: unknown) =>
          setStatusError(`${suite.name}: ${describeError(failure).body}`),
      },
    );
  };

  /*
    No expansion state, and its absence is the §98 change with the most code behind it.

    It was `expandedItems` on a `SimpleTreeView`, seeded once from the ancestors of the selection
    so a deep-linked `?suiteId=` arrived with its context open, and re-expanded by hand on every
    create so a suite made inside a collapsed folder did not land invisible. All of that answered
    one question — *is the selected row on screen?* — and on a flat list the answer is always yes.
  */

  const renderRow = (suite: Suite) => (
    <Box
      component="li"
      key={suite.id}
      sx={{ listStyle: 'none' }}
    >
      <Stack
        direction="row"
        sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 1, pr: 0.5 }}
      >
        {/*
          The row IS the select control, and it is a real <button> rather than a div with an
          onClick — the tree item used to be one and the flattening must not lose the keyboard.
          "All cases" above it is built the same way, for the same reason.
        */}
        <Box
          component="button"
          type="button"
          data-testid="SuiteList.Item"
          aria-current={suite.id === selectedSuiteId ? 'true' : undefined}
          onClick={() => onSelect(suite.id)}
          title={suite.name}
          sx={{
            flex: 1,
            minWidth: 0,
            display: 'block',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            textAlign: 'left',
            px: 1.5,
            py: 0.75,
            border: 'none',
            borderRadius: 1,
            cursor: 'pointer',
            font: 'inherit',
            fontSize: '0.8125rem',
            color: 'text.primary',
            fontWeight: suite.id === selectedSuiteId ? 600 : 400,
            bgcolor: suite.id === selectedSuiteId ? 'action.selected' : 'transparent',
            '&:hover': { bgcolor: 'action.hover' },
          }}
        >
          {suite.name}
        </Box>
        <Stack direction="row" sx={{ alignItems: 'center', gap: 0.5, flexShrink: 0 }}>
          {/*
            Rendered only when the suite is NOT a draft, and that is a deliberate asymmetry
            rather than a hidden state: DRAFT is where every suite starts and where most stay,
            so a badge on all of them costs the width the name needs in a narrow rail and says
            nothing. The three that mean something — under review, signed off, retired — are
            exactly the ones worth the space, and the menu shows the status either way.
          */}
          {suite.lifecycleStatus !== 'DRAFT' && (
            <Box
              component="span"
              data-testid="SuiteList.Item.Status"
              title={lifecycleLabels[suite.lifecycleStatus]}
              sx={{
                fontSize: '0.625rem',
                textTransform: 'uppercase',
                letterSpacing: '0.04em',
                color: suite.lifecycleStatus === 'DEPRECATED' ? 'text.disabled' : 'text.secondary',
                border: '1px solid',
                borderColor: 'divider',
                borderRadius: 0.5,
                px: 0.5,
              }}
            >
              {lifecycleLabels[suite.lifecycleStatus]}
            </Box>
          )}
          <Tooltip
            // §96: a count is RESOLVED membership — filed, added individually, or carrying one of
            // the suite's tags — so a case can be counted by several suites at once and these
            // numbers do not add up to the project's case count. Saying so on the number itself is
            // the only place a reader meets the discrepancy; leaving it to be found by subtraction
            // is how a correct number reads as a bug.
            //
            // §98 removed "and everything under it" from this sentence, and that is the whole
            // difference: the number is now `Suite.caseCount` verbatim, with nothing added to it
            // on this side. Three arms still overlap, so the caveat stays.
            title="Cases in this suite — filed here, added here, or carrying one of its tags. A case can belong to several suites, so these do not sum to the project total."
            enterDelay={600}
          >
            <Box
              component="span"
              className="identifier"
              sx={{ fontSize: '0.6875rem', color: 'text.secondary' }}
            >
              {formatCount(suite.caseCount)}
            </Box>
          </Tooltip>
          {/*
            One kebab per row rather than three inline buttons: the rail is narrow, and three
            icons per row would crowd out the name the row exists to show. No `stopPropagation`
            any more — the select target is the sibling <button>, not an ancestor of this one, so
            there is no longer a click to stop from reaching it.
          */}
          <IconButton
            size="small"
            aria-label={`Actions for ${suite.name}`}
            data-testid="SuiteList.ItemMenu"
            onClick={(event) => setMenuFor({ anchor: event.currentTarget, suite })}
            sx={{ p: 0.25 }}
          >
            <MoreVertIcon sx={{ fontSize: '1rem' }} />
          </IconButton>
        </Stack>
      </Stack>
    </Box>
  );

  return (
    <Box>
      {/*
        Dismissible, and above the list rather than inside the menu, which is closed by the time the
        server answers. Both sentences name a suite, so the suite's name is prefixed — a bare
        "return this suite to draft first" in a rail of thirty names none of them.
      */}
      {statusError && (
        <Alert
          severity="warning"
          onClose={() => setStatusError(null)}
          data-testid="SuiteList.StatusError"
          sx={{ mx: 1.5, mb: 1, py: 0, fontSize: '0.75rem' }}
        >
          {statusError}
        </Alert>
      )}
      {/*
        No heading here. The panel this list lives in is already titled "Suites", and an overline
        repeating the word directly beneath it was the app's clearest case of an accessory that
        earns nothing — two labels for one thing, one of them shouting. The row survives because
        the action does; the label does not.
      */}
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'flex-end', px: 1.5, pb: 0.5 }}>
        {/*
          One create, with no target to choose. It used to create under the CURRENT selection and
          say so in its label, because a New button that silently picks a parent is how a folder
          ends up three levels from where its author meant it. §98 removes the parent, so the
          hazard and the label that guarded against it both go.
        */}
        <PermissionGate projectId={projectId} permission="suite:manage">
          {({ allowed }) => (
            <Button
              size="small"
              startIcon={<AddIcon />}
              disabled={!allowed}
              onClick={() => setEditing({ kind: 'create' })}
              data-testid="SuiteList.NewSuite"
            >
              New
            </Button>
          )}
        </PermissionGate>
      </Stack>

      {/*
        "All cases" is a real selection, not the absence of one — it gives the user an
        explicit way back out of a suite filter without hunting for a Clear button.
      */}
      <Box
        component="button"
        type="button"
        onClick={() => onSelect(undefined)}
        data-testid="SuiteList.AllCases"
        aria-current={selectedSuiteId ? undefined : 'true'}
        sx={{
          display: 'flex',
          width: '100%',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 1,
          px: 1.5,
          py: 0.75,
          border: 'none',
          borderRadius: 1,
          cursor: 'pointer',
          font: 'inherit',
          fontSize: '0.8125rem',
          textAlign: 'left',
          color: 'text.primary',
          fontWeight: selectedSuiteId ? 400 : 600,
          bgcolor: selectedSuiteId ? 'transparent' : 'action.selected',
          '&:hover': { bgcolor: 'action.hover' },
        }}
      >
        All cases
      </Box>

      {/*
        A real <ul>, not a styled <div> stack: a screen reader announces "list, 12 items", which
        is the one affordance the TreeView gave for free and a div would silently drop.
      */}
      <Box component="ul" data-testid="SuiteList" sx={{ m: 0, mt: 0.5, p: 0 }}>
        {rows.map(renderRow)}
      </Box>

      {rows.length === 0 && (
        <Stack spacing={1} sx={{ px: 1.5, py: 2 }}>
          {/*
            The empty state's copy is gated too, not just its button. "This is the first thing to
            make" beside a button they cannot press is an instruction the reader cannot follow —
            an empty state that names the wrong next step is worse than one that names none.
          */}
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            {gate.can('suite:manage')
              ? 'No suites yet. A case needs a suite to live in, so this is the first thing to make.'
              : 'No suites yet. A case needs a suite to live in — ask a project lead to create the first one.'}
          </Typography>
          <PermissionGate projectId={projectId} permission="suite:manage">
            {({ allowed }) => (
              <Button
                size="small"
                variant="contained"
                startIcon={<AddIcon />}
                disabled={!allowed}
                onClick={() => setEditing({ kind: 'create' })}
                data-testid="SuiteList.Empty.NewSuite"
                sx={{ alignSelf: 'flex-start' }}
              >
                New suite
              </Button>
            )}
          </PermissionGate>
        </Stack>
      )}

      <Menu
        open={menuFor !== null}
        anchorEl={menuFor?.anchor ?? null}
        onClose={() => setMenuFor(null)}
        data-testid="SuiteList.Menu"
      >
        {/*
          The way into the suite's execution history — and until §52 there was no way at all: the
          route and the page had existed since Phase 1 with nothing anywhere linking to them, so
          the only way to reach the screen was to type its URL.

          First in the menu and ungated, because it is the only READ here and it is the item most
          people want. A folder's history needs no permission beyond seeing the project, which is
          already true of anyone looking at this tree — wrapping it in a PermissionGate would gate
          it on nothing and imply otherwise.
        */}
        <MenuItem
          component={RouterLink}
          to={menuFor ? `/projects/${projectId}/suites/${menuFor.suite.id}/history` : '#'}
          data-testid="SuiteList.Menu.History"
          onClick={() => setMenuFor(null)}
        >
          <ListItemText>Execution history</ListItemText>
        </MenuItem>
        {/*
          `New suite inside` is GONE, not disabled (§98). Disable-don't-hide is `ui-rules`' rule
          about an action the *state* forbids — a control the reader could earn by changing
          something. There is no state in which a suite can be created inside another one any more,
          so a greyed row would advertise a capability the product does not have and invite a
          reader to hunt for the permission that unlocks it. The `New` button above creates the
          only kind of suite there is.
        */}
        <PermissionGate projectId={projectId} permission="suite:manage" wrapper="block">
          {({ allowed }) => (
            <MenuItem
              disabled={!allowed}
              data-testid="SuiteList.Menu.Rename"
              onClick={() => {
                if (menuFor) setEditing({ kind: 'rename', suite: menuFor.suite });
                setMenuFor(null);
              }}
            >
              <ListItemText>Rename…</ListItemText>
            </MenuItem>
          )}
        </PermissionGate>
        <Divider />
        {/*
          The ladder. The moves SWAP rather than accumulate, as on the plan: a folder offers only
          the edges it actually has, because offering one the server refuses with a 409 is what
          §31 exists to stop. Each is gated on its own key, so a Test Engineer sees "Submit for
          review" live beside a greyed "Deprecate…" — which is the shape of the rule, visible.
        */}
        {menuFor
          && suiteMoves[menuFor.suite.lifecycleStatus].map((option) => (
            <PermissionGate
              key={option.target}
              projectId={projectId}
              permission={option.permission}
              wrapper="block"
            >
              {({ allowed }) => (
                <MenuItem
                  disabled={!allowed || setStatus.isPending}
                  data-testid={`SuiteList.Menu.Move.${option.target}`}
                  onClick={() => {
                    if (menuFor) move(menuFor.suite, option.target);
                    setMenuFor(null);
                  }}
                >
                  <ListItemText>{option.label}</ListItemText>
                </MenuItem>
              )}
            </PermissionGate>
          ))}
        <Divider />
        <PermissionGate projectId={projectId} permission="suite:delete" wrapper="block">
          {({ allowed }) => (
            <MenuItem
              disabled={!allowed}
              data-testid="SuiteList.Menu.Delete"
              onClick={() => {
                if (menuFor) setEditing({ kind: 'delete', suite: menuFor.suite });
                setMenuFor(null);
              }}
            >
              {/* Danger-coloured here as well as on the dialog's confirm, so the destructive item
                  is distinguishable before it is chosen and not only after. Muted when the item
                  is disabled, so a greyed row doesn't shout in red about something it won't do. */}
              <ListItemText sx={{ color: allowed ? 'error.main' : undefined }}>Delete…</ListItemText>
            </MenuItem>
          )}
        </PermissionGate>
      </Menu>

      {editing?.kind === 'create' && (
        <CreateSuiteDialog
          open
          onClose={() => setEditing(null)}
          projectId={projectId}
          // Selecting the new suite is the useful next step: the user made it in order to put
          // something in it, and the case list beside this rail is now scoped to it. Nothing has
          // to be expanded first any more — a new row is a visible row.
          onCreated={(created) => onSelect(created.id)}
        />
      )}
      {editing?.kind === 'rename' && (
        <RenameSuiteDialog
          open
          onClose={() => setEditing(null)}
          projectId={projectId}
          suite={editing.suite}
          onRenamed={() => setEditing(null)}
        />
      )}
      {editing?.kind === 'delete' && (
        <DeleteSuiteDialog
          open
          onClose={() => setEditing(null)}
          projectId={projectId}
          suite={editing.suite}
          // A deleted folder must not stay selected — the case list would filter on a suite that
          // is gone and read as "this project has no cases".
          onDeleted={() => {
            if (selectedSuiteId === editing.suite.id) onSelect(undefined);
          }}
        />
      )}
    </Box>
  );
}
