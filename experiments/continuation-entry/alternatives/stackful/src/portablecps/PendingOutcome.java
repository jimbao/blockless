package portablecps;

import java.util.concurrent.CompletableFuture;

/** Internal readiness-copy metadata for a standard Supplier returned by Blockless. */
public interface PendingOutcome {
  CompletableFuture<?> outcomeFuture();
}
