import { Button, ListItemText, Menu, MenuItem, Tooltip } from '@mui/material';
import ArrowDropDownIcon from '@mui/icons-material/ArrowDropDown';
import { useState } from 'react';
import type { LinkRelationship } from '../api/types';
import { linkRelationshipHelp, linkRelationshipLabels } from '../lib/labels';

const CHOICES = Object.keys(linkRelationshipLabels) as LinkRelationship[];

/**
 * What a traceability link claims, and — since §174 — the control that corrects it.
 *
 * Shared by both link surfaces for {@link UnlinkDialog}'s reason, one slice later: a link's
 * relationship is corrected on the documentation page under `doc:edit` and on the case under
 * `case:edit`, and §173 is the section that paid for letting one act acquire two spellings. This
 * one is worth sharing twice over, because the *reading* has to match too — the three values are
 * not synonyms, and a screen that spells them its own way is how a coverage number stops meaning
 * one thing.
 *
 * ## A menu, and not a `<select>` — but not for `ui-rules` §3's reason
 *
 * That rule forbids an editable `Status` dropdown because it bypasses a transition's side effects
 * and its gate. This is not a lifecycle state: nothing fires, nothing is stamped but the audit, and
 * there is no illegal move between the three. What it is instead is a **claim**, and the reason it
 * gets a menu rather than a bare select is that the three need their sentences beside them at the
 * moment of choosing — `VERIFIES` is counted as coverage and the other two are not, which is the
 * whole content of the decision and is invisible from the words alone.
 *
 * ## Disabled, not hidden (§31)
 *
 * A reader without the key sees the claim and sees that it is the kind of thing that can be
 * changed. The tooltip carries the current value's meaning either way, so the chip is informative
 * before it is interactive.
 */
export function LinkRelationshipControl({
  value,
  onChange,
  disabled,
  pending,
  testId,
}: {
  value: LinkRelationship;
  onChange: (next: LinkRelationship) => void;
  disabled: boolean;
  pending: boolean;
  testId: string;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  return (
    <>
      <Tooltip title={linkRelationshipHelp[value]}>
        {/* A span, because a disabled button fires no events and MUI's tooltip needs something to
            listen to — so the meaning of the value would be unreachable to the reader least able
            to act on it. `TableRowsEditor` records the same trap. */}
        <span>
          <Button
            /*
              A Button and not a Chip, and the Chip is what this was written as first.

              MUI's Chip renders a `div`; `disabled` on it becomes a class plus
              `pointer-events: none`, which LOOKS disabled, exposes no disabled state to a screen
              reader, and is indistinguishable from an enabled control to anything that asks the
              DOM — so the permission gate on it would have been decorative. `component="button"`
              fixes the semantics and keeps the chip's pointer-events quirk, which is how this was
              found: a test could not click the ENABLED control either.

              A button is also what it is. This opens a menu; a chip is a display element that was
              being asked to act like one.
            */
            size="small"
            variant="outlined"
            color="inherit"
            endIcon={<ArrowDropDownIcon />}
            disabled={disabled || pending}
            onClick={(event) => setAnchor(event.currentTarget)}
            sx={{
              py: 0,
              minWidth: 0,
              textTransform: 'none',
              fontWeight: 400,
              fontSize: '0.75rem',
              color: 'text.secondary',
              borderColor: 'divider',
            }}
            data-testid={testId}
          >
            {linkRelationshipLabels[value]}
          </Button>
        </span>
      </Tooltip>
      <Menu anchorEl={anchor} open={anchor !== null} onClose={() => setAnchor(null)}>
        {CHOICES.map((choice) => (
          <MenuItem
            key={choice}
            selected={choice === value}
            onClick={() => {
              setAnchor(null);
              // The no-op is sent nowhere. The server answers 200 and writes nothing for it, which
              // is correct of the server and pointless from here — a request that cannot change
              // anything still spends a round trip and still invalidates a page.
              if (choice !== value) onChange(choice);
            }}
            data-testid={`${testId}.${choice}`}
          >
            <ListItemText primary={linkRelationshipLabels[choice]} secondary={linkRelationshipHelp[choice]} />
          </MenuItem>
        ))}
      </Menu>
    </>
  );
}
