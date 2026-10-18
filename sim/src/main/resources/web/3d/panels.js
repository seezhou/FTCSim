// FTCSim 3D — port of the 2D front end's remaining panels (web/app.js).
//
// Everything here mirrors app.js one-for-one against the same backend messages
// ({type:'log'|'configurables'|'graphs'|'config'|'state'|'hello'|'opmodes'|'buildStatus'})
// and reuses the very same element ids, so the two front ends stay in sync.
// Covered: repo meta line, sim rate / I-O pills, pause+step, Reload code, warn box,
// the right-hand tab stack (Hardware / Configurables / Graph / Log / Robot config),
// the Pedro pose readout and the pose entry / save-start / reset controls.
// No backend change: it only sends the same messages the 2D page sends.

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = (v) => {
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(Math.abs(v) < 10 ? 3 : 1);
  if (Array.isArray(v)) return '[' + v.join(',') + ']';
  return String(v);
};

// ---------------------------------------------------------------- coordinates
const norm = (d) => { d = ((d + 180) % 360 + 360) % 360 - 180; return d; };
const toPedro = (x, y, h) => ({ x: y + 72, y: 72 - x, h: norm(h - 90) });
const fromPedro = (px, py, ph) => ({ x: 72 - py, y: px - 72, h: norm(ph + 90) });

export function createPanels({ send, toast }) {
  const st = {
    last: null, hello: null, build: null, telemetryTab: 'ds', rightTab: 'hardware',
    logLevel: 2, logFilter: '', logEntries: [], configurables: [], graphs: {},
  };

  // =============================================================== top bar
  $('reloadBtn').onclick = () => send({ type: 'code.reload' });
  $('pauseBtn').onclick = () => send({ type: 'sim.pause', paused: !(st.last && st.last.sim.paused) });
  $('stepBtn').onclick = () => {
    if (!(st.last && st.last.sim.paused)) send({ type: 'sim.pause', paused: true });
    send({ type: 'sim.step', ms: parseFloat($('stepSize').value) });
  };

  // =============================================================== right tabs
  const rightButtons = document.querySelectorAll('#rightTabs button');
  rightButtons.forEach((b) => {
    b.onclick = () => {
      rightButtons.forEach((x) => x.classList.remove('active'));
      b.classList.add('active');
      st.rightTab = b.dataset.tab;
      $('rightPanel').classList.remove('collapsed');
      $('rightToggle').textContent = '–';
      document.querySelectorAll('#rightPanel .tab').forEach((t) => t.classList.toggle('active', t.id === 'tab-' + st.rightTab));
      if (st.rightTab === 'robot' && !$('configText').value) send({ type: 'config.get' });
      if (st.rightTab === 'graph') renderGraph();
    };
  });
  $('rightToggle').onclick = () => {
    const p = $('rightPanel');
    const collapsed = p.classList.toggle('collapsed');
    $('rightToggle').textContent = collapsed ? '+' : '–';
    if (collapsed) {
      p.dataset.savedW = p.style.width; p.dataset.savedH = p.style.height;
      p.style.width = ''; p.style.height = '';
    } else {
      p.style.width = p.dataset.savedW || ''; p.style.height = p.dataset.savedH || '';
    }
  };

  // =============================================================== hardware
  const devInputs = {}; // name -> {key -> element}
  let devicesSignature = '';
  function renderDevices(devices) {
    if (!devices) return;
    const sig = devices.map((d) => d.name + '|' + d.type + '|' + d.hub).join(';');
    const root = $('devices');
    if (sig !== devicesSignature) {
      devicesSignature = sig; root.innerHTML = '';
      for (const k of Object.keys(devInputs)) delete devInputs[k];
      const byHub = {};
      for (const d of devices) (byHub[d.hub] ||= []).push(d);
      for (const hub of Object.keys(byHub)) {
        const h = document.createElement('div'); h.className = 'hubhead'; h.textContent = hub; root.appendChild(h);
        for (const d of byHub[hub]) root.appendChild(deviceCard(d));
      }
    }
    for (const d of devices) {
      const card = root.querySelector(`.dev[data-name="${CSS.escape(d.name)}"][data-type="${CSS.escape(d.type)}"]`);
      if (!card) continue;
      const vals = card.querySelector('.vals'); const parts = [];
      for (const [k, v] of Object.entries(d.values)) { if (k === 'input') continue; parts.push(`<span><b>${esc(k)}</b> ${esc(fmt(v))}</span>`); }
      const html = parts.join(''); if (vals.innerHTML !== html) vals.innerHTML = html;
      const bar = card.querySelector('.bar i');
      if (bar) {
        const p = d.values.power != null ? d.values.power : d.values.commanded != null ? (d.values.commanded * 2 - 1) : 0;
        const w = Math.abs(p) * 50;
        bar.style.left = (p < 0 ? 50 - w : 50) + '%';
        bar.style.width = w + '%';
      }
      const inputs = devInputs[d.name] || {};
      for (const [k, el] of Object.entries(inputs)) {
        if (document.activeElement === el) continue;
        const v = d.values[k]; if (v == null) continue;
        if (el.type === 'checkbox') el.checked = !!v;
        else if (el.type === 'range' || el.type === 'number') { if (Math.abs(parseFloat(el.value) - v) > 1e-6) el.value = v; }
      }
    }
  }
  function deviceCard(d) {
    const card = document.createElement('div'); card.className = 'dev'; card.dataset.name = d.name; card.dataset.type = d.type;
    card.innerHTML = `<div class="head"><span>${esc(d.name)}</span><span class="type">${esc(d.type)}${d.port != null ? ' · port ' + d.port : ''}</span>${d.autoCreated ? '<span class="badge" title="Not in the robot configuration; created when the code asked for it">auto</span>' : ''}</div><div class="vals"></div>`;
    if (d.type === 'DcMotorEx' || d.type === 'Servo' || d.type === 'CRServo') { const bar = document.createElement('div'); bar.className = 'bar'; bar.innerHTML = '<i></i>'; card.appendChild(bar); }
    const inputs = document.createElement('div'); inputs.className = 'inputs'; const reg = devInputs[d.name] = {};
    const addToggle = (key, labelText) => { const l = document.createElement('label'); const c = document.createElement('input'); c.type = 'checkbox'; c.onchange = () => send({ type: 'device.set', name: d.name, key, value: c.checked }); l.appendChild(c); l.appendChild(document.createTextNode(labelText)); inputs.appendChild(l); reg[key] = c; };
    const addRange = (key, labelText, min, max, step) => { const l = document.createElement('label'); const r = document.createElement('input'); r.type = 'range'; r.min = min; r.max = max; r.step = step; r.oninput = () => send({ type: 'device.set', name: d.name, key, value: parseFloat(r.value) }); l.appendChild(document.createTextNode(labelText)); l.appendChild(r); inputs.appendChild(l); reg[key] = r; };
    const addNumber = (key, labelText, step) => { const l = document.createElement('label'); const n = document.createElement('input'); n.type = 'number'; n.step = step; n.style.width = '70px'; n.onchange = () => send({ type: 'device.set', name: d.name, key, value: parseFloat(n.value) }); l.appendChild(document.createTextNode(labelText)); l.appendChild(n); inputs.appendChild(l); reg[key] = n; };
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

  // =============================================================== configurables
  let configSig = '';
  function renderConfigurables() {
    const root = $('configurables');
    const sig = st.configurables.map((c) => c.className + ':' + c.fields.map((f) => f.path + '/' + f.type + '/' + f.editable).join(',')).join(';');
    if (sig !== configSig) {
      configSig = sig; root.innerHTML = '';
      if (!st.configurables.length) { root.innerHTML = '<p class="hint">No @Configurable / @Config classes found in the team code.</p>'; return; }
      for (const c of st.configurables) {
        const div = document.createElement('div'); div.className = 'cls';
        const h = document.createElement('h3'); h.textContent = c.simpleName; h.title = c.className; div.appendChild(h);
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
    for (const c of st.configurables) for (const f of c.fields) {
      const row = root.querySelector(`.row[data-cls="${CSS.escape(c.className)}"][data-path="${CSS.escape(f.path)}"]`); if (!row) continue;
      const el = row.children[1]; if (document.activeElement === el) continue;
      if (el.tagName === 'SPAN') el.textContent = fmt(f.value);
      else if (el.type === 'checkbox') el.checked = !!f.value;
      else el.value = f.value == null ? '' : f.value;
    }
  }

  // =============================================================== graph
  const COLORS = ['#4f8cff', '#2ecc71', '#f1c40f', '#e74c3c', '#9b59b6', '#1abc9c', '#e67e22', '#ff7ab6'];
  function renderGraph() {
    const g = $('graph'); if (!g) return;
    const c = g.getContext('2d');
    g.width = Math.max(200, Math.round(g.clientWidth) || 380);
    const W = g.width, H = g.height;
    c.clearRect(0, 0, W, H);
    const names = Object.keys(st.graphs);
    if (!names.length) { c.fillStyle = '#8b94a7'; c.font = '12px system-ui'; c.fillText('No graph data (Panels graph / Dashboard packets).', 10, 20); $('graphLegend').innerHTML = ''; return; }
    const now = Date.now(); const span = 20000;
    let min = Infinity, max = -Infinity;
    for (const n of names) for (const [t, v] of st.graphs[n]) if (now - t < span) { min = Math.min(min, v); max = Math.max(max, v); }
    if (!isFinite(min)) { min = 0; max = 1; }
    if (max - min < 1e-9) max = min + 1;
    const pad = (max - min) * 0.05; min -= pad; max += pad;
    c.strokeStyle = '#2a3140'; c.beginPath(); for (let i = 0; i <= 4; i++) { const y = i * H / 4; c.moveTo(0, y); c.lineTo(W, y); } c.stroke();
    c.fillStyle = '#8b94a7'; c.font = '10px monospace';
    c.fillText(max.toFixed(2), 2, 10); c.fillText(min.toFixed(2), 2, H - 3);
    names.forEach((n, i) => {
      c.strokeStyle = COLORS[i % COLORS.length]; c.lineWidth = 1.5; c.beginPath();
      let first = true;
      for (const [t, v] of st.graphs[n]) {
        if (now - t > span) continue;
        const x = W - (now - t) / span * W, y = H - (v - min) / (max - min) * H;
        if (first) { c.moveTo(x, y); first = false; } else c.lineTo(x, y);
      }
      c.stroke();
    });
    $('graphLegend').innerHTML = names.map((n, i) => `<span style="color:${COLORS[i % COLORS.length]}">■ ${esc(n)} ${esc(fmt(st.graphs[n].length ? st.graphs[n][st.graphs[n].length - 1][1] : 0))}</span>`).join('');
  }

  // =============================================================== log
  const LEVELS = { VERBOSE: 0, DEBUG: 1, INFO: 2, WARN: 3, ERROR: 4 };
  function renderLog(newEntries) {
    const pre = $('log'); if (!pre) return;
    const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 30;
    if (!newEntries) pre.innerHTML = '';
    const entries = newEntries || st.logEntries;
    const frag = document.createDocumentFragment();
    for (const e of entries) {
      if (LEVELS[e.level] < st.logLevel) continue;
      if (st.logFilter && !(e.tag + ' ' + e.message).toLowerCase().includes(st.logFilter)) continue;
      const span = document.createElement('span'); span.className = e.level[0];
      const t = new Date(e.timeMs);
      span.textContent = `${t.toLocaleTimeString([], { hour12: false })}.${String(t.getMilliseconds()).padStart(3, '0')} ${e.level[0]}/${e.tag}: ${e.message}\n`;
      frag.appendChild(span);
    }
    pre.appendChild(frag);
    while (pre.childNodes.length > 3000) pre.removeChild(pre.firstChild);
    if (atBottom) pre.scrollTop = pre.scrollHeight;
  }
  $('logLevel').onchange = (e) => { st.logLevel = parseInt(e.target.value, 10); renderLog(null); };
  $('logFilter').oninput = (e) => { st.logFilter = e.target.value.toLowerCase(); renderLog(null); };
  $('logClear').onclick = () => { st.logEntries = []; $('log').innerHTML = ''; send({ type: 'log.clear' }); };

  // =============================================================== robot config
  let lastBuildDiag = '';
  let lastBuildOk = null; // null = first render (no toast), then true/false
  // A failed rebuild deliberately keeps the previous good build in the server's
  // `current()`, so the server keeps reporting success for it. We therefore latch the
  // failure ourselves: a build that really replaced the running code shows up as a new
  // `builtAt`, and the compiler errors arrive in the log stream while it runs.
  let buildFail = null;   // { summary, lines } while the last compile attempt is broken
  let lastBuiltAt = null;
  let sawBuilding = false;
  let buildErrSummary = '';
  let buildErrLines = [];
  function noteBuildLog(entries) {
    for (const e of entries || []) {
      if (e.tag !== 'TeamCode') continue;
      const msg = e.message || '';
      if (/^Compilation failed/i.test(msg)) buildErrSummary = msg;
      else if (e.level === 'ERROR') buildErrLines.push(msg);
    }
  }
  function renderBuild(b) {
    b = b || {};
    const pill = $('buildPill');
    // `builtAt` only moves when a compile really replaced the running code (the server
    // keeps shipping the old build's builtAt while a rebuild is in flight).
    const freshBuild = b.builtAt != null && b.builtAt !== lastBuiltAt;
    if (b.building) {
      buildFail = null; sawBuilding = true; buildErrSummary = ''; buildErrLines = [];
    } else if (freshBuild) {                                   // a new build landed → it compiled
      buildFail = null; sawBuilding = false; buildErrSummary = ''; buildErrLines = [];
    } else if (sawBuilding || (!buildFail && (buildErrSummary || buildErrLines.length))) {
      // compile finished without replacing the running build → it failed
      const first = !buildFail;
      buildFail = { summary: buildErrSummary || 'Compilation failed', lines: buildErrLines.slice(-40) };
      sawBuilding = false;
      if (first && lastBuildOk === true) toast('error', 'Build FAILED — ' + buildFail.summary);
    }
    if (!b.building && b.builtAt != null) lastBuiltAt = b.builtAt;
    // header pill: compiling… / build OK [· sources changed] / build FAILED (red)
    if (b.building) { pill.textContent = 'compiling…'; pill.className = 'pill busy'; }
    else if (buildFail) { pill.textContent = 'build FAILED'; pill.className = 'pill bad'; }
    else if (b.success) {
      pill.textContent = 'build OK' + (b.sourcesChanged ? ' · sources changed' : '');
      pill.className = 'pill ' + (b.sourcesChanged ? 'busy' : 'ok');
    } else { pill.textContent = 'build FAILED'; pill.className = 'pill bad'; }
    pill.title = buildFail ? (buildFail.summary + '\n' + buildFail.lines.join('\n')) : (b.summary || '');
    // glow the Reload button while there are uncompiled source changes or the last build failed
    $('reloadBtn').classList.toggle('attention',
      !!(!b.building && (buildFail || (b.sourcesChanged && b.success))));
    if (!b.building) lastBuildOk = buildFail ? false : !!b.success;
    // diagnostics in the Robot config tab
    const txt = buildFail
      ? (buildFail.summary || 'Compilation failed') + '\n' + buildFail.lines.join('\n') + '\n'
      : (b.summary || '') + '\n'
        + (b.diagnostics || []).map((d) => `${d.kind} ${d.file}:${d.line}: ${d.message}`).join('\n') + '\n'
        + (b.loadErrors || []).map((e) => `LOAD ${e}`).join('\n');
    if (txt !== lastBuildDiag) { lastBuildDiag = txt; $('buildDiag').textContent = txt; }
  }
  $('configLoadBtn').onclick = () => send({ type: 'config.get' });
  $('configSaveBtn').onclick = () => {
    try { JSON.parse($('configText').value); } catch (e) { toast('error', 'Invalid JSON: ' + e.message); return; }
    send({ type: 'config.save', json: $('configText').value });
  };
  $('configSaveCurrentBtn').onclick = () => { send({ type: 'config.saveCurrent' }); setTimeout(() => send({ type: 'config.get' }), 300); };

  // =============================================================== pose controls
  // The x/y/θ boxes double as the live pose readout: they show the robot pose in
  // whichever frame the Frame selector is on, and are left alone while focused.
  function readPoseInputs() {
    let x = parseFloat($('poseX').value) || 0, y = parseFloat($('poseY').value) || 0, h = parseFloat($('poseH').value) || 0;
    if ($('frameSelect').value === 'pedro') { const p = fromPedro(x, y, h); x = p.x; y = p.y; h = p.h; }
    return { x, y, h };
  }
  // The readout is refreshed from the 20 Hz state message, which would undo typing as
  // soon as a box loses focus — fill in x, click y, and x snaps straight back, so only
  // the last box you touched ever reached "Set pose". Any edit therefore holds the whole
  // row off the live readout for a few seconds: long enough to fill all three and press
  // the button, short enough that a pose that moves on its own is picked up again.
  const POSE_HOLD_MS = 6000;
  let poseHoldUntil = 0;
  const holdPose = () => { poseHoldUntil = performance.now() + POSE_HOLD_MS; };
  for (const id of ['poseX', 'poseY', 'poseH']) {
    $(id).addEventListener('input', holdPose);
    $(id).addEventListener('change', holdPose);
  }
  function showPose(x, y, h) {
    if (performance.now() < poseHoldUntil) return;
    const hn = ((h % 360) + 360) % 360;
    const v = [[$('poseX'), x.toFixed(1)], [$('poseY'), y.toFixed(1)], [$('poseH'), hn.toFixed(0)]];
    for (const [el, s] of v) if (document.activeElement !== el && el.value !== s) el.value = s;
  }
  let poseDraft = null;
  // capture the typed values before the click blurs the input (a state update could
  // otherwise overwrite them in between)
  $('setPoseBtn').onpointerdown = () => { poseDraft = readPoseInputs(); };
  $('setPoseBtn').onclick = () => {
    const p = poseDraft || readPoseInputs();
    poseDraft = null;
    send({ type: 'sim.setPose', x: p.x, y: p.y, headingDeg: p.h });
    poseHoldUntil = 0;   // the robot is being teleported — show the new truth at once
  };
  $('resetPoseBtn').onclick = () => { poseHoldUntil = 0; send({ type: 'sim.reset' }); };
  $('saveStartBtn').onclick = () => {
    const r = st.last && st.last.robot; if (!r) return;
    send({ type: 'sim.setStartPose', x: r.x, y: r.y, headingDeg: r.headingDeg });
    toast('info', 'Start pose saved');
  };
  $('frameSelect').onchange = () => {
    const r = st.last && st.last.robot; if (!r) return;
    poseHoldUntil = 0;   // switching frame is deliberate: re-seed from the live pose
    const p = $('frameSelect').value === 'pedro' ? toPedro(r.x, r.y, r.headingDeg) : { x: r.x, y: r.y, h: r.headingDeg };
    showPose(p.x, p.y, p.h);
  };

  // =============================================================== message pump
  function onMessage(m) {
    switch (m.type) {
      case 'hello':
        st.hello = m;
        $('repoMeta').textContent = `${m.repoName || 'no repo'} · SDK ${m.sdkVersion} · config ${m.configName}`;
        $('configFile').textContent = m.configFile;
        break;
      case 'opmodes': st.build = m.build; renderBuild(m.build); break;
      case 'buildStatus': st.build = m.build; renderBuild(m.build); break;
      case 'state': st.last = m; onState(m); break;
      case 'log':
        for (const e of m.entries) st.logEntries.push(e);
        if (st.logEntries.length > 5000) st.logEntries.splice(0, st.logEntries.length - 5000);
        noteBuildLog(m.entries);
        renderLog(m.entries);
        break;
      case 'configurables': st.configurables = m.classes; renderConfigurables(); break;
      case 'graphs': st.graphs = m.series; if (st.rightTab === 'graph') renderGraph(); break;
      case 'config': $('configText').value = m.json; $('configFile').textContent = m.file; break;
    }
  }

  let lastWarn = '';
  function onState(m) {
    const op = m.opmode || {};
    $('timerRemaining').textContent = op.timerRemaining != null ? Math.ceil(op.timerRemaining) + 's' : '';
    const warn = [op.warning, op.globalError && op.state !== 'ESTOP' ? op.globalError : ''].filter(Boolean).join(' · ');
    if (warn !== lastWarn) {
      lastWarn = warn;
      const box = $('warnBox');
      box.classList.toggle('hidden', !warn);
      box.textContent = warn;
    }
    if (m.battery) $('batteryPill').textContent = `${m.battery.volts.toFixed(2)} V · ${m.battery.current.toFixed(1)} A`;
    const sim = m.sim || {};
    $('simPill').textContent = sim.paused ? (sim.stepping ? 'stepping' : 'paused') : Math.round(sim.stepRate || 0) + ' Hz';
    $('pauseBtn').textContent = sim.paused ? '▶ Resume' : '⏸ Pause';
    const io = sim.io || {};
    const ioText = io.enabled === false ? 'I/O instant'
      : `I/O ${(io.totalMs || 0).toFixed(0)} ms · ${io.bulkReads || 0} bulk · ${io.writes || 0} w · ${io.i2c || 0} i2c`;
    if ($('ioPill').textContent !== ioText) {
      $('ioPill').textContent = ioText;
      $('ioPill').classList.toggle('muted', io.enabled === false);
    }
    const r = m.robot;
    if (r) {
      const pedro = $('frameSelect').value === 'pedro';
      const pd = pedro ? toPedro(r.x, r.y, r.headingDeg) : null;
      showPose(pedro ? pd.x : r.x, pedro ? pd.y : r.y, pedro ? pd.h : r.headingDeg);
    }
    if (st.rightTab === 'hardware') renderDevices(m.devices);
  }

  // seed the pose inputs from the live robot the first time the frame is picked
  $('frameSelect').onfocus = () => { if (!$('poseX').value) $('frameSelect').onchange(); };

  return { onMessage, onState, renderGraph, renderDevices };
}
