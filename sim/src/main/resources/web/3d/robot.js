// FTCSim 3D robot model: procedurally generated chassis + four mecanum-style wheels.
// Pose comes from the 20 Hz "state" WebSocket message (FTC field frame, inches / degrees);
// wheel spin comes from robot.wheels = [leftFront, rightFront, leftRear, rightRear] duty cycles.
import * as THREE from 'three';

const BODY_COLOR = 0x3d6fd9;
const WHEEL_COLOR = 0x14171c;

export class Robot {
  constructor(scene) {
    this.group = new THREE.Group();
    this.group.name = 'robot';
    scene.add(this.group);

    this.spec = { lengthIn: 18, widthIn: 17 };
    this.wheelSpin = [0, 0, 0, 0];
    this.wheels = [];
    this.camPitchDeg = 35;
    this.target = { x: 0, y: 0, h: 0, vx: 0, vy: 0, w: 0, t: 0, epoch: -1 };
    this.current = { x: 0, y: 0, h: 0 };
    this.onEpochChange = null;

    this.buildBody();
  }

  buildBody() {
    const g = this.group;
    // clear previous body (but keep group). The camera pod is skipped: it owns the
    // picture-in-picture camera, so it must survive a rebuild intact (and undisposed).
    for (const c of [...g.children]) {
      if (c === this.camPivot) continue;
      c.traverse?.(o => { o.geometry?.dispose(); o.material?.dispose?.(); });
      g.remove(c);
    }
    this.wheels = [];

    const L = this.spec.lengthIn, W = this.spec.widthIn;

    // chassis: two decks for a bit of visual depth
    const chassis = new THREE.Mesh(
      new THREE.BoxGeometry(L, 6, W),
      new THREE.MeshStandardMaterial({ color: BODY_COLOR, roughness: 0.45, metalness: 0.35 }));
    chassis.position.y = 5;
    chassis.castShadow = true;
    g.add(chassis);

    const deck = new THREE.Mesh(
      new THREE.BoxGeometry(L * 0.8, 2.5, W * 0.8),
      new THREE.MeshStandardMaterial({ color: 0x2b3a55, roughness: 0.6, metalness: 0.2 }));
    deck.position.y = 9.2;
    deck.castShadow = true;
    g.add(deck);

    // camera pod: the yellow cone that marks where the AprilTag camera looks.
    // Built here (not as a child of `g`) so it survives buildBody()'s clear, and so
    // the pitch angle set from the UI is preserved across a robot-spec rebuild.
    this.buildCameraPod(L);

    // four wheels (mecanum order: LF RF LR RR); axis across the chassis (three Z)
    const wr = 2.1, ww = 1.6;
    const wheelGeom = new THREE.CylinderGeometry(wr, wr, ww, 20);
    const wheelMat = new THREE.MeshStandardMaterial({ color: WHEEL_COLOR, roughness: 0.85 });
    const capMat = new THREE.MeshStandardMaterial({ color: 0x4f8cff, roughness: 0.5, metalness: 0.3 });
    const offsets = [
      [L / 2 - 3, W / 2 - 1.4], [L / 2 - 3, -(W / 2 - 1.4)],
      [-(L / 2 - 3), W / 2 - 1.4], [-(L / 2 - 3), -(W / 2 - 1.4)],
    ];
    for (const [fx, fz] of offsets) {
      const holder = new THREE.Group();
      holder.position.set(fx, wr, fz);
      const wheel = new THREE.Mesh(wheelGeom, wheelMat);
      wheel.rotation.x = Math.PI / 2;          // axle along the holder's Z
      wheel.castShadow = true;
      holder.add(wheel);
      // small hub marker so the spin is visible
      const cap = new THREE.Mesh(new THREE.BoxGeometry(ww * 0.7, wr * 1.15, 0.5), capMat);
      cap.position.z = 0;
      holder.add(cap);
      g.add(holder);
      this.wheels.push(holder);
    }
  }

  /** The on-board AprilTag camera: a yellow cone on a pitch pivot at the front centre
   *  of the deck. The pivot rotates about the robot's local Z, which tilts the cone in
   *  the robot's vertical plane (+pitch = look up). The picture-in-picture camera in
   *  webcam.js is parented to this pivot, so it inherits the pose for free. */
  buildCameraPod(L) {
    if (this.camPivot) {
      if (this.camMount) { this.setCamMount(this.camMount); return; }   // keep the team mount
      this.camPivot.position.set(L / 2 - 1.2, 12.2, 0);
      this.camPivot.rotation.set(0, 0, THREE.MathUtils.degToRad(this.camPitchDeg), 'ZYX');
      return;
    }
    const pivot = new THREE.Group();
    pivot.name = 'camPivot';
    pivot.position.set(L / 2 - 1.2, 12.2, 0);
    this.group.add(pivot);

    const cone = new THREE.Mesh(
      new THREE.ConeGeometry(1.7, 6, 20),
      new THREE.MeshStandardMaterial({ color: 0xffd166, roughness: 0.4, emissive: 0x6b5210 }));
    cone.rotation.z = -Math.PI / 2;      // cone axis +Y -> robot's +X (forward)
    cone.position.x = 3.4;
    cone.castShadow = true;
    pivot.add(cone);

    // dark lens barrel at the base so the cone reads as a camera, not an arrow
    const lens = new THREE.Mesh(
      new THREE.CylinderGeometry(2.1, 2.1, 3, 20),
      new THREE.MeshStandardMaterial({ color: 0x14171c, roughness: 0.5, metalness: 0.4 }));
    lens.rotation.z = -Math.PI / 2;
    lens.position.x = 0.2;
    lens.castShadow = true;
    pivot.add(lens);

    const cap = new THREE.Mesh(
      new THREE.CircleGeometry(1.5, 20),
      new THREE.MeshStandardMaterial({ color: 0x7fb0ff, roughness: 0.2, metalness: 0.6 }));
    cap.rotation.y = Math.PI / 2;
    cap.position.x = 1.75;
    pivot.add(cap);

    pivot.rotation.z = THREE.MathUtils.degToRad(this.camPitchDeg);
    this.camPivot = pivot;
  }

  /** Tilt the camera pod. Positive looks up. */
  setCamPitch(deg) {
    this.camPitchDeg = deg;
    if (this.camPivot) this.camPivot.rotation.z = THREE.MathUtils.degToRad(deg);
  }

  /** Place and aim the pod from the team code's camera mount (ftcsim.vision.CameraMount:
   *  robot frame x forward, y left, z up, degrees). This makes the pod the team's actual
   *  Webcam rather than a decoration, so the PiP view matches the running program. */
  setCamMount(m) {
    if (!m) return;
    this.camMount = m;
    this.camPitchDeg = m.pitchDeg;
    if (!this.camPivot) this.buildCameraPod(this.spec.lengthIn);
    // three local: +X forward, +Y up, +Z right  =>  FTC "y left" is local -Z.
    // YXZ so the pitch happens in the yawed frame, the way a real mount tilts.
    this.camPivot.position.set(m.xIn, m.zIn, -m.yIn);
    this.camPivot.rotation.order = 'YXZ';
    this.camPivot.rotation.x = 0;
    this.camPivot.rotation.y = THREE.MathUtils.degToRad(m.yawDeg);
    this.camPivot.rotation.z = THREE.MathUtils.degToRad(m.pitchDeg);
  }

  setSpec(robotMsg) {
    if (!robotMsg) return;
    this.spec = { lengthIn: robotMsg.lengthIn || 18, widthIn: robotMsg.widthIn || 17 };
    this.buildBody();
    // snap to the reported start pose once the geometry exists
    if (robotMsg.startPose) {
      const p = robotMsg.startPose;
      this.current = { x: p.x || 0, y: p.y || 0, h: (p.headingDeg || 0) * Math.PI / 180 };
    }
  }

  /** Feed a state.robot message. */
  setState(r) {
    this.target.x = r.x; this.target.y = r.y;
    this.target.h = THREE.MathUtils.degToRad(r.headingDeg);
    this.target.vx = r.vx || 0; this.target.vy = r.vy || 0;
    this.target.w = THREE.MathUtils.degToRad(r.omegaDeg || 0);
    this.target.t = performance.now();
    this.target.wheelPowers = r.wheels || [];
    if (r.epoch !== undefined && r.epoch !== this.target.epoch) {
      const first = this.target.epoch === -1;
      this.target.epoch = r.epoch;
      if (!first && this.onEpochChange) this.onEpochChange();
    }
  }

  /** Called each animation frame: smooth toward the (extrapolated) target pose. */
  update(dt) {
    // extrapolate the last measurement by velocity (cap the horizon)
    const age = Math.min((performance.now() - this.target.t) / 1000, 0.12);
    const c = Math.cos(this.target.h), s = Math.sin(this.target.h);
    const tx = this.target.x + (this.target.vx * c - this.target.vy * s) * age;
    const ty = this.target.y + (this.target.vx * s + this.target.vy * c) * age;
    const th = this.target.h + this.target.w * age;

    const k = 1 - Math.exp(-14 * dt);   // critically-damped-ish follow
    this.current.x += (tx - this.current.x) * k;
    this.current.y += (ty - this.current.y) * k;
    this.current.h += angleDelta(this.current.h, th) * k;

    this.group.position.set(this.current.x, 0, -this.current.y);
    this.group.rotation.y = this.current.h;

    // wheel spin from duty cycle
    const powers = this.target.wheelPowers || [];
    for (let i = 0; i < this.wheels.length; i++) {
      const p = powers[i] || 0;
      this.wheelSpin[i] += p * 22 * dt;      // rad/s at full power
      this.wheels[i].rotation.z = this.wheelSpin[i];
    }
  }

  position() { return this.group.position.clone(); }
  heading() { return this.current.h; }
}

function angleDelta(from, to) {
  let d = (to - from) % (Math.PI * 2);
  if (d > Math.PI) d -= Math.PI * 2;
  if (d < -Math.PI) d += Math.PI * 2;
  return d;
}
