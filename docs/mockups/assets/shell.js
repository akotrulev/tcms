/*
 * Shell for the TCMS project-management mockups.
 *
 * Every page is a plain static file; this script draws the parts that must be identical on all
 * of them (rail, topbar, breadcrumbs, scheme toggle) from a few data-* attributes on <body>, so a
 * navigation change is made once here rather than in forty files.
 *
 *   data-nav       which top-level rail entry is current
 *   data-proj      "KEY|Project name" — when present the project sub-tree is drawn open
 *   data-projnav   which project entry is current
 *   data-crumbs    "Label>href|Label>href|Current"
 *   data-role      role this page is being viewed as (drives what the rail shows at all)
 *
 * The role switcher is the one control here that changes what you see: Billing is absent for
 * everyone but Finance and Company Admin (story M2), and the sidebar is the place that is
 * observable from.
 */

const ROLES = [
  'Company Admin',
  'Project Manager',
  'Test Lead',
  'Test Engineer',
  'Account Manager',
  'Finance',
  'Viewer',
];
const SEES_BILLING = new Set(['Company Admin', 'Finance']);
const SEES_APPROVALS = new Set(['Company Admin', 'Project Manager', 'Test Lead']);

const ICONS = {
  mywork: '◧', projects: '▤', clients: '◈', timesheets: '◷', billing: '⛁',
  staffing: '⧉', teams: '⚇', directory: '☷', help: '?', reports: '◲',
  overview: '⌂', work: '▦', releases: '⬡', documents: '▭', cases: '✓',
  suites: '❑', plans: '◫', runs: '▷', settings: '⚙',
};

function icon(k) { return `<span class="ic" aria-hidden="true">${ICONS[k] || '·'}</span>`; }

function link(id, current, href, label, key, extra = '') {
  const cur = id === current ? ' aria-current="page"' : '';
  return `<a class="rail-link" href="${href}"${cur}>${icon(key)}<span>${label}</span>${extra}</a>`;
}

function getRole() {
  const stored = (() => { try { return localStorage.getItem('tcms-mock-role'); } catch { return null; } })();
  return document.body.dataset.role || stored || 'Project Manager';
}

function getScheme() {
  try { return localStorage.getItem('tcms-mock-scheme') || 'dark'; } catch { return 'dark'; }
}

function buildRail(role) {
  const nav = document.body.dataset.nav || '';
  const proj = document.body.dataset.proj;
  const pn = document.body.dataset.projnav || '';
  const parts = [];

  parts.push(`<a class="rail-brand" href="index.html">
      <span class="mark">T</span>
      <span>TCMS<small>Mockups</small></span>
    </a>`);

  parts.push('<nav class="rail-group" aria-label="Primary">');
  parts.push(link('mywork', nav, 'my-work.html', 'My Work', 'mywork', '<span class="count">7</span>'));
  parts.push(link('projects', nav, 'projects.html', 'Projects', 'projects'));

  if (proj) {
    const [key, name] = proj.split('|');
    parts.push(`<div class="rail-proj"><span class="key">${key}</span><strong>${name}</strong></div>`);
    parts.push('<div class="rail-sub">');
    parts.push(link('overview', pn, 'project-overview.html', 'Overview', 'overview'));
    parts.push(link('work', pn, 'work-board.html', 'Work', 'work'));
    parts.push(link('releases', pn, 'releases.html', 'Releases', 'releases'));
    parts.push(link('documents', pn, 'documents.html', 'Documents', 'documents'));
    parts.push(link('cases', pn, 'test-cases.html', 'Test Cases', 'cases'));
    parts.push(link('suites', pn, 'test-suites.html', 'Suites', 'suites'));
    parts.push(link('plans', pn, 'test-plans.html', 'Plans', 'plans'));
    parts.push(link('runs', pn, 'test-runs.html', 'Runs', 'runs'));
    parts.push(link('reports', pn, 'reports.html', 'Reports', 'reports'));
    parts.push(link('settings', pn, 'settings-general.html', 'Settings', 'settings'));
    parts.push('</div>');
  }

  parts.push('</nav>');

  parts.push('<nav class="rail-group" aria-label="Company">');
  parts.push('<div class="rail-label">Company</div>');
  parts.push(link('clients', nav, 'clients.html', 'Clients', 'clients'));
  parts.push(link('timesheets', nav, 'timesheets.html', 'Timesheets', 'timesheets',
    SEES_APPROVALS.has(role) ? '<span class="count">4</span>' : ''));
  if (SEES_BILLING.has(role)) parts.push(link('billing', nav, 'billing.html', 'Billing', 'billing'));
  parts.push(link('staffing', nav, 'staffing.html', 'Staffing', 'staffing'));
  parts.push(link('teams', nav, 'teams.html', 'Teams', 'teams'));
  parts.push(link('directory', nav, 'directory.html', 'Directory', 'directory'));
  parts.push('</nav>');

  parts.push('<nav class="rail-group" aria-label="Account">');
  parts.push('<div class="rail-label">Account</div>');
  parts.push(link('profile', nav, 'profile-assistants.html', 'My profile', 'mywork'));
  parts.push(link('help', nav, 'help.html', 'Help', 'help'));
  parts.push('</nav>');

  const rail = document.createElement('div');
  rail.className = 'rail';
  rail.innerHTML = parts.join('');
  return rail;
}

function buildCrumbs() {
  const raw = document.body.dataset.crumbs || '';
  if (!raw) return '';
  const bits = raw.split('|').map((seg, i, all) => {
    const [label, href] = seg.split('>');
    const last = i === all.length - 1;
    if (last || !href) return `<span class="here">${label}</span>`;
    return `<a href="${href}">${label}</a><span class="sep">/</span>`;
  });
  return `<div class="crumbs">${bits.join('')}</div>`;
}

function buildTopbar(role, scheme) {
  const bar = document.createElement('header');
  bar.className = 'topbar';
  bar.innerHTML = `
    ${buildCrumbs()}
    <span class="spacer"></span>
    <span class="search" data-mock="Global search">${'⌕'} Search issues, cases, docs <kbd>/</kbd></span>
    <label class="sel" style="gap:4px">
      <span style="color:var(--color-text-ghost)">as</span>
      <select id="role-switch" aria-label="View the product as" style="background:transparent;border:0;color:inherit;font:inherit;font-size:12px;outline:none">
        ${ROLES.map((r) => `<option${r === role ? ' selected' : ''}>${r}</option>`).join('')}
      </select>
    </label>
    <button class="btn btn-icon" id="scheme-toggle" aria-label="Switch colour scheme" title="Light / dark">
      ${scheme === 'dark' ? '☾' : '☀'}
    </button>
    <a class="avatar" href="profile-assistants.html" title="Dana Petrov — my profile">DP</a>`;
  return bar;
}

function buildMockbar() {
  const el = document.createElement('div');
  el.className = 'mockbar';
  el.innerHTML = `<span class="tagm">Mockup</span>
    <span>Static render — links navigate, nothing is saved.</span>
    <span class="spacer"></span>
    <a href="index.html">All screens</a>`;
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
  t.innerHTML = `<span class="k">Mockup</span><span>${msg}</span>`;
  wrap.appendChild(t);
  setTimeout(() => t.remove(), 2600);
}

document.addEventListener('DOMContentLoaded', () => {
  const role = getRole();
  const scheme = getScheme();
  document.documentElement.setAttribute('data-color-scheme', scheme);

  const content = document.querySelector('.page');
  const main = document.createElement('div');
  main.className = 'main';
  main.appendChild(buildTopbar(role, scheme));
  main.appendChild(buildMockbar());
  if (content) main.appendChild(content);

  const shell = document.createElement('div');
  shell.className = 'shell';
  shell.appendChild(buildRail(role));
  shell.appendChild(main);
  document.body.prepend(shell);

  document.getElementById('scheme-toggle')?.addEventListener('click', () => {
    const next = document.documentElement.getAttribute('data-color-scheme') === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-color-scheme', next);
    try { localStorage.setItem('tcms-mock-scheme', next); } catch { /* private mode */ }
    document.getElementById('scheme-toggle').textContent = next === 'dark' ? '☾' : '☀';
  });

  document.getElementById('role-switch')?.addEventListener('change', (e) => {
    try { localStorage.setItem('tcms-mock-role', e.target.value); } catch { /* private mode */ }
    location.reload();
  });

  // Toggles are the one stateful control in the renders: they flip, and say what would follow.
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
