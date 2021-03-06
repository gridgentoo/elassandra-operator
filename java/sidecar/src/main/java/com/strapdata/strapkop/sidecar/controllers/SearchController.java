package com.strapdata.strapkop.sidecar.controllers;


import com.strapdata.strapkop.sidecar.cassandra.CassandraModule;
import com.strapdata.strapkop.sidecar.cassandra.ElasticNodeMetricsMBean;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "search")
@Controller("/enterprise/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchController {

    private ElasticNodeMetricsMBean elasticNodeMetricsMBean;

    public SearchController(CassandraModule cassandraModule) {
        this.elasticNodeMetricsMBean = cassandraModule.elasticNodeMetricsMBeanProvider();
    }

    /**
     * Return Elassandra ENterprise search status.
     * @return
     */
    @Get("/")
    public Single<Boolean> isSearchEnabled() {
        return Single.create(emitter -> {
            emitter.onSuccess(elasticNodeMetricsMBean.isSearchEnabled());
        });
    }

    /**
     * Enable search (the node contributes to distributed Elasticseach search requests).
     * @return
     */
    @Post("/enable")
    public Completable enable() {
        return Completable.create(emitter -> {
            elasticNodeMetricsMBean.setSearchEnabled(true);
            emitter.onComplete();
        });
    }

    /**
     * Disable search (the node does not contribute to distributed Elasticseach search requests).
     * @return
     */
    @Post("/disable")
    public Completable disable() {
        return Completable.create(emitter -> {
            elasticNodeMetricsMBean.setSearchEnabled(false);
            emitter.onComplete();
        });
    }
}
