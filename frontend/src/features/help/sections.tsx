import type { ReactNode } from 'react';
import { Code, Definitions, H, List, Note, P, Terminal } from './prose';
import { RoleMatrix } from './RoleMatrix';

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
                'In the left rail. Everything below it is scoped to the project you picked — switching projects changes what those screens show.',
            },
            {
              term: 'My work',
              detail:
                'Above the switcher, so it is not scoped by it: everything assigned to you across every project you can see.',
            },
            {
              term: 'Search cases',
              detail:
                'Also above the switcher. Finds a case across every project you can open, for when you do not know which project holds it.',
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
    summary: 'The six roles, exactly what each one can do, and how a grant reaches you.',
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
                  Costs <Code>project:members</Code> on that project — held by Test Lead, Company
                  Admin and Instance Admin. A Test Lead can staff their own project without being
                  able to staff anybody else&apos;s. Do it from{' '}
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
              <strong>Six roles, and they are fixed.</strong> There are no custom roles, not even
              clone-and-edit. A team needing something between two tiers gets the higher one.
            </>,
            <>
              <strong>The code checks permissions, never role names</strong>, and there is no
              wildcard permission. &quot;Holds everything&quot; is worked out by comparing against
              the catalogue, so a gap shows up as a gap.
            </>,
            <>
              <strong>Test Lead adds exactly seven things over Test Engineer</strong>: the three
              approvals, plus permission to change a project&apos;s settings, its members, its
              integrations and to read its audit log.
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
          The project <strong>Overview</strong> is the dashboard: what needs attention, how cases
          break down by lifecycle and by classification, and how execution is trending.
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
              term: 'Execution statuses',
              detail:
                'Name your own result statuses and set the order they appear in. "Passed" and "Failed" are not fixed vocabulary.',
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
          ]}
        />
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
            'Link a failed result out to a defect in your tracker.',
            'Assign executions to people — individually, or split a selection across a list of people round-robin.',
            'Clone a run to repeat it, and close it when it is done.',
            'Runs carry release version, build number, commit SHA and branch, so a run traces back to what was built.',
            'Runs move Not Started → In Progress → Completed → Aborted.',
          ]}
        />

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
              detail: 'The cross-project execution board, with drag-and-drop.',
            },
            {
              term: 'My work',
              detail: 'Everything assigned to you, across every project.',
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
    summary: 'Installing tcms, the 27 commands, and the two it deliberately does not have.',
    body: (
      <>
        <P>
          <Code>tcms</Code> is a command-line client for this instance. It needs a URL and an API
          token and nothing else: there is no config file and no keychain.
        </P>

        <H>Setup</H>
        <List
          ordered
          items={[
            <>
              Issue a token: <strong>Company → API tokens → Issue token</strong>. Copy the secret —
              it is shown once.
            </>,
            <>Install it from this instance, which serves the tarball itself:</>,
          ]}
        />
        <Terminal>{`npm install -g <your-tcms-url>/downloads/tcms-cli-latest.tgz

export TCMS_URL=<your-tcms-url>
export TCMS_TOKEN=<the token you were shown once>

tcms whoami`}</Terminal>
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
tcms project list      # projects this token can see
tcms case list         # search and filter test cases
tcms case show TC-42   # one case in full
tcms library list      # the reusable condition library
tcms suite list        # suites in the project
tcms suite show TS-7   # one suite, with the conditions it imposes
tcms plan list
tcms run list
tcms doc list
tcms doc show <page>   # one documentation page, with its blocks`}</Terminal>

        <H>Writing</H>
        <Terminal>{`tcms case edit         # change a test case
tcms case propose      # file a generated case for a person to review
tcms suite create | suite edit | suite status
tcms plan create  | plan edit  | plan status
tcms run create   | run status
tcms result record     # record a verdict against one case in a run
tcms doc create | doc edit | doc link | doc unlink
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
          ]}
        />

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
    summary: 'Connecting an AI assistant, the 23 tools, and the limits that make it safe.',
    body: (
      <>
        <P>
          TCMS speaks <strong>MCP</strong>, so an AI assistant can read and write your test
          repository directly rather than being pasted screenshots. There are two ways to connect
          and the first needs no installation.
        </P>

        <H>Hosted — nothing to install</H>
        <P>
          Point your MCP host at the instance and send your API token as the bearer credential:
        </P>
        <Terminal>{`<your-tcms-url>/mcp`}</Terminal>
        <P>
          This is the shape to prefer. The server holds no credential of its own — the whole tool
          list and the instructions are built <em>per session</em> from your token, so what a session
          can see is exactly what that token can reach. It is upgraded by your operator along with
          the instance, so it cannot drift from the API it adapts.
        </P>
        <Note severity="warning">
          The hosted endpoint is off unless your operator turned it on for this instance. If the URL
          does not answer, that is what to ask them about.
        </Note>

        <H>Local, over stdio</H>
        <P>For an agent running on your own machine:</P>
        <Terminal>{`npm install -g <your-tcms-url>/downloads/tcms-mcp-latest.tgz`}</Terminal>
        <Terminal>{`{
  "mcpServers": {
    "tcms": {
      "command": "tcms-mcp",
      "env": {
        "TCMS_URL": "<your-tcms-url>",
        "TCMS_TOKEN": "<your token>"
      }
    }
  }
}`}</Terminal>
        <P>
          Set <Code>TCMS_PROJECT</Code> as well to bind every session to one project when the token
          can see several.
        </P>

        <H>The tools</H>
        <Definitions
          items={[
            {
              term: 'Orientation — 2',
              detail:
                'tcms_whoami reports who the credential acts as and what it can reach. tcms_orient returns the project with its classification dimensions, execution statuses, suites, tags and case counts in one call — the vocabulary is per-project, so an agent that skips this guesses values that get refused.',
            },
            {
              term: 'Reading — 10',
              detail:
                'tcms_search_cases · tcms_open_case · tcms_list_suites · tcms_list_library · tcms_list_artifacts · tcms_doc_tree · tcms_open_doc_page · tcms_list_plans · tcms_open_run · tcms_triage',
            },
            {
              term: 'Writing — 11',
              detail:
                'tcms_write_case · tcms_write_suite · tcms_write_doc_page · tcms_write_plan · tcms_write_run · tcms_record_result · tcms_review_comment · tcms_review_verdict · tcms_write_work_item · tcms_write_artifact · tcms_report',
            },
          ]}
        />
        <P>
          The list is workflow-shaped rather than one tool per endpoint: 23 tools over roughly 88 API
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
