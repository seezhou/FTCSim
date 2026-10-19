// FTCSim 3D — activity colouring, shared by every motor-driven part.
//
// The 2D view has always shown drive power as signed bars, green forward and red back. This is the
// same idea for 3D: a part's colour says what its motor is doing, so you can see the whole robot's
// state at a glance without reading numbers.
//
// Two encodings, because the two kinds of actuator mean different things:
//
//   MOTOR — signed power. Hue carries direction (green forward, red backward) and lightness
//           carries magnitude, so a wheel at full power and one at a quarter read differently at a
//           glance. Hue must stay fixed or "more power" would look like "different direction".
//
//   SERVO — position, 0..1 across its declared travel. A servo has no forward/backward: its value
//           is where it is in its sweep, so amber-to-lightness says "how far along", and painting
//           it red would wrongly imply it is driving in reverse.
//
// A part at rest keeps its own material colour rather than turning grey. Grey is meaningful for a
// wheel — it says "idle" — but recolouring a whole mechanism every frame would destroy the shape
// cues the colours otherwise provide.
import { Color } from 'three';

/** Hue for "driving forward". ~0.33 on the colour wheel. */
const MOTOR_FWD_HUE = 0.33;
/** Hue for "driving backward". ~0.00. */
const MOTOR_REV_HUE = 0.00;
/**
 * Hue for a servo's position: gold, deliberately not green or red (a servo is positioned, not
 * driving) and a shade off the chassis edge colour 0xffa33c, so an extended intake finger does not
 * read as part of the robot's outline.
 */
const SERVO_HUE = 0.135;

/** Below this the reading is noise, not intent. */
const DEAD_ZONE = 0.06;

/** Dark enough to read as idle, light enough to stay visible on the field. */
export const IDLE_COLOR = 0x14171c;

const _c = new Color();

/** Colour for a motor at the given signed power, -1..1. */
export function motorColor(power, out = _c) {
  const p = Math.max(-1, Math.min(1, power || 0));
  if (Math.abs(p) < DEAD_ZONE) return out.setHex(IDLE_COLOR);
  const hue = p > 0 ? MOTOR_FWD_HUE : MOTOR_REV_HUE;
  return out.setHSL(hue, 0.68, 0.16 + 0.42 * Math.abs(p));
}

/**
 * Colour for a servo at the given position 0..1.
 *
 * `restColor` is the part's own colour, used at position 0 so the part still looks like itself
 * when parked. Anything above that brightens along amber, which keeps "servo" visually distinct
 * from "motor driving".
 */
export function servoColor(position, restColor, out = _c) {
  const p = Math.max(0, Math.min(1, position || 0));
  if (p <= DEAD_ZONE) return out.setHex(restColor);
  return out.setHSL(SERVO_HUE, 0.75, 0.42 + 0.28 * p);
}

/**
 * Pick the right encoding for a part and return the colour.
 *
 * `mount.sweeps` is what distinguishes them: it is set when the configuration declared a servo
 * travel, which is exactly the case where the value is a position rather than a power. Centralised
 * here so a caller never has to re-derive it, and so the two encodings stay in step.
 */
export function activityColor(mount, value, restColor, out = _c) {
  return mount.sweeps ? servoColor(value, restColor, out) : motorColor(value, out);
}