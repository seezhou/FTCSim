// FTCSim 3D robot model: procedurally generated chassis + four mecanum-style wheels.
// Pose comes from the 20 Hz "state" WebSocket message (FTC field frame, inches / degrees);
// wheel spin comes from robot.wheels = [leftFront, rightFront, leftRear, rightRear] duty cycles.
// Parts the team declared a mount for (motors, servos) are added by parts.js and animated from
// the same message's device views.
import * as THREE from 'three';
import { parseMounts, mountSignature, buildPartMesh, angleFor } from './parts.js';
import { motorColor, activityColor, IDLE_COLOR } from './activity.js';

const BODY_COLOR = 0x3d6fd9;
const WHEEL_COLOR = IDLE_COLOR;
/** How solid the chassis is drawn. Low enough to see mechanisms bolted inside the frame. */
const CHASSIS_OPACITY = 0.22;
/**
 * Opaque silhouette colour for the chassis edges; see buildBody.
 * A light neutral grey: the outline only has to say where the robot ends, not attract attention.
 * Orange (0xffa33c) was too loud against the field, and the blue it replaced (0x9fc4ff) was too
 * close to the odometry trail (0x8fb8ff) to read once the robot drove over its own path.
 */
const BODY_EDGE_COLOR = 0xc8cdd4;
const _wheelColor = new THREE.Color();
const _partColor = new THREE.Color();

export class Robot {
  constructor(scene) {
    this.group = new THREE.Group();
    this.group.name = 'robot';
    scene.add(this.group);

    // Balls carried in the magazine, drawn inside the (translucent) chassis so you can see how many
    // are left without a HUD number. Parented to the robot group, so they ride along as it drives.
    this.ammoGroup = new THREE.Group();
    this.ammoGroup.name = 'ammo';
    this.group.add(this.ammoGroup);
    this.ammoMeshes = [];
    this.ammoCapacity = 0;
    this.ammoRadius = 0;

    this.spec = { lengthIn: 18, widthIn: 17, heightIn: 6, chassisOpacity: CHASSIS_OPACITY };
    this.drive = { wheelDiameterIn: 0, trackWidthIn: 0, wheelBaseIn: 0 };
    this.wheelSpin = [0, 0, 0, 0];
    this.wheels = [];
    // declared actuator parts, keyed by device name; rebuilt only when the declared set changes
    this.mounts = new Map();
    this.parts = new Map();
    this.mountSig = '';
    this.camPitchDeg = 35;
    this.target = { x: 0, y: 0, h: 0, vx: 0, vy: 0, w: 0, t: 0, epoch: -1 };
    this.current = { x: 0, y: 0, h: 0 };
    this.onEpochChange = null;

    this.buildBody();
  }

  /** Feed the device list from the 20 Hz state message; adds or removes declared parts. */
  setDevices(devices) {
    const mounts = parseMounts(devices);
    const sig = mountSignature(mounts);
    if (sig === this.mountSig) return;
    this.mountSig = sig;
    this.mounts = mounts;
    this.buildParts();
  }

  /** Map of device name -> live values, rebuilt each frame from the state message. */
  setDeviceValues(devices) {
    this.deviceValues = new Map();
    for (const d of devices || []) this.deviceValues.set(d.name, d.values || {});
  }

  /**
   * Build one mesh per declared part. Parts are re-parented into the hierarchy their `parent`
   * names, so an intake finger mounted on a sweeping arm turns with it — the same parent-transform
   * behaviour the camera pod gets for free from being parented to `camPivot`.
   */
  buildParts() {
    for (const p of this.parts.values()) {
      p.traverse?.(o => { o.geometry?.dispose(); o.material?.dispose?.(); });
      this.group.remove(p);
    }
    this.parts.clear();

    // Attach in two passes: everything first, then re-parent. A child that names a parent which
    // has not been added yet would otherwise land on the chassis and stay there.
    for (const mount of this.mounts.values()) {
      const mesh = buildPartMesh(mount);
      this.group.add(mesh);
      this.parts.set(mount.name, mesh);
    }
    for (const mount of this.mounts.values()) {
      if (!mount.parent) continue;
      const parent = this.parts.get(mount.parent);
      const child = this.parts.get(mount.name);
      if (!parent || !child) continue;
      // the child offset was expressed in the parent group's space; re-express it there
      const p = toLocalIn(mount, parent.userData.mount);
      child.position.copy(p);
    }
  }

  buildBody() {
    const g = this.group;
    // clear previous body. Two things survive a rebuild: the camera pod, because it owns the
    // picture-in-picture camera and must not be disposed; and the declared parts, because setSpec()
    // rebuilds the body whenever the robot dimensions change and that must not throw away mounts
    // the team configured. Everything else — body, edges, wheels — is rebuilt below.
    for (const c of [...g.children]) {
      if (c === this.camPivot || c === this.ammoGroup || c.name?.startsWith('part-')) continue;
      c.traverse?.(o => { o.geometry?.dispose(); o.material?.dispose?.(); });
      g.remove(c);
    }
    this.wheels = [];

    const L = this.spec.lengthIn, W = this.spec.widthIn;
    // Chassis height comes from the configuration, so a tall robot reads as tall rather than as the
    // same box with more mechanisms floating in it. The frame sits on the ground: its bottom edge
    // rests at y=0, which is also where the wheels touch, so raising the body never floats it.
    const H = this.spec.heightIn;
    const op = this.spec.chassisOpacity;

    // The body is translucent so the parts bolted inside the robot — launcher flywheels, intake
    // servos — stay visible from outside. Plain alpha rather than transmission: a transmissive
    // material needs a background pass to refract, which is overkill for "I can see inside".
    // Translucent surfaces are drawn back-to-front, so depthWrite is off, otherwise the body would
    // paint over the mechanisms it is meant to reveal.
    const chassis = new THREE.Mesh(
      new THREE.BoxGeometry(L, H, W),
      new THREE.MeshStandardMaterial({
        color: BODY_COLOR, roughness: 0.45, metalness: 0.35,
        transparent: true, opacity: op, depthWrite: false,
      }));
    chassis.position.y = H / 2;
    chassis.castShadow = true;
    g.add(chassis);
    this.chassisMeshes = [chassis];

    // Opaque edges. Without them a translucent box reads as an abstract smudge rather than a
    // machine: the silhouette is what tells you where the robot ends. LineSegments in the same
    // geometry share the body's corners exactly, so the outline can never drift from the surface.
    const edges = new THREE.LineSegments(
      new THREE.EdgesGeometry(chassis.geometry),
      new THREE.LineBasicMaterial({ color: BODY_EDGE_COLOR, transparent: true, opacity: 0.95 }));
    edges.name = 'bodyEdge';
    edges.position.copy(chassis.position);
    g.add(edges);

    // camera pod: the yellow cone that marks where the AprilTag camera looks.
    // Built here (not as a child of `g`) so it survives buildBody()'s clear, and so
    // the pitch angle set from the UI is preserved across a robot-spec rebuild.
    this.buildCameraPod(L);

    // Four wheels (mecanum order: LF RF LR RR); axis across the chassis (three Z).
    // Geometry comes from the robot configuration's drivetrain block when the backend reports it,
    // so the wheels sit where the team actually put them rather than at fixed offsets. A missing
    // or nonsensical value falls back to the old proportions instead of drawing a broken wheel.
    const d = this.drive;
    const wr = d.wheelDiameterIn > 0.5 && d.wheelDiameterIn < 8 ? d.wheelDiameterIn / 2 : 2.1;
    const ww = 1.6;
    const halfTrack = d.trackWidthIn > 1 && d.trackWidthIn < 60 ? d.trackWidthIn / 2 : W / 2 - 1.4;
    const halfBase = d.wheelBaseIn > 1 && d.wheelBaseIn < 60 ? d.wheelBaseIn / 2 : L / 2 - 3;
    const wheelGeom = new THREE.CylinderGeometry(wr, wr, ww, 20);
    // One material per wheel: each takes its colour from its own duty cycle, so a mecanum wheel
    // driving forward while another is stowed shows the difference immediately.
    const capMat = new THREE.MeshStandardMaterial({ color: 0x4f8cff, roughness: 0.5, metalness: 0.3 });
    // local frame is +X forward, +Y up, +Z right; robot "left" is -Z, so +halfTrack is the right side
    const offsets = [
      [halfBase, halfTrack], [halfBase, -halfTrack],
      [-halfBase, halfTrack], [-halfBase, -halfTrack],
    ];
    this.wheelMats = [];
    for (const [fx, fz] of offsets) {
      const holder = new THREE.Group();
      holder.position.set(fx, wr, fz);
      const wheelMat = new THREE.MeshStandardMaterial({ color: WHEEL_COLOR, roughness: 0.85 });
      this.wheelMats.push(wheelMat);
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
    // Default pod height rides on the chassis rather than sitting at a fixed 12.2 in, which was
    // tuned for the old 6 in frame plus its decorative upper deck. With a single body the pod sits
    // just above the roof, so it stays clear of the chassis at any configured height.
    const podY = this.spec.heightIn + 1.2;
    if (this.camPivot) {
      if (this.camMount) { this.setCamMount(this.camMount); return; }   // keep the team mount
      this.camPivot.position.set(L / 2 - 1.2, podY, 0);
      this.camPivot.rotation.set(0, 0, THREE.MathUtils.degToRad(this.camPitchDeg), 'ZYX');
      return;
    }
    const pivot = new THREE.Group();
    pivot.name = 'camPivot';
    pivot.position.set(L / 2 - 1.2, podY, 0);
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
    // A zero or absent height would collapse the body to a plane; fall back rather than draw it.
    const h = +robotMsg.heightIn;
    const op = +robotMsg.chassisOpacity;
    this.spec = {
      lengthIn: robotMsg.lengthIn || 18,
      widthIn: robotMsg.widthIn || 17,
      heightIn: h > 0 ? h : 6,
      // opacity 0 would make the body invisible and 1 would hide the mechanisms; clamp to a band
      // that still reads as a solid object while letting the parts inside show through.
      chassisOpacity: op > 0.05 && op < 1 ? op : CHASSIS_OPACITY,
    };
    // drivetrain geometry rides along on the same message (Simulator puts config.drivetrain there)
    const dt = robotMsg.drivetrain || {};
    this.drive = {
      wheelDiameterIn: +dt.wheelDiameterIn || 0,
      trackWidthIn: +dt.trackWidthIn || 0,
      wheelBaseIn: +dt.wheelBaseIn || 0,
    };
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
      const mat = this.wheelMats?.[i];
      if (mat) mat.color.copy(motorColor(p, _wheelColor));
    }

    this.updateParts(dt);
  }

  /**
   * Spin and sweep the declared parts from the live device values.
   *
   * A motor integrates its *physical* rpm; a servo jumps straight to its commanded angle because
   * it has no velocity to integrate and easing it would misreport where the mechanism is. Both are
   * coloured from what the robot code asked for rather than what the hardware achieved — see the
   * branches below for why those differ.
   */
  updateParts(dt) {
    if (!this.parts.size) return;
    const values = this.deviceValues;
    for (const [name, obj] of this.parts) {
      const mount = obj.userData.mount;
      const joint = obj.userData.joint;
      const v = values?.get(name);
      if (!joint) continue;
      if (mount.sweeps) {
        // servo: position 0..1 across the declared sweep
        const pos = v ? (v.position != null ? +v.position : +v.commanded || 0) : 0;
        joint.rotation.z = THREE.MathUtils.degToRad(angleFor(mount, pos));
        this.tintPart(obj, pos);
      } else {
        // Motor: rotate at the *physical* speed. `rpm` comes from MotorState.physicalOmega(),
        // which already carries the sign of the real rotation — the SDK inverts power and encoder
        // sign for CCW motor types (goBILDA, REV HD Hex), so `duty` alone would spin a goBILDA
        // flywheel backwards. Integrating rpm rather than duty also keeps the visual speed honest
        // when the motor is stalled or held by its load.
        const rpm = v ? (+v.rpm || 0) : 0;
        obj.userData._spin = (obj.userData._spin || 0) + rpm * (Math.PI / 30) * dt;
        joint.rotation.z = obj.userData._spin;
        // Colour by power, not rpm: a jammed flywheel spins slowly but is still being told to
        // drive hard, and the colour should say what the code asked for.
        this.tintPart(obj, v ? +v.duty || 0 : 0);
      }
    }
  }

  /**
   * Recolour a part's materials from its current activity. Motors go green/red by power, servos
   * brighten along amber by position — see activity.js for why the two encodings differ.
   */
  tintPart(obj, value) {
    const tinted = obj.userData.tinted;
    if (!tinted || !tinted.length) return;
    const mount = obj.userData.mount;
    // base 0 is the part's own colour, used when a servo sits at its rest position
    activityColor(mount, value, tinted[0].base, _partColor);
    for (const t of tinted) t.mat.color.copy(_partColor);
  }

  position() { return this.group.position.clone(); }
  heading() { return this.current.h; }

  /**
   * Show `remaining` of `capacity` balls sitting inside the chassis, replacing the HUD
   * "remaining/capacity" text pill: you can now read the magazine at a glance through the
   * translucent body. Firing drops `remaining`, reloading refills it; this just mirrors that count.
   */
  setAmmo(remaining, capacity, radiusIn) {
    if (!capacity) { this.ammoGroup.visible = false; return; }
    this.ammoGroup.visible = true;
    const r = radiusIn > 0.5 && radiusIn < 9 ? radiusIn : 3.0;
    if (capacity !== this.ammoCapacity || r !== this.ammoRadius) {
      for (const m of this.ammoMeshes) { m.geometry.dispose(); m.material.dispose(); this.ammoGroup.remove(m); }
      this.ammoMeshes = [];
      this.ammoCapacity = capacity;
      this.ammoRadius = r;
      const L = this.spec.lengthIn, W = this.spec.widthIn, H = this.spec.heightIn;
      const spots = ammoLayout(capacity, r, L, W, H);
      for (let i = 0; i < capacity; i++) {
        const mesh = new THREE.Mesh(
          new THREE.SphereGeometry(r, 16, 12),
          new THREE.MeshStandardMaterial({ color: 0xf2f4f8, roughness: 0.4, metalness: 0.05 }));
        mesh.position.copy(spots[i]);
        mesh.castShadow = true;
        mesh.visible = i < remaining;
        this.ammoGroup.add(mesh);
        this.ammoMeshes.push(mesh);
      }
    } else {
      for (let i = 0; i < this.ammoMeshes.length; i++) this.ammoMeshes[i].visible = i < remaining;
    }
  }
}

function angleDelta(from, to) {
  let d = (to - from) % (Math.PI * 2);
  if (d > Math.PI) d -= Math.PI * 2;
  if (d < -Math.PI) d += Math.PI * 2;
  return d;
}

/**
 * Cluster `n` balls (radius `r`) in a tight grid at the CENTRE of the chassis — not spread out to the
 * wheels. Balls just clear each other (spacing ~2r) and the whole cluster is centred on the robot
 * origin, so they read as "carried in the middle of the car". Robot-local frame: +X forward (length L),
 * +Z right (width W), +Y up (height H); the cluster sits at mid-height. Returns an array of Vector3.
 */
function ammoLayout(n, r, L, W, H) {
  const cols = Math.ceil(Math.sqrt(n));
  const rows = Math.ceil(n / cols);
  const step = 2 * r + 0.2;   // balls just touch, kept in one central clump
  const x0 = -((cols - 1) * step) / 2;
  const z0 = -((rows - 1) * step) / 2;
  const y = H / 2;
  const out = [];
  for (let i = 0; i < n; i++) {
    const c = i % cols, rw = Math.floor(i / cols);
    out.push(new THREE.Vector3(x0 + c * step, y, z0 + rw * step));
  }
  return out;
}

/**
 * A part's offset expressed in its parent's local space. Both poses are robot-frame inches, so
 * the difference is taken there and only then mapped into this scene's axes — subtracting
 * scene-space points would be wrong, because the two frames are not parallel once a parent is
 * already rotated.
 */
function toLocalIn(mount, parentMount) {
  return new THREE.Vector3(mount.x - parentMount.x, mount.z - parentMount.z, -(mount.y - parentMount.y));
}
