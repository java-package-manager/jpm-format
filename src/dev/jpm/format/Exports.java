package dev.jpm.format;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Palantir Java Format uses javac's internal classes, which the {@code jdk.compiler} module does not export.
 * {@link Main} relaunches the JVM with {@code --add-exports} when they are not exported; on JDKs before 24
 * (where it causes no warning) this class can also export them in the running JVM, which saves that second
 * start and lets tests run under any test launcher.
 */
public final class Exports {
    static final List<String> PACKAGES = List.of("api", "code", "file", "main", "parser", "tree", "util").stream()
            .map(p -> "com.sun.tools.javac." + p)
            .toList();

    private static volatile boolean open;

    private Exports() {}

    /** True if javac's internals are exported to unnamed modules. */
    public static boolean exported() {
        if (open) return true;
        Module javac = ModuleLayer.boot().findModule("jdk.compiler").orElse(null);
        if (javac == null) return false;
        Module me = Exports.class.getModule();
        for (String p : PACKAGES) if (!javac.isExported(p, me)) return false;
        return open = true;
    }

    /** Exports javac's internals if needed and possible; true if they are exported afterwards. */
    public static synchronized boolean open() {
        if (exported()) return true;
        // JPM_FORMAT_RELAUNCH=1 forces the relaunch path instead (for testing it)
        if (Runtime.version().feature() >= 24 || System.getenv("JPM_FORMAT_RELAUNCH") != null) return false;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            Field impl = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, impl);
            long offset = (long)
                    unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, impl);
            MethodHandles.Lookup lookup = (MethodHandles.Lookup)
                    unsafeClass.getMethod("getObject", Object.class, long.class).invoke(unsafe, base, offset);
            MethodHandle add = lookup.findVirtual(
                    Module.class, "implAddExportsToAllUnnamed", MethodType.methodType(void.class, String.class));
            Module javac = ModuleLayer.boot().findModule("jdk.compiler").orElseThrow();
            for (String p : PACKAGES) add.invoke(javac, p);
            return exported();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The JVM flags that export them. */
    public static List<String> flags() {
        return PACKAGES.stream()
                .map(p -> "--add-exports=jdk.compiler/" + p + "=ALL-UNNAMED")
                .toList();
    }
}
