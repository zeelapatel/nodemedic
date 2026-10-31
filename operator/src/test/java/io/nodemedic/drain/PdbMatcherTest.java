package io.nodemedic.drain;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudgetBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PdbMatcherTest {

    private static PodDisruptionBudget pdb(String ns, String name, Map<String, String> selector, Integer allowed) {
        var builder = new PodDisruptionBudgetBuilder()
                .withNewMetadata().withNamespace(ns).withName(name).endMetadata()
                .withNewSpec().withNewSelector().withMatchLabels(selector).endSelector().endSpec();
        if (allowed != null) {
            builder.withNewStatus().withDisruptionsAllowed(allowed).endStatus();
        }
        return builder.build();
    }

    private final io.fabric8.kubernetes.api.model.Pod pod = TestPods.ordinary("web-1");   // labels: app=web

    @Test
    void namesThePdbThatSelectsThePod() {
        var pdbs = List.of(pdb("default", "web-pdb", Map.of("app", "web"), 0));

        assertThat(PdbMatcher.blocking(pod, pdbs)).containsExactly("default/web-pdb");
    }

    @Test
    void ignoresPdbsThatSelectOtherPods() {
        var pdbs = List.of(pdb("default", "db-pdb", Map.of("app", "db"), 0));

        assertThat(PdbMatcher.blocking(pod, pdbs)).isEmpty();
    }

    @Test
    void prefersThePdbWithNoDisruptionsLeft() {
        var pdbs = List.of(
                pdb("default", "roomy", Map.of("app", "web"), 2),
                pdb("default", "exhausted", Map.of("app", "web"), 0));

        assertThat(PdbMatcher.blocking(pod, pdbs)).containsExactly("default/exhausted");
    }

    @Test
    void whenNoneIsExhaustedAllMatchingAreListed() {
        var pdbs = List.of(
                pdb("default", "b", Map.of("app", "web"), 1),
                pdb("default", "a", Map.of("app", "web"), null));

        assertThat(PdbMatcher.blocking(pod, pdbs)).containsExactly("default/a", "default/b");
    }

    @Test
    void anEmptySelectorSelectsEveryPodInTheNamespace() {
        var pdbs = List.of(pdb("default", "all", Map.of(), 0));

        assertThat(PdbMatcher.blocking(pod, pdbs)).containsExactly("default/all");
    }
}
