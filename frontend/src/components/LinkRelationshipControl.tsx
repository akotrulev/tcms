import { ChevronDown } from 'lucide-react';
import type { LinkRelationship } from '../api/types';
import { linkRelationshipHelp, linkRelationshipLabels } from '../lib/labels';
import {
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from './ui';

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
  return (
    <Tooltip>
      {/* A span, because a disabled button fires no events and a tooltip needs something to
          listen to — so the meaning of the value would be unreachable to the reader least able to
          act on it. `TableRowsEditor` records the same trap. It survived the port: Radix's
          tooltip has the same requirement MUI's did. */}
      <TooltipTrigger asChild>
        <span>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              {/*
                A Button and not a Chip, and the Chip is what this was written as first.

                ~~MUI's Chip renders a `div`; `disabled` on it becomes a class plus
                `pointer-events: none`, which LOOKS disabled, exposes no disabled state to a
                screen reader, and is indistinguishable from an enabled control to anything that
                asks the DOM~~ → superseded in T16: there is no MUI Chip here any more. The
                finding it records is why this is still a `<button>` and not the kit's `Chip`,
                which is also a display element — a real button carries the disabled state the
                permission gate depends on.

                A button is also what it is. This opens a menu; a chip is a display element that
                was being asked to act like one.
              */}
              <Button
                size="sm"
                variant="ghost"
                disabled={disabled || pending}
                className="min-w-0 py-0 text-xs font-normal text-ink-2"
                data-testid={testId}
              >
                {linkRelationshipLabels[value]}
                <ChevronDown aria-hidden className="size-3.5" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start">
              {CHOICES.map((choice) => (
                <DropdownMenuItem
                  key={choice}
                  onSelect={() => {
                    // The no-op is sent nowhere. The server answers 200 and writes nothing for it,
                    // which is correct of the server and pointless from here — a request that
                    // cannot change anything still spends a round trip and still invalidates a
                    // page.
                    if (choice !== value) onChange(choice);
                  }}
                  data-testid={`${testId}.${choice}`}
                  className="flex-col items-start gap-0.5"
                >
                  <span className={choice === value ? 'font-semibold text-ink' : 'text-ink'}>
                    {linkRelationshipLabels[choice]}
                  </span>
                  <span className="text-xs text-ink-2">{linkRelationshipHelp[choice]}</span>
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
        </span>
      </TooltipTrigger>
      <TooltipContent>{linkRelationshipHelp[value]}</TooltipContent>
    </Tooltip>
  );
}
