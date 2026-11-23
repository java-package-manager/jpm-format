package dev.jpm.format;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Which JDK the formatter can run on. Palantir Java Format reads javac's internal syntax tree, and newer JDKs
 * changed it (javac no longer keeps {@code JCCompilationUnit.endPositions}), so on those the formatter cannot
 * run at all. jpm starts extensions on whatever JDK it picks, so {@link Main} then moves the run to a JDK the
 * formatter supports, which jpm installs if needed.
 */
public final class Jdks {
    /**
     * The JDK asked from jpm when the running one is not supported.
     */
    static final String SUPPORTED = "21";

    private static volatile Boolean supported;

    private Jdks() {
    }

    /**
     * True if the running JDK's javac has what the formatter needs.
     */
    public static boolean formatterSupported() {
        Boolean s = supported;
        if (s == null) {
            try {
                Class<?> unit = Class.forName(
                        "com.sun.tools.javac.tree.JCTree$JCCompilationUnit", false, ClassLoader.getSystemClassLoader());
                unit.getField("endPositions");
                s = true;
            } catch (ReflectiveOperationException | LinkageError e) {
                s = false;
            }
            supported = s;
        }
        return s;
    }

    /**
     * Why the formatter cannot run here, for error messages.
     */
    public static String unsupportedMessage() {
        return "the formatter (Palantir Java Format) does not support the javac of Java "
                + Runtime.version().feature() + " yet; run jpm-format on JDK " + SUPPORTED
                + " (set JPM_FORMAT_JAVA_HOME to its folder)";
    }

    /**
     * The {@code java} of a JDK the formatter supports: {@code JPM_FORMAT_JAVA_HOME}, else the JDK
     * {@code jpm java home 21} names (jpm installs it if missing). Throws if neither works.
     */
    static String supportedJava() {
        String home = System.getenv("JPM_FORMAT_JAVA_HOME");
        if (home == null || home.isBlank()) home = fromJpm();
        if (home == null) throw new FormatException(unsupportedMessage());
        Path java = Path.of(home, "bin", windows() ? "java.exe" : "java");
        if (!Files.isRegularFile(java)) throw new FormatException(java + " does not exist; " + unsupportedMessage());
        return java.toString();
    }

    private static String fromJpm() {
        String jpm = System.getenv("JPM_EXE");
        if (jpm == null || jpm.isBlank()) jpm = "jpm";
        try {
            Process p = new ProcessBuilder(jpm, "java", "home", SUPPORTED)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(15, TimeUnit.MINUTES) || p.exitValue() != 0 || out.isEmpty()) return null;
            String[] lines = out.split("\\R");
            return lines[lines.length - 1].strip();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
