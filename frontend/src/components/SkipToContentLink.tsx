/**
 * Skip-to-content link.
 *
 * A real `<a href="#main-content">`, not a button with a scroll handler: the browser's own
 * fragment navigation moves focus as well as scrolling, and `<main>` carries `tabIndex={-1}`
 * precisely so it can receive that focus.
 *
 * Hidden until focused — the standard treatment. It is positioned off-screen rather than
 * `display: none`, because a hidden-by-display element cannot be focused and so could never
 * reveal itself. `translate-y` rather than `top` so no layout is affected.
 *
 * Both `focus` and `focus-visible` are honoured: some browsers give a fragment link `:focus`
 * without `:focus-visible`, and a link that is focused but invisible is worse than no link.
 */
export function SkipToContentLink() {
  return (
    <a
      href="#main-content"
      data-testid="SkipToContent"
      className="absolute top-2 left-2 z-1400 -translate-y-[200%] rounded bg-steel px-4 py-2 text-sm font-semibold text-white no-underline focus:translate-y-0 focus-visible:translate-y-0 motion-safe:transition-transform motion-safe:duration-120 motion-safe:ease-out"
    >
      Skip to content
    </a>
  );
}
