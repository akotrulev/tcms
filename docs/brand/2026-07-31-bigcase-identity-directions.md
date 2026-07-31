# BigCase — three identity directions

**Date:** 2026-07-31 · **Status:** awaiting a decision · **Renders:** [`renders/index.html`](renders/index.html)

Open the renders in a browser (`open docs/brand/renders/index.html`). Each direction is a complete
system — mark, lockup, app icon down to 16px, palette, type scale, and a live slice of the case list
so it can be judged doing the actual job rather than on a plinth.

---

## 0. The constraint all three obey

This project's existing design thesis, recorded at the top of `frontend/src/index.css`:

> Status colour is load-bearing **data** in a test management tool, so the brand palette deliberately
> retreats. Status owns the entire saturation budget.

That is not a stylistic preference, it is a correctness property — a brand green that reads as
"passed", or a brand red that reads as "failed", makes a results grid lie. So:

- **Green, red, amber, violet and grey are spoken for** (`passed`, `failed`, `blocked`, `retest`,
  `skipped`/`untested`). None may become a brand colour.
- The remaining usable hue space is **blue, teal-cyan, indigo, and magenta**. Each direction below
  picks exactly one and spends it only on chrome: the primary action, active nav, the mark.
- **All three keep the status palette byte-identical to today's tokens.** They differ only in the
  chrome around it, which is what makes any of them a contained change.

Naming, separately: **BigCase** works because "case" is already the product's unit of meaning
(`test case`), and doubles as the container metaphor (case file, briefcase). All three marks are
built on one of those two readings. Display keys become `BC-1042`.

---

## 1. Direction A — **Casefile**

**Thesis:** the system of record for what was tested. Quiet, archival, built to be stared at for
eight hours without shouting.

| | |
|---|---|
| **Mark** | Two brackets `[ ]` — the assertion syntax everyone here reads all day — enclosing a tabbed file block. Container and code in one glyph. |
| **Brand** | Ink `#141A22` · Steel `#1F6F8B` (action) · Slate `#5B6B80` · Steel wash `#EAF2F6` |
| **Type** | Space Grotesk 700 (display) · IBM Plex Sans (body) · IBM Plex Mono (identifiers) |
| **Signature** | The identifier. `BC-1042` is treated as a first-class typographic object — boxed, monospaced, always leftmost — because it is what people actually say out loud to each other. |

**Cost to adopt: lowest.** Steel moves three points from `#2E6E8E` to `#1F6F8B`; ink and the whole
neutral ramp stay. One new font family (Space Grotesk) for display only. Nothing in
`lib/labels.ts`, `lib/results.ts` or the status tokens is touched.

**Risk:** restraint read as blandness. It will not win a screenshot on social media; it will win a
Tuesday afternoon. If the product is sold on a landing page, this direction gives the page nothing
to lead with.

---

## 2. Direction B — **Signal**

**Thesis:** a product with a spine. Near-monochrome, one hot accent spent only on the thing you are
meant to press, and a wordmark wide enough to hold a page on its own.

| | |
|---|---|
| **Mark** | An open **C** — a case with its lid off — holding a solid block. The gap faces right, into the wordmark, so the mark points at the name instead of sitting beside it. |
| **Brand** | Graphite `#0F1115` · Magenta `#C0246B` (action) · Grey `#5C636E` · Bone `#FAFAF8` |
| **Type** | Archivo 800 @ width 112 (display, uppercase, rationed to page titles and the wordmark) · Inter (body) · JetBrains Mono (identifiers) |
| **Signature** | The wide uppercase wordmark and a single magenta stroke. Nothing else in the interface is allowed to be loud. |

**Cost to adopt: highest.** New action colour and its dark-mode pair, a cooler-warmer neutral ramp
(bone rather than blue-grey), and two new font families. Every `--color-action-*`,
`--color-info` and focus-ring token changes.

**Risk — and this is a real one:** magenta (330°) sits closer to failure red (6°) than any other
choice here. The renders mitigate it by shape as well as hue — an action is always a hard-edged
filled button, a verdict is always an outlined chip with a dot and a word, so colour is never the
only signal. That mitigation is sound, but **judge it on the in-product section of the render, at a
glance, not in the palette strip.** If it survives that look, this is the direction nobody will
mistake for another QA tool.

---

## 3. Direction C — **Matrix**

**Thesis:** a suite *is* a grid — cases down, runs across, one cell per verdict. Build the identity
out of that grid, and draw the name literally.

| | |
|---|---|
| **Mark** | Nine modules. The top-left four merge into one — the big case. The bottom-right module holds the cyan: the one cell that changed since the last run. |
| **Brand** | Midnight `#111634` · Indigo `#2F3D9E` (action) · Cyan `#00A6C7` (signature, chrome only) · Indigo wash `#EDEFFA` |
| **Type** | Sora 700/500 (display; the weight split carries "**Big**Case", not colour) · Inter (body) · IBM Plex Mono (identifiers) |
| **Signature** | The cyan "changed since last run" cell, promoted out of the logo into a real UI affordance. The module grid also generates favicons, empty-state art, skeletons and report covers without new drawing. |

**Cost to adopt: medium.** New action colour, one new display face, and the neutral ramp shifts cool
while keeping its structure. Cyan is a chrome colour by rule — it never appears on a result — so the
status tokens are untouched.

**Risk:** nine-square logos are a crowded genre (every cloud console has one). It earns its place
only because the 2×2 merge means something specific here rather than being decoration. Indigo is
also the closest brand hue to retest violet — 30° apart, and separated further by being much darker,
but worth a look on a run screen with retests visible.

---

## 4. Recommendation

**Direction C — Matrix**, with **A — Casefile** as the safe fallback.

C is the only one of the three whose mark keeps paying out after the logo: the module grid gives the
product an empty state, a loading skeleton, a favicon set and a report cover for free, and the cyan
cell becomes a genuine affordance instead of a brand flourish. That extensibility is worth more here
than B's distinctiveness, because this product's surface area is dense screens, not a marketing site.

Pick **A** if the priority is shipping M-whatever without a theming detour — it is a three-point
colour change and a display font.

Pick **B** only after looking hard at the in-product section of its render. It is the most memorable
and the only one carrying a genuine colour risk against the status palette.

---

## 5. If a direction is chosen, what actually changes

1. `frontend/src/index.css` — the brand and neutral custom properties in `:root` and
   `[data-mui-color-scheme='dark']`. **The status, semantic-feedback and lifecycle blocks stay as
   they are** in all three cases.
2. `frontend/src/app/theme.ts` — the bridge picks up the new values; no structural change.
3. Fonts — self-host the chosen families rather than linking Google Fonts (all six candidates are
   SIL OFL). The renders use the CDN purely so they open standalone.
4. The mark ships as an inline SVG component plus a favicon set at 16/28/48; the 16px variant in
   every direction is a deliberately simplified drawing, not the full mark scaled down.
5. Product name and display-key prefix (`TC-` → `BC-`) — a separate decision with a data-migration
   cost, **not** implied by picking a logo. Flag it before anyone starts.
