import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Build-time relocation only. This class and asm-commons are not packaged in the agent. */
public final class RelocateAgent {
  private static final String ASM = "org/objectweb/asm";
  private static final String PRIVATE_ASM = "callbackentry/internal/asm";

  public static void main(String[] args) throws IOException {
    final var source = Path.of(args[0]);
    final var destination = Path.of(args[1]);
    final var remapper =
        new Remapper() {
          @Override
          public String map(String name) {
            return name.equals(ASM) || name.startsWith(ASM + "/")
                ? PRIVATE_ASM + name.substring(ASM.length())
                : name;
          }
        };
    try (final var paths = Files.walk(source)) {
      for (final var input : paths.filter(Files::isRegularFile).toList()) {
        final var relative = source.relativize(input);
        if (relative.getFileName().toString().equals("module-info.class")) continue;
        if (input.toString().endsWith(".class")) {
          final var reader = new ClassReader(Files.readAllBytes(input));
          final var writer = new ClassWriter(0);
          reader.accept(new ClassRemapper(writer, remapper), 0);
          final var output =
              destination.resolve(remapper.mapType(reader.getClassName()) + ".class");
          Files.createDirectories(output.getParent());
          Files.write(output, writer.toByteArray());
        } else {
          // Retain any upstream notices/resources unchanged, outside the core library.
          final var output = destination.resolve(relative);
          Files.createDirectories(output.getParent());
          Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }
}
