package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.Operation;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import io.kubernetes.client.models.V1Deployment;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Notify datacenter controller when a deployment is ready (for plugins)
 */
@Handler
public class DeploymentHandler extends TerminalHandler<K8sWatchEvent<V1Deployment>> {

    private final Logger logger = LoggerFactory.getLogger(DeploymentHandler.class);

    @Inject
    WorkQueues workQueues;

    @Inject
    DataCenterCache dataCenterCache;

    @Inject
    DataCenterController dataCenterController;

    @Inject
    MeterRegistry meterRegistry;

    Long managed = 0L;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "deployment"));

    @PostConstruct
    public void initGauge() {
        meterRegistry.gauge("k8s.managed",  tags, managed);
    }

    @Override
    public void accept(K8sWatchEvent<V1Deployment> event) throws Exception {
        final V1Deployment deployment;
        logger.debug("Deployment event={}", event);
        switch(event.getType()) {
            case INITIAL:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.init", tags).increment();
                managed++;
                reconcileDeploymentIfAvailable(event.getResource());
                break;

            case ADDED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.added", tags).increment();
                managed++;
                break;

            case MODIFIED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.modified", tags).increment();
                reconcileDeploymentIfAvailable(event.getResource());
                break;

            case DELETED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed--;
            case ERROR:
                logger.warn("event type={}", event.getType());
                meterRegistry.counter("k8s.event.error", tags).increment();
                throw new IllegalStateException("V1Deployment error");
        }
    }

    public static boolean isDeploymentReady(V1Deployment dep) {
        return Objects.equals(dep.getSpec().getReplicas(), ObjectUtils.defaultIfNull(dep.getStatus().getReadyReplicas(), 0));
    }

    public static boolean isDeploymentAvailable(V1Deployment dep) {
        return ObjectUtils.defaultIfNull(dep.getStatus().getAvailableReplicas(), 0) > 0;
    }

    // trigger a dc reconciliation when plugin deployments become available (allow reaper to register)
    public void reconcileDeploymentIfAvailable(V1Deployment deployment) throws Exception {
        if (isDeploymentAvailable(deployment)) {
            final String clusterName = deployment.getMetadata().getLabels().get(OperatorLabels.CLUSTER);
            DataCenter dataCenter = dataCenterCache.get(new Key(deployment.getMetadata().getLabels().get(OperatorLabels.PARENT), deployment.getMetadata().getNamespace()));
            if (dataCenter != null) {
                logger.info("datacenter={} deployment={}/{} is available, triggering a dc deploymentAvailable",
                        dataCenter.id(), deployment.getMetadata().getName(), deployment.getMetadata().getNamespace());
                Operation op = new Operation().withSubmitDate(new Date()).withDesc("updated deployment="+deployment.getMetadata().getName());
                workQueues.submit(
                        new ClusterKey(clusterName, deployment.getMetadata().getNamespace()),
                        dataCenterController.deploymentAvailable(op, dataCenter, deployment));
            }
        }
    }
}