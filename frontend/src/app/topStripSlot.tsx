import { createContext, use, useMemo, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

/**
 * A place in the shell's top strip that a page may render into (007 T7 Step 0, moved from T5).
 *
 * The case editor's commit bar belongs *beside the breadcrumb*, not in a footer bar of its own —
 * design language rule 1, and §0 item 7 of `docs/plans/007-render-design-implementation.md`. A page
 * cannot reach into `<header>` on its own, so the shell offers a target and the page portals into
 * it.
 *
 * **The fallback is the load-bearing part.** With no shell above it — which is how every page test
 * in this repository renders a page, and how a page renders when it is mounted outside the
 * authenticated layout — `TopStripSlot` renders its children **in place** rather than dropping
 * them. A commit bar that exists only when a particular ancestor happens to be mounted is a Save
 * button that disappears, and it would fail silently: the portal target is simply `null` and
 * `createPortal` has nowhere to go.
 */
interface TopStrip {
  target: HTMLElement | null;
  register: (element: HTMLElement | null) => void;
}

const TopStripContext = createContext<TopStrip | null>(null);

export function TopStripProvider({ children }: { children: ReactNode }) {
  const [target, setTarget] = useState<HTMLElement | null>(null);
  const value = useMemo<TopStrip>(() => ({ target, register: setTarget }), [target]);
  return <TopStripContext.Provider value={value}>{children}</TopStripContext.Provider>;
}

/** The region inside the shell's `<header>`. Empty, and invisible, until a page fills it. */
export function TopStripTarget({ className }: { className?: string }) {
  const strip = use(TopStripContext);
  return <div ref={strip?.register} data-testid="Nav.TopStrip" className={className} />;
}

export function TopStripSlot({ children }: { children: ReactNode }) {
  const strip = use(TopStripContext);
  if (!strip?.target) return <>{children}</>;
  return createPortal(children, strip.target);
}
