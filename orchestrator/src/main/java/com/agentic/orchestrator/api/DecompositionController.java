package com.agentic.orchestrator.api;

import com.agentic.orchestrator.plan.DecompositionPlanner;
import com.agentic.orchestrator.plan.MermaidRenderer;
import com.agentic.orchestrator.plan.Plan;
import com.agentic.orchestrator.plan.PlanRepository;
import com.agentic.orchestrator.requirement.Requirement;
import com.agentic.orchestrator.requirement.RequirementNormalizer;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DecompositionController {

    private final RequirementNormalizer normalizer;
    private final DecompositionPlanner planner;
    private final PlanRepository plans;
    private final MermaidRenderer renderer;

    public DecompositionController(RequirementNormalizer normalizer,
                                   DecompositionPlanner planner,
                                   PlanRepository plans,
                                   MermaidRenderer renderer) {
        this.normalizer = normalizer;
        this.planner = planner;
        this.plans = plans;
        this.renderer = renderer;
    }

    /** Requirement in, task graph out. */
    @PostMapping("/decompose")
    public PlanResponse decompose(@Valid @RequestBody DecomposeRequest request) {
        Requirement requirement =
                normalizer.normalize(request.requirement(), request.scenarioHint());
        Plan plan = plans.save(planner.decompose(requirement));
        return PlanResponse.of(plan);
    }

    @GetMapping("/plans")
    public List<PlanResponse> list() {
        return plans.findAll().stream().map(PlanResponse::of).toList();
    }

    @GetMapping("/plans/{id}")
    public ResponseEntity<PlanResponse> get(@PathVariable String id) {
        return plans.findById(id)
                .map(PlanResponse::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Mermaid source for the task graph. */
    @GetMapping(value = "/plans/{id}/mermaid", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> mermaid(@PathVariable String id) {
        return plans.findById(id)
                .map(renderer::render)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The sequencing as readable text: which tasks run together, in which order. */
    @GetMapping(value = "/plans/{id}/schedule", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> schedule(@PathVariable String id) {
        return plans.findById(id)
                .map(renderer::renderSchedule)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
