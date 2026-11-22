package research;

import io.grpc.ManagedChannelBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.pjlabs.example.proto.BenchmarkReply;
import org.pjlabs.example.proto.BenchmarkRequest;
import org.pjlabs.example.proto.BenchmarkServiceGrpc;

/**
 * Test harness only; business code uses immediate supplier.get(), never callbacks. Baseline
 * platform callers wait for pending values. Health uses spare platform workers; its success does
 * not prove the blocked request released its worker. Earlier pending-read-guard failures belong to
 * the historical implementation.
 */
public final class StrictGrpcContractClient {
  public static void main(String[] args) throws Exception {
    final var address = args.length > 0 ? args[0] : "localhost:9090";
    final var expectAutomaticEntry = args.length > 1 && args[1].equals("automatic");
    final var channel = ManagedChannelBuilder.forTarget(address).usePlaintext().build();
    final var blocking = BenchmarkServiceGrpc.newBlockingStub(channel);
    final var asynchronous =
        BenchmarkServiceGrpc.newFutureStub(channel).withDeadlineAfter(15, TimeUnit.SECONDS);
    final var gated = asynchronous.query(request("gated"));
    try {
      var state = awaitState(blocking, Map.of("entries", 1, "bodies", 1));
      check(
          state.getVirtualThread() == expectAutomaticEntry,
          "caller virtualization did not match selected startup configuration: " + state);
      final var health = query(blocking, "health");
      check(health.getResult() == 1, "health failed while user query was held");
      check(health.getVirtualThread() == expectAutomaticEntry, "health caller flag changed");
      check(!gated.isDone(), "gated request finished before release");
      checkCounters(
          state,
          Map.of("entries", 1, "bodies", 1, "afterGet", 0, "callerFinally", 0, "bodyFinally", 0));
      query(blocking, "release");
      final var result = gated.get(5, TimeUnit.SECONDS);
      check(
          result.getVirtualThread() == expectAutomaticEntry,
          "response caller flag did not match selected startup configuration");
      final var control = query(blocking, "join");
      check(result.getResult() == control.getResult(), "supplier changed the database result");
      state =
          awaitState(
              blocking,
              Map.of(
                  "entries", 1, "bodies", 1, "afterGet", 1, "callerFinally", 1, "bodyFinally", 1));
      if (expectAutomaticEntry) {
        System.out.println(
            "PASS automatic strict gRPC: immediate get returns actual DB result="
                + result.getResult()
                + "; health progresses while pending; callerVirtual=true; state="
                + state.getThreadName());
      } else {
        System.out.println(
            "PASS baseline strict gRPC: pending get waits without throwing; actual DB result="
                + result.getResult()
                + "; health uses spare platform workers; callerVirtual=false; state="
                + state.getThreadName());
        System.out.println(
            "LIMIT baseline: Supplier compatibility is verified; health progress does not prove the"
                + " waiting platform worker was released");
      }
    } finally {
      try {
        query(blocking, "release");
      } catch (Throwable ignoredDuringCleanup) {
      }
      channel.shutdownNow();
      check(channel.awaitTermination(5, TimeUnit.SECONDS), "client channel did not terminate");
    }
  }

  private static BenchmarkReply query(
      BenchmarkServiceGrpc.BenchmarkServiceBlockingStub stub, String mode) {
    return stub.withDeadlineAfter(5, TimeUnit.SECONDS).query(request(mode));
  }

  private static BenchmarkRequest request(String mode) {
    return BenchmarkRequest.newBuilder()
        .setMode(mode)
        .setCountry("SE")
        .setSleepSeconds(0.01)
        .setDbPoolSize(10)
        .build();
  }

  private static BenchmarkReply awaitState(
      BenchmarkServiceGrpc.BenchmarkServiceBlockingStub stub, Map<String, Integer> expected)
      throws Exception {
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    BenchmarkReply state;
    do {
      state = query(stub, "state");
      final var counters = counters(state);
      if (expected.entrySet().stream()
          .allMatch(entry -> entry.getValue().equals(counters.get(entry.getKey())))) {
        return state;
      }
      Thread.sleep(5);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("state did not reach " + expected + ": " + state.getThreadName());
  }

  private static void checkCounters(BenchmarkReply state, Map<String, Integer> expected) {
    final var actual = counters(state);
    expected.forEach(
        (key, value) ->
            check(
                value.equals(actual.get(key)),
                "wrong counter " + key + ": " + state.getThreadName()));
  }

  private static Map<String, Integer> counters(BenchmarkReply state) {
    final var counters = new HashMap<String, Integer>();
    for (final var field : state.getThreadName().split(";")) {
      final var parts = field.split("=", 2);
      if (parts.length == 2 && !parts[0].equals("callerVirtual")) {
        counters.put(parts[0], Integer.valueOf(parts[1]));
      }
    }
    return counters;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
