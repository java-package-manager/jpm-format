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
        Spec other = jarOrGit(style, root);
        if (other != null) {
            if (config != null) throw new FormatException("formatter.eclipseConfig needs formatter.style \"eclipse\"");
            return other;
        }
        int at = style.indexOf('@');
        String name = at < 0 ? style : style.substring(0, at);
        String version = at < 0 ? "latest" : style.substring(at + 1);
        String key = KEYS.get(name);
        if (key == null || version.isBlank()) {
            throw new FormatException("formatter.style must be \"intellij\" (the default), \"palantir\", \"palantir@<version>\","
                    + " \"google-java-format[@<version>]\", \"eclipse[@<version>]\", the URL of a jar, or a Git repository such as"
                    + " \"github:author/formatter[@<version>|#<branch>]\", not \"" + style + "\"");
        }
        if (name.equals("palantir") && at < 0) return null;
        if (config != null && !name.equals("eclipse")) {
            throw new FormatException("formatter.eclipseConfig needs formatter.style \"eclipse\"");
        }
        return new Spec(name, key, version, root, config);
    }

    private static final java.util.regex.Pattern GIT = java.util.regex.Pattern.compile("((?:github|gitlab|codeberg):[^@#\\s]+/[^@#\\s]+)([@#].+)?");
    private static final java.util.regex.Pattern SHA256 = java.util.regex.Pattern.compile("[0-9a-f]{64}");

    private static Spec jarOrGit(String style, Path root) {
        if (style.matches("(https?|file):.*")) {
            int hash = style.indexOf("#sha256=");
            String url = hash < 0 ? style : style.substring(0, hash);
            String sha = hash < 0 ? null : style.substring(hash + "#sha256=".length()).toLowerCase(java.util.Locale.ROOT);
            if (!url.endsWith(".jar")) throw new FormatException("formatter.style names a URL, which must be of a jar (ending in .jar): " + url);
            if (sha != null && !SHA256.matcher(sha).matches()) throw new FormatException("formatter.style: #sha256= takes the jar's SHA-256 in hex");
            return new Spec("jar", url, sha, root, null);
        }
        java.util.regex.Matcher g = GIT.matcher(style);
        if (!g.matches()) return null;
        String rest = g.group(2);
        String version = rest == null ? "*" : rest.startsWith("@") ? rest.substring(1) : rest;
        if (version.isBlank()) throw new FormatException("formatter.style " + style + " needs a version after @ or a branch after #");
        return new Spec("git", g.group(1), version, root, null);
    }

    /**
     * Formats the file of {@code ctx} with the formatter of {@code spec}, resolving its jars on first use.
     */
    public static String format(Spec spec, FileContext ctx) {
        String source = ctx.source(), path = ctx.path();
        Object f = FORMATTERS.computeIfAbsent(spec.name() + "@" + spec.key() + "@" + spec.version() + "@" + spec.config(), k -> create(spec));
        if (f instanceof RuleSetFormatters rs) return rs.format(ctx);
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
        if (spec.name().equals("jar") || spec.name().equals("git")) {
            List<Path> jars = spec.name().equals("jar") ? List.of(download(spec)) : resolve(spec.root(), Map.of(spec.key(), spec.version()));
            return any(spec, jars);
        }
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

    /**
     * The formatters of jpm-format rule sets that the jars list as services, run in order, each on the previous one's
     * output.
     */
    record RuleSetFormatters(List<dev.jpm.format.api.Formatter> formatters) {
        String format(FileContext ctx) {
            String cur = ctx.source();
            for (dev.jpm.format.api.Formatter f : formatters) {
                cur = f.format(new FileContext(new Source(ctx.path(), cur), f, dev.jpm.format.api.Severity.ERROR, ctx.settings(), null,
                        new ArrayList<>()));
            }
            return cur;
        }
    }

    private static final List<String> KNOWN = List.of("com.palantir.javaformat.java.Formatter", "com.google.googlejavaformat.java.Formatter");

    /**
     * The formatter in jars from a URL or a Git repository: Palantir's or google-java-format's (a fork or a build of
     * them), else the formatters of the jpm-format rule sets they list in {@code META-INF/services}.
     */
    private static Object any(Spec spec, List<Path> jars) {
        ClassLoader isolated = loader(jars);
        try {
            for (String name : KNOWN) {
                Class<?> c;
                try {
                    c = isolated.loadClass(name);
                } catch (ClassNotFoundException e) {
                    continue;
                }
                return name.contains("palantir") ? palantir(isolated) : c.getConstructor().newInstance();
            }
        } catch (InvocationTargetException e) {
            throw new FormatException("could not start the formatter of " + spec.key() + ": " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new FormatException("could not start the formatter of " + spec.key() + ": " + e, e);
        }
        List<URL> urls = new ArrayList<>();
        for (Path j : jars) urls.add(url(j));
        ClassLoader withApi = new URLClassLoader(urls.toArray(URL[]::new), ExternalFormatter.class.getClassLoader());
        dev.jpm.format.api.Rules rules = new dev.jpm.format.api.Rules();
        for (dev.jpm.format.api.RuleSet rs : java.util.ServiceLoader.load(dev.jpm.format.api.RuleSet.class, withApi)) {
            if (rs.getClass().getClassLoader() != withApi) continue;
            try {
                rs.register(rules);
            } catch (RuntimeException | LinkageError e) {
                throw new FormatException(spec.key() + ": " + rs.getClass().getName() + " could not register its rules: " + e, e);
            }
        }
        List<dev.jpm.format.api.Formatter> formatters = new ArrayList<>();
        for (dev.jpm.format.api.Lint l : rules.all()) if (l instanceof dev.jpm.format.api.Formatter f) formatters.add(f);
        if (formatters.isEmpty()) {
            throw new FormatException(spec.key() + " has no formatter: its jars need Palantir's or google-java-format's Formatter class,"
                    + " or a jpm-format RuleSet with a Formatter, listed in META-INF/services/dev.jpm.format.api.RuleSet");
        }
        return new RuleSetFormatters(List.copyOf(formatters));
    }

    /**
     * The jar a URL names, downloaded once into {@code .cache/format/formatters/} and checked against the SHA-256 after
     * {@code #sha256=} when there is one.
     */
    private static Path download(Spec spec) {
        String url = spec.key();
        Path target = spec.root().resolve(".cache").resolve("format").resolve("formatters").resolve(Hashing.sha256(url).substring(0, 32) + ".jar");
        try {
            if (!java.nio.file.Files.isRegularFile(target)) {
                java.nio.file.Files.createDirectories(target.getParent());
                Path part = target.resolveSibling(target.getFileName() + ".part");
                if (url.startsWith("file:")) {
                    java.nio.file.Files.copy(Path.of(java.net.URI.create(url)), part, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
                    java.net.http.HttpResponse<Path> r = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                            .header("User-Agent", "jpm-format").build(), java.net.http.HttpResponse.BodyHandlers.ofFile(part));
                    if (r.statusCode() != 200) {
                        java.nio.file.Files.deleteIfExists(part);
                        throw new FormatException("could not download the formatter " + url + ": HTTP " + r.statusCode());
                    }
                }
                java.nio.file.Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (spec.version() != null) {
                String actual = Hashing.sha256(java.nio.file.Files.readAllBytes(target));
                if (!actual.equals(spec.version())) {
                    java.nio.file.Files.deleteIfExists(target);
                    throw new FormatException("the formatter " + url + " has SHA-256 " + actual + ", not the " + spec.version() + " formatter.style names");
                }
            }
            return target;
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new FormatException("could not download the formatter " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FormatException("interrupted while downloading " + url, e);
        }
    }

    private static URL url(Path p) {
        try {
            return p.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new FormatException("bad jar path " + p, e);
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
        Class<?> provisionerType = loader.loadClass("com.diffplug.spotless.Provisioner");
        Object provisioner = provisioner(loader, spec.root());
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

    /**
     * Spotless's Provisioner, which Spotless's steps ask for the jars of the formatters they run: jpm resolves them.
     */
    static Object provisioner(ClassLoader loader, Path root) {
        if (System.getProperty("slf4j.internal.verbosity") == null) System.setProperty("slf4j.internal.verbosity", "ERROR");
        Class<?> provisionerType;
        try {
            provisionerType = loader.loadClass("com.diffplug.spotless.Provisioner");
        } catch (ClassNotFoundException e) {
            throw new FormatException("Spotless has no Provisioner: " + e, e);
        }
        return Proxy.newProxyInstance(loader, new Class<?>[]{provisionerType}, (proxy, method, args) -> {
            if (method.getName().equals("provisionWithTransitives")) {
                boolean transitives = (Boolean) args[0];
                Collection<?> coordinates = args[1] instanceof Collection<?> c ? c : List.of((Object[]) args[1]);
                return provision(root, transitives, coordinates);
            }
            if (method.getName().equals("toString")) return "jpm";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private static final Map<Path, ClassLoader> SPOTLESS_LOADERS = new ConcurrentHashMap<>();

    /**
     * A class loader with Spotless's library ({@code spotless-lib}, the version the Eclipse step uses, and the SLF4J
     * API it logs with but does not depend on), resolved by jpm once per project.
     */
    static ClassLoader spotless(Path root) {
        return SPOTLESS_LOADERS.computeIfAbsent(root, r -> loader(resolve(r, Map.of("com.diffplug.spotless:spotless-lib", SPOTLESS,
                "org.slf4j:slf4j-api", "^2.0"))));
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
