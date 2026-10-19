// End-to-end check of the declared launcher parts, driven by the team's own TeleOp.
// Starts the TeleOp, holds the fire button (gamepad1.a) and watches the 3D joints through the
// page's own render loop: the servos should snap to the fire position and the flywheels spin.
//
// usage: node tools/verify-launcher.mjs        (needs ./gradlew run on :8000)
// Chrome is located via $FTCSIM_CHROME; screenshots land in build/.
import { spawn } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import http from 'node:http';
import fs, { mkdirSync } from 'node:fs';

import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';

// Chrome location, overridable so this is not tied to one machine.
const CHROME_PATH = process.env.FTCSIM_CHROME
  || (process.platform === 'win32' ? 'C:/Program Files/Google/Chrome/Application/chrome.exe' : '/usr/bin/google-chrome');
/** Screenshots land under build/, which is already gitignored. */
const SHOT = (name) => {
  const dir = join(dirname(fileURLToPath(import.meta.url)), '..', 'build');
  mkdirSync(dir, { recursive: true });
  return join(dir, name);
};


const SIM = 8000, CDP = 9227;
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
let st = null, tel = [];
sim.onmessage = ev => {
  const m = JSON.parse(typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString('utf8'));
  if (m.type === 'state') { st = m; tel = m.telemetry?.lines || []; }
};
const send = o => sim.send(JSON.stringify(o));
const hold = async (ms, state) => {
  const end = Date.now() + ms;
  while (Date.now() < end) { send({ type: 'gamepad', id: 1, state }); await sleep(60); }
};

for (let i = 0; i < 40; i++) { try { await getJSON('/json/version'); break; } catch { await sleep(500); } }
const tabs = await getJSON('/json/list');
const cdp = new WebSocket(tabs.find(t => t.type === 'page').webSocketDebuggerUrl);
let id = 0; const pending = new Map(); const errors = [];
cdp.onmessage = ev => {
  const m = JSON.parse(typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString('utf8'));
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

// Work out what to look at from the robot's own parts rather than naming devices here, so the
// script also runs against another team's config. A motor is any part that does not sweep, a servo
// is one that does; two of each are picked so paired mechanisms can be compared.
const PLAN = `(() => {
  const r = window.__sim3d.robot;
  const motors = [], servos = [];
  for (const [name, o] of r.parts) (o.userData.mount.sweeps ? servos : motors).push(name);
  window.__plan = { motors: motors.slice(0, 2), servos: servos.slice(0, 2) };
  return window.__plan;
})()`;

const SNAP = `(() => {
  const r = window.__sim3d.robot, plan = window.__plan;
  const d = n => { const o = r.parts.get(n); return o ? +(o.userData.joint.rotation.z*180/Math.PI).toFixed(1) : 'MISSING'; };
  const v = n => { const x = (r.deviceValues?.get(n)||{}); return { duty: +(x.duty||0).toFixed(2), rpm: +(x.rpm||0).toFixed(0), pos: x.position }; };
  return {
    motors: plan.motors.map(n => ({ name: n, deg: d(n), v: v(n) })),
    servos: plan.servos.map(n => ({ name: n, deg: d(n) })),
  };
})()`;

try {
  const plan = await ev(PLAN);
  console.log('watching motors', plan.motors.join(', ') || '(none)', 'and servos', plan.servos.join(', ') || '(none)');
  if (!plan.motors.length || !plan.servos.length) throw new Error('config declares no motor/servo mounts to check');

  const opName = process.argv[2] || 'BioBuzzTele';
  console.log(`starting ${opName}...`);
  send({ type: 'opmode.init', name: opName, flavor: 'TELEOP' });
  await sleep(4000);
  console.log('  state:', st?.opmode?.state, st?.opmode?.error ? '| ERROR: ' + st.opmode.error : '');
  if (st?.opmode?.state === 'INIT') { send({ type: 'opmode.start' }); await sleep(2500); }
  console.log('  state:', st?.opmode?.state);

  await sleep(1500);
  console.log('\n1. idle (servo at SERVO_REST_POSITION = 0.0 => 0 deg)');
  const idle = await ev(SNAP);
  console.log('  ', JSON.stringify(idle));

  console.log('\n2. holding gamepad1.a (spin-up 0.5 s, then servo -> 0.8)');
  const samples = [];
  const end = Date.now() + 6000;
  while (Date.now() < end) {
    send({ type: 'gamepad', id: 1, state: { lx:0, ly:0, rx:0, ry:0, a:true } });
    await sleep(160);
    samples.push(await ev(SNAP));
  }
  const firing = samples[samples.length - 1];
  console.log('   servo angle over time:', samples.map(s => s.servos[0].deg).join(' -> '));
  console.log('   final:', JSON.stringify(firing));
  console.log('   telemetry:', tel.filter(l => /SHOT|Status/.test(l)).join(' | '));

  await hold(400, { lx:0, ly:0, rx:0, ry:0, a:false });
  await sleep(800);
  console.log('\n3. released');
  console.log('  ', JSON.stringify(await ev(SNAP)));

  console.log('\n--- results ---');
  // SERVO_REST_POSITION 0.0 and SERVO_FIRE_POSITION 0.8 across a declared 0..90 sweep
  const restDeg = idle.servos[0].deg;
  const expectedFire = 0.8 * 90;              // SERVO_FIRE_POSITION 0.8 over the declared 90 deg
  // The team code cycles (fire, stop, fire) while A is held, so the last sample can land anywhere in
  // that loop. Judge on the peak reached across the whole burst, not on the final value.
  const peakServo = Math.max(...samples.map(s => s.servos[0].deg));
  const peakRpm = Math.max(...samples.map(s => s.motors[0].v.rpm));
  const checks = [
    ['servo rests at 0 deg (SERVO_REST_POSITION 0.0)', Math.abs(restDeg) < 1],
    [`servo reaches the fire position (${expectedFire} deg for 0.8 x 90)`, Math.abs(peakServo - expectedFire) < 2],
    ['servo actually moved off its rest angle', peakServo - restDeg > 30],
    ['flywheel spinning under A (rpm > 100)', peakRpm > 100],
    ['both flywheels spin together (same direction)', Math.sign(Math.max(...samples.map(s => s.motors[0].deg))) === Math.sign(Math.max(...samples.map(s => s.motors[1].deg)))] ,
    ['launcher joint rotated away from rest', Math.abs(firing.motors[0].deg - idle.motors[0].deg) > 20],
    ['emitted a power command (duty was non-zero at some point)', samples.some(s => Math.abs(s.motors[0].v.duty) > 0.3)],
  ];
  for (const [k, ok] of checks) console.log(` ${ok ? 'PASS' : 'FAIL'}  ${k}`);

  // screenshot mid-fire for the record
  const shot = await cdpSend('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(SHOT('verify-fire.png'), Buffer.from(shot.data, 'base64'));
  send({ type: 'opmode.stop' });
  if (errors.length) { console.log('\n--- page errors ---'); errors.forEach(e => console.log(e)); }
} finally { sim.close(); cdp.close(); chrome.kill(); }