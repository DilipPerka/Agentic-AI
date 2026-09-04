package com.agentic.orchestrator.governance;

import com.agentic.orchestrator.plan.BlastRadius;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps (autonomy level x blast radius) to what governance permits.
 *
 * <pre>
 *              LOW      MEDIUM   HIGH     CRITICAL
 *   L0        APPROVE  APPROVE  APPROVE  DENY
 *   L1        AUTO     APPROVE  APPROVE  DENY
 *   L2        AUTO     AUTO     APPROVE  DENY
 *   L3        AUTO     AUTO     AUTO     APPROVE
 * </pre>
 *
 * <p>Two invariants, asserted by tests rather than left as documentation:
 * <ol>
 *   <li><b>CRITICAL is never AUTO</b>, at any autonomy level. Full autonomy still stops for
 *       secrets, build configuration and destructive changes.
 *   <li><b>Raising autonomy never makes a cell stricter.</b> The table is monotonic, so an operator
 *       can reason about the effect of a level change without reading every cell.
 * </ol>
 */
@Component
public class ApprovalMatrix {

    private final Map<AutonomyLevel, Map<BlastRadius, ControlAction>> matrix =
            new EnumMap<>(AutonomyLevel.class);

    public ApprovalMatrix() {
        row(AutonomyLevel.L0_OBSERVE,
                ControlAction.APPROVE, ControlAction.APPROVE, ControlAction.APPROVE, ControlAction.DENY);
        row(AutonomyLevel.L1_SUPERVISED,
                ControlAction.AUTO, ControlAction.APPROVE, ControlAction.APPROVE, ControlAction.DENY);
        row(AutonomyLevel.L2_DELEGATED,
                ControlAction.AUTO, ControlAction.AUTO, ControlAction.APPROVE, ControlAction.DENY);
        row(AutonomyLevel.L3_AUTONOMOUS,
                ControlAction.AUTO, ControlAction.AUTO, ControlAction.AUTO, ControlAction.APPROVE);
    }

    private void row(AutonomyLevel level, ControlAction low, ControlAction medium,
                     ControlAction high, ControlAction critical) {
        Map<BlastRadius, ControlAction> byRadius = new EnumMap<>(BlastRadius.class);
        byRadius.put(BlastRadius.LOW, low);
        byRadius.put(BlastRadius.MEDIUM, medium);
        byRadius.put(BlastRadius.HIGH, high);
        byRadius.put(BlastRadius.CRITICAL, critical);
        matrix.put(level, byRadius);
    }

    public ControlAction actionFor(AutonomyLevel level, BlastRadius radius) {
        return matrix.get(level).get(radius);
    }
}
