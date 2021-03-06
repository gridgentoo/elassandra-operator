package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.backup.BackupArguments;
import com.strapdata.model.backup.CommonBackupArguments;
import com.strapdata.model.backup.StorageProvider;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Singleton
@Infrastructure
public class BackupTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(BackupTaskReconcilier.class);
    private final SidecarClientFactory sidecarClientFactory;
    
    public BackupTaskReconcilier(K8sResourceUtils k8sResourceUtils, SidecarClientFactory sidecarClientFactory, CustomObjectsApi customObjectsApi) {
        super("backup", k8sResourceUtils);
        this.sidecarClientFactory = sidecarClientFactory;
    }
    
    
    @Override
    protected Completable doTask(Task task, DataCenter dc) throws ApiException {
        // if it's the first time, initialize the map of pods status
        if (task.getStatus().getPods() == null) {
            task.getStatus().setPhase(TaskPhase.STARTED);
            initializePodMap(task, dc);
        }
        
        // find the next pods to cleanup
        final List<String> pods = task.getStatus().getPods().entrySet().stream()
                .filter(e -> Objects.equals(e.getValue(), TaskPhase.WAITING))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        // if there is no more we are done
        if (pods.isEmpty()) {
            task.getStatus().setPhase(TaskPhase.SUCCEED);
            k8sResourceUtils.updateTaskStatus(task);
            return ensureUnlockDc(task, dc);
        }
        
        // TODO: better backup with sstableloader and progress tracking
        // right now it just call the backup api on every nodes sidecar
        try {
            for (String pod : pods) {
                final BackupArguments backupArguments = generateBackupArguments(
                        task.getMetadata().getName(),
                        StorageProvider.valueOf(task.getSpec().getBackup().getType()),
                        task.getSpec().getBackup().getTarget(),
                        OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter()),
                        pod);
    
                final boolean success = sidecarClientFactory.clientForPod(ElassandraPod.fromName(dc, pod))
                        .backup(backupArguments)
                        .doOnSuccess(backupResponse -> logger.debug("received backup response with status = {}", backupResponse.getStatus()))
                        .map(backupResponse -> backupResponse.getStatus().equalsIgnoreCase("success"))
                        .onErrorReturn(throwable -> {
                            logger.warn("error occurred from sidecar backup", throwable);
                            task.getStatus().setLastMessage(throwable.getMessage());
                            return true;
                        }).blockingGet();
        
                task.getStatus().getPods().put(pod, success ? TaskPhase.SUCCEED : TaskPhase.FAILED);
        
                if (!success) {
                    task.getStatus().setPhase(TaskPhase.FAILED);
                    break;
                }
            }
        }
        catch (Throwable throwable) {
            task.getStatus().setLastMessage(throwable.getMessage());
            task.getStatus().setPhase(TaskPhase.FAILED);
        }
        
        return k8sResourceUtils.updateTaskStatus(task);
    }
    
    
//    protected void doTaskOld(Task task, DataCenter dc) throws ApiException {
//        logger.info("processing backup task submit");
//
//        if (task.getStatus() == null || task.getStatus().getPhase() == null) {
//
//            logger.debug("Reconciling Backup");
//            callBackupApiAllPods(task).onErrorReturnItem(false).subscribe(success -> {
//                task.setStatus(new TaskStatus().setPhase(success ? TaskPhase.SUCCEED : TaskPhase.FAILED));
//                logger.info("Backup name={} namespace={} success={}",
//                        task.getMetadata().getName(), task.getMetadata().getNamespace(), success);
//                customObjectsApi.replaceNamespacedCustomObjectStatus("stable.strapdata.com", "v1",
//                        task.getMetadata().getNamespace(), "elassandratasks", task.getMetadata().getName(), task);
//            });
//        }
//    }
//
//    private Single<Boolean> callBackupApiAllPods(final Task backupTask) throws ApiException {
//
//        // backup target a single datacenter
//        final Map<String, String> labels = ImmutableMap.of(
//                OperatorLabels.CLUSTER, backupTask.getSpec().getCluster(),
//                OperatorLabels.DATACENTER, backupTask.getSpec().getDatacenter());
//
//        final String dataCenterPodsLabelSelector = OperatorLabels.toSelector(labels);
//
//        final Iterable<V1Pod> pods = k8sResourceUtils.listNamespacedPods(backupTask.getMetadata().getNamespace(), null, dataCenterPodsLabelSelector);
//        return Observable.fromIterable(pods)
//                .observeOn(Schedulers.io())
//                .flatMapSingle(pod -> callBackupApi(pod, backupTask))
//                .all(Boolean::booleanValue);
//    }
//
//    private Single<Boolean> callBackupApi(final String pod, final Task backupTask) {
//        try {
//            BackupArguments backupArguments = generateBackupArguments(
//                    backupTask.getMetadata().getName(),
//                    StorageProvider.valueOf(backupTask.getSpec().getBackup().getType()),
//                    backupTask.getSpec().getBackup().getTarget(),
//                    OperatorNames.dataCenterResource(backupTask.getSpec().getCluster(), backupTask.getSpec().getDatacenter()),
//                    pod);
//
//
//            return sidecarClientFactory.clientForPod(pod)
//                    .backup(backupArguments)
//                    .doOnSuccess(backupResponse -> logger.debug("received backup response with status = {}", backupResponse.getStatus()))
//                    .map(backupResponse -> backupResponse.getStatus().equalsIgnoreCase("success"))
//                    .onErrorReturn(throwable -> {
//                        logger.warn("error occured from sidecar backup");
//                        throwable.printStackTrace();
//                        return false;
//                    });
//        } catch (MalformedURLException | UnknownHostException e) {
//            return Single.error(e);
//        }
//    }
//
    public static BackupArguments generateBackupArguments(final String tag, final StorageProvider provider, final String target, final String cluster, final String pod) {
        BackupArguments backupArguments = new BackupArguments();
        backupArguments.cassandraConfigDirectory = Paths.get("/etc/cassandra/");
        backupArguments.cassandraDirectory = Paths.get("/var/lib/cassandra/");
        backupArguments.sharedContainerPath = Paths.get("/tmp"); // elassandra can't ran as root
        backupArguments.snapshotTag = tag;
        backupArguments.storageProvider = provider;
        backupArguments.backupBucket = target;
        backupArguments.offlineSnapshot = false;
        backupArguments.account = "";
        backupArguments.secret = "";
        backupArguments.clusterId = cluster;
        backupArguments.backupId = pod;
        backupArguments.speed = CommonBackupArguments.Speed.LUDICROUS;
        return backupArguments;
    }
}
