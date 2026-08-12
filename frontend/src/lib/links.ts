import type { DocLink } from '../api/types';

/**
 * A traceability link's target, as one line of plain text.
 *
 * `DocLinkTarget` in `CaseDetailPage` renders the same three kinds as **markup** — an anchor, a
 * router link, an identifier group — which is right where the reader can click them and wrong in a
 * confirmation dialog, where a link that navigates away mid-confirmation is a control the reader did
 * not mean to use. So this is the text form, and it exists because `ui-rules` §4 requires a
 * destructive confirmation to **name the specific object**: "Remove this link?" over an unnamed row
 * is the "are you sure?" the rule exists to forbid.
 *
 * **The switch is on `targetKind`, never on which field is populated.** A row is one kind and the
 * other kind's properties are null — §169's defect, which rendered a URL link as a blank line for
 * seventy sections because it branched on `externalRepo`. Same rule, second implementation, stated
 * so the two stay in step.
 *
 * The git form keeps the path, the range and the pin together, as `DocLinkTarget` does: the pin is
 * what makes the range mean anything six months later (#26), so it is part of the name and not a
 * footnote. A single-line range renders as one number; a whole-file link renders no range at all.
 */
export function describeLinkTarget(link: DocLink): string {
  if (link.targetKind === 'DOC_BLOCK') {
    // The page's title, and its key when there is one — the block itself has no name a reader
    // would recognise, which is the whole reason a block anchor is a uuid (plan §6i).
    return [link.pageKey, link.pageTitle].filter(Boolean).join(' — ') || 'a documentation block';
  }

  if (link.targetKind === 'EXTERNAL_URL') {
    return link.externalUrl ?? 'an address';
  }

  const range =
    link.externalLineStart == null
      ? ''
      : link.externalLineEnd == null || link.externalLineEnd === link.externalLineStart
        ? `:${link.externalLineStart}`
        : `:${link.externalLineStart}–${link.externalLineEnd}`;
  const file = `${link.externalPath ?? ''}${range}`;
  const pinned = link.externalCommitSha ? `${file} at ${link.externalCommitSha}` : file;
  return [link.externalRepo, pinned].filter((part) => part !== '').join(' · ');
}
