// Headless probe of the 3D page: loads it, waits for the websocket, and reports which declared
// parts exist and where they sit, plus a screenshot.
// usage: node tools/verify-3d-probe.mjs [url]     (needs ./gradlew run)
//
// Chrome is driven over the DevTools protocol with Node's built-in WebSocket, so this needs no
// npm install. Useful after editing the config: it answers "did the mounts actually arrive and
// did the meshes get built" without anyone having to watch the page.
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
const PORT = 9222;
const URL = process.argv[2] || 'http://127.0.0.1:8000/3d/';

const chrome = spawn(CHROME_PATH, [
  '--headless=new', `--remote-debugging-port=${PORT}`, '--disable-gpu-sandbox',
  '--no-sandbox', '--use-gl=swiftshader', '--enable-unsafe-swiftshader',
  '--window-size=1400,900', 'about:blank',
], { stdio: 'ignore' });

const getJSON = (path) => new Promise((res, rej) => {
  http.get({ host: '127.0.0.1', port: PORT, path }, r => {
    let b = ''; r.on('data', c => b += c); r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
  }).on('error', rej);
});

async function waitForChrome() {
  for (let i = 0; i < 40; i++) {
    try { return await getJSON('/json/version'); } catch { await sleep(500); }
  }
  throw new Error('chrome did not start');
}

const PROBE = `(() => {
  const s = window.__sim3d;
  if (!s) return { error: 'no __sim3d (module failed to load)' };
  const r = s.robot;
  const parts = [...(r.parts?.entries?.() || [])].map(([name, obj]) => ({
    name,
    axis: obj.userData.mount.axis,
    sweeps: obj.userData.mount.sweeps,
    minDeg: obj.userData.mount.minDeg,
    maxDeg: obj.userData.mount.maxDeg,
    pos: [ +obj.position.x.toFixed(2), +obj.position.y.toFixed(2), +obj.position.z.toFixed(2) ],
    jointZ: +obj.userData.joint.rotation.z.toFixed(3),
    visible: obj.visible,
    children: obj.children.length,
  }));
  return {
    parts,
    partCount: r.parts?.size || 0,
    drive: r.drive,
    wheelPositions: r.wheels.map(w => [ +w.position.x.toFixed(2), +w.position.y.toFixed(2), +w.position.z.toFixed(2) ]),
    camPitchDeg: r.camPitchDeg,
    canvasOk: s.canvas && s.canvas.width > 0,
  };
})()`;

(async () => {
  try {
    await waitForChrome();
    const tabs = await getJSON('/json/list');
    const page = tabs.find(t => t.type === 'page');
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    ws.binaryType = 'arraybuffer';
    let id = 0; const pending = new Map();
    const send = (method, params = {}) => new Promise((res, rej) => {
      const msgId = ++id; pending.set(msgId, { res, rej });
      ws.send(JSON.stringify({ id: msgId, method, params }));
    });
    ws.onmessage = ev => {
      const m = JSON.parse(typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString('utf8'));
      if (m.id && pending.has(m.id)) {
        const { res, rej } = pending.get(m.id); pending.delete(m.id);
        m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result);
      }
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error')
        logs.push(m.params.args.map(a => a.value ?? a.description).join(' '));
      if (m.method === 'Runtime.exceptionThrown')
        logs.push('EXCEPTION: ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text));
    };
    await new Promise(r => ws.onopen = r);

    await send('Page.enable');
    await send('Runtime.enable');
    const logs = [];

    await send('Page.navigate', { url: URL });
    await sleep(9000);   // let the websocket connect and a few state messages arrive

    const r = await send('Runtime.evaluate', { expression: PROBE, returnByValue: true, awaitPromise: false });
    console.log(JSON.stringify(r.result.value, null, 2));
    if (logs.length) { console.log('\n--- console errors ---'); logs.forEach(l => console.log(l)); }

    // a picture is worth more than the JSON: save the frame for eyeballing
    const shot = await send('Page.captureScreenshot', { format: 'png' });
    const fs = await import('node:fs');
    fs.writeFileSync(SHOT, Buffer.from(shot.data, 'base64'));
    console.log('\nscreenshot saved');
    ws.close();
  } catch (e) {
    console.error('FAILED:', e.message);
    process.exitCode = 1;
  } finally {
    chrome.kill();
  }
})();