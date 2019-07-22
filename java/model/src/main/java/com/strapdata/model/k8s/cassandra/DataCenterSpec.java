
package com.strapdata.model.k8s.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.models.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DataCenterSpec {
    
    @SerializedName("clusterName")
    @Expose
    private String clusterName;
    
    @SerializedName("datacenterName")
    @Expose
    private String datacenterName;
    
    /**
     * Number of Cassandra nodes in this data center.
     * 
     */
    @SerializedName("replicas")
    @Expose
    private int replicas;

    @SerializedName("racks")
    @Expose
    private int racks;
    
    @SerializedName("rackNodeSelectors")
    @Expose
    private Map<String, V1NodeSelector> rackNodeSelectors;
    
    @SerializedName("elassandraImage")
    @Expose
    private java.lang.String elassandraImage;

    @SerializedName("sidecarImage")
    @Expose
    private java.lang.String sidecarImage;

    @SerializedName("imagePullPolicy")
    @Expose
    private java.lang.String imagePullPolicy;

    @SerializedName("imagePullSecret")
    @Expose
    private java.lang.String imagePullSecret;
    
    /**
     * List of environment variables to inject in the Cassandra & Sidecar container.
     * 
     */
    @SerializedName("env")
    @Expose
    private List<V1EnvVar> env = new ArrayList<V1EnvVar>();

    /**
     * Resource requirements for the Cassandra container.
     * 
     */
    @SerializedName("resources")
    @Expose
    private V1ResourceRequirements resources;

    @SerializedName("dataVolumeClaim")
    @Expose
    private V1PersistentVolumeClaimSpec dataVolumeClaim;

    /**
     * Name of the CassandraBackup to restore from
     * 
     */
    @SerializedName("restoreFromBackup")
    @Expose
    private java.lang.String restoreFromBackup;

    /**
     * Name of an optional config map that contains cassandra configuration in the form of yaml fragments
     * 
     */
    @SerializedName("userConfigMapVolumeSource")
    @Expose
    private V1ConfigMapVolumeSource userConfigMapVolumeSource;

    /**
     * Name of an optional secret that contains cassandra related secrets
     * 
     */
    @SerializedName("userSecretVolumeSource")
    @Expose
    private V1SecretVolumeSource userSecretVolumeSource;

    /**
     * Enable Prometheus support.
     * 
     */
    @SerializedName("prometheusSupport")
    @Expose
    private Boolean prometheusSupport;

    /**
     * Labels to attach to the Prometheus ServiceMonitor for this data center.
     * 
     */
    @SerializedName("prometheusServiceMonitorLabels")
    @Expose
    private Map<String, String> prometheusServiceMonitorLabels;

    /**
     * Attempt to run privileged configuration options for better performance
     * 
     */
    @SerializedName("privilegedSupported")
    @Expose
    private Boolean privilegedSupported;

    /**
     * Enable elasticsearch service
     */
    @SerializedName("elasticsearchEnabled")
    @Expose
    private Boolean elasticsearchEnabled = true;

    /**
     * Enable hostPort for nativePort, storagePort and sslStoragePort
     */
    @SerializedName("hostPortEnabled")
    @Expose
    private Boolean hostPortEnabled = true;

    /**
     * CQL native port (also hostPort)
     */
    @SerializedName("nativePort")
    @Expose
    private Integer nativePort = 39042;

    /**
     * Cassandra storage port (also hostPort)
     */
    @SerializedName("storagePort")
    @Expose
    private Integer storagePort = 37000;

    /**
     * Cassandra storage port (also hostPort)
     */
    @SerializedName("sslStoragePort")
    @Expose
    private Integer sslStoragePort = 37001;

    /**
     * Enable SSL support
     */
    @SerializedName("ssl")
    @Expose
    private Boolean ssl = false;

    /**
     * Enable cassandra/ldap authentication and authorization
     */
    @SerializedName("authentication")
    @Expose
    private Authentication authentication = Authentication.CASSANDRA;

    @SerializedName("enterprise")
    @Expose
    private Enterprise enterprise;
    
    @SerializedName("remoteSeeds")
    @Expose
    private List<String> remoteSeeds;
}
