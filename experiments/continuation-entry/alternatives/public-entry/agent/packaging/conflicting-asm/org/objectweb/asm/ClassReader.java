package org.objectweb.asm;

/** Deliberately incompatible with ASM's real ClassReader. */
public final class ClassReader {
  public static String identity() {
    return "application-owned";
  }
}
