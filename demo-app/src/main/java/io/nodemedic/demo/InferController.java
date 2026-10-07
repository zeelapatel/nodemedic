package io.nodemedic.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stands in for a GPU inference endpoint. It does no real work: it waits a little and
 * reports which node and pod answered, so a load test can see where traffic went.
 */
@RestController
public class InferController {

    public record InferResponse(String result, String servedBy, String pod) {
    }

    private final String nodeName;
    private final String podName;
    private final long latencyMs;

    public InferController(@Value("${NODE_NAME:unknown}") String nodeName,
                           @Value("${POD_NAME:${HOSTNAME:unknown}}") String podName,
                           @Value("${demo.latency-ms:50}") long latencyMs) {
        this.nodeName = nodeName;
        this.podName = podName;
        this.latencyMs = latencyMs;
    }

    @GetMapping("/infer")
    public InferResponse infer() throws InterruptedException {
        Thread.sleep(latencyMs);
        return new InferResponse("ok", nodeName, podName);
    }
}
