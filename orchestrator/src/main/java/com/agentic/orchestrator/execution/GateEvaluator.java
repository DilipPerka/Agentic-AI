package com.agentic.orchestrator.execution;

import com.agentic.orchestrator.governance.GovernanceVerdict;
import com.agentic.orchestrator.plan.Task;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Evaluates entry and exit gates.
 *
 * <p>The exit gate is the load-bearing one. A node does not become {@code SUCCEEDED} because its
 * executor said so — it becomes SUCCEEDED because the gate found the evidence the task declared it
 * would produce. An agent that reports success while producing nothing fails, and that is the single
 * rule that separates an orchestrator from a progress bar.
 *
 * <p>Today the only evidence available is the declared {@code writes} of a task. When real tools
 * arrive, {@code BuildGreen} and {@code TestsPassed} become additional checks here, reading compiler
 * and Surefire output. The shape does not change; only the check list grows.
 */
@Component
public class GateEvaluator {

    public GateResult entryGate(Task task,
                                boolean dependenciesSatisfied,
                                GovernanceVerdict governance,
                                boolean approvalGranted,
                                boolean runActive) {

        List<CheckResult> checks = new ArrayList<>();

        checks.add(dependenciesSatisfied
                ? CheckResult.pass("DependenciesSatisfied", "All predecessors succeeded")
                : CheckResult.fail("DependenciesSatisfied", "Predecessors not yet complete"));

        checks.add(governance.denied()
                ? CheckResult.fail("PolicyPermits",
                        "Denied by " + governance.denyingPolicy() + ". " + governance.summary())
                : CheckResult.pass("PolicyPermits", "No policy denies this action"));

        if (governance.requiresApproval()) {
            checks.add(approvalGranted
                    ? CheckResult.pass("ApprovalGranted", "Human approval on record")
                    : CheckResult.fail("ApprovalGranted", "Awaiting human approval"));
        } else {
            checks.add(CheckResult.pass("ApprovalGranted", "No approval required"));
        }

        checks.add(runActive
                ? CheckResult.pass("RunActive", "Run is accepting work")
                : CheckResult.fail("RunActive", "Run is paused, stopping or terminal"));

        return GateResult.of("EntryGate[" + task.id() + "]", checks);
    }

    public GateResult exitGate(Task task, NodeResult result) {
        List<CheckResult> checks = new ArrayList<>();

        checks.add(result.success()
                ? CheckResult.pass("ExecutorReportedSuccess", result.summary())
                : CheckResult.fail("ExecutorReportedSuccess", result.summary()));

        Set<String> missing = new LinkedHashSet<>(task.writes());
        missing.removeAll(result.outputs().keySet());

        // The executor's claim is checked against what it actually produced. Only meaningful when
        // the task declared outputs; a task that declares none cannot fail this check.
        if (task.writes().isEmpty()) {
            checks.add(CheckResult.pass("EvidenceProduced", "Task declares no outputs"));
        } else if (missing.isEmpty()) {
            checks.add(CheckResult.pass("EvidenceProduced",
                    "All " + task.writes().size() + " declared outputs present"));
        } else {
            checks.add(CheckResult.fail("EvidenceProduced",
                    "Reported success but produced no evidence for: " + String.join(", ", missing)));
        }

        buildChecks(result, checks);

        return GateResult.of("ExitGate[" + task.id() + "]", checks);
    }

    /**
     * Checks the build and test evidence a real executor records.
     *
     * <p>Only applied when the evidence is present. A documentation node runs no compiler, and
     * demanding a build result from it would fail nodes for not doing something they were never
     * asked to do. What is <em>not</em> acceptable is treating absent evidence as a pass when the
     * executor did try: {@code tests.run} present but null-valued means the totals could not be read,
     * and unread is not the same as zero failures.
     */
    private void buildChecks(NodeResult result, List<CheckResult> checks) {
        Map<String, String> outputs = result.outputs();

        String buildExit = outputs.get("build.exitCode");
        if (buildExit != null) {
            checks.add("0".equals(buildExit)
                    ? CheckResult.pass("BuildGreen", "Compiler exited 0")
                    : CheckResult.fail("BuildGreen", "Compiler exited " + buildExit));
        }

        String testsRun = outputs.get("tests.run");
        if (testsRun != null) {
            String failures = outputs.get("tests.failures");
            String errors = outputs.get("tests.errors");

            if ("null".equals(testsRun) || "null".equals(failures)) {
                checks.add(CheckResult.fail("TestsPassed",
                        "Test totals could not be read; the outcome is unproven"));
            } else if (!"0".equals(failures) || !"0".equals(errors)) {
                checks.add(CheckResult.fail("TestsPassed",
                        failures + " failure(s), " + errors + " error(s) in " + testsRun + " test(s)"));
            } else if ("0".equals(testsRun)) {
                // A suite that ran nothing proves nothing. Green here would be the most misleading
                // possible result: a testing node that executed no tests and passed its gate.
                checks.add(CheckResult.fail("TestsPassed",
                        "No tests were executed; a testing node must actually run tests"));
            } else {
                checks.add(CheckResult.pass("TestsPassed", testsRun + " test(s) passed"));
            }
        }
    }
}
