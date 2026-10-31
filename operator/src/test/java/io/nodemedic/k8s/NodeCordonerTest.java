package io.nodemedic.k8s;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class NodeCordonerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    KubernetesClient client;

    @BeforeEach
    void createNode() {
        client.nodes().resource(new NodeBuilder().withNewMetadata().withName("n1").endMetadata().build()).create();
    }

    @Test
    void cordonsAnUnschedulableFreeNodeAndMarksIt() {
        boolean cordoned = new NodeCordoner(client).ensureCordoned("n1", null, NOW);

        var node = client.nodes().withName("n1").get();
        assertThat(cordoned).isTrue();
        assertThat(node.getSpec().getUnschedulable()).isTrue();
        assertThat(node.getMetadata().getAnnotations())
                .containsEntry(NodeCordoner.CORDONED_BY, "nodemedic")
                .containsEntry(NodeCordoner.CORDONED_AT, NOW.toString());
    }

    @Test
    void writesOneEventOnTheNode() {
        new NodeCordoner(client).ensureCordoned("n1", null, NOW);

        var events = client.v1().events().inNamespace("default").list().getItems();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getReason()).isEqualTo("Cordoned");
        assertThat(events.get(0).getType()).isEqualTo("Normal");
        assertThat(events.get(0).getInvolvedObject().getName()).isEqualTo("n1");
    }

    @Test
    void secondCallDoesNothingAndEmitsNoSecondEvent() {
        var cordoner = new NodeCordoner(client);
        cordoner.ensureCordoned("n1", null, NOW);

        boolean again = cordoner.ensureCordoned("n1", null, NOW.plusSeconds(30));

        assertThat(again).isFalse();
        assertThat(client.v1().events().inNamespace("default").list().getItems()).hasSize(1);
        // the first timestamp is kept: a repeat call must not rewrite the annotation
        assertThat(client.nodes().withName("n1").get().getMetadata().getAnnotations())
                .containsEntry(NodeCordoner.CORDONED_AT, NOW.toString());
    }

    @Test
    void aNodeThatWasAlreadyCordonedByAPersonIsLeftAlone() {
        client.nodes().withName("n1").edit(n ->
                new NodeBuilder(n).withNewSpec().withUnschedulable(true).endSpec().build());

        boolean cordoned = new NodeCordoner(client).ensureCordoned("n1", null, NOW);

        assertThat(cordoned).isFalse();
        var annotations = client.nodes().withName("n1").get().getMetadata().getAnnotations();
        assertThat(annotations == null || !annotations.containsKey(NodeCordoner.CORDONED_BY)).isTrue();
    }

    @Test
    void aMissingNodeIsNotAnError() {
        assertThat(new NodeCordoner(client).ensureCordoned("ghost", null, NOW)).isFalse();
    }
}
