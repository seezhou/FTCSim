// Drives the declared motor/servo parts through their travel and reports the joint angles.
// usage: node tools/verify-parts.mjs        (needs ./gradlew run on :8000)
//
// Servos are checked through the real control path (Simulator handles "device.set"), so the
// numbers reflect what the team code would command. Motors cannot be driven that way on purpose:
// SimDcMotor has no applyInput, because a motor belongs to the robot code and the UI must not
// override it. Motor rotation is verified separately by injecting device values in the page.
import { spawn } from 'node:child_process';
import { setTimeout as sleep } from 'node:timers/promises';
import http from 'node:http';

import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';

// Chrome location, overridable so this is not tied to one machine.
const CHROME_PATH = process.env.FTCSIM_CHROME
  || (process.platform === 'win32' ? 'C:/Program Files/Google/Chrome/Application/chrome.exe' : '/usr/bin/google-chrome');
/** Screenshots land under build/, which is already gitignored. */
const SHOT = (name) => join(dirname(fileURLToPath(import.meta.url)), '..', 'build', name);
const PORT = 9224;
const chrome = spawn(CHROME_PATH, [
  '--headless=new', `--remote-debugging-port=${PORT}`, '--no-sandbox',
  '--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--window-size=1400,900', 'about:blank',
], { stdio: 'ignore' });

const getJSON = p => new Promise((res, rej) => {
  http.get({ host: '127.0.0.1', port: PORT, path: p }, r => {
    let b = ''; r.on('data', c => b += c); r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
  }).on('error', rej);
});

const snapshot = `(() => {
  const r = window.__sim3d.robot;
  const out = {};
  for (const name of ['leftServo', 'rightServo', 'launchLeft', 'launchRight']) {
    const o = r.parts.get(name);
    out[name] = o ? +(o.userData.joint.rotation.z * 180 / Math.PI).toFixed(2) : 'MISSING';
  }
  return out;
})()`;

// The page's own websocket is not exported, so open a second one from the test to talk to the
// same backend the way the device panel does (Simulator handles "device.set").
const SETUP = `(() => {
  window.__t = new WebSocket('ws://' + location.host + '/ws');
  window.__t.onopen = () => { window.__tOpen = true; };
  return 'connecting';
})()`;
const setDevice = (name, key, value) => `(() => {
  if (!window.__tOpen) return 'ws not open';
  window.__t.send(JSON.stringify({ type: 'device.set', name: '${name}', key: '${key}', value: ${value} }));
  return 'sent';
})()`;

(async () => {
  try {
    for (let i = 0; i < 40; i++) { try { await getJSON('/json/version'); break; } catch { await sleep(500); } }
    const tabs = await getJSON('/json/list');
    const ws = new WebSocket(tabs.find(t => t.type === 'page').webSocketDebuggerUrl);
    let id = 0; const pending = new Map(); const errors = [];
    const send = (method, params = {}) => new Promise((res, rej) => {
      const n = ++id; pending.set(n, { res, rej }); ws.send(JSON.stringify({ id: n, method, params }));
    });
    const evalJs = async (expr, aw = false) => {
      const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: aw });
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || 'eval failed');
      return r.result.value;
    };
    ws.onmessage = ev => {
      const m = JSON.parse(typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString('utf8'));
      if (m.id && pending.has(m.id)) { const { res, rej } = pending.get(m.id); pending.delete(m.id); m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result); }
      if (m.method === 'Runtime.exceptionThrown') errors.push((m.params.exceptionDetails.exception?.description || '').split('\n')[0]);
    };
    await new Promise(r => ws.onopen = r);
    await send('Page.enable'); await send('Runtime.enable');
    await send('Page.navigate', { url: 'http://127.0.0.1:8000/3d/' });
    await sleep(9000);

    await evalJs(SETUP);
    await sleep(1500);

    console.log('1. initial — servos rest at 0.5, which is half of the declared 0..90 sweep');
    console.log('  ', JSON.stringify(await evalJs(snapshot)));

    console.log('\n2. drive both servos through their declared travel (device.set position)');
    for (const v of [0, 0.25, 0.5, 1.0]) {
      await evalJs(setDevice('leftServo', 'position', v));
      await evalJs(setDevice('rightServo', 'position', v));
      await sleep(1300);
      const snap = await evalJs(snapshot);
      console.log(`   servo=${v}  ->  left ${String(snap.leftServo).padStart(6)}deg   right ${String(snap.rightServo).padStart(6)}deg`);
    }

    console.log('\n3. run launcherLeft at full power for ~1.5 s');
    const before = (await evalJs(snapshot)).launchLeft;
    await evalJs(setDevice('launchLeft', 'power', 1));
    await sleep(1500);
    const during = (await evalJs(snapshot)).launchLeft;
    await evalJs(setDevice('launchLeft', 'power', 0));
    console.log(`   ${before}deg -> ${during}deg  (integrated from duty)`);

    console.log('\n4. reverse direction');
    await evalJs(setDevice('launchLeft', 'power', -1));
    await sleep(1500);
    const back = (await evalJs(snapshot)).launchLeft;
    await evalJs(setDevice('launchLeft', 'power', 0));
    console.log(`   ${during}deg -> ${back}deg  (should move the other way)`);

    if (errors.length) { console.log('\n--- page errors ---'); errors.forEach(e => console.log(e)); }
    ws.close();
  } catch (e) { console.error('FAILED:', e.message); process.exitCode = 1; }
  finally { chrome.kill(); }
})();