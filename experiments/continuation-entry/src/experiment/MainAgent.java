package experiment;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Research only: establishes an entry before a selected main method starts. */
public final class MainAgent {
  private static final String RESULT =
      "io/github/pjlabs/blockless/continuations/Continuations$ContinuationResult";
  private static final String RUNTIME = "experiment/MainRuntime";

  public static void premain(String rootClass, Instrumentation instrumentation) {
    if (rootClass == null || rootClass.isBlank()) {
      throw new IllegalArgumentException("Specify the main class after -javaagent:agent.jar=");
    }
    final var root = rootClass.replace('.', '/');
    instrumentation.addTransformer(
        new ClassFileTransformer() {
          @Override
          public byte[] transform(
              ClassLoader loader,
              String name,
              Class<?> type,
              ProtectionDomain domain,
              byte[] bytes) {
            if (!root.equals(name) && !RESULT.equals(name)) return null;
            try {
              final var reader = new ClassReader(bytes);
              final var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
              reader.accept(
                  new ClassVisitor(Opcodes.ASM9, writer) {
                    private boolean foundMain;

                    @Override
                    public MethodVisitor visitMethod(
                        int access,
                        String method,
                        String descriptor,
                        String signature,
                        String[] exceptions) {
                      if (root.equals(name)
                          && method.equals("main")
                          && descriptor.equals("([Ljava/lang/String;)V")
                          && (access & Opcodes.ACC_STATIC) != 0) {
                        foundMain = true;
                        method = "blockless$originalMain";
                      }
                      final var delegate =
                          super.visitMethod(access, method, descriptor, signature, exceptions);
                      if (!RESULT.equals(name)
                          || !method.equals("get")
                          || !descriptor.equals("()Ljava/lang/Object;")) return delegate;
                      return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                          super.visitCode();
                          super.visitVarInsn(Opcodes.ALOAD, 0);
                          super.visitMethodInsn(
                              Opcodes.INVOKESTATIC,
                              RUNTIME,
                              "awaitResult",
                              "(Lio/github/pjlabs/blockless/AsyncSupplier;)V",
                              false);
                        }
                      };
                    }

                    @Override
                    public void visitEnd() {
                      if (foundMain) {
                        final var method =
                            super.visitMethod(
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "main",
                                "([Ljava/lang/String;)V",
                                null,
                                null);
                        method.visitCode();
                        method.visitVarInsn(Opcodes.ALOAD, 0);
                        method.visitInvokeDynamicInsn(
                            "run",
                            "([Ljava/lang/String;)Ljava/lang/Runnable;",
                            new Handle(
                                Opcodes.H_INVOKESTATIC,
                                "java/lang/invoke/LambdaMetafactory",
                                "metafactory",
                                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                                false),
                            Type.getMethodType("()V"),
                            new Handle(
                                Opcodes.H_INVOKESTATIC,
                                name,
                                "blockless$originalMain",
                                "([Ljava/lang/String;)V",
                                false),
                            Type.getMethodType("()V"));
                        method.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            RUNTIME,
                            "runMain",
                            "(Ljava/lang/Runnable;)V",
                            false);
                        method.visitInsn(Opcodes.RETURN);
                        method.visitMaxs(0, 0);
                        method.visitEnd();
                      }
                      super.visitEnd();
                    }
                  },
                  0);
              return writer.toByteArray();
            } catch (RuntimeException | Error failure) {
              System.err.println(
                  "Continuation-entry experiment could not transform " + name + ": " + failure);
              throw failure;
            }
          }
        });
  }
}
