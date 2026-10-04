import { createContext, useContext } from 'react';

export interface ShellFrame {
  projectId?: string;
  projectKey?: string;
  projectName?: string;
}

export const ShellFrameContext = createContext<ShellFrame>({});

export function useShellFrame(): ShellFrame {
  return useContext(ShellFrameContext);
}
