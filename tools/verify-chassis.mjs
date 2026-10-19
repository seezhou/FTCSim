// Checks the three chassis/parts changes: a translucent body, wheel colour by direction and
// speed, and the raised mechanism mounts.
//
// usage: node tools/verify-chassis.mjs        (needs ./gradlew run on :8000)
import { spawn } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import http from 'node:http';
import fs from 'node:fs';

import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';
import { mkdirSync } from 'node:fs';

// Chrome location, overridable so this is not tied to one machine.
const CHROME_PATH = process.env.FTCSIM_CHROME
  || (process.platform === 'win32' ? 'C:/Program Files/Google/Chrome/Application/chrome.exe' : '/usr/bin/google-chrome');
/** Screenshots land under build/, which is already gitignored. */
const SHOT = (name) => {
  const dir = join(dirname(fileURLToPath(import.meta.url)), '..', 'build');
  mkdirSync(dir, { recursive: true });
  return join(dir, name);
};


const SIM = 8000, CDP = 9228;
const chrome = spawn(CHROME_PATH, [
  '--headless=new', `--remote-debugging-port=${CDP}`, '--no-sandbox',
  '--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--window-size=1600,1000', 'about:blank',
], { stdio: 'ignore' });
const getJSON = p => new Promise((res, rej) => {
  http.get({ host: '127.0.0.1', port: CDP, path: p }, r => {
    let b = ''; r.on('data', c => b += c); r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
  }).on('error', rej);
});

const sim = new WebSocket(`ws://127.0.0.1:${SIM}/ws`);
await new Promise((res, rej) => { sim.onopen = res; sim.onerror = rej; });
let st = null;
sim.onmessage = ev => { const m = JSON.parse(typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString('utf8')); if (m.type === 'state') st = m; };
const send = o => sim.send(JSON.stringify(o));

for (let i = 0; i < 40; i++) { try { await getJSON('/json/version'); break; } catch { await sleep(500); } }
const tabs = await getJSON('/json/list');
const cdp = new WebSocket(tabs.find(t => t.type === 'page').webSocketDebuggerUrl);
let id = 0; const pending = new Map(); const errors = [];
cdp.onmessage = e => {
  const m = JSON.parse(typeof e.data === 'string' ? e.data : Buffer.from(e.data).toString('utf8'));
  if (m.id && pending.has(m.id)) { const { res, rej } = pending.get(m.id); pending.delete(m.id); m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result); }
  if (m.method === 'Runtime.exceptionThrown') errors.push((m.params.exceptionDetails.exception?.description || '').split('\n')[0]);
};
await new Promise(r => cdp.onopen = r);
const cdpSend = (method, params = {}) => new Promise((res, rej) => { const n = ++id; pending.set(n, { res, rej }); cdp.send(JSON.stringify({ id: n, method, params })); });
const ev = async expr => {
  const r = await cdpSend('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || 'eval failed');
  return r.result.value;
};
await cdpSend('Page.enable'); await cdpSend('Runtime.enable');
await cdpSend('Page.navigate', { url: `http://127.0.0.1:${SIM}/3d/` });
await sleep(9000);

// park the camera close, three-quarter view from the front so body and parts are both visible
await ev(`(() => {
  const s = window.__sim3d, r = s.robot;
  let root = r.group; while (root.parent) root = root.parent;
  const f = root.getObjectByName('field'); if (f) f.visible = false;
  const p = r.position();
  s.controls.target.set(p.x, p.y + 9, p.z);
  s.camera.position.set(p.x + 34, p.y + 26, p.z + 34);
  s.controls.update();
  return 'parked';
})()`);

const BODY = `(() => {
  const r = window.__sim3d.robot;
  return (r.chassisMeshes || []).map(m => ({
    transparent: m.material.transparent, opacity: +m.material.opacity.toFixed(2), depthWrite: m.material.depthWrite,
  }));
})()`;

const PARTS = `(() => {
  const r = window.__sim3d.robot;
  return [...r.parts.entries()].map(([n, o]) => ({ name: n, y: +o.position.y.toFixed(2) }));
})()`;

// drive the sticks and read the wheel materials back
const WHEELS = `(() => {
  const r = window.__sim3d.robot;
  return (r.wheelMats || []).map(m => '#' + m.color.getHexString());
})()`;

try {
  // The sticks only reach the wheels through the team's TeleOp, so start it before driving.
  console.log('starting BioBuzzTele (the sticks only reach the wheels through team code)...');
  send({ type: 'opmode.init', name: 'BioBuzzTele', flavor: 'TELEOP' });
  await sleep(4000);
  if (st?.opmode?.state === 'INIT') { send({ type: 'opmode.start' }); await sleep(2500); }
  console.log('  state:', st?.opmode?.state, st?.opmode?.error ? '| ERROR ' + st.opmode.error : '');
  await sleep(1200);

  console.log('\n1. chassis: declared shape, transparency, edges');
  const body = await ev(BODY);
  console.log('   meshes:', JSON.stringify(body));
  console.log('   edges :', JSON.stringify(await ev(`(() => {
    let n = 0;
    window.__sim3d.robot.group.traverse(o => { if (o.name === 'bodyEdge') n++; });
    return { bodyEdgeCount: n, spec: window.__sim3d.robot.spec };
  })()`)));

  console.log('\n2. mechanism heights (robot frame z = up; deck top is 10.4)');
  const parts = await ev(PARTS);
  parts.forEach(p => console.log(`   ${p.name.padEnd(13)} z=${p.y}`));

  console.log('\n3. wheel colour vs direction/speed  (driving forward with the sticks)');
  console.log('   idle:      ', await ev(WHEELS));
  // forward: both sticks forward
  for (let i = 0; i < 30; i++) { send({ type: 'gamepad', id: 1, state: { lx: 0, ly: -1, rx: 0, ry: 0 } }); await sleep(70); }
  await sleep(700);
  const fwd = await ev(WHEELS);
  console.log('   forward:   ', fwd, ' backend:', st?.robot?.wheels);
  // reverse: both sticks back
  for (let i = 0; i < 30; i++) { send({ type: 'gamepad', id: 1, state: { lx: 0, ly: 1, rx: 0, ry: 0 } }); await sleep(70); }
  await sleep(700);
  const rev = await ev(WHEELS);
  console.log('   backward:  ', rev, ' backend:', st?.robot?.wheels);
  // strafe right, to check the two sides can differ
  for (let i = 0; i < 30; i++) { send({ type: 'gamepad', id: 1, state: { lx: -1, ly: 0, rx: 0, ry: 0 } }); await sleep(70); }
  await sleep(700);
  const side = await ev(WHEELS);
  console.log('   strafe:    ', side);
  for (let i = 0; i < 5; i++) { send({ type: 'gamepad', id: 1, state: { lx: 0, ly: 0, rx: 0, ry: 0 } }); await sleep(60); }
  await sleep(900);
  console.log('   stopped:   ', await ev(WHEELS));
  console.log('   opmode:', st?.opmode?.state, st?.opmode?.name || '', st?.opmode?.error ? 'ERR ' + st.opmode.error : '');
  console.log('   backend wheels:', st?.robot?.wheels);

  console.log('\n--- results ---');
  const checks = [
    ['chassis is a single translucent body', body.length === 1 && body.every(b => b.transparent && b.opacity < 0.6)],
    ['chassis height comes from the config (12 in)', (await ev('window.__sim3d.robot.spec.heightIn')) === 12],
    ['exactly one opaque edge outline', (await ev(`(() => { let n = 0; window.__sim3d.robot.group.traverse(o => { if (o.name === 'bodyEdge') n++; }); return n; })()`)) === 1],
    ['mechanisms sit at or above deck level (z >= 6)', parts.every(p => p.y >= 6)],
    ['forward reads green', fwd.every(c => c[1] > '9' && Number.parseInt(c.slice(3), 16) > Number.parseInt(c.slice(1, 3), 16))],
    ['backward reads red', rev.every(c => Number.parseInt(c.slice(1, 3), 16) > Number.parseInt(c.slice(3), 16))],
    ['strafe shows per-wheel variety (mecanum)', new Set(side).size > 1],
    ['stopped returns to idle grey', (await ev(WHEELS)).every(c => c === '#14171c')],
  ];
  for (const [k, ok] of checks) console.log(` ${ok ? 'PASS' : 'FAIL'}  ${k}`);

  // Park the robot at the origin and let the camera settle before the shot, so the screenshot
  // shows the chassis rather than wherever the wheel-driving test left it.
  send({ type: 'sim.setPose', x: 12, y: -63, headingDeg: 90 });
  await sleep(1500);
  await ev(`(() => {
    const s = window.__sim3d, r = s.robot;
    const p = r.position();
    s.controls.target.set(p.x, p.y + 8, p.z);
    s.camera.position.set(p.x + 32, p.y + 22, p.z + 32);
    s.controls.update();
    return 'framed';
  })()`);
  await sleep(900);
  const shot = await cdpSend('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(SHOT('verify-chassis.png'), Buffer.from(shot.data, 'base64'));
  console.log('\nscreenshot saved');
  send({ type: 'opmode.stop' });
  if (errors.length) { console.log('--- page errors ---'); errors.forEach(e => console.log(e)); }
} catch (e) { console.error('FAILED:', e.message); process.exitCode = 1; }
finally { sim.close(); cdp.close(); chrome.kill(); }