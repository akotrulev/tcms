import {
  createContext,
  useContext,
  useState,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';

const WorkStripContext = createContext<{
  target: HTMLElement | null;
  setTarget: (node: HTMLElement | null) => void;
} | null>(null);

export function WorkStripActionsProvider({ children }: { children: ReactNode }) {
  const [target, setTarget] = useState<HTMLElement | null>(null);
  return <WorkStripContext value={{ target, setTarget }}>{children}</WorkStripContext>;
}

export function WorkStripActionsTarget() {
  const context = useContext(WorkStripContext);
  return (
    <div
      ref={context?.setTarget}
      data-testid="WorkTable.StripActions"
      className="flex flex-wrap items-center gap-2"
    />
  );
}

export function WorkStripActions({ children }: { children: ReactNode }) {
  const context = useContext(WorkStripContext);
  if (context === null) return <>{children}</>;
  return context.target ? createPortal(children, context.target) : null;
}
