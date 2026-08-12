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

/**
 * A run's git coordinate as one line — `release/4.3 at 4f9c2b1`, `main`, `at 4f9c2b1`, or null.
 *
 * Here rather than in `RunDetailPage` for the reason the shared layer exists, and the phrasing is
 * the point: **`at <sha>` is `describeLinkTarget`'s wording above**, and a run's commit and a doc
 * link's pin are the same kind of fact read on adjacent screens — the case detail's Traceability
 * panel names a pinned file, and the run that tested that case names a pinned build. Two spellings
 * of one convention is how a hundred consistent screens become a hundred slightly different ones.
 *
 * **Null when neither is set, which is most runs**, so the caller renders nothing rather than a
 * label with an em dash after it: a manual pass in a project that does not record builds should not
 * grow a permanently empty field (§157's signal, one screen over).
 *
 * Either half alone is a legitimate state and both read correctly: a CI job may know its branch and
 * not its sha, and §177 accepts each independently rather than requiring the pair.
 */
export function describeRunCode(run: {
  branch: string | null;
  commitSha: string | null;
}): string | null {
  if (run.branch === null && run.commitSha === null) {
    return null;
  }
  return [run.branch, run.commitSha === null ? null : `at ${run.commitSha}`]
    .filter((part) => part !== null)
    .join(' ');
}
