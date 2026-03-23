package org.opentripplanner.ext.demandresponsivetransportation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides a shared thread pool for DRT I/O operations (Shotl API calls).
 * <p>
 * DRT API calls are I/O-bound (threads block waiting for HTTP responses), so
 * {@code parallelStream()} — which sizes its thread pool to CPU core count — is
 * the wrong tool. On a single-core machine, {@code parallelStream()} runs
 * everything sequentially, even though the CPU is idle while waiting for responses.
 * <p>
 * This executor provides a fixed pool of daemon threads sized to match the HTTP
 * connection pool ({@code DRT_MAX_CONN_PER_ROUTE = 20}), enabling concurrent
 * Shotl API calls regardless of CPU core count.
 */
public final class DrtIoExecutor {

  /**
   * Number of threads for concurrent DRT API calls. Matches the per-host HTTP
   * connection limit in {@code DemandResponsiveTransportationServicesModule} so
   * that each thread can have its own connection without contention.
   */
  private static final int DRT_IO_THREADS = 20;

  private static final AtomicInteger THREAD_COUNT = new AtomicInteger(0);

  private static final ExecutorService INSTANCE = Executors.newFixedThreadPool(
    DRT_IO_THREADS,
    r -> {
      Thread t = new Thread(r, "drt-io-" + THREAD_COUNT.getAndIncrement());
      t.setDaemon(true);
      return t;
    }
  );

  public static ExecutorService getInstance() {
    return INSTANCE;
  }

  private DrtIoExecutor() {}
}
