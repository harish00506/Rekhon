/**
 * Checks the prototype's navigation: every screen reachable, back working at depth, no chip shelves.
 *
 * Why:  the prototype exists to be walked through, and a screen nobody can reach is a screen that
 *       does not exist. Twice during its construction a screen was wired to nothing and only a
 *       script noticed — once because the link lived inside a variant of a parameterised screen,
 *       once because an edit rewired a button to itself. Reading the page is not enough.
 * What: renders every screen in every argument it accepts, walks the navigation graph from the
 *       tabs and the Add FAB, and asserts the rules the design language sets.
 * Result: PASS/FAIL per check and a non-zero exit when any fails.
 *
 * Run: `node verify.js` from this directory (optionally with a path to another page).
 * Changed: 2026-10-11 — reads the HTML directly, so there is no extracted copy to go stale.
 */
const fs = require('fs');
const path = require('path');

const page = process.argv[2] || path.join(__dirname, 'v2-prototype.html');
const html = fs.readFileSync(page, 'utf8');
const script = html.slice(html.indexOf('<script>') + 8, html.lastIndexOf('</script>'));

// Enough of a DOM for the module-level code to run; the screens themselves are pure functions.
const stub = () => ({
  innerHTML: '', scrollTop: 0, textContent: '', hidden: false, value: '',
  classList: { add() {}, remove() {}, toggle() {} },
  addEventListener() {}, querySelectorAll: () => [],
});
globalThis.document = { getElementById: stub, querySelectorAll: () => [], addEventListener() {} };
globalThis.setTimeout = () => {};

// The checks run inside the same eval so they can see the page's own const/let bindings.
eval(script.slice(0, script.indexOf("el('tabs').addEventListener")) + fs.readFileSync(path.join(__dirname, 'checks.js'), 'utf8'));
