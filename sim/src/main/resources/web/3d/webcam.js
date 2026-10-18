// FTCSim 3D — robot on-board camera, picture-in-picture.
//
// A second PerspectiveCamera is parented to the robot's camPivot (see robot.js), so it
// automatically inherits the robot pose and the pitch angle set from the UI — no manual
// world-matrix syncing. Every frame we render the *same* scene through it into a small
// canvas in the bottom-left panel, then draw the AprilTags the team code actually
// detected on an overlay canvas above it.
//
// The pod is not decoration: it is placed and aimed exactly where the team code says its
// WebcamName sits (see parseMount), so the picture and the detections on it agree with
// the running program. Move the pitch slider and that link breaks, so the overlay stops
// drawing and says so.
//
// Two notes on why it is built this way:
//  - The pod mesh itself is hidden while rendering: the camera sits inside the cone's
//    lens, so without this the view is filled by the inside of the cone.
//  - This renderer runs with shadows off. Both renderers share the same DirectionalLight,
//    and two WebGLRenderers writing the same light's shadow map means it is rebuilt on
//    every frame of both views (flicker + wasted work). The main view keeps full shadows.
import * as THREE from 'three';

// ftcsim.hardware.SimWebcamName reports its mount as one formatted string:
//   "(6.0, 0.0, 10.0) in yaw 0 pitch 60 fov 76x43"
const MOUNT_RE = /\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\)\s*in\s*yaw\s*(-?[\d.]+)\s*pitch\s*(-?[\d.]+)\s*fov\s*(-?[\d.]+)x(-?[\d.]+)/;
// "AprilTagProcessorImpl tags [35, 36, RED SCORING 100%]"
const TAGS_RE = /tags\s*\[([^\]]*)\]/;
// the same visibility test the backend detector applies (AprilTagProcessorImpl.Camera.see)
const MIN_FACING = 0.25;

export function createWebcam({ scene, robot, canvas, overlay }) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
  renderer.setPixelRatio(1);            // a 380 px panel does not need retina
  renderer.shadowMap.enabled = false;
  renderer.setClearColor(0x0b0e13, 1);

  const octx = overlay ? overlay.getContext('2d') : null;

  // Horizontal FOV is what the team code configures and what the tag detector projects
  // against, so it is the anchor: the vertical FOV is derived from it on every resize and
  // therefore stays truthful at whatever aspect the panel ends up.
  let hfovDeg = 70.4;
  const camera = new THREE.PerspectiveCamera(50, 16 / 9, 0.5, 2000);
  // A three.js camera looks down its own -Z, but the pod's "forward" is the robot's +X,
  // so turn it a quarter turn: the pod's +Z (view axis) then lands on the pod's +X and the
  // pitch pivot tilts the view instead of rolling the image.
  camera.rotation.y = -Math.PI / 2;
  robot.camPivot.add(camera);

  let W = 0, H = 0;
  function resize() {
    const w = canvas.clientWidth || 360;
    const h = canvas.clientHeight || Math.round(w * 9 / 16);
    const changed = canvas.width !== w || canvas.height !== h;
    if (changed) {
      renderer.setSize(w, h, false);
      if (overlay) { overlay.width = w; overlay.height = h; }
      W = w; H = h;
      camera.aspect = w / h;
    }
    // derived every time, not only on a resize: applyMount() changes the FOV
    const fov = THREE.MathUtils.radToDeg(2 * Math.atan(Math.tan(THREE.MathUtils.degToRad(hfovDeg) / 2) / camera.aspect));
    if (changed || Math.abs(camera.fov - fov) > 1e-6) { camera.fov = fov; camera.updateProjectionMatrix(); }
  }

  const ro = window.ResizeObserver ? new ResizeObserver(resize) : null;
  ro?.observe(canvas);
  resize();

  // ------------------------------------------------------------------ team mount
  let mount = null;
  function applyMount(m) {
    mount = m;
    hfovDeg = m.hfovDeg;
    robot.setCamMount(m);
    resize();
  }

  // ------------------------------------------------------------------ detections
  let detections = [];
  let synced = true;          // pod still matches the team mount -> overlay is truthful
  function setDetections(list, isSynced) {
    detections = list || [];
    synced = !!isSynced;
  }

  // Tag meshes are rebuilt every time the field message arrives, so resolve the field root
  // lazily and re-check it whenever the scene no longer holds the one we cached.
  let fieldRoot = null;
  function findMesh(prefix, name) {
    const root = scene.getObjectByName('field');
    if (root !== fieldRoot) fieldRoot = root;
    return fieldRoot ? fieldRoot.getObjectByName(prefix + name) : null;
  }

  const _wp = new THREE.Vector3(), _n = new THREE.Vector3(), _toCam = new THREE.Vector3();
  const _q = new THREE.Quaternion();
  const _box = new THREE.Box3();
  const camWorld = new THREE.Vector3();

  /** The backend only reports a tag it can really see, so apply the same test here —
   *  otherwise a tag on the far side of the HIVE lights up just because it happens to
   *  project inside the frame. */
  function facingOK(mesh) {
    mesh.getWorldQuaternion(_q);
    _n.set(0, 0, 1).applyQuaternion(_q);            // a tag plane faces along its +Z
    _wp.setFromMatrixPosition(mesh.matrixWorld);
    _toCam.subVectors(camWorld, _wp).normalize();
    return _n.dot(_toCam) >= MIN_FACING;
  }

  let drawn = 0;
  function drawOverlay() {
    if (!octx) return;
    octx.clearRect(0, 0, W, H);
    drawn = 0;
    if (!synced || !detections.length) return;
    camera.getWorldPosition(camWorld);
    octx.lineWidth = 2;
    octx.font = '600 11px ui-monospace, Consolas, monospace';
    octx.textAlign = 'center';
    for (const d of detections) {
      // a tag is a single plane mesh; a cluster is the CELL group, which has no geometry
      // of its own — the world AABB covers both and is what we want to frame anyway
      const obj = d.kind === 'cluster' ? findMesh('cell-', d.name) : findMesh('tag-', d.id);
      if (!obj || !obj.visible) continue;
      if (d.kind === 'tag' && !facingOK(obj)) continue;
      _box.setFromObject(obj);
      if (_box.isEmpty()) continue;
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity, ok = true;
      for (let i = 0; i < 8 && ok; i++) {
        _wp.set(i & 1 ? _box.max.x : _box.min.x,
                i & 2 ? _box.max.y : _box.min.y,
                i & 4 ? _box.max.z : _box.min.z).project(camera);
        if (_wp.z > 1) { ok = false; break; }                       // behind the camera
        const x = (_wp.x * 0.5 + 0.5) * W, y = (-_wp.y * 0.5 + 0.5) * H;
        if (x < minX) minX = x; if (x > maxX) maxX = x;
        if (y < minY) minY = y; if (y > maxY) maxY = y;
      }
      if (!ok || maxX < 0 || minX > W || maxY < 0 || minY > H) continue;
      drawn++;
      const pad = 4;
      minX -= pad; minY -= pad; maxX += pad; maxY += pad;
      const cluster = d.kind === 'cluster';
      const col = cluster ? '#7ee2a0' : '#ffd166';
      octx.strokeStyle = col;
      octx.fillStyle = cluster ? 'rgba(126,226,160,0.10)' : 'rgba(255,209,102,0.12)';
      octx.fillRect(minX, minY, maxX - minX, maxY - minY);
      octx.strokeRect(minX, minY, maxX - minX, maxY - minY);
      const label = cluster ? `${d.label} ${d.pct}%` : d.label;
      const tw = octx.measureText(label).width + 8;
      // The tag sits in a chip just above the box. Both the chip and the text baseline were
      // derived from minY independently and used different offsets, which dropped the text
      // down onto the box's top edge where the outline and fill covered it up.
      const labelY = Math.max(0, minY - 15);
      octx.fillStyle = col;
      octx.fillRect((minX + maxX) / 2 - tw / 2, labelY, tw, 15);
      octx.fillStyle = '#0b0e13';
      octx.fillText(label, (minX + maxX) / 2, labelY + 11);
    }
  }

  let frames = 0;
  function render() {
    resize();
    // the pod would fill its own view; hide just the meshes, keep the pivot (so the
    // camera's transform is untouched)
    const hidden = [];
    for (const c of robot.camPivot.children) {
      if (c !== camera && c.visible) { c.visible = false; hidden.push(c); }
    }
    renderer.render(scene, camera);
    for (const c of hidden) c.visible = true;
    drawOverlay();
    frames++;
  }

  function dispose() { ro?.disconnect(); renderer.dispose(); }
  return {
    renderer, camera, render, resize, dispose, applyMount, setDetections,
    get mount() { return mount; },
    get synced() { return synced; },
    get detections() { return detections; },
    get drawn() { return drawn; },
    get frames() { return frames; },
  };
}

/** Pull the WebCam mount out of a device list (the WebcamName device card). */
export function parseMount(devices) {
  for (const d of devices || []) {
    if (!/webcam/i.test(d.type || '')) continue;
    const m = MOUNT_RE.exec((d.values || {}).mount || '');
    if (!m) continue;
    return {
      name: d.name, xIn: +m[1], yIn: +m[2], zIn: +m[3],
      yawDeg: +m[4], pitchDeg: +m[5], hfovDeg: +m[6], vfovDeg: +m[7],
    };
  }
  return null;
}

/** Turn the AprilTag processor summaries into drawable detections. */
export function parseDetections(devices) {
  const out = [];
  for (const d of devices || []) {
    if (!/visionportal/i.test(d.type || '')) continue;
    for (const p of (d.values || {}).processors || []) {
      const m = TAGS_RE.exec(String(p));
      if (!m || !m[1].trim()) continue;
      for (const raw of m[1].split(',')) {
        const s = raw.trim();
        if (!s) continue;
        const cm = /^(.+?)\s+(\d+)%$/.exec(s);
        if (cm) out.push({ kind: 'cluster', name: cm[1], label: cm[1], pct: +cm[2] });
        else if (/^\d+$/.test(s)) out.push({ kind: 'tag', id: +s, label: s });
      }
    }
  }
  return out;
}