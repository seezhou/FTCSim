// FTCSim 3D — entry point.
// Connects to the simulator's existing WebSocket (/ws), builds the field from the
// "hello"/"field" messages and animates the robot from the 20 Hz "state" messages.
// Read-only viewer: no backend changes, no DS controls in v1 (field option selects
// reuse the existing sim.field message).
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { createField } from './field.js';
import { Robot } from './robot.js';
import { startGamepads } from './gamepad.js';
import { startRobotDrag } from './drag.js';
import { createOverlay } from './overlay.js';
import { createBalls } from './balls.js';
import { createPanels } from './panels.js';
import { createWebcam, parseMount, parseDetections } from './webcam.js';

const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------- renderer / scene
const canvas = $('scene');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x0e1116);
scene.fog = new THREE.Fog(0x0e1116, 320, 700);

const camera = new THREE.PerspectiveCamera(50, 1, 0.5, 2000);
camera.position.set(130, 150, 150);

const controls = new OrbitControls(camera, canvas);
controls.target.set(0, 0, 0);
controls.maxPolarAngle = Math.PI / 2.05;
controls.maxDistance = 500;
controls.minDistance = 20;
controls.enableDamping = true;
controls.dampingFactor = 0.08;

// lights
scene.add(new THREE.HemisphereLight(0xcdd6e4, 0x22262e, 1.1));
const sun = new THREE.DirectionalLight(0xffffff, 1.6);
sun.position.set(120, 220, 90);
sun.castShadow = true;
sun.shadow.mapSize.set(2048, 2048);
sun.shadow.camera.left = sun.shadow.camera.bottom = -110;
sun.shadow.camera.right = sun.shadow.camera.top = 110;
sun.shadow.camera.far = 600;
sun.shadow.bias = -0.0004;
scene.add(sun);

function resize() {
  const w = innerWidth, h = innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
addEventListener('resize', resize);
resize();

// ---------------------------------------------------------------- world pieces
const field = createField(scene);
const robot = new Robot(scene);
// FTC Dashboard / Panels field overlays (Road Runner's planned path, etc.)
const overlay = createOverlay(scene);
// Balls the robot launched. The backend owns their physics; this only draws where they are.
const balls = createBalls(scene);

// odometry trail
const TRAIL_MAX = 3000;
const trailPos = new Float32Array(TRAIL_MAX * 3);
let trailLen = 0;
const trailGeom = new THREE.BufferGeometry();
trailGeom.setAttribute('position', new THREE.BufferAttribute(trailPos, 3));
trailGeom.setDrawRange(0, 0);
const trailLine = new THREE.Line(trailGeom, new THREE.LineBasicMaterial({ color: 0x8fb8ff, transparent: true, opacity: 0.85 }));
trailLine.frustumCulled = false;
trailLine.visible = false;
scene.add(trailLine);

let lastTrailPoint = null;
function pushTrail(p) {
  if (lastTrailPoint && p.distanceTo(lastTrailPoint) < 1.2) return;
  if (trailLen >= TRAIL_MAX) { // shift by half
    trailPos.copyWithin(0, (TRAIL_MAX / 2) * 3, trailLen * 3);
    trailLen = TRAIL_MAX / 2;
  }
  trailPos[trailLen * 3] = p.x; trailPos[trailLen * 3 + 1] = 0.3; trailPos[trailLen * 3 + 2] = p.z;
  trailLen++;
  trailGeom.setDrawRange(0, trailLen);
  trailGeom.attributes.position.needsUpdate = true;
  lastTrailPoint = p.clone();
}
function clearTrail() { trailLen = 0; trailGeom.setDrawRange(0, 0); lastTrailPoint = null; }
robot.onEpochChange = clearTrail;

// ---------------------------------------------------------------- websocket
let ws = null;
let fieldName = '';
let camMode = 'orbit';

function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws`);
  ws.onopen = () => { $('connPill').textContent = 'connected'; $('connPill').className = 'pill ok'; send({ type: 'hello' }); };
  ws.onclose = () => { $('connPill').textContent = 'offline'; $('connPill').className = 'pill bad'; setTimeout(connect, 1000); };
  ws.onmessage = (ev) => { try { handle(JSON.parse(ev.data)); } catch (e) { console.error(e); } };
}
function send(obj) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); }

function handle(m) {
  panels.onMessage(m);
  switch (m.type) {
    case 'hello':
      fieldName = m.field?.name || '';
      $('fieldPill').textContent = `${fieldName} · ${m.configName || ''}`;
      field.rebuild(m.field);
      robot.setSpec({ ...(m.robot || {}), startPose: m.startPose });
      renderBioBuzz(m.field);
      $('camHint').classList.add('hidden');
      clearTrail();
      balls.clear();
      break;
    case 'field':
      fieldName = m.field?.name || '';
      $('fieldPill').textContent = fieldName;
      field.rebuild(m.field);
      renderBioBuzz(m.field);
      break;
    case 'state':
      if (m.robot) robot.setState(m.robot);
      syncCameraTeam(m.devices);
      balls.setState(m.balls);
      updateLauncher(m.launcher);
      overlay.setDrawings(m.drawings);
      syncOverlayLifecycle(m.opmode);
      updateHud(m);
      break;
    case 'opmodes':
      renderOpModes(m.list || []);
      break;
    case 'buildStatus':
      break;
    case 'gamepad.rumble': gamepads.applyRumble(m); break;
    case 'gamepad.rumble.stop': gamepads.stopAllRumble(); break;
    case 'ball.score': onBallScore(m); break;
    case 'hive.tip': onHiveTip(m); break;
    case 'toast': toast(m.level, m.text); break;
  }
}

// ---------------------------------------------------------------- driver station
let opmodes = [];
function renderOpModes(list) {
  opmodes = list;
  const sel = $('opmodeSelect');
  const prev = sel.value;
  sel.innerHTML = '';
  const groups = { TELEOP: {}, AUTONOMOUS: {} };
  for (const o of list) {
    if (o.disabled) continue;
    (groups[o.flavor][o.group || ''] ||= []).push(o);
  }
  for (const flavor of ['TELEOP', 'AUTONOMOUS']) {
    for (const g of Object.keys(groups[flavor]).sort()) {
      const og = document.createElement('optgroup');
      og.label = (flavor === 'TELEOP' ? 'TeleOp' : 'Autonomous') + (g ? ' · ' + g : '');
      for (const o of groups[flavor][g].sort((a, b) => a.name.localeCompare(b.name))) {
        const opt = document.createElement('option');
        opt.value = o.flavor + '|' + o.name;
        opt.textContent = o.name;
        og.appendChild(opt);
      }
      sel.appendChild(og);
    }
  }
  if (!sel.options.length) {
    const opt = document.createElement('option');
    opt.textContent = '(no OpModes found yet)';
    sel.appendChild(opt);
  } else if (prev) sel.value = prev;
}

function selectedOpMode() {
  const v = $('opmodeSelect').value;
  if (!v.includes('|')) return null;
  const [flavor, name] = v.split('|');
  return { flavor, name };
}

$('initBtn').onclick = () => {
  const o = selectedOpMode();
  if (o) send({ type: 'opmode.init', name: o.name, flavor: o.flavor });
};
$('startBtn').onclick = () => send({ type: 'opmode.start' });
$('stopBtn').onclick = () => send({ type: 'opmode.stop' });
$('restartBtn').onclick = () => { overlay.clear(); send({ type: 'robot.restart' }); };
$('opmodeSelect').ondblclick = () => $('initBtn').click();
$('timerChk').onchange = (e) => send({ type: 'sim.timer', enabled: e.target.checked });

let lastErrorText = '';
function updateDsPanel(op) {
  const st = op.state || 'IDLE';
  $('initBtn').disabled = st !== 'IDLE';
  $('startBtn').disabled = st !== 'INIT';
  $('stopBtn').disabled = !(st === 'INIT' || st === 'RUNNING');
  $('restartBtn').classList.toggle('hidden', st !== 'ESTOP');
  const err = op.error || '';
  if (err !== lastErrorText) {
    lastErrorText = err;
    const box = $('errorBox');
    box.classList.toggle('hidden', !err);
    box.textContent = err;
    if (err) toast('error', 'OpMode error — see the panel for the stack trace');
  }
}

// ------------------------------------------------------------------ launcher
// The launch angle belongs to the launcher, not to the camera. The configuration decides where it
// starts; once the slider is touched, it wins. Firing is left to the team's own code — the
// simulator infers a shot from the devices it drives — so there is no fire button here.
let launchPitchTouched = false;
$('launchPitch').oninput = () => {
  launchPitchTouched = true;
  const deg = +$('launchPitch').value;
  $('launchPitchVal').textContent = deg + '°';
  send({ type: 'sim.launcherPitch', deg });
};

// The magazine is the robot's, not the UI's: the backend counts the balls (one per shot) and
// tells us how many are left until it reloads in the reload zone.
function updateLauncher(l) {
  if (!l) return;
  $('launcherInline').classList.toggle('hidden', !l.enabled);
  if (!l.enabled) { robot.setAmmo(0, 0); return; }
  // The magazine is now drawn as balls inside the robot (robot.setAmmo); the text pill is redundant.
  $('ammoPill').classList.add('hidden');
  robot.setAmmo(l.remaining, l.capacity, l.ballRadiusIn);
  // Follow the backend's angle until the slider is used, so the control opens on the configured one.
  if (!launchPitchTouched && l.pitchDeg != null) {
    $('launchPitch').value = String(Math.round(l.pitchDeg));
    $('launchPitchVal').textContent = Math.round(l.pitchDeg) + '°';
  }
}

function onBallScore(m) {
  toast('info', `${m.goal}: ${m.total} scored`);
}

// A HIVE tip, not the balls, is what scores on BIOBUZZ: the balls only ever show up as
// "in CELL" in the launcher row (see updateLauncher).
function onHiveTip(m) {
  toast('info', `${m.alliance} HIVE tipped — +${m.points} pts`);
}

// ------------------------------------------------- FTC Dashboard / Panels overlay
// The drawings layer is sticky (see overlay.js): it no longer expires on its own,
// because the backend stops broadcasting a drawing 3 s after the team code stops
// drawing it — which used to make Road Runner's planned path blink out mid-match.
// So we clear it on our own triggers instead: loading a new OpMode, the Clear
// button, and the robot being restarted.
let lastOpState = 'IDLE';
function syncOverlayLifecycle(op) {
  const st = (op && op.state) || 'IDLE';
  if (st === 'INIT' && lastOpState !== 'INIT') overlay.clear();
  lastOpState = st;
}
$('overlayClearBtn').onclick = () => { overlay.clear(); toast('info', 'Field drawings cleared'); };

// ---------------------------------------------------------------- HUD
function updateHud(m) {
  const op = m.opmode || {};
  const st = op.state || 'IDLE';
  $('statePill').textContent = st === 'RUNNING' && op.name ? `${op.name}` : st;
  $('statePill').className = 'pill wide ' + (st === 'RUNNING' ? 'ok' : st === 'ERROR' ? 'bad' : st === 'INIT' ? 'warn' : '');
  $('runtime').textContent = `${(op.runtime || 0).toFixed(1)} s`;
  $('pausedPill').classList.toggle('hidden', !(m.sim && m.sim.paused));
  updateDsPanel(op);
  renderTelemetry(m);
}

// ---------------------------------------------------- telemetry / panels / dashboard
// All three line buffers already ride along in the 20 Hz "state" message:
//   m.telemetry  → Driver Station telemetry (Telemetry.addData / update)
//   m.panels     → Panels (com.bylazar) telemetry
//   m.dashboard  → FTC Dashboard telemetry
let telTab = 'ds';
const telTabButtons = document.querySelectorAll('#telTabs button');
telTabButtons.forEach((b) => {
  b.onclick = () => {
    telTabButtons.forEach((x) => x.classList.remove('active'));
    b.classList.add('active');
    telTab = b.dataset.tab;
    lastTelKey = '';
  };
});

let lastTelKey = '';
function renderTelemetry(m) {
  const src = telTab === 'ds' ? m.telemetry : telTab === 'panels' ? m.panels : m.dashboard;
  const lines = (src && src.lines) || [];
  const txt = lines.join('\n');
  const key = txt + '\u0000' + lines.length;
  if (key === lastTelKey) return;
  lastTelKey = key;
  const pre = $('telLines');
  const pinned = pre.scrollHeight - pre.scrollTop - pre.clientHeight < 28;
  pre.textContent = txt || (telTab === 'ds'
    ? '(no telemetry — run an OpMode and call telemetry.addData + telemetry.update)'
    : `(no ${telTab} output — the team code must use Panels/FTC Dashboard)`);
  if (pinned) pre.scrollTop = pre.scrollHeight;
  $('telCount').textContent = lines.length + ' lines';
  $('telCount').className = 'pill' + (lines.length ? ' ok' : '');
}

function renderBioBuzz(f) {
  const host = $('bioBuzzOptions');
  const opts = f?.options || [];
  const key = JSON.stringify(opts.map(o => o.key)) + fieldName;
  if (host.dataset.key === key) {
    const st = f?.state || {};
    for (const sel of host.querySelectorAll('select'))
      if (st[sel.dataset.key] !== undefined && document.activeElement !== sel) sel.value = String(st[sel.dataset.key]);
    return;
  }
  host.dataset.key = key;
  host.innerHTML = '';
  for (const o of opts) {
    const label = document.createElement('label');
    label.textContent = o.label + ' ';
    const sel = document.createElement('select');
    sel.dataset.key = o.key;
    o.choices.forEach((c, i) => {
      const opt = document.createElement('option');
      opt.value = c; opt.textContent = o.choiceLabels?.[i] || c;
      sel.appendChild(opt);
    });
    sel.onchange = (e) => {
      if (o.key === 'obelisk') send({ type: 'sim.obelisk', id: parseInt(e.target.value, 10) });
      send({ type: 'sim.field', key: o.key, value: e.target.value });
    };
    label.appendChild(sel);
    host.appendChild(label);
  }
  const st = f?.state || {};
  for (const sel of host.querySelectorAll('select'))
    if (st[sel.dataset.key] !== undefined) sel.value = String(st[sel.dataset.key]);
}

function toast(level, text) {
  const d = document.createElement('div');
  d.className = 'toast ' + (level || 'info');
  d.textContent = text;
  $('toasts').appendChild(d);
  setTimeout(() => d.remove(), 6000);
}

// ---------------------------------------------------------------- camera modes
function setCamMode(mode) {
  camMode = mode;
  $('camSelect').value = mode;
  if (mode === 'top') {
    camera.position.set(0.01, 260, 0.01);
    controls.target.set(0, 0, 0);
    controls.enabled = true;
  } else if (mode === 'orbit') {
    controls.enabled = true;
  } else {
    controls.enabled = false;
  }
}
$('camSelect').onchange = (e) => setCamMode(e.target.value);

$('trailBtn').onclick = (e) => {
  const on = e.currentTarget.classList.toggle('active');
  if (!on) clearTrail();
  trailLine.visible = on;
};
$('labelsBtn').onclick = (e) => { const on = e.currentTarget.classList.toggle('active'); field.setLabelsVisible(on); };
$('overlayBtn').onclick = (e) => { const on = e.currentTarget.classList.toggle('active'); overlay.setVisible(on); };
// per-panel collapse buttons (the right panel has its own #rightToggle)
for (const btn of document.querySelectorAll('button.collapse-btn')) {
  const p = btn.closest('.panel');
  btn.onclick = () => {
    const collapsed = p.classList.toggle('collapsed');
    btn.textContent = collapsed ? '+' : '–';
    // remember any user-resized inline size while collapsed, so a collapsed panel
    // shrinks to its title bar and pops back to the same size on expand
    if (collapsed) {
      p.dataset.savedW = p.style.width; p.dataset.savedH = p.style.height;
      p.style.width = ''; p.style.height = '';
    } else {
      p.style.width = p.dataset.savedW || ''; p.style.height = p.dataset.savedH || '';
    }
    syncCamPanelHeight();   // the camera panel's height feeds --camsz for #dsPanel
  };
}

// the header is pinned to the very top and carries all the summary info; publish its
// height as --hdr so the floating panels always start below it, however it wraps
const topBarEl = $('topBar');
const syncHeaderHeight = () => {
  document.documentElement.style.setProperty('--hdr', Math.round(topBarEl.getBoundingClientRect().height) + 'px');
};
syncHeaderHeight();
addEventListener('resize', syncHeaderHeight);
if (window.ResizeObserver) new ResizeObserver(syncHeaderHeight).observe(topBarEl);

// ------------------------------------------------- robot camera (picture-in-picture)
// A second PerspectiveCamera rides on the robot's camPivot; webcam.js renders the same
// scene through it into #camCanvas. The panel publishes its height as --camsz so the
// Driver Station panel above it yields exactly the right amount of room.
const camPanel = $('camPanel');
const webcam = createWebcam({ scene, robot, canvas: $('camCanvas'), overlay: $('camOverlay') });

// ---- the pod doubles as the team code's Webcam ---------------------------------
// The simulator already models the team's camera: the WebcamName device card carries its
// mount and the VisionPortal card lists what the AprilTag processor detected. When both
// are there we place the pod exactly on that mount and box the detections on the view, so
// the panel shows what the program sees rather than an approximation. Moving the pitch
// slider breaks that link (the backend keeps using the team's own mount), so the overlay
// stands down and the status line says why.
let camMount = null, camSynced = true;
let lastDevices = [];

function syncCamPanelHeight() {
  // a collapsed camera panel still shows its title bar, so it must keep reserving that
  // strip -- only a fully hidden panel gives its room back
  const h = camPanel.classList.contains('hidden') ? 0 : camPanel.getBoundingClientRect().height;
  document.documentElement.style.setProperty('--camsz', Math.round(h + 8) + 'px');
  if (camPanel.classList.contains('hidden')) webcam.resize();
}
if (window.ResizeObserver) new ResizeObserver(syncCamPanelHeight).observe(camPanel);
addEventListener('resize', syncCamPanelHeight);

const camPitch = $('camPitch');
function applyCamMount(m) {
  camMount = m;
  if (!m) return;
  camPitch.value = String(Math.max(-30, Math.min(80, Math.round(m.pitchDeg))));
  webcam.applyMount(m);
  camSynced = true;
  applyCamPitch();
}

function applyCamPitch() {
  const deg = +camPitch.value;
  robot.setCamPitch(deg);
  $('camPitchVal').textContent = (deg > 0 ? '+' : '') + deg + '°';
  // dragging away from the team's mount is fine, it just is not the team's camera any more
  if (camMount && Math.abs(deg - Math.round(camMount.pitchDeg)) > 0.5) camSynced = false;
  renderCamStatus();
}

function renderCamStatus() {
  const el = $('camStatus');
  if (!camMount) {
    el.className = 'camstatus';
    el.textContent = 'no team Webcam found — free camera';
    return;
  }
  const found = parseDetections(lastDevices);
  const list = found.length ? found.map(d => d.label + (d.pct != null ? ` ${d.pct}%` : '')).join(' ') : 'none';
  const boxed = found.length ? ` · ${webcam.drawn}/${found.length} boxed` : '';
  if (camSynced) {
    el.className = 'camstatus live';
    el.textContent = `${camMount.name} · pitch ${Math.round(camMount.pitchDeg)}° · fov ${Math.round(camMount.hfovDeg)}° · tags: ${list}${boxed}`;
  } else {
    el.className = 'camstatus free';
    el.textContent = `free look — team cam stays at ${Math.round(camMount.pitchDeg)}° · its tags: ${list}`;
  }
}

camPitch.oninput = applyCamPitch;
$('camSyncBtn').onclick = () => { if (camMount) applyCamMount(camMount); else toast('info', 'No team Webcam in the robot configuration yet'); };
applyCamPitch();

// short viewports cannot spare the room: start with the camera collapsed to its title
if (innerHeight < 620 || innerWidth < 900) camPanel.classList.add('collapsed');
$('camHint').classList.remove('hidden');
syncCamPanelHeight();

// Adopt the team code's camera as soon as the device list arrives, and keep the overlay
// fed with whatever the AprilTag processor currently sees.
let camMountSig = '';
function syncCameraTeam(devices) {
  if (!devices) return;
  lastDevices = devices;
  // Declared motor/servo mounts ride on the same message: the set may change when the config is
  // reloaded, the live values change every frame. Parts without a mount are never drawn.
  robot.setDevices(devices);
  robot.setDeviceValues(devices);
  const m = parseMount(devices);
  const sig = m ? JSON.stringify(m) : '';
  if (sig !== camMountSig) {
    camMountSig = sig;
    if (m) applyCamMount(m); else { camMount = null; renderCamStatus(); }
  }
  const key = JSON.stringify((devices.find(d => /visionportal/i.test(d.type || '')) || {}).values?.processors || []);
  if (key !== lastDetSig) { lastDetSig = key; renderCamStatus(); }
  webcam.setDetections(parseDetections(devices), camSynced);
}
let lastDetSig = '';

const chasePos = new THREE.Vector3(60, 45, 60);
function updateChase(dt) {
  const p = robot.position();
  const h = robot.heading();
  // sit behind and above the robot, looking along its heading
  const behind = new THREE.Vector3(-Math.cos(h) * 48, 30, Math.sin(h) * 48);
  const desired = p.clone().add(behind);
  chasePos.lerp(desired, 1 - Math.exp(-3.5 * dt));
  camera.position.copy(chasePos);
  camera.lookAt(p.x, 8, p.z);
}

// ---------------------------------------------------------------- main loop
const clock = new THREE.Clock();
// render FPS readout in the header
let fpsFrames = 0, fpsSince = performance.now();
let camFrames = 0, camSince = performance.now();

function animate() {
  requestAnimationFrame(animate);
  const dt = Math.min(clock.getDelta(), 0.1);
  const now = performance.now();
  robot.update(dt);
  balls.update(dt);
  field.update(dt);
  if (trailLine.visible) pushTrail(robot.position());
  if (camMode === 'chase') updateChase(dt);
  else controls.update();

  // the robot's own view, rendered from a camera riding on the robot
  if (!camPanel.classList.contains('hidden') && !camPanel.classList.contains('collapsed')) {
    webcam.render();
    camFrames++;
    if (now - camSince >= 1000) {
      $('camFps').textContent = Math.round((camFrames * 1000) / (now - camSince)) + ' fps';
      camFrames = 0; camSince = now;
      renderCamStatus();   // refresh the "n/m boxed" count
    }
  }

  fpsFrames++;
  if (now - fpsSince >= 500) {
    $('fpsPill').textContent = Math.round((fpsFrames * 1000) / (now - fpsSince)) + ' fps';
    fpsFrames = 0; fpsSince = now;
  }

  renderer.render(scene, camera);
}

const gamepads = startGamepads({ send, toast, host1: $('gp1'), host2: $('gp2') });
const panels = createPanels({ send, toast });
startRobotDrag({ canvas, camera, robot, send });
// debug/testing handle (harmless in production)
window.__sim3d = { canvas, camera, controls, robot, overlay, field, panels, webcam, balls, scene };

connect();
animate();
