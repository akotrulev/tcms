import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';
import { installFaultConsole } from './faults';

export const worker = setupWorker(...handlers);

/**
 * Exposes `window.tcmsFaults` alongside the worker. The resilience gate claims things that the
 * app cannot be made to do by using it normally — a 500, a hung request, an expired session —
 * and this is how those get driven in a real browser rather than asserted on paper.
 */
installFaultConsole();
