package dev.jpm.format;

import dev.jpm.api.client.JpmClient;
import dev.jpm.api.client.JpmError;
import dev.jpm.api.client.JpmNotInstalled;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Java formatter picked by name in {@code formatter.style}: {@code palantir@<version>} (Palantir Java Format),
 * {@code google-java-format[@<version>]}, or {@code eclipse[@<version>]} (Eclipse JDT, through Spotless's step, with
 * the profile in {@code formatter.eclipseConfig}). jpm resolves the jars ({@code dependencies/resolve}, protocol
 * 1.2), and they run in a class loader of their own, so their Guava never meets jpm-format's. Palantir and
 * google-java-format are called through {@code Formatter.formatSource(String)} with their own default options
 * (Palantir in its Palantir style, as its {@code --palantir} flag gives); {@code formatter.lineWidth} and the
 * indentation settings do not apply.
 */
public final class ExternalFormatter {
    /**
     * A style name, the Maven key jpm resolves, the version asked for, the project whose jpm resolves it, and for
     * {@code eclipse} the profile to format with (null for Eclipse's defaults).
     */
    public record Spec(String name, String key, String version, Path root, Path config) {}

    static final String SPOTLESS = "4.12.0";
    private static final Map<String, String> KEYS = Map.of(
            "palantir", "com.palantir.javaformat:palantir-java-format",
            "google-java-format", "com.google.googlejavaformat:google-java-format",
            "eclipse", "com.diffplug.spotless:spotless-lib-extra");
    private static final Map<String, Object> FORMATTERS = new ConcurrentHashMap<>();

    private ExternalFormatter() {
    }

    /**
     * The formatter a {@code formatter.style} value names, or null for the built-in styles ({@code intellij},
     * {@code palantir}). {@code config} is {@code formatter.eclipseConfig}, resolved against the project.
     */
    public static Spec parse(String style, Path root, Path config) {
        if (style.equals("intellij") || style.equals("palantir")) {
            if (config != null) throw new FormatException("formatter.eclipseConfig needs formatter.style \"eclipse\"");
            return null;
        }
        int at = style.indexOf('@');
        String name = at < 0 ? style : style.substring(0, at);
        String version = at < 0 ? "latest" : style.substring(at + 1);
        String key = KEYS.get(name);
        if (key == null || version.isBlank()) {
            throw new FormatException("formatter.style must be \"intellij\" (the default), \"palantir\", \"palantir@<version>\","
                    + " \"google-java-format[@<version>]\" or \"eclipse[@<version>]\", not \"" + style + "\"");
        }
        if (name.equals("palantir") && at < 0) return null;
        if (config != null && !name.equals("eclipse")) {
            throw new FormatException("formatter.eclipseConfig needs formatter.style \"eclipse\"");
        }
        return new Spec(name, key, version, root, config);
    }

    /**
     * Formats {@code source}, the file at {@code path} in the project, with the formatter of {@code spec}, resolving
     * its jars on first use.
     */
    public static String format(Spec spec, String source, String path) {
        Object f = FORMATTERS.computeIfAbsent(spec.name() + "@" + spec.version() + "@" + spec.config(), k -> create(spec));
        try {
            if (spec.name().equals("eclipse")) {
                Method m = f.getClass().getMethod("format", String.class, File.class);
                m.setAccessible(true);
                String unix = source.replace("\r\n", "\n");
                return (String) m.invoke(f, unix, spec.root().resolve(path).toAbsolutePath().toFile());
            }
            Method m = f.getClass().getMethod("formatSource", String.class);
            return (String) m.invoke(f, source);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            throw new FormatException(spec.name() + ": " + (c.getMessage() == null ? c.toString() : c.getMessage()), c);
        } catch (ReflectiveOperationException e) {
            throw new FormatException(spec.name() + " cannot be called: " + e, e);
        }
    }

    private static Object create(Spec spec) {
        String version = spec.name().equals("eclipse") ? SPOTLESS : spec.version();
        ClassLoader loader = loader(resolve(spec.root(), Map.of(spec.key(), version)));
        try {
            return switch (spec.name()) {
                case "palantir" -> palantir(loader);
                case "eclipse" -> eclipse(loader, spec);
                default -> loader.loadClass("com.google.googlejavaformat.java.Formatter").getConstructor().newInstance();
            };
        } catch (InvocationTargetException e) {
            throw new FormatException("could not start " + spec.name() + ": " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new FormatException("could not start " + spec.name() + " " + spec.version() + ": " + e, e);
        }
    }

    private static Object palantir(ClassLoader loader) throws ReflectiveOperationException {
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

    private static Object eclipse(ClassLoader loader, Spec spec) throws ReflectiveOperationException {
        if (System.getProperty("slf4j.internal.verbosity") == null) System.setProperty("slf4j.internal.verbosity", "ERROR");
        Class<?> provisionerType = loader.loadClass("com.diffplug.spotless.Provisioner");
        Object provisioner = Proxy.newProxyInstance(loader, new Class<?>[]{provisionerType}, (proxy, method, args) -> {
            if (method.getName().equals("provisionWithTransitives")) {
                boolean transitives = (Boolean) args[0];
                Collection<?> coordinates = args[1] instanceof Collection<?> c ? c : List.of((Object[]) args[1]);
                return provision(spec.root(), transitives, coordinates);
            }
            if (method.getName().equals("toString")) return "jpm";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            throw new UnsupportedOperationException(method.getName());
        });
        Class<?> p2 = loader.loadClass("com.diffplug.spotless.extra.P2Provisioner");
        Object p2Default = p2.getMethod("createDefault").invoke(null);
        Class<?> step = loader.loadClass("com.diffplug.spotless.extra.java.EclipseJdtFormatterStep");
        Object builder = step.getMethod("createBuilder", provisionerType, p2).invoke(null, provisioner, p2Default);
        if (!spec.version().equals("latest")) call(builder, "setVersion", String.class, spec.version());
        if (spec.config() != null) call(builder, "setPreferences", Iterable.class, List.of(spec.config().toFile()));
        Method build = builder.getClass().getMethod("build");
        build.setAccessible(true);
        return build.invoke(builder);
    }

    private static void call(Object target, String name, Class<?> type, Object arg) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(name, type);
        m.setAccessible(true);
        m.invoke(target, arg);
    }

    private static Set<File> provision(Path root, boolean transitives, Collection<?> coordinates) {
        Map<String, String> deps = new LinkedHashMap<>();
        Set<String> wanted = new LinkedHashSet<>();
        for (Object o : coordinates) {
            String c = String.valueOf(o);
            int last = c.lastIndexOf(':');
            if (last < 0) throw new FormatException("Spotless asked for " + c + ", which is not group:artifact:version");
            deps.put(c.substring(0, last), c.substring(last + 1));
            String[] parts = c.split(":");
            wanted.add(parts[parts.length - 2] + "-" + parts[parts.length - 1] + ".jar");
        }
        Set<File> out = new LinkedHashSet<>();
        for (Path p : resolve(root, deps)) {
            if (transitives || wanted.contains(p.getFileName().toString())) out.add(p.toFile());
        }
        return out;
    }

    private static ClassLoader loader(List<Path> jars) {
        List<URL> urls = new ArrayList<>();
        for (Path j : jars) {
            try {
                urls.add(j.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new FormatException("bad jar path " + j, e);
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static List<Path> resolve(Path root, Map<String, String> dependencies) {
        String jpm = System.getenv("JPM_EXE");
        if (jpm == null || jpm.isBlank()) jpm = "jpm";
        try (JpmClient client = JpmClient.start(List.of(jpm, "api"), root, Map.of(), "jpm-format", Main.VERSION)) {
            if (!client.supports("dependencies/resolve")) {
                throw new FormatException("a formatter.style that names a formatter release needs jpm 0.15.0 or newer"
                        + " (protocol 1.2, dependencies/resolve); upgrade with `jpm upgrade`");
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("dir", root.toAbsolutePath().toString());
            params.put("dependencies", dependencies);
            Map<String, Object> r = client.call("dependencies/resolve", params);
            List<Path> out = new ArrayList<>();
            if (r.get("classpath") instanceof List<?> l) for (Object o : l) out.add(Path.of(String.valueOf(o)));
            if (out.isEmpty()) throw new FormatException("jpm resolved no jars for " + dependencies);
            return out;
        } catch (JpmNotInstalled e) {
            throw new FormatException("a formatter.style that names a formatter release needs jpm to download it, and "
                    + jpm + " could not run", e);
        } catch (JpmError e) {
            throw new FormatException("jpm could not resolve " + dependencies + ": " + e.getMessage(), e);
        }
    }
}
