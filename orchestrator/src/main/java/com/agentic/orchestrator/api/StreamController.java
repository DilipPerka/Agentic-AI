package com.agentic.orchestrator.api;

import com.agentic.orchestrator.execution.EventLog;
import com.agentic.orchestrator.execution.RunEvent;
import java.io.IOException;
import java.util.function.Consumer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events over the same append-only log that serves the audit trail (CR4.9).
 *
 * <p>One mechanism, two consumers. A separate notification channel could drift from the log, and
 * then the live view and the audit trail would disagree about what happened — with no way to tell
 * which was lying.
 *
 * <p>A subscriber first receives a replay of everything after {@code since}, then live events. That
 * ordering is what makes reconnection safe: a client that lost its connection reconnects with its
 * last sequence number and misses nothing. Without replay, an SSE feed silently drops whatever
 * happened while it was down, which for an audit UI is the one unacceptable failure.
 */
@RestController
@RequestMapping("/api/v1")
public class StreamController {

    /**
     * Long enough that a quiet run does not churn connections, short enough that a dead client is
     * eventually released. The browser reconnects on its own, and replay makes that lossless.
     */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final EventLog events;

    public StreamController(EventLog events) {
        this.events = events;
    }

    /** Live feed for one run. */
    @GetMapping(value = "/runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRun(@PathVariable String id,
                                @RequestParam(defaultValue = "0") long since) {
        return open(event -> event.runId().equals(id), id, since);
    }

    /** Live feed for every run — what the dashboard watches. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAll() {
        return open(event -> true, null, Long.MAX_VALUE);
    }

    private SseEmitter open(java.util.function.Predicate<RunEvent> filter, String replayRunId,
                            long since) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        if (replayRunId != null && since != Long.MAX_VALUE) {
            try {
                for (RunEvent past : events.forRunSince(replayRunId, since)) {
                    emitter.send(SseEmitter.event().id(String.valueOf(past.seq()))
                            .name("run-event").data(past));
                }
            } catch (IOException | IllegalStateException clientGone) {
                // The client hung up during replay. Nothing to clean up beyond completing.
                emitter.complete();
                return emitter;
            }
        }

        Consumer<RunEvent> listener = event -> {
            if (!filter.test(event)) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(event.seq()))
                        .name("run-event").data(event));
            } catch (IOException | IllegalStateException clientGone) {
                // Thrown by design when the browser navigates away. Unsubscribing here is what stops
                // a closed tab from accumulating as a dead listener on every scheduler transition.
                emitter.completeWithError(clientGone);
            }
        };

        events.subscribe(listener);
        // All three fire exactly once, and all three must unsubscribe: a listener that outlives its
        // emitter is a slow leak that only shows up after a long demo.
        emitter.onCompletion(() -> events.unsubscribe(listener));
        emitter.onTimeout(() -> events.unsubscribe(listener));
        emitter.onError(error -> events.unsubscribe(listener));

        return emitter;
    }
}
