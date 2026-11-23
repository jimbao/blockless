package callbackentry;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Exact callback-loop entry experiment. This is not a general Runnable/Future adapter. */
public final class CallbackEntryAgent {
  private CallbackEntryAgent() {}

  public static void premain(String arguments, Instrumentation instrumentation) {
    final var options = new HashMap<String, String>();
    if (arguments != null) {
      for (final var argument : arguments.split(";")) {
        final var pair = argument.split("=", 2);
        if (pair.length != 2 || options.putIfAbsent(pair[0], pair[1]) != null) {
          throw new IllegalArgumentException("Use class=package.CallbackLoop;method=run");
        }
      }
    }
    if (!options.keySet().equals(Set.of("class", "method"))) {
      throw new IllegalArgumentException("Specify exactly class and method");
    }
    instrumentation.addTransformer(
        new CallbackLoop(options.get("class").replace('.', '/'), options.get("method")));
  }

  private record CallbackLoop(String targetClass, String targetMethod)
      implements ClassFileTransformer {
    private static final Handle LAMBDA =
        new Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                + "Ljava/lang/invoke/CallSite;",
            false);

    @Override
    public byte[] transform(
        ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
      if (!targetClass.equals(name)) return null;
      final var reader = new ClassReader(bytes);
      final var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
      final var transformed = new boolean[1];
      reader.accept(
          new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                int access,
                String method,
                String descriptor,
                String signature,
                String[] exceptions) {
              if (!method.equals(targetMethod)) {
                return super.visitMethod(access, method, descriptor, signature, exceptions);
              }
              if ((access
                          & (Opcodes.ACC_STATIC
                              | Opcodes.ACC_ABSTRACT
                              | Opcodes.ACC_NATIVE
                              | Opcodes.ACC_SYNCHRONIZED))
                      != 0
                  || Type.getReturnType(descriptor).getSort() != Type.VOID) {
                throw new IllegalArgumentException(
                    "Target must be a concrete nonsynchronized instance void method");
              }
              final var original = "$virtualEntry$" + method;
              final var callback =
                  super.visitMethod(access, method, descriptor, signature, exceptions);
              callback.visitCode();
              callback.visitVarInsn(Opcodes.ALOAD, 0);
              var local = 1;
              final var captured = new StringBuilder("(L").append(name).append(';');
              for (final var argument : Type.getArgumentTypes(descriptor)) {
                callback.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local);
                local += argument.getSize();
                captured.append(argument.getDescriptor());
              }
              captured.append(")Ljava/lang/Runnable;");
              callback.visitInvokeDynamicInsn(
                  "run",
                  captured.toString(),
                  LAMBDA,
                  Type.getMethodType("()V"),
                  new Handle(Opcodes.H_INVOKESPECIAL, name, original, descriptor, false),
                  Type.getMethodType("()V"));
              callback.visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  "java/lang/Thread",
                  "startVirtualThread",
                  "(Ljava/lang/Runnable;)Ljava/lang/Thread;",
                  false);
              callback.visitInsn(Opcodes.POP);
              callback.visitInsn(Opcodes.RETURN);
              callback.visitMaxs(0, 0);
              callback.visitEnd();
              transformed[0] = true;
              return super.visitMethod(
                  (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                      | Opcodes.ACC_PRIVATE
                      | Opcodes.ACC_SYNTHETIC,
                  original,
                  descriptor,
                  signature,
                  exceptions);
            }
          },
          0);
      if (!transformed[0]) throw new IllegalArgumentException("Target method was not found");
      System.err.println("Callback entry installed: " + name + "." + targetMethod);
      return writer.toByteArray();
    }
  }
}
