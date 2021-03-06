package com.strapdata.strapkop.pipeline;

import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.Cache;
import com.strapdata.strapkop.cache.PodCache;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.k8s.ReaperLabels;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodList;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;

import javax.inject.Named;
import java.lang.reflect.Type;
import java.util.Collection;

@Context
@Infrastructure
public class ReaperPipeline extends K8sWatchPipeline<V1Pod, V1PodList>  {

    public ReaperPipeline(@Named("apiClient") ApiClient apiClient, CoreV1Api coreV1Api, OperatorConfig config, PodCache cache) {
        super(apiClient, new ReaperAdapter(coreV1Api, config), cache);
    }

    public static class ReaperAdapter extends K8sWatchResourceAdapter<V1Pod, V1PodList> {
        private OperatorConfig config;
        private CoreV1Api coreV1Api;

        public ReaperAdapter(CoreV1Api coreV1Api, OperatorConfig config) {
            this.coreV1Api = coreV1Api;
            this.config = config;
        }

        @Override
        public Type getResourceType() {
            return V1Pod.class;
        }

        @Override
        public Type getResourceListType() {
            return V1PodList.class;
        }

        @Override
        public Call createListApiCall(boolean watch, String resourceVersion) throws ApiException {
            return coreV1Api.listNamespacedPodCall( config.getNamespace(), false, null, null,
                    null, OperatorLabels.toSelector(ReaperLabels.PODS_SELECTOR), null,
                    null, null, watch, null, null
            );
        }

        @Override
        public Key getKey(V1Pod resource) {
            return new Key(resource.getMetadata());
        }

        @Override
        public V1ObjectMeta getMetadata(V1Pod resource) {
            return resource.getMetadata();
        }

        @Override
        public Collection<? extends V1Pod> getListItems(V1PodList list) {
            return list.getItems();
        }

        @Override
        public V1ListMeta getListMetadata(V1PodList list) {
            return list.getMetadata();
        }
    }
}
