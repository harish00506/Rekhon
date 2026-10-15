/**
 * The checks themselves, evaluated inside the page's own scope by verify.js.
 *
 * Why:  kept in their own file so the page under test stays untouched — a checker that has to edit
 *       what it checks proves nothing.
 * What: fifteen assertions covering renders, link targets, reachability, depth, chip shelves, the
 *       back stack, the two defects this revamp fixed, and the proposed/built tagging.
 * Result: one PASS/FAIL line each, then a non-zero exit when any failed.
 * Changed: 2026-10-11 — created alongside the navigation revamp.
 */
let fail = 0;
const check = (ok, msg) => { console.log((ok ? 'PASS ' : 'FAIL ') + msg); if (!ok) fail++; };

// 1 · render every screen, in every argument it accepts
const args = {
  account: S.accounts.map((a) => a.id),
  txns: [null, ...S.budgets.map((b) => b.id)],
  txn: S.txns.map((_, i) => String(i)),
  goaldetail: S.goals.map((_, i) => String(i)),
};
const blank = () => ({ tab: 'today', stacks: { today: [], money: [], plan: [], ask: [], you: [] } });
const edges = {}; let rendered = 0; const broken = [];
for (const [name, fn] of Object.entries(SCREENS)) {
  edges[name] = new Set();
  for (const a of (args[name] || [null])) {
    nav = blank(); nav.stacks.today = [{ screen: name, arg: a }];
    let html;
    try { html = fn(); } catch (e) { broken.push(name + '(' + a + '): ' + e.message); continue; }
    rendered++;
    if (!html || html.length < 40 || /undefined|NaN/.test(html)) broken.push(name + '(' + a + '): thin or undefined');
    for (const m of html.matchAll(/data-nav="([a-z]+)"/g)) edges[name].add(m[1]);
  }
}
check(broken.length === 0, rendered + ' screen renders clean' + (broken.length ? ' — ' + broken.join('; ') : ''));

// 2 · every data-nav target is a real screen
const missing = [...new Set(Object.values(edges).flatMap((e) => [...e]))].filter((t) => !SCREENS[t]);
check(missing.length === 0, 'every data-nav target resolves' + (missing.length ? ' — missing ' + missing : ''));

// 3 · reachability. The FAB is a real entry point, so Add is a root rather than an exemption.
const ROOTS = TABS.concat(['add']);
const depth = {}; ROOTS.forEach((t) => { depth[t] = 0; });
let frontier = ROOTS.slice();
while (frontier.length) {
  const next = [];
  for (const f of frontier) {
    if (f === 'index') continue;
    for (const t of edges[f] || []) if (depth[t] === undefined) { depth[t] = depth[f] + 1; next.push(t); }
  }
  frontier = next;
}
const exempt = new Set(['index', 'erased', 'lock']);
const orphans = Object.keys(SCREENS).filter((n) => depth[n] === undefined && !exempt.has(n));
check(orphans.length === 0, 'no orphans' + (orphans.length ? ' — ' + orphans : ''));
const deep = Object.entries(depth).filter(([, d]) => d > 3).map(([n, d]) => n + ':' + d);
check(deep.length === 0, 'every screen within 3 taps of an entry point' + (deep.length ? ' — ' + deep : ''));

// 4 · no chip shelves on a tab screen
for (const t of TABS) {
  nav = blank(); nav.tab = t;
  const chips = (SCREENS[t]().match(/class="chip"[^>]*data-nav/g) || []).length;
  check(chips <= 2, t + ' has ' + chips + ' navigation chips (limit 2)');
}

// 5 · the back stack, at depth
nav = blank(); nav.tab = 'money';
go('account', 'sip'); go('holdings');
const trail = [here().screen];
back(); trail.push(here().screen);
back(); trail.push(here().screen);
check(trail.join(',') === 'holdings,account,money', 'back at depth: ' + trail.join(' → '));
nav = blank(); nav.tab = 'money'; go('account', 'card'); switchTab('plan'); switchTab('money');
check(here().screen === 'account' && here().arg === 'card', 'a tab switch preserves the other tab depth');

// 6 · the two bugs this revamp was meant to fix
nav = blank(); nav.tab = 'you';
check(/data-nav="erase"/.test(SCREENS.you()), 'You reaches the Erase screen');
nav.stacks.you = [{ screen: 'erase', arg: null }];
check(/data-act="erase"/.test(SCREENS.erase()) && !/data-nav="erase"/.test(SCREENS.erase()), 'Erase no longer links to itself');

// 7 · proposed screens are tagged, built ones are not
nav = blank(); nav.stacks.today = [{ screen: 'tax', arg: null }];
check(/class="soon">proposed/.test(SCREENS.tax()), 'a proposed screen carries its tag');
nav = blank(); nav.stacks.today = [{ screen: 'budgets', arg: null }];
check(!/class="soon">proposed/.test(SCREENS.budgets()), 'a built screen carries no tag');

console.log(fail === 0 ? '\nALL CHECKS PASS' : '\n' + fail + ' CHECK(S) FAILED');
process.exit(fail ? 1 : 0);
