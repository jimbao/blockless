package io.github.pjlabs.blockless;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Stores a single outcome and waits for its publication before supplying it. */
final class OutcomeSupplier<T> implements Supplier<T> {

  private final CompletableFuture<T> result = new CompletableFuture<>();
  private final CountDownLatch latch = new CountDownLatch(1);

  /** Called once by the producer; publishes the outcome to all current and later callers. */
  void complete(T value, Throwable failure) {
    try {
      if (failure == null) {
        result.complete(value);
      } else {
        // exceptionNow() unwraps one CompletionException; preserve the original failure.
        // The null message avoids calling the failure's toString.
        result.completeExceptionally(new CompletionException(null, failure));
      }
    } finally {
      latch.countDown();
    }
  }

  @Override
  public T get() {
    // Skip await() once completed, so an interrupted caller still gets the outcome.
    if (!result.isDone()) {
      try {
        awaitOutcome();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    return switch (result.state()) {
      case SUCCESS -> result.resultNow();
      case FAILED -> throw wrapIfChecked(result.exceptionNow());
      case CANCELLED, RUNNING -> throw new IllegalStateException("Result not completed");
    };
  }

  // Research-only alternative: inform a ForkJoinPool when this platform worker waits.
  private void awaitOutcome() throws InterruptedException {
    if (!ForkJoinTask.inForkJoinPool()) {
      latch.await();
      return;
    }
    try {
      ForkJoinPool.managedBlock(
          new ForkJoinPool.ManagedBlocker() {
            @Override
            public boolean isReleasable() {
              return latch.getCount() == 0;
            }

            @Override
            public boolean block() throws InterruptedException {
              latch.await();
              return true;
            }
          });
    } catch (RejectedExecutionException compensationUnavailable) {
      // A finite pool may refuse another spare. Pending status must not become a get failure.
      latch.await();
    }
  }

  private static RuntimeException wrapIfChecked(Throwable t) {
    if (t instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    if (t instanceof Error error) {
      throw error;
    }
    return new RuntimeException(t);
  }
}
