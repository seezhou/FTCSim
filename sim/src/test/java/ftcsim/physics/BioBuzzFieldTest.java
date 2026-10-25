package ftcsim.physics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The BIOBUZZ field: two tipping HIVEs whose CELLs carry the AprilTag clusters. */
public class BioBuzzFieldTest {
    private static Field.Cluster cluster(Field f, String name) {
        for (Field.Cluster c : f.clusters) if (c.name.equals(name)) return c;
        throw new AssertionError("no cluster " + name + " on the field");
    }

    @Test public void theFieldCarriesTheFourCellClusters() {
        Field f = Field.biobuzz();
        assertEquals("BIOBUZZ", f.name);
        assertEquals(4, f.clusters.size());
        assertEquals(16, f.tags.size(), "four tags per CELL");
        int[] ids = f.tags.stream().mapToInt(t -> t.id).sorted().toArray();
        for (int i = 0; i < 16; i++) assertEquals(30 + i, ids[i], "BIOBUZZ uses tags 30-45");
        for (Field.AprilTag t : f.tags) assertEquals(3.25, t.sizeIn, 1e-9);
    }

    @Test public void theUpwardCellSitsAtThePublishedHeight() {
        Field f = Field.biobuzz();
        // at the start the red HIVE has its audience CELL up and the blue HIVE its scoring CELL
        Field.Cluster redUp = cluster(f, "RED AUDIENCE"), redDown = cluster(f, "RED SCORING");
        assertTrue(redUp.z > redDown.z, "the upward CELL is higher than the downward one");
        assertEquals(30, redUp.pitchDeg, 1e-9, "the arm is tilted 30 degrees");
        assertEquals(-30, redDown.pitchDeg, 1e-9);
        assertTrue(redUp.x > 0, "the red audience CELL is on the audience (+X) side");
        assertEquals(-12.75, redUp.y, 1e-9, "the red HIVE pivot is 12.75 in from the centre");
        assertEquals(12.75, cluster(f, "BLUE SCORING").y, 1e-9);
        // the raised CELL mouth runs from 53.5 in to 65.6 in (manual values), so its centre - the cluster
        // origin the SDK puts "in the centre of the Cell opening" - is at 59.55 in
        assertEquals((53.5 + 65.6) / 2, redUp.z, 0.2, "the raised CELL opening centre");
        // The cluster is a sticker on the CELL's bottom face, not inside the opening: Figure 9-15
        // puts its centre 9.938 - 2.75 in below the front of the CELL, which at the 30° tilt is a
        // vertical drop of (9.938 - 2.75) * cos(30°). The tags sit just under the 53.5 in lip.
        double bottomFaceDrop = (9.938 - 2.75) * Math.cos(Math.toRadians(30));
        for (double[] off : redUp.memberOffsets) {
            assertEquals(redUp.z - bottomFaceDrop, redUp.memberPosition(off)[2], 1e-3,
                "each tag is on the CELL's bottom face");
        }
        for (double[] off : redDown.memberOffsets) {
            double z = redDown.memberPosition(off)[2];
            assertTrue(z > 29 && z < 45, "the lowered CELL clears a 29 in robot driving under it, tag at " + z);
        }
    }

    @Test public void tippingAHiveSwapsWhichCellIsUp() {
        Field f = Field.biobuzz();
        double upBefore = cluster(f, "RED AUDIENCE").z, downBefore = cluster(f, "RED SCORING").z;
        assertTrue(f.setOption("redHive", "scoring"));
        double upAfter = cluster(f, "RED SCORING").z, downAfter = cluster(f, "RED AUDIENCE").z;
        assertEquals(upBefore, upAfter, 1e-6, "the raised CELL is now the scoring one, at the same height");
        assertEquals(downBefore, downAfter, 1e-6);
        assertEquals("scoring", f.option("redHive"));
        assertFalse(f.setOption("redHive", "sideways"), "unknown values are rejected");
        assertFalse(f.setOption("nosuch", "audience"));
        // the blue HIVE is independent
        assertEquals("scoring", f.option("blueHive"));
    }

    @Test public void clusterMembersAreSpacedAcrossTheCellMouth() {
        Field.Cluster c = cluster(Field.biobuzz(), "BLUE SCORING");
        assertEquals(4, c.memberIds.length);
        double[] first = c.memberPosition(c.memberOffsets[0]), last = c.memberPosition(c.memberOffsets[3]);
        double span = Math.hypot(first[0] - last[0], first[1] - last[1]);
        assertEquals(13.0, span, 0.5, "the outer tags are 13 in apart across the CELL");
        assertEquals(first[2], last[2], 1e-6, "the cluster is co-planar");
    }

    @Test @SuppressWarnings("unchecked")
    public void onlyBioBuzzSendsCellGeometryToTheUi() {
        Map<String, Object> extra = Field.biobuzz().rules().extraFieldData();
        Map<String, Object> geom = (Map<String, Object>) extra.get("cellGeometry");
        assertNotNull(geom, "the 3D view needs the CELL geometry");
        assertEquals(BiobuzzRules.CELL_DEPTH_IN, geom.get("depth"));
        assertEquals(BiobuzzRules.CELL_BOTTOM_IN, geom.get("bottom"));
        assertTrue(Field.decode().rules().extraFieldData().isEmpty(), "DECODE has no CELLs to draw");
    }

    @Test public void decodeStillWorksAndFieldsAreSelectedByName() {
        assertEquals("DECODE", Field.byName("decode").name);
        assertEquals("BIOBUZZ", Field.byName("BioBuzz").name);
        assertEquals("Empty", Field.byName("empty").name);
        assertEquals("DECODE", Field.byName(null).name);
        Field d = Field.decode();
        assertEquals("21", d.option("obelisk"));
        assertTrue(d.setOption("obelisk", "23"));
        assertEquals("23", d.option("obelisk"));
    }
}
