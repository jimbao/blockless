package joinproof;

import java.util.concurrent.Callable;

public final class MethodRuntime {
  public static String invoke(Callable<String> body) {
    // The proposed transparent synchronous-method wrapper: the caller must still join.
    return JoinSupplier.supplier(body).get();
  }
}
