package com.strapdata.strapkop.controllers;

import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.reactivex.Single;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Return nodes IP of pod 0 for active racks
 */
@Tag(name = "seeds")
@Controller("/seeds")
public class SeedsController {

    private final Logger logger = LoggerFactory.getLogger(SeedsController.class);

    private final K8sResourceUtils k8sResourceUtils;
    private final CoreV1Api coreApi;

    public SeedsController(CoreV1Api coreApi, K8sResourceUtils k8sResourceUtils) {
        this.coreApi= coreApi;
        this.k8sResourceUtils = k8sResourceUtils;
    }

    /**
     * @return OK when preflight service are applied (CA and CRD installation)
     */
    @Get("/")
    public HttpStatus index() {
        return HttpStatus.OK;
    }

    /**
     * Retreive seed node IP addresses.
     * @param namespace
     * @param clusterName
     * @param datacenterName
     * @return
     */
    @Get(value = "/{namespace}/{clusterName}/{datacenterName}", produces = MediaType.APPLICATION_JSON)
    public Single<List<String>> seeds(String namespace, String clusterName, String datacenterName) throws ApiException {
        return k8sResourceUtils.readDatacenter(new com.strapdata.model.Key(OperatorNames.dataCenterResource(clusterName, datacenterName), namespace))
                .map(dataCenter -> {
                List<String> seeds = new ArrayList<>();
                k8sResourceUtils.listNamespacedStatefulSets(namespace, null, OperatorLabels.toSelector(OperatorLabels.datacenter(dataCenter)))
                        .forEach(statefulSet -> {
                                if (statefulSet != null && statefulSet.getStatus() != null && statefulSet.getStatus().getCurrentReplicas() != null && statefulSet.getStatus().getCurrentReplicas() > 0) {
                                    String podName = OperatorNames.podName(dataCenter, statefulSet.getMetadata().getLabels().get(OperatorLabels.RACK), 0);
                                    // retreive pod node IP
                                    final String labelSelector = OperatorLabels.toSelector(ImmutableMap.of(OperatorLabels.POD, podName));
                                    try {
                                        k8sResourceUtils.listNamespacedPods(namespace, null, labelSelector).forEach(pod -> {
                                            String nodeName = pod.getSpec().getNodeName();
                                            logger.debug("found node={}", nodeName);
                                            if (pod.getStatus() != null && pod.getStatus().getHostIP() != null) {
                                                logger.debug("add hostIp={}", pod.getStatus().getHostIP());
                                                seeds.add(pod.getStatus().getHostIP());
                                            }
                                        });
                                    } catch (ApiException e) {
                                        logger.warn("Failed to get pod list", e);
                                    }
                                }
                        });
                logger.info("seeds="+seeds);
                return seeds;
        });
    }
}
