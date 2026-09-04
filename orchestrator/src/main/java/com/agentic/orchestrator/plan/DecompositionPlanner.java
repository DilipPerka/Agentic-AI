package com.agentic.orchestrator.plan;

import com.agentic.orchestrator.requirement.Capability;
import com.agentic.orchestrator.requirement.Requirement;
import com.agentic.orchestrator.requirement.ScenarioType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Converts a normalised {@link Requirement} into an actionable task graph with dependencies and a
 * sequencing plan (CR2).
 *
 * <p>Decomposition is capability-driven rather than template-driven. Each detected capability
 * expands into its own design → schema → implement → test branch; those branches run in parallel
 * except where the capabilities' own dependency structure orders them. The result is that the graph
 * changes shape with the requirement instead of being the same fourteen boxes every time.
 *
 * <p>Three shapes fall out of one algorithm:
 * <ul>
 *   <li><b>Greenfield</b> — prerequisites are planned as work.
 *   <li><b>Brownfield</b> — an impact-analysis task precedes design, and prerequisites are assumed
 *       to exist rather than rebuilt.
 *   <li><b>Ambiguous</b> — planning stops at a human clarification task rather than guessing.
 * </ul>
 */
@Component
public class DecompositionPlanner {

    public static final String REQ_NORMALISE = "REQ_NORMALISE";
    public static final String CLARIFY = "CLARIFY";
    public static final String IMPACT_ANALYSIS = "IMPACT_ANALYSIS";
    public static final String ARCH_BASELINE = "ARCH_BASELINE";
    public static final String INTEGRATION_TEST = "INTEGRATION_TEST";
    public static final String DOCUMENTATION = "DOCUMENTATION";
    public static final String CODE_REVIEW = "CODE_REVIEW";
    public static final String RELEASE_READINESS = "RELEASE_READINESS";

    public Plan decompose(Requirement requirement) {
        return decompose(requirement, 1, null);
    }

    /**
     * Decomposes into a specific plan version.
     *
     * <p>Re-planning goes through this same method rather than a separate code path, so a revised
     * plan's shape follows from the amended requirement exactly as a first plan's does. A dedicated
     * "re-planner" would be a second implementation of decomposition, free to drift from the first.
     */
    public Plan decompose(Requirement requirement, int version, String supersedes) {
        List<Task> tasks = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        notes.add("Scenario classified as " + requirement.scenarioType()
                + " from " + requirement.capabilities().size() + " detected capability(ies).");

        tasks.add(requirementTask());
        String root = REQ_NORMALISE;

        if (!requirement.isPlannable()) {
            return haltForClarification(requirement, tasks, dependencies, notes, version,
                    supersedes);
        }

        if (requirement.scenarioType() == ScenarioType.BROWNFIELD) {
            tasks.add(impactAnalysisTask());
            dependencies.add(Dependency.data(root, IMPACT_ANALYSIS,
                    "Impact analysis needs the normalised requirement"));
            root = IMPACT_ANALYSIS;
            if (!requirement.assumedExisting().isEmpty()) {
                notes.add("Assumed already present in the codebase (not planned as work): "
                        + String.join(", ", requirement.assumedExisting())
                        + ". Impact analysis must confirm this.");
            }
        }

        if (!requirement.ambiguities().isEmpty()) {
            notes.add(requirement.ambiguities().size() + " ambiguity(ies) recorded but planning "
                    + "proceeded, because concrete capabilities were also present. These become "
                    + "assumptions to confirm, not blockers.");
        }

        tasks.add(architectureTask(requirement.scenarioType() == ScenarioType.BROWNFIELD));
        dependencies.add(Dependency.data(root, ARCH_BASELINE,
                "Architecture baseline follows requirement analysis"));

        List<String> implIds = new ArrayList<>();
        List<String> testIds = new ArrayList<>();
        List<String> designIds = new ArrayList<>();

        for (Capability capability : requirement.capabilities()) {
            String design = "DESIGN_" + capability.id();
            String schema = "SCHEMA_" + capability.id();
            String impl = "IMPL_" + capability.id();
            String test = "TEST_" + capability.id();

            tasks.add(designTask(design, capability));
            dependencies.add(Dependency.data(ARCH_BASELINE, design,
                    "Feature design refines the architecture baseline"));
            designIds.add(design);

            List<String> implPredecessors = new ArrayList<>(List.of(design));
            if (capability.requiresSchemaChange()) {
                tasks.add(schemaTask(schema, capability));
                dependencies.add(Dependency.data(design, schema,
                        "Schema follows from the feature design"));
                implPredecessors.add(schema);
            }

            tasks.add(implementationTask(impl, capability));
            for (String predecessor : implPredecessors) {
                dependencies.add(Dependency.data(predecessor, impl,
                        "Implementation consumes " + predecessor));
            }
            implIds.add(impl);

            tasks.add(testTask(test, capability));
            dependencies.add(Dependency.data(impl, test,
                    "Tests exercise the implementation"));
            testIds.add(test);
        }

        // Cross-capability sequencing: a capability cannot be implemented before the capabilities it
        // builds on. Only applies where the prerequisite is itself planned work — in brownfield it
        // already exists and is recorded as an assumption instead.
        Set<String> planned = new LinkedHashSet<>();
        requirement.capabilities().forEach(capability -> planned.add(capability.id()));
        for (Capability capability : requirement.capabilities()) {
            for (String prerequisite : capability.dependsOn()) {
                if (planned.contains(prerequisite)) {
                    dependencies.add(Dependency.data("IMPL_" + prerequisite, "IMPL_" + capability.id(),
                            capability.displayName() + " depends on " + prerequisite));
                }
            }
        }

        tasks.add(integrationTestTask());
        for (String test : testIds) {
            dependencies.add(Dependency.data(test, INTEGRATION_TEST,
                    "Integration tests join all feature test branches"));
        }

        // Documentation depends on implementation, not on testing, so it runs in parallel with the
        // test branch instead of queueing behind it.
        tasks.add(documentationTask());
        for (String impl : implIds) {
            dependencies.add(Dependency.data(impl, DOCUMENTATION,
                    "Documentation describes the implemented behaviour"));
        }

        tasks.add(codeReviewTask());
        dependencies.add(Dependency.data(INTEGRATION_TEST, CODE_REVIEW,
                "Review reads the change plus its test evidence"));

        tasks.add(releaseReadinessTask());
        dependencies.add(Dependency.data(CODE_REVIEW, RELEASE_READINESS,
                "Release readiness requires a completed review"));
        dependencies.add(Dependency.data(DOCUMENTATION, RELEASE_READINESS,
                "Release readiness requires documentation"));

        return assemble(requirement, tasks, dependencies, notes, designIds, version, supersedes);
    }

    private Plan haltForClarification(Requirement requirement, List<Task> tasks,
                                      List<Dependency> dependencies, List<String> notes,
                                      int version, String supersedes) {
        tasks.add(clarificationTask(requirement.ambiguities().size()));
        dependencies.add(Dependency.data(REQ_NORMALISE, CLARIFY,
                "Clarification needs the analyst's reading of the request"));

        notes.add("Planning halted at CLARIFY: no concrete capability could be extracted, so any "
                + "further decomposition would be invention rather than analysis.");
        notes.add("The graph will be re-planned once the clarification is answered; the answer "
                + "changes the requirement, and the requirement determines the shape.");

        TaskGraph graph = TaskGraph.of(tasks, dependencies);
        return new Plan(newPlanId(), requirement, tasks, graph.dependencies(),
                graph.executionLevels(), notes, Instant.now(), version, supersedes);
    }

    private Plan assemble(Requirement requirement, List<Task> tasks, List<Dependency> dependencies,
                          List<String> notes, List<String> designIds, int version,
                          String supersedes) {
        TaskGraph initial = TaskGraph.of(tasks, dependencies);

        List<Dependency> conflictEdges = initial.writeConflictEdges();
        List<Dependency> allDependencies = new ArrayList<>(initial.dependencies());
        allDependencies.addAll(conflictEdges);

        for (Dependency edge : conflictEdges) {
            notes.add("Added ordering " + edge.from() + " -> " + edge.to() + ". " + edge.reason()
                    + ". Left parallel, two agents would edit the same component concurrently.");
        }

        TaskGraph resolved = TaskGraph.of(tasks, allDependencies);
        List<List<String>> levels = resolved.executionLevels();

        int widest = levels.stream().mapToInt(List::size).max().orElse(0);
        notes.add("Sequencing: " + tasks.size() + " tasks across " + levels.size()
                + " levels, widest level " + widest + " tasks.");
        notes.add("Feature design branches (" + designIds.size()
                + ") are independent and dispatch in parallel once the architecture baseline lands.");

        return new Plan(newPlanId(), requirement, tasks, resolved.dependencies(), levels, notes,
                Instant.now(), version, supersedes);
    }

    private static String newPlanId() {
        return "plan-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- task factories

    private Task requirementTask() {
        return new Task(REQ_NORMALISE,
                "Normalise the requirement",
                "Interpret intent, extract concrete capabilities, and record anything under-specified.",
                Stage.REQUIREMENTS, AgentRole.REQUIREMENT_ANALYST, BlastRadius.LOW,
                List.of("A normalised statement exists",
                        "Every extracted capability is named",
                        "Ambiguities are recorded with options, not just flagged"),
                Set.of(), Set.of("artifact:requirement-spec"), false);
    }

    private Task clarificationTask(int ambiguityCount) {
        return new Task(CLARIFY,
                "Clarify the requirement with a human",
                "Present " + ambiguityCount + " under-specified term(s) with options and obtain a "
                        + "decision before any further planning.",
                Stage.CLARIFICATION, AgentRole.HUMAN, BlastRadius.LOW,
                List.of("Every recorded ambiguity has a human answer",
                        "The answers are written back into the requirement",
                        "Re-planning is triggered from the amended requirement"),
                Set.of("artifact:requirement-spec"), Set.of("artifact:clarification"), true);
    }

    private Task impactAnalysisTask() {
        return new Task(IMPACT_ANALYSIS,
                "Analyse impact on the existing codebase",
                "Identify affected modules, APIs, data flows and hot paths before any code changes.",
                Stage.IMPACT_ANALYSIS, AgentRole.CODEBASE_ANALYST, BlastRadius.LOW,
                List.of("Affected components are listed with the reason each is affected",
                        "Assumed-existing capabilities are confirmed to actually exist",
                        "Hot-path and schema impacts are called out explicitly"),
                Set.of("artifact:requirement-spec"), Set.of("artifact:impact-analysis"), false);
    }

    private Task architectureTask(boolean brownfield) {
        // Declared reads must match what will actually exist. In greenfield there is no impact
        // analysis to read, and declaring it anyway would break the fingerprinting the execution
        // engine will later derive from these declarations.
        Set<String> reads = brownfield
                ? Set.of("artifact:requirement-spec", "artifact:impact-analysis")
                : Set.of("artifact:requirement-spec");

        return new Task(ARCH_BASELINE,
                "Establish the architecture baseline",
                "Define module boundaries, the API surface and the data model the features share.",
                Stage.DESIGN, AgentRole.ARCHITECT, BlastRadius.MEDIUM,
                List.of("Module boundaries and dependency direction are stated",
                        "Shared data model is defined",
                        "Each decision records the alternative that was rejected"),
                reads, Set.of("artifact:architecture"), false);
    }

    private Task designTask(String id, Capability capability) {
        return new Task(id,
                "Design: " + capability.displayName(),
                "Detailed design for " + capability.displayName()
                        + ", including API contract and error behaviour.",
                Stage.DESIGN, AgentRole.ARCHITECT, BlastRadius.LOW,
                List.of("API contract is specified including failure responses",
                        "Design fits within the architecture baseline",
                        "Components to be touched are named"),
                Set.of("artifact:architecture"),
                Set.of("artifact:design:" + capability.id()), false);
    }

    private Task schemaTask(String id, Capability capability) {
        return new Task(id,
                "Schema change: " + capability.displayName(),
                "Author the migration for " + capability.displayName()
                        + ". Schema changes are hard to reverse once data exists.",
                Stage.IMPLEMENTATION, AgentRole.IMPLEMENTER, BlastRadius.HIGH,
                List.of("Migration applies cleanly against the current schema",
                        "Change is additive or has a documented backfill",
                        "Rollback path is stated"),
                Set.of("artifact:design:" + capability.id()),
                Set.of("migration:" + capability.id()), true);
    }

    private Task implementationTask(String id, Capability capability) {
        BlastRadius radius = capability.touchesHotPath() ? BlastRadius.HIGH : BlastRadius.MEDIUM;
        Set<String> reads = new LinkedHashSet<>();
        reads.add("artifact:design:" + capability.id());
        if (capability.requiresSchemaChange()) {
            reads.add("migration:" + capability.id());
        }

        return new Task(id,
                "Implement: " + capability.displayName(),
                "Build " + capability.displayName()
                        + (capability.touchesHotPath()
                        ? ". Touches the request hot path, so latency regression is a real risk."
                        : "."),
                Stage.IMPLEMENTATION, AgentRole.IMPLEMENTER, radius,
                List.of("Code compiles and matches the design contract",
                        "Input validation and error handling are present",
                        capability.touchesHotPath()
                                ? "No measurable latency regression on the hot path"
                                : "No change to existing public behaviour"),
                reads, capability.componentsTouched(), radius == BlastRadius.HIGH);
    }

    private Task testTask(String id, Capability capability) {
        return new Task(id,
                "Test: " + capability.displayName(),
                "Unit and slice tests for " + capability.displayName()
                        + ", including failure and boundary cases.",
                Stage.TESTING, AgentRole.TEST_ENGINEER, BlastRadius.LOW,
                List.of("Happy path covered",
                        "Failure and boundary cases covered",
                        "Tests fail if the implementation is reverted"),
                capability.componentsTouched(), Set.of("test:" + capability.id()), false);
    }

    private Task integrationTestTask() {
        return new Task(INTEGRATION_TEST,
                "Integration tests across features",
                "Exercise the features together end to end, which is where interactions between "
                        + "independently designed branches actually surface.",
                Stage.TESTING, AgentRole.TEST_ENGINEER, BlastRadius.LOW,
                List.of("End-to-end flow passes against a running context",
                        "Cross-feature interactions are covered",
                        "No pre-existing test regressed"),
                Set.of(), Set.of("test:integration"), false);
    }

    private Task documentationTask() {
        return new Task(DOCUMENTATION,
                "Write user and API documentation",
                "Document the API surface, setup and operational behaviour.",
                Stage.DOCUMENTATION, AgentRole.DOC_WRITER, BlastRadius.LOW,
                List.of("Every public endpoint is documented with request and response examples",
                        "Setup instructions are verified from a clean checkout",
                        "Known limitations are stated rather than omitted"),
                Set.of("artifact:architecture"), Set.of("artifact:docs"), false);
    }

    private Task codeReviewTask() {
        return new Task(CODE_REVIEW,
                "Review the change",
                "Assess correctness, security and maintainability against the design and evidence.",
                Stage.REVIEW, AgentRole.REVIEWER, BlastRadius.LOW,
                List.of("Every finding cites a file and a reason",
                        "Security-relevant changes are explicitly assessed",
                        "Verdict is APPROVE or CHANGES_REQUESTED, never silence"),
                Set.of("test:integration"), Set.of("artifact:review"), false);
    }

    private Task releaseReadinessTask() {
        return new Task(RELEASE_READINESS,
                "Assess release readiness",
                "Confirm the change is safe to ship: evidence complete, risks recorded, rollback known.",
                Stage.RELEASE_READINESS, AgentRole.RELEASE_MANAGER, BlastRadius.MEDIUM,
                List.of("All gate evidence is present and passing",
                        "Outstanding risks and any waivers are listed",
                        "A rollback procedure is documented"),
                Set.of("artifact:review", "artifact:docs"), Set.of("artifact:release-note"), true);
    }
}
