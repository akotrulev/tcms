/**
 * MSW request handlers — the stand-in for the Spring Boot API until it exists.
 *
 * These are deliberately *stateful* (see ../db): a create round-trips into the list, an
 * edit into the detail, a submitted result into the run counts. A handler set that returns
 * fixtures regardless of what you sent makes every write screen untestable, because the
 * one thing you need to see — that the write landed — is the thing it fakes.
 *
 * Paths mirror docs/specs/openapi.yaml exactly. When the contract changes, change it there,
 * regenerate, then fix both sides.
 *
 * **Order is load-bearing, which is why this file composes rather than concatenates blindly.**
 * MSW resolves handlers in declaration order and takes the first that matches, so:
 *
 *   1. `faultHandler` MUST stay first — it is a wildcard over every path, and anything above it
 *      would be unfaultable. Everything below it is only reached when no fault is armed.
 *   2. Within a group, a more specific path must precede the `:param` that would swallow it
 *      (`/cases/bulk` before `/cases/:caseId`). Groups are self-contained on this, so adding a
 *      group is safe; reordering the lines *inside* one is not.
 *
 * Adding an endpoint family means adding a file and one line here — which is the point. Four
 * hundred lines of unrelated handlers no longer sit between you and the one you are editing.
 */
import { adminHandlers } from './admin';
import { authHandlers } from './auth';
import { caseHandlers } from './cases';
import { documentationHandlers } from './documentation';
import { faultHandler } from './injection';
import { libraryHandlers } from './library';
import { planHandlers } from './plans';
import { projectHandlers } from './projects';
import { reportingHandlers } from './reporting';
import { runHandlers } from './runs';
import { suiteHandlers } from './suites';
import { workItemHandlers } from './workItems';

export const handlers = [
  faultHandler,
  ...authHandlers,
  ...projectHandlers,
  ...suiteHandlers,
  ...caseHandlers,
  ...libraryHandlers,
  ...planHandlers,
  ...runHandlers,
  ...documentationHandlers,
  ...reportingHandlers,
  ...adminHandlers,
  ...workItemHandlers,
];
