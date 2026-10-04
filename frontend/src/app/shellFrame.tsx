import { createContext, useContext, type ReactNode } from 'react';

export interface ShellFrame {
  projectId?: string;
  projectKey?: string;
  projectName?: string;
}

const ShellFrameContext = createContext<ShellFrame>({});

export function ShellFrameProvider({ value, children }: { value: ShellFrame; children: ReactNode }) {
  return <ShellFrameContext value={value}>{children}</ShellFrameContext>;
}

export function useShellFrame(): ShellFrame {
  return useContext(ShellFrameContext);
}
