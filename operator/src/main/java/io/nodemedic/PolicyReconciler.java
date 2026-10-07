package io.nodemedic;

import io.fabric8.kubernetes.api.model.Node;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.nodemedic.crd.RemediationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@ControllerConfiguration
public class PolicyReconciler implements Reconciler<RemediationPolicy> {

    private static final Logger log = LoggerFactory.getLogger(PolicyReconciler.class);

    @Override
    public UpdateControl<RemediationPolicy> reconcile(RemediationPolicy policy, Context<RemediationPolicy> ctx) {
        log.info("reconciling policy {} (generation {})", policy.getMetadata().getName(),
                 policy.getMetadata().getGeneration());
        return UpdateControl.noUpdate();
    }

    @Override
    public List<EventSource<?, RemediationPolicy>> prepareEventSources(EventSourceContext<RemediationPolicy> ctx) {
        var config = InformerEventSourceConfiguration.from(Node.class, RemediationPolicy.class)
            // any node change → reconcile every policy (there's only one per pool)
            .withSecondaryToPrimaryMapper(node -> ctx.getPrimaryCache().list()
                .map(ResourceID::fromResource)
                .collect(Collectors.toSet()))
            .build();
        return List.of(new InformerEventSource<>(config, ctx));
    }
}
