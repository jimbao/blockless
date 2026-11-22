package experiment;

import com.sun.net.httpserver.HttpServer;
import io.github.pjlabs.blockless.AsyncSupplier;
import io.github.pjlabs.blockless.continuations.Continuations;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Actual HTTP request, platform caller, production supplier/get; explicit owner configuration. */
public final class NetworkContractProbe {
  public static void main(String[] args) throws Exception {
    final var releaseQuery = new CountDownLatch(1);
    final var queryStarted = new CountDownLatch(1);
    final var enteringGet = new CountDownLatch(1);
    final var bodyEntries = new AtomicInteger();
    final var bodyCleanups = new AtomicInteger();
    final var callerEntries = new AtomicInteger();
    final var callerCleanups = new AtomicInteger();
    final var afterGet = new AtomicInteger();
    final var logicalCompletions = new AtomicInteger();
    final var createdCarriers = new AtomicInteger();
    final var requestCarrier = new AtomicReference<Thread>();
    final var healthCarrier = new AtomicReference<Thread>();
    final var uncaught = new CompletableFuture<Throwable>();

    try (final var carriers =
        Executors.newSingleThreadExecutor(
            command -> {
              createdCarriers.incrementAndGet();
              return Thread.ofPlatform().name("http-single-platform-carrier").unstarted(command);
            })) {
      final var owner =
          new ManagedOwner(carriers, uncaught::complete, logicalCompletions::incrementAndGet);
      final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(owner);
      server.createContext(
          "/user",
          exchange -> {
            check(!Thread.currentThread().isVirtual(), "caller must be a platform thread");
            requestCarrier.set(Thread.currentThread());
            callerEntries.incrementAndGet();
            final var savedLocal = "request:42";
            try {
              // Business code uses the actual production API. No callback or await helper.
              final var user =
                  Continuations.supplier(
                      () -> {
                        check(
                            Thread.currentThread().isVirtual(), "blocking body must run virtually");
                        bodyEntries.incrementAndGet();
                        try {
                          queryStarted.countDown();
                          check(
                              releaseQuery.await(5, TimeUnit.SECONDS),
                              "test did not release query");
                          final var details = "Alice";
                          final var permissions = "admin";
                          return details + ": " + permissions;
                        } finally {
                          bodyCleanups.incrementAndGet();
                        }
                      });
              enteringGet.countDown();
              final var value = nestedRead(user, savedLocal);
              check(Thread.currentThread() == requestCarrier.get(), "request resumed elsewhere");
              check(savedLocal.equals("request:42"), "caller local changed");
              afterGet.incrementAndGet();
              final var response = value.getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, response.length);
              try (final var stream = exchange.getResponseBody()) {
                stream.write(response);
              }
            } finally {
              callerCleanups.incrementAndGet();
              exchange.close();
            }
          });
      server.createContext(
          "/health",
          exchange -> {
            check(!Thread.currentThread().isVirtual(), "health must use the platform carrier");
            healthCarrier.set(Thread.currentThread());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
          });
      server.start();
      try (final var client = HttpClient.newHttpClient()) {
        try {
          final var base = "http://127.0.0.1:" + server.getAddress().getPort();
          final var response =
              client.sendAsync(
                  HttpRequest.newBuilder(URI.create(base + "/user")).build(),
                  HttpResponse.BodyHandlers.ofString());
          check(enteringGet.await(5, TimeUnit.SECONDS), "request did not reach get");
          check(queryStarted.await(5, TimeUnit.SECONDS), "query did not start");
          final var health =
              client
                  .sendAsync(
                      HttpRequest.newBuilder(URI.create(base + "/health")).build(),
                      HttpResponse.BodyHandlers.discarding())
                  .get(5, TimeUnit.SECONDS);
          check(health.statusCode() == 204, "health request failed");
          check(
              healthCarrier.get() == requestCarrier.get(), "original carrier did not serve health");
          check(createdCarriers.get() == 1, "a replacement thread hid a blocked caller");
          check(!response.isDone(), "user request completed while query was gated");
          check(afterGet.get() == 0 && callerCleanups.get() == 0, "caller continued on suspension");
          check(bodyCleanups.get() == 0, "body cleanup ran while query was gated");
          owner.shutdown();
          check(
              !owner.awaitTermination(20, TimeUnit.MILLISECONDS), "owner forgot suspended request");
          releaseQuery.countDown();
          final var userResponse = response.get(5, TimeUnit.SECONDS);
          check(userResponse.statusCode() == 200, "user request failed");
          check(
              userResponse.body().equals("Alice: admin:request:42:nested"), "wrong response value");
          check(owner.awaitTermination(5, TimeUnit.SECONDS), "logical request did not finish");
          check(bodyEntries.get() == 1 && callerEntries.get() == 1, "body or caller replayed");
          check(
              bodyCleanups.get() == 1 && callerCleanups.get() == 1,
              "finally skipped or duplicated");
          check(
              afterGet.get() == 1 && logicalCompletions.get() == 2,
              "logical completion count changed");
          check(!uncaught.isDone(), "unexpected request failure: " + uncaught.getNow(null));
          check(!carriers.isShutdown(), "owner shut down borrowed carrier executor");
          check(
              carriers.submit(Thread::currentThread).get(5, TimeUnit.SECONDS)
                  == requestCarrier.get(),
              "borrowed platform carrier was replaced");
          System.out.println(
              "PASS real HTTP: production supplier/get from a platform caller; same sole carrier"
                  + " serves health while query is pending; actual value, locals, finally, and"
                  + " graceful logical shutdown");
        } finally {
          releaseQuery.countDown();
        }
      } finally {
        releaseQuery.countDown();
        server.stop(0);
        owner.shutdown();
        check(owner.awaitTermination(5, TimeUnit.SECONDS), "owner cleanup did not finish");
      }
    }
  }

  private static String nestedRead(AsyncSupplier<String> user, String request) {
    final var saved = request + ":nested";
    return user.get() + ":" + saved;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
