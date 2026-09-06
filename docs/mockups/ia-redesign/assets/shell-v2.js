/*
 * Shell v2 — the navigation/IA redesign proposal, drawn.
 *
 * Starting point was `docs/mockups/assets/shell.js`, which is left untouched: this file is a
 * separate builder so the existing mockups keep rendering the IA they were drawn against.
 * What it draws is `docs/specs/2026-09-06-navigation-ia-redesign-proposal.md` §1–§5 and §9 —
 * three scope zones, a FLAT project zone in usage order, the one surviving folder
 * (`Administration ▸`), the project switcher as the Project zone header, the reserved company
 * slot, shell-owned breadcrumbs, the ⌘K control, the capped page-action slot and the user menu.
 * Labels, order and gates are the proposal's, verbatim.
 *
 * REVISED 2026-09-06 with the proposal. The first draft's `Repository ▸` / `Quality ▸` subject
 * folders are gone: they filed the most-used screens one click down and named a distinction
 * nobody working the board makes. Five rows they held are now TABS on the screen they extend
 * (see TABS below) — a tab is not a rail row, and this file is where that is stated once.
 *
 * REVISED AGAIN 2026-09-06, second product-owner pass. Two things moved:
 *   · `Timesheets` LEFT THE RAIL (reversal R11) and is in the user menu with `Timesheet
 *     approvals` under it. The rule that decides it: the rail is a place in the product scoped
 *     by the project switcher or by the company; a timesheet is the person's own fortnight, so
 *     it fails that test and passes the user menu's. The `You` zone is now two rows, and the
 *     row counts are 20 for a caller holding every gate and 17 for a Test Engineer.
 *   · The Work strip carries THREE boards, not two — PM · Dev · QA — over an eleven-status
 *     workflow (§2.3), each segment badged with what is waiting *at its door* (§8.3): the PM
 *     board's is `Ready to deploy`, Dev's is `Ready to develop`, QA's is `Ready for test`.
 *
 * Body attributes:
 *   data-role      Test Engineer | Project Manager | Finance | Company Admin
 *   data-rail      expanded (default) | collapsed
 *   data-active    id of the current rail item
 *   data-open      space-separated folder ids to force open (default: the one holding data-active)
 *   data-flyout    folder id whose collapsed flyout is drawn open
 *   data-crumbs    "Label>href|Label>href|Current"   (shell-owned — proposal reversal R2)
 *   data-topslot   "Primary>label|More"  document-mode pages only; capped at one + overflow
 *   data-menu      "open" draws the user menu open
 *   data-shell     "none" — page draws its own frames; only the builders are exported
 *   <template id="caption">  the render's caption strip, as HTML
 */

/* ── The permission matrix, from docs/specs/permission-matrix-summary.md ──
 * Only the eight keys the rail and the settings landing actually gate on.
 * PROJECT scope: issue:import · integration:manage · project:audit are Project Manager,
 * Test Lead, Company Admin and Instance Admin — a Test Engineer holds none of the three.
 * COMPANY scope: client:read is Account Manager upward; billing:read is Finance upward;
 * token:issue · audit:view · company:idp are Company Admin upward.
 * Finance and Account Manager hold no `project:read`, deliberately — at company scope either
 * key would open every project in the company, present and future — so the PROJECT zone is
 * not drawn for them at all.
 */
const ROLE_PERMS = {
  'Test Engineer': ['project:read'],
  'Project Manager': ['project:read', 'issue:import', 'integration:manage', 'project:audit'],
  Finance: ['client:read', 'billing:read'],
  'Company Admin': [
    'project:read', 'issue:import', 'integration:manage', 'project:audit',
    'client:read', 'billing:read', 'token:issue', 'company:idp', 'audit:view',
  ],
};
const ROLES = Object.keys(ROLE_PERMS);

const ICONS = {
  mywork: '◧', timesheets: '◷', projects: '▤',
  overview: '⌂', work: '▦', releases: '⬡', analytics: '◔', team: '⧉', settings: '⚙',
  repository: '❑', cases: '✓', suites: '☰', documents: '▭', artifacts: '◰', proposed: '✧',
  quality: '◫', plans: '◫', runs: '▷', schedules: '◷', ci: '⟳', reports: '◲',
  orgchart: '⚇', people: '☷', directory: '☷', capacity: '⧉', teams: '⚇',
  clients: '◈', billing: '⛁', admin: '⚿', tokens: '⚿', sso: '⛨', audit: '❐',
  access: '☖', search: '⌕', jump: '↵', place: '⌖',
};

/* ── The rail, exactly as proposal §1 orders it ───────────────────────────
 * The project zone is FLAT and ordered by how often a screen is opened, not by subject:
 * Work first because the day starts and ends on the board; Overview seventh because it is the
 * project's dashboard, read rather than worked (reversal R8). One folder survives anywhere in
 * the rail — `Administration ▸` — because all three of its children are gated and a closed
 * folder is where their absence is least conspicuous.
 */
const NAV = [
  {
    zone: 'you',
    label: 'You',
    items: [
      { id: 'mywork', label: 'My work', href: '07-my-work-home.html', icon: 'mywork', badge: '8' },
      { id: 'projects', label: 'Projects', href: '#', icon: 'projects' },
      /* `Timesheets` is deliberately NOT here — reversal R11 moved it to the user menu, and the
       * one place it may now appear is buildUserMenu() below. Two rows, not three. */
    ],
  },
  {
    zone: 'project',
    label: 'Project',
    needs: 'project:read',
    switcher: true,
    items: [
      { id: 'work', label: 'Work', href: '12-dev-board.html', icon: 'work' },
      { id: 'cases', label: 'Test cases', href: '16-test-cases-tabs.html', icon: 'cases' },
      { id: 'plans', label: 'Test plans', href: '#', icon: 'plans' },
      { id: 'runs', label: 'Test runs', href: '17-test-runs-tabs.html', icon: 'runs' },
      { id: 'releases', label: 'Releases', href: '#', icon: 'releases' },
      { id: 'documents', label: 'Documents', href: '#', icon: 'documents' },
      { id: 'overview', label: 'Overview', href: '01-rail-expanded-pm.html', icon: 'overview' },
      { id: 'analytics', label: 'Analytics', href: '#', icon: 'analytics' },
      { id: 'reports', label: 'Test reports', href: '#', icon: 'reports' },
      { id: 'team', label: 'Team', href: '#', icon: 'team' },
      { id: 'settings', label: 'Settings', href: '04-settings-landing.html', icon: 'settings' },
    ],
  },
  {
    zone: 'company',
    label: 'Company',
    items: [
      { id: 'orgchart', label: 'Org chart', href: '#', icon: 'orgchart' },
      { id: 'directory', label: 'Directory', href: '#', icon: 'directory' },
      { id: 'capacity', label: 'Capacity', href: '#', icon: 'capacity' },
      { id: 'teamsroles', label: 'Teams & roles', href: '#', icon: 'teams' },
      { id: 'clients', label: 'Clients', href: '#', icon: 'clients', gate: 'client:read' },
      { id: 'billing', label: 'Billing', href: '#', icon: 'billing', gate: 'billing:read' },
      {
        folder: 'admin', label: 'Administration', icon: 'admin',
        children: [
          { id: 'tokens', label: 'API tokens', href: '#', icon: 'tokens', gate: 'token:issue' },
          { id: 'sso', label: 'Single sign-on', href: '#', icon: 'sso', gate: 'company:idp' },
          { id: 'coaudit', label: 'Company audit', href: '#', icon: 'audit', gate: 'audit:view' },
        ],
      },
    ],
  },
];

/* ── Tabs — the tail that is NOT in the rail (proposal §1, §2.6) ──────────
 * Five screens that had rail rows in the first draft are a segmented strip on the screen they
 * extend, and the Work views are the same pattern the build already ships. Each tab keeps its
 * own URL, so a pasted link still lands and the active tab is read from the route, never stored.
 * Badges here are the proposal's §8.3 list, each an *awaiting you* number and never a total.
 */
const TABS = {
  /* Three boards in position order, then the four non-board views (§2.8). Each board's badge is
   * what is waiting AT ITS DOOR (§8.3) — never the board's total, and never the Blocked count,
   * which shows on each board's Blocked column header instead. */
  work: [
    { id: 'pm', label: 'PM board', href: '18-pm-board.html', route: '…/work?view=board&board=pm', badge: '3' },
    { id: 'dev', label: 'Dev board', href: '12-dev-board.html', route: '…/work?view=board&board=dev', badge: '6' },
    { id: 'qa', label: 'QA board', href: '13-qa-board.html', route: '…/work?view=board&board=qa', badge: '5' },
    { id: 'backlog', label: 'Backlog', href: '#', route: '…/work?view=backlog' },
    { id: 'table', label: 'Table', href: '#', route: '…/work?view=table' },
    { id: 'timeline', label: 'Timeline', href: '#', route: '…/work?view=timeline' },
    { id: 'calendar', label: 'Calendar', href: '#', route: '…/work?view=calendar' },
  ],
  cases: [
    { id: 'cases', label: 'Cases', href: '16-test-cases-tabs.html', route: '…/cases' },
    { id: 'suites', label: 'Suites', href: '#', route: '…/suites', was: 'Suites' },
    { id: 'proposals', label: 'Proposals', href: '#', route: '…/generated-cases', badge: '3', was: 'Generated cases' },
  ],
  runs: [
    { id: 'runs', label: 'Runs', href: '17-test-runs-tabs.html', route: '…/runs' },
    { id: 'schedules', label: 'Schedules', href: '#', route: '…/run-schedules', was: 'Run schedules' },
    { id: 'ci', label: 'CI results', href: '#', route: '…/ingestions', badge: '4', was: 'CI ingestion' },
  ],
  docs: [
    { id: 'pages', label: 'Pages', href: '#', route: '…/docs' },
    { id: 'artifacts', label: 'Artifacts', href: '#', route: '…/artifacts', was: 'Artifacts' },
  ],
};

function buildTabs(key, current) {
  const items = TABS[key] || [];
  return items.map((t) => {
    const cur = t.id === current ? ' aria-current="page"' : '';
    const badge = t.badge
      ? `<span class="tbadge" aria-label="${esc(t.badge)} awaiting you">${esc(t.badge)}</span>` : '';
    return `<a href="${t.href}"${cur}>${esc(t.label)}${badge}</a>`;
  }).join('');
}

/* Project settings landing — one card per section, one live fact (proposal §4). */
const SETTINGS = [
  { id: 'general', label: 'General', icon: 'settings', fact: 'ACME · 4 issue types · fortnightly time · budget on' },
  { id: 'workflow', label: 'Workflow', icon: 'work', fact: '11 statuses · 3 kinds · 22 legal moves' },
  { id: 'boards', label: 'Boards', icon: 'work', fact: 'PM (5+Done) · Dev (5) · QA (5) · every status covered', href: '15-board-coverage.html' },
  { id: 'repos', label: 'Code repositories', icon: 'repository', fact: '3 bound' },
  { id: 'classification', label: 'Classification', icon: 'suites', fact: '5 vocabularies' },
  { id: 'review', label: 'Review policy', icon: 'cases', fact: '2 reviewers required on approve' },
  { id: 'imports', label: 'Imports', icon: 'ci', fact: 'last: Jira, 12 Aug', gate: 'issue:import' },
  { id: 'notifications', label: 'Notifications', icon: 'proposed', fact: '2 channels', gate: 'integration:manage' },
  { id: 'tracker', label: 'Issue tracker', icon: 'ci', fact: 'Jira · bound to ACME-*', gate: 'integration:manage' },
  { id: 'access', label: 'Access & roles', icon: 'access', fact: '14 members · 3 inherited from company' },
  { id: 'audit', label: 'Project audit', icon: 'audit', fact: 'last event 2 min ago', gate: 'project:audit' },
];

/* Subnav row every settings subpage carries (proposal §4). No route moves. */
const SETTINGS_SUBNAV = SETTINGS;

function has(role, perm) {
  return !perm || (ROLE_PERMS[role] || []).includes(perm);
}
function icon(k) {
  return `<span class="ic" aria-hidden="true">${ICONS[k] || '·'}</span>`;
}
function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/* An item survives its gate; a folder whose every child is hidden is itself hidden. */
function visibleItems(items, role) {
  return items
    .map((it) => {
      if (!it.folder) return has(role, it.gate) ? it : null;
      const kids = it.children.filter((c) => has(role, c.gate));
      return kids.length ? { ...it, children: kids } : null;
    })
    .filter(Boolean);
}

function railLink(it, active, collapsed) {
  const cur = it.id === active ? ' aria-current="page"' : '';
  const badge = it.badge ? `<span class="nbadge">${esc(it.badge)}</span>` : '';
  const title = collapsed ? ` title="${esc(it.label)}"` : '';
  const name = collapsed ? ` aria-label="${esc(it.label)}"` : '';
  return `<a class="rail-link" href="${it.href}"${cur}${title}${name}>${icon(it.icon)}<span>${esc(it.label)}</span>${badge}</a>`;
}

function folderRow(f, opts) {
  const { active, open, collapsed, flyout } = opts;
  const hasActive = f.children.some((c) => c.id === active);
  const isOpen = open.has(f.folder) || (!open.size && hasActive);
  const kidBadges = f.children.reduce((n, c) => n + (c.badge ? Number(c.badge) : 0), 0);

  if (collapsed) {
    const fly = flyout === f.folder
      ? `<div class="flyout" role="group" aria-label="${esc(f.label)}">
           <div class="fly-label">${esc(f.label)}</div>
           ${f.children.map((c) => railLink(c, active, false)).join('')}
         </div>`
      : '';
    const mark = hasActive ? ' has-active' : '';
    // The roll-up badge survives the collapse: a folder that is shut is the only thing standing
    // between the reader and work waiting for them, so hiding the count hides the reason to open it.
    // With the revised §1 this rule has nothing left to act on — the one surviving folder,
    // Administration, holds no badged child — but it is kept so a folder added later behaves.
    const cb = kidBadges ? `<span class="nbadge">${kidBadges}</span>` : '';
    const label = kidBadges ? `${f.label} — ${kidBadges} awaiting you` : f.label;
    return `<div class="fly-host">
      <button class="folder-btn${mark}" type="button" aria-expanded="${flyout === f.folder}"
              aria-label="${esc(label)}" title="${esc(label)}" data-folder="${f.folder}">
        ${icon(f.icon)}<span>${esc(f.label)}</span>${cb}
      </button>${fly}</div>`;
  }

  const badge = !isOpen && kidBadges
    ? `<span class="nbadge">${kidBadges}</span>` : '';
  return `<div class="folder">
    <button class="folder-btn${hasActive ? ' has-active' : ''}" type="button"
            aria-expanded="${isOpen}" aria-controls="fld-${f.folder}" data-folder="${f.folder}">
      ${icon(f.icon)}<span>${esc(f.label)}</span>${badge}<span class="cv" aria-hidden="true">▸</span>
    </button>
    <div class="folder-kids" id="fld-${f.folder}"${isOpen ? '' : ' hidden'}>
      ${f.children.map((c) => railLink(c, active, false)).join('')}
    </div>
  </div>`;
}

/*
 * buildRail — the whole proposal §1 rail for one role and one state.
 * Exported so the comparison and drawer renders can draw a rail without a whole shell.
 */
function buildRail(opts = {}) {
  const role = opts.role || 'Project Manager';
  const active = opts.active || '';
  const collapsed = !!opts.collapsed;
  const flyout = opts.flyout || '';
  const open = new Set((opts.open || '').split(/\s+/).filter(Boolean));
  const proj = (opts.project || 'ACME|Acme Payments').split('|');
  const parts = [];

  // Collapsed, the wordmark's mark IS the expand control — at 64px there is no room for both,
  // and a collapsed rail with no way back out is a trap.
  parts.push(collapsed
    ? `<div class="rail-brand">
        <button class="rail-collapse mark-btn" type="button" aria-label="Expand the rail" title="Expand">T</button>
      </div>`
    : `<div class="rail-brand">
        <span class="mark">T</span>
        <span>TCMS<small>IA proposal</small></span>
        <span class="spacer"></span>
        <button class="rail-collapse" type="button" aria-label="Collapse the rail" title="Collapse">⇤</button>
      </div>`);

  NAV.forEach((zone) => {
    if (!has(role, zone.needs)) return;
    const items = visibleItems(zone.items, role);
    if (!items.length) return;

    parts.push(`<nav class="zone" aria-label="${esc(zone.label)}">`);
    if (zone.switcher) {
      // Reversal R1: the switcher IS the zone header, next to the rows it changes.
      parts.push(`<button class="zone-switch" type="button" data-mock="Project switcher"
          aria-label="Project: ${esc(proj[1])}. Switch project">
        <span class="key">${esc(proj[0])}</span>
        ${collapsed ? '' : `<span class="nm"><span class="zl">Project</span>${esc(proj[1])}</span><span class="cv" aria-hidden="true">▾</span>`}
      </button>`);
    } else {
      parts.push(`<div class="zone-label">${esc(zone.label)}</div>`);
    }
    items.forEach((it) => {
      parts.push(it.folder
        ? folderRow(it, { active, open, collapsed, flyout })
        : railLink(it, active, collapsed));
    });
    parts.push('</nav>');
  });

  const rail = document.createElement('div');
  rail.className = `rail v2${collapsed ? ' is-collapsed' : ''}`;
  rail.innerHTML = parts.join('');
  return rail;
}

/* The rail as it is built TODAY — brief §4.1, verbatim, for the before/after render. */
const LEGACY = [
  { label: '', items: [['My work', 'mywork'], ['Timesheets', 'timesheets'], ['Billing  [billing:read]', 'billing'], ['All projects', 'projects']] },
  { label: 'Repository', items: [['Overview', 'overview'], ['Test cases', 'cases'], ['Documentation', 'documents']] },
  { label: 'Execution', items: [['Test plans', 'plans'], ['Test runs', 'runs'], ['Work', 'work'], ['Releases', 'releases'], ['Staffing', 'team'], ['Reports', 'reports'], ['Analytics', 'analytics'], ['CI ingestion', 'ci']] },
  { label: 'Authoring', items: [['Generated cases', 'proposed']] },
  { label: 'Project', items: [['Artifacts', 'artifacts'], ['Access & roles', 'access'], ['General', 'settings'], ['Workflow', 'work'], ['Boards', 'work'], ['Repositories', 'repository'], ['Imports  [issue:import]', 'ci'], ['Classification', 'suites'], ['Review', 'cases'], ['Notifications  [integration:manage]', 'proposed'], ['Issue tracker  [integration:manage]', 'ci'], ['Audit trail  [project:audit]', 'audit']] },
  { label: 'Company', items: [['Org chart', 'orgchart'], ['Clients  [client:read]', 'clients'], ['Staffing', 'capacity'], ['Directory', 'directory'], ['Teams & roles', 'teams'], ['API tokens  [token:issue]', 'tokens'], ['Single sign-on  [company:idp]', 'sso'], ['Audit trail  [audit:view]', 'audit']] },
];

function buildLegacyRail() {
  const parts = [`<div class="rail-brand">
      <span class="mark">T</span><span>TCMS<small>as built</small></span>
      <span class="spacer"></span>
      <button class="rail-collapse" type="button" aria-label="Collapse the rail" title="Collapse">⇤</button>
    </div>`];
  LEGACY.forEach((g) => {
    parts.push(`<nav class="rail-group" aria-label="${esc(g.label || 'Primary')}">`);
    if (g.label) parts.push(`<div class="rail-label">${esc(g.label)}</div>`);
    g.items.forEach(([label, ic]) => {
      const m = label.match(/^(.*?)\s\s\[(.+)\]$/);
      const text = m ? m[1] : label;
      const gate = m ? `<span class="count">${esc(m[2])}</span>` : '';
      parts.push(`<a class="rail-link" href="#">${icon(ic)}<span>${esc(text)}</span>${gate}</a>`);
    });
    parts.push('</nav>');
  });
  const rail = document.createElement('div');
  rail.className = 'rail flatrail';
  rail.innerHTML = parts.join('');
  return rail;
}

/* ── Header ────────────────────────────────────────────────────────────── */

function buildCrumbs() {
  const raw = document.body.dataset.crumbs || '';
  if (!raw) return '';
  const bits = raw.split('|').map((seg, i, all) => {
    const [label, href] = seg.split('>');
    const last = i === all.length - 1;
    if (last || !href) return `<span class="here">${esc(label)}</span>`;
    return `<a href="${href}">${esc(label)}</a><span class="sep">/</span>`;
  });
  return `<nav class="crumbs" aria-label="Breadcrumb">${bits.join('')}</nav>`;
}

function buildTopSlot() {
  const raw = document.body.dataset.topslot || '';
  if (!raw) return '';
  // Capped at one primary action plus one overflow — the header-wrap fix (proposal §2, §8).
  const [primary, more] = raw.split('|');
  return `<div class="topslot">
    <button class="btn btn-primary btn-sm" data-mock="${esc(primary)}">${esc(primary)}</button>
    ${more ? `<button class="btn btn-sm btn-icon" data-mock="${esc(more)}" aria-label="${esc(more)}">⋯</button>` : ''}
  </div>`;
}

/*
 * The user menu, in proposal §4's order exactly — and §4's order is an argument, not a list.
 * The person's own things come FIRST, above the account group, because the two rows at the top
 * are the ones that moved: `Timesheets` left the rail (R11) and `Timesheet approvals` came with
 * it. The rule that put them here: the rail is a place in the product scoped by the project
 * switcher or by the company, and a timesheet is neither — it is the person's own fortnight.
 *
 * `Timesheet approvals` is listed for EVERYONE and carries NO COUNT. Both are deliberate and
 * both are recorded decisions: it is listed because the page explains its own refusal
 * (disable-don't-hide), and it is countless because no company-wide "where may I approve" read
 * exists to ask — §8.3's badge table says `none` on that row, alone among its seven.
 */
function buildUserMenu(role) {
  const open = document.body.dataset.menu === 'open';
  return `<div class="umenu-host">
    <button class="avatar" id="user-menu" type="button" aria-haspopup="true" aria-expanded="${open}"
            aria-label="Account menu — Dana Petrov">DP</button>
    <div class="umenu" id="umenu-pop"${open ? '' : ' hidden'}>
      <div class="who"><strong>Dana Petrov</strong><span>${esc(role)} on ACME, BETA</span></div>
      <a href="#" data-mock="Timesheets — /timesheets">Timesheets</a>
      <a href="#" data-mock="Timesheet approvals — /timesheets/approvals">Timesheet approvals</a>
      <div class="sepr"></div>
      <div class="grp">Account</div>
      <a href="#" data-mock="Security">Security</a>
      <a href="#" data-mock="Tracker connections">Tracker connections</a>
      <a href="#" data-mock="AI assistants">AI assistants</a>
      <div class="sepr"></div>
      <a href="08-your-access.html">Your access</a>
      <a href="#" data-mock="Keyboard shortcuts">Keyboard shortcuts</a>
      <a href="#" data-mock="User guide">User guide</a>
      <div class="sepr"></div>
      <a href="#" data-mock="Sign out">Sign out</a>
    </div>
  </div>`;
}

function buildTopbar(role, scheme) {
  const bar = document.createElement('header');
  bar.className = 'topbar';
  bar.innerHTML = `
    ${/* Company slot (new) — reserved for M6; static text until then. */ ''}
    <span class="co-slot" title="Reserved for the M6 company switcher">Acme Ltd <span class="m6">M6</span></span>
    ${buildCrumbs()}
    <span class="spacer"></span>
    <button class="search" type="button" id="open-palette" aria-label="Search cases, or jump to a place">
      ⌕ Search cases, or jump to… <kbd>⌘K</kbd>
    </button>
    ${buildTopSlot()}
    <label class="sel" style="gap:4px">
      <span style="color:var(--color-text-ghost)">as</span>
      <select id="role-switch" aria-label="View the product as" style="background:transparent;border:0;color:inherit;font:inherit;font-size:12px;outline:none">
        ${ROLES.map((r) => `<option${r === role ? ' selected' : ''}>${esc(r)}</option>`).join('')}
      </select>
    </label>
    <button class="btn btn-icon" id="scheme-toggle" aria-label="Switch colour scheme" title="Light / dark">
      ${scheme === 'dark' ? '☾' : '☀'}
    </button>
    ${buildUserMenu(role)}`;
  return bar;
}

function buildMockbar() {
  const el = document.createElement('div');
  el.className = 'mockbar';
  el.innerHTML = `<span class="tagm">Mockup</span>
    <span>Static render of the IA proposal — nothing is saved, and no route here is the real app.</span>
    <span class="spacer"></span>
    <a href="index.html">All renders</a>`;
  return el;
}

function buildCaption() {
  const tpl = document.getElementById('caption');
  if (!tpl) return null;
  const el = document.createElement('div');
  el.className = 'capstrip';
  el.innerHTML = `<span class="tagm">Shows</span><div>${tpl.innerHTML}</div>`;
  return el;
}

function toast(msg) {
  let wrap = document.querySelector('.toast-wrap');
  if (!wrap) {
    wrap = document.createElement('div');
    wrap.className = 'toast-wrap';
    wrap.setAttribute('role', 'status');
    wrap.setAttribute('aria-live', 'polite');
    document.body.appendChild(wrap);
  }
  const t = document.createElement('div');
  t.className = 'toast';
  t.innerHTML = `<span class="k">Mockup</span><span>${esc(msg)}</span>`;
  wrap.appendChild(t);
  setTimeout(() => t.remove(), 2600);
}

function getScheme() {
  try { return localStorage.getItem('tcms-mock-scheme') || 'dark'; } catch { return 'dark'; }
}

/*
 * The role and the rail state are page attributes, but both controls that change them live in
 * the render, so a query parameter overrides the attribute. That keeps every file's default
 * honest (01 IS a Project Manager) while still letting a reader drive it from the same page.
 */
function param(name) {
  try { return new URLSearchParams(location.search).get(name); } catch { return null; }
}
function withParam(name, value) {
  const q = new URLSearchParams(location.search);
  q.set(name, value);
  return `${location.pathname}?${q.toString()}`;
}

/* Settings landing cards and the subnav, both filtered by the same test: can it be read? */
function settingsCards(role) {
  return SETTINGS.filter((s) => has(role, s.gate)).map((s) => `
    <a class="setcard" href="${s.href || '05-settings-subpage.html'}">
      <div class="hd">${icon(s.icon)}<strong>${esc(s.label)}</strong>${s.gate ? `<span class="gate">${esc(s.gate)}</span>` : ''}</div>
      <div class="fact">${esc(s.fact)}</div>
    </a>`).join('');
}
function settingsSubnav(role, current) {
  return SETTINGS_SUBNAV.filter((s) => has(role, s.gate)).map((s) => `
    <a href="${s.href || '05-settings-subpage.html'}"${s.id === current ? ' aria-current="page"' : ''}>${esc(s.label)}</a>`).join('');
}

window.TCMSNav = {
  NAV, TABS, ROLES, ROLE_PERMS, SETTINGS, ICONS,
  buildRail, buildLegacyRail, buildTabs, visibleItems, has, icon, esc,
  settingsCards, settingsSubnav, toast,
};

document.addEventListener('DOMContentLoaded', () => {
  const body = document.body;
  const role = (ROLES.includes(param('role')) && param('role')) || body.dataset.role || 'Project Manager';
  body.dataset.role = role;
  if (param('rail')) body.dataset.rail = param('rail');
  const scheme = getScheme();
  document.documentElement.setAttribute('data-color-scheme', scheme);

  // Pages that place their own frames (comparisons, the phone) only want the exports.
  if (body.dataset.shell !== 'none') {
    const content = document.querySelector('.page');
    const main = document.createElement('div');
    main.className = 'main';
    main.appendChild(buildTopbar(role, scheme));
    main.appendChild(buildMockbar());
    const cap = buildCaption();
    if (cap) main.appendChild(cap);
    if (content) main.appendChild(content);

    const shell = document.createElement('div');
    shell.className = 'shell';
    shell.appendChild(buildRail({
      role,
      active: body.dataset.active,
      collapsed: body.dataset.rail === 'collapsed',
      flyout: body.dataset.flyout,
      open: body.dataset.open,
      project: body.dataset.proj,
    }));
    shell.appendChild(main);
    body.prepend(shell);
  } else {
    const cap = buildCaption();
    const bar = buildMockbar();
    const page = document.querySelector('.page');
    if (page) {
      if (cap) page.parentNode.insertBefore(cap, page);
      page.parentNode.insertBefore(bar, cap || page);
    }
  }

  // Fill any slot a page asked the shell for.
  document.querySelectorAll('[data-fill="settings-cards"]').forEach((el) => { el.innerHTML = settingsCards(role); });
  document.querySelectorAll('[data-fill="settings-subnav"]').forEach((el) => { el.innerHTML = settingsSubnav(role, el.dataset.current); });
  // A tab strip is drawn from the same model as the rail, so a row that moved out of one and
  // into the other cannot end up in both — or in neither.
  document.querySelectorAll('[data-fill="tabs"]').forEach((el) => { el.innerHTML = buildTabs(el.dataset.tabs, el.dataset.current); });

  document.getElementById('scheme-toggle')?.addEventListener('click', () => {
    const next = document.documentElement.getAttribute('data-color-scheme') === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-color-scheme', next);
    try { localStorage.setItem('tcms-mock-scheme', next); } catch { /* private mode */ }
    document.getElementById('scheme-toggle').textContent = next === 'dark' ? '☾' : '☀';
  });

  document.getElementById('role-switch')?.addEventListener('change', (e) => {
    location.href = withParam('role', e.target.value);
  });

  // The collapse toggle is the one rail control that is real here: it swaps 236px for 64px.
  document.querySelector('.rail-collapse')?.addEventListener('click', () => {
    location.href = withParam('rail', body.dataset.rail === 'collapsed' ? 'expanded' : 'collapsed');
  });

  // Folders are accordions: open/closed is a real state, not a picture of one.
  document.body.addEventListener('click', (e) => {
    const f = e.target.closest('.folder-btn[aria-controls]');
    if (!f) return;
    const kids = document.getElementById(f.getAttribute('aria-controls'));
    const open = f.getAttribute('aria-expanded') === 'true';
    f.setAttribute('aria-expanded', String(!open));
    if (kids) kids.hidden = open;
  });

  // The mobile drawer's zone accordions, same behaviour.
  document.body.addEventListener('click', (e) => {
    const a = e.target.closest('.acc-btn');
    if (!a) return;
    const body2 = a.nextElementSibling;
    const open = a.getAttribute('aria-expanded') === 'true';
    a.setAttribute('aria-expanded', String(!open));
    if (body2) body2.hidden = open;
  });

  const menuBtn = document.getElementById('user-menu');
  menuBtn?.addEventListener('click', () => {
    const pop = document.getElementById('umenu-pop');
    const open = menuBtn.getAttribute('aria-expanded') === 'true';
    menuBtn.setAttribute('aria-expanded', String(!open));
    if (pop) pop.hidden = open;
  });

  const palette = document.querySelector('.pal-scrim');
  document.getElementById('open-palette')?.addEventListener('click', () => {
    if (palette) palette.hidden = false;
    else location.href = '06-palette.html';
  });
  document.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      if (palette) palette.hidden = false; else location.href = '06-palette.html';
    }
    if (e.key === 'Escape' && palette && !palette.hidden) palette.hidden = true;
  });
  palette?.addEventListener('click', (e) => { if (e.target === palette) palette.hidden = true; });

  document.querySelectorAll('.toggle').forEach((t) => {
    t.addEventListener('click', () => {
      const on = t.getAttribute('aria-checked') === 'true';
      t.setAttribute('aria-checked', String(!on));
      if (t.dataset.on || t.dataset.off) toast(!on ? (t.dataset.on || 'On') : (t.dataset.off || 'Off'));
    });
  });

  // Anything deliberately left unwired says so rather than doing nothing at all.
  document.body.addEventListener('click', (e) => {
    const el = e.target.closest('[data-mock]');
    if (!el) return;
    e.preventDefault();
    toast(`${el.dataset.mock} — not wired in the render.`);
  });
});
