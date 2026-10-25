package ftcsim.physics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Balls fired into an upward CELL are captured into the box, and enough of them tip the HIVE. */
public class HiveTipTest {
    private static World biobuzzWorld() {
        World w = new World();
        w.field = Field.biobuzz();
        return w;
    }

    /**
     * Fires a ball straight into a CELL's mouth from just outside it. The mouth normal points out
     * of the box, so "in" is -facing from the mouth plane.
     */
    private static Projectile fireInto(World w, String cellName, double speed) {
        Field.Cluster cl = w.field.cluster(cellName);
        double[] f = cl.facing();
        double d = BiobuzzRules.CELL_DEPTH_IN / 2 + 6;
        return w.spawnProjectile(cl.x + f[0] * d, cl.y + f[1] * d, cl.z + f[2] * d,
                                 -f[0] * speed, -f[1] * speed, -f[2] * speed, 1.4);
    }

    /** Fires a ball at the CLOSED back of a CELL (from the field centre side). */
    private static Projectile fireAtBack(World w, String cellName, double speed) {
        Field.Cluster cl = w.field.cluster(cellName);
        double[][] ax = cl.axes();
        double[] dep = ax[2];                       // mouth -> back
        double d = BiobuzzRules.CELL_DEPTH_IN / 2 + 6;
        return w.spawnProjectile(cl.x + dep[0] * d, cl.y + dep[1] * d, cl.z + dep[2] * d,
                                 -dep[0] * speed, -dep[1] * speed, -dep[2] * speed, 1.4);
    }

    /** Box-local depth of a ball, measured from the CELL's mouth plane (negative = in front). */
    private static double depth(World w, Projectile p, String cellName) {
        return w.field.cluster(cellName).local(p.x, p.y, p.z)[2];
    }

    private static void run(World w, Projectile p, int steps) {
        for (int i = 0; i < steps && p.capturedIn == null; i++) w.step();
    }

    @Test public void aBallEnteringTheUpCellIsCapturedNotScored() {
        World w = biobuzzWorld();
        Projectile p = fireInto(w, "RED AUDIENCE", 100);   // the red upward CELL at the start
        run(w, p, 500);
        assertEquals("RED AUDIENCE", p.capturedIn, "the ball is riding in the CELL");
        assertEquals(Projectile.State.CAPTURED, p.state);
        assertTrue(w.scoreEvents.isEmpty(), "a captured ball shows no score");
        // the ball sits inside the box: at the CELL's height, not on the floor
        assertTrue(p.z > 53.5 && p.z < 65.6, "captured ball inside the raised CELL mouth, at " + p.z);
        assertTrue(w.capturedCount() == 1);
    }

    @Test public void enoughCapturedBallsTipTheHiveAndSpillThem() {
        World w = biobuzzWorld();
        for (int i = 0; i < BiobuzzRules.HIVE_TIP_BALLS; i++) {
            Projectile p = fireInto(w, "RED AUDIENCE", 100);
            run(w, p, 500);
        }
        assertEquals("scoring", w.field.option("redHive"), "the HIVE tipped to its other state");
        assertEquals("scoring", w.field.option("blueHive"), "the blue HIVE is independent (and starts tipped)");
        assertEquals("RED", w.tipEvents.poll());
        assertEquals(1, w.tipCounts.get("RED"));
        // the CELLs moved, so the UI has to be handed the field again or the 3D hive stays put
        assertTrue(w.takeFieldChanged(), "a tip marks the field as changed for the UI");
        assertFalse(w.takeFieldChanged(), "and only once");
        for (Projectile p : w.projectiles) {
            assertNull(p.capturedIn, "the tipped-out balls pour from the mouth");
            assertEquals(Projectile.State.FLIGHT, p.state);
        }
        // the spilled balls fall to the floor as litter rather than vanishing
        for (int i = 0; i < 2000; i++) w.step();
        for (Projectile p : w.projectiles) assertTrue(p.z >= 1.39, "a spilled ball rests on the floor");
    }

    @Test public void aBallThroughTheLowerLipOfTheMouthIsStillCaptured() {
        World w = biobuzzWorld();
        Field.Cluster cl = w.field.cluster("RED AUDIENCE");
        double[][] ax = cl.axes();
        double d = BiobuzzRules.CELL_DEPTH_IN / 2 + 6;
        // aimed at the bottom lip of the mouth: 8 in below the centre, well outside the
        // axis-aligned hit volume that used to decide a capture
        Projectile p = w.spawnProjectile(
            cl.x - ax[2][0] * d - ax[1][0] * 8, cl.y - ax[2][1] * d - ax[1][1] * 8, cl.z - ax[2][2] * d - ax[1][2] * 8,
            ax[2][0] * 100, ax[2][1] * 100, ax[2][2] * 100, 1.4);
        run(w, p, 500);
        assertEquals("RED AUDIENCE", p.capturedIn, "the whole mouth opening takes balls in");
    }

    @Test public void aBallHittingAClosedFaceBouncesOffIt() {
        World w = biobuzzWorld();
        Projectile p = fireAtBack(w, "RED AUDIENCE", 100);
        for (int i = 0; i < 400; i++) w.step();
        assertNull(p.capturedIn, "the back of the box is not a way in");
        double halfD = BiobuzzRules.CELL_DEPTH_IN / 2;
        assertTrue(depth(w, p, "RED AUDIENCE") > halfD,
            "the ball stayed behind the back face instead of passing through");
        assertTrue(w.capturedCount() == 0);
    }

    @Test public void aBallMissingTheMouthIsNotCaptured() {
        World w = biobuzzWorld();
        // straight up past the mouth, well above the apex of the pentagon
        Field.Cluster cl = w.field.cluster("RED AUDIENCE");
        Projectile p = w.spawnProjectile(cl.x, cl.y, cl.z + 20, 0, 0, -60, 1.4);
        for (int i = 0; i < 400; i++) w.step();
        assertNull(p.capturedIn);
        assertTrue(w.capturedCount() == 0);
    }

    @Test public void theDownwardCellDoesNotCapture() {
        World w = biobuzzWorld();
        Projectile p = fireInto(w, "RED SCORING", 100);    // faces the floor at the start
        run(w, p, 500);
        assertNull(p.capturedIn, "a ball cannot be captured through a closed face");
        assertTrue(w.scoreEvents.isEmpty());
        assertTrue(w.capturedCount() == 0);
    }

    @Test public void decodeGoalsStillScore() {
        World w = biobuzzWorld();
        w.field = Field.decode();
        Projectile p = w.spawnProjectile(-60, 60, 30, -20, 0, 0, 1.4);
        for (int i = 0; i < 2000 && w.scoreEvents.isEmpty(); i++) w.step();
        List<String> ev = List.copyOf(w.scoreEvents);
        assertEquals(1, ev.size());
        assertEquals("Red goal", ev.get(0));
        assertNull(p.capturedIn);
    }

    @Test public void clearingTheFieldResetsTheHives() {
        World w = biobuzzWorld();
        for (int i = 0; i < BiobuzzRules.HIVE_TIP_BALLS; i++) {
            Projectile p = fireInto(w, "RED AUDIENCE", 100);
            run(w, p, 500);
        }
        assertEquals("scoring", w.field.option("redHive"));
        w.clearProjectiles();
        assertTrue(w.takeFieldChanged(), "putting the HIVEs back is a field change too");
        assertEquals("audience", w.field.option("redHive"), "a fresh match starts untipped");
        assertEquals("scoring", w.field.option("blueHive"));
        assertTrue(w.tipCounts.isEmpty());
        assertTrue(w.capturedCount() == 0);
    }
}
