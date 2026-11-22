package org.objectweb.asm;

/** Deliberately incompatible application API: detects accidental linkage to the agent's ASM. */
public final class ClassReader {
  public static String identity() {
    return "application-owned";
  }
}
