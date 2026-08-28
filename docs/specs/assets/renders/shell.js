/*
  The app shell, written once.

  Every render in this directory draws the same rail, so it lives here instead of being pasted
  nine times and drifting. Put `<nav class="rail" data-rail="cases"></nav>` in a render and this
  fills it, marking that item active. The item keys are the `to` paths from
  `frontend/src/app/AppShell.tsx:projectNav` — headings and order are that function's, verbatim,
  because the redesign changes how the rail LOOKS and not what is in it.

  `data-rail="mini"` on a `.mini` nav draws the collapsed icon rail instead — the one the case
  document editor takes, and now the execution runner too (see the design-language doc, §5).
*/
const I = {
  overview:'<rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/>',
  cases:'<path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z"/>',
  docs:'<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 8h6M9 12h6M9 16h3"/>',
  plans:'<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 3h6v3H9z"/><path d="M9 11h6M9 15h4"/>',
  runs:'<path d="M4 7h10M4 12h7M4 17h13"/><path d="M17 6l2 2 3-3"/>',
  board:'<rect x="3" y="4" width="5" height="16" rx="1.5"/><rect x="10" y="4" width="5" height="10" rx="1.5"/><rect x="17" y="4" width="4" height="14" rx="1.5"/>',
  reports:'<path d="M4 20V10M10 20V4M16 20v-7"/>',
  ingest:'<path d="M12 15V4m0 0L8 8m4-4l4 4"/><path d="M4 16v2a2 2 0 002 2h12a2 2 0 002-2v-2"/>',
  gen:'<path d="M12 3l1.9 4.6L18.5 9l-4.6 1.9L12 15.5l-1.9-4.6L5.5 9l4.6-1.4z"/><path d="M18 16l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z"/>',
  artifacts:'<path d="M21 8l-9-5-9 5 9 5 9-5z"/><path d="M3 12l9 5 9-5M3 16l9 5 9-5"/>',
  access:'<circle cx="9" cy="8" r="3"/><path d="M3 20a6 6 0 0112 0"/><path d="M16 11a3 3 0 100-6M18 20a5.5 5.5 0 00-2-4.3"/>',
  class:'<path d="M3 12V5a2 2 0 012-2h7l9 9-9 9z"/><circle cx="7.5" cy="7.5" r="1.2"/>',
  review:'<path d="M4 5h16v11H9l-5 4z"/><path d="M8.5 10.5l2 2 4-4"/>',
  notif:'<path d="M6 9a6 6 0 1112 0c0 5 2 6 2 6H4s2-1 2-6z"/><path d="M10 20a2 2 0 004 0"/>',
  audit:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  org:'<rect x="9" y="3" width="6" height="4" rx="1"/><rect x="3" y="16" width="6" height="4" rx="1"/><rect x="15" y="16" width="6" height="4" rx="1"/><path d="M12 7v5M6 16v-4h12v4"/>',
  teams:'<circle cx="8" cy="8" r="3"/><circle cx="17" cy="9" r="2.5"/><path d="M2 19a6 6 0 0112 0M15 19a5 5 0 017-4.5"/>',
  tokens:'<circle cx="8" cy="12" r="4"/><path d="M12 12h9l-2 2.5M17 12v3"/>',
  sso:'<path d="M14 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4"/><path d="M10 16l4-4-4-4M14 12H3"/>',
  search:'<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>',
  mywork:'<path d="M4 6h16M4 12h16M4 18h9"/><circle cx="19" cy="18" r="2"/>',
  projects:'<path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z"/>',
  help:'<circle cx="12" cy="12" r="9"/><path d="M9.5 9a2.5 2.5 0 113 2.5V13"/><circle cx="12" cy="16.5" r=".7" fill="currentColor"/>',
};
const svg = k => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${I[k]}</svg>`;

const NAV = [
  ['Repository', [['overview','Overview'],['cases','Test cases','1 284'],['docs','Documentation','37']]],
  ['Execution',  [['plans','Test plans','9'],['runs','Test runs','412'],['board','Board','23'],['reports','Reports'],['ingest','CI ingestion']]],
  ['Authoring',  [['gen','Generated cases','6']]],
  ['Project',    [['artifacts','Artifacts'],['access','Access & roles'],['class','Classification'],['review','Review'],['notif','Notifications'],['audit','Audit trail']]],
  ['Company',    [['org','Org chart'],['teams','Teams & roles'],['tokens','API tokens'],['sso','Single sign-on']]],
];

// The collapsed rail keeps only the five most-travelled destinations plus the trail.
const MINI = ['docs','search','cases','runs','reports','|','audit'];

function railHTML(active){
  let h = `<div class="pick"><div class="lbl">Project</div><div class="cur">Reflex Payments ${svg('overview')}</div></div>`;
  for (const [heading, items] of NAV){
    h += `<div class="grp"><h4>${heading}</h4>`;
    for (const [key,label,count] of items){
      h += `<a class="n${key===active?' on':''}" href="#">${svg(key)}<span>${label}</span>${count?`<span class="c">${count}</span>`:''}</a>`;
    }
    h += `</div>`;
  }
  h += `<div class="foot-u"><span class="av">MK</span><span>Mira Kalcheva</span></div>`;
  return h;
}

function miniHTML(active){
  return MINI.map(k => k==='|' ? '<span class="sep"></span>'
    : `<span class="m${k===active?' on':''}">${svg(k)}</span>`).join('');
}

for (const el of document.querySelectorAll('[data-rail]')){
  const a = el.getAttribute('data-rail');
  el.innerHTML = el.classList.contains('mini') ? miniHTML(a) : railHTML(a);
}
