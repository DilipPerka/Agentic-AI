package com.agentic.orchestrator.execution;

import com.agentic.orchestrator.persistence.StatePersistence;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Append-only event log, one sequence per run.
 *
 * <p>Append-only is the point: entries are never updated or removed, so the log is a record of what
 * happened rather than a view of what is currently true. That is what makes it usable as an audit
 * trail, and the same stream will feed the live UI without a second mechanism.
 *
 * <p>Sequence numbers are allocated under the same atomic that appends, so a reader paging with
 * {@code since} can never miss an entry or see one twice.
 */
@Component
public class EventLog {

    private static final String SCHEDULER = "SCHEDULER";

    private final Map<String, List<RunEvent>> byRun = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final List<Consumer<RunEvent>> listeners = new CopyOnWriteArrayList<>();
    private final StatePersistence persistence;

    public EventLog(StatePersistence persistence) {
        this.persistence = persistence;
    }

    /**
     * Registers a live subscriber — the SSE feed is the only one today.
     *
     * <p>Listeners are notified <em>outside</em> the append lock, so a slow or wedged subscriber can
     * never stall the scheduler thread that produced the event. The cost of that choice is that two
     * events can reach a listener out of order under contention; every listener therefore gets the
     * sequence number and is expected to order by it rather than by arrival.
     */
    public void subscribe(Consumer<RunEvent> listener) {
        listeners.add(listener);
    }

    public void unsubscribe(Consumer<RunEvent> listener) {
        listeners.remove(listener);
    }

    public RunEvent append(String runId, EventType type, String taskId, String actor,
                           String message) {
        List<RunEvent> log = byRun.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>());
        RunEvent event;
        // Allocating the sequence and appending must be one atomic step. Done separately, two
        // concurrent nodes can take seq 1 and 2 and then insert in the opposite order, leaving a log
        // whose list order disagrees with its own sequence numbers. The durable write happens here
        // too, so a sequence number is never handed out for an entry that was not stored.
        synchronized (log) {
            long seq = sequences.computeIfAbsent(runId, key -> new AtomicLong()).incrementAndGet();
            event = new RunEvent(seq, runId, taskId, type, actor, message, Instant.now());
            log.add(event);
            persistence.appendEvent(event);
        }
        publish(event);
        return event;
    }

    private void publish(RunEvent event) {
        for (Consumer<RunEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException broken) {
                // A failed subscriber is a UI problem, never an orchestration one. Letting this
                // escape would fail the node whose transition happened to produce the event.
                listeners.remove(listener);
            }
        }
    }

    /** Repopulates the log from storage, restoring each run's sequence counter to its high mark. */
    public void restore(List<RunEvent> stored) {
        for (RunEvent event : stored) {
            byRun.computeIfAbsent(event.runId(), key -> new CopyOnWriteArrayList<>()).add(event);
            sequences.computeIfAbsent(event.runId(), key -> new AtomicLong())
                    .updateAndGet(current -> Math.max(current, event.seq()));
        }
    }

    public RunEvent append(String runId, EventType type, String taskId, String message) {
        return append(runId, type, taskId, SCHEDULER, message);
    }

    public List<RunEvent> forRun(String runId) {
        return List.copyOf(byRun.getOrDefault(runId, List.of()));
    }

    /** Entries with {@code seq > since}, for incremental polling and, later, SSE resume. */
    public List<RunEvent> forRunSince(String runId, long since) {
        return byRun.getOrDefault(runId, List.of()).stream()
                .filter(event -> event.seq() > since)
                .toList();
    }

    public void clear() {
        byRun.clear();
        sequences.clear();
    }
}
