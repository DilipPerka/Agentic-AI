package com.agentic.orchestrator.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentic.orchestrator.persistence.StatePersistence;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The live feed that backs SSE.
 *
 * <p>No Spring context: {@link StatePersistence#NONE} keeps this a test of the broadcast semantics
 * rather than of the database, and lets it use synthetic run ids that no foreign key would accept.
 */
class EventLogBroadcastTest {

    private final EventLog log = new EventLog(StatePersistence.NONE);

    @Test
    @DisplayName("subscribers receive events as they are appended, and stop when unsubscribed")
    void broadcastsToSubscribers() {
        List<RunEvent> received = new CopyOnWriteArrayList<>();
        Consumer<RunEvent> listener = received::add;

        log.subscribe(listener);
        log.append("run-x", EventType.NODE_STARTED, "T1", "hello");

        assertThat(received).hasSize(1);
        assertThat(received.get(0).type()).isEqualTo(EventType.NODE_STARTED);
        assertThat(received.get(0).seq()).isEqualTo(1);

        log.unsubscribe(listener);
        log.append("run-x", EventType.NODE_SUCCEEDED, "T1", "after unsubscribe");

        assertThat(received).hasSize(1);
    }

    @Test
    @DisplayName("a subscriber that throws is dropped and never fails the orchestration")
    void survivesABrokenSubscriber() {
        List<String> delivered = new ArrayList<>();
        log.subscribe(event -> {
            throw new IllegalStateException("subscriber exploded");
        });
        log.subscribe(event -> delivered.add(event.taskId()));

        // The scheduler appends events from inside its critical section. A throwing UI listener must
        // never propagate there: it would fail the node whose transition happened to produce it.
        log.append("run-y", EventType.NODE_STARTED, "T1", "still fine");
        log.append("run-y", EventType.NODE_SUCCEEDED, "T2", "and again");

        assertThat(delivered).containsExactly("T1", "T2");
    }

    @Test
    @DisplayName("appending still records the event even with no subscribers")
    void broadcastIsNotRequiredForRecording() {
        log.append("run-z", EventType.RUN_STARTED, null, "no listeners");

        assertThat(log.forRun("run-z")).hasSize(1);
    }

    @Test
    @DisplayName("replay from a sequence number returns only what followed it")
    void replaysFromASequenceNumber() {
        log.append("run-r", EventType.RUN_CREATED, null, "one");
        log.append("run-r", EventType.RUN_STARTED, null, "two");
        log.append("run-r", EventType.NODE_STARTED, "T1", "three");

        // This is what makes an SSE reconnect lossless: the client sends its last seq and receives
        // exactly the gap, with no duplicates and nothing skipped.
        assertThat(log.forRunSince("run-r", 1)).hasSize(2);
        assertThat(log.forRunSince("run-r", 3)).isEmpty();
    }
}
