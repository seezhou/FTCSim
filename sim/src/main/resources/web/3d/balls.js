// FTCSim 3D — the balls the robot launched.
// The backend owns the physics (ftcsim.physics.Projectile integrates gravity, bounces and rolling
// at 1 kHz); this module only draws what the 20 Hz "state" message says is on the field, easing
// between updates so a fast ball does not strobe across the screen.
// Field frame is inches with z up; the scene is x, up, -y (see robot.js).
import * as THREE from 'three';

const BALL_COLOR = 0xf2f4f8;
const SCORED_COLOR = 0x8ce07a;
const SCORED_EMISSIVE = 0x2f6b28;
// a ball captured into a CELL wears its alliance's colours
const CAPTURED = {
  RED: { color: 0xd93b2b, emissive: 0x5a150f },
  BLUE: { color: 0x2b5fd9, emissive: 0x12275a },
};

function tintOf(b) {
  if (b.cell) return b.cell.startsWith('RED') ? 'RED' : 'BLUE';
  if (b.goal) return 'scored';
  return 'free';
}

function applyTint(mat, tint) {
  if (tint === 'scored') { mat.color.setHex(SCORED_COLOR); mat.emissive.setHex(SCORED_EMISSIVE); }
  else if (CAPTURED[tint]) { mat.color.setHex(CAPTURED[tint].color); mat.emissive.setHex(CAPTURED[tint].emissive); }
  else { mat.color.setHex(BALL_COLOR); mat.emissive.setHex(0x000000); }
}

export function createBalls(scene) {
  const group = new THREE.Group();
  group.name = 'balls';
  scene.add(group);

  // One unit sphere shared by every ball; each mesh scales it to its own radius.
  const geometry = new THREE.SphereGeometry(1, 20, 14);
  /** id -> { mesh, target, tint } */
  const balls = new Map();

  function makeMaterial(tint) {
    const m = new THREE.MeshStandardMaterial({ roughness: 0.35, metalness: 0.05 });
    applyTint(m, tint);
    return m;
  }

  /** Feed the state.balls array: add what is new, move what is there, drop what is gone. */
  function setState(list) {
    const seen = new Set();
    for (const b of list || []) {
      seen.add(b.id);
      const target = new THREE.Vector3(b.x, b.z, -b.y);
      const tint = tintOf(b);
      let e = balls.get(b.id);
      if (!e) {
        const mesh = new THREE.Mesh(geometry, makeMaterial(tint));
        mesh.name = 'ball-' + b.id;
        mesh.scale.setScalar(b.r || 3);
        mesh.castShadow = true;
        mesh.position.copy(target);
        group.add(mesh);
        e = { mesh, target, tint };
        balls.set(b.id, e);
      } else {
        e.target.copy(target);
        if (tint !== e.tint) {
          e.tint = tint;
          applyTint(e.mesh.material, tint);
        }
      }
    }
    for (const [id, e] of balls) {
      if (seen.has(id)) continue;
      group.remove(e.mesh);
      e.mesh.material.dispose();
      balls.delete(id);
    }
  }

  /** Called every rendered frame: catch up to where the physics says the ball is. */
  function update(dt) {
    if (!balls.size) return;
    const k = 1 - Math.exp(-30 * Math.min(dt, 0.1));
    for (const e of balls.values()) e.mesh.position.lerp(e.target, k);
  }

  function clear() {
    for (const e of balls.values()) {
      group.remove(e.mesh);
      e.mesh.material.dispose();
    }
    balls.clear();
  }

  return { setState, update, clear, count: () => balls.size };
}
