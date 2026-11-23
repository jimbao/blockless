package portablecps;

import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Build-time prepass: make standard Supplier.get carry continuation metadata. */
public final class RewriteGetCalls {
  public static void main(String[] args) throws Exception {
    final var path = Path.of(args[0]);
    final var reader = new ClassReader(Files.readAllBytes(path));
    final var writer = new ClassWriter(reader, 0);
    reader.accept(
        new ClassVisitor(Opcodes.ASM9, writer) {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodVisitor(
                Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
              @Override
              public void visitMethodInsn(
                  int opcode, String owner, String name, String descriptor, boolean isInterface) {
                if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("java/util/function/Supplier")
                    && name.equals("get")
                    && descriptor.equals("()Ljava/lang/Object;")) {
                  opcode = Opcodes.INVOKESTATIC;
                  owner = "portablecps/FlowRuntime";
                  descriptor = "(Ljava/util/function/Supplier;)Ljava/lang/Object;";
                  isInterface = false;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
              }
            };
          }
        },
        0);
    Files.write(path, writer.toByteArray());
  }
}
