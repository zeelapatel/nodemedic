package io.nodemedic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.NodeBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NodePoolTest {

    private static final Map<String, String> GPU = Map.of("nodemedic.io/pool", "gpu");

    @Test
    void matchesWhenSelectorEntryIsPresent() {
        var node = TestNodes.withLabels(Map.of("nodemedic.io/pool", "gpu"));
        assertThat(NodePool.matches(node, GPU)).isTrue();
    }

    @Test
    void extraLabelsOnTheNodeAreFine() {
        var node = TestNodes.withLabels(Map.of("nodemedic.io/pool", "gpu", "nodemedic.io/role", "active"));
        assertThat(NodePool.matches(node, GPU)).isTrue();
    }

    @Test
    void allSelectorEntriesMustMatch() {
        var selector = Map.of("nodemedic.io/pool", "gpu", "nodemedic.io/role", "active");
        var spare = TestNodes.withLabels(Map.of("nodemedic.io/pool", "gpu", "nodemedic.io/role", "spare"));
        assertThat(NodePool.matches(spare, selector)).isFalse();
    }

    @Test
    void wrongValueDoesNotMatch() {
        var node = TestNodes.withLabels(Map.of("nodemedic.io/pool", "cpu"));
        assertThat(NodePool.matches(node, GPU)).isFalse();
    }

    @Test
    void missingLabelDoesNotMatch() {
        var node = TestNodes.withLabels(Map.of("other", "x"));
        assertThat(NodePool.matches(node, GPU)).isFalse();
    }

    @Test
    void nodeWithoutLabelsDoesNotMatch() {
        var node = new NodeBuilder().withNewMetadata().withName("bare").endMetadata().build();
        assertThat(NodePool.matches(node, GPU)).isFalse();
    }

    @Test
    void emptyOrNullSelectorMatchesNothing() {
        var node = TestNodes.withLabels(Map.of("nodemedic.io/pool", "gpu"));
        assertThat(NodePool.matches(node, Map.of())).isFalse();
        assertThat(NodePool.matches(node, null)).isFalse();
    }
}
