# Virtual-thread join: what we can reuse

Joining one virtual thread from another uses the same wait machinery already
present in Blockless. This was checked against the bundled Java 21 and 25 source
and executable probes using the actual current `Continuations` implementation.

```text
target.join()                      supplier.get()
    |                                  |
termination CountDownLatch        outcome CountDownLatch
    +---------------+------------------+
                    |
             AQS records caller
                    |
             LockSupport.park()
                    |
       caller VT parks its continuation
```

The target supplies the completion condition. The caller supplies the continuation
that parks and later resumes. The probes inspect both continuation identities and
verify reuse of the same sole physical carrier, with no replacement worker.

| Waiting caller | Observed behavior |
|---|---|
| Virtual thread joining a virtual child | Caller parks; its same carrier handles another task. |
| Virtual thread calling actual supplier.get | Same carrier release; locals, ThreadLocal, locks, finally and result visibility retained. |
| Platform thread joining a virtual child | Platform worker stays occupied until the target finishes. |
| Platform thread inside a native continuation joining a virtual child | Still waits: standard LockSupport checks virtual Thread identity, not arbitrary native scopes. |

The JDK also automatically inherits a parent's virtual-thread scheduler when the
child is created through public `Thread.startVirtualThread`. The independent child
inheritance probe verifies that parent, child and marker all use one carrier.

## Implementation consequences

- Keep the cached outcome wait: `OutcomeSupplier.get()` already uses the same
  CountDownLatch/AQS/LockSupport chain. A helper join does not add a caller entry.
- Preserve the ready-result fast path. The actual supplier's callback can run on
  its producer before that producer terminates and safely read get(). An
  unconditional producer.join would self-deadlock there.
- Keep value/failure caching. Thread.join itself provides no returned value or
  propagation of the target's exception.
- Preserve interruption semantics: join throws a checked InterruptedException and
  clears the caller flag; get wraps that exception and restores the flag. Neither
  automatically cancels the target's I/O.
- Create logical caller identity at the automatic task/request entry. Merely
  wrapping a synchronous method returning String onto a VT leaves its outer
  platform caller waiting for that String; the optional agent probe verifies this.

Full source tracing and URLs are in [JDK-JOIN-TRACE.md](JDK-JOIN-TRACE.md).
No production code was changed by these experiments.

The [native-free runtime alternative](native-free/README.md) puts the reusable
pieces together: construct the virtual producer at the factory call site, dispatch
its start, and retain the existing cached outcome wait. It removes the internal
native waiter while keeping the current outer-caller restriction explicit.

## Run

```sh
JAVA_HOME=/path/to/jdk21 ASM_JAR=/path/to/asm-9.8.jar \
  bash experiments/continuation-entry/run-join-probes.sh
JAVA_HOME=/path/to/jdk25 ASM_JAR=/path/to/asm-9.8.jar \
  bash experiments/continuation-entry/run-join-probes.sh
```

The basic probes need only the JDK; ASM enables the optional method-entry agent.
The script builds the checked-out core and continuation sources directly with
javac, requiring no Maven cache. It compiles with each selected JDK's default
class-file target. Timed joins, interruption, 1,000 completion/publication races,
callback readiness, context and finally checks are included.
