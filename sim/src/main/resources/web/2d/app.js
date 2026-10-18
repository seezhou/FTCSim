/* FTCSim browser UI */
(() => {
'use strict';
const $ = (id) => document.getElementById(id);
const state = {
  ws: null, hello: null, opmodes: [], build: null, last: null, field: null,
  trail: [], trailEpoch: -1, viewRot: 0, telemetryTab: 'ds', rightTab: 'hardware',
  logLevel: 2, logFilter: '', logEntries: [], configurables: [], graphs: {}, drag: null,
  gamepads: { 1: null, 2: null }, physical: { 1: null, 2: null }, keyboardPad: 1,
};

// ------------------------------------------------------------------ websocket
function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws`);
  state.ws = ws;
  ws.onopen = () => { $('connPill').textContent = 'connected'; $('connPill').className = 'pill ok'; send({ type: 'hello' }); };
  ws.onclose = () => { stopAllRumble(); $('connPill').textContent = 'offline'; $('connPill').className = 'pill bad'; setTimeout(connect, 1000); };
  ws.onmessage = (ev) => { try { handle(JSON.parse(ev.data)); } catch (e) { console.error(e); } };
}
function send(obj) { if (state.ws && state.ws.readyState === 1) state.ws.send(JSON.stringify(obj)); }

function handle(m) {
  switch (m.type) {
    case 'hello': state.hello = m; state.field = m.field; renderFieldOptions(m.field); $('repoMeta').textContent = `${m.repoName || 'no repo'} · SDK ${m.sdkVersion} · config ${m.configName}`; $('configFile').textContent = m.configFile; state.trail = []; break;
    case 'field': state.field = m.field; renderFieldOptions(m.field); break;
    case 'opmodes': state.opmodes = m.list; state.build = m.build; renderOpModes(); renderBuild(); break;
    case 'buildStatus': state.build = m.build; renderBuild(); break;
    case 'state': state.last = m; onState(m); break;
    case 'log': for (const e of m.entries) state.logEntries.push(e); if (state.logEntries.length > 5000) state.logEntries.splice(0, state.logEntries.length - 5000); renderLog(m.entries); break;
    case 'configurables': state.configurables = m.classes; renderConfigurables(); break;
    case 'graphs': state.graphs = m.series; if (state.rightTab === 'graph') renderGraph(); break;
    case 'config': $('configText').value = m.json; $('configFile').textContent = m.file; break;
    case 'toast': toast(m.level, m.text); break;
    case 'gamepad.rumble': applyRumble(m); break;
    case 'gamepad.rumble.stop': stopAllRumble(); break;
  }
}

function toast(level, text) {
  const d = document.createElement('div'); d.className = 'toast ' + level; d.textContent = text; $('toasts').appendChild(d);
  setTimeout(() => d.remove(), 6000);
}

// ------------------------------------------------------------------ driver station
function renderOpModes() {
  const sel = $('opmodeSelect'); const prev = sel.value; sel.innerHTML = '';
  const groups = { TELEOP: {}, AUTONOMOUS: {} };
  for (const o of state.opmodes) { if (o.disabled) continue; (groups[o.flavor][o.group || ''] ||= []).push(o); }
  for (const flavor of ['TELEOP', 'AUTONOMOUS']) {
    for (const g of Object.keys(groups[flavor]).sort()) {
      const og = document.createElement('optgroup'); og.label = (flavor === 'TELEOP' ? 'TeleOp' : 'Autonomous') + (g ? ' · ' + g : '');
      for (const o of groups[flavor][g].sort((a, b) => a.name.localeCompare(b.name))) { const opt = document.createElement('option'); opt.value = o.flavor + '|' + o.name; opt.textContent = o.name + (o.linear ? '' : ' ⟳'); og.appendChild(opt); }
      sel.appendChild(og);
    }
  }
  if (!sel.options.length) { const opt = document.createElement('option'); opt.textContent = state.build && state.build.success === false ? '(build failed — see Log)' : '(no OpModes found yet)'; sel.appendChild(opt); }
  if (prev) sel.value = prev;
}
function renderBuild() {
  const b = state.build || {}; const pill = $('buildPill');
  if (b.building) { pill.textContent = 'compiling…'; pill.className = 'pill busy'; }
  else if (b.success) { pill.textContent = 'build OK' + (b.sourcesChanged ? ' · sources changed' : ''); pill.className = 'pill ' + (b.sourcesChanged ? 'busy' : 'ok'); }
  else { pill.textContent = 'build FAILED'; pill.className = 'pill bad'; }
  pill.title = b.summary || '';
  const diag = $('buildDiag'); let txt = (b.summary || '') + '\n';
  for (const d of (b.diagnostics || [])) txt += `${d.kind} ${d.file}:${d.line}: ${d.message}\n`;
  for (const e of (b.loadErrors || [])) txt += `LOAD ${e}\n`;
  diag.textContent = txt;
}
$('reloadBtn').onclick = () => send({ type: 'code.reload' });
$('initBtn').onclick = () => { const v = $('opmodeSelect').value; if (!v.includes('|')) return; const [flavor, name] = v.split('|'); send({ type: 'opmode.init', name, flavor }); };
$('startBtn').onclick = () => send({ type: 'opmode.start' });
$('stopBtn').onclick = () => send({ type: 'opmode.stop' });
$('restartBtn').onclick = () => send({ type: 'robot.restart' });
$('timerChk').onchange = (e) => send({ type: 'sim.timer', enabled: e.target.checked });
$('pauseBtn').onclick = () => { const paused = !(state.last && state.last.sim.paused); send({ type: 'sim.pause', paused }); };
$('stepBtn').onclick = () => { if (!(state.last && state.last.sim.paused)) send({ type: 'sim.pause', paused: true }); send({ type: 'sim.step', ms: parseFloat($('stepSize').value) }); };
$('opmodeSelect').ondblclick = () => $('initBtn').click();

function onState(m) {
  const op = m.opmode;
  const pill = $('statePill'); pill.textContent = op.state; pill.className = 'state-pill ' + op.state;
  $('runtime').textContent = op.runtime.toFixed(1) + ' s';
  $('initBtn').disabled = op.state !== 'IDLE'; $('startBtn').disabled = op.state !== 'INIT'; $('stopBtn').disabled = !(op.state === 'INIT' || op.state === 'RUNNING');
  $('restartBtn').classList.toggle('hidden', op.state !== 'ESTOP');
  $('errorBox').classList.toggle('hidden', !op.error); $('errorBox').textContent = op.error || '';
  const warn = [op.warning, op.globalError && op.state !== 'ESTOP' ? op.globalError : ''].filter(Boolean).join(' · ');
  $('warnBox').classList.toggle('hidden', !warn); $('warnBox').textContent = warn;
  $('timerRemaining').textContent = op.timerRemaining != null ? Math.ceil(op.timerRemaining) + 's' : '';
  const src = state.telemetryTab === 'ds' ? m.telemetry : state.telemetryTab === 'panels' ? m.panels : m.dashboard;
  const tel = $('telemetry'); const txt = (src.lines || []).join('\n'); if (tel.textContent !== txt) tel.textContent = txt;
  $('batteryPill').textContent = m.battery.volts.toFixed(2) + ' V · ' + m.battery.current.toFixed(1) + ' A';
  $('simPill').textContent = (m.sim.paused ? (m.sim.stepping ? 'stepping' : 'paused') : Math.round(m.sim.stepRate) + ' Hz');
  $('pauseBtn').textContent = m.sim.paused ? '▶ Resume' : '⏸ Pause';
  const io = m.sim.io || {};
  $('ioPill').textContent = io.enabled === false ? 'I/O instant'
    : `I/O ${(io.totalMs || 0).toFixed(0)} ms · ${io.bulkReads || 0} bulk · ${io.writes || 0} w · ${io.i2c || 0} i2c`;
  $('ioPill').classList.toggle('muted', io.enabled === false);
  const r = m.robot;
  if (r.epoch !== state.trailEpoch) { state.trail = []; state.trailEpoch = r.epoch; }
  const lastT = state.trail[state.trail.length - 1];
  if (!lastT || Math.hypot(lastT[0] - r.x, lastT[1] - r.y) > 0.25) { state.trail.push([r.x, r.y]); if (state.trail.length > 1500) state.trail.shift(); }
  const pd = toPedro(r.x, r.y, r.headingDeg);
  $('poseFtc').textContent = `FTC x ${r.x.toFixed(1)}  y ${r.y.toFixed(1)}  θ ${r.headingDeg.toFixed(0)}°`;
  $('posePedro').textContent = `Pedro x ${pd.x.toFixed(1)}  y ${pd.y.toFixed(1)}  θ ${pd.h.toFixed(0)}°`;
  if (state.rightTab === 'hardware') renderDevices(m.devices);
  if ($('obeliskSelect')) $('obeliskSelect').value = String(m.sim.obelisk);
}
document.querySelectorAll('#telemetryTabs button').forEach(b => b.onclick = () => { document.querySelectorAll('#telemetryTabs button').forEach(x => x.classList.remove('active')); b.classList.add('active'); state.telemetryTab = b.dataset.tab; });
document.querySelectorAll('#rightTabs button').forEach(b => b.onclick = () => {
  document.querySelectorAll('#rightTabs button').forEach(x => x.classList.remove('active')); b.classList.add('active'); state.rightTab = b.dataset.tab;
  document.querySelectorAll('#right .tab').forEach(t => t.classList.toggle('active', t.id === 'tab-' + state.rightTab));
  if (state.rightTab === 'robot' && !$('configText').value) send({ type: 'config.get' });
  if (state.rightTab === 'graph') renderGraph();
});

// ------------------------------------------------------------------ coordinates
function toPedro(x, y, h) { return { x: y + 72, y: 72 - x, h: norm(h - 90) }; }
function fromPedro(px, py, ph) { return { x: 72 - py, y: px - 72, h: norm(ph + 90) }; }
function norm(d) { d = ((d + 180) % 360 + 360) % 360 - 180; return d; }

// ------------------------------------------------------------------ field
const canvas = $('field'); const ctx = canvas.getContext('2d');
function fieldToScreen(x, y) {
  // FTC frame: +x right, +y up (rotated by the view setting)
  const rot = state.viewRot * Math.PI / 180;
  const c = Math.cos(rot), s = Math.sin(rot);
  const rx = c * x - s * y, ry = s * x + c * y;
  const scale = canvas.width / 150;
  return [canvas.width / 2 + rx * scale, canvas.height / 2 - ry * scale];
}
function screenToField(sx, sy) {
  const scale = canvas.width / 150;
  const rx = (sx - canvas.width / 2) / scale, ry = -(sy - canvas.height / 2) / scale;
  const rot = -state.viewRot * Math.PI / 180; const c = Math.cos(rot), s = Math.sin(rot);
  return [c * rx - s * ry, s * rx + c * ry];
}
function drawField() {
  const W = canvas.width, H = canvas.height; ctx.clearRect(0, 0, W, H);
  ctx.save();
  const scale = W / 150;
  // tiles
  for (let i = 0; i < 6; i++) for (let j = 0; j < 6; j++) {
    const x0 = -72 + i * 24, y0 = -72 + j * 24;
    poly([[x0, y0], [x0 + 24, y0], [x0 + 24, y0 + 24], [x0, y0 + 24]], (i + j) % 2 ? '#1a1f28' : '#161b23', null);
  }
  // grid lines
  ctx.strokeStyle = '#2a3140'; ctx.lineWidth = 1;
  for (let i = 0; i <= 6; i++) { line(-72 + i * 24, -72, -72 + i * 24, 72); line(-72, -72 + i * 24, 72, -72 + i * 24); }
  // walls
  poly([[-72, -72], [72, -72], [72, 72], [-72, 72]], null, '#6c7a95', 3);
  const f = state.field;
  if (f) {
    for (const z of (f.zones || [])) poly([[z.minX, z.minY], [z.maxX, z.minY], [z.maxX, z.maxY], [z.minX, z.maxY]], z.color + '44', z.color + 'aa', 1);
    for (const o of f.obstacles) poly([[o.minX, o.minY], [o.maxX, o.minY], [o.maxX, o.maxY], [o.minX, o.maxY]], o.color + '55', o.color, 2);
    for (const sh of (f.shapes || [])) drawShape(sh);
    const ob = state.last ? state.last.sim.obelisk : 21;
    for (const t of f.tags) {
      if (t.id >= 21 && t.id <= 23 && t.id !== ob && f.name === 'DECODE') continue;
      const a = t.yawDeg * Math.PI / 180; const hx = Math.cos(a + Math.PI / 2) * t.size / 2, hy = Math.sin(a + Math.PI / 2) * t.size / 2;
      ctx.lineWidth = 4; ctx.strokeStyle = '#f5f5f5'; line(t.x - hx, t.y - hy, t.x + hx, t.y + hy);
      ctx.lineWidth = 1.5; ctx.strokeStyle = '#f5f5f5'; line(t.x, t.y, t.x + Math.cos(a) * 5, t.y + Math.sin(a) * 5);
      if ($('labelsChk').checked) label(t.x + Math.cos(a) * 9, t.y + Math.sin(a) * 9, String(t.id), '#f5f5f5');
    }
  }
  // axis hint
  if ($('labelsChk').checked) { ctx.fillStyle = '#5c6a85'; const [ax, ay] = fieldToScreen(66, -69); ctx.font = '11px system-ui'; ctx.fillText('+X →', ax - 12, ay); const [bx, by] = fieldToScreen(-69, 66); ctx.fillText('+Y ↑', bx, by); }
  // overlays (Panels / Dashboard drawings)
  if (state.last) for (const d of state.last.drawings) drawOverlay(d);
  // trail
  if ($('trailChk').checked && state.trail.length > 1) {
    ctx.strokeStyle = '#4f8cff88'; ctx.lineWidth = 2; ctx.beginPath();
    state.trail.forEach((p, i) => { const [sx, sy] = fieldToScreen(p[0], p[1]); if (i === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy); }); ctx.stroke();
  }
  // robot
  if (state.last && state.hello) {
    const r = state.last.robot; const L = state.hello.robot.lengthIn, Wd = state.hello.robot.widthIn;
    const h = r.headingDeg * Math.PI / 180; const c = Math.cos(h), s = Math.sin(h);
    const corner = (fx, fy) => [r.x + c * fx - s * fy, r.y + s * fx + c * fy];
    poly([corner(L / 2, Wd / 2), corner(L / 2, -Wd / 2), corner(-L / 2, -Wd / 2), corner(-L / 2, Wd / 2)], '#4f8cff66', '#8fb8ff', 2);
    // heading arrow
    ctx.strokeStyle = '#ffd166'; ctx.lineWidth = 3; const tip = corner(L / 2 - 1, 0); line(r.x, r.y, tip[0], tip[1]);
    // wheel power bars (mecanum order LF RF LR RR)
    const w = r.wheels || [];
    const wp = [[L / 2 - 2.5, Wd / 2 - 1.2], [L / 2 - 2.5, -Wd / 2 + 1.2], [-L / 2 + 2.5, Wd / 2 - 1.2], [-L / 2 + 2.5, -Wd / 2 + 1.2]];
    w.forEach((p, i) => { if (!wp[i]) return; const [wx, wy] = wp[i]; const a = corner(wx - 2, wy), b = corner(wx + 2 * Math.max(-1, Math.min(1, p)) , wy); ctx.strokeStyle = p >= 0 ? '#2ecc71' : '#e74c3c'; ctx.lineWidth = 4; line(a[0], a[1], b[0], b[1]); });
  }
  ctx.restore();
  function poly(pts, fill, stroke, lw) { ctx.beginPath(); pts.forEach((p, i) => { const [sx, sy] = fieldToScreen(p[0], p[1]); if (i === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy); }); ctx.closePath(); if (fill) { ctx.fillStyle = fill; ctx.fill(); } if (stroke) { ctx.strokeStyle = stroke; ctx.lineWidth = lw || 1; ctx.stroke(); } }
  function line(x1, y1, x2, y2) { const a = fieldToScreen(x1, y1), b = fieldToScreen(x2, y2); ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.stroke(); }
  function label(x, y, text, color) { const [sx, sy] = fieldToScreen(x, y); ctx.fillStyle = color; ctx.font = 'bold 12px system-ui'; ctx.textAlign = 'center'; ctx.fillText(text, sx, sy + 4); ctx.textAlign = 'left'; }
  function drawShape(sh) {
    const c = sh.coords, lw = sh.width || 1;
    switch (sh.type) {
      case 'rect': poly([[c[0], c[1]], [c[2], c[1]], [c[2], c[3]], [c[0], c[3]]], sh.fill || null, sh.stroke || null, lw);
        if (sh.label && $('labelsChk').checked) label((c[0] + c[2]) / 2, (c[1] + c[3]) / 2, sh.label, '#f5f5f5'); break;
      case 'circle': { const [sx, sy] = fieldToScreen(c[0], c[1]); ctx.beginPath(); ctx.arc(sx, sy, c[2] * scale, 0, Math.PI * 2); if (sh.fill) { ctx.fillStyle = sh.fill; ctx.fill(); } if (sh.stroke) { ctx.strokeStyle = sh.stroke; ctx.lineWidth = lw; ctx.stroke(); }
        if (sh.label && $('labelsChk').checked) label(c[0], c[1] - c[2] - 3, sh.label, '#f5f5f5'); break; }
      case 'line': ctx.strokeStyle = sh.stroke || '#ffffff'; ctx.lineWidth = lw; line(c[0], c[1], c[2], c[3]); break;
      case 'polygon': { const pts = []; for (let i = 0; i + 1 < c.length; i += 2) pts.push([c[i], c[i + 1]]); poly(pts, sh.fill || null, sh.stroke || null, lw); break; }
      case 'label': if ($('labelsChk').checked) label(c[0], c[1], sh.label || '', sh.stroke || '#f5f5f5'); break;
    }
  }
}
/** Field-specific controls (obelisk face, HIVE tips...) come from the server's field description. */
function renderFieldOptions(field) {
  const host = $('fieldOptions'); if (!host) return;
  const opts = (field && field.options) || [];
  const key = JSON.stringify(opts.map(o => o.key)) + (field ? field.name : '');
  if (host.dataset.key !== key) {
    host.dataset.key = key;
    host.innerHTML = opts.map(o => `<label>${esc(o.label)} <select data-key="${esc(o.key)}">${o.choices.map((c, i) => `<option value="${esc(c)}">${esc(o.choiceLabels[i] || c)}</option>`).join('')}</select></label>`).join('');
    for (const sel of host.querySelectorAll('select')) sel.onchange = (e) => {
      const k = e.target.dataset.key;
      if (k === 'obelisk') send({ type: 'sim.obelisk', id: parseInt(e.target.value, 10) });
      send({ type: 'sim.field', key: k, value: e.target.value });
    };
  }
  const st = (field && field.state) || {};
  for (const sel of host.querySelectorAll('select')) if (st[sel.dataset.key] !== undefined && document.activeElement !== sel) sel.value = String(st[sel.dataset.key]);
}
function drawOverlay(d) {
  const conv = d.frame === 'pedro' ? (x, y) => { const p = fromPedro(x, y, 0); return [p.x, p.y]; } : (x, y) => [x, y];
  const scale = canvas.width / 150;
  let stroke = '#ffffff', fill = '#ffffff', lw = 1, alpha = 1;
  for (const op of d.ops) {
    ctx.globalAlpha = alpha;
    switch (op.op) {
      case 'stroke': stroke = op.c; break; case 'fill': fill = op.c; break; case 'strokeWidth': lw = op.w; break; case 'alpha': alpha = op.a; break;
      case 'circle': { const [sx, sy] = fieldToScreen(...conv(op.x, op.y)); ctx.beginPath(); ctx.arc(sx, sy, op.r * scale, 0, Math.PI * 2);
        if (op.stroke === false || (d.source === 'panels' && op.fill && op.fill !== 'transparent')) { ctx.fillStyle = d.source === 'panels' ? op.fill : fill; ctx.fill(); }
        if (op.stroke !== false) { ctx.strokeStyle = d.source === 'panels' ? (op.stroke && op.stroke !== 'transparent' ? op.stroke : (op.fill || stroke)) : stroke; ctx.lineWidth = d.source === 'panels' ? Math.max(1, (op.width || 0.1) * scale) : lw; ctx.stroke(); } break; }
      case 'line': { const a = fieldToScreen(...conv(op.x1, op.y1)), b = fieldToScreen(...conv(op.x2, op.y2)); ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.strokeStyle = d.source === 'panels' ? (op.stroke && op.stroke !== 'transparent' ? op.stroke : (op.fill || stroke)) : stroke; ctx.lineWidth = d.source === 'panels' ? Math.max(1, (op.width || 0.1) * scale) : lw; ctx.stroke(); break; }
      case 'rect': { const pts = [conv(op.x, op.y), conv(op.x + op.w, op.y), conv(op.x + op.w, op.y + op.h), conv(op.x, op.y + op.h)]; ctx.beginPath(); pts.forEach((p, i) => { const [sx, sy] = fieldToScreen(p[0], p[1]); if (i === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy); }); ctx.closePath();
        if (op.stroke === false || (d.source === 'panels' && op.fill && op.fill !== 'transparent')) { ctx.fillStyle = d.source === 'panels' ? op.fill : fill; ctx.fill(); }
        if (op.stroke !== false) { ctx.strokeStyle = d.source === 'panels' ? (op.stroke && op.stroke !== 'transparent' ? op.stroke : stroke) : stroke; ctx.lineWidth = d.source === 'panels' ? Math.max(1, (op.width || 0.1) * scale) : lw; ctx.stroke(); } break; }
      case 'polygon': case 'polyline': { ctx.beginPath(); op.xs.forEach((x, i) => { const [sx, sy] = fieldToScreen(...conv(x, op.ys[i])); if (i === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy); }); if (op.op === 'polygon') ctx.closePath();
        if (op.stroke === false) { ctx.fillStyle = fill; ctx.fill(); } else { ctx.strokeStyle = stroke; ctx.lineWidth = lw; ctx.stroke(); } break; }
      case 'text': { const [sx, sy] = fieldToScreen(...conv(op.x, op.y)); ctx.fillStyle = fill; ctx.font = op.font || '12px system-ui'; ctx.fillText(op.t, sx, sy); break; }
      case 'grid': break;
    }
  }
  ctx.globalAlpha = 1;
}
function sizeCanvas() { const wrap = canvas.parentElement; const s = Math.max(200, Math.min(wrap.clientWidth, wrap.clientHeight) - 4); if (canvas.width !== s) { canvas.width = s; canvas.height = s; } }
window.addEventListener('resize', sizeCanvas);
function frame() { sizeCanvas(); drawField(); requestAnimationFrame(frame); }
requestAnimationFrame(frame);

// drag robot / rotate
canvas.addEventListener('pointerdown', (e) => {
  if (!state.last) return; canvas.setPointerCapture(e.pointerId);
  const rect = canvas.getBoundingClientRect(); const sx = (e.clientX - rect.left) * canvas.width / rect.width, sy = (e.clientY - rect.top) * canvas.height / rect.height;
  const [fx, fy] = screenToField(sx, sy); const r = state.last.robot;
  state.drag = { rotate: e.shiftKey || e.button === 2, x: r.x, y: r.y, h: r.headingDeg, ox: fx - r.x, oy: fy - r.y };
});
canvas.addEventListener('pointermove', (e) => {
  if (!state.drag) return;
  const rect = canvas.getBoundingClientRect(); const sx = (e.clientX - rect.left) * canvas.width / rect.width, sy = (e.clientY - rect.top) * canvas.height / rect.height;
  const [fx, fy] = screenToField(sx, sy);
  if (state.drag.rotate) { state.drag.h = Math.atan2(fy - state.drag.y, fx - state.drag.x) * 180 / Math.PI; }
  else { state.drag.x = Math.max(-72, Math.min(72, fx - state.drag.ox)); state.drag.y = Math.max(-72, Math.min(72, fy - state.drag.oy)); }
  send({ type: 'sim.setPose', x: state.drag.x, y: state.drag.y, headingDeg: state.drag.h });
});
canvas.addEventListener('pointerup', () => { state.drag = null; });
canvas.addEventListener('contextmenu', (e) => e.preventDefault());
canvas.addEventListener('wheel', (e) => { if (!state.last) return; e.preventDefault(); const r = state.last.robot; send({ type: 'sim.setPose', x: r.x, y: r.y, headingDeg: r.headingDeg + (e.deltaY > 0 ? -5 : 5) }); }, { passive: false });
$('setPoseBtn').onclick = () => {
  let x = parseFloat($('poseX').value) || 0, y = parseFloat($('poseY').value) || 0, h = parseFloat($('poseH').value) || 0;
  if ($('frameSelect').value === 'pedro') { const p = fromPedro(x, y, h); x = p.x; y = p.y; h = p.h; }
  send({ type: 'sim.setPose', x, y, headingDeg: h });
};
$('resetPoseBtn').onclick = () => send({ type: 'sim.reset' });
$('saveStartBtn').onclick = () => { const r = state.last.robot; send({ type: 'sim.setStartPose', x: r.x, y: r.y, headingDeg: r.headingDeg }); toast('info', 'Start pose saved'); };
$('rotateSelect').onchange = (e) => { state.viewRot = parseInt(e.target.value, 10); };

$('frameSelect').onchange = () => { if (!state.last) return; const r = state.last.robot; const p = $('frameSelect').value === 'pedro' ? toPedro(r.x, r.y, r.headingDeg) : { x: r.x, y: r.y, h: r.headingDeg }; $('poseX').value = p.x.toFixed(1); $('poseY').value = p.y.toFixed(1); $('poseH').value = p.h.toFixed(0); };

// ------------------------------------------------------------------ hardware panel
const devInputs = {}; // name -> {key -> element} to avoid re-rendering while editing
let devicesSignature = '';
function renderDevices(devices) {
  const sig = devices.map(d => d.name + '|' + d.type + '|' + d.hub).join(';');
  const root = $('devices');
  if (sig !== devicesSignature) {
    devicesSignature = sig; root.innerHTML = '';
    const byHub = {};
    for (const d of devices) (byHub[d.hub] ||= []).push(d);
    for (const hub of Object.keys(byHub)) {
      const h = document.createElement('div'); h.className = 'hubhead'; h.textContent = hub; root.appendChild(h);
      for (const d of byHub[hub]) root.appendChild(deviceCard(d));
    }
  }
  for (const d of devices) {
    const card = document.querySelector(`.dev[data-name="${CSS.escape(d.name)}"][data-type="${CSS.escape(d.type)}"]`); if (!card) continue;
    const vals = card.querySelector('.vals'); const parts = [];
    for (const [k, v] of Object.entries(d.values)) { if (k === 'input') continue; parts.push(`<span><b>${k}</b> ${fmt(v)}</span>`); }
    const html = parts.join(''); if (vals.innerHTML !== html) vals.innerHTML = html;
    const bar = card.querySelector('.bar i');
    if (bar) { const p = d.values.power != null ? d.values.power : d.values.commanded != null ? (d.values.commanded * 2 - 1) : 0; const w = Math.abs(p) * 50; bar.style.left = (p < 0 ? 50 - w : 50) + '%'; bar.style.width = w + '%'; }
    const inputs = devInputs[d.name] || {};
    for (const [k, el] of Object.entries(inputs)) { if (document.activeElement === el) continue; const v = d.values[k]; if (v == null) continue; if (el.type === 'checkbox') el.checked = !!v; else if (el.type === 'range' || el.type === 'number') { if (Math.abs(parseFloat(el.value) - v) > 1e-6) el.value = v; } }
  }
}
function fmt(v) { if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(Math.abs(v) < 10 ? 3 : 1); if (Array.isArray(v)) return '[' + v.join(',') + ']'; return String(v); }
function deviceCard(d) {
  const card = document.createElement('div'); card.className = 'dev'; card.dataset.name = d.name; card.dataset.type = d.type;
  card.innerHTML = `<div class="head"><span>${esc(d.name)}</span><span class="type">${esc(d.type)}${d.port != null ? ' · port ' + d.port : ''}</span>${d.autoCreated ? '<span class="badge" title="Not in the robot configuration; created when the code asked for it">auto</span>' : ''}</div><div class="vals"></div>`;
  if (d.type === 'DcMotorEx' || d.type === 'Servo' || d.type === 'CRServo') { const bar = document.createElement('div'); bar.className = 'bar'; bar.innerHTML = '<i></i>'; card.appendChild(bar); }
  const inputs = document.createElement('div'); inputs.className = 'inputs'; const reg = devInputs[d.name] = {};
  const addToggle = (key, labelText) => { const l = document.createElement('label'); const c = document.createElement('input'); c.type = 'checkbox'; c.onchange = () => send({ type: 'device.set', name: d.name, key, value: c.checked }); l.appendChild(c); l.appendChild(document.createTextNode(labelText)); inputs.appendChild(l); reg[key] = c; };
  const addRange = (key, labelText, min, max, step) => { const l = document.createElement('label'); const r = document.createElement('input'); r.type = 'range'; r.min = min; r.max = max; r.step = step; r.oninput = () => send({ type: 'device.set', name: d.name, key, value: parseFloat(r.value) }); l.appendChild(document.createTextNode(labelText)); l.appendChild(r); inputs.appendChild(l); reg[key] = r; };
  const addNumber = (key, labelText, step) => { const l = document.createElement('label'); const n = document.createElement('input'); n.type = 'number'; n.step = step; n.onchange = () => send({ type: 'device.set', name: d.name, key, value: parseFloat(n.value) }); l.appendChild(document.createTextNode(labelText)); l.appendChild(n); inputs.appendChild(l); reg[key] = n; };
  switch (d.type) {
    case 'DigitalChannel': addToggle('state', 'input high'); break;
    case 'TouchSensor': addToggle('pressed', 'pressed'); break;
    case 'AnalogInput': addRange('voltage', 'V', 0, 3.3, 0.01); break;
    case 'Rev2mDistanceSensor': addNumber('distanceMm', 'mm', 1); addToggle('raycast', 'ray-cast'); break;
    case 'RevColorSensorV3': addRange('red', 'R', 0, 1, 0.01); addRange('green', 'G', 0, 1, 0.01); addRange('blue', 'B', 0, 1, 0.01); addRange('alpha', 'A', 0, 1, 0.01); addNumber('distanceMm', 'mm', 1); break;
    case 'Limelight3A': addToggle('forceNoTargets', 'hide targets'); break;
    case 'Servo': addRange('position', 'move', 0, 1, 0.01); break;
  }
  if (inputs.children.length) card.appendChild(inputs);
  return card;
}
function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }

// ------------------------------------------------------------------ configurables
let configSig = '';
function renderConfigurables() {
  const root = $('configurables');
  const sig = state.configurables.map(c => c.className + ':' + c.fields.map(f => f.path + '/' + f.type + '/' + f.editable).join(',')).join(';');
  if (sig !== configSig) {
    configSig = sig; root.innerHTML = '';
    if (!state.configurables.length) { root.innerHTML = '<p class="hint">No @Configurable / @Config classes found in the team code.</p>'; return; }
    for (const c of state.configurables) {
      const div = document.createElement('div'); div.className = 'cls'; const h = document.createElement('h3'); h.textContent = c.simpleName; h.title = c.className; div.appendChild(h);
      for (const f of c.fields) {
        const row = document.createElement('div'); row.className = 'row' + (f.editable ? '' : ' ro'); row.dataset.path = f.path; row.dataset.cls = c.className;
        const name = document.createElement('span'); name.textContent = f.path; name.title = f.type; row.appendChild(name);
        let input;
        if (!f.editable) { input = document.createElement('span'); input.className = 'val'; }
        else if (f.type === 'boolean') { input = document.createElement('input'); input.type = 'checkbox'; input.onchange = () => send({ type: 'configurable.set', className: c.className, path: f.path, value: input.checked }); }
        else if (f.type === 'enum') { input = document.createElement('select'); for (const o of f.options) { const op = document.createElement('option'); op.value = o; op.textContent = o; input.appendChild(op); } input.onchange = () => send({ type: 'configurable.set', className: c.className, path: f.path, value: input.value }); }
        else { input = document.createElement('input'); input.type = f.type === 'String' ? 'text' : 'number'; if (input.type === 'number') input.step = 'any'; input.onchange = () => send({ type: 'configurable.set', className: c.className, path: f.path, value: input.value }); input.onkeydown = (e) => { if (e.key === 'Enter') input.blur(); }; }
        row.appendChild(input); div.appendChild(row);
      }
      root.appendChild(div);
    }
  }
  for (const c of state.configurables) for (const f of c.fields) {
    const row = root.querySelector(`.row[data-cls="${CSS.escape(c.className)}"][data-path="${CSS.escape(f.path)}"]`); if (!row) continue;
    const el = row.children[1]; if (document.activeElement === el) continue;
    if (el.tagName === 'SPAN') el.textContent = fmt(f.value); else if (el.type === 'checkbox') el.checked = !!f.value; else el.value = f.value == null ? '' : f.value;
  }
}

// ------------------------------------------------------------------ graph
const COLORS = ['#4f8cff', '#2ecc71', '#f1c40f', '#e74c3c', '#9b59b6', '#1abc9c', '#e67e22', '#ff7ab6'];
function renderGraph() {
  const g = $('graph'); const c = g.getContext('2d'); g.width = g.clientWidth || 360; const W = g.width, H = g.height; c.clearRect(0, 0, W, H);
  const names = Object.keys(state.graphs); if (!names.length) { c.fillStyle = '#8b94a7'; c.font = '12px system-ui'; c.fillText('No graph data (Panels graph / Dashboard packets).', 10, 20); $('graphLegend').innerHTML = ''; return; }
  const now = Date.now(); const span = 20000; let min = Infinity, max = -Infinity;
  for (const n of names) for (const [t, v] of state.graphs[n]) if (now - t < span) { min = Math.min(min, v); max = Math.max(max, v); }
  if (!isFinite(min)) { min = 0; max = 1; } if (max - min < 1e-9) { max = min + 1; }
  const pad = (max - min) * 0.05; min -= pad; max += pad;
  c.strokeStyle = '#2a3140'; c.beginPath(); for (let i = 0; i <= 4; i++) { const y = i * H / 4; c.moveTo(0, y); c.lineTo(W, y); } c.stroke();
  c.fillStyle = '#8b94a7'; c.font = '10px monospace'; c.fillText(max.toFixed(2), 2, 10); c.fillText(min.toFixed(2), 2, H - 3);
  names.forEach((n, i) => { c.strokeStyle = COLORS[i % COLORS.length]; c.lineWidth = 1.5; c.beginPath(); let first = true; for (const [t, v] of state.graphs[n]) { if (now - t > span) continue; const x = W - (now - t) / span * W, y = H - (v - min) / (max - min) * H; if (first) { c.moveTo(x, y); first = false; } else c.lineTo(x, y); } c.stroke(); });
  $('graphLegend').innerHTML = names.map((n, i) => `<span style="color:${COLORS[i % COLORS.length]}">■ ${esc(n)} ${fmt(state.graphs[n].length ? state.graphs[n][state.graphs[n].length - 1][1] : 0)}</span>`).join('');
}

// ------------------------------------------------------------------ log
const LEVELS = { VERBOSE: 0, DEBUG: 1, INFO: 2, WARN: 3, ERROR: 4 };
function renderLog(newEntries) {
  const pre = $('log'); const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 30;
  if (!newEntries) pre.innerHTML = '';
  const entries = newEntries || state.logEntries; const frag = document.createDocumentFragment();
  for (const e of entries) {
    if (LEVELS[e.level] < state.logLevel) continue;
    if (state.logFilter && !(e.tag + ' ' + e.message).toLowerCase().includes(state.logFilter)) continue;
    const span = document.createElement('span'); span.className = e.level[0];
    const t = new Date(e.timeMs); span.textContent = `${t.toLocaleTimeString([], { hour12: false })}.${String(t.getMilliseconds()).padStart(3, '0')} ${e.level[0]}/${e.tag}: ${e.message}\n`;
    frag.appendChild(span);
  }
  pre.appendChild(frag);
  while (pre.childNodes.length > 3000) pre.removeChild(pre.firstChild);
  if (atBottom) pre.scrollTop = pre.scrollHeight;
}
$('logLevel').onchange = (e) => { state.logLevel = parseInt(e.target.value, 10); renderLog(null); };
$('logFilter').oninput = (e) => { state.logFilter = e.target.value.toLowerCase(); renderLog(null); };
$('logClear').onclick = () => { state.logEntries = []; $('log').innerHTML = ''; send({ type: 'log.clear' }); };
$('configLoadBtn').onclick = () => send({ type: 'config.get' });
$('configSaveBtn').onclick = () => { try { JSON.parse($('configText').value); } catch (e) { toast('error', 'Invalid JSON: ' + e.message); return; } send({ type: 'config.save', json: $('configText').value }); };
$('configSaveCurrentBtn').onclick = () => { send({ type: 'config.saveCurrent' }); setTimeout(() => send({ type: 'config.get' }), 300); };

// ------------------------------------------------------------------ gamepads
const BUTTONS = [['a', 'A'], ['b', 'B'], ['x', 'X'], ['y', 'Y'], ['lb', 'LB'], ['rb', 'RB'], ['du', '▲'], ['dd', '▼'], ['dl', '◀'], ['dr', '▶'], ['back', 'back'], ['start', 'start'], ['guide', 'guide'], ['ls', 'L3'], ['rs', 'R3'], ['touchpad', 'pad']];
const KEYMAP = { KeyZ: 'a', KeyX: 'b', KeyC: 'x', KeyV: 'y', Digit1: 'lb', Digit2: 'rb', KeyT: 'du', KeyG: 'dd', KeyF: 'dl', KeyH: 'dr', KeyR: 'start', Backspace: 'back', Tab: 'guide', Digit3: 'ls', Digit4: 'rs' };
class VirtualPad {
  constructor(el, id) {
    this.el = el; this.id = id; this.keys = new Set();
    this.s = { lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0, type: 'xbox' }; for (const [k] of BUTTONS) this.s[k] = false;
    this.lastSent = ''; this.source = 'virtual';
    el.innerHTML = `<div class="title">Gamepad ${id} <span class="src">virtual · ${id === 1 ? 'keyboard: WASD / arrows / Z X C V / 1 2 / Q E / T F G H' : 'physical controller: press Start+B'}</span><span class="spacer"></span><span class="rumbleInd" title="the robot is rumbling this gamepad">rumble</span><select class="physSel"><option value="">no controller</option></select></div>
      <div class="stick" data-stick="l"><div class="knob"></div><div class="lab">left stick</div></div>
      <div class="mid"></div>
      <div class="stick" data-stick="r"><div class="knob"></div><div class="lab">right stick</div></div>`;
    const mid = el.querySelector('.mid');
    for (const [k, label] of BUTTONS) { const b = document.createElement('div'); b.className = 'btn'; b.dataset.key = k; b.textContent = label; b.onpointerdown = (e) => { e.preventDefault(); b.setPointerCapture(e.pointerId); this.s[k] = true; this.render(); }; b.onpointerup = () => { this.s[k] = false; this.render(); }; b.onpointercancel = b.onpointerup; mid.appendChild(b); }
    for (const t of ['lt', 'rt']) { const l = document.createElement('label'); l.className = 'trig'; l.innerHTML = `${t.toUpperCase()} <input type="range" min="0" max="1" step="0.01" value="0">`; const inp = l.querySelector('input'); inp.oninput = () => { this.s[t] = parseFloat(inp.value); }; inp.onpointerup = () => { inp.value = 0; this.s[t] = 0; }; mid.appendChild(l); }
    el.querySelectorAll('.stick').forEach(st => {
      const which = st.dataset.stick; const knob = st.querySelector('.knob');
      const move = (e) => { const r = st.getBoundingClientRect(); let x = (e.clientX - r.left - r.width / 2) / (r.width / 2 - 14), y = (e.clientY - r.top - r.height / 2) / (r.height / 2 - 14); const m = Math.hypot(x, y); if (m > 1) { x /= m; y /= m; } this.s[which + 'x'] = x; this.s[which + 'y'] = y; this.render(); };
      st.onpointerdown = (e) => { st.setPointerCapture(e.pointerId); st.classList.add('active'); move(e); };
      st.onpointermove = (e) => { if (st.classList.contains('active')) move(e); };
      st.onpointerup = st.onpointercancel = () => { st.classList.remove('active'); this.s[which + 'x'] = 0; this.s[which + 'y'] = 0; this.render(); };
    });
    this.physSel = el.querySelector('.physSel');
    this.physSel.onchange = () => { state.physical[id] = this.physSel.value === '' ? null : parseInt(this.physSel.value, 10); };
    this.render();
  }
  render() {
    const el = this.el;
    el.querySelectorAll('.btn').forEach(b => b.classList.toggle('on', !!this.s[b.dataset.key]));
    el.querySelectorAll('.stick').forEach(st => { const w = st.dataset.stick; const k = st.querySelector('.knob'); const r = st.clientWidth / 2 - 14; k.style.left = (st.clientWidth / 2 - 14 + this.s[w + 'x'] * r) + 'px'; k.style.top = (st.clientHeight / 2 - 14 + this.s[w + 'y'] * r) + 'px'; });
    el.querySelectorAll('.trig input').forEach((inp, i) => { const v = this.s[i === 0 ? 'lt' : 'rt']; if (document.activeElement !== inp) inp.value = v; });
  }
  applyPhysical(gp) {
    const dz = (v) => Math.abs(v) < 0.08 ? 0 : v;
    const b = (i) => !!(gp.buttons[i] && gp.buttons[i].pressed);
    this.s.lx = dz(gp.axes[0] || 0); this.s.ly = dz(gp.axes[1] || 0); this.s.rx = dz(gp.axes[2] || 0); this.s.ry = dz(gp.axes[3] || 0);
    this.s.a = b(0); this.s.b = b(1); this.s.x = b(2); this.s.y = b(3); this.s.lb = b(4); this.s.rb = b(5);
    this.s.lt = gp.buttons[6] ? gp.buttons[6].value : 0; this.s.rt = gp.buttons[7] ? gp.buttons[7].value : 0;
    this.s.back = b(8); this.s.start = b(9); this.s.ls = b(10); this.s.rs = b(11); this.s.du = b(12); this.s.dd = b(13); this.s.dl = b(14); this.s.dr = b(15); this.s.guide = b(16);
    this.s.type = /playstation|dualshock|dualsense|054c/i.test(gp.id) ? 'ps4' : 'xbox';
    this.render();
  }
  applyKeyboard() {
    const k = this.keys; const ax = (neg, pos) => (k.has(pos) ? 1 : 0) - (k.has(neg) ? 1 : 0);
    this.s.lx = ax('KeyA', 'KeyD'); this.s.ly = ax('KeyW', 'KeyS'); this.s.rx = ax('ArrowLeft', 'ArrowRight'); this.s.ry = ax('ArrowUp', 'ArrowDown');
    this.s.lt = k.has('KeyQ') ? 1 : 0; this.s.rt = k.has('KeyE') ? 1 : 0;
    for (const [code, btn] of Object.entries(KEYMAP)) this.s[btn] = k.has(code);
    this.render();
  }
  sendIfNeeded(force) {
    const json = JSON.stringify(this.s);
    if (force || json !== this.lastSent) { this.lastSent = json; send({ type: 'gamepad', id: this.id, state: this.s }); }
  }
}
const pads = { 1: new VirtualPad($('gp1'), 1), 2: new VirtualPad($('gp2'), 2) };

// ------------------------------------------------------------------ rumble
// gamepad1.rumble(...) in the team code is drained by the simulator's event loop and arrives here as a
// list of steps: large / small are the two rumble motors (0..255) and duration is milliseconds, or -1
// for "until cancelled". Replay them on the assigned physical controller through the Gamepad API, and
// mirror the activity on the on-screen pad so the effect is visible without a controller.
const RUMBLE_CONTINUOUS_MS = 10 * 60 * 1000;
const rumble = { 1: { timers: [], untilTimer: 0 }, 2: { timers: [], untilTimer: 0 } };

function physicalGamepad(id) {
  const gps = navigator.getGamepads ? Array.from(navigator.getGamepads()).filter(Boolean) : [];
  return state.physical[id] == null ? null : (gps.find(g => g.index === state.physical[id]) || null);
}
function rumbleActuator(id) {
  const gp = physicalGamepad(id); if (!gp) return null;
  const act = gp.vibrationActuator || (gp.hapticActuators && gp.hapticActuators[0]);
  return act && typeof act.playEffect === 'function' ? act : null;
}
function playRumbleStep(act, step) {
  if (!act) return;
  const duration = step.duration < 0 ? RUMBLE_CONTINUOUS_MS : Math.max(0, step.duration | 0);
  const mag = (v) => Math.min(1, Math.max(0, (v | 0) / 255));
  try { act.playEffect('dual-rumble', { startDelay: 0, duration, weakMagnitude: mag(step.small), strongMagnitude: mag(step.large) }); } catch (e) { /* controller went away */ }
}
function cancelRumbleTimers(id) {
  const st = rumble[id];
  for (const t of st.timers) clearTimeout(t);
  st.timers = []; clearTimeout(st.untilTimer); st.untilTimer = 0;
}
function resetActuator(id) {
  const act = rumbleActuator(id); if (!act) return;
  try { if (typeof act.reset === 'function') act.reset(); else act.playEffect('dual-rumble', { duration: 0, weakMagnitude: 0, strongMagnitude: 0 }); } catch (e) {}
}
function rumbleIndicator(id, on) { const el = pads[id] && pads[id].el; if (el) el.classList.toggle('rumbling', on); }
function stopRumble(id) { cancelRumbleTimers(id); resetActuator(id); rumbleIndicator(id, false); }
function stopAllRumble() { stopRumble(1); stopRumble(2); }
function applyRumble(m) {
  const id = m.id === 2 ? 2 : 1;
  const steps = Array.isArray(m.steps) ? m.steps : [];
  cancelRumbleTimers(id);
  let power = 0;
  for (const s of steps) power += Math.abs(s.large | 0) + Math.abs(s.small | 0);
  if (!steps.length || power === 0) { resetActuator(id); rumbleIndicator(id, false); return; } // e.g. stopRumble()
  const act = rumbleActuator(id), st = rumble[id];
  let at = 0, duration = 0;
  for (const step of steps) {
    const delay = at;
    st.timers.push(setTimeout(() => playRumbleStep(act, step), delay));
    if (step.duration < 0) { duration = RUMBLE_CONTINUOUS_MS; break; } // a continuous step never hands over
    at += Math.max(0, step.duration | 0); duration = at;
  }
  rumbleIndicator(id, true);
  if (duration < RUMBLE_CONTINUOUS_MS) st.untilTimer = setTimeout(() => rumbleIndicator(id, false), duration);
}

let keepalive = 0;
function gamepadLoop() {
  const gps = navigator.getGamepads ? Array.from(navigator.getGamepads()).filter(Boolean) : [];
  // keep selectors in sync
  for (const id of [1, 2]) {
    const sel = pads[id].physSel; const opts = ['<option value="">no controller</option>'].concat(gps.map(g => `<option value="${g.index}">${esc(g.id.slice(0, 32))}</option>`)).join('');
    if (sel.dataset.opts !== opts) { sel.dataset.opts = opts; sel.innerHTML = opts; sel.value = state.physical[id] == null ? '' : String(state.physical[id]); }
  }
  // Driver Station style assignment: Start+A -> gamepad 1, Start+B -> gamepad 2
  for (const g of gps) {
    const start = g.buttons[9] && g.buttons[9].pressed;
    if (start && g.buttons[0] && g.buttons[0].pressed && state.physical[1] !== g.index) { state.physical[1] = g.index; if (state.physical[2] === g.index) state.physical[2] = null; toast('info', `${g.id.slice(0, 40)} → gamepad 1`); }
    if (start && g.buttons[1] && g.buttons[1].pressed && state.physical[2] !== g.index) { state.physical[2] = g.index; if (state.physical[1] === g.index) state.physical[1] = null; toast('info', `${g.id.slice(0, 40)} → gamepad 2`); }
  }
  for (const id of [1, 2]) {
    const p = pads[id]; const phys = state.physical[id] != null ? gps.find(g => g.index === state.physical[id]) : null;
    if (phys) { p.applyPhysical(phys); p.el.querySelector('.src').textContent = 'physical: ' + phys.id.slice(0, 40); }
    else if (id === state.keyboardPad && p.keys.size) { p.applyKeyboard(); }
    // resend the latest state periodically so a dropped packet does not stick
    p.sendIfNeeded(keepalive % 15 === 0);
  }
  keepalive++;
  setTimeout(gamepadLoop, 33);
}
gamepadLoop();
window.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.tagName === 'SELECT') return;
  if (e.code === 'Tab') e.preventDefault();
  const p = pads[state.keyboardPad]; if (!p.keys.has(e.code)) { p.keys.add(e.code); p.applyKeyboard(); }
  if (e.code === 'Space') { e.preventDefault(); }
});
window.addEventListener('keyup', (e) => { const p = pads[state.keyboardPad]; if (p.keys.delete(e.code)) { p.applyKeyboard(); } });
window.addEventListener('blur', () => { for (const id of [1, 2]) { pads[id].keys.clear(); pads[id].applyKeyboard(); } });

connect();
})();
