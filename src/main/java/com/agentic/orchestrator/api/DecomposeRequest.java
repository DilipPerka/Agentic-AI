package com.agentic.orchestrator.api;

import com.agentic.orchestrator.requirement.ScenarioType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param requirement  the high-level requirement, in plain English
 * @param scenarioHint optional override of the automatic classification; lets the same text be
 *                     planned as greenfield and then as brownfield for comparison
 */
public record DecomposeRequest(
        @NotBlank(message = "requirement must not be blank")
        @Size(max = 4000, message = "requirement must be at most 4000 characters")
        String requirement,
        ScenarioType scenarioHint) {
}
