package org.pjlabs.example;

import com.zaxxer.hikari.HikariDataSource;
import io.github.pjlabs.blockless.Blockless;
import io.github.pjlabs.blockless.continuations.Continuations;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.devh.boot.grpc.server.service.GrpcService;
import org.pjlabs.example.db.ProductRepository;
import org.pjlabs.example.proto.BenchmarkReply;
import org.pjlabs.example.proto.BenchmarkRequest;
import org.pjlabs.example.proto.BenchmarkServiceGrpc;

/** Research overlay: all response delivery occurs after the handler's immediate get(). */
@GrpcService
public class BenchmarkGrpcService extends BenchmarkServiceGrpc.BenchmarkServiceImplBase {
  private static final CountDownLatch RELEASE_QUERY = new CountDownLatch(1);
  private static final AtomicInteger GATED_ENTRIES = new AtomicInteger();
  private static final AtomicInteger GATED_BODIES = new AtomicInteger();
  private static final AtomicInteger GATED_AFTER_GET = new AtomicInteger();
  private static final AtomicInteger GATED_CALLER_FINALLY = new AtomicInteger();
  private static final AtomicInteger GATED_BODY_FINALLY = new AtomicInteger();

  private final ProductRepository repository;
  private final HikariDataSource dataSource;

  public BenchmarkGrpcService(ProductRepository repository, DataSource dataSource) {
    this.repository = repository;
    this.dataSource = (HikariDataSource) dataSource;
  }

  @Override
  public void query(BenchmarkRequest request, StreamObserver<BenchmarkReply> responseObserver) {
    final var caller = Thread.currentThread();
    final var callerVirtual = caller.isVirtual();
    final var start = System.nanoTime();
    final var mode = request.getMode().isEmpty() ? "join" : request.getMode();
    final var gated = mode.equals("gated");
    final var savedLocal = "request:42";
    if (gated) GATED_ENTRIES.incrementAndGet();
    try {
      if (request.getDbPoolSize() > 0
          && request.getDbPoolSize() != dataSource.getMaximumPoolSize()) {
        dataSource.setMaximumPoolSize(request.getDbPoolSize());
      }
      final var country = request.getCountry().isEmpty() ? "SE" : request.getCountry();
      final var sleep = request.getSleepSeconds() > 0 ? request.getSleepSeconds() : 0.01;
      final int result;
      switch (mode) {
        case "health" -> result = 1;
        case "release" -> {
          RELEASE_QUERY.countDown();
          result = 1;
        }
        case "state" -> result = GATED_ENTRIES.get();
        case "join" -> result = repository.countByCountryWithDelay(country, sleep);
        case "blockless" ->
            result =
                Blockless.supplier(() -> repository.countByCountryWithDelay(country, sleep)).get();
        case "continuation" -> {
          // Exact requested business contract. The caller emits the response after get returns.
          final var user =
              Continuations.supplier(() -> repository.countByCountryWithDelay(country, sleep));
          result = user.get();
        }
        case "gated" -> {
          final var user =
              Continuations.supplier(
                  () -> {
                    if (!Thread.currentThread().isVirtual()) {
                      throw new AssertionError("blocking query body is not virtual");
                    }
                    GATED_BODIES.incrementAndGet();
                    try {
                      if (!RELEASE_QUERY.await(30, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release the query");
                      }
                      return repository.countByCountryWithDelay(country, sleep);
                    } finally {
                      GATED_BODY_FINALLY.incrementAndGet();
                    }
                  });
          result = user.get();
          GATED_AFTER_GET.incrementAndGet();
          if (Thread.currentThread() != caller || !savedLocal.equals("request:42")) {
            throw new AssertionError("caller identity or local was lost");
          }
        }
        default -> throw new IllegalArgumentException("Unknown strict query mode " + mode);
      }
      final var thread =
          mode.equals("state")
              ? caller.getName()
                  + ";callerVirtual="
                  + callerVirtual
                  + ";entries="
                  + GATED_ENTRIES.get()
                  + ";bodies="
                  + GATED_BODIES.get()
                  + ";afterGet="
                  + GATED_AFTER_GET.get()
                  + ";callerFinally="
                  + GATED_CALLER_FINALLY.get()
                  + ";bodyFinally="
                  + GATED_BODY_FINALLY.get()
              : caller.getName();
      responseObserver.onNext(
          BenchmarkReply.newBuilder()
              .setResult(result)
              .setThreadName(thread)
              .setVirtualThread(callerVirtual)
              .setElapsedMs((System.nanoTime() - start) / 1_000_000)
              .build());
      responseObserver.onCompleted();
    } catch (Throwable failure) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Strict query failed: " + failure)
              .withCause(failure)
              .asRuntimeException());
    } finally {
      if (gated) GATED_CALLER_FINALLY.incrementAndGet();
    }
  }
}
