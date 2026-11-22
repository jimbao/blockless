package joinproof;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

public final class MethodAgent {
  public static void premain(String ignored, Instrumentation instrumentation) {
    instrumentation.addTransformer(
        new ClassFileTransformer() {
          @Override
          public byte[] transform(
              ClassLoader loader,
              String name,
              Class<?> type,
              ProtectionDomain domain,
              byte[] bytes) {
            if (!"joinproof/MethodProbe".equals(name)) return null;
            var reader = new ClassReader(bytes);
            var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                  @Override
                  public MethodVisitor visitMethod(
                      int access,
                      String method,
                      String descriptor,
                      String signature,
                      String[] exceptions) {
                    if (method.equals("loadUser") && descriptor.equals("()Ljava/lang/String;"))
                      method = "originalLoadUser";
                    return super.visitMethod(access, method, descriptor, signature, exceptions);
                  }

                  @Override
                  public void visitEnd() {
                    var wrapper =
                        super.visitMethod(
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                            "loadUser",
                            "()Ljava/lang/String;",
                            null,
                            null);
                    wrapper.visitCode();
                    wrapper.visitInvokeDynamicInsn(
                        "call",
                        "()Ljava/util/concurrent/Callable;",
                        new Handle(
                            Opcodes.H_INVOKESTATIC,
                            "java/lang/invoke/LambdaMetafactory",
                            "metafactory",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                            false),
                        Type.getMethodType("()Ljava/lang/Object;"),
                        new Handle(
                            Opcodes.H_INVOKESTATIC,
                            name,
                            "originalLoadUser",
                            "()Ljava/lang/String;",
                            false),
                        Type.getMethodType("()Ljava/lang/String;"));
                    wrapper.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "joinproof/MethodRuntime",
                        "invoke",
                        "(Ljava/util/concurrent/Callable;)Ljava/lang/String;",
                        false);
                    wrapper.visitInsn(Opcodes.ARETURN);
                    wrapper.visitMaxs(0, 0);
                    wrapper.visitEnd();
                    super.visitEnd();
                  }
                },
                0);
            return writer.toByteArray();
          }
        });
  }
}
