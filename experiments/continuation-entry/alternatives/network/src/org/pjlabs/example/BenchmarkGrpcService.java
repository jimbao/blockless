package org.pjlabs.example;

import com.zaxxer.hikari.HikariDataSource;
import io.github.pjlabs.blockless.Blockless;
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

/** All variants execute the same stable Blockless supplier and immediate get before replying. */
@GrpcService
public class BenchmarkGrpcService extends BenchmarkServiceGrpc.BenchmarkServiceImplBase {
  static final CountDownLatch RELEASE = new CountDownLatch(1);
  static final AtomicInteger ENTRIES = new AtomicInteger();
  static final AtomicInteger BODIES = new AtomicInteger();
  static final AtomicInteger AFTER_GET = new AtomicInteger();
  static final AtomicInteger CALLER_FINALLY = new AtomicInteger();
  static final AtomicInteger BODY_FINALLY = new AtomicInteger();
  static final AtomicInteger VIRTUAL = new AtomicInteger();
  static final AtomicInteger PLATFORM = new AtomicInteger();
  static final AtomicInteger QUERY_VIRTUAL = new AtomicInteger();
  static final AtomicInteger QUERY_PLATFORM = new AtomicInteger();
  private static final ThreadLocal<String> REQUEST_CONTEXT = new ThreadLocal<>();
  private final ProductRepository repository;
  private final HikariDataSource dataSource;

  public BenchmarkGrpcService(ProductRepository repository, DataSource dataSource) {
    this.repository = repository;
    this.dataSource = (HikariDataSource) dataSource;
  }

  @Override
  public void query(BenchmarkRequest request, StreamObserver<BenchmarkReply> response) {
    final var caller = Thread.currentThread();
    final var start = System.nanoTime();
    final var mode = request.getMode();
    final var gated = mode.equals("gated");
    if (mode.equals("blockless")) {
      (caller.isVirtual() ? QUERY_VIRTUAL : QUERY_PLATFORM).incrementAndGet();
    }
    final var savedLocal = "request:" + request.getCountry();
    if (gated) {
      ENTRIES.incrementAndGet();
      (caller.isVirtual() ? VIRTUAL : PLATFORM).incrementAndGet();
      REQUEST_CONTEXT.set(savedLocal);
    }
    try {
      if (request.getDbPoolSize() > 0
          && request.getDbPoolSize() != dataSource.getMaximumPoolSize()) {
        dataSource.setMaximumPoolSize(request.getDbPoolSize());
      }
      final var country = request.getCountry().isEmpty() ? "SE" : request.getCountry();
      final var delay = request.getSleepSeconds() > 0 ? request.getSleepSeconds() : 0.01;
      final int value;
      if (mode.equals("health")) value = 1;
      else if (mode.equals("join")) value = repository.countByCountryWithDelay(country, delay);
      else {
        if (!mode.equals("blockless") && !gated)
          throw new IllegalArgumentException("Unknown mode: " + mode);
        final var user =
            Blockless.supplier(
                () -> {
                  if (!Thread.currentThread().isVirtual())
                    throw new AssertionError("Body is not virtual");
                  try {
                    if (gated) {
                      BODIES.incrementAndGet();
                      if (!RELEASE.await(30, TimeUnit.SECONDS))
                        throw new AssertionError("Gate not released");
                    }
                    return repository.countByCountryWithDelay(country, delay);
                  } finally {
                    if (gated) BODY_FINALLY.incrementAndGet();
                  }
                });
        value = user.get();
      }
      if (gated) {
        if (Thread.currentThread() != caller || !savedLocal.equals(REQUEST_CONTEXT.get())) {
          throw new AssertionError("Caller identity/local context changed across get");
        }
        AFTER_GET.incrementAndGet();
      }
      response.onNext(
          BenchmarkReply.newBuilder()
              .setResult(value)
              .setVirtualThread(caller.isVirtual())
              .setThreadName(caller.getName())
              .setElapsedMs((System.nanoTime() - start) / 1_000_000)
              .build());
      response.onCompleted();
    } catch (Throwable failure) {
      response.onError(
          Status.INTERNAL
              .withDescription("Query failed: " + failure)
              .withCause(failure)
              .asRuntimeException());
    } finally {
      if (gated) {
        REQUEST_CONTEXT.remove();
        CALLER_FINALLY.incrementAndGet();
      }
    }
  }
}
