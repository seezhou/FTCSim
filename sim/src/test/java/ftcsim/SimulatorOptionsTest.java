package ftcsim;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code gradlew run} starts the simulator in {@code sim/}, so explicit relative options must be
 * anchored to the repository root instead of silently creating files under the build directory.
 */
public class SimulatorOptionsTest {
    @Test
    public void relativePathsFollowTheRepositoryRoot() throws Exception {
        File home = Files.createTempDirectory("ftcsim-home").toFile();
        Simulator.Options o = new Simulator.Options();
        o.home = home;
        o.config = new File("configs/MyRobot.json");
        o.dataDir = new File("build/data");
        o.webOverrideDir = new File("web");
        o.resolvePaths();
        assertEquals(new File(home, "configs/MyRobot.json"), o.config);
        assertEquals(new File(home, "build/data"), o.dataDir);
        assertEquals(new File(home, "web"), o.webOverrideDir);
    }

    @Test
    public void absoluteAndMissingPathsAreLeftAlone() throws Exception {
        File home = Files.createTempDirectory("ftcsim-home").toFile();
        File absolute = new File(home, "absolute.json").getAbsoluteFile();
        Simulator.Options o = new Simulator.Options();
        o.home = home;
        o.config = absolute;
        o.resolvePaths();
        assertEquals(absolute, o.config);
        assertNull(o.dataDir);
        assertNull(o.webOverrideDir);
    }
}
