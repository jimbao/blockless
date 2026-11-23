package org.pjlabs.example;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-server dispatch and out-of-band gate control; never used by business queries. */
@Configuration(proxyBeanMethods = false)
public class AlternativeConfiguration {
  private static final AtomicInteger CREATED = new AtomicInteger();
  private static final AtomicInteger LIVE = new AtomicInteger();
  private static final AtomicInteger PEAK = new AtomicInteger();

  @Bean(destroyMethod = "shutdown")
  public ExecutorService alternativeExecutor(@Value("${research.entry:fixed}") String mode)
      throws Exception {
    final ThreadFactory factory =
        task -> {
          final var index = CREATED.incrementAndGet();
          return Thread.ofPlatform()
              .name("entry-request-" + index)
              .unstarted(
                  () -> {
                    PEAK.accumulateAndGet(LIVE.incrementAndGet(), Math::max);
                    try {
                      task.run();
                    } finally {
                      LIVE.decrementAndGet();
                    }
                  });
        };
    return switch (mode) {
      case "fixed" -> Executors.newFixedThreadPool(4, factory);
      case "virtual" -> Executors.newVirtualThreadPerTaskExecutor();
      case "public" ->
          (ExecutorService)
              Class.forName("publicentry.PublicTaskExecutor").getMethod("newExecutor").invoke(null);
      case "lazy" ->
          (ExecutorService)
              Class.forName("helping.LazyHelpingExecutor")
                  .getMethod("newFixedThreadPool", int.class, ThreadFactory.class)
                  .invoke(null, 4, factory);
      case "compensated" ->
          (ExecutorService)
              Class.forName("compensation.CompensatingTaskExecutor")
                  .getMethod("newExecutor", int.class, int.class)
                  .invoke(null, 4, 10);
      default -> throw new IllegalArgumentException("Unknown entry mode: " + mode);
    };
  }

  @Bean
  public GrpcServerConfigurer alternativeGrpcConfigurer(
      @Qualifier("alternativeExecutor") ExecutorService executor) {
    return builder -> builder.executor(executor);
  }

  @RestController
  public static class Control {
    @GetMapping("/research/entry/release")
    public Map<String, Object> release() {
      BenchmarkGrpcService.RELEASE.countDown();
      return status();
    }

    @GetMapping("/research/entry/state")
    public Map<String, Object> status() {
      final var result = new LinkedHashMap<String, Object>();
      result.put("entries", BenchmarkGrpcService.ENTRIES.get());
      result.put("bodies", BenchmarkGrpcService.BODIES.get());
      result.put("afterGet", BenchmarkGrpcService.AFTER_GET.get());
      result.put("callerFinally", BenchmarkGrpcService.CALLER_FINALLY.get());
      result.put("bodyFinally", BenchmarkGrpcService.BODY_FINALLY.get());
      result.put("gatedVirtualCallers", BenchmarkGrpcService.VIRTUAL.get());
      result.put("gatedPlatformCallers", BenchmarkGrpcService.PLATFORM.get());
      result.put("queryVirtualCallers", BenchmarkGrpcService.QUERY_VIRTUAL.get());
      result.put("queryPlatformCallers", BenchmarkGrpcService.QUERY_PLATFORM.get());
      result.put("factoryCreated", CREATED.get());
      result.put("factoryLive", LIVE.get());
      result.put("factoryPeak", PEAK.get());
      final var management = ManagementFactory.getThreadMXBean();
      result.put("platformThreadCount", management.getThreadCount());
      result.put("platformPeakCount", management.getPeakThreadCount());
      final var threads = Thread.getAllStackTraces().keySet();
      // Public diagnostic data only: there is no internal type or member access here.
      result.put(
          "jdkCarrierSnapshot",
          threads.stream()
              .filter(
                  thread -> thread.getClass().getName().equals("jdk.internal.misc.CarrierThread"))
              .count());
      result.put("platformThreadNames", threads.stream().map(Thread::getName).sorted().toList());
      return result;
    }
  }
}
