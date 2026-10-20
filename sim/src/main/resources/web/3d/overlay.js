// FTCSim 3D — FTC Dashboard / Panels field-overlay renderer.
//
// The 20 Hz "state" message carries `drawings` — the same field-overlay ops the 2D page
// paints on its canvas: Road Runner's planned path (green #4CAF50 polyline), the target
// robot outline, Pedro Pathing drawings, Panels shapes, etc.
//
// In 3D they are laid flat on the floor the same way the 2D page draws them, but as real
// geometry: strokes become thin ribbons (a plain THREE.Line is stuck at 1 px on WebGL, so
// a Road Runner path would be invisible from an orbit camera), fills become flat polygons,
// text becomes a flat label. Nothing here changes the backend — it just consumes the
// existing message.
//
// Op schema (see com.acmerobotics.dashboard.canvas.Canvas / com.bylazar.field.FieldManager):
//   state:  stroke{c} fill{c} strokeWidth{w} alpha{a}
//   shapes: line{x1,y1,x2,y2} polyline/polygon{xs,ys} rect{x,y,w,h} circle{x,y,r} text{t,x,y}
//   frame:  'ftc' (inches, field frame) or 'pedro' (converted below)
import * as THREE from 'three';
import { ftc } from './field.js';

const OVERLAY_Y = 0.45;      // above the floor (+ zones 0.06, odometry trail 0.3)
const MIN_W_IN = 1.2;        // ribbon width clamp, inches
const MAX_W_IN = 6;
const CIRCLE_SEGS = 48;

/** Pedro Pathing frame -> FTC field frame (same conversion as the 2D page). */
function fromPedro(px, py) { return [72 - py, px - 72]; }

/** '#rrggbb' | '#rrggbbaa' | 'transparent' -> { color, alpha } */
function parseColor(col, fallback = '#ffffff', fallbackAlpha = 1) {
  if (!col || col === 'transparent' || col === 'none') return { color: fallback, alpha: 0 };
  if (col[0] === '#' && col.length === 9) {
    return { color: col.slice(0, 7), alpha: parseInt(col.slice(7), 16) / 255 };
  }
  return { color: col, alpha: fallbackAlpha };
}

function strokeWidthIn(source, lw, opWidth) {
  const base = source === 'panels' && opWidth != null ? opWidth * 8 : (lw || 1) * 1.4;
  return Math.min(MAX_W_IN, Math.max(MIN_W_IN, base));
}

/** Ops -> { segments, fills, texts } in FTC inches. */
function translateOps(d) {
  const conv = d.frame === 'pedro' ? fromPedro : (x, y) => [x, y];
  const segments = [], fills = [], texts = [];
  let stroke = '#ffffff', fill = '#ffffff', lw = 1, alpha = 1;
  for (const op of d.ops || []) {
    switch (op.op) {
      case 'stroke': stroke = op.c; break;
      case 'fill': fill = op.c; break;
      case 'strokeWidth': lw = op.w || 1; break;
      case 'alpha': alpha = op.a != null ? op.a : 1; break;
      case 'line': {
        const a = parseColor(stroke), w = strokeWidthIn(d.source, lw);
        segments.push({ pts: [conv(op.x1, op.y1), conv(op.x2, op.y2)], color: a.color, alpha: a.alpha * alpha, w });
        break;
      }
      case 'polyline': {
        const c = parseColor(stroke), w = strokeWidthIn(d.source, lw);
        if ((op.xs || []).length > 1) segments.push({ pts: op.xs.map((x, i) => conv(x, op.ys[i])), color: c.color, alpha: c.alpha * alpha, w });
        break;
      }
      case 'polygon': {
        const pts = (op.xs || []).map((x, i) => conv(x, op.ys[i]));
        if (pts.length > 1) {
          if (op.stroke === false) { const c = parseColor(fill); fills.push({ pts, color: c.color, alpha: c.alpha * alpha }); }
          else { const c = parseColor(stroke), w = strokeWidthIn(d.source, lw); segments.push({ pts: [...pts, pts[0]], color: c.color, alpha: c.alpha * alpha, w }); }
        }
        break;
      }
      case 'rect': {
        const pts = [conv(op.x, op.y), conv(op.x + op.w, op.y), conv(op.x + op.w, op.y + op.h), conv(op.x, op.y + op.h)];
        if (op.stroke === false) { const c = parseColor(fill); fills.push({ pts, color: c.color, alpha: c.alpha * alpha }); }
        else { const c = parseColor(stroke), w = strokeWidthIn(d.source, lw); segments.push({ pts: [...pts, pts[0]], color: c.color, alpha: c.alpha * alpha, w }); }
        break;
      }
      case 'circle': {
        const ring = [];
        for (let i = 0; i <= CIRCLE_SEGS; i++) {
          const t = (i / CIRCLE_SEGS) * Math.PI * 2;
          ring.push(conv(op.x + Math.cos(t) * op.r, op.y + Math.sin(t) * op.r));
        }
        if (op.stroke === false) { const c = parseColor(fill); fills.push({ pts: ring.slice(0, CIRCLE_SEGS), color: c.color, alpha: c.alpha * alpha }); }
        else { const c = parseColor(stroke), w = strokeWidthIn(d.source, lw); segments.push({ pts: ring, color: c.color, alpha: c.alpha * alpha, w }); }
        break;
      }
      case 'text': {
        if (!op.t) break;
        const c = parseColor(op.stroke === false ? fill : stroke);
        texts.push({ t: op.t, color: c.color, alpha: c.alpha * alpha, pos: conv(op.x, op.y) });
        break;
      }
      // scale / rotation / translate / grid / image: not rendered (same as the 2D page)
    }
  }
  return { segments, fills, texts };
}

/** Flat ribbon geometry for one polyline (butt joints, two triangles per span). */
function ribbonPositions(pts, widthIn) {
  const out = [];
  const hw = widthIn / 2;
  for (let i = 0; i + 1 < pts.length; i++) {
    const a = pts[i], b = pts[i + 1];
    let dx = b[0] - a[0], dy = b[1] - a[1];
    const len = Math.hypot(dx, dy);
    if (len < 1e-6) continue;
    dx /= len; dy /= len;
    const nx = -dy * hw, ny = dx * hw;
    out.push(
      a[0] + nx, 0, -(a[1] + ny),
      a[0] - nx, 0, -(a[1] - ny),
      b[0] - nx, 0, -(b[1] - ny),
      a[0] + nx, 0, -(a[1] + ny),
      b[0] - nx, 0, -(b[1] - ny),
      b[0] + nx, 0, -(b[1] + ny));
  }
  return out;
}

function textTexture(text, color) {
  const c = document.createElement('canvas');
  const g = c.getContext('2d');
  const font = 'bold 40px system-ui';
  g.font = font;
  const w = Math.max(8, Math.ceil(g.measureText(text).width) + 12);
  c.width = w; c.height = 56;
  const g2 = c.getContext('2d');
  g2.font = font; g2.textAlign = 'center'; g2.textBaseline = 'middle';
  g2.fillStyle = color;
  g2.fillText(text, w / 2, 30);
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  return { tex, aspect: w / 56 };
}

/** Cheap change signature: enough samples that a moving path always differs. */
function signatureOf(d) {
  const parts = [d.source || '', d.frame || ''];
  for (const op of d.ops || []) {
    parts.push(op.op);
    for (const k of ['c', 'w', 'a', 'r', 'x', 'y', 'x1', 'y1', 'x2', 'y2', 'h', 'stroke']) {
      const v = op[k];
      if (v !== undefined) parts.push(typeof v === 'number' ? v.toFixed(2) : String(v));
    }
    for (const k of ['xs', 'ys']) {
      const arr = op[k];
      if (!arr || !arr.length) continue;
      parts.push('n' + arr.length);
      for (const i of [0, arr.length >> 2, arr.length >> 1, (arr.length * 3) >> 2, arr.length - 1]) {
        if (arr[i] !== undefined) parts.push(arr[i].toFixed(2));
      }
    }
  }
  return parts.join('|');
}

export function createOverlay(scene) {
  const group = new THREE.Group();
  group.name = 'overlay';
  scene.add(group);

  const sources = new Map();      // source -> { group, sig }
  let visible = true;
  let lastStats = { segments: 0, fills: 0, texts: 0 };

  function disposeGroup(g) {
    for (const c of [...g.children]) {
      c.geometry?.dispose();
      if (c.material) {
        for (const m of Array.isArray(c.material) ? c.material : [c.material]) {
          if (m.map) m.map.dispose();
          m.dispose();
        }
      }
      g.remove(c);
    }
  }

  function buildOne(d, sig) {
    let entry = sources.get(d.source);
    if (!entry) { entry = { group: new THREE.Group(), sig: '' }; entry.group.name = 'overlay-' + d.source; group.add(entry.group); sources.set(d.source, entry); }
    disposeGroup(entry.group);

    const { segments, fills, texts } = translateOps(d);
    lastStats = { segments: segments.length, fills: fills.length, texts: texts.length };

    // strokes: one mesh per (color, alpha, width) bucket
    const buckets = new Map();
    for (const s of segments) {
      const key = `${s.color}|${s.alpha.toFixed(3)}|${s.w.toFixed(2)}`;
      let arr = buckets.get(key);
      if (!arr) { arr = []; buckets.set(key, arr); }
      arr.push(s);
    }
    for (const arr of buckets.values()) {
      const pos = [];
      for (const s of arr) pos.push(...ribbonPositions(s.pts, s.w));
      if (!pos.length) continue;
      const geo = new THREE.BufferGeometry();
      geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array(pos), 3));
      const mat = new THREE.MeshBasicMaterial({
        color: arr[0].color, transparent: arr[0].alpha < 0.999, opacity: arr[0].alpha,
        side: THREE.DoubleSide, depthWrite: false,
      });
      const mesh = new THREE.Mesh(geo, mat);
      mesh.position.y = OVERLAY_Y;
      mesh.renderOrder = 3;
      entry.group.add(mesh);
    }

    // fills: flat polygons on the floor
    for (const f of fills) {
      if (f.pts.length < 3) continue;
      const shape = new THREE.Shape();
      shape.moveTo(f.pts[0][0], f.pts[0][1]);
      for (let i = 1; i < f.pts.length; i++) shape.lineTo(f.pts[i][0], f.pts[i][1]);
      const geo = new THREE.ShapeGeometry(shape);
      const mat = new THREE.MeshBasicMaterial({
        color: f.color, transparent: true, opacity: Math.min(f.alpha, 0.5),
        side: THREE.DoubleSide, depthWrite: false,
      });
      const mesh = new THREE.Mesh(geo, mat);
      mesh.rotation.x = -Math.PI / 2;
      mesh.position.y = OVERLAY_Y - 0.05;
      mesh.renderOrder = 2;
      entry.group.add(mesh);
    }

    // text: flat label on the floor
    for (const t of texts) {
      const { tex, aspect } = textTexture(String(t.t), t.color);
      const h = 4;
      const mat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, opacity: t.alpha, depthWrite: false, side: THREE.DoubleSide });
      const mesh = new THREE.Mesh(new THREE.PlaneGeometry(h * aspect, h), mat);
      mesh.rotation.x = -Math.PI / 2;
      const p = ftc(t.pos[0], t.pos[1], 0);
      mesh.position.set(p.x, OVERLAY_Y + 0.05, p.z);
      mesh.renderOrder = 4;
      entry.group.add(mesh);
    }

    entry.group.visible = visible;
    entry.sig = sig;
  }

  /**
   * Apply the drawings carried by one "state" message.
   *
   * Sticky by design: the backend only broadcasts a drawing while it is fresh
   * (Simulator.java drops it 3 s after the last packet), so Road Runner's planned
   * path used to vanish the instant the drive action finished. Here a source keeps
   * its last geometry until it is redrawn, cleared by the team code itself (an empty
   * drawing / fieldOverlay().clear()), or cleared from this page (opmode INIT, the
   * Clear button, or the Overlay checkbox).
   */
  function setDrawings(list) {
    for (const d of list || []) {
      const entry = sources.get(d.source);
      const sig = signatureOf(d);
      if (entry && entry.sig === sig) continue;   // unchanged since the last frame
      buildOne(d, sig);
    }
    // NB: sources absent from this message are kept on purpose — see above.
  }

  function clear() {
    for (const entry of sources.values()) { disposeGroup(entry.group); group.remove(entry.group); }
    sources.clear();
    lastStats = { segments: 0, fills: 0, texts: 0 };
  }

  function setVisible(v) {
    visible = v;
    for (const entry of sources.values()) entry.group.visible = v;
  }

  return { setDrawings, setVisible, clear, group, stats: () => lastStats };
}
