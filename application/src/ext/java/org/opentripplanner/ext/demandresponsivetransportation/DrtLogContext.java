package org.opentripplanner.ext.demandresponsivetransportation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Carries the caller's log context (the slf4j MDC, where {@code server.traceParameters} puts the
 * request's {@code correlationId}) to the threads that do DRT work for that request, so their log
 * lines name the same request. A task takes the MDC of the thread that creates it and restores
 * the worker's own MDC when it ends, so a pooled thread never keeps a finished request's id.
 */
public final class DrtLogContext {

  private DrtLogContext() {}

  /** The task, run under the log context of the thread calling this method. */
  public static Runnable withCallerContext(Runnable task) {
    var callerContext = MDC.getCopyOfContextMap();
    return () -> {
      var workerContext = MDC.getCopyOfContextMap();
      setContext(callerContext);
      try {
        task.run();
      } finally {
        setContext(workerContext);
      }
    };
  }

  /** The supplier, run under the log context of the thread calling this method. */
  public static <T> Supplier<T> supplierWithCallerContext(Supplier<T> task) {
    var callerContext = MDC.getCopyOfContextMap();
    return () -> {
      var workerContext = MDC.getCopyOfContextMap();
      setContext(callerContext);
      try {
        return task.get();
      } finally {
        setContext(workerContext);
      }
    };
  }

  /** An executor that runs every task under the log context of the thread that submitted it. */
  public static ExecutorService propagating(ExecutorService delegate) {
    return new PropagatingExecutorService(delegate);
  }

  private static void setContext(Map<String, String> context) {
    if (context == null || context.isEmpty()) {
      MDC.clear();
    } else {
      MDC.setContextMap(context);
    }
  }

  private static final class PropagatingExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;

    private PropagatingExecutorService(ExecutorService delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
      delegate.execute(withCallerContext(command));
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }
  }
}
