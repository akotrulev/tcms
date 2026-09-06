import { Info } from 'lucide-react';

/**
 * What a panel renders instead of a figure when the server says there is not enough to draw.
 *
 * **This is the module's third done-criterion** — *a report with too little data says so rather
 * than drawing a flat line* — and it is one component rather than three branches so all three
 * panels cannot drift into wording it differently.
 *
 * The sentence is the **server's**, printed verbatim. Every `reason` on the wire names a different
 * fix — a release nothing targets, a window with two completions, a project whose history is not
 * recorded — and a client that mapped them onto its own copy would be a second place the sentence
 * can be wrong (020 D-e). A null `reason` falls back to one line, because a null there means the
 * server called a report insufficient without saying why, which is a server defect and not
 * something to paper over.
 *
 * <h2>Not `StateBlock kind="empty"`, and that is a decision</h2>
 *
 * That arm hard-codes *"Nothing here yet."* as its description, which is a second sentence and a
 * wrong one: these projects are not empty. A release with 23 issues every one of which was assigned
 * after it existed has plenty here — what it does not have is a burn-up worth drawing. Restyling
 * `StateBlock` from a feature folder to make one word fit would be the worse of the two moves.
 *
 * No axis, no grid, no zero-valued series. A flat line at zero reads as *the team has done nothing*
 * where the truth is *there is not enough here to say*, and those are opposite statements about the
 * same project.
 */
export function InsufficientBlock({ reason }: { reason: string | null }) {
  return (
    <div
      data-testid="analytics-insufficient"
      className="flex items-start gap-2.5 rounded-[6px] border border-line bg-field px-3.5 py-3"
    >
      <Info className="mt-px size-4 shrink-0 text-ink-4" aria-hidden />
      <p className="text-[12.5px] leading-[1.5] text-ink-2">
        {reason ?? 'There is not enough data to draw this yet.'}
      </p>
    </div>
  );
}
