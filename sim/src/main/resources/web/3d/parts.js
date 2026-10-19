// FTCSim 3D — mounts declared for motors and servos in the robot configuration.
//
// The backend sends each device's declared pose alongside its live values, on the same 20 Hz
// `state` message (see ftcsim.vision.DeviceMount). A part with no mount is left out of the robot
// model rather than parked at the origin: absence is a statement, and drawing a part somewhere
// the team never declared would be a guess.
//
// Frames. The config is written in the robot frame (x forward, y left, z up, inches); this scene's
// local frame is +X forward, +Y up, +Z right. Robot "left" is therefore local -Z, and the whole
// conversion lives in the two helpers below rather than being sprinkled through the builder.
import * as THREE from 'three';

/** Robot frame (x fwd, y left, z up, inches) -> this scene's local (X fwd, Y up, Z right). */
export function toLocal(m) {
  return new THREE.Vector3(m.x, m.z, -m.y);
}

/** The declared mount of every device that has one, keyed by device name. */
export function parseMounts(devices) {
  const out = new Map();
  for (const d of devices || []) {
    const m = d.mount;
    if (!m) continue;
    out.set(d.name, {
      name: d.name,
      type: d.type,
      x: +m.x || 0, y: +m.y || 0, z: +m.z || 0,
      axis: m.axis === 'z' ? 'z' : 'x',
      minDeg: +m.minDeg || 0,
      maxDeg: +m.maxDeg || 0,
      shape: m.shape === 'box' ? 'box' : 'cylinder',
      lengthIn: +m.lengthIn || 2,
      diameterIn: +m.diameterIn || 1.4,
      parent: m.parent || null,
      sweeps: Math.abs((+m.maxDeg || 0) - (+m.minDeg || 0)) > 1e-6,
    });
  }
  return out;
}

/** A stable signature of the declared set, so a rebuild happens only when it really changed. */
export function mountSignature(mounts) {
  if (!mounts || !mounts.size) return '';
  return [...mounts.values()].map(m => `${m.name}:${m.x},${m.y},${m.z},${m.axis},${m.minDeg},${m.maxDeg},${m.shape},${m.parent || ''}`).sort().join('|');
}

/**
 * A motor or servo body at its declared place.
 *
 * The joint spins about `axis`: a wheel-like motor turns about local Z (the axle across the
 * chassis), an arm-like servo sweeps about local X... which maps to the robot's z because the
 * robot frame's pitch axis is the one an arm swings in. buildParts() sets the rotation axis from
 * the mount, so a part only has to expose "turn me by this many degrees".
 */
export function buildPartMesh(mount) {
  const g = new THREE.Group();
  g.name = 'part-' + mount.name;

  const radius = mount.diameterIn / 2;
  const body = new THREE.Group();
  // Materials that activity colouring will drive, with their base colour remembered so a part at
  // rest still looks like the mechanism it is rather than a grey block.
  const tinted = [];

  if (mount.shape === 'box') {
    // An intake finger: a flat blade, longer along the robot's forward axis than it is tall.
    const bladeMat = new THREE.MeshStandardMaterial({ color: 0xf0c53c, roughness: 0.5, metalness: 0.25 });
    const blade = new THREE.Mesh(
      new THREE.BoxGeometry(mount.lengthIn * 1.6, 0.5, mount.diameterIn * 1.2), bladeMat);
    blade.castShadow = true;
    body.add(blade);
    tinted.push({ mat: bladeMat, base: 0xf0c53c });
  } else {
    // A motor: cylinder along the axle, plus a darker end cap so the spin is readable.
    const drumMat = new THREE.MeshStandardMaterial({ color: 0x6f7d95, roughness: 0.5, metalness: 0.4 });
    const drum = new THREE.Mesh(
      new THREE.CylinderGeometry(radius, radius, mount.lengthIn, 18), drumMat);
    drum.castShadow = true;
    body.add(drum);
    tinted.push({ mat: drumMat, base: 0x6f7d95 });
    const capMat = new THREE.MeshStandardMaterial({ color: 0x2b3240, roughness: 0.7 });
    for (const s of [1, -1]) {
      const cap = new THREE.Mesh(new THREE.CircleGeometry(radius * 0.92, 18), capMat);
      cap.position.y = s * mount.lengthIn / 2;
      cap.rotation.x = s > 0 ? -Math.PI / 2 : Math.PI / 2;
      body.add(cap);
    }
    // a stripe along the drum so rotation is visible even head-on
    const stripeMat = new THREE.MeshStandardMaterial({ color: 0xffd166, roughness: 0.4 });
    const stripe = new THREE.Mesh(
      new THREE.BoxGeometry(radius * 0.34, mount.lengthIn * 1.02, radius * 0.34), stripeMat);
    body.add(stripe);
    tinted.push({ mat: stripeMat, base: 0xffd166 });
  }

  // The joint: every part turns about this, and the body is offset along the axis so the part
  // hangs off its mount point instead of spinning through it.
  const joint = new THREE.Group();
  joint.name = 'joint-' + mount.name;
  joint.add(body);
  g.add(joint);

  g.position.copy(toLocal(mount));
  // robot x (roll) -> local X ; robot z (pitch) -> local Z
  if (mount.axis === 'z') {
    joint.rotation.order = 'ZYX';
    body.position.z = mount.lengthIn / 2;
    body.rotation.x = Math.PI / 2;      // cylinder axis +Y -> local Z
  } else {
    joint.rotation.order = 'XYZ';
    body.position.x = mount.lengthIn / 2;
    body.rotation.z = Math.PI / 2;      // cylinder axis +Y -> local X
  }

  g.userData = { mount, joint, body, tinted };
  return g;
}

/** Degrees -> the joint angle, honouring the declared sweep direction. */
export function angleFor(mount, fraction) {
  if (!mount.sweeps) return fraction * 360;
  const clamped = Math.max(0, Math.min(1, fraction));
  return mount.minDeg + (mount.maxDeg - mount.minDeg) * clamped;
}