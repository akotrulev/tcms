import { Box } from '@mui/material';
import { useMyWork } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { Board } from './BoardPage';

/**
 * §6l's cross-project **"My Work"** board (§83) — everything assigned to this person, in every
 * project they can see.
 *
 * §6l calls this the view most people keep open all day, and says a per-project-only board fails
 * the person working on three; §81 shipped the reviewer's inbox and recorded the absence of this
 * screen as the bound it left behind. This is that bound removed.
 *
 * <h2>Why the route is `/my-work` and not under `/projects/:projectId`</h2>
 *
 * The project segment is not decoration in this app — `router.tsx` says it is a real navigation
 * boundary, and every screen under it reads its project from the URL for breadcrumbs, for the rail,
 * and for permission scoping. **This screen has no project to read**, and nesting it under one
 * would mean picking an arbitrary project to stand in the URL for a list that spans all of them:
 * the crumb would name a project two thirds of the cards are not from, switching projects in the
 * rail would appear to change a board that cannot change, and the link a person keeps open all day
 * would silently be one project's link. So it sits beside `/admin/teams` as an instance-scoped
 * route — the same call that file already makes for a company-wide screen.
 *
 * It is `/my-work` rather than `/board`: there is already a board, it is the project one, and one
 * name for two concepts is the `ui-rules` §6 failure this product has a rail entry for.
 *
 * <h2>What it deliberately does NOT have</h2>
 *
 * **No "Everyone" chip.** The project board's filter pair has no cross-project counterpart — the
 * server answers **400** to a projectless board without `mine=true`, and says why: there is no
 * board that is every project's work unfiltered, and the one response in the product whose size is
 * the company's is not a screen anybody could work from. Rendering the chip and letting it produce
 * a 400 would be `ui-rules` §4's render-then-reject, so the chip is not here at all and the page
 * title says whose board this is instead.
 *
 * **No "New task".** A create needs a project, and this screen has none; asking for one in a dialog
 * would be a project picker on the one screen that exists to not have a project frame. The task is
 * created on the project board it belongs to, where the permission gate is also already scoped.
 *
 * **Never 404s, and an empty list is not an error.** A member holding no project grants gets an
 * empty board — reachable and empty — which is the distinction the project board has to make
 * (`[]` vs 404) and this one, naming no project, cannot fail to make.
 */
export function MyWorkPage() {
  const items = useMyWork();

  return (
    <Box>
      {/*
        No breadcrumbs, unlike every project screen: this route has no ancestor. A single crumb
        reading "My work" above a title reading "My work" is chrome that says nothing twice.
      */}
      <PageHeader
        testId="MyWork.Header"
        title="My work"
        documentTitle="My work"
        subtitle="Everything assigned to you, across every project you can see — executions to record, reviews you have been asked for, and your own tasks."
      />

      <DataRegion
        testId="MyWork.Content"
        state={{ ...items, refetch: () => void items.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          kind: 'no-records',
          /*
            The empty state has to be readable as GOOD NEWS, because on this screen it usually is —
            unlike the project board's, where nothing on the board is a project nobody has started.
            It must also not read as a failure: a person newly added to a company sees this before
            they see anything else, and "nothing found" would tell them the product is broken rather
            than that their queue is clear.
          */
          title: 'Nothing assigned to you',
          body: 'No executions, reviews or tasks are waiting on you in any project right now.',
        }}
      >
        {/*
          The project board's own component, not a second one. §6l describes one board with three
          scopes; two implementations of it would drift on the first change to either — which is the
          `ui-rules` §8 renderer-divergence rule, and the reason `linkFor` had to start reading the
          card's project rather than the route's before this screen could exist at all.
        */}
        {(rows) => <Board items={rows} crossProject />}
      </DataRegion>
    </Box>
  );
}
