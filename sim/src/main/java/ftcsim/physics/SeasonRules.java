package ftcsim.physics;

import java.util.Map;

/**
 * The season-specific behaviour of a field: how its movable elements stop or hold a launched
 * ball, when a target scores, and how a fresh match resets them. The engine calls these; a
 * field with no movable elements uses {@link #NONE}.
 */
public interface SeasonRules {
    /** Rules for a field whose elements are all static. */
    SeasonRules NONE = new SeasonRules() {};

    /** Ball-versus-season-element collision, run after the generic obstacle pass. */
    default void collide(Projectile p, Field field) {}

    /**
     * Advances a ball riding inside a season element. Returns true while the element still holds
     * it; false lets normal flight resume this step.
     */
    default boolean ride(Projectile p, Field field) { return false; }

    /** Per-step bookkeeping once the ball has moved and collided (captures, triggers). */
    default void afterStep(Projectile p, World world) {}

    /** True when a goal volume with this name is a real scoring target, not a season container. */
    default boolean isScoringGoal(String goalName, Field field) { return true; }

    /**
     * True when an AprilTag is physically present for vision and for the UI. A season can place
     * alternative faces of one object at the same pose and show only the selected one.
     */
    default boolean isTagVisible(Field field, Field.AprilTag tag) { return true; }

    /** Points a season scoring event is worth (a BIOBUZZ HIVE tip is 20; most events are 0). */
    default int tipPoints() { return 0; }

    /** Human-readable label for a season scoring event, for the UI toast. */
    default String scoreEventLabel(String event) { return event; }

    /** Puts the movable elements back to their match-start state. */
    default void resetFieldState(Field field) {}

    /** Extra season geometry for the UI, merged into the field message. */
    default Map<String, Object> extraFieldData() { return Map.of(); }
}
