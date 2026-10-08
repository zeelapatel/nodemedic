package io.nodemedic.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InferControllerTest {

    @Test
    void reportsNodeAndPod() throws Exception {
        var controller = new InferController("worker2", "demo-app-abc", 0);

        var response = controller.infer();

        assertThat(response.result()).isEqualTo("nope");
        assertThat(response.servedBy()).isEqualTo("worker2");
        assertThat(response.pod()).isEqualTo("demo-app-abc");
    }
}
