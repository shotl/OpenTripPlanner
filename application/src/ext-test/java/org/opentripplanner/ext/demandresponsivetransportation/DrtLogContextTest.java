package org.opentripplanner.ext.demandresponsivetransportation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** The request's log context (its correlationId) reaches the threads doing its DRT work. */
class DrtLogContextTest {

  private static final String KEY = "correlationId";

  @AfterEach
  void clear() {
    MDC.clear();
  }

  @Test
  void theDrtIoPoolRunsATaskUnderItsCallersContext() {
    MDC.put(KEY, "quote-1");

    var seen = CompletableFuture.supplyAsync(
      () -> MDC.get(KEY),
      DrtIoExecutor.getInstance()
    ).join();

    assertEquals("quote-1", seen);
  }

  @Test
  void aPooledThreadDoesNotKeepAFinishedRequestsContext() {
    var single = DrtLogContext.propagating(Executors.newSingleThreadExecutor());
    try {
      MDC.put(KEY, "quote-1");
      assertEquals("quote-1", CompletableFuture.supplyAsync(() -> MDC.get(KEY), single).join());

      MDC.clear();
      assertNull(
        CompletableFuture.supplyAsync(() -> MDC.get(KEY), single).join(),
        "the same thread, asked by a caller without an id, has none"
      );

      MDC.put(KEY, "quote-2");
      assertEquals("quote-2", CompletableFuture.supplyAsync(() -> MDC.get(KEY), single).join());
    } finally {
      single.shutdownNow();
    }
  }

  @Test
  void aWrappedRunnableAndSupplierCarryTheContextToTheCommonPool() {
    MDC.put(KEY, "quote-3");
    var seen = new String[1];

    CompletableFuture.runAsync(
      DrtLogContext.withCallerContext(() -> {
        seen[0] = MDC.get(KEY);
      })
    ).join();
    var supplied = CompletableFuture.supplyAsync(
      DrtLogContext.supplierWithCallerContext(() -> MDC.get(KEY))
    ).join();

    assertEquals("quote-3", seen[0]);
    assertEquals("quote-3", supplied);
  }

  @Test
  void aTaskRunOnTheCallingThreadGivesItsContextBack() {
    MDC.put(KEY, "worker");
    var task = withContext("caller");

    task.run();

    assertEquals("worker", MDC.get(KEY));
  }

  private static Runnable withContext(String id) {
    var previous = MDC.getCopyOfContextMap();
    MDC.put(KEY, id);
    var task = DrtLogContext.withCallerContext(() -> assertEquals(id, MDC.get(KEY)));
    MDC.setContextMap(previous);
    return task;
  }
}
