// Runs the demo page's OWN inline <script> (static/index.html) in node, with a minimal DOM stub, and clicks
// reset, then the four demo buttons in page order. Prints one JSON line: the text each output box ends with and
// every HTTP call the script made. Used by DemoPageScriptTest (Java), which asserts on that JSON.
//
// Usage: node page-harness.mjs <index.html> <mode> [baseUrl]
//   mode = live      -> fetch goes to <baseUrl> (a running app), no credentials sent
//   mode = planted401 -> every call answers 401 {"detail":"auth_missing"} (a denied call must render as a failure)
//   mode = empty200  -> every call answers 200 {} (missing fields must never render a check mark)
//   mode = networkError -> every call rejects like a dropped connection (a failure must be rendered, never a
//                          progress text left on screen)
// A listener that throws does not stop the page (as in a browser): the error is recorded in "uncaught".
import { readFileSync } from 'node:fs';

const [, , pagePath, mode, baseUrl] = process.argv;
const html = readFileSync(pagePath, 'utf8');
const inline = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
if (inline.length !== 1) {
  console.error(`expected exactly one inline <script>, found ${inline.length}`);
  process.exit(2);
}

const uncaught = [];
class El {
  constructor(id) { this.id = id; this.value = ''; this.listeners = {}; this.dataset = {}; this.disabled = false; }
  set textContent(v) { this.value = String(v); }
  get textContent() { return this.value; }
  set innerHTML(v) { this.value = String(v); }
  get innerHTML() { return this.value; }
  addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); }
  async click() {
    for (const fn of this.listeners.click ?? []) {
      try { await fn(); } catch (e) { uncaught.push(`${this.id}: ${e}`); }
    }
  }
}
const els = new Map();
const byId = id => { if (!els.has(id)) els.set(id, new El(id)); return els.get(id); };
const demoButtons = ['idem', 'tamper', 'audit', 'reconcile'].map(k => { const b = new El('btn-' + k); b.dataset.demo = k; return b; });
const document = {
  querySelector: s => { if (!s.startsWith('#')) throw new Error('unsupported selector ' + s); return byId(s.slice(1)); },
  querySelectorAll: s => {
    if (s === 'button[data-demo]') return demoButtons;
    if (s === 'button') return [...demoButtons, byId('btnReset')];
    throw new Error('unsupported selector ' + s);
  },
};

const calls = [];
const planted = (status, body) => async (path, init) => {
  calls.push({ method: init?.method ?? 'GET', path, status });
  return new Response(body, { status, headers: { 'Content-Type': 'application/json' } });
};
const fetchImpl = mode === 'live'
  ? async (path, init) => {
      const r = await fetch(baseUrl + path, init);
      calls.push({ method: init?.method ?? 'GET', path, status: r.status });
      return r;
    }
  : mode === 'planted401' ? planted(401, '{"detail":"auth_missing"}')
  : mode === 'empty200' ? planted(200, '{}')
  : mode === 'networkError' ? async (path, init) => {
      calls.push({ method: init?.method ?? 'GET', path, status: null });
      throw new TypeError('network down');
    }
  : null;
if (!fetchImpl) { console.error('unknown mode ' + mode); process.exit(2); }

new Function('document', 'fetch', inline[0])(document, fetchImpl);

await byId('btnReset').click();
const outputs = { resetMsg: byId('resetMsg').value };
for (const b of demoButtons) {
  await b.click();
  outputs[b.dataset.demo] = byId('out-' + b.dataset.demo).value;
}
console.log(JSON.stringify({ mode, outputs, calls, uncaught }));
