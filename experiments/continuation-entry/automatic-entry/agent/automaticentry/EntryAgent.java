package automaticentry;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Research only: replace selected executor factory calls before their caller classes load. */
public final class EntryAgent {
  private static final String EXECUTORS = "java/util/concurrent/Executors";
  private static final String RUNTIME = "automaticentry/EntryRuntime";
  private static final Set<String> FIXED_DESCRIPTORS =
      Set.of(
          "(I)Ljava/util/concurrent/ExecutorService;",
          "(ILjava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;");
  private static final Set<String> CACHED_DESCRIPTORS =
      Set.of(
          "()Ljava/util/concurrent/ExecutorService;",
          "(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;");

  private EntryAgent() {}

  public static void premain(String arguments, Instrumentation instrumentation) {
    final var options = Options.parse(arguments);
    // Load without initializing the runtime, then grant its reflective VT builder access before
    // use.
    final var runtimeModule = EntryRuntime.class.getModule();
    final var openModules = Set.copyOf(List.of(runtimeModule, EntryAgent.class.getModule()));
    instrumentation.redefineModule(
        Object.class.getModule(),
        Set.of(),
        Map.of(),
        Map.of("java.lang", openModules),
        Set.of(),
        Map.of());
    EntryRuntime.configureCachedCarriers(options.cachedCarriers());
    instrumentation.addTransformer(new FactoryCalls(options.includes()));
  }

  private static boolean supportedFactory(String owner, String method, String descriptor) {
    if (!EXECUTORS.equals(owner)) return false;
    return (method.equals("newFixedThreadPool") && FIXED_DESCRIPTORS.contains(descriptor))
        || (method.equals("newCachedThreadPool") && CACHED_DESCRIPTORS.contains(descriptor));
  }

  private record Options(List<String> includes, int cachedCarriers) {
    private static Options parse(String arguments) {
      final var values = new LinkedHashMap<String, String>();
      if (arguments != null) {
        for (final var item : arguments.split(";")) {
          if (item.isBlank()) continue;
          final var pair = item.trim().split("=", 2);
          if (pair.length != 2 || values.putIfAbsent(pair[0].trim(), pair[1].trim()) != null) {
            throw new IllegalArgumentException("Use include=example.,io.grpc.;cachedCarriers=4");
          }
        }
      }
      if (!Set.of("include", "cachedCarriers").containsAll(values.keySet())) {
        throw new IllegalArgumentException("Unknown automatic-entry option: " + values.keySet());
      }
      final var includes =
          List.of(values.getOrDefault("include", "").split(",")).stream()
              .map(String::trim)
              .filter(prefix -> !prefix.isEmpty())
              .map(prefix -> prefix.replace('.', '/'))
              .toList();
      if (includes.isEmpty()) throw new IllegalArgumentException("Specify include=example.");
      final var cachedCarriers = Integer.parseInt(values.getOrDefault("cachedCarriers", "4"));
      if (cachedCarriers < 1) throw new IllegalArgumentException("cachedCarriers must be positive");
      return new Options(includes, cachedCarriers);
    }
  }

  private static final class FactoryCalls implements ClassFileTransformer {
    private final List<String> includes;

    private FactoryCalls(List<String> includes) {
      this.includes = includes;
    }

    @Override
    public byte[] transform(
        ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
      if (loader == null
          || name == null
          || name.startsWith("automaticentry/")
          || name.startsWith("org/objectweb/asm/")
          || includes.stream().noneMatch(name::startsWith)) return null;
      final var reader = new ClassReader(bytes);
      final var writer = new ClassWriter(reader, 0);
      final var changed = new boolean[1];
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
                  if (opcode == Opcodes.INVOKESTATIC
                      && supportedFactory(owner, method, descriptor)) {
                    changed[0] = true;
                    owner = RUNTIME;
                  }
                  super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                }

                @Override
                public void visitInvokeDynamicInsn(
                    String method, String descriptor, Handle bootstrap, Object... arguments) {
                  // Factory method references have the same signatures and need the same entry.
                  for (int index = 0; index < arguments.length; index++) {
                    if (arguments[index] instanceof Handle target
                        && target.getTag() == Opcodes.H_INVOKESTATIC
                        && supportedFactory(
                            target.getOwner(), target.getName(), target.getDesc())) {
                      changed[0] = true;
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
      return changed[0] ? writer.toByteArray() : null;
    }
  }
}
