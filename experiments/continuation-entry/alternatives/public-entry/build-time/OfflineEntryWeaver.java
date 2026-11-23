import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Transforms class files as data; application classes are never loaded or initialized. */
public final class OfflineEntryWeaver {
  private static final String EXECUTORS = "java/util/concurrent/Executors";
  private static final String RUNTIME = "publicentry/EntryRuntime";
  private static final Map<String, Set<String>> FACTORIES =
      Map.of(
          "newFixedThreadPool",
              Set.of(
                  "(I)Ljava/util/concurrent/ExecutorService;",
                  "(ILjava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;"),
          "newCachedThreadPool",
              Set.of(
                  "()Ljava/util/concurrent/ExecutorService;",
                  "(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;"));

  private OfflineEntryWeaver() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 3)
      throw new IllegalArgumentException("input-classes output-classes include-prefixes");
    final var source = Path.of(args[0]).toRealPath();
    final var destination = Path.of(args[1]).toAbsolutePath().normalize();
    if (destination.startsWith(source))
      throw new IllegalArgumentException("Output must be outside input");
    if (Files.exists(destination)) {
      try (final var existing = Files.list(destination)) {
        if (existing.findAny().isPresent())
          throw new IllegalArgumentException("Output directory must be empty");
      }
    }
    final var prefixes =
        Arrays.stream(args[2].split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.replace('.', '/'))
            .toList();
    if (prefixes.isEmpty()) throw new IllegalArgumentException("Select an application prefix");
    final var rewrittenCalls = new int[1];
    try (final var paths = Files.walk(source)) {
      for (final var input : paths.filter(Files::isRegularFile).sorted().toList()) {
        final var output = destination.resolve(source.relativize(input));
        Files.createDirectories(output.getParent());
        if (!input.toString().endsWith(".class")) {
          Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
          continue;
        }
        final var bytes = Files.readAllBytes(input);
        final var reader = new ClassReader(bytes);
        final var name = reader.getClassName();
        if (name.startsWith("publicentry/") || prefixes.stream().noneMatch(name::startsWith)) {
          Files.write(output, bytes);
          continue;
        }
        final var writer = new ClassWriter(reader, 0);
        reader.accept(
            new ClassVisitor(Opcodes.ASM9, writer) {
              @Override
              public MethodVisitor visitMethod(
                  int access,
                  String method,
                  String descriptor,
                  String signature,
                  String[] exceptions) {
                return new MethodVisitor(
                    Opcodes.ASM9,
                    super.visitMethod(access, method, descriptor, signature, exceptions)) {
                  @Override
                  public void visitMethodInsn(
                      int opcode,
                      String owner,
                      String method,
                      String descriptor,
                      boolean isInterface) {
                    if (opcode == Opcodes.INVOKESTATIC && supported(owner, method, descriptor)) {
                      rewrittenCalls[0]++;
                      owner = RUNTIME;
                    }
                    super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                  }

                  @Override
                  public void visitInvokeDynamicInsn(
                      String method, String descriptor, Handle bootstrap, Object... arguments) {
                    for (int index = 0; index < arguments.length; index++) {
                      if (arguments[index] instanceof Handle target
                          && target.getTag() == Opcodes.H_INVOKESTATIC
                          && supported(target.getOwner(), target.getName(), target.getDesc())) {
                        rewrittenCalls[0]++;
                        arguments[index] =
                            new Handle(
                                Opcodes.H_INVOKESTATIC,
                                RUNTIME,
                                target.getName(),
                                target.getDesc(),
                                false);
                      }
                    }
                    super.visitInvokeDynamicInsn(method, descriptor, bootstrap, arguments);
                  }
                };
              }
            },
            0);
        Files.write(output, writer.toByteArray());
      }
    }
    System.out.println(
        "Rewritten fixed/cached factory instructions or handles: " + rewrittenCalls[0]);
  }

  private static boolean supported(String owner, String method, String descriptor) {
    return EXECUTORS.equals(owner) && FACTORIES.getOrDefault(method, Set.of()).contains(descriptor);
  }
}
