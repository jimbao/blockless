package org.pjlabs.example;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Research only: compare the same strict handler under different request executors. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "research.grpc.configure-executor",
    havingValue = "true",
    matchIfMissing = true)
public class StrictServerExecutorConfiguration {
  @Bean(destroyMethod = "shutdown")
  public ExecutorService strictRequestExecutor(
      @Value("${research.grpc.executor:platform}") String mode,
      @Value("${research.grpc.workers:4}") int workers) {
    return switch (mode) {
      case "platform" -> Executors.newFixedThreadPool(workers);
      case "virtual" -> Executors.newVirtualThreadPerTaskExecutor();
      default -> throw new IllegalArgumentException("Unknown research.grpc.executor " + mode);
    };
  }

  @Bean
  public GrpcServerConfigurer strictGrpcServerConfigurer(
      @Qualifier("strictRequestExecutor") ExecutorService executor) {
    return builder -> builder.executor(executor);
  }
}
