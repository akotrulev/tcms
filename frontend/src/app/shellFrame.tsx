import type { ReactNode } from 'react';

import { ShellFrameContext, type ShellFrame } from './shellFrameContext';

export function ShellFrameProvider({ value, children }: { value: ShellFrame; children: ReactNode }) {
  return <ShellFrameContext value={value}>{children}</ShellFrameContext>;
}
