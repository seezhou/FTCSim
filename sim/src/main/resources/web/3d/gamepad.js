// Gamepad / keyboard driving + rumble for the FTCSim 3D page.
// Mirrors the 2D frontend's behaviour (app.js) against the same backend messages:
//   - keyboard mapping for gamepad 1 (WASD / arrows / Z X C V / 1 2 / Q E / T F G H …)
//   - physical controllers through the browser Gamepad API, assigned DS-style
//     (press Start+A → gamepad 1, Start+B → gamepad 2)
//   - state sent as {type:'gamepad', id, state} at ~30 Hz with a periodic keepalive
//   - gamepad.rumble / gamepad.rumble.stop messages replayed on the assigned controller

const BUTTONS = [['a', 'A'], ['b', 'B'], ['x', 'X'], ['y', 'Y'], ['lb', 'LB'], ['rb', 'RB'],
  ['du', '▲'], ['dd', '▼'], ['dl', '◀'], ['dr', '▶'], ['back', 'back'], ['start', 'start'],
  ['guide', 'guide'], ['ls', 'L3'], ['rs', 'R3'], ['touchpad', 'pad']];
const KEYMAP = { KeyZ: 'a', KeyX: 'b', KeyC: 'x', KeyV: 'y', Digit1: 'lb', Digit2: 'rb',
  KeyT: 'du', KeyG: 'dd', KeyF: 'dl', KeyH: 'dr', KeyR: 'start', Backspace: 'back', Tab: 'guide',
  Digit3: 'ls', Digit4: 'rs' };
const RUMBLE_CONTINUOUS_MS = 10 * 60 * 1000;

export class Pad {
  constructor(id, send) {
    this.id = id;
    this.send = send;
    this.keys = new Set();
    this.s = { lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0, type: 'xbox' };
    for (const [k] of BUTTONS) this.s[k] = false;
    this.lastSent = '';
    this.physicalIndex = null;
    this.sourceEl = null;       // set by the UI
    this.rumbleEl = null;
  }

  applyPhysical(gp) {
    const dz = (v) => Math.abs(v) < 0.08 ? 0 : v;
    const b = (i) => !!(gp.buttons[i] && gp.buttons[i].pressed);
    this.s.lx = dz(gp.axes[0] || 0); this.s.ly = dz(gp.axes[1] || 0);
    this.s.rx = dz(gp.axes[2] || 0); this.s.ry = dz(gp.axes[3] || 0);
    this.s.a = b(0); this.s.b = b(1); this.s.x = b(2); this.s.y = b(3);
    this.s.lb = b(4); this.s.rb = b(5);
    this.s.lt = gp.buttons[6] ? gp.buttons[6].value : 0;
    this.s.rt = gp.buttons[7] ? gp.buttons[7].value : 0;
    this.s.back = b(8); this.s.start = b(9); this.s.ls = b(10); this.s.rs = b(11);
    this.s.du = b(12); this.s.dd = b(13); this.s.dl = b(14); this.s.dr = b(15); this.s.guide = b(16);
    this.s.type = /playstation|dualshock|dualsense|054c/i.test(gp.id) ? 'ps4' : 'xbox';
  }

  applyKeyboard() {
    const k = this.keys;
    const ax = (neg, pos) => (k.has(pos) ? 1 : 0) - (k.has(neg) ? 1 : 0);
    this.s.lx = ax('KeyA', 'KeyD'); this.s.ly = ax('KeyW', 'KeyS');
    this.s.rx = ax('ArrowLeft', 'ArrowRight'); this.s.ry = ax('ArrowUp', 'ArrowDown');
    this.s.lt = k.has('KeyQ') ? 1 : 0; this.s.rt = k.has('KeyE') ? 1 : 0;
    for (const [code, btn] of Object.entries(KEYMAP)) this.s[btn] = k.has(code);
  }

  sendIfNeeded(force) {
    const json = JSON.stringify(this.s);
    if (force || json !== this.lastSent) {
      this.lastSent = json;
      this.send({ type: 'gamepad', id: this.id, state: this.s });
    }
  }
}

const esc = (s) => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export function startGamepads({ send, toast, host1, host2 }) {
  const pads = { 1: new Pad(1, send), 2: new Pad(2, send) };
  const hosts = { 1: host1, 2: host2 };
  const rumble = { 1: { timers: [], untilTimer: 0 }, 2: { timers: [], untilTimer: 0 } };
  const views = {};

  // -------------------------------------------------------------- virtual pads
  // Same widget as the 2D front end (web/app.js VirtualPad): two sticks, the button
  // grid and the two triggers, all live-off the Pad state this module already keeps.
  // It doubles as an input device — dragging a stick / pressing a button writes into
  // the same state that gets sent to the robot.
  function buildPad(id) {
    const p = pads[id];
    const el = hosts[id];
    el.innerHTML = `
      <div class="title">GP${id}
        <span class="rumbleInd" title="the robot is rumbling this gamepad">rumble</span>
        <span class="spacer"></span>
        <select class="gpphys"><option value="">no controller</option></select>
      </div>
      <div class="src"></div>
      <div class="stick" data-stick="l"><div class="knob"></div><div class="lab">left stick</div></div>
      <div class="mid"></div>
      <div class="stick" data-stick="r"><div class="knob"></div><div class="lab">right stick</div></div>`;
    const src = el.querySelector('.src');
    src.textContent = id === 1 ? 'keyboard: WASD / arrows / Z X C V / 1 2 / Q E / T F G H' : 'physical controller: press Start+B';
    const rumbleEl = el.querySelector('.rumbleInd');
    const selectEl = el.querySelector('.gpphys');
    selectEl.onchange = () => { p.physicalIndex = selectEl.value === '' ? null : parseInt(selectEl.value, 10); };
    p.sourceEl = src; p.rumbleEl = rumbleEl; p.selectEl = selectEl;

    const mid = el.querySelector('.mid');
    for (const [k, label] of BUTTONS) {
      const b = document.createElement('div');
      b.className = 'btn'; b.dataset.key = k; b.textContent = label;
      b.onpointerdown = (e) => { e.preventDefault(); b.setPointerCapture(e.pointerId); p.s[k] = true; renderPad(id); };
      b.onpointerup = () => { p.s[k] = false; renderPad(id); };
      b.onpointercancel = b.onpointerup;
      mid.appendChild(b);
    }
    for (const t of ['lt', 'rt']) {
      const l = document.createElement('label');
      l.className = 'trig';
      l.innerHTML = `${t.toUpperCase()} <input type="range" min="0" max="1" step="0.01" value="0">`;
      const inp = l.querySelector('input');
      inp.oninput = () => { p.s[t] = parseFloat(inp.value); };
      inp.onpointerup = () => { inp.value = 0; p.s[t] = 0; };
      mid.appendChild(l);
    }

    const sticks = {};
    el.querySelectorAll('.stick').forEach((stickEl) => {
      const which = stickEl.dataset.stick;
      sticks[which] = stickEl;
      const move = (e) => {
        const r = stickEl.getBoundingClientRect();
        let x = (e.clientX - r.left - r.width / 2) / (r.width / 2 - 13);
        let y = (e.clientY - r.top - r.height / 2) / (r.height / 2 - 13);
        const m = Math.hypot(x, y);
        if (m > 1) { x /= m; y /= m; }
        p.s[which + 'x'] = x; p.s[which + 'y'] = y;
        renderPad(id);
      };
      stickEl.onpointerdown = (e) => { e.preventDefault(); stickEl.setPointerCapture(e.pointerId); stickEl.classList.add('active'); move(e); };
      stickEl.onpointermove = (e) => { if (stickEl.classList.contains('active')) move(e); };
      stickEl.onpointerup = stickEl.onpointercancel = () => { stickEl.classList.remove('active'); p.s[which + 'x'] = 0; p.s[which + 'y'] = 0; renderPad(id); };
    });
    views[id] = { el, sticks };
  }

  function renderPad(id) {
    const p = pads[id], v = views[id];
    if (!v) return;
    v.el.querySelectorAll('.btn').forEach((b) => b.classList.toggle('on', !!p.s[b.dataset.key]));
    for (const [which, stickEl] of Object.entries(v.sticks)) {
      const knob = stickEl.querySelector('.knob');
      const r = stickEl.clientWidth / 2 - 13;
      knob.style.left = (stickEl.clientWidth / 2 - 13 + p.s[which + 'x'] * r) + 'px';
      knob.style.top = (stickEl.clientHeight / 2 - 13 + p.s[which + 'y'] * r) + 'px';
    }
    v.el.querySelectorAll('.trig input').forEach((inp, i) => {
      const val = p.s[i === 0 ? 'lt' : 'rt'];
      if (document.activeElement !== inp) inp.value = val;
    });
  }
  buildPad(1); buildPad(2);

  const physicalGamepad = (id) => {
    const gps = navigator.getGamepads ? Array.from(navigator.getGamepads()).filter(Boolean) : [];
    return pads[id].physicalIndex == null ? null : (gps.find(g => g.index === pads[id].physicalIndex) || null);
  };

  // -------------------------------------------------------------- rumble replay
  const rumbleActuator = (id) => {
    const gp = physicalGamepad(id); if (!gp) return null;
    const act = gp.vibrationActuator || (gp.hapticActuators && gp.hapticActuators[0]);
    return act && typeof act.playEffect === 'function' ? act : null;
  };
  const playRumbleStep = (act, step) => {
    if (!act) return;
    const duration = step.duration < 0 ? RUMBLE_CONTINUOUS_MS : Math.max(0, step.duration | 0);
    const mag = (v) => Math.min(1, Math.max(0, (v | 0) / 255));
    try { act.playEffect('dual-rumble', { startDelay: 0, duration, weakMagnitude: mag(step.small), strongMagnitude: mag(step.large) }); } catch (e) { /* controller went away */ }
  };
  const resetActuator = (id) => {
    const act = rumbleActuator(id); if (!act) return;
    try { if (typeof act.reset === 'function') act.reset(); else act.playEffect('dual-rumble', { duration: 0, weakMagnitude: 0, strongMagnitude: 0 }); } catch (e) {}
  };
  const rumbleIndicator = (id, on) => {
    if (views[id]) views[id].el.classList.toggle('rumbling', on);
    if (pads[id].rumbleEl) pads[id].rumbleEl.classList.toggle('hidden', false);
  };
  const cancelRumbleTimers = (id) => {
    const st = rumble[id];
    for (const t of st.timers) clearTimeout(t);
    st.timers = []; clearTimeout(st.untilTimer); st.untilTimer = 0;
  };
  function applyRumble(m) {
    const id = m.id === 2 ? 2 : 1;
    const steps = Array.isArray(m.steps) ? m.steps : [];
    cancelRumbleTimers(id);
    let power = 0;
    for (const s of steps) power += Math.abs(s.large | 0) + Math.abs(s.small | 0);
    if (!steps.length || power === 0) { resetActuator(id); rumbleIndicator(id, false); return; }
    const act = rumbleActuator(id), st = rumble[id];
    let at = 0, duration = 0;
    for (const step of steps) {
      const delay = at;
      st.timers.push(setTimeout(() => playRumbleStep(act, step), delay));
      if (step.duration < 0) { duration = RUMBLE_CONTINUOUS_MS; break; }
      at += Math.max(0, step.duration | 0); duration = at;
    }
    rumbleIndicator(id, true);
    if (duration < RUMBLE_CONTINUOUS_MS) st.untilTimer = setTimeout(() => rumbleIndicator(id, false), duration);
  }
  function stopAllRumble() {
    for (const id of [1, 2]) { cancelRumbleTimers(id); resetActuator(id); rumbleIndicator(id, false); }
  }

  // -------------------------------------------------------------- 30 Hz loop
  let keepalive = 0;
  function loop() {
    const gps = navigator.getGamepads ? Array.from(navigator.getGamepads()).filter(Boolean) : [];
    // keep the controller selectors in sync
    for (const id of [1, 2]) {
      const sel = pads[id].selectEl;
      const opts = ['<option value="">no controller</option>']
        .concat(gps.map(g => `<option value="${g.index}">${esc(g.id.slice(0, 32))}</option>`)).join('');
      if (sel.dataset.opts !== opts) {
        sel.dataset.opts = opts; sel.innerHTML = opts;
        sel.value = pads[id].physicalIndex == null ? '' : String(pads[id].physicalIndex);
      }
    }
    // Driver Station style assignment: Start+A → gamepad 1, Start+B → gamepad 2
    for (const g of gps) {
      const start = g.buttons[9] && g.buttons[9].pressed;
      if (start && g.buttons[0] && g.buttons[0].pressed && pads[1].physicalIndex !== g.index) {
        pads[1].physicalIndex = g.index;
        if (pads[2].physicalIndex === g.index) pads[2].physicalIndex = null;
        toast('info', `${g.id.slice(0, 40)} → gamepad 1`);
      }
      if (start && g.buttons[1] && g.buttons[1].pressed && pads[2].physicalIndex !== g.index) {
        pads[2].physicalIndex = g.index;
        if (pads[1].physicalIndex === g.index) pads[1].physicalIndex = null;
        toast('info', `${g.id.slice(0, 40)} → gamepad 2`);
      }
    }
    for (const id of [1, 2]) {
      const p = pads[id];
      const phys = p.physicalIndex != null ? gps.find(g => g.index === p.physicalIndex) : null;
      if (phys) {
        p.applyPhysical(phys);
        const src = 'physical: ' + phys.id.slice(0, 40);
        if (p.sourceEl.textContent !== src) p.sourceEl.textContent = src;
      } else if (id === 1 && p.keys.size) {
        p.applyKeyboard();
        const src = 'keyboard (WASD / arrows / buttons)';
        if (p.sourceEl.textContent !== src) p.sourceEl.textContent = src;
      } else {
        const src = id === 1 ? 'keyboard (idle)' : 'no input';
        if (p.sourceEl.textContent !== src) p.sourceEl.textContent = src;
      }
      p.sendIfNeeded(keepalive % 15 === 0);
      renderPad(id);
    }
    keepalive++;
    setTimeout(loop, 33);
  }
  loop();

  // -------------------------------------------------------------- keyboard
  window.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.tagName === 'SELECT') return;
    if (e.code === 'Tab') e.preventDefault();
    if (!pads[1].keys.has(e.code)) { pads[1].keys.add(e.code); pads[1].applyKeyboard(); }
  });
  window.addEventListener('keyup', (e) => { if (pads[1].keys.delete(e.code)) { pads[1].applyKeyboard(); renderPad(1); } });
  window.addEventListener('blur', () => { pads[1].keys.clear(); pads[1].applyKeyboard(); renderPad(1); });

  // debug hook (harmless in production)
  window.__gp = pads;
  return { pads, applyRumble, stopAllRumble };
}
