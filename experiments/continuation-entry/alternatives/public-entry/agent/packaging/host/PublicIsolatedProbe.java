import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

public final class PublicIsolatedProbe {
  public static void main(String[] args) throws Exception {
    final var applicationAsm = Class.forName("org.objectweb.asm.ClassReader");
    if (!"application-owned".equals(applicationAsm.getMethod("identity").invoke(null))) {
      throw new AssertionError("Application ASM identity changed");
    }
    try (final var isolated =
        new URLClassLoader(
            new URL[] {Path.of(args[0]).toUri().toURL(), Path.of(args[1]).toUri().toURL()}, null)) {
      if (Class.forName("publicentry.EntryRuntime", false, isolated).getClassLoader() != null) {
        throw new AssertionError("Entry runtime is not on bootstrap");
      }
      isolated
          .loadClass("example.publicentry.AutomaticEntryProbe")
          .getMethod("main", String[].class)
          .invoke(null, (Object) new String[] {"automatic"});
    }
    System.out.println("PASS parent-null loader and incompatible application ASM coexistence");
  }
}
