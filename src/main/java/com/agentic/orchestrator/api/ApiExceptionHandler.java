package com.agentic.orchestrator.api;

import com.agentic.orchestrator.plan.GraphValidationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidationFailure(
            MethodArgumentNotValidException exception) {
        List<String> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Invalid request", errors);
    }

    /**
     * A structurally invalid graph is a planner defect, not a client error. 500 would hide it in the
     * wrong bucket, so it gets its own status and the reason is returned rather than swallowed.
     */
    @ExceptionHandler(GraphValidationException.class)
    public ResponseEntity<Map<String, Object>> onInvalidGraph(GraphValidationException exception) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "Produced an invalid task graph",
                List.of(exception.getMessage()));
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message,
                                                      List<String> details) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "message", message,
                "details", details));
    }
}
