package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.model.k8s.task.CleanupTaskSpec;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.sidecar.NodeStatus;
import com.strapdata.strapkop.cache.NodeStatusCache;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.models.V1StatefulSet;
import io.micronaut.context.annotation.Prototype;
import io.reactivex.Flowable;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * This class implement the complex logic of updating, scaling up or down statefulsets with multiple rack, one by one.
 */
@Prototype
class CarefulStatefulSetUpdateManager {
    
    private final Logger logger = LoggerFactory.getLogger(CarefulStatefulSetUpdateManager.class);
    
    private final AppsV1Api appsApi;
    private final K8sResourceUtils k8sResourceUtils;
    private final SidecarClientFactory sidecarClientFactory;
    
    private final NodeStatusCache nodeStatusCache;
    
    private DataCenter dataCenter;
    
    // tree maps are ordered by rack ascending (rack1, rack2, ...)
    private TreeMap<String, V1StatefulSet> newtStsMap;
    private TreeMap<String, V1StatefulSet> existingStsMap;
    
    private Map<Boolean, List<String>> rackByReadiness;
    private Map<NodeStatus, List<String>> podsByStatus;
    private static Set<NodeStatus> MOVING_NODE_STATUSES = ImmutableSet.of(
            NodeStatus.JOINING, NodeStatus.DRAINING, NodeStatus.LEAVING, NodeStatus.MOVING, NodeStatus.STARTING, NodeStatus.UNKNOWN);
    
    // tree set is ordered by rack ascending (rack1, rack2, ...)
    private Map<RackMode, TreeSet<String>> racksByRackMode;
    
    
    CarefulStatefulSetUpdateManager(AppsV1Api appsApi, K8sResourceUtils k8sResourceUtils, SidecarClientFactory sidecarClientFactory, NodeStatusCache nodeStatusCache) throws Exception {
        this.appsApi = appsApi;
        this.k8sResourceUtils = k8sResourceUtils;
        this.sidecarClientFactory = sidecarClientFactory;
        this.nodeStatusCache = nodeStatusCache;
    }
    
    /**
     * Initialize some data structure with the observed state (node status, statefulset, ...)
     */
    private void observe(DataCenter dataCenter, TreeMap<String, V1StatefulSet> newtStsMap, TreeMap<String, V1StatefulSet> existingStsMap) {
        this.dataCenter = dataCenter;
        this.newtStsMap = newtStsMap;
        this.existingStsMap = existingStsMap;
        
        this.racksByRackMode = fetchRackModes();
        this.rackByReadiness = fetchReadinesses();
        this.podsByStatus = getStatusesFromCache();
    }
    
    /**
     * This is the main function.
     *
     * Given a map of existing racks, and a map of expected rack... do something to decrease the gap without elassandra downtime.
     *
     * Only one expected rack should have a different replicas value than the existing one.
     */
    void updateNextStatefulSet(DataCenter dataCenter, TreeMap<String, V1StatefulSet> newtStsMap, TreeMap<String, V1StatefulSet> existingStsMap) throws ApiException, MalformedURLException, UnknownHostException {
    
        // make a lot of observations and build some data structures in order to take the best decision
        observe(dataCenter, newtStsMap, existingStsMap);
        
        // update dc status with what we observed
        updateDatacenterStatus();

        // "garde-fou" 1
        if (racksByRackMode.get(RackMode.SCALE_UP).size() > 0 && racksByRackMode.get(RackMode.SCALE_DOWN).size() > 0) {
            // here we have some racks to scale-up and some racks to scale-down... it should not happens
            dataCenter.getStatus().setPhase(DataCenterPhase.ERROR);
            logger.error("inconsistent state, racks [{}] must scale up while racks [{}] must scale down",
                    racksByRackMode.get(RackMode.SCALE_UP), racksByRackMode.get(RackMode.SCALE_DOWN));
            return;
        }
    
        // "garde-fou" 2
        if (racksByRackMode.get(RackMode.SCALE_UP).size() > 1 || racksByRackMode.get(RackMode.SCALE_DOWN).size() > 1) {

            // the scaling of which rack should be decided has been moved upstream. They should never be multiple rack that need to
            // be scaled at this point of the reconciliation.
            
            dataCenter.getStatus().setPhase(DataCenterPhase.ERROR);
            logger.error("inconsistent state, multiple racks request scaling dc={}", dataCenter.getMetadata().getName());
            return;
        }
    
        // ensure all statefulsets are ready
        if (rackByReadiness.get(false).size() > 0) {
            logger.debug("some statefulsets are not ready, skipping sts replacement : {}", rackByReadiness.get(false));
            
            // TODO: check if some pods are stuck with old configuration and restart it manually
            //        see https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/#forced-rollback
            
            return ;
        }
        
        // ensure all nodes are joined or decommissioned
        if (MOVING_NODE_STATUSES.stream().anyMatch(status -> podsByStatus.get(status).size() > 0)) {
            logger.debug("some pods are in a moving operational status, skipping sts replacement");
            return;
        }
        
        // TODO: check that's if there is decommissioned node but no scale-down operation, we should still delete the node (in case user has trigger scale down then up to fast)
    
        // first, we update a rack that does not need to scale
        if (racksByRackMode.get(RackMode.BEHIND).size() > 0) {
            updateRack(racksByRackMode.get(RackMode.BEHIND).first());
        }
        // when all racks are up-to-date except the one we need to scale-up, we trigger the scale+1 as well as rack update in one shot
        else if (racksByRackMode.get(RackMode.SCALE_UP).size() > 0) {
            scaleUp(racksByRackMode.get(RackMode.SCALE_UP).first());
        }
        // ...scale down is almost the same except that we start by decommissioning the node
        else if (racksByRackMode.get(RackMode.SCALE_DOWN).size() > 0) {
            scaleDown(racksByRackMode.get(RackMode.SCALE_DOWN).first());
        }
        // otherwise all racks are OK, we might need to trigger a cleanup
        else {
            
            // if we go from scaling to running phase, we trigger a cleanup
            if (dataCenter.getStatus().getPhase().equals(DataCenterPhase.SCALING_DOWN) ||
                    (dataCenter.getStatus().getPhase().equals(DataCenterPhase.SCALING_UP) && dataCenter.getSpec().getReplicas() > 1)) {
                logger.info("Scaling of dc={} terminated, triggering a dc cleanup", dataCenter.getMetadata().getName());
                triggerCleanupTask();
            }

            dataCenter.getStatus().setPhase(DataCenterPhase.RUNNING);
            logger.debug("Everything is fine, nothing to do");
        }
    }
    
    /**
     * Based on metadata.generation of DC, check if sts need an update
     */
    private boolean stsNeedsUpdate(final V1StatefulSet existingSts, final DataCenter dc) {
        final Long stsGen = Long.valueOf(existingSts.getMetadata().getAnnotations().get(OperatorLabels.DATACENTER_GENERATION));
        final Long dcGen = dc.getMetadata().getGeneration();
        
        // TODO: take care of user defined config map changes
        
        if (stsGen == null || dcGen == null) {
            return false;
        }
        
        return stsGen < dcGen;
    }
    
    /**
     * Build a map of rack -> RackMode.
     * The RackMode describes if the rack is up-to-date or if it needs scale-up, down, or rolling update.
     */
    private Map<RackMode, TreeSet<String>> fetchRackModes() {
        
        Map<RackMode, TreeSet<String>> modes = new HashMap<>();
        for (RackMode mode : RackMode.values()) {
            modes.put(mode, new TreeSet<>());
        }
        
        // some updateNextStatefulSet modes are cumulative (UP or DOWN imply UPDATE)
        // some others are exclusive (UP and DOWN, or UPDATE and NOTHING)
        newtStsMap.forEach((rack, sts) -> {
            
            if (sts.getSpec().getReplicas() > existingStsMap.get(rack).getSpec().getReplicas()) {
                modes.get(RackMode.SCALE_UP).add(rack);
            } else if (sts.getSpec().getReplicas() < existingStsMap.get(rack).getSpec().getReplicas()) {
                modes.get(RackMode.SCALE_DOWN).add(rack);
            }
            // compare the datacenter generation and the sts annotation to see if we need an update
            else if (stsNeedsUpdate(existingStsMap.get(rack), dataCenter)) {
                modes.get(RackMode.BEHIND).add(rack);
                logger.debug("sts {} has to be updated\nold:{}\nnew:{}", sts.getMetadata().getName(), existingStsMap.get(rack), sts);
            }
            else {
                modes.get(RackMode.NORMAL).add(rack);
            }
        });
        
        return modes;
    }
    
    /**
     * Enumerate the list of pods name based on existing statefulsets and .spec.replicas
     * This does not execute any network operation
     */
    private List<String> enumeratePods() {
        
        List<String> pods = new ArrayList<>();
        
        for (Map.Entry<String, V1StatefulSet> entry : existingStsMap.entrySet()) {
            String rack = entry.getKey();
            V1StatefulSet sts = entry.getValue();
            for (int i = 0; i < sts.getSpec().getReplicas(); i++) {
                pods.add(OperatorNames.podName(dataCenter, rack, i));
            }
        }
        
        return pods;
    }
    
    /**
     * Build a map of rack readiness by inspecting statefulset status
     */
    private Map<Boolean, List<String>> fetchReadinesses() {
        Map<Boolean, List<String>> readinesses = new HashMap<>();
        readinesses.put(true, new ArrayList<>());
        readinesses.put(false, new ArrayList<>());
        
        for (V1StatefulSet sts : existingStsMap.values()) {
            final Boolean readiness =
                    sts.getStatus() != null &&
                            Objects.equals(sts.getStatus().getReplicas(), sts.getSpec().getReplicas()) &&
                            Objects.equals(Optional.ofNullable(sts.getStatus().getReadyReplicas()).orElse(0), sts.getSpec().getReplicas()) &&
                            Objects.equals(Optional.ofNullable(sts.getStatus().getCurrentReplicas()).orElse(0), sts.getSpec().getReplicas()) &&
                            (
                                    // no rolling update in progress
                                    Strings.isNullOrEmpty(sts.getStatus().getUpdateRevision()) ||
                                            Objects.equals(sts.getStatus().getUpdateRevision(), sts.getStatus().getCurrentRevision())
                            );
            readinesses.get(readiness).add(sts.getMetadata().getLabels().get(OperatorLabels.RACK));
        }
        
        return readinesses;
    }
    
    /**
     * Retrieve node status (cassandra operation mode) from the cache
     */
    private Map<NodeStatus, List<String>> getStatusesFromCache() {
        
        Map<NodeStatus, List<String>> statuses = new HashMap<>();
        for (NodeStatus status : NodeStatus.values()) {
            statuses.put(status, new ArrayList<>());
        }
        
        for (String pod : enumeratePods()) {
            NodeStatus nodeStatus = Optional
                    .ofNullable(nodeStatusCache.get(new Key(pod, dataCenter.getMetadata().getNamespace())))
                    .orElse(NodeStatus.UNKNOWN);
            statuses.get(nodeStatus).add(pod);
        }
        
        return statuses;
    }
    
    /**
     * Create an ElassandraTask of type cleanup
     */
    private void triggerCleanupTask() throws ApiException {
        final String name = OperatorNames.dataCenterChildObjectName("%s-cleanup-" + UUID.randomUUID().toString().substring(0, 8), dataCenter);
        final Task cleanupTask = Task.fromDataCenter(name, dataCenter);
        cleanupTask.getSpec().setCleanup(new CleanupTaskSpec());
        k8sResourceUtils.createTask(cleanupTask);
    }
    
    // TODO: reuse part of this commented code to delete failed stuck pod with old config
//    private void tryRecoverUnschedulablePods() throws ApiException {
//
//        // search for racks that are out-of-date (RackMode.BEHIND) and that contains unschedulable pods.
//        // If found, update the first rack and kill the unschedulable pods (sts won't recreate those pods automatically sometimes...)
//
//        Optional<Map.Entry<String, List<V1Pod>>> unschedulableRack = fetchUnschedulablePods()
//                .entrySet()
//                .stream()
//                .filter(e -> racksByRackMode.get(RackMode.BEHIND).contains(e.getKey()))
//                .findFirst();
//
//        if (unschedulableRack.isPresent()) {
//            logger.info("recovering unschedulable pods {} in rack {}",
//                    unschedulableRack.get().getValue().stream().map(pod -> pod.getMetadata().getName()).collect(Collectors.toList()),
//                    unschedulableRack.get().getKey());
//            updateRack(unschedulableRack.get().getKey());
//            for (V1Pod pod : unschedulableRack.get().getValue()) {
//                try {
//                    coreApi.deleteNamespacedPod(pod.getMetadata().getName(), pod.getMetadata().getNamespace(), new V1DeleteOptions().gracePeriodSeconds(0L), null, null, null, null, null);
//                }
//                catch (com.google.gson.JsonSyntaxException e) {
//                    logger.debug("safely ignoring exception (see https://github.com/kubernetes-client/java/issues/86)", e);
//                }
//            }
//        }
//    }
//
//    private TreeMap<String, List<V1Pod>> fetchUnschedulablePods() {
//        return podsByPhase.get(PodPhase.PENDING).stream()
//                .filter(pod ->
//                        pod.getStatus().getConditions().stream()
//                                .filter(v1PodCondition -> Objects.equals(v1PodCondition.getType(), "PodScheduled"))
//                                .findFirst()
//                                .map(v1PodCondition -> Objects.equals(v1PodCondition.getStatus(), "False")
//                                        && Objects.equals(v1PodCondition.getReason(), "Unschedulable"))
//                                .orElse(Boolean.FALSE))
//                .collect(Collectors.groupingBy(
//                        pod -> pod.getMetadata().getLabels().get(OperatorLabels.RACK),
//                        TreeMap::new,
//                        Collectors.toList()));
//    }
    
    
    /**
     * Scale up a specific rack (+1)
     */
    private void scaleUp(String rack) throws ApiException {
        
        dataCenter.getStatus().setPhase(DataCenterPhase.SCALING_UP);
    
        final V1StatefulSet statefulSetToScale = newtStsMap.get(rack);
        // ensure we scale only +1
        final int replicas = existingStsMap.get(rack).getSpec().getReplicas() + 1;
        statefulSetToScale.getSpec().setReplicas(replicas);
        
        logger.info("Scaling up sts {} to {} replicas", statefulSetToScale.getMetadata().getName(), replicas);
        
        appsApi.replaceNamespacedStatefulSet(statefulSetToScale.getMetadata().getName(), statefulSetToScale.getMetadata().getNamespace(), statefulSetToScale, null, null);
    }
    
    /**
     * Scale up a specific rack (+1)
     */
    private void scaleDown(String rack) throws ApiException, MalformedURLException {
    
        dataCenter.getStatus().setPhase(DataCenterPhase.SCALING_DOWN);
        
        final V1StatefulSet statefulSetToScale = newtStsMap.get(rack);
        final int replicas = existingStsMap.get(rack).getSpec().getReplicas() - 1;
        // ensure we scale only -1
        statefulSetToScale.getSpec().setReplicas(replicas);
        
        // the name of the pod remove
        final String podName = statefulSetToScale.getMetadata().getName() + "-" + (statefulSetToScale.getSpec().getReplicas() - 1);
        
        if (podsByStatus.get(NodeStatus.NORMAL).contains(podName)) {
            logger.info("Scaling down sts {} to {}, decommissioning {}", statefulSetToScale.getMetadata().getName(), replicas, podName);
            
            // blocking call to decommission, max 5 times, with 2 second delays between each try
            Throwable throwable = sidecarClientFactory.clientForHost(OperatorNames.podFqdn(dataCenter, podName)).decommission().retryWhen(errors -> errors
                    .zipWith(Flowable.range(1, 5), (n, i) -> i)
                    .flatMap(retryCount -> Flowable.timer(2, TimeUnit.SECONDS))
            ).blockingGet();
            
            if (throwable != null) {
                logger.error("failed to decommission pod={}", podName, throwable);
                dataCenter.getStatus().setLastErrorMessage(throwable.getMessage());
            }
        }
        else if (podsByStatus.get(NodeStatus.DECOMMISSIONED).contains(podName)) {
            logger.info("Scaling down sts {} to {}, removing {}", statefulSetToScale.getMetadata().getName(), replicas, podName);
            
            
            appsApi.replaceNamespacedStatefulSet(statefulSetToScale.getMetadata().getName(), statefulSetToScale.getMetadata().getNamespace(), statefulSetToScale, null, null);
        }
    }
    
    private void updateRack(String rack) throws ApiException {
    
        dataCenter.getStatus().setPhase(DataCenterPhase.UPDATING);
        
        final V1StatefulSet newStatefulSet = newtStsMap.get(rack);
        logger.info("Update spec of sts {} (rolling restart)", newStatefulSet.getMetadata().getName());
        appsApi.replaceNamespacedStatefulSet(newStatefulSet.getMetadata().getName(), newStatefulSet.getMetadata().getNamespace(), newStatefulSet, null, null);
    }
    
    private void updateDatacenterStatus() {
    
        // set replicas status
        Tuple2<Integer,Integer> replicasStatus = existingStsMap.values().stream()
                .map(V1StatefulSet::getStatus)
                .filter(Objects::nonNull)
                .map(status -> Tuple.of(
                        Optional.ofNullable(status.getReplicas()).orElse(0),
                        Optional.ofNullable(status.getReadyReplicas()).orElse(0)))
                .reduce((t1, t2) -> Tuple.of(
                        t1._1 + t2._1,
                        t1._2 + t2._2))
                .orElseGet(() -> Tuple.of(0, 0));
        
        dataCenter.getStatus()
                .setReplicas(replicasStatus._1)
                .setReadyReplicas(replicasStatus._2)
                .setJoinedReplicas(this.podsByStatus.get(NodeStatus.NORMAL).size());

        
        // initialize pod statuses
        final List<ElassandraPodStatus> podStatuses = new ArrayList<>();
        dataCenter.getStatus().setPodStatuses(podStatuses);
    
        // initialize rack statuses
        final List<RackStatus> rackStatuses = new ArrayList<>();
        dataCenter.getStatus().setRackStatuses(rackStatuses);
    
        // for each rack
        for (Map.Entry<String, V1StatefulSet> entry : newtStsMap.entrySet()) {
            String rack = entry.getKey();
            V1StatefulSet sts = entry.getValue();
    
            // set rack status
            rackStatuses.add(new RackStatus()
                    .setName(rack)
                    .setReady(rackByReadiness.get(true).contains(rack))
                    .setMode(racksByRackMode.entrySet().stream()
                            .filter(e -> e.getValue().contains(rack))
                            .map(Map.Entry::getKey)
                            .findFirst().orElse(RackMode.UNKNOWN))
                    .setReplicas(existingStsMap.get(rack).getSpec().getReplicas())
            );
            
            // set pod statuses
            for (int i = 0; i < sts.getSpec().getReplicas(); i++) {
                String podName = OperatorNames.podName(dataCenter, rack, i);
                podStatuses.add(new ElassandraPodStatus()
                        // TODO: add more information in ElassandraPodStatus
                        .setPodName(podName)
                        .setMode(nodeStatusCache.getOrDefault(new Key(podName, dataCenter.getMetadata().getNamespace()), NodeStatus.UNKNOWN))
                );
            }
        }
    }
}