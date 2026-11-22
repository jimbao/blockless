package experiment;

import io.github.pjlabs.blockless.continuations.Continuations;

/** Unchanged supplier/get source; the experimental agent supplies the main entry boundary. */
public final class UserExample {
  public static void main(String[] args) {
    final var id = 42;
    final var user =
        Continuations.supplier(
            () -> {
              final var details = loadDetails(id);
              final var permissions = loadPermissions(id);
              return details + ": " + permissions;
            });
    System.out.println(user.get());
  }

  private static String loadDetails(int id) throws InterruptedException {
    Thread.sleep(30);
    return "Alice";
  }

  private static String loadPermissions(int id) throws InterruptedException {
    Thread.sleep(30);
    return "admin";
  }
}
