// FTCSim 3D — drag the robot on the field.
// Hovering the robot shows a grab cursor; dragging it moves it across the field,
// and shift-drag (or right-button drag) rotates its heading. Anything else keeps
// the orbit-camera behavior untouched. Pose changes ride the existing
// "sim.setPose" WebSocket message — the same channel the 2D frontend uses.
import * as THREE from 'three';
import { getHalfField } from './field';

// half field comes from the backend fieldMessage.sizeIn (via field.getHalfField());
// the 72 fallback there only applies if this runs before the first rebuild.
const SEND_MS = 35;    // ~30 Hz pose updates while dragging

export function startRobotDrag({ canvas, camera, robot, send }) {
  const raycaster = new THREE.Raycaster();
  const ground = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
  const ndc = new THREE.Vector2();
  const hitP = new THREE.Vector3();
  let drag = null;      // { rotate, x, y, h, ox, oy } — FTC frame, inches / degrees
  let lastSend = 0;

  const setRay = (e) => {
    const r = canvas.getBoundingClientRect();
    ndc.x = ((e.clientX - r.left) / r.width) * 2 - 1;
    ndc.y = -((e.clientY - r.top) / r.height) * 2 + 1;
    raycaster.setFromCamera(ndc, camera);
  };
  const overRobot = () => raycaster.intersectObject(robot.group, true).length > 0;
  const groundPoint = () => raycaster.ray.intersectPlane(ground, hitP);
  const clamp = (v) => Math.max(-getHalfField(), Math.min(getHalfField(), v));
  // three(x, y, z) → FTC(x, y): ftcX = three.x, ftcY = -three.z
  const toFtc = (p) => ({ x: p.x, y: -p.z });

  function pushPose() {
    const now = performance.now();
    if (now - lastSend < SEND_MS) return;
    lastSend = now;
    send({ type: 'sim.setPose', x: drag.x, y: drag.y, headingDeg: drag.h });
    // follow the cursor immediately instead of lagging behind the 20 Hz state feed
    robot.current.x = drag.x;
    robot.current.y = drag.y;
    robot.current.h = (drag.h * Math.PI) / 180;
  }

  function onDown(e) {
    if (e.target !== canvas || (e.button !== 0 && e.button !== 2)) return;
    setRay(e);
    if (!overRobot()) return;               // field / blank → original orbit logic
    e.stopPropagation();                     // keep OrbitControls out of this gesture
    e.preventDefault();
    try { canvas.setPointerCapture(e.pointerId); } catch (_) { /* synthetic events */ }
    const g = groundPoint();
    const f = g ? toFtc(g) : { x: robot.current.x, y: robot.current.y };
    drag = {
      rotate: e.shiftKey || e.button === 2,
      x: robot.current.x, y: robot.current.y,
      h: (robot.current.h * 180) / Math.PI,
      ox: f.x - robot.current.x, oy: f.y - robot.current.y,
    };
    canvas.style.cursor = 'grabbing';
  }

  function onMove(e) {
    if (!drag) {
      if (e.target === canvas) { setRay(e); canvas.style.cursor = overRobot() ? 'grab' : ''; }
      return;
    }
    setRay(e);
    const g = groundPoint();
    if (!g) return;
    const f = toFtc(g);
    if (drag.rotate) {
      drag.h = (Math.atan2(f.y - drag.y, f.x - drag.x) * 180) / Math.PI;
    } else {
      drag.x = clamp(f.x - drag.ox);
      drag.y = clamp(f.y - drag.oy);
    }
    pushPose();
  }

  function onUp(e) {
    if (!drag) return;
    drag = null;
    canvas.style.cursor = '';
    try { canvas.releasePointerCapture(e.pointerId); } catch (_) { /* already released */ }
  }

  // window + capture so we can claim the gesture before OrbitControls sees it
  window.addEventListener('pointerdown', onDown, true);
  window.addEventListener('pointermove', onMove, true);
  window.addEventListener('pointerup', onUp, true);
  window.addEventListener('pointercancel', onUp, true);
  canvas.addEventListener('contextmenu', (e) => { setRay(e); if (drag || overRobot()) e.preventDefault(); });

  // shift + wheel over the robot: rotate heading (plain wheel still zooms)
  canvas.addEventListener('wheel', (e) => {
    if (!e.shiftKey) return;
    setRay(e);
    if (!overRobot()) return;
    e.preventDefault();
    e.stopPropagation();
    const r = robot.current;
    const h = (r.h * 180) / Math.PI + (e.deltaY > 0 ? -5 : 5);
    send({ type: 'sim.setPose', x: r.x, y: r.y, headingDeg: h });
    r.h = (h * Math.PI) / 180;
  }, { passive: false, capture: true });
}
