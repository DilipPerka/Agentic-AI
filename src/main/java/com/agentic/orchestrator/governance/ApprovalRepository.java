package com.agentic.orchestrator.governance;

import com.agentic.orchestrator.persistence.StatePersistence;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalRepository {

    private final Map<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private final StatePersistence persistence;

    public ApprovalRepository(StatePersistence persistence) {
        this.persistence = persistence;
    }

    /** Write-through. Called both when a request is raised and again when it is decided. */
    public ApprovalRequest save(ApprovalRequest request) {
        requests.put(request.id(), request);
        persistence.saveApproval(request);
        return request;
    }

    /** Repopulates the index from storage without writing back. */
    public void restore(ApprovalRequest request) {
        requests.put(request.id(), request);
    }

    public Optional<ApprovalRequest> findById(String id) {
        return Optional.ofNullable(requests.get(id));
    }

    /**
     * The open approval for a node, if any. The scheduler consults this before raising a new one:
     * a node whose entry gate is re-evaluated on every tick would otherwise flood the inbox with
     * duplicate requests for the same decision.
     */
    public Optional<ApprovalRequest> findPendingFor(String runId, String taskId) {
        return requests.values().stream()
                .filter(request -> request.runId().equals(runId)
                        && request.taskId().equals(taskId)
                        && request.isPending())
                .findFirst();
    }

    public Optional<ApprovalRequest> findDecidedFor(String runId, String taskId) {
        return requests.values().stream()
                .filter(request -> request.runId().equals(runId)
                        && request.taskId().equals(taskId)
                        && !request.isPending())
                .findFirst();
    }

    public List<ApprovalRequest> findAll() {
        return requests.values().stream()
                .sorted(Comparator.comparing(ApprovalRequest::requestedAt).reversed())
                .toList();
    }

    public List<ApprovalRequest> findByStatus(ApprovalRequest.Status status) {
        return findAll().stream().filter(request -> request.status() == status).toList();
    }

    public List<ApprovalRequest> findByRun(String runId) {
        return findAll().stream().filter(request -> request.runId().equals(runId)).toList();
    }

    public void clear() {
        requests.clear();
    }
}
