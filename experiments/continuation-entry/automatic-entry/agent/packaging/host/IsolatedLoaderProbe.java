import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** The parent-null loader cannot see the agent's system-loader classes. */
public final class IsolatedLoaderProbe {
  public static void main(String[] args) throws Exception {
    final var applicationAsm = Class.forName("org.objectweb.asm.ClassReader");
    if (!applicationAsm.getMethod("identity").invoke(null).equals("application-owned")) {
      throw new AssertionError("Agent changed the application's ASM identity");
    }
    try (final var isolated =
        new URLClassLoader(new URL[] {Path.of(args[0]).toUri().toURL()}, null)) {
      final var runtime = Class.forName("automaticentry.EntryRuntime", false, isolated);
      if (runtime.getClassLoader() != null) throw new AssertionError("Runtime is not on bootstrap");
      isolated
          .loadClass("example.agentchecks.FactoryCalls")
          .getMethod("main", String[].class)
          .invoke(null, (Object) new String[] {"agent"});
    }
    System.out.println("PASS parent-null loader and incompatible application ASM coexistence");
  }
}
