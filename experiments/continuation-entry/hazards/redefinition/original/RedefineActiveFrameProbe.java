import java.lang.instrument.ClassDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RedefineActiveFrameProbe {
  public static void main(String[] args) throws Exception {
    var first = new CompletableFuture<String>();
    var worker =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    first.complete(Target.awaitValue());
                  } catch (Throwable failure) {
                    first.completeExceptionally(failure);
                  }
                });
    if (!Target.entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("entry timeout");
    Agent.instrumentation.redefineClasses(
        new ClassDefinition(Target.class, Files.readAllBytes(Path.of(args[0]))));
    Target.release.countDown();
    var activeResult = first.get(5, TimeUnit.SECONDS);
    var nextResult = Target.awaitValue();
    worker.join();
    System.out.println("Existing frame after redefine: " + activeResult);
    System.out.println("New invocation after redefine: " + nextResult);
    if (!activeResult.equals("original active method")) throw new AssertionError(activeResult);
    if (!nextResult.equals("replacement method")) throw new AssertionError(nextResult);
  }
}
