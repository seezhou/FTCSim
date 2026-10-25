package ftcsim.physics;

/**
 * DECODE (2025-26): two corner goals score a ball, and the obelisk has three alternative tag
 * faces of which only the selected one is on the field.
 */
public final class DecodeRules implements SeasonRules {
    @Override
    public boolean isTagVisible(Field field, Field.AprilTag tag) {
        if (tag.id < 21 || tag.id > 23) return true;
        return String.valueOf(tag.id).equals(field.option("obelisk"));
    }
}
