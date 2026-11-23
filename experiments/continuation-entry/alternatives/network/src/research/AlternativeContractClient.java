package research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.pjlabs.example.proto.BenchmarkReply;
import org.pjlabs.example.proto.BenchmarkRequest;
import org.pjlabs.example.proto.BenchmarkServiceGrpc;

/**
 * Harness only: four held RPCs saturate the baseline; HTTP controls cannot execute business work.
 */
public final class AlternativeContractClient {
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final ObjectMapper JSON = new ObjectMapper();

  public static void main(String[] args) throws Exception {
    final var address = args[0];
    final var control = args[1];
    final var expectProgress = Boolean.parseBoolean(args[2]);
    final var expectVirtualCaller =
        switch (args[3]) {
          case "fixed", "lazy", "compensated" -> false;
          default -> true;
        };
    final var expectVirtualHealth = !args[3].equals("compensated");
    final var channel = ManagedChannelBuilder.forTarget(address).usePlaintext().build();
    final var blocking = BenchmarkServiceGrpc.newBlockingStub(channel);
    final var async =
        BenchmarkServiceGrpc.newFutureStub(channel).withDeadlineAfter(20, TimeUnit.SECONDS);
    final var pending = new ArrayList<ListenableFuture<BenchmarkReply>>();
    try {
      for (int index = 0; index < 4; index++) pending.add(async.query(request("gated")));
      final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      JsonNode state;
      do {
        state = state(control, "state");
        if (state.get("bodies").asInt() == 4) break;
        if (System.nanoTime() > deadline)
          throw new AssertionError("Four bodies not admitted: " + state);
        Thread.sleep(5);
      } while (true);
      if (state.get("gatedVirtualCallers").asInt() != (expectVirtualCaller ? 4 : 0)
          || state.get("gatedPlatformCallers").asInt() != (expectVirtualCaller ? 0 : 4)) {
        throw new AssertionError("Unexpected held caller type: " + state);
      }
      for (final var value : pending)
        if (value.isDone()) throw new AssertionError("Get returned before release");
      if (state.get("afterGet").asInt() != 0
          || state.get("callerFinally").asInt() != 0
          || state.get("bodyFinally").asInt() != 0) {
        throw new AssertionError("Early body/caller cleanup: " + state);
      }
      var progressed = false;
      try {
        final var health =
            blocking.withDeadlineAfter(500, TimeUnit.MILLISECONDS).query(request("health"));
        progressed = health.getResult() == 1;
        if (health.getVirtualThread() != expectVirtualHealth)
          throw new AssertionError("Unexpected health caller type: " + health);
      } catch (StatusRuntimeException failure) {
        if (failure.getStatus().getCode() != Status.Code.DEADLINE_EXCEEDED) throw failure;
      }
      if (progressed != expectProgress)
        throw new AssertionError(
            "Unexpected saturated health progress=" + progressed + ": " + state);
      System.out.println("Held before release: " + state);
      state(control, "release");
      final var expected =
          blocking.withDeadlineAfter(5, TimeUnit.SECONDS).query(request("join")).getResult();
      for (final var value : pending) {
        final var actual = value.get(5, TimeUnit.SECONDS);
        if (actual.getResult() != expected)
          throw new AssertionError("Wrong actual DB result: " + actual);
        if (actual.getVirtualThread() != expectVirtualCaller)
          throw new AssertionError("Unexpected result caller type: " + actual);
      }
      final var cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      do {
        state = state(control, "state");
        if (state.get("afterGet").asInt() == 4
            && state.get("callerFinally").asInt() == 4
            && state.get("bodyFinally").asInt() == 4) break;
        if (System.nanoTime() > cleanupDeadline)
          throw new AssertionError("Missing cleanup: " + state);
        Thread.sleep(5);
      } while (true);
      System.out.println(
          "PASS real supplier/get values="
              + expected
              + "; saturatedHealthProgress="
              + progressed
              + "; final="
              + state);
    } finally {
      try {
        state(control, "release");
      } catch (Exception ignoredDuringCleanup) {
      }
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
      HTTP.close();
    }
  }

  private static BenchmarkRequest request(String mode) {
    return BenchmarkRequest.newBuilder()
        .setMode(mode)
        .setCountry("SE")
        .setSleepSeconds(0.01)
        .setDbPoolSize(10)
        .build();
  }

  private static JsonNode state(String control, String operation) throws Exception {
    final var response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(control + "/research/entry/" + operation))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new AssertionError("Control failed: " + response.body());
    return JSON.readTree(response.body());
  }
}
