package com.strapdata.strapkop.reconcilier;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.sidecar.NodeStatus;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorMetadata;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1StatefulSet;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.strapdata.strapkop.reconcilier.DataCenterUpdateAction.RACK_NAME_COMPARATOR;
import static com.strapdata.strapkop.reconcilier.DataCenterUpdateAction.STATEFUL_SET_POD_NEWEST_FIRST_COMPARATOR;

/**
 * This class implement the complex logic of scaling up and down statefulsets
 */
class StatefulSetsReplacer {
    
    private final Logger logger = LoggerFactory.getLogger(StatefulSetsReplacer.class);
    
    private final CoreV1Api coreApi;
    private final AppsV1Api appsApi;
    private final K8sResourceUtils k8sResourceUtils;
    private final SidecarClientFactory sidecarClientFactory;
    
    private final DataCenter dataCenter;
    
    // tree maps are ordered by rack ascending (rack1, rack2, ...)
    private final TreeMap<String, V1StatefulSet> newtStsMap;
    private final TreeMap<String, V1StatefulSet> existingStsMap;
    
    private List<V1Pod> pods;
    private Map<PodPhase, List<V1Pod>> podsByPhase;
    private Map<NodeStatus, List<V1Pod>> podsByStatus;
    
    // tree set is ordered by rack ascending (rack1, rack2, ...)
    private Map<ReplaceMode, TreeSet<String>> racksByReplaceMode;
    
    private enum ReplaceMode {
        UP, DOWN, UPDATE, NOTHING
    }
    
    private enum PodPhase {
        PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN
    }
    
    StatefulSetsReplacer(CoreV1Api coreApi, AppsV1Api appsApi, K8sResourceUtils k8sResourceUtils, SidecarClientFactory sidecarClientFactory, DataCenter dataCenter, TreeMap<String, V1StatefulSet> newtStsMap, TreeMap<String, V1StatefulSet> existingStsMap) throws Exception {
        this.coreApi = coreApi;
        this.appsApi = appsApi;
        this.k8sResourceUtils = k8sResourceUtils;
        this.sidecarClientFactory = sidecarClientFactory;
        this.dataCenter = dataCenter;
        this.newtStsMap = newtStsMap;
        this.existingStsMap = existingStsMap;
        
        this.racksByReplaceMode = fetchReplaceModes();
        this.pods = fetchPods();
        this.podsByPhase = fetchPhases();
        this.podsByStatus = fetchStatuses();
    }
    
    private Map<ReplaceMode, TreeSet<String>> fetchReplaceModes() {
        
        Map<ReplaceMode, TreeSet<String>> modes = new HashMap<>();
        for (ReplaceMode mode : ReplaceMode.values()) {
            modes.put(mode, new TreeSet<>(RACK_NAME_COMPARATOR));
        }
        
        // some replace modes are cumulative (UP or DOWN imply UPDATE)
        // some others are exclusive (UP and DOWN, or UPDATE and NOTHING)
        newtStsMap.forEach((rack, sts) -> {
            
            // compare the datacenter fingerprint to see if we need an update
            if (sts.getMetadata().getAnnotations().get(OperatorMetadata.DATACENTER_FINGERPRINT)
                    .equals(existingStsMap.get(rack).getMetadata().getAnnotations().get(OperatorMetadata.DATACENTER_FINGERPRINT))) {
                modes.get(ReplaceMode.NOTHING).add(rack);
                return;
            }
            
            modes.get(ReplaceMode.UPDATE).add(rack);
            
            if (sts.getSpec().getReplicas() > existingStsMap.get(rack).getSpec().getReplicas()) {
                modes.get(ReplaceMode.UP).add(rack);
            } else if (sts.getSpec().getReplicas() < existingStsMap.get(rack).getSpec().getReplicas()) {
                modes.get(ReplaceMode.DOWN).add(rack);
            }
        });
        
        return modes;
    }
    
    private List<V1Pod> fetchPods() throws Exception {
        // next step is to check the current k8s phase of every pods
        final String allPodsSelector = OperatorMetadata.toSelector(OperatorMetadata.datacenter(dataCenter));
        return ImmutableList.sortedCopyOf(STATEFUL_SET_POD_NEWEST_FIRST_COMPARATOR,
                k8sResourceUtils.listNamespacedPods(dataCenter.getMetadata().getNamespace(), null, allPodsSelector)
        );
    }
    
    private Map<PodPhase, List<V1Pod>> fetchPhases() {
        Map<PodPhase, List<V1Pod>> phases = new HashMap<>();
        for (PodPhase phase : PodPhase.values()) {
            phases.put(phase, new ArrayList<>());
        }
        
        for (V1Pod pod : pods) {
            final PodPhase phase = PodPhase.valueOf(pod.getStatus().getPhase().toUpperCase());
            phases.get(phase).add(pod);
        }
        
        return phases;
    }
    
    
    private Map<NodeStatus, List<V1Pod>> fetchStatuses() {
        
        // WARNING: this function is blocking
        
        Map<NodeStatus, List<V1Pod>> statuses = new HashMap<>();
        for (NodeStatus status : NodeStatus.values()) {
            statuses.put(status, new ArrayList<>());
        }
        Observable.fromIterable(podsByPhase.get(PodPhase.RUNNING))
                .flatMap(pod -> sidecarClientFactory.clientForPodNullable(pod)
                        .status()
                        .toObservable()
                        .doOnError(e -> logger.info("can't get pod status of {}", pod.getMetadata().getName(), e))
                        .onErrorResumeNext(Observable.empty())
                        .map(s -> Tuple.of(s, pod))
                        .subscribeOn(Schedulers.io()))
                .toMultimap(Tuple2::_1, Tuple2::_2)
                .blockingGet()
                .forEach((status, pods) -> statuses.get(status).addAll(pods));
        
        return statuses;
    }
    
    private static Set<NodeStatus> MOVING_NODE_STATUSES = ImmutableSet.of(
            NodeStatus.JOINING, NodeStatus.DRAINING, NodeStatus.LEAVING, NodeStatus.MOVING, NodeStatus.STARTING);
    

    
    void replace() throws ApiException {
        
        if (racksByReplaceMode.get(ReplaceMode.UP).size() > 0 && racksByReplaceMode.get(ReplaceMode.DOWN).size() > 0) {
            // here we have some racks to scale-up and some racks to scale-down... it should not happens
            logger.error("inconsistent state, racks [{}] must scale up while racks [{}] must scale down",
                    racksByReplaceMode.get(ReplaceMode.UP), racksByReplaceMode.get(ReplaceMode.DOWN));
            return;
        }
        
        if (MOVING_NODE_STATUSES.stream().anyMatch(status -> podsByStatus.get(status).size() > 0)) {
            logger.debug("some pods are in a moving operational status, skipping sts replacement");
            return;
        }
        
        if (podsByPhase.get(PodPhase.RUNNING).size() < pods.size()) {
            logger.debug("some pods are not running");
            
            // there is maybe unschedulable pods in out-of-date racks, so we try to recover from that situation
            tryRecoverUnschedulablePods();
            return;
        }
        
        // TODO: If some pod are not running, check if we need to perform an update to recover from misconfiguration
        
        // TODO: check that's if there is decommissioned node but no scale-down operation, we should restart the node (in case user has trigger scale down then up to fast)
        
        if (racksByReplaceMode.get(ReplaceMode.UP).size() > 0) {
            scaleUp();
        } else if (racksByReplaceMode.get(ReplaceMode.DOWN).size() > 0) {
            scaleDown();
        } else if (racksByReplaceMode.get(ReplaceMode.UPDATE).size() > 0) {
            updateNextRack();
        } else {
            // this should not happens except if there is no rack...
            logger.debug("Everything is fine, nothing to do");
        }
    }
    
    private void tryRecoverUnschedulablePods() throws ApiException {
        
        // search for racks that are out-of-date (ReplaceMode.UPDATE) and that contains unschedulable pods.
        // If found, update the first rack and kill the unschedulable pods (sts won't recreate those pods automatically sometimes...)
        
        Optional<Map.Entry<String, List<V1Pod>>> unschedulableRack = fetchUnschedulablePods()
                .entrySet()
                .stream()
                .filter(e -> racksByReplaceMode.get(ReplaceMode.UPDATE).contains(e.getKey()))
                .findFirst();
        
        if (unschedulableRack.isPresent()) {
            logger.info("recovering unschedulable pods {} in rack {}",
                    unschedulableRack.get().getValue().stream().map(pod -> pod.getMetadata().getName()).collect(Collectors.toList()),
                    unschedulableRack.get().getKey());
            updateRack(unschedulableRack.get().getKey());
            for (V1Pod pod : unschedulableRack.get().getValue()) {
                try {
                    coreApi.deleteNamespacedPod(pod.getMetadata().getName(), pod.getMetadata().getNamespace(), new V1DeleteOptions().gracePeriodSeconds(0L), null, null, null, null, null);
                }
                catch (com.google.gson.JsonSyntaxException e) {
                    logger.debug("safely ignoring exception (see https://github.com/kubernetes-client/java/issues/86)", e);
                }
            }
        }
    }
    
    private TreeMap<String, List<V1Pod>> fetchUnschedulablePods() {
        return podsByPhase.get(PodPhase.PENDING).stream()
                .filter(pod ->
                        pod.getStatus().getConditions().stream()
                                .filter(v1PodCondition -> Objects.equals(v1PodCondition.getType(), "PodScheduled"))
                                .findFirst()
                                .map(v1PodCondition -> Objects.equals(v1PodCondition.getStatus(), "False")
                                        && Objects.equals(v1PodCondition.getReason(), "Unschedulable"))
                                .orElse(Boolean.FALSE))
                .collect(Collectors.groupingBy(
                        pod -> pod.getMetadata().getLabels().get(OperatorMetadata.RACK),
                        () -> new TreeMap<>(RACK_NAME_COMPARATOR),
                        Collectors.toList()));
    }
    
    private void scaleUp() throws ApiException {
        // scale-up occurs one rack at a time, scale the lowest rack number with the lowest number of replicas
        final int minReplicas = existingStsMap.values().stream().mapToInt(sts -> sts.getSpec().getReplicas()).min()
                .orElseThrow(() -> new RuntimeException("Inconsistent state, no racks found"));
        final V1StatefulSet statefulSetToScale = racksByReplaceMode.get(ReplaceMode.UP).stream()
                .filter(rack -> existingStsMap.get(rack).getSpec().getReplicas() == minReplicas)
                .findFirst()
                .map(existingStsMap::get)
                .get(); // should always contain an item
        
        logger.info("Scaling up sts {}", statefulSetToScale.getMetadata().getName());
        
        // when scaling up, we can't modify the entire spec of the existing sts because it could accidentally trigger a rolling restart
        statefulSetToScale.getSpec().setReplicas(statefulSetToScale.getSpec().getReplicas() + 1);
        appsApi.replaceNamespacedStatefulSet(statefulSetToScale.getMetadata().getName(), statefulSetToScale.getMetadata().getNamespace(), statefulSetToScale, null, null);
    }
    
    private void scaleDown() throws ApiException {
        // scale-down occurs one rack at a time, scale the highest rack number with the highest number of replicas
        final int maxReplicas = existingStsMap.values().stream().mapToInt(sts -> sts.getSpec().getReplicas()).max()
                .orElseThrow(() -> new RuntimeException("Inconsistent state, no racks found"));
        final V1StatefulSet statefulSetToScale = racksByReplaceMode.get(ReplaceMode.DOWN).descendingSet().stream()
                .filter(rack -> existingStsMap.get(rack).getSpec().getReplicas() == maxReplicas)
                .findFirst()
                .map(existingStsMap::get)
                .get(); // should always contain an item
        
        logger.info("Scaling up sts {}", statefulSetToScale.getMetadata().getName());
        
        // when scaling up, we can't modify the entire spec of the existing sts because it could accidentally trigger a rolling restart
        statefulSetToScale.getSpec().setReplicas(statefulSetToScale.getSpec().getReplicas() + 1);
        appsApi.replaceNamespacedStatefulSet(statefulSetToScale.getMetadata().getName(), statefulSetToScale.getMetadata().getNamespace(), statefulSetToScale, null, null);
    }
    
    private void updateNextRack() throws ApiException {
        // updating sts spec trigger a rolling restart of the rack, we want to rolling restart one rack at a time
        final String rack = racksByReplaceMode.get(ReplaceMode.UPDATE).first();
        updateRack(rack);
    }
    
    private void updateRack(String rack) throws ApiException {
        final V1StatefulSet newStatefulSet = newtStsMap.get(rack);
        logger.info("Update spec of sts {} (rolling restart)", newStatefulSet.getMetadata().getName());
        appsApi.replaceNamespacedStatefulSet(newStatefulSet.getMetadata().getName(), newStatefulSet.getMetadata().getNamespace(), newStatefulSet, null, null);
    }
}