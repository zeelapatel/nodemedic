package io.nodemedic.drain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudgetListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The mock server scripts the API's answers; it does not implement PDB or eviction logic itself. */
@EnableKubernetesMockClient
class DrainerTest {

    KubernetesMockServer server;
    KubernetesClient client;

    private void podsOnNode(Pod... pods) {
        server.expect().get().withPath("/api/v1/pods?fieldSelector=spec.nodeName%3Dn1")
                .andReturn(200, new PodListBuilder().withItems(pods).build()).always();
    }

    private void evictionAnswers(String pod, int code) {
        server.expect().post().withPath("/api/v1/namespaces/default/pods/" + pod + "/eviction")
                .andReturn(code, new StatusBuilder().withCode(code).withMessage("scripted " + code).build()).always();
    }

    private void pdbs(io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget... pdbs) {
        server.expect().get().withPath("/apis/policy/v1/namespaces/default/poddisruptionbudgets")
                .andReturn(200, new PodDisruptionBudgetListBuilder().withItems(pdbs).build()).always();
    }

    @Test
    void onlyOrdinaryPodsAreEvicted() {
        podsOnNode(TestPods.ordinary("web-1"), TestPods.daemonSet("ds-1"), TestPods.mirror("static-1"),
                TestPods.finished("Succeeded", "job-1"), TestPods.terminating("dying-1"));
        // every eviction would succeed, so anything wrongly evicted would show up in the result
        for (String p : List.of("web-1", "ds-1", "static-1", "job-1", "dying-1")) {
            evictionAnswers(p, 201);
        }

        var round = new Drainer(client).run("n1");

        assertThat(round.evicted()).containsExactly("default/web-1");
        assertThat(round.terminating()).containsExactly("default/dying-1");
        assertThat(round.blocked()).isEmpty();
        assertThat(round.done()).isFalse();
    }

    @Test
    void aRefusedEvictionIsBlockedAndNamesThePdb() {
        podsOnNode(TestPods.ordinary("web-1"));
        evictionAnswers("web-1", 429);
        pdbs(new io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudgetBuilder()
                .withNewMetadata().withNamespace("default").withName("web-pdb").endMetadata()
                .withNewSpec().withNewSelector().withMatchLabels(Map.of("app", "web")).endSelector().endSpec()
                .withNewStatus().withDisruptionsAllowed(0).endStatus().build());

        var round = new Drainer(client).run("n1");

        assertThat(round.evicted()).isEmpty();
        assertThat(round.blocked()).hasSize(1);
        assertThat(round.blocked().get(0).pod()).isEqualTo("default/web-1");
        assertThat(round.blocked().get(0).pdbs()).containsExactly("default/web-pdb");
        assertThat(round.message()).contains("blocked by PDB default/web-pdb");
        assertThat(round.done()).isFalse();
    }

    @Test
    void aPodThatIsAlreadyGoneCountsAsDone() {
        podsOnNode(TestPods.ordinary("web-1"));
        evictionAnswers("web-1", 404);

        var round = new Drainer(client).run("n1");

        assertThat(round.done()).isTrue();
        assertThat(round.message()).isEqualTo(Drainer.DRAINED);
    }

    @Test
    void anEmptyNodeIsDrained() {
        podsOnNode();

        var round = new Drainer(client).run("n1");

        assertThat(round.done()).isTrue();
        assertThat(round.message()).isEqualTo("drained");
    }

    @Test
    void aNodeWithOnlyDaemonSetPodsIsDrained() {
        podsOnNode(TestPods.daemonSet("ds-1"), TestPods.mirror("static-1"));

        assertThat(new Drainer(client).run("n1").done()).isTrue();
    }

    @Test
    void aBarePodIsReportedAndNeverEvicted() {
        podsOnNode(TestPods.bare("lonely"));
        evictionAnswers("lonely", 201);

        var round = new Drainer(client).run("n1");

        assertThat(round.evicted()).isEmpty();
        assertThat(round.bare()).containsExactly("default/lonely");
        assertThat(round.done()).isFalse();
        assertThat(round.message()).contains("without a controller").contains("default/lonely");
    }

    @Test
    void anUnexpectedErrorIsNotSwallowed() {
        podsOnNode(TestPods.ordinary("web-1"));
        evictionAnswers("web-1", 500);

        assertThatThrownBy(() -> new Drainer(client).run("n1")).isInstanceOf(KubernetesClientException.class);
    }

    @Test
    void messageSummarisesEachKindOfLeftover() {
        var round = new Drainer.Round(List.of("a/1", "a/2"), List.of("a/3"), List.of(),
                List.of(new Drainer.Blocked("a/4", List.of("a/pdb"))));

        assertThat(round.message()).isEqualTo("draining: evicted 2; waiting for 1 terminating; 1 blocked by PDB a/pdb");
    }
}
