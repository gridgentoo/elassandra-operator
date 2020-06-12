package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.DataCenterStatusCache;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.task.CleanupTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sequentially cleanup nodes of a datacenter, waiting 10 secondes between node cleanup.
 */
@Singleton
@Infrastructure
public final class CleanupTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(CleanupTaskReconcilier.class);

    private final JmxmpElassandraProxy jmxmpElassandraProxy;

    public CleanupTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                  final OperatorConfig operatorConfig,
                                  final K8sResourceUtils k8sResourceUtils,
                                  final JmxmpElassandraProxy jmxmpElassandraProxy,
                                  final MeterRegistry meterRegistry,
                                  final DataCenterController dataCenterController,
                                  final DataCenterCache dataCenterCache,
                                  final DataCenterStatusCache dataCenterStatusCache,
                                  ExecutorFactory executorFactory,
                                  @Named("tasks") UserExecutorConfiguration userExecutorConfiguration) {
        super(reconcilierObserver, operatorConfig, k8sResourceUtils, meterRegistry,
                dataCenterController, dataCenterCache, dataCenterStatusCache, executorFactory, userExecutorConfiguration);
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
    }

    /**
     * Execute task on each pod and update the task status
     * @param task
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Completable doTask(final DataCenter dc, final DataCenterStatus dataCenterStatus, final Task task, Iterable<V1Pod> pods) throws ApiException {
        // do clean up on each pod with 10 sec interval
        // TODO: maybe we should try to caught outer exception (even if we already catch inside doOnNext)
        final CleanupTaskSpec cleanupTaskSpec = task.getSpec().getCleanup();
        return Observable.zip(Observable.fromIterable(pods), Observable.interval(cleanupTaskSpec.getWaitIntervalInSec(), TimeUnit.SECONDS), (pod, timer) -> pod)
                .subscribeOn(Schedulers.io())
                .flatMapSingle(pod ->
                        jmxmpElassandraProxy.cleanup(ElassandraPod.fromV1Pod(pod), task.getSpec().getCleanup().getKeyspace())
                        .doOnComplete(() -> {
                            task.getStatus().getPods().put(pod.getMetadata().getName(), TaskPhase.SUCCEED);
                        })
                        .doOnError(throwable -> {
                            logger.error("datacenter={} cleanup={} Error while executing cleanup on pod={}", dc.id(), task.id(), pod, throwable);
                            task.getStatus().setLastMessage(throwable.getMessage());
                            task.getStatus().getPods().put(pod.getMetadata().getName(), TaskPhase.FAILED);
                        })
                        .toSingleDefault(pod))
                .toList()
                .flatMapCompletable(list -> finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.SUCCEED, "cleanup"));
    }

    @Override
    public Single<List<V1Pod>> init(Task task, DataCenter dc) {
        return listAllDcPods(task, dc).map(pods -> initTaskStatusPodMap(task, pods));
    }
}
