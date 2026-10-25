package ftcsim;

import java.io.File;

/** Command line entry point. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        // SDK code resolves its user-visible strings through the Android Context; serve the AARs' English values.
        android.content.res.Resources.setStringResolver(ftcsim.generated.ResourceNames::string);
        Simulator.Options o = new Simulator.Options();
        String repoProp = System.getProperty("ftcsim.repo");
        if (repoProp != null && !repoProp.isEmpty()) o.repo = new File(repoProp);
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--repo": o.repo = new File(args[++i]); break;
                case "--port": o.port = Integer.parseInt(args[++i]); break;
                case "--host": o.host = args[++i]; break;
                case "--config": o.config = new File(args[++i]); break;
                case "--samples": o.includeSamples = true; break;
                case "--prebuilt": o.prebuilt = true; break;
                case "--no-browser": o.openBrowser = false; break;
                case "--data": o.dataDir = new File(args[++i]); break;
                case "--web": o.webOverrideDir = new File(args[++i]); break;
                case "--help": case "-h": usage(); return;
                default:
                    if (a.startsWith("-")) { System.err.println("Unknown option " + a); usage(); return; }
                    o.repo = new File(a);
            }
        }
        if (o.repo != null && !o.repo.isDirectory()) { System.err.println("Team repository not found: " + o.repo); System.exit(2); }
        if (o.repo != null) o.repo = o.repo.getCanonicalFile();
        if (System.getenv("FTCSIM_NO_BROWSER") != null || java.awt.GraphicsEnvironment.isHeadless()) o.openBrowser = false;
        Simulator sim = new Simulator(o);
        sim.start();
        Thread.currentThread().join();
    }

    private static void usage() {
        System.out.println("FTCSim - desktop simulator for FTC robot code\n" +
            "usage: ftcsim [--repo <FtcRobotController project>] [--port 8000] [--host 127.0.0.1]\n" +
            "              [--config robot.json] [--samples] [--prebuilt] [--no-browser] [--data <dir>]\n" +
            "  --host      address to bind the web UI to (default 127.0.0.1; 0.0.0.0 exposes it to the network)\n" +
            "  --samples   also load the FtcRobotController sample OpModes\n" +
            "  --prebuilt  use classes compiled by Android Studio (TeamCode/build/...) instead of compiling sources\n");
    }
}
