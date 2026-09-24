package co.rsk.peg;

import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs tasks at the same time on daemon threads, one per task, and fails instead of hanging when one never finishes. */
public final class ConcurrentRuns {

    private static final Duration LIMIT = Duration.ofMinutes(2);

    private ConcurrentRuns() {
    }

    public static <T> List<T> run(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size(), runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<T> results = new ArrayList<>();
            for (Future<T> future : pool.invokeAll(tasks, LIMIT.toSeconds(), TimeUnit.SECONDS)) {
                if (future.isCancelled()) {
                    fail("a task did not finish within " + LIMIT);
                }
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
