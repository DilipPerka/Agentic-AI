package com.agentic.orchestrator.requirement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequirementNormalizerTest {

    private final CapabilityCatalog catalog = new CapabilityCatalog();
    private final RequirementNormalizer normalizer = new RequirementNormalizer(catalog);

    private static List<String> ids(Requirement requirement) {
        return requirement.capabilities().stream().map(Capability::id).toList();
    }

    @Test
    @DisplayName("greenfield: extracts capabilities and plans their prerequisites as work")
    void classifiesGreenfield() {
        Requirement requirement = normalizer.normalize(
                "Build a URL shortener service with shorten and redirect APIs");

        assertThat(requirement.scenarioType()).isEqualTo(ScenarioType.GREENFIELD);
        assertThat(ids(requirement)).containsExactly(
                CapabilityCatalog.LINK_CREATION, CapabilityCatalog.REDIRECT);
        assertThat(requirement.assumedExisting()).isEmpty();
    }

    @Test
    @DisplayName("brownfield: prerequisites are assumed to exist, not rebuilt")
    void classifiesBrownfield() {
        Requirement requirement = normalizer.normalize(
                "Add click analytics and rate limiting to the existing service");

        assertThat(requirement.scenarioType()).isEqualTo(ScenarioType.BROWNFIELD);
        assertThat(ids(requirement)).containsExactlyInAnyOrder(
                CapabilityCatalog.CLICK_ANALYTICS, CapabilityCatalog.RATE_LIMITING);
        assertThat(requirement.assumedExisting()).contains(
                CapabilityCatalog.LINK_CREATION, CapabilityCatalog.REDIRECT);
    }

    @Test
    @DisplayName("ambiguous: no concrete capability means no plan")
    void classifiesAmbiguous() {
        Requirement requirement = normalizer.normalize(
                "Make the URL shortener more reliable and faster");

        assertThat(requirement.scenarioType()).isEqualTo(ScenarioType.AMBIGUOUS);
        assertThat(requirement.capabilities()).isEmpty();
        assertThat(requirement.isPlannable()).isFalse();
    }

    @Test
    @DisplayName("ambiguities carry options, so a human can answer in one pass")
    void ambiguitiesOfferOptions() {
        Requirement requirement = normalizer.normalize(
                "Make the URL shortener more reliable and faster");

        assertThat(requirement.ambiguities())
                .extracting(Ambiguity::term)
                .contains("reliable", "faster");
        assertThat(requirement.ambiguities())
                .allSatisfy(ambiguity -> assertThat(ambiguity.options()).isNotEmpty());
    }

    @Test
    @DisplayName("'URL shortener' names the product, not a capability")
    void productNameIsNotACapability() {
        // The whole point of the ambiguity path: mentioning the system is not asking for a feature.
        // Substring matching would read "shortener" as "shorten" and plan work nobody requested.
        assertThat(catalog.detect("improve the url shortener")).isEmpty();
        assertThat(catalog.detect("shorten a url")).isNotEmpty();
    }

    @Test
    @DisplayName("a requirement can be concrete and still contain ambiguities")
    void concreteRequirementsStillRecordAmbiguities() {
        Requirement requirement = normalizer.normalize(
                "Add caching to make redirects faster");

        assertThat(requirement.isPlannable()).isTrue();
        assertThat(requirement.ambiguities()).extracting(Ambiguity::term).contains("faster");
    }

    @Test
    void scenarioHintOverridesClassification() {
        Requirement requirement = normalizer.normalize(
                "Build a URL shortener with shorten and redirect APIs", ScenarioType.BROWNFIELD);

        assertThat(requirement.scenarioType()).isEqualTo(ScenarioType.BROWNFIELD);
    }

    @Test
    @DisplayName("prerequisite expansion is transitive")
    void expandsTransitivePrerequisites() {
        // Caching depends on redirect, which depends on link creation.
        Requirement requirement = normalizer.normalize("Build caching for the redirect path");

        assertThat(ids(requirement)).containsExactly(
                CapabilityCatalog.LINK_CREATION,
                CapabilityCatalog.REDIRECT,
                CapabilityCatalog.CACHING);
    }

    @Test
    void handlesEmptyInput() {
        Requirement requirement = normalizer.normalize("   ");

        assertThat(requirement.scenarioType()).isEqualTo(ScenarioType.AMBIGUOUS);
        assertThat(requirement.isPlannable()).isFalse();
    }
}
