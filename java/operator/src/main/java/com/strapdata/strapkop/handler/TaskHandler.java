package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskSpec;
import com.strapdata.strapkop.pipeline.WorkQueue;
import com.strapdata.strapkop.reconcilier.*;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.*;

@Handler
public class TaskHandler extends TerminalHandler<K8sWatchEvent<Task>> {
    
    private final Logger logger = LoggerFactory.getLogger(TaskHandler.class);
    
    private static final EnumSet<K8sWatchEvent.Type> creationEventTypes = EnumSet.of(ADDED, MODIFIED, INITIAL);
    private static final EnumSet<K8sWatchEvent.Type> deletionEventTypes = EnumSet.of(DELETED);
    
    private final WorkQueue workQueue;
    
    private final List<Tuple2<TaskReconcilier, Function<TaskSpec, Object>>> taskFamily;
    
    public TaskHandler(WorkQueue workQueue,
                       BackupTaskReconcilier backupTaskReconcilier,
                       CleanupTaskReconcilier cleanupTaskReconcilier,
                       TestTaskReconcilier testTaskReconcilier,
                       RepairTaskReconcilier repairTaskReconcilier,
                       RebuildTaskReconcilier rebuildTaskReconcilier,
                       DecommissionTaskReconcilier decommissionTaskSpec) {
     
        this.workQueue = workQueue;

        taskFamily = ImmutableList.of(
                Tuple.of(backupTaskReconcilier, TaskSpec::getBackup),
                Tuple.of(cleanupTaskReconcilier, TaskSpec::getCleanup),
                Tuple.of(repairTaskReconcilier, TaskSpec::getRepair),
                Tuple.of(testTaskReconcilier, TaskSpec::getTest),
                Tuple.of(rebuildTaskReconcilier, TaskSpec::getRebuild),
                Tuple.of(decommissionTaskSpec, TaskSpec::getDecommission));
    }
    
    @Override
    public void accept(K8sWatchEvent<Task> event) throws Exception {
        logger.debug("Processing a Task event={}", event);
        
        final ClusterKey key = new ClusterKey(
                event.getResource().getSpec().getCluster(),
                event.getResource().getMetadata().getNamespace()
        );
        
        final List<Tuple2<TaskReconcilier, Function<TaskSpec, Object>>> candidates = taskFamily.stream()
                .filter(tuple -> tuple._2.apply(event.getResource().getSpec()) != null)
                .collect(Collectors.toList());
        
        if (candidates.size() != 1) {
            handleWrongTaskType(event);
            return ;
        }
        
        if (creationEventTypes.contains(event.getType())) {
            workQueue.submit(key, candidates.get(0)._1.prepareSubmitCompletable(event.getResource()));
        }
        else if (deletionEventTypes.contains(event.getType())) {
            // TODO: implement task cancellation
        }
    }
    
    private void handleWrongTaskType(K8sWatchEvent<Task> data) {
        logger.error("wrong task arguments for {}, no task reconcilier found", data.getResource().getMetadata().getName());
        // TODO: write message in task status
    }
    
}
