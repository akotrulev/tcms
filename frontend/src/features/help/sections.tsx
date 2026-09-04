import type { ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { CliSetup } from './CliSetup';
import { McpSetup } from './McpSetup';
import { Code, Definitions, H, List, Note, P, Terminal } from './prose';
import { RoleMatrix } from './RoleMatrix';
import { TokenSteps } from './TokenSteps';

/**
 * The user guide, as a list of sections.
 *
 * **The `id` is a URL and is therefore permanent.** `/help/:sectionId` deep-links here and those
 * links get pasted into tickets, onboarding notes and support replies. Retitle a section freely;
 * renaming an `id` breaks every link anybody kept, with a 404 that says nothing about where the
 * content went. Add sections rather than renumbering them.
 *
 * **What this file may and may not claim.** Everything here describes behaviour that is shipped.
 * Where the product deliberately does *not* do something — an agent cannot attach evidence, there
 * is no `case create` in the CLI — the section says so and says why, because "we do not do that,
 * on purpose" is the answer that stops a user hunting for a menu item that was never built. What
 * it must never do is describe a planned feature in the present tense; `docs/features.md` keeps
 * the built/planned split and is the source this was written from.
 *
 * The role matrix is the one section with no prose copy of its facts — see `RoleMatrix`.
 */
export interface HelpSection {
  /** Stable, lowercase-kebab, and part of the URL. Never rename one. */
  id: string;
  title: string;
  /** One line, shown in the contents list. Says what the reader will learn, not what exists. */
  summary: string;
  body: ReactNode;
}

export const helpSections: HelpSection[] = [
  {
    id: 'getting-started',
    title: 'Getting started',
    summary: 'What TCMS is, how you get in, and how the pieces fit together.',
    body: (
      <>
        <P>
          TCMS is a test case management system. It holds the test <em>artefacts</em> — cases,
          suites, plans, runs, results and documentation — and takes in results from your CI. It is
          not a test runner and not a defect tracker: it links out to both.
        </P>

        <H>How the pieces nest</H>
        <Definitions
          items={[
            {
              term: 'Company',
              detail:
                'Your organisation. It is the isolation boundary — nothing you do is visible to another company on this instance. Companies are created by an operator, not from the app.',
            },
            {
              term: 'Project',
              detail:
                'A whole product or client engagement, not a feature area. "Payments" is a suite or a classification value inside a project; it is not a project of its own.',
            },
            {
              term: 'Suite',
              detail:
                'A named collection of test cases inside a project. A suite is flat — it is not a folder and does not nest.',
            },
          ]}
        />

        <H>Getting in</H>
        <P>
          You reach the app one of four ways, depending on what your Company Admin has configured:
          an email and password; an emailed invitation link, which is where you set that password;
          your organisation&apos;s single sign-on, chosen automatically from your email domain; or a
          GitHub, GitLab or Google account. You can link and unlink those accounts later from{' '}
          <strong>your name → Security</strong>.
        </P>
        <P>
          Being in the company is only the first of two gates. Company membership gets you signed in
          and gives you <em>no project visibility at all</em>. Somebody has to grant you a role on a
          project before it appears in your project list — see <strong>Roles and access</strong>.
        </P>

        <H>Finding your way around</H>
        <Definitions
          items={[
            {
              term: 'The project switcher',
              detail:
                'In the header, at the top of every screen. Everything in the left rail is scoped to the project you picked — switching projects changes what those screens show.',
            },
            {
              term: 'The search field',
              detail:
                'Beside the switcher, and deliberately not scoped by it. Type and press Enter to find a case across every project you can open, for when you do not know which project holds it.',
            },
            {
              term: 'My work',
              detail:
                'In the rail, above the project groups, so it is not scoped by the switcher: every issue, execution and review assigned to you, across every project you can see.',
            },
          ]}
        />
        <Note>
          Controls you do not have permission to use are <strong>visible but disabled</strong>, with
          a tooltip naming what you would need. That is deliberate: the product never hides that a
          capability exists, so what you are missing is something you can go and ask for. The two
          audit-trail entries are the one exception and are hidden entirely.
        </Note>
      </>
    ),
  },

  {
    id: 'roles',
    title: 'Roles and access',
    summary: 'The nine roles, exactly what each one can do, and how a grant reaches you.',
    body: (
      <>
        <P>
          Access is two independent gates. <strong>Company membership</strong> authenticates you and
          grants zero project visibility. An <strong>explicit grant on a project</strong> is what
          makes that project appear and decides what you can do in it.
        </P>
        <P>
          A grant can be made to you personally or to a <strong>team</strong> you belong to, in
          which case every member inherits it. A grant made at company scope covers every project in
          the company, present and future; a grant made on one project replaces the company-wide one
          there.
        </P>

        <H>Who grants what</H>
        <Definitions
          items={[
            {
              term: 'Adding somebody to a project',
              detail: (
                <>
                  Costs <Code>project:members</Code> on that project — held by Test Lead, Project
                  Manager, Company Admin and Instance Admin. A Test Lead can staff their own project
                  without being able to staff anybody else&apos;s. Do it from{' '}
                  <strong>Project → Access &amp; roles</strong>.
                </>
              ),
            },
            {
              term: 'Adding somebody to the company',
              detail: (
                <>
                  Costs <Code>company:members</Code> — Company Admin and Instance Admin only. Invite
                  them from <strong>Company → Teams &amp; roles</strong>; they get an email link and
                  set their own password when they accept.
                </>
              ),
            },
            {
              term: 'Granting a role company-wide',
              detail: (
                <>
                  Also <Code>company:members</Code>. This is how somebody becomes a Company Admin —
                  it is a company-scope role assignment, not a separate kind of account.
                </>
              ),
            },
            {
              term: 'Creating a team',
              detail: (
                <>
                  Costs <Code>company:teams</Code> — Company Admin and Instance Admin.
                </>
              ),
            },
          ]}
        />

        <H>The rules behind the table</H>
        <List
          items={[
            <>
              <strong>Nine roles, and they are fixed.</strong> There are no custom roles, not even
              clone-and-edit. A team needing something between two tiers gets the higher one.
            </>,
            <>
              <strong>The code checks permissions, never role names</strong>, and there is no
              wildcard permission. &quot;Holds everything&quot; is worked out by comparing against
              the catalogue, so a gap shows up as a gap.
            </>,
            <>
              <strong>Test Lead adds exactly ten things over Test Engineer</strong>: the three
              approvals, permission to change a project&apos;s settings, its members and its
              integrations, to read its audit log, and to delete issues, change the issue workflow
              and import issues from another tracker.
            </>,
            <>
              <strong>Nobody approves their own work</strong> by default. Reviewing and commenting
              are separate from approving.
            </>,
            <>
              <strong>No grant is narrowed by authorship.</strong> Every non-Viewer contributor can
              edit and delete anything in the project, regardless of who created it. What separates
              the tiers is approvals and project configuration, not ownership.
            </>,
            <>
              <strong>An API token never grants more than its owner has.</strong> Its reach is your
              permissions intersected with the token&apos;s role — so demoting somebody immediately
              shrinks every token they issued.
            </>,
          ]}
        />

        <H>What each role can do</H>
        <P>
          Read live from this instance, so it is what <em>this</em> server actually enforces rather
          than what was true when this page was written. Roles run least-permissioned first.
        </P>
        <RoleMatrix />
      </>
    ),
  },

  {
    id: 'projects',
    title: 'Projects',
    summary: 'Creating a project, and the four things you configure per project.',
    body: (
      <>
        <P>
          A project is a whole product or client engagement. Creating one costs{' '}
          <Code>project:create</Code>, which Company Admins hold. Projects can be edited and
          archived when the engagement finishes.
        </P>
        <P>
          The project <strong>Overview</strong> is where opening a project lands, and it holds
          delivery and quality together: the epics with their completion and the basis each
          percentage was computed on, the story-level work that sits under no epic, and test health
          — then the repository figures underneath, which is what needs attention, how cases break
          down by lifecycle and by classification, and how execution is trending.
        </P>

        <H>What you configure per project</H>
        <Definitions
          items={[
            {
              term: 'Classification',
              detail:
                'Your own dimensions and values — Priority, Component, whatever this client calls them, including nested values. Guessing a value from another project will be refused; the vocabulary is per-project.',
            },
            {
              term: 'Review',
              detail:
                'Whether review and approval gating is on, and what it applies to. Everyone who can see the project can read this page; changing it costs project:settings.',
            },
            {
              term: 'Notifications',
              detail:
                'Email or webhook channels, and who gets told what. The reads cost integration:manage, so this page shows a contributor nothing — that is the page working, not a fault.',
            },
            {
              term: 'Issue tracker',
              detail:
                'Which tracker this project files defects to — a Jira Cloud, Jira Data Center or Azure DevOps site, and the project key or team project inside it. This page holds no credential; each person connects with their own. It also lists who on the team has connected, so "why can nobody file defects?" is answerable from one screen. The reads cost integration:manage, like Notifications.',
            },
          ]}
        />
        <Note>
          <strong>Execution statuses are per project but not yet editable.</strong> Each project
          carries its own result vocabulary and the order it reads in — the app never assumes
          &quot;Passed&quot; and &quot;Failed&quot; — but the six are seeded when the project is
          provisioned and there is no screen or endpoint that adds to them. Name your own statuses
          when it is built; today, read them and use them.
        </Note>
        <P>
          <strong>Access &amp; roles</strong> is the per-project view of the access model: what this
          project granted, and what it inherits from the company. <strong>Audit trail</strong>{' '}
          appears in the rail only if you hold <Code>project:audit</Code>.
        </P>
      </>
    ),
  },

  {
    id: 'test-cases',
    title: 'Writing test cases',
    summary: 'Cases, Gherkin, keys, health signals, review and versioning.',
    body: (
      <>
        <P>
          A case has a title, a description, preconditions, postconditions, and numbered steps
          pairing an action with its expected result. Or write it in <strong>Gherkin</strong>{' '}
          instead — the editor seeds a <Code>Feature:</Code> scaffold and the detail view renders the
          keywords.
        </P>
        <P>
          Every case gets a readable key — <Code>TC-1</Code>, <Code>TC-2</Code> — that is unique
          across your whole company, not just the project. <Code>TC-42</Code> identifies exactly one
          case anywhere.
        </P>

        <H>What else goes on a case</H>
        <List
          items={[
            'Priority, tags, classification values, automation status, and the automation key that binds it to its automated test.',
            'Links out to an external system — a spec, a ticket, a source file at a specific commit.',
            'Issues from your tracker, kept in their own list rather than mixed into those links: a traceability link says what the case covers, an issue says what is wrong with it, and the two have different lifetimes. You search the tracker and pick a result rather than typing a key — a key moves, so what gets stored is the issue’s own id — which, like filing, needs a tracker you have connected. The rows are ours, so the list still renders when the tracker is unreachable.',
            'File attachments.',
            'Preconditions and postconditions taken from the project-wide condition library, rather than retyped. Cases also inherit the conditions their suite imposes.',
          ]}
        />
        <P>
          As you type a step you get suggestions drawn from steps already written elsewhere in the
          project. Your unsaved work is buffered per person per case and offered back when you
          return, and two people editing the same case cannot silently overwrite each other — the
          second save is refused rather than applied.
        </P>

        <H>Keeping cases healthy</H>
        <P>
          The product continuously flags cases needing attention, and you can filter the repository
          by any of these signals: its automation has stopped reporting; it runs in CI but is not
          declared automated; the specification it links to has changed since it was written; its
          approval has gone stale; the conditions it depends on have drifted; it has never been
          executed, or not for a long time; it is failing repeatedly; it belongs to no suite; it
          links to no documentation; it is incomplete. Separately, cases can be marked{' '}
          <strong>flaky</strong> from their result history and <strong>quarantined</strong> by hand.
        </P>

        <H>Review and versioning</H>
        <List
          items={[
            'Submit a case for review, name a reviewer, then approve, unapprove or request changes.',
            'Comment on cases, suites and plans, and resolve threads. One permission covers commenting on all three; resolving a thread is not commenting — it costs the review permission for that subject.',
            'Every edit is versioned. Browse the full timeline and see what changed between any two versions.',
            'Cases move Draft → Active → Approved → Deprecated → Archived.',
          ]}
        />

        <H>Working in bulk</H>
        <P>
          Filter the repository by suite, tag, lifecycle status, health signal, classification or
          free text, then change status, assign classifications, apply tags or move cases between
          suites — many at once. You can also clone a case, or delete one.
        </P>

        <H>Generated cases</H>
        <P>
          An AI agent can file a <em>proposed</em> test case for a person to review. Proposals live
          under <strong>Authoring → Generated cases</strong> and are accepted into the repository or
          rejected there.
        </P>
        <Note>
          Nothing an agent writes lands unreviewed. This is structural rather than a policy setting:
          accepting a proposal requires a real signed-in person, and every request an agent or a CLI
          makes carries a token, so an agent cannot accept its own proposal on any code path.
        </Note>
      </>
    ),
  },

  {
    id: 'suites-and-plans',
    title: 'Suites, tags and test plans',
    summary: 'How cases get organised, and how a plan differs from a run.',
    body: (
      <>
        <H>Suites</H>
        <Note>
          A suite is a <strong>named collection, not a folder</strong>. It does not nest, and a case
          is not &quot;in&quot; exactly one of them.
        </Note>
        <P>
          A suite&apos;s membership comes from three places at once: cases filed into it, cases added
          to it by hand, and cases matching its tag rules. Open a suite to see which arm each member
          came from. Suites are managed from the <strong>Test cases</strong> screen.
        </P>
        <List
          items={[
            'Create, rename and delete suites.',
            'Assign preconditions and postconditions that every case in the suite inherits.',
            'Suites are versioned as their composition changes, so you can see when the contents shifted.',
            'Suites have their own lifecycle and can be submitted for review and approved.',
          ]}
        />

        <H>Tags</H>
        <P>
          Reusable per project, applied to cases, suggested as you type, and usable to drive suite
          membership.
        </P>

        <H>Test plans</H>
        <P>
          A plan is a named selection of what you intend to cover against a milestone. Pull cases in
          by naming suites, individual cases, or tags; break the plan into sections, each with its
          own scope and prose.
        </P>
        <List
          items={[
            'Create, edit, clone and archive plans.',
            'Submit a plan for review and approve it.',
            'Generate the plan as a specification document, linked into the documentation tree.',
            'A statistics view showing execution rollups and progress against the plan.',
            'Plans move Draft → Active → Closed → Archived.',
          ]}
        />
        <P>
          A plan says what <em>should</em> be covered; a <strong>run</strong> is one attempt at
          executing a selection. They are separate on purpose — one plan outlives many runs.
        </P>
      </>
    ),
  },

  {
    id: 'runs',
    title: 'Running tests',
    summary: 'Creating runs, recording results, and the boards that show the work.',
    body: (
      <>
        <P>
          Create a run scoped by suites, cases and tags. The scope you asked for is remembered, so
          later you can see what the run was <em>meant</em> to cover, not just what ended up in it.
        </P>
        <List
          items={[
            "Record a result per case using your project's own status vocabulary, with per-step outcomes.",
            'Attach evidence files to a result, and remove them.',
            'Link a failed result out to a defect in your tracker, or file a new one without leaving the run.',
            'Assign executions to people — individually, or split a selection across a list of people round-robin.',
            'Clone a run to repeat it, and close it when it is done.',
            'Runs carry release version, build number, commit SHA and branch, so a run traces back to what was built.',
            'Runs move Not Started → In Progress → Completed → Aborted.',
          ]}
        />

        <H>Filing a defect</H>
        <P>
          A failure can become an issue in the project&apos;s tracker from the execution screen, with
          the case, the step that failed and what was recorded already written into the body. The
          defect is filed <strong>as you, with your own credential</strong> — not with a shared
          service account — so the issue in the tracker is reported by the person who saw the
          failure.
        </P>
        <Note severity="warning">
          <strong>That is why the button can be disabled when you hold every permission there is.</strong>{' '}
          Filing needs three things: <Code>defect:create</Code>, a run that is still open, and{' '}
          <strong>a tracker you have connected</strong>. The third is not a permission and nobody
          can grant it to you — connect under <strong>your name → Tracker connections</strong> with
          an API token you create in the tracker itself. The control says which of the three is
          missing before you click rather than failing afterwards.
        </Note>
        <P>
          Your connections are yours: one per tracker site, each showing when it was last used, what
          the tracker last said about it, and — for Jira Cloud, which caps a token at a year — the
          date it dies. A project&apos;s settings page shows leads <em>who</em> has connected, never
          the credential and never the reason somebody&apos;s needs attention.
        </P>

        <H>Seeing the work</H>
        <Definitions
          items={[
            {
              term: 'Run detail',
              detail:
                'Live pass/fail/blocked counts and progress, plus a What Failed panel putting the run’s failures and their steps on one screen.',
            },
            {
              term: 'Board',
              detail:
                'ONE project’s board — its open issues in columns named by that project’s own workflow, alongside the work the repository already knows about: executions assigned to you, reviews you have been asked for, cases the health computation flagged, and tests somebody quarantined. Drag a card, or use the keyboard twin the footer shows while a card is lifted. A move the workflow forbids is refused and the card says why.',
            },
            {
              term: 'Project boards',
              detail:
                'A project can carry several boards over the same statuses — a per-status one for the team, a three-column one for a stakeholder — because a board is a view and owns no issue. Move a card on one and it is in the matching column on the other. Swimlanes group by epic, assignee, label or priority; a WIP limit reports a breach rather than refusing the drop; and the columns, their claims and the project’s labels are defined on Project settings → Boards, where a status × board table shows every status claimed by exactly one column before a save is attempted.',
            },
            {
              term: 'Backlog',
              detail:
                'Every story-level issue that is not done, in one project-wide order you can drag or move with ↑ ↓. Select rows to set an epic or an assignee on all of them at once, and the result names anything that was refused rather than reporting a partial write as a success. The filter is the same one the board and the table carry, held in the URL.',
            },
            {
              term: 'My work',
              detail:
                'Everything assigned to you, across every project you can see, as two tables over one read: your issues in one, your executions and reviews in the other. Not a board — two projects name their statuses separately, so columns there would be a grouping the data cannot support. You can also keep a private note and a personal due date on any card, which no teammate sees.',
            },
            {
              term: 'Team board',
              detail:
                'Everything assigned to a team, across every project. Any company member may open any team’s board.',
            },
          ]}
        />

        <H>Scheduled runs</H>
        <P>
          A run can be created on a schedule rather than by hand — a standing request for what to ask
          for and when, re-asked by the server. The screen is at{' '}
          <Code>/projects/&lt;project&gt;/run-schedules</Code>.
        </P>
        <Note severity="warning">
          That screen currently has no entry in the navigation rail and nothing in the app links to
          it, so the address above is the only way to reach it today.
        </Note>
      </>
    ),
  },

  {
    id: 'ci-ingestion',
    title: 'Results from CI',
    summary: 'Posting automated results from your pipeline, and what happens to them.',
    body: (
      <>
        <P>
          Your pipeline posts a report file to the ingestion endpoint for the project. Four formats
          are understood: <strong>JUnit XML</strong>, <strong>TestNG XML</strong>,{' '}
          <strong>Cucumber JSON</strong> and <strong>Allure</strong>.
        </P>
        <Terminal>{`curl -X POST "$TCMS_URL/api/projects/<projectId>/ingestions" \\
  -H "Authorization: Bearer $TCMS_TOKEN" \\
  -F "file=@build/test-results/junit.xml"`}</Terminal>
        <P>
          Issue the token from <strong>Company → API tokens</strong>. A CI credential is normally
          given the <strong>Automation Service</strong> role, which can create runs, submit results
          and attach evidence and can do very little else.
        </P>
        <List
          items={[
            'Automated tests bind to TCMS cases by automation key.',
            'A binder screen lists tests that arrived and matched nothing, so unmatched results are visible rather than silently dropped. Binding one costs case:edit.',
            'Every ingestion is a tracked job with its own list, detail, and a report of what matched.',
            'Ingested results feed the stale-automation and undeclared-automation health signals, and the flake detection behind quarantine.',
          ]}
        />
        <P>
          Find it all under <strong>Execution → CI ingestion</strong>. If your results did not show
          up, that is the screen that says why.
        </P>
      </>
    ),
  },

  {
    id: 'documentation-and-reports',
    title: 'Documentation, artifacts and reports',
    summary: 'Where requirements live, what the artifact register is for, and the three reports.',
    body: (
      <>
        <H>Documentation</H>
        <Note>
          <strong>This is where requirements live.</strong> There is no separate requirement or
          acceptance-criterion record in this product — a requirement <em>is</em> a block on a
          documentation page, and traceability is the set of links from blocks to the cases that
          verify them.
        </Note>
        <List
          items={[
            'Write pages from blocks — paragraphs, headings, lists, code, images, and links to test cases.',
            'Organise pages into a tree, keep them as drafts, then publish them.',
            'Search across all documentation, and attach files to a page.',
            'Link cases and documentation both ways: a page cites a case, and the case shows which pages cite it.',
          ]}
        />

        <H>Artifacts</H>
        <P>
          The register of documents a project stands behind — test strategies, environment guides,
          sign-off records, pinned reports. Upload, list, download, edit and delete them; each
          carries a kind, a scope and a status.
        </P>

        <H>Reports</H>
        <Definitions
          items={[
            { term: 'Run summary', detail: 'One run’s outcome.' },
            {
              term: 'Aggregate execution',
              detail:
                'Results across many runs, including a section calling out inconsistent results and one showing where versions have diverged.',
            },
            {
              term: 'Coverage matrix',
              detail: 'Requirements to cases to their last result, with a document as its spine.',
            },
          ]}
        />
        <P>
          Reports generate in the background as tracked jobs. Read a finished one on screen or
          download it, save a configuration and re-run it later, and check the freshness stamp to
          see how current the data is.
        </P>
        <Note>
          Available formats are <strong>HTML</strong>, <strong>Markdown</strong> and{' '}
          <strong>JSON</strong>. PDF is offered but refused unless your operator installed the
          renderer — the product tells you which formats <em>this</em> server can actually produce
          rather than offering a choice that will fail.
        </Note>
      </>
    ),
  },

  {
    id: 'teams-and-tokens',
    title: 'People, teams and API tokens',
    summary: 'Inviting colleagues, teams, the org chart, and issuing credentials.',
    body: (
      <>
        <H>People and teams</H>
        <List
          items={[
            'Invite people to the company with a role, list the directory, and deactivate people who leave.',
            'Create teams and manage their membership.',
            'Grant a role to a team on a project — every member inherits it, and a change to the team changes who holds it.',
          ]}
        />
        <P>
          The <strong>Org chart</strong> shows everyone in the company, the roles they hold across
          projects and their team memberships. The people axis is the whole company; the project axis
          shows only the projects <em>you</em> can see. It has no permission gate — every member may
          open it.
        </P>

        <H>API tokens</H>
        <P>
          Issue a token for CI or for an AI agent from <strong>Company → API tokens</strong>. Costs{' '}
          <Code>token:issue</Code>, which Company Admins hold.
        </P>
        <List
          items={[
            'A token carries a role and can be scoped to a single project.',
            'The secret is shown once, at creation. There is no way to retrieve it afterwards.',
            'Set an expiry, see when each token was last used, and revoke one at any time.',
          ]}
        />
        <Note>
          <strong>A token never grants more than its owner has.</strong> Its reach is your own
          permissions, intersected with the token&apos;s role, intersected with the project it is
          scoped to — an intersection, never a union. Demoting the person who issued a token
          immediately shrinks the token.
        </Note>

        <H>Notifications</H>
        <P>
          Channels are configured per project under <strong>Project → Notifications</strong>: email
          or webhook. You are notified when work is assigned to you and when a run completes, and
          every delivery attempt is recorded, so a channel that stopped working is visible rather
          than silent.
        </P>
        <Note severity="warning">
          The review workflow does not currently raise notifications. Nobody is told that they have
          been asked to review, that their case was approved, or that changes were requested — so
          agree how reviewers get nudged rather than assuming the product does it.
        </Note>
      </>
    ),
  },

  {
    id: 'cli',
    title: 'The command-line tool',
    summary: 'Installing tcms, the 35 commands, and the two it deliberately does not have.',
    body: (
      <>
        <P>
          <Code>tcms</Code> is a command-line client for this instance. It needs a URL and an API
          token and nothing else: there is no config file and no keychain.
        </P>

        <H>Setup</H>
        <P>First issue the credential it runs as:</P>
        <TokenSteps purpose="ci" />
        <P>
          Then install it from this instance, which serves the tarball itself, and give it the two
          values it needs:
        </P>
        <CliSetup />
        <Note>
          <strong>Install the copy from the instance you use.</strong> The CLI is an adapter over
          that instance&apos;s API contract, and the tarball is built by the same release as the
          server beside it — so a copy taken from a different instance is a pairing nobody has run.{' '}
          <Code>…-latest.tgz</Code> always means &quot;what this instance is running now&quot;; a
          versioned filename pins a build. The tarball vendors every dependency, so the install needs
          no access to a public npm registry at all and works offline with <Code>--offline</Code>.
        </Note>

        <H>Reading</H>
        <Terminal>{`tcms whoami            # who this token acts as, and what it can reach
tcms format case       # what a payload must look like — reaches no instance
tcms project list      # projects this token can see
tcms case list         # search and filter test cases
tcms case show TC-42   # one case in full
tcms library list      # the reusable condition library
tcms suite list        # suites in the project
tcms suite show TS-7   # one suite, with the conditions it imposes
tcms plan list
tcms run list
tcms doc list
tcms doc show <page>   # one documentation page, with its blocks
tcms proposal list     # the review queue of proposed cases
tcms proposal show <id># one proposal, with its payload and grounding
tcms issue list        # the project's epics, stories, bugs, spikes and tasks
tcms issue show ACME-1 # one issue, its children, comments and the moves it may make
tcms issue queue       # the AI-eligible tasks assigned to you
tcms issue context ACME-1 # everything an assistant needs to work one task, in one read
tcms board list        # the boards over this project
tcms board show <name> # one board — its columns, its cards, and any column over its WIP limit
tcms backlog           # every story and task that is not done, in rank order
tcms label list        # the project's labels
tcms release list      # the releases, with stories done and tests passing per row
tcms release show 2.4  # can we ship it — and the stories that stand in the way
tcms roadmap           # releases on a timeline, with the epics targeting each
tcms issue cases ACME-1# the test cases a story is proven by, and their latest result
tcms import list       # migrations into this project, newest first
tcms import show <id>  # one import report — the counts, the mapping, and every dropped field
tcms time list         # your own timesheet for this period — entries, totals, and its id
tcms time queue        # timesheets waiting on you to approve
tcms time rollup ACME-1# estimate against approved and pending hours; --epic for the epic grain
tcms staffing directory# who has which skills, when they are free, and how booked they already are
tcms staffing project  # who is allocated to this project, and the ids the allocate verb takes`}</Terminal>

        <H>Writing</H>
        <Terminal>{`tcms case edit         # change a test case
tcms case propose      # file a generated case for a person to review
tcms suite create | suite edit | suite status
tcms plan create  | plan edit  | plan status
tcms run create   | run status
tcms result record     # record a verdict against one case in a run
tcms doc create | doc edit | doc link | doc unlink
tcms issue create | issue move | issue comment
tcms board move ACME-1 --column "In flight"  # a card to a column; a WIP breach prints and exits 0
tcms rank ACME-1 --after ACME-9              # exactly one of --after or --before
tcms label add | label set
tcms release create | release edit | release publish | release delete
tcms issue release ACME-1 --release 2.4      # target a story at a version, or --none
tcms issue cases set ACME-1 --case TC-311    # the cases that prove it; a replacement, not an addition
tcms issue attach-pr ACME-1 --pr-url <URL>   # report the pull request the work went into
tcms time log --issue ACME-1 --hours 2       # or --against MEETING | LEAVE | TRAINING | INTERNAL
tcms time submit <period>                    # your period stops being editable, by anybody
tcms time approve <period> [--reject --note TEXT | --withdraw]
tcms staffing allocate --person "Dana Petrov" --percent 50 --from 2026-09-01 --to 2026-11-30
tcms staffing absence --from 2026-09-24 --to 2026-09-24   # your own needs no permission
tcms review comment    # a remark on an open review`}</Terminal>

        <H>Options that apply everywhere</H>
        <Definitions
          items={[
            {
              term: <Code>--project KEY</Code>,
              detail: 'The project to act in. Needed when the token can see more than one.',
            },
            { term: <Code>--json</Code>, detail: 'Print the API response verbatim.' },
            {
              term: <Code>--url URL</Code>,
              detail: (
                <>
                  The instance, overriding <Code>TCMS_URL</Code>.
                </>
              ),
            },
            { term: <Code>--timeout SECS</Code>, detail: 'Override the request deadline.' },
            {
              term: <Code>--yes</Code>,
              detail:
                'Skip the confirmation on a command that removes something. Without it, and with '
                + 'no terminal to ask at, the command refuses rather than proceeding.',
            },
            {
              term: <Code>--dry-run</Code>,
              detail: 'Say what a write would do, and send nothing.',
            },
            { term: <Code>--version</Code>, detail: 'Print the client version.' },
          ]}
        />

        <H>Exit codes</H>
        <P>
          Ten of them, so a script can branch without parsing output — the difference between{' '}
          <em>rate limited, back off</em> and <em>this token will never be allowed to do this</em>{' '}
          is the whole reason they are not all <Code>1</Code>.
        </P>
        <Terminal>{`0 ok            2 usage        3 not authenticated   4 permission denied
5 not found     6 conflict     7 rate limited        8 timed out
9 unavailable   1 other        130 cancelled`}</Terminal>

        <H>What it will not do</H>
        <Definitions
          items={[
            {
              term: 'Create a test case',
              detail:
                'There is no tcms case create. Generated content is filed as a proposal for a person to accept, and case propose is the create arm.',
            },
            {
              term: 'Accept a proposal',
              detail:
                'There is no tcms case accept either, and it is structural rather than a policy: accepting requires a request carrying no token, and every request the CLI makes carries one. Accept proposals in the web app.',
            },
            {
              term: 'Attach or delete evidence',
              detail:
                'Evidence upload is a file upload, which this surface does not do. Attach evidence from the web app.',
            },
            {
              term: 'Administer the company',
              detail:
                'No user, team, token, single-sign-on or project-settings commands. Those are company-scope administration and stay in the web app.',
            },
            {
              term: 'Ingest CI results',
              detail:
                'Post the report file to the ingestion endpoint from your pipeline directly — see Results from CI.',
            },
          ]}
        />
        <P>
          A create is retry-safe: repeating a create that already succeeded returns the thing you
          made rather than making a second one. An edit refuses rather than overwriting somebody
          who changed the same record while you were working.
        </P>
      </>
    ),
  },

  {
    id: 'mcp',
    title: 'AI agent access (MCP)',
    summary: 'Connecting an AI assistant, the 37 tools, and the limits that make it safe.',
    body: (
      <>
        <P>
          TCMS speaks <strong>MCP</strong>, so an AI assistant can read and write your test
          repository directly rather than being pasted screenshots. There are two ways to connect
          and the first needs no installation.
        </P>

        <H>Connect an AI assistant</H>
        <P>
          <strong>Two minutes, and it needs no administrator.</strong>{' '}
          <RouterLink
            to="/account/assistants/new"
            className="text-steel no-underline hover:underline"
          >
            Connect an assistant
          </RouterLink>{' '}
          names it, scopes it to one project or all of them, and hands you a setup snippet for your
          own tool. What it can do is exactly what <em>you</em> can do — there is no separate
          permission list, and nothing there can grant it more than you hold. Everything below is
          the same connection described in full, plus what a company credential is for instead.
        </P>

        <McpSetup />

        <H>The tools</H>
        <Definitions
          items={[
            {
              term: 'Orientation — 3',
              detail:
                'tcms_whoami reports who the credential acts as and what it can reach. tcms_orient returns the project with its classification dimensions, execution statuses, suites, tags and case counts in one call — the vocabulary is per-project, so an agent that skips this guesses values that get refused. tcms_shape answers what a payload of a given kind must look like, and reaches no instance at all, so it is safe to call before anything is configured.',
            },
            {
              term: 'Reading — 17',
              detail:
                'tcms_search_cases · tcms_open_case · tcms_list_suites · tcms_list_library · tcms_list_artifacts · tcms_doc_tree · tcms_open_doc_page · tcms_list_plans · tcms_open_run · tcms_read_issue · tcms_read_board · tcms_read_release · tcms_read_timesheet · tcms_read_time_rollup · tcms_read_staffing · tcms_list_proposals · tcms_triage',
            },
            {
              term: 'Writing — 17',
              detail:
                'tcms_write_case · tcms_write_suite · tcms_write_doc_page · tcms_write_plan · tcms_write_run · tcms_record_result · tcms_review_comment · tcms_review_verdict · tcms_write_issue · tcms_write_backlog · tcms_write_release · tcms_write_artifact · tcms_write_timesheet · tcms_approve_timesheet · tcms_write_allocation · tcms_write_absence · tcms_report',
            },
          ]}
        />
        <P>
          The list is workflow-shaped rather than one tool per endpoint: 37 tools over roughly 98 API
          paths. Tool definitions cost the model context, so a mirror of the API would be a surface
          only the largest hosts could afford to load.
        </P>

        <H>What makes it safe to point at real data</H>
        <List
          items={[
            <>
              <strong>The credential is capped.</strong> An agent token carries a fixed{' '}
              <em>Agent</em> role, and its reach is still intersected with yours — it can never
              exceed the person who issued it.
            </>,
            <>
              <strong>Tools are filtered by permission.</strong> A narrowly-scoped token sees a
              narrower tool list, so the model does not attempt work it will be refused.
            </>,
            <>
              <strong>The session says up front what it cannot do</strong>, naming the permission
              that would unlock each missing tool — which is the thing you take to an administrator.
            </>,
            <>
              <strong>Deletions ask a person.</strong> A delete prompts for confirmation through your
              MCP host, and <em>refuses outright</em> when the host has no way to ask. A consent gate
              that quietly degrades to &quot;no prompt available, carry on&quot; is not a gate.
            </>,
            <>
              <strong>Nothing generated becomes a test case on its own.</strong> An agent files a
              proposal; a person accepts it.
            </>,
          ]}
        />
        <Note severity="warning">
          The confirmation prompt binds the MCP connection only. The same token used with{' '}
          <Code>curl</Code> or the CLI deletes with nobody to ask. What bounds a token everywhere is
          the intersection with its owner, the required expiry, revocation, soft delete and the audit
          trail — so scope tokens narrowly and expire them.
        </Note>

        <H>What an agent cannot do here</H>
        <Definitions
          items={[
            {
              term: 'Create a test case directly',
              detail:
                'tcms_write_case proposes; it does not create. Nothing on this surface can, by design.',
            },
            {
              term: 'Attach or delete evidence',
              detail:
                'Evidence is a file upload and a tool call carries no file. Since an agent can never attach evidence, it is not given the ability to remove somebody else’s either — that is the one irreversible delete in the product.',
            },
            {
              term: 'Register a file as an artifact',
              detail:
                'Same reason. An agent can register a documentation page, an external link or a pinned report, but not upload bytes.',
            },
            {
              term: 'Write the condition library',
              detail:
                'Reading it is fine; writing costs library:manage, which this surface deliberately does not carry, because that change is gated by an impact review a caller with no screen never sees.',
            },
            {
              term: 'Assign a reviewer',
              detail:
                'It can record verdicts and resolve threads, but naming an assignee needs the user and team directories, which are company-scope and outside what an agent token can read.',
            },
            {
              term: 'Touch users, teams, tokens or company settings',
              detail: 'None of it is on the surface. Company administration stays with people.',
            },
          ]}
        />
      </>
    ),
  },
];

/** Lookup by the id in the URL. Returns `undefined` for an id no section claims. */
export function findHelpSection(id: string | undefined): HelpSection | undefined {
  return helpSections.find((section) => section.id === id);
}
