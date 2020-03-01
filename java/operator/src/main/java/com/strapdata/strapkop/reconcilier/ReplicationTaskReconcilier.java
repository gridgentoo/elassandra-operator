package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.ReplicationTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Single;
import org.elasticsearch.common.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Update replication map.
 * Flush nodes when adding a DC to get all data when streaming
 */
@Singleton
@Infrastructure
public class ReplicationTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(ReplicationTaskReconcilier.class);
    private final ApplicationContext context;
    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;
    private final JmxmpElassandraProxy jmxmpElassandraProxy;

    public ReplicationTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                      final DataCenterUpdateReconcilier dataCenterUpdateReconcilier,
                                      final K8sResourceUtils k8sResourceUtils,
                                      final CustomObjectsApi customObjectsApi,
                                      final JmxmpElassandraProxy jmxmpElassandraProxy,
                                      final ApplicationContext context,
                                      final CqlRoleManager cqlRoleManager,
                                      final CqlKeyspaceManager cqlKeyspaceManager,
                                      final MeterRegistry meterRegistry) {
        super(reconcilierObserver, "replication", k8sResourceUtils, meterRegistry, dataCenterUpdateReconcilier);
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
    }

    public BlockReason blockReason() {
        return BlockReason.REPLICATION;
    }

    /**
     * Remove a datacenter from C* replication map
     *
     * @param task
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Single<TaskPhase> doTask(final Task task, DataCenter dc) throws Exception {
        final ReplicationTaskSpec replicationTaskSpec = task.getSpec().getReplication();
        final CqlSessionHandler cqlSessionHandler = context.createBean(CqlSessionHandler.class, this.cqlRoleManager);

        if (Strings.isNullOrEmpty(replicationTaskSpec.getDcName())) {
            logger.warn("datacenter={} task={} dcName not set, ignoring task", dc.id(), task.id());
            return Single.just(TaskPhase.FAILED);
        }

        Completable todo = Completable.complete();
        switch (replicationTaskSpec.getAction()) {
            case ADD:
                final Map<String, Integer> replicationMap = new HashMap<>();
                replicationMap.putAll(replicationTaskSpec.getReplicationMap());
                for (CqlKeyspace systemKs : CqlKeyspaceManager.SYSTEM_KEYSPACES)
                    replicationMap.putIfAbsent(systemKs.getName(), systemKs.getRf());

                // add replication for these keyspaces
                for (Map.Entry<String, Integer> entry : replicationMap.entrySet()) {
                    todo = todo.andThen(this.cqlKeyspaceManager.updateKeyspaceReplicationMap(dc, replicationTaskSpec.getDcName(), entry.getKey(), Math.min(entry.getValue(), replicationTaskSpec.getDcSize()), cqlSessionHandler, false));
                }

                final List<String> pods = task.getStatus().getPods().entrySet().stream()
                        .filter(e -> Objects.equals(e.getValue(), TaskPhase.WAITING))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());

                // flush sstables in parallel to stream properly
                List<CompletableSource> todoList = new ArrayList<>();
                for (String pod : pods) {
                    todoList.add(jmxmpElassandraProxy.flush(ElassandraPod.fromName(dc, pod), null)
                            .toSingleDefault(task)
                            .map(t -> {
                                // update pod status in memory (no etcd update)
                                task.getStatus().getPods().put(pod, TaskPhase.SUCCEED);
                                return t;
                            })
                            .ignoreElement()
                            .onErrorResumeNext(throwable -> {
                                logger.error("datacenter={} rebuild={} Error while executing flush on source DC pod={}", dc.id(), task.id(), pod, throwable);
                                return updateTaskPodStatus(dc, task, TaskPhase.RUNNING, pod, TaskPhase.FAILED, throwable.getMessage());
                            })
                    );
                }
                return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]))
                        .toSingleDefault(TaskPhase.SUCCEED)
                        .flatMap(phase -> finalizeTaskStatus(dc, task))
                        .onErrorResumeNext(throwable -> {
                            logger.error("datacenter={} task={} add replication failed, error={}",
                                    dc.id(), task.id(), replicationTaskSpec.getDcName(), throwable.getMessage());
                            task.getStatus().setLastMessage(throwable.getMessage());
                            return Single.just(TaskPhase.FAILED);
                        });

            case REMOVE:
                return this.cqlKeyspaceManager.removeDcFromReplicationMap(dc, replicationTaskSpec.getDcName(), cqlSessionHandler)
                        .toSingleDefault(TaskPhase.SUCCEED)
                        .flatMap(phase -> finalizeTaskStatus(dc, task))
                        .onErrorResumeNext(throwable -> {
                            logger.error("datacenter={} task={} remove replication failed, error={}",
                                    dc.id(), task.id(), replicationTaskSpec.getDcName(), throwable.getMessage());
                            task.getStatus().setLastMessage(throwable.getMessage());
                            return Single.just(TaskPhase.FAILED);
                        });
        }
        throw new IllegalArgumentException("Unknwon action");
    }

    @Override
    public Completable initializePodMap(Task task, DataCenter dc) {
        if (ReplicationTaskSpec.Action.ADD.equals(task.getSpec().getReplication().getAction())) {
            for (Map.Entry<String, ElassandraNodeStatus> entry : dc.getStatus().getElassandraNodeStatuses().entrySet()) {
                if (!entry.getValue().equals(ElassandraNodeStatus.UNKNOWN)) {
                    // only add reachable nodes (usually UNKNWON is used for unreachable or non bootstrapped node)
                    task.getStatus().getPods().put(entry.getKey(), TaskPhase.WAITING);
                }
            }
        }
        return Completable.complete();
    }
}