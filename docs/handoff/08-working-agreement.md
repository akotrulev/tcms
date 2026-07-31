# §8. Working agreement for this project

- **Load the plugin skills before producing anything they cover** (§0). This is the single most important line in this document.
- Verify with `npm run build`, never `tsc --noEmit` alone — **and then run the app** (§6, lesson 5).
- `openapi.yaml` is the contract — change it there, regenerate, then adapt both sides in one commit. Read it before coding against a shape; don't guess.
- **New display logic goes in the shared layer, not the page.** `lib/labels.ts` for enums, `lib/format.ts` for formats, `lib/results.ts` for count arithmetic, `DataRegion` for the three states, `Identifier` for anything monospace. A second implementation of any of these is how a hundred consistent pages become a hundred slightly-different ones.
- Save generated `.md` artifacts into this project under `docs/`, never to a global directory.
- The plan document is long by design; §6a–§6q each state the *decision and its reason*. Read the relevant section before changing behaviour it covers — several decisions look arbitrary until you see the failure mode they prevent.
