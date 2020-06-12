package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonUtils;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cassandra reaper default configuration.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Reaper {

    /**
     * Reaper docker image;
     */
    @JsonPropertyDescription("reaper docker image")
    @SerializedName("image")
    @Expose
    private String image  = "strapdata/cassandra-reaper:2.1.0";

    /**
     * Resource requirements for the reaper container.
     *
     */
    @JsonPropertyDescription("Resource requirements for reaper pod")
    @SerializedName("resources")
    @Expose
    private V1ResourceRequirements resources = null;

    /**
     * PodTemplate provides pod customisation (labels, resource, annotations, affinity rules, resource, priorityClassName, serviceAccountName) for the reaper pods
     */
    @JsonPropertyDescription("Reaper pods template allowing customisation")
    @SerializedName("podTemplate")
    @Expose
    private V1PodTemplateSpec podTemplate = new V1PodTemplateSpec();

    /**
     * Reaper JWT secret
     */
    @JsonPropertyDescription("Reaper JWT secret")
    @SerializedName("jwtSecret")
    @Expose
    private String jwtSecret = null;

    /**
     * Enable Cassandra Reaper support.
     *
     */
    @JsonPropertyDescription("Reaper enabled")
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = false;

    @JsonPropertyDescription("Reaper logging level")
    @SerializedName("loggingLevel")
    @Expose
    private String loggingLevel = "INFO";

    /**
     * Reaper ingress suffix concatened with "repaer-" and "reaper-admin-"
     * reaper-suffix
     * reaper-admin-suffix
     */
    @JsonPropertyDescription("Reaper ingress suffix")
    @SerializedName("ingressSuffix")
    @Expose
    private String ingressSuffix = null;

    /**
     * Ingress annotations
     */
    @JsonPropertyDescription("Reaper ingress annotations")
    @SerializedName("ingressAnnotations")
    @Expose
    private Map<String, String> ingressAnnotations = null;

    @JsonPropertyDescription("Reaper scheduled repairs configuration")
    @SerializedName("scheduledRepairs")
    @Expose
    private List<ReaperScheduledRepair> scheduledRepairs = null;

    public String reaperFingerprint() {
        List<Object> acc = new ArrayList<>();

        // we exclude :
        // * Reaper config
        // * Kibana config
        // * parked attribute
        // * scheduledBackups (DC reconciliation is useless in this case, we only want to update Scheduler)
        acc.add(this);
        String json = GsonUtils.toJson(acc);
        String digest = DigestUtils.sha1Hex(json).substring(0,7);
        return digest;
    }
}
