package com.agentic.orchestrator.api;

import com.agentic.orchestrator.execution.RunScheduler;
import com.agentic.orchestrator.governance.ApprovalRepository;
import com.agentic.orchestrator.governance.ApprovalRequest;
import com.agentic.orchestrator.governance.Policy;
import com.agentic.orchestrator.governance.PolicyEngine;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The human oversight surface: the approval inbox and the policy catalogue. */
@RestController
@RequestMapping("/api/v1")
public class ApprovalController {

    private final ApprovalRepository approvals;
    private final RunScheduler scheduler;
    private final PolicyEngine policies;

    public ApprovalController(ApprovalRepository approvals, RunScheduler scheduler,
                              PolicyEngine policies) {
        this.approvals = approvals;
        this.scheduler = scheduler;
        this.policies = policies;
    }

    /** The inbox. Defaults to what still needs a decision. */
    @GetMapping("/approvals")
    public List<ApprovalView> list(@RequestParam(required = false) ApprovalRequest.Status status,
                                   @RequestParam(required = false) String runId) {
        List<ApprovalRequest> found = runId != null
                ? approvals.findByRun(runId)
                : (status == null ? approvals.findByStatus(ApprovalRequest.Status.PENDING)
                                  : approvals.findByStatus(status));

        return found.stream()
                .filter(request -> status == null || request.status() == status)
                .map(ApprovalView::of)
                .toList();
    }

    @GetMapping("/approvals/{id}")
    public ResponseEntity<ApprovalView> get(@PathVariable String id) {
        return approvals.findById(id)
                .map(ApprovalView::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/approvals/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable String id,
            @RequestParam(defaultValue = "operator") String decidedBy,
            @RequestParam(required = false) String note) {

        return decide(id, scheduler.approve(id, decidedBy, note), "approved");
    }

    @PostMapping("/approvals/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable String id,
            @RequestParam(defaultValue = "operator") String decidedBy,
            @RequestParam(required = false) String guidance) {

        return decide(id, scheduler.reject(id, decidedBy, guidance), "rejected");
    }

    private ResponseEntity<Map<String, Object>> decide(String id, boolean applied, String verb) {
        if (approvals.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!applied) {
            // Already decided, or the run has since ended. Reporting this as success would be a lie
            // that hides a real race between two approvers.
            return ResponseEntity.status(409).body(Map.of(
                    "approvalId", id,
                    "applied", false,
                    "detail", "Approval already decided, or its run is no longer active."));
        }
        return ResponseEntity.ok(Map.of("approvalId", id, "applied", true, "outcome", verb));
    }

    /** The policy catalogue, so a reviewer can see what the guardrails actually are. */
    @GetMapping("/policies")
    public List<Map<String, String>> policies() {
        return policies.all().stream()
                .map(policy -> Map.of(
                        "id", policy.id(),
                        "name", policy.name(),
                        "category", policy.category().name(),
                        "effect", policy.effect().name(),
                        "rationale", policy.rationale()))
                .toList();
    }
}
