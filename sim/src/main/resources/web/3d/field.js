// FTCSim 3D field builder.
//
// Builds the playing field from the simulator's "hello"/"field" WebSocket messages.
// Everything is data-driven from the backend's Field description (obstacles, zones,
// shapes, tags, clusters); no backend code is changed.
//
// Coordinate mapping (see ftcsim.physics.Field / CLAUDE.md):
//   FTC field frame: origin centre, +X toward audience, +Y from red wall (-Y) to blue
//   wall (+Y), +Z up, lengths in inches.
//   three.js:        x = ftc.x, y = ftc.z (height), z = -ftc.y. 1 unit = 1 inch.
import * as THREE from 'three';

export const HALF = 72;      // field half size fallback, inches (backend sends sizeIn)
let halfField = HALF;          // current half size, updated from fieldMessage.sizeIn in rebuild()
export function getHalfField() { return halfField; }
const WALL_H = 9;            // visual wall height
const TILE = 12;             // foam tile size

// The backend's "shapes" list is the 2D top-down drawing layer: for BIOBUZZ it is the
// flat projection of the HIVE (frame outline, pivot line, the "R up"/"R down"/"B down"/
// "B up" CELL footprints and the FLOWER circles). In 3D every one of those already exists
// as real geometry (HIVE frame + beams + CELL prisms, 21.5 in FLOWER obstacle boxes), so
// rendering the flat overlay again just paints rectangles on the floor. Off by default:
// that ground stays plain. Flip to true to bring the 2D overlay back.
const SHOW_FLAT_SHAPES = false;

// FTC (x, y[, z]) -> three.js position
export function ftc(x, y, z = 0) { return new THREE.Vector3(x, z, -y); }

/** Unit normal (three.js) a tag face points along, from yaw/pitch in degrees (FTC frame). */
function facingNormal(yawDeg, pitchDeg) {
  const p = THREE.MathUtils.degToRad(pitchDeg), y = THREE.MathUtils.degToRad(yawDeg);
  return new THREE.Vector3(Math.cos(p) * Math.cos(y), Math.sin(p), -Math.cos(p) * Math.sin(y));
}

function disposeObject(root) {
  root.traverse(o => {
    if (o.geometry) o.geometry.dispose();
    if (o.material) {
      for (const m of Array.isArray(o.material) ? o.material : [o.material]) {
        if (m.map) m.map.dispose();
        m.dispose();
      }
    }
  });
}

/** Canvas texture with the AprilTag id printed on it. */
function tagTexture(id) {
  const c = document.createElement('canvas'); c.width = c.height = 256;
  const g = c.getContext('2d');
  g.fillStyle = '#f2f2f2'; g.fillRect(0, 0, 256, 256);
  g.strokeStyle = '#111'; g.lineWidth = 10; g.strokeRect(5, 5, 246, 246);
  // Digits as large as possible: grow the font until the ink nearly fills the sticker
  // (both directions), keeping only a small margin inside the black border. Tags are
  // separate planes 0.5 in apart, so a full-size digit still cannot run into its
  // neighbours — it only has to stay on its own sticker.
  const text = String(id);
  const maxW = 256 * 0.82, maxH = 256 * 0.82;
  let fs = 320;
  for (; fs > 20; fs -= 2) {
    g.font = `bold ${fs}px system-ui`;
    const m = g.measureText(text);
    const inkH = (m.actualBoundingBoxAscent || fs * 0.72) + (m.actualBoundingBoxDescent || 0);
    if (m.width <= maxW && inkH <= maxH) break;
  }
  // centre the ink box (not the em box) so the digits sit visually in the middle
  const mt = g.measureText(text);
  const asc = mt.actualBoundingBoxAscent || fs * 0.72;
  const desc = mt.actualBoundingBoxDescent || 0;
  g.fillStyle = '#111'; g.textAlign = 'center'; g.textBaseline = 'alphabetic';
  g.fillText(text, 128, 128 + (asc - desc) / 2);
  const tex = new THREE.CanvasTexture(c); tex.anisotropy = 4; tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

/** White cluster-sticker backing for the CELL bottom face: alliance-coloured strip
 *  with the 0.5 in reference holes at its ends plus the cluster name (fig 9-15).
 *  Canvas top = sticker top = toward the CELL mouth; bottom = strip = field centre. */
function stickerTexture(name, color) {
  const W = 512, H = Math.round(W * 5.1 / 17.5);
  const c = document.createElement('canvas'); c.width = W; c.height = H;
  const g = c.getContext('2d');
  g.fillStyle = '#f2f2f2'; g.fillRect(0, 0, W, H);
  const stripH = Math.round(H * 0.9 / 5.1);
  g.fillStyle = color; g.fillRect(0, H - stripH, W, stripH);
  for (const fx of [-6.75, 6.75]) {
    g.fillStyle = '#e8a33d';
    g.beginPath();
    g.arc(W / 2 + (fx / 17.5) * W, H - stripH / 2, stripH * 0.32, 0, Math.PI * 2);
    g.fill();
  }
  g.fillStyle = '#111'; g.font = 'bold 15px system-ui'; g.textAlign = 'center'; g.textBaseline = 'middle';
  g.fillText(name + ' · 36h11', W / 2, H - stripH / 2);
  const tex = new THREE.CanvasTexture(c); tex.colorSpace = THREE.SRGBColorSpace; tex.anisotropy = 4;
  return tex;
}

/** Flat label texture (transparent background). */
function labelTexture(text, color = '#e8ebf0') {
  const c = document.createElement('canvas');
  const g = c.getContext('2d');
  g.font = 'bold 40px system-ui';
  const w = Math.max(2, g.measureText(text).width + 16);
  c.width = w; c.height = 56;
  const g2 = c.getContext('2d');
  g2.font = 'bold 40px system-ui'; g2.textAlign = 'center'; g2.textBaseline = 'middle';
  g2.fillStyle = color; g2.fillText(text, w / 2, 30);
  const tex = new THREE.CanvasTexture(c); tex.colorSpace = THREE.SRGBColorSpace;
  return { tex, aspect: w / 56 };
}

export function createField(scene) {
  const root = new THREE.Group();
  root.name = 'field';
  scene.add(root);

  let labelsVisible = true;

  function clear() {
    disposeObject(root);
    root.clear();
    // the see-saw meta (displayed tilt, running swing) survives the rebuild; the groups do not
    for (const k in hiveAssemblies) hiveAssemblies[k].group = null;
    for (const k in cellGroups) delete cellGroups[k];
  }

  // ---------------------------------------------------------------- static shell
  // Draws at the field's actual half-size. `halfField` is updated from the backend's
  // fieldMessage.sizeIn in rebuild(); it falls back to HALF (72) if the message omits it.
  function buildShell(name) {
    // floor
    const floor = new THREE.Mesh(
      new THREE.PlaneGeometry(2 * halfField, 2 * halfField),
      new THREE.MeshStandardMaterial({ color: '#20262f', roughness: 0.95, metalness: 0 }));
    floor.rotation.x = -Math.PI / 2; floor.receiveShadow = true;
    root.add(floor);

    // faint alliance-wall tints (red wall at FTC -Y => three +Z, blue at three -Z)
    for (const [z, color] of [[1, 0xd93b2b], [-1, 0x2b5fd9]]) {
      const tint = new THREE.Mesh(
        new THREE.PlaneGeometry(2 * halfField, 2 * halfField),
        new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.05, depthWrite: false }));
      tint.rotation.x = -Math.PI / 2; tint.position.set(0, 0.02, z * halfField / 2);
      root.add(tint);
    }

    // tile grid
    const grid = new THREE.GridHelper(2 * halfField, (2 * halfField) / TILE, 0x3a4352, 0x2b323e);
    grid.position.y = 0.04;
    root.add(grid);

    // perimeter walls
    const wallMat = new THREE.MeshStandardMaterial({ color: '#39414f', roughness: 0.8 });
    const wallGeom = new THREE.BoxGeometry(2 * halfField + 4, WALL_H, 2);
    const walls = [
      { pos: [0, WALL_H / 2, halfField + 1], color: 0xd93b2b },   // red wall (FTC -Y)
      { pos: [0, WALL_H / 2, -halfField - 1], color: 0x2b5fd9 },  // blue wall (FTC +Y)
      { pos: [halfField + 1, WALL_H / 2, 0], rot: Math.PI / 2, color: null },
      { pos: [-halfField - 1, WALL_H / 2, 0], rot: Math.PI / 2, color: null },
    ];
    for (const w of walls) {
      const m = new THREE.Mesh(wallGeom, wallMat);
      m.position.set(...w.pos);
      if (w.rot) m.rotation.y = w.rot;
      root.add(m);
      if (w.color) { // thin colored strip on the alliance walls
        const strip = new THREE.Mesh(
          new THREE.BoxGeometry(2 * halfField + 4, 1.5, 0.4),
          new THREE.MeshBasicMaterial({ color: w.color }));
        strip.position.set(w.pos[0], 1.2, w.pos[2] - Math.sign(w.pos[2]) * 1.2);
        root.add(strip);
      }
    }
  }

  // ---------------------------------------------------------------- data pieces
  function buildZones(zones) {
    for (const z of zones || []) {
      const w = z.maxX - z.minX, d = z.maxY - z.minY;
      const m = new THREE.Mesh(
        new THREE.PlaneGeometry(w, d),
        new THREE.MeshBasicMaterial({ color: z.color, transparent: true, opacity: 0.18, depthWrite: false }));
      m.rotation.x = -Math.PI / 2;
      m.position.copy(ftc(z.minX + w / 2, z.minY + d / 2, 0)); m.position.y = 0.06;
      root.add(m);
      const edge = new THREE.LineSegments(
        new THREE.EdgesGeometry(new THREE.PlaneGeometry(w, d)),
        new THREE.LineBasicMaterial({ color: z.color }));
      edge.rotation.x = -Math.PI / 2; edge.position.copy(m.position); edge.position.y = 0.08;
      root.add(edge);
    }
  }

  // Obstacle heights: Simulator.java's field payload only carries name/bounds/colour
  // (no height), so infer it from the obstacle name — values straight out of Field.java.
  const OBSTACLE_HEIGHTS = [
    [/^HIVE foot bar/i, 1.5],
    [/^FLOWER/i, 21.5],
    [/goal$/i, 40],
  ];
  function obstacleHeight(o) {
    if (typeof o.height === 'number' && isFinite(o.height)) return o.height;
    for (const [re, h] of OBSTACLE_HEIGHTS) if (re.test(o.name || '')) return h;
    return 1.5;  // unknown -> assume a low floor-level element
  }

  function buildObstacles(obs) {
    for (const o of obs || []) {
      const w = o.maxX - o.minX, d = o.maxY - o.minY, h = obstacleHeight(o);
      const m = new THREE.Mesh(
        new THREE.BoxGeometry(w, h, d),
        new THREE.MeshStandardMaterial({ color: o.color || '#8a93a6', roughness: 0.7, metalness: 0.05 }));
      m.position.copy(ftc(o.minX + w / 2, o.minY + d / 2, 0)); m.position.y = h / 2;
      m.castShadow = true; m.receiveShadow = true;
      root.add(m);
    }
  }

  /** Local basis of a CELL cluster, matching the backend's cluster frame so the
   *  backend tag coordinates land exactly in this local space:
   *  +Z = cluster facing (n), +Y = pentagon "up" (as world-up as possible),
   *  +X = across (the tag-row direction). */
  function cellBasis(cl) {
    const n = facingNormal(cl.yawDeg, cl.pitchDeg);
    const up = new THREE.Vector3(0, 1, 0).addScaledVector(n, -n.y).normalize();
    const across = new THREE.Vector3().crossVectors(up, n).normalize();
    return { n, up, across };
  }

  // CELL, per manual fig 9-11 / 9-15 / 9-16: a pentagon 20 in across the bottom edge,
  // 14 in up to the apex, pivot midline 7.61 above the bottom, ~11.2 in deep along its
  // facing. In the backend's cluster frame the origin is the pentagon centre and the
  // tag stickers sit on the front face (5.622 in along the facing), so the glass panel
  // front face is put exactly at the tag plane and the tags land flush on it.
  const CELL_TUBE_R = 0.65;     // red edge-frame tube radius
  // CELL/HIVE geometry now comes from the backend fieldMessage.cellGeometry (source of truth).
  // The values below are a fallback only used if an older sim build omits it.
  let cellGeom = {
    pent: [[-10, -9.938], [10, -9.938], [10, -2.328], [0, 4.062], [-10, -2.328]],
    depth: 11.244, bottom: 9.938, armDrop: 5.6375, hivePivotZ: 43.95, hiveY: 12.75, hiveTiltDeg: 30,
  };

  /** Thin round tube from a to b (three.js local coords) — one edge of a frame. */
  function addEdgeTube(parent, a, b, mat, r = CELL_TUBE_R) {
    const dir = new THREE.Vector3().subVectors(b, a);
    const len = dir.length();
    const m = new THREE.Mesh(new THREE.CylinderGeometry(r, r, len, 10), mat);
    m.position.copy(a).addScaledVector(dir, 0.5);
    m.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir.normalize());
    m.castShadow = true;
    parent.add(m);
  }

  // Per-alliance see-saw assemblies: everything that rotates around the pivot (the beam, both
  // CELLs and their tags) lives in one group whose origin is the pivot point, so a tip is a
  // 60° swing of the group around its local z (the pivot axis runs along FTC Y = three z).
  const hiveAssemblies = {};   // 'RED'/'BLUE' -> { group, shown, rot }
  const cellGroups = {};       // cluster name -> the CELL group (tags are parented into it)
  function hiveAssembly(prefix) {
    let a = hiveAssemblies[prefix];
    if (!a || !a.group) {
      const g = new THREE.Group();
      g.name = 'hive-' + prefix;
      g.position.copy(ftc(0, prefix === 'RED' ? -cellGeom.hiveY : cellGeom.hiveY, cellGeom.hivePivotZ));
      root.add(g);
      if (!a) a = hiveAssemblies[prefix] = { shown: null, rot: 0 };
      a.group = g;
      g.rotation.z = a.rot;
    }
    return a;
  }

  function buildClusters(field) {
    for (const cl of field.clusters || []) {
      const color = cl.name.startsWith('RED') ? 0xd93b2b : 0x2b5fd9;
      const { n, up, across } = cellBasis(cl);

      const cell = new THREE.Group();
      cell.name = 'cell-' + cl.name;
      cell.quaternion.setFromRotationMatrix(new THREE.Matrix4().makeBasis(across, up, n));
      cell.position.copy(ftc(cl.x, cl.y, cl.z));

      // the box itself, per manual fig 9-11: five closed faces and the whole pentagon mouth
      // (the largest face) open — back panel plus one wall per pentagon edge
      const shellMat = new THREE.MeshStandardMaterial({
        color, transparent: true, opacity: 0.2, roughness: 0.45, metalness: 0,
        side: THREE.DoubleSide, depthWrite: false });
      const half = cellGeom.depth / 2;
      const backPanel = new THREE.Mesh(
        new THREE.ShapeGeometry(new THREE.Shape(cellGeom.pent.map(([u, v]) => new THREE.Vector2(u, v)))),
        shellMat);
      backPanel.position.z = -half;
      cell.add(backPanel);
      for (let i = 0; i < cellGeom.pent.length; i++) {
        const [u1, v1] = cellGeom.pent[i], [u2, v2] = cellGeom.pent[(i + 1) % cellGeom.pent.length];
        const wall = new THREE.Mesh(new THREE.BufferGeometry(), shellMat);
        wall.geometry.setAttribute('position', new THREE.Float32BufferAttribute([
          u1, v1, -half, u2, v2, -half, u2, v2, half,
          u1, v1, -half, u2, v2, half, u1, v1, half,
        ], 3));
        wall.geometry.computeVertexNormals();
        cell.add(wall);
      }

      // red edge frame: thin tubes along the pentagon outline (front + back) and the
      // corner edges through the depth. A deep extruded band here reads as a solid red
      // tunnel from oblique angles; the manual render shows edge tubes with the whole
      // interior and both sides open (fig 9-11).
      const frameMat = new THREE.MeshStandardMaterial({ color, roughness: 0.5, metalness: 0.2 });
      const front = cellGeom.pent.map(([u, v]) => new THREE.Vector3(u, v, cellGeom.depth / 2));
      const back = cellGeom.pent.map(([u, v]) => new THREE.Vector3(u, v, -cellGeom.depth / 2));
      for (let i = 0; i < front.length; i++) {
        const j = (i + 1) % front.length;
        addEdgeTube(cell, front[i], front[j], frameMat);
        addEdgeTube(cell, back[i], back[j], frameMat);
        addEdgeTube(cell, front[i], back[i], frameMat);
      }

      // cluster-sticker backing on the bottom face (manual fig 9-15): white sticker
      // with the alliance strip + reference holes toward the field centre; the four
      // AprilTags render just in front of it (see buildTags)
      const sticker = new THREE.Mesh(
        new THREE.PlaneGeometry(17.5, 5.1),
        new THREE.MeshBasicMaterial({
          map: stickerTexture(cl.name, '#' + color.toString(16).padStart(6, '0')),
          side: THREE.DoubleSide }));
      sticker.name = 'sticker-' + cl.name;
      sticker.position.set(0, -cellGeom.bottom - 0.05, -0.5);
      sticker.rotation.x = Math.PI / 2;  // plane faces -up (down), texture up = +n (mouth)
      cell.add(sticker);
      // a CELL rotates with its HIVE: parent it to the alliance's see-saw assembly
      const prefix = cl.name.startsWith('RED') ? 'RED' : cl.name.startsWith('BLUE') ? 'BLUE' : null;
      const parent = prefix ? hiveAssembly(prefix).group : root;
      if (parent !== root) cell.position.sub(parent.position);
      parent.add(cell);
      cellGroups[cl.name] = cell;
    }
    // BIOBUZZ HIVE: see-saw beams through the central pivot bar + frame
    if ((field.name || '').toUpperCase() === 'BIOBUZZ') buildHives(field);
  }

  function buildHives(field) {
    const frameMat = new THREE.MeshStandardMaterial({ color: '#6c7a95', roughness: 0.6, metalness: 0.3 });
    const beamMat = new THREE.MeshStandardMaterial({ color: '#8a93a6', roughness: 0.55, metalness: 0.25 });
    // central pivot bar along FTC Y (three Z) at x=0, height 43.95 in (Field.java HIVE_PIVOT_Z)
    const bar = new THREE.Mesh(new THREE.BoxGeometry(2.5, 2.5, 49.5), frameMat);
    bar.position.set(0, cellGeom.hivePivotZ, 0);
    root.add(bar);
    // frame per manual 9.6.1 + fig 9-7: two triangular metal structures, each standing
    // on a full-width floor rail (the "under TILE strip" = the HIVE foot-bar obstacle
    // box rendered from the backend — solid, robots may not cross it). The legs rise
    // from the ENDS of that rail up to the apexes joined by the pivot crossbar.
    const FRAME_R = 1.0, FOOT_H = 1.5;  // FOOT_H = foot-bar obstacle height
    for (const bz of [-24, 24]) {
      addEdgeTube(root, new THREE.Vector3(-19.475, FOOT_H, bz), new THREE.Vector3(19.475, FOOT_H, bz), frameMat, FRAME_R);
      addEdgeTube(root, new THREE.Vector3(-19.475, FOOT_H, bz), new THREE.Vector3(0, cellGeom.hivePivotZ, bz), frameMat, FRAME_R);
      addEdgeTube(root, new THREE.Vector3(19.475, FOOT_H, bz), new THREE.Vector3(0, cellGeom.hivePivotZ, bz), frameMat, FRAME_R);
    }
    // one see-saw beam per alliance, through the pivot and OUT to each CELL — the rod connects
    // the two boxes: it stops just short of each box's back face and a short link drops from
    // the beam end to the box's bottom-back edge, instead of running through the interior
    for (const prefix of ['RED', 'BLUE']) {
      const asm = hiveAssembly(prefix);
      const a = (field.clusters || []).find(c => c.name === prefix + ' AUDIENCE');
      const s = (field.clusters || []).find(c => c.name === prefix + ' SCORING');
      if (!asm || !a || !s) continue;
      const pivot = asm.group.position;
      const ends = [a, s].map(cl => {
        const { up, n } = cellBasis(cl);
        const centre = ftc(cl.x, cl.y, cl.z);
        const arm = centre.clone().addScaledVector(up, -cellGeom.armDrop);
        const dir = arm.clone().sub(pivot).normalize();
        // bottom-back edge of the box (the mouth is the +n face)
        const edge = centre.clone()
          .addScaledVector(up, -cellGeom.bottom)
          .addScaledVector(n, -cellGeom.depth / 2);
        const end = pivot.clone().addScaledVector(dir, edge.clone().sub(pivot).dot(dir) - 1.2);
        return { end, edge, dir };
      });
      const [ea, es] = ends;
      const beam = new THREE.Mesh(new THREE.BoxGeometry(ea.end.distanceTo(es.end), 2.5, 3), beamMat);
      beam.position.copy(ea.end.clone().add(es.end).multiplyScalar(0.5)).sub(pivot);
      beam.quaternion.setFromUnitVectors(new THREE.Vector3(1, 0, 0), ea.end.clone().sub(es.end).normalize());
      beam.castShadow = true;
      asm.group.add(beam);
      for (const { end, edge } of ends) {
        addEdgeTube(asm.group, end.clone().sub(pivot), edge.clone().sub(pivot), beamMat, 0.55);
      }
    }
    aimHives(field);
  }

  /**
   * Point each see-saw at the stable position the field state asks for. The audience CELL is
   * mounted at the assembly's +X end, so "audience" tips that end up (+30° around the pivot
   * axis). A tip is animated: the group starts at the previously displayed pose and swings to
   * its new one (update() eases the remainder to zero).
   */
  function aimHives(field) {
    const st = field.state || {};
    for (const prefix of ['RED', 'BLUE']) {
      const a = hiveAssemblies[prefix];
      if (!a) continue;
      const target = st[prefix.toLowerCase() + 'Hive'] === 'scoring' ? -cellGeom.hiveTiltDeg * Math.PI / 180 : cellGeom.hiveTiltDeg * Math.PI / 180;
      if (a.shown === null) { a.shown = target; a.rot = 0; }
      else a.rot = a.shown - target;   // start the swing from wherever we displayed last
      a.shown = target;
      if (Math.abs(a.rot) < 1e-4) a.rot = 0;
      a.group.rotation.z = a.rot;
    }
  }

  /** Ease the running tip swings back to their rest positions. */
  function update(dt) {
    for (const k in hiveAssemblies) {
      const a = hiveAssemblies[k];
      if (a.rot === 0) continue;
      a.rot *= Math.exp(-5 * Math.min(dt, 0.1));   // falls through, then the damper stops it
      if (Math.abs(a.rot) < 0.002) a.rot = 0;
      a.group.rotation.z = a.rot;
    }
  }

  function buildTags(field) {
    const clusters = field.clusters || [];
    for (const t of field.tags || []) {
      const cl = clusters.find(c => (t.name || '').startsWith(c.name));
      // The server serialises the tag size as "size" (Simulator.java); reading "sizeIn"
      // used to fall through to the 6.5 in default for every tag — on a CELL cluster
      // (3.25 in tags only 3.75 in apart) that made the white faces overlap.
      const size = t.size || t.sizeIn || (cl ? 3.25 : 6.5);
      const pos = ftc(t.x, t.y, t.z);
      let quat = null;
      let parent = root;
      if (cl) {
        // Manual 9.9: the cluster sticker is on the BOTTOM face of the CELL, facing
        // downward toward the tiles, its bottom edge (reference holes) toward the
        // field centre. On the rendered CELL (fig 9-15) it sits at the MIDDLE of the
        // bottom wall (z = 0 in the CELL frame), not recessed toward the back.
        // Everything below is expressed in the CELL's own frame, so the tag can ride
        // inside the CELL group and tip with its HIVE.
        const { across } = cellBasis(cl);
        const origin = ftc(cl.x, cl.y, cl.z);
        const d = pos.clone().sub(origin);
        // local x = across (the row), y = up, z = mouth: keep the tag's offset along the row
        pos.set(d.dot(across), -cellGeom.bottom - 0.15, 0);
        // the same pose as the sticker: flat on the bottom face, facing down, texture up toward
        // the mouth. The CELL group already carries the orientation, so this is local: the plane's
        // +z normal turns to -y (down) and its "up" edge turns to +z (toward the mouth).
        quat = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(1, 0, 0), Math.PI / 2);
        parent = cellGroups[cl.name] || root;
      }
      const mesh = new THREE.Mesh(
        new THREE.PlaneGeometry(size, size),
        new THREE.MeshBasicMaterial({ map: tagTexture(t.id), side: THREE.DoubleSide }));
      mesh.name = 'tag-' + t.id;
      mesh.position.copy(pos);
      if (quat) mesh.quaternion.copy(quat);
      else {
        const n = facingNormal(t.yawDeg || 0, 0);
        mesh.position.addScaledVector(n, 0.15);
        mesh.lookAt(pos.clone().add(n));
      }
      parent.add(mesh);
    }
  }

  function buildShapes(shapes) {
    for (const sh of shapes || []) {
      const c = sh.coords || [], lw = Math.max(sh.width || 1, 0.5);
      const y = 0.1;
      // colors may carry an alpha suffix (#rrggbbaa); THREE.Color only takes #rrggbb
      const parseColor = (col, fallbackOp = 0.25) => {
        if (!col) return { color: null, opacity: 1 };
        if (col.length === 9 && col.startsWith('#')) return { color: col.slice(0, 7), opacity: parseInt(col.slice(7), 16) / 255 };
        return { color: col, opacity: fallbackOp };
      };
      switch (sh.type) {
        case 'rect': {
          const w = c[2] - c[0], d = c[3] - c[1];
          if (sh.fill) {
            const { color, opacity } = parseColor(sh.fill);
            const m = new THREE.Mesh(new THREE.PlaneGeometry(w, d),
              new THREE.MeshBasicMaterial({ color, transparent: true, opacity: opacity * 0.6, depthWrite: false }));
            m.rotation.x = -Math.PI / 2; m.position.copy(ftc(c[0] + w / 2, c[1] + d / 2, 0)); m.position.y = y;
            root.add(m);
          }
          if (sh.stroke) {
            const e = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.PlaneGeometry(w, d)),
              new THREE.LineBasicMaterial({ color: sh.stroke }));
            e.rotation.x = -Math.PI / 2; e.position.copy(ftc(c[0] + w / 2, c[1] + d / 2, 0)); e.position.y = y + 0.02;
            root.add(e);
          }
          if (sh.label) addLabel(sh.label, ftc(c[0] + w / 2, c[1] + d / 2, 1.2));
          break;
        }
        case 'circle': {
          const ring = new THREE.Mesh(new THREE.RingGeometry(Math.max(c[2] - lw / 2, 0.2), c[2] + lw / 2, 40),
            new THREE.MeshBasicMaterial({ color: sh.stroke || sh.fill || '#ffffff', transparent: true, opacity: 0.6, side: THREE.DoubleSide, depthWrite: false }));
          ring.rotation.x = -Math.PI / 2; ring.position.copy(ftc(c[0], c[1], 0)); ring.position.y = y;
          root.add(ring);
          if (sh.label) addLabel(sh.label, ftc(c[0], c[1], 1.2));
          break;
        }
        case 'line': {
          const a = ftc(c[0], c[1], 0), b = ftc(c[2], c[3], 0);
          const len = a.distanceTo(b);
          const m = new THREE.Mesh(new THREE.PlaneGeometry(len, lw),
            new THREE.MeshBasicMaterial({ color: sh.stroke || '#ffffff', transparent: true, opacity: 0.7, depthWrite: false }));
          m.rotation.x = -Math.PI / 2;
          m.position.copy(a.clone().add(b).multiplyScalar(0.5)); m.position.y = y;
          m.rotation.z = Math.atan2(b.z - a.z, b.x - a.x);
          root.add(m);
          break;
        }
        case 'polygon': {
          const pts = [];
          for (let i = 0; i + 1 < c.length; i += 2) pts.push(new THREE.Vector2(c[i], c[i + 1]));
          if (pts.length < 2) break;
          const geo = new THREE.ShapeGeometry(pts.reduce((s, p) => { s.lineTo(p.x, p.y); return s; }, new THREE.Shape().moveTo(pts[0].x, pts[0].y)));
          if (sh.fill) {
            const m = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ color: sh.fill, transparent: true, opacity: 0.25, side: THREE.DoubleSide, depthWrite: false }));
            m.rotation.x = -Math.PI / 2; m.position.y = y;
            root.add(m);
          }
          const e = new THREE.LineSegments(new THREE.EdgesGeometry(geo), new THREE.LineBasicMaterial({ color: sh.stroke || '#ffffff' }));
          e.rotation.x = -Math.PI / 2; e.position.y = y + 0.02;
          root.add(e);
          break;
        }
        case 'label':
          if (sh.label || sh.stroke) addLabel(sh.label || '', ftc(c[0], c[1], 1.2), sh.stroke);
          break;
      }
    }
  }

  const labelMeshes = [];
  function addLabel(text, pos, color) {
    if (!text) return;
    const { tex, aspect } = labelTexture(text, color || '#e8ebf0');
    const h = 3.2;
    const m = new THREE.Mesh(new THREE.PlaneGeometry(h * aspect, h),
      new THREE.MeshBasicMaterial({ map: tex, transparent: true, depthWrite: false }));
    m.position.copy(pos); m.position.y = 0.4;
    m.rotation.x = -Math.PI / 2;
    m.visible = labelsVisible;
    root.add(m);
    labelMeshes.push(m);
  }

  function rebuild(fieldMsg) {
    clear();
    labelMeshes.length = 0;
    if (typeof fieldMsg.sizeIn === 'number' && fieldMsg.sizeIn > 0) halfField = fieldMsg.sizeIn / 2;
    else halfField = HALF;
    if (fieldMsg.cellGeometry) cellGeom = fieldMsg.cellGeometry;
    buildShell(fieldMsg.name);
    buildZones(fieldMsg.zones);
    buildObstacles(fieldMsg.obstacles);
    buildClusters(fieldMsg);
    buildTags(fieldMsg);
    if (SHOW_FLAT_SHAPES) buildShapes(fieldMsg.shapes);
    return root;
  }

  function setLabelsVisible(v) {
    labelsVisible = v;
    for (const m of labelMeshes) m.visible = v;
  }

  return { root, rebuild, setLabelsVisible, update };
}
