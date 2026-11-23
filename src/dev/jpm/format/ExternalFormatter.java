package dev.jpm.format;

import dev.jpm.api.client.JpmClient;
import dev.jpm.api.client.JpmError;
import dev.jpm.api.client.JpmNotInstalled;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Java formatter picked by name in {@code formatter.style}: {@code palantir@<version>} (Palantir Java Format) or
 * {@code google-java-format[@<version>]}. jpm resolves its jars ({@code dependencies/resolve}, protocol 1.2), and
 * they run in a class loader of their own, so their Guava never meets jpm-format's. Both are called through their
 * public API, {@code Formatter.formatSource(String)}, with their own default options (Palantir in its Palantir style,
 * as its {@code --palantir} flag gives): {@code formatter.lineWidth} and the indentation settings do not apply.
 */
public final class ExternalFormatter {
    /**
     * A style name, the formatter's Maven key, the version asked for, and the project whose jpm resolves it.
     */
    public record Spec(String name, String key, String version, Path root) {}

    private static final Map<String, String> KEYS = Map.of(
            "palantir", "com.palantir.javaformat:palantir-java-format",
            "google-java-format", "com.google.googlejavaformat:google-java-format");
    private static final Map<String, Object> FORMATTERS = new ConcurrentHashMap<>();

    private ExternalFormatter() {
    }

    /**
     * The formatter a {@code formatter.style} value names, or null for the built-in styles ({@code intellij},
     * {@code palantir}).
     */
    public static Spec parse(String style, Path root) {
        if (style.equals("intellij") || style.equals("palantir")) return null;
        int at = style.indexOf('@');
        String name = at < 0 ? style : style.substring(0, at);
        String version = at < 0 ? "latest" : style.substring(at + 1);
        String key = KEYS.get(name);
        if (key == null || version.isBlank()) {
            throw new FormatException("formatter.style must be \"intellij\" (the default), \"palantir\", \"palantir@<version>\""
                    + " or \"google-java-format[@<version>]\", not \"" + style + "\"");
        }
        if (name.equals("palantir") && at < 0) return null;
        return new Spec(name, key, version, root);
    }

    /**
     * Formats {@code source} with the formatter of {@code spec}, resolving its jars on first use.
     */
    public static String format(Spec spec, String source) {
        Object f = FORMATTERS.computeIfAbsent(spec.key() + "@" + spec.version(), k -> create(spec, spec.root()));
        try {
            Method m = f.getClass().getMethod("formatSource", String.class);
            return (String) m.invoke(f, source);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            throw new FormatException(spec.name() + ": " + (c.getMessage() == null ? c.toString() : c.getMessage()), c);
        } catch (ReflectiveOperationException e) {
            throw new FormatException(spec.name() + " has no formatSource(String): " + e, e);
        }
    }

    private static Object create(Spec spec, Path root) {
        List<Path> jars = resolve(spec, root);
        List<URL> urls = new ArrayList<>();
        for (Path j : jars) {
            try {
                urls.add(j.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new FormatException("bad jar path " + j, e);
            }
        }
        ClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        try {
            if (spec.name().equals("palantir")) {
                Class<?> options = loader.loadClass("com.palantir.javaformat.java.JavaFormatterOptions");
                Class<?> style = loader.loadClass("com.palantir.javaformat.java.JavaFormatterOptions$Style");
                Object builder = options.getMethod("builder").invoke(null);
                Method setStyle = builder.getClass().getMethod("style", style);
                setStyle.setAccessible(true);
                setStyle.invoke(builder, style.getField("PALANTIR").get(null));
                Method build = builder.getClass().getMethod("build");
                build.setAccessible(true);
                Object opts = build.invoke(builder);
                return loader.loadClass("com.palantir.javaformat.java.Formatter")
                        .getMethod("createFormatter", options)
                        .invoke(null, opts);
            }
            return loader.loadClass("com.google.googlejavaformat.java.Formatter").getConstructor().newInstance();
        } catch (InvocationTargetException e) {
            throw new FormatException("could not start " + spec.name() + ": " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new FormatException("could not start " + spec.name() + " " + spec.version() + ": " + e, e);
        }
    }

    private static List<Path> resolve(Spec spec, Path root) {
        String jpm = System.getenv("JPM_EXE");
        if (jpm == null || jpm.isBlank()) jpm = "jpm";
        try (JpmClient client = JpmClient.start(List.of(jpm, "api"), root, Map.of(), "jpm-format", Main.VERSION)) {
            if (!client.supports("dependencies/resolve")) {
                throw new FormatException("formatter.style \"" + spec.name() + "@" + spec.version() + "\" needs jpm 0.15.0 or newer"
                        + " (protocol 1.2, dependencies/resolve); upgrade with `jpm upgrade`");
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("dir", root.toAbsolutePath().toString());
            params.put("dependencies", Map.of(spec.key(), spec.version()));
            Map<String, Object> r = client.call("dependencies/resolve", params);
            List<Path> out = new ArrayList<>();
            if (r.get("classpath") instanceof List<?> l) for (Object o : l) out.add(Path.of(String.valueOf(o)));
            if (out.isEmpty()) throw new FormatException("jpm resolved no jars for " + spec.key() + " " + spec.version());
            return out;
        } catch (JpmNotInstalled e) {
            throw new FormatException("formatter.style \"" + spec.name() + "\" needs jpm to download it, and " + jpm + " could not run", e);
        } catch (JpmError e) {
            throw new FormatException("jpm could not resolve " + spec.key() + " " + spec.version() + ": " + e.getMessage(), e);
        }
    }
}
