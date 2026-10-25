// Playwright smoke test of the field UI: the 3D and 2D views, the season options panel, and how the
// field message and the page stay in step. Run it against a simulator started with a config for the
// mode you ask for.
//
// usage: node tools/ui-field-test.mjs [port] [biobuzz|decode] [screenshotDir]
//
// It reads the field through the WebSocket API, then drives the real pages: selecting an option in
// the UI must reach the backend and come back to every view, and a reset must do the same. In
// biobuzz mode it also checks the CELL/HIVE rendering and the HIVE swing; in decode mode it checks
// that only the selected obelisk face is visible. Screenshots land in build/ui-field/.
import { chromium } from 'playwright';
import fs from 'node:fs';

const port = process.argv[2] || '8000';
const mode = (process.argv[3] || 'biobuzz').toLowerCase();
const shots = process.argv[4] || 'build/ui-field';

const MODES = {
  biobuzz: { field: 'BIOBUZZ', option: { key: 'redHive', start: 'audience', changed: 'scoring' } },
  decode: { field: 'DECODE', option: { key: 'obelisk', start: '21', changed: '23' } },
};
if (!MODES[mode]) {
  console.error('usage: node tools/ui-field-test.mjs [port] [biobuzz|decode] [screenshotDir]');
  process.exit(2);
}
const expected = MODES[mode];
const SIM = `http://localhost:${port}`;
const WS = `ws://localhost:${port}/ws`;
// Software WebGL so the 3D scene builds without a GPU (ANGLE/SwiftShader in Playwright's Chromium).
const ARGS = ['--no-sandbox', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'];
const TIMEOUT = 15000;
fs.mkdirSync(shots, { recursive: true });

const out = { mode, port, ok: false, checks: [], errors: [] };
const check = (name, cond, detail) => {
  out.checks.push({ name, ok: !!cond, detail });
  if (!cond) out.errors.push(`${name}: ${detail}`);
};
const close = (a, b, eps = 1e-9) => Math.abs(a - b) <= eps;

// Waiters match from a cursor (`mark`) taken before the action, so a fast reply that arrives while
// the browser call is still returning is not lost, and an older broadcast cannot satisfy a new wait.
function wsClient() {
  const ws = new WebSocket(WS);
  const msgs = [];
  const waiters = new Set();
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    msgs.push(m);
    for (const w of [...waiters]) if (w.pred(m)) { waiters.delete(w); w.resolve(m); }
  };
  const open = new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const mark = () => msgs.length;
  const waitFor = (pred, ms = TIMEOUT, since = 0) => {
    for (let i = since; i < msgs.length; i++) if (pred(msgs[i])) return Promise.resolve(msgs[i]);
    return new Promise((res, rej) => {
      const w = { pred, resolve: (m) => { clearTimeout(t); res(m); } };
      const t = setTimeout(() => { waiters.delete(w); rej(new Error('timeout waiting for a field message')); }, ms);
      waiters.add(w);
    });
  };
  return { ws, open, mark, waitFor };
}

const stateOf = (m, key) => m.type === 'field' && m.field?.state?.[key];
const tagIds = (field) => field.tags.map((t) => t.id);

async function reset(client) {
  const mark = client.mark();
  if (mode === 'biobuzz') {
    client.ws.send(JSON.stringify({ type: 'sim.clearBalls' }));
    return client.waitFor((m) => stateOf(m, 'redHive') === 'audience', TIMEOUT, mark);
  }
  client.ws.send(JSON.stringify({ type: 'sim.field', key: 'obelisk', value: '21' }));
  return client.waitFor((m) => stateOf(m, 'obelisk') === '21', TIMEOUT, mark);
}

function watchErrors(page) {
  const errors = { page: [], console: [] };
  page.on('pageerror', (e) => errors.page.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.console.push(m.text()); });
  return errors;
}

const sceneSnapshot = () => {
  const scene = window.__sim3d?.scene;
  const find = (n) => scene && scene.getObjectByName(n);
  const cells = [];
  let tags = 0;
  scene?.traverse((o) => {
    if (o.name && o.name.startsWith('tag-')) tags++;
    if (o.name && o.name.startsWith('cell-')) cells.push(o.name);
  });
  return {
    pill: document.getElementById('fieldPill')?.textContent || '',
    options: [...document.querySelectorAll('#fieldOptions select')].map((e) => [e.dataset.key, e.value]),
    hives: ['hive-RED', 'hive-BLUE'].map((n) => !!find(n)),
    redRot: find('hive-RED')?.rotation.z ?? null,
    cells,
    tags,
    redAudienceY: find('cell-RED AUDIENCE')?.position.y ?? null,
    redScoringY: find('cell-RED SCORING')?.position.y ?? null,
    canvas: [window.__sim3d?.canvas?.width, window.__sim3d?.canvas?.height],
  };
};

const optionsOf = (page) =>
  page.evaluate(() => [...document.querySelectorAll('#fieldOptions select')].map((e) => [e.dataset.key, e.value]));

const selected = (page, key) => page.inputValue(`select[data-key="${key}"]`);

async function selectAndAwait(page, client, fieldName) {
  const { key, changed } = expected.option;
  const mark = client.mark();
  await page.selectOption(`select[data-key="${key}"]`, changed);
  const msg = await client.waitFor((m) => stateOf(m, key) === changed, TIMEOUT, mark);
  await page.waitForFunction(
    ([k, v]) => document.querySelector(`select[data-key="${k}"]`)?.value === v,
    [key, changed], { timeout: TIMEOUT });
  check(`${fieldName}: option select reaches the backend`, msg.field.state[key] === changed, JSON.stringify(msg.field.state));
  check(`${fieldName}: option select follows the broadcast`, (await selected(page, key)) === changed, changed);
  return msg;
}

async function run3d(client, browser) {
  const page = await browser.newPage({ viewport: { width: 1500, height: 950 } });
  const errors = watchErrors(page);
  // Capture the page's socket so a season event can be fed without staging a ball hit.
  await page.addInitScript(() => {
    const Orig = window.WebSocket;
    window.WebSocket = class extends Orig {
      constructor(...a) { super(...a); window.__ws = this; }
    };
  });

  try {
    await page.goto(SIM + '/', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => document.querySelector('#fieldOptions select'), null, { timeout: 20000 });
    await page.waitForFunction(
      (id) => window.__sim3d?.scene?.getObjectByName(`tag-${id}`),
      tagIds(client.field)[0], { timeout: 20000 });
    await page.waitForTimeout(800);

    const initial = await page.evaluate(sceneSnapshot);
    check('3D: field pill names the season', initial.pill.includes(expected.field), initial.pill);
    check('3D: canvas has size', initial.canvas[0] > 0 && initial.canvas[1] > 0, JSON.stringify(initial.canvas));
    check('3D: options panel shows live state',
      initial.options.length === client.field.options.length && initial.options.every(([k, v]) => String(client.field.state[k]) === v),
      JSON.stringify(initial.options));
    check('3D: every visible tag is drawn', initial.tags === client.field.tags.length, `${initial.tags} of ${client.field.tags.length}`);

    if (mode === 'biobuzz') {
      check('3D: two HIVE assemblies drawn', initial.hives.every(Boolean), JSON.stringify(initial.hives));
      check('3D: four CELL groups drawn', initial.cells.length === 4, initial.cells.join(', '));
      check('3D: AUDIENCE CELL starts up', initial.redAudienceY > initial.redScoringY, `${initial.redAudienceY} > ${initial.redScoringY}`);
      const g = client.field.cellGeometry;
      check('3D: cellGeometry matches BiobuzzRules',
        g && g.pent.length === 5 && close(g.depth, 11.244) && close(g.bottom, 9.938)
          && close(g.hivePivotZ, 43.95) && close(g.hiveY, 12.75) && g.hiveTiltDeg === 30,
        JSON.stringify(g));
      await page.evaluate(() => window.__ws.onmessage({
        data: JSON.stringify({ type: 'field.event', event: 'RED', label: 'RED HIVE tipped', count: 1, points: 20 }),
      }));
      await page.waitForFunction(
        () => (document.getElementById('toasts')?.textContent || '').includes('RED HIVE tipped'),
        null, { timeout: 5000 });
      const toast = await page.evaluate(() => document.getElementById('toasts')?.textContent || '');
      check('3D: field.event renders a labelled toast with points', toast.includes('RED HIVE tipped') && toast.includes('20'), toast);
    } else {
      check('3D: only the selected obelisk face is sent',
        tagIds(client.field).includes(21) && !tagIds(client.field).includes(22) && !tagIds(client.field).includes(23),
        JSON.stringify(tagIds(client.field)));
    }
    await page.screenshot({ path: `${shots}/3d-${mode}-start.png` });

    const changed = await selectAndAwait(page, client, '3D');
    if (mode === 'biobuzz') {
      await page.waitForFunction(() => {
        const s = window.__sim3d?.scene;
        const y = (n) => s?.getObjectByName(n)?.position.y;
        const r = s?.getObjectByName('hive-RED')?.rotation.z;
        return y('cell-RED SCORING') > y('cell-RED AUDIENCE') && r != null && Math.abs(r) < 1e-6;
      }, null, { timeout: TIMEOUT });
      const tipped = await page.evaluate(sceneSnapshot);
      check('3D: SCORING is now the raised CELL', tipped.redScoringY > tipped.redAudienceY, `${tipped.redScoringY} > ${tipped.redAudienceY}`);
      check('3D: HIVE swing settled', Math.abs(tipped.redRot) < 0.01, String(tipped.redRot));
      check('3D: option change keeps the CELL geometry', !!changed.field.cellGeometry, 'cellGeometry');
    } else {
      const ids = tagIds(changed.field);
      check('3D: new obelisk face is the only one sent', ids.includes(23) && !ids.includes(21) && !ids.includes(22), JSON.stringify(ids));
      await page.waitForFunction((wanted) => {
        const s = window.__sim3d?.scene;
        return s && wanted.every((id) => s.getObjectByName(`tag-${id}`)) && !s.getObjectByName('tag-21');
      }, ids, { timeout: TIMEOUT });
      const switched = await page.evaluate(sceneSnapshot);
      check('3D: scene follows the new face', switched.tags === ids.length, `${switched.tags} of ${ids.length}`);
    }
    await page.screenshot({ path: `${shots}/3d-${mode}-changed.png` });

    const resetMsg = await reset(client);
    await page.waitForFunction(
      ([k, v]) => document.querySelector(`select[data-key="${k}"]`)?.value === v,
      [expected.option.key, expected.option.start], { timeout: TIMEOUT });
    check('3D: reset reaches the backend', resetMsg.field.state[expected.option.key] === expected.option.start,
      JSON.stringify(resetMsg.field.state));
    check('3D: reset follows the broadcast',
      (await selected(page, expected.option.key)) === expected.option.start, expected.option.start);
    if (mode === 'biobuzz') {
      await page.waitForFunction(() => {
        const s = window.__sim3d?.scene;
        const y = (n) => s?.getObjectByName(n)?.position.y;
        const r = s?.getObjectByName('hive-RED')?.rotation.z;
        return y('cell-RED AUDIENCE') > y('cell-RED SCORING') && r != null && Math.abs(r) < 1e-6;
      }, null, { timeout: TIMEOUT });
      const back = await page.evaluate(sceneSnapshot);
      check('3D: AUDIENCE is up again', back.redAudienceY > back.redScoringY, `${back.redAudienceY} > ${back.redScoringY}`);
      check('3D: HIVE swing settled after reset', Math.abs(back.redRot) < 0.01, String(back.redRot));
    }
    await page.screenshot({ path: `${shots}/3d-${mode}-reset.png` });
    check('3D: no page errors', errors.page.length === 0, errors.page.join(' | '));
    check('3D: no console errors', errors.console.length === 0, errors.console.join(' | '));
  } finally {
    await page.close();
  }
}

async function run2d(client, browser) {
  const page = await browser.newPage({ viewport: { width: 1500, height: 950 } });
  const errors = watchErrors(page);
  try {
    await page.goto(SIM + '/2d/', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => document.querySelector('#fieldOptions select'), null, { timeout: 20000 });
    await page.waitForTimeout(300);

    const options = await optionsOf(page);
    check('2D: options panel built from the field message',
      options.length === client.field.options.length && options.every(([k, v]) => String(client.field.state[k]) === v),
      JSON.stringify(options));
    check('2D: reset state is shown', options.some(([k, v]) => k === expected.option.key && v === expected.option.start),
      JSON.stringify(options));

    await selectAndAwait(page, client, '2D');
    await page.screenshot({ path: `${shots}/2d-${mode}-changed.png` });

    const resetMsg = await reset(client);
    await page.waitForFunction(
      ([k, v]) => document.querySelector(`select[data-key="${k}"]`)?.value === v,
      [expected.option.key, expected.option.start], { timeout: TIMEOUT });
    check('2D: reset follows the broadcast',
      (await selected(page, expected.option.key)) === expected.option.start, expected.option.start);
    check('2D: reset reaches the backend', resetMsg.field.state[expected.option.key] === expected.option.start,
      JSON.stringify(resetMsg.field.state));
    await page.screenshot({ path: `${shots}/2d-${mode}-reset.png` });
    check('2D: no page errors', errors.page.length === 0, errors.page.join(' | '));
    check('2D: no console errors', errors.console.length === 0, errors.console.join(' | '));
  } finally {
    await page.close();
  }
}

const client = wsClient();
try {
  await client.open;
  const mark = client.mark();
  client.ws.send(JSON.stringify({ type: 'hello' }));
  const hello = await client.waitFor((m) => m.type === 'hello' && m.field, TIMEOUT, mark);
  client.field = hello.field;
  if (hello.field.name !== expected.field) {
    check('simulator serves the requested field', false, `${hello.field.name} (start it with a ${expected.field} config)`);
    throw new Error(`expected ${expected.field}, got ${hello.field.name}`);
  }
  const start = await reset(client);
  client.field = start.field;

  const browser = await chromium.launch({ headless: true, args: ARGS });
  try {
    await run3d(client, browser);
    await run2d(client, browser);
  } finally {
    await browser.close();
  }
  out.ok = out.errors.length === 0;
  console.log(JSON.stringify(out, null, 2));
} catch (e) {
  out.ok = false;
  out.errors.push(e.message);
  console.error(JSON.stringify(out, null, 2));
  process.exitCode = 2;
} finally {
  client.ws.close();
}
if (!out.ok && process.exitCode == null) process.exitCode = 1;
