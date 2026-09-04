package com.agentic.orchestrator.replan;

import com.agentic.orchestrator.requirement.Requirement;
import com.agentic.orchestrator.requirement.RequirementNormalizer;
import com.agentic.orchestrator.requirement.ScenarioType;
import org.springframework.stereotype.Component;

/**
 * Folds new human input back into the requirement and re-normalises it.
 *
 * <p>Appending rather than replacing: the original text is what the run was commissioned to do, and
 * discarding it would let a one-line clarification silently redefine the whole job. The amended text
 * goes through the same normaliser as the original, so a clarification that names a concrete
 * capability turns an ambiguous requirement into a plannable one by the ordinary path rather than a
 * special case.
 */
@Component
public class RequirementAmender {

    private final RequirementNormalizer normalizer;

    public RequirementAmender(RequirementNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public Requirement amend(Requirement original, String guidance) {
        if (guidance == null || guidance.isBlank()) {
            return original;
        }
        String combined = original.rawText() + ". " + guidance.trim();

        // The scenario is not re-derived from the combined text. A brownfield run stays brownfield
        // even if the clarification happens to read like a fresh build request — the codebase did
        // not stop existing because someone answered a question.
        ScenarioType scenario = original.scenarioType() == ScenarioType.AMBIGUOUS
                ? null                       // ambiguity may now be resolved; let it reclassify
                : original.scenarioType();

        return normalizer.normalize(combined, scenario);
    }
}
