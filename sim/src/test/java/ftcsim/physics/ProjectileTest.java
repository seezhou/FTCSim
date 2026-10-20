package ftcsim.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ball model: it falls, it bounces off the floor and the perimeter, it rolls to a stop, and it
 * scores when it reaches a goal. Units are inches and seconds throughout, like the rest of the
 * physics package.
 */
public class ProjectileTest {
    private static final double DT = 0.001;
    private static final double R = 3.0;

    private static Projectile ball(double x, double y, double z, double vx, double vy, double vz) {
        return new Projectile(x, y, z, vx, vy, vz, R);
    }

    private static void run(Projectile p, Field f, double seconds) {
        int steps = (int) Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) p.step(DT, f);
    }

    @Test
    public void gravityBringsItDown() {
        Projectile p = ball(0, 0, 40, 0, 0, 0);
        run(p, Field.decode(), 0.2);
        // 0.2 s of gravity is 0.5 * g * t² = 7.7 in, less a little for drag
        assertTrue(p.z < 35, "gravity should have pulled the ball down, z=" + p.z);
        assertTrue(p.vz < 0, "the ball should be falling, vz=" + p.vz);
    }

    @Test
    public void fallsAndSettlesOnTheFloor() {
        Projectile p = ball(0, 0, 60, 0, 0, 0);
        run(p, Field.decode(), 12.0);
        assertEquals(R, p.z, 1e-6);
        assertSame(Projectile.State.REST, p.state);
    }

    @Test
    public void bouncesOffTheFloor() {
        Field f = Field.decode();
        Projectile p = ball(0, 0, 20, 0, 0, 0);
        // fall until the floor stops it
        while (p.z > R && p.age < 5) p.step(DT, f);
        double impact = Math.abs(p.vz);
        assertTrue(impact > 1, "a ball dropped from 20 in should hit the floor moving");
        p.step(DT, f);
        assertTrue(p.vz > 0, "the floor should send the ball back up, vz=" + p.vz);
        assertTrue(p.vz < impact, "a bounce cannot give the ball more speed than it came in with");
    }

    @Test
    public void staysInsideThePerimeter() {
        Field f = Field.decode();
        Projectile p = ball(0, 0, 10, 6000, 0, 0);
        run(p, f, 1.0);
        double limit = Field.HALF_SIZE_IN - R;
        assertTrue(Math.abs(p.x) <= limit + 1e-6, "ball left the field: x=" + p.x);
        assertTrue(Math.abs(p.y) <= limit + 1e-6, "ball left the field: y=" + p.y);
    }

    @Test
    public void rollsToARest() {
        Field f = Field.decode();
        Projectile p = ball(0, 0, R, 240, 0, 0);
        run(p, f, 10.0);
        assertSame(Projectile.State.REST, p.state);
        assertEquals(0.0, p.vx, 1e-9);
        assertEquals(0.0, p.vy, 1e-9);
    }

    @Test
    public void scoresInTheGoal() {
        Field f = Field.decode();
        // the DECODE blue goal box: 24 x 24 in footprint, 40 in tall, in the -X corner
        Projectile p = ball(-60, -60, 20, 0, 0, 0);
        p.step(DT, f);
        assertEquals("Blue goal", p.scoredGoal);
    }

    @Test
    public void doesNotScoreElsewhere() {
        Field f = Field.decode();
        Projectile p = ball(0, 0, 20, 0, 0, 0);
        p.step(DT, f);
        assertNull(p.scoredGoal);
    }

    @Test
    public void scoresOnlyOnce() {
        Field f = Field.decode();
        Projectile p = ball(-60, -60, 20, 0, 0, 0);
        run(p, f, 0.5);
        assertEquals("Blue goal", p.scoredGoal);
        // a settled ball inside the goal does not keep scoring
        String again = p.scoredGoal;
        run(p, f, 0.5);
        assertSame(again, p.scoredGoal);
    }

    @Test
    public void worldKeepsAndClearsBalls() {
        World w = new World();
        w.spawnProjectile(0, 0, 10, 100, 0, 0, R);
        assertEquals(1, w.projectiles.size());
        w.clearProjectiles();
        assertTrue(w.projectiles.isEmpty());
    }
}
