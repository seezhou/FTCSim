// Verifies the shared activity colouring: every declared motor reacts to power (green forward,
// red back) and every servo to position (amber), through the same code path the wheels use.
//
// usage: node tools/verify-activity.mjs        (needs ./gradlew run on :8000)
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


const SIM = 8000, CDP = 9229;
const chrome = spawn(CHROME_PATH, [
  '--headless=new', `--remote-debugging-port=${CDP}`, '--no-sandbox',
  '--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--window-size=1600,1000', 'about:blank',
], { stdio: 'ignore' });
const getJSON = p => new Promise((res, rej) => {
  http.get({ host: '127.0.0.1', port: CDP, path: p }, r => {
    let b = ''; r.on('data', c => b += c); r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
  }).on('error', rej);
});

for (let i = 0; i < 40; i++) { try { await getJSON('/json/version'); break; } catch { await sleep(500); } }
const tabs = await getJSON('/json/list');
const ws = new WebSocket(tabs.find(t => t.type === 'page').webSocketDebuggerUrl);
let id = 0; const pending = new Map(); const errors = [];
ws.onmessage = e => {
  const m = JSON.parse(typeof e.data === 'string' ? e.data : Buffer.from(e.data).toString('utf8'));
  if (m.id && pending.has(m.id)) { const { res, rej } = pending.get(m.id); pending.delete(m.id); m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result); }
  if (m.method === 'Runtime.exceptionThrown') errors.push((m.params.exceptionDetails.exception?.description || '').split('\n')[0]);
};
await new Promise(r => ws.onopen = r);
const cdpSend = (method, params = {}) => new Promise((res, rej) => { const n = ++id; pending.set(n, { res, rej }); ws.send(JSON.stringify({ id: n, method, params })); });
const ev = async expr => {
  const r = await cdpSend('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || 'eval failed');
  return r.result.value;
};
await cdpSend('Page.enable'); await cdpSend('Runtime.enable');
await cdpSend('Page.navigate', { url: `http://127.0.0.1:${SIM}/3d/` });
await sleep(9000);

await ev(`(() => {
  const s = window.__sim3d, r = s.robot;
  let root = r.group; while (root.parent) root = root.parent;
  const f = root.getObjectByName('field'); if (f) f.visible = false;
  const p = r.position();
  s.controls.target.set(p.x, p.y + 8, p.z);
  s.camera.position.set(p.x + 32, p.y + 22, p.z + 32);
  s.controls.update();
  // The 20 Hz state stream would overwrite the injected values every frame.
  r.setState = () => {};
  r.setDevices = () => {};
  r.setDeviceValues = () => {};
  return 'ready';
})()`);

const READ = `(() => {
  const r = window.__sim3d.robot;
  const wheel = r.wheelMats[0].color.getHexString();
  const part = {};
  for (const [n, o] of r.parts) part[n] = o.userData.tinted[0].mat.color.getHexString();
  return { wheel, part };
})()`;

const FEED = (duty, servoPos) => `(() => {
  const r = window.__sim3d.robot;
  r.target.wheelPowers = [${duty}, ${duty}, ${duty}, ${duty}];
  const dv = [
    { name: 'launchLeft',  values: { duty: ${duty}, rpm: ${Math.abs(duty) * 400}, direction: 'FORWARD' } },
    { name: 'launchRight', values: { duty: ${duty}, rpm: ${Math.abs(duty) * 400}, direction: 'FORWARD' } },
    { name: 'leftServo',   values: { position: ${servoPos}, commanded: ${servoPos} } },
    { name: 'rightServo',  values: { position: ${servoPos}, commanded: ${servoPos} } },
  ];
  r.deviceValues = new Map(dv.map(d => [d.name, d.values]));
  r.updateParts(0.016);
  return 1;
})()`;

try {
  const rows = [];
  for (const [duty, pos] of [[0, 0], [0.9, 0.5], [-0.7, 1.0], [0.4, 0.15]]) {
    await ev(FEED(duty, pos));
    await sleep(250);
    const s = await ev(READ);
    rows.push({ duty, pos, ...s });
    console.log(`duty ${String(duty).padStart(5)}  servo ${String(pos).padStart(4)}  wheel ${s.wheel}  launch ${s.part.launchLeft}  servo ${s.part.leftServo}`);
  }

  const base = rows[0];
  const fwd = rows[1], rev = rows[2], half = rows[3];
  const hex = h => parseInt(h.slice(0, 2), 16);
  const lum = h => { const r = hex(h), g = parseInt(h.slice(2, 4), 16), b = parseInt(h.slice(4, 6), 16); return 0.299 * r + 0.587 * g + 0.114 * b; };
  const greenerThan = (a, b) => hex(a.slice(2, 4)) > hex(b.slice(2, 4));

  console.log('\n--- chassis edge colour ---');
console.log('   edges:', JSON.stringify(await ev(`(() => {
  let c = null;
  window.__sim3d.robot.group.traverse(o => { if (o.name === 'bodyEdge' && !c) c = o.material.color.getHexString(); });
  return { edge: c };
})()`)));

console.log('\n--- results ---');
  const checks = [
    ['wheels idle at rest colour', base.wheel === '14171c'],
    ['wheels + full power reads green', greenerThan(fwd.wheel, base.wheel)],
    ['wheels - power reads red', hex(rev.wheel.slice(0, 2)) > hex(rev.wheel.slice(2, 4))],
    ['brighter at higher power', lum(fwd.wheel) > lum(half.wheel)],
    ['launcher motor follows the same encoding as the wheels', fwd.part.launchLeft === fwd.wheel && rev.part.launchRight === rev.wheel],
    ['servo keeps its own colour at rest (not grey)', base.part.leftServo !== '14171c'],
    ['servo brightens as it extends', lum(rev.part.leftServo) > lum(fwd.part.leftServo)],
    ['servo stays in the red/amber band, never green', (h => {
      const r = hex(h.slice(0, 2)), g = hex(h.slice(2, 4)), b = hex(h.slice(4, 6));
      const mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn;
      if (d === 0) return true;
      let hue;
      if (mx === r) hue = ((g - b) / d + 6) % 6;
      else if (mx === g) hue = (b - r) / d + 2;
      else hue = (r - g) / d + 4;
      hue /= 6;
      return hue < 0.19 || hue > 0.95;   // gold band; green would land near 0.33
    })(fwd.part.leftServo)],
    ['servo is visibly distinct from the motors', fwd.part.leftServo !== fwd.part.launchLeft],
    ['chassis edges are neutral grey, not the trail blue', (await ev(`(() => {
      let c = null;
      window.__sim3d.robot.group.traverse(o => { if (o.name === 'bodyEdge' && !c) c = o.material.color.getHexString(); });
      return c === 'c8cdd4';
    })()`))],
    ['servo is distinct from the chassis edge', fwd.part.leftServo !== 'c8cdd4'],
  ];
  for (const [k, ok] of checks) console.log(` ${ok ? 'PASS' : 'FAIL'}  ${k}`);

  // leave the robot in a state that shows the effect, then shoot it
  await ev(FEED(0.9, 1.0));
  await sleep(400);
  const shot = await cdpSend('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(SHOT('verify-activity.png'), Buffer.from(shot.data, 'base64'));
  console.log('\nscreenshot saved (motors green, servo amber)');
  if (errors.length) { console.log('--- page errors ---'); errors.forEach(e => console.log(e)); }
} catch (e) { console.error('FAILED:', e.message); process.exitCode = 1; }
finally { ws.close(); chrome.kill(); }