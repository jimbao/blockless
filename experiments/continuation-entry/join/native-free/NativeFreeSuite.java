import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Set;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/**
 * Runs existing tests unchanged, excluding the requirement this alternative intentionally removes.
 */
public final class NativeFreeSuite {
  public static void main(String[] args) {
    final PostDiscoveryFilter applicable =
        descriptor -> {
          if (descriptor.getSource().orElse(null) instanceof MethodSource method
              && method
                  .getClassName()
                  .equals("io.github.pjlabs.blockless.continuations.ContinuationsTest")
              && method.getMethodName().equals("missingExportFailsBeforeExecutingTheTask")) {
            return FilterResult.excluded(
                "The shadow implementation has no native-export requirement");
          }
          return FilterResult.included("Existing core or continuation behavior");
        };
    final var request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(Path.of(args[0]))))
            .filters(applicable)
            .build();
    final var listener = new SummaryGeneratingListener();
    final var launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(listener);
    launcher.execute(request);
    final var summary = listener.getSummary();
    final var output = new PrintWriter(System.out, true);
    summary.printTo(output);
    summary.printFailuresTo(output);
    if (summary.getTestsFoundCount() == 0 || !summary.getFailures().isEmpty()) System.exit(1);
  }
}
