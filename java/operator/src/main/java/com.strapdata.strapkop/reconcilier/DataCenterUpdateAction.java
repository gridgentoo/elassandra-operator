package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.InetAddresses;
import com.google.gson.reflect.TypeToken;
import com.squareup.okhttp.Call;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterSpec;
import com.strapdata.model.k8s.cassandra.Enterprise;
import com.strapdata.model.k8s.task.BackupTask;
import com.strapdata.model.sidecar.NodeStatus;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorMetadata;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.*;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Prototype;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Prototype
public class DataCenterUpdateAction {
    private static final Logger logger = LoggerFactory.getLogger(DataCenterUpdateAction.class);
    
    private final CoreV1Api coreApi;
    private final AppsV1Api appsApi;
    private final CustomObjectsApi customObjectsApi;
    private final SidecarClientFactory sidecarClientFactory;
    private final K8sResourceUtils k8sResourceUtils;
    private final AuthorityManager authorityManager;
    
    private final DataCenter dataCenter;
    private final V1ObjectMeta dataCenterMetadata;
    private final DataCenterSpec dataCenterSpec;
    private final Map<String, String> dataCenterLabels;
    private final String datacenterFingerprint;
    
    public DataCenterUpdateAction(CoreV1Api coreApi, AppsV1Api appsApi,
                                  CustomObjectsApi customObjectsApi,
                                  SidecarClientFactory sidecarClientFactory,
                                  K8sResourceUtils k8sResourceUtils,
                                  AuthorityManager authorityManager,
                                  @Parameter("dataCenter") DataCenter dataCenter
    ) {
        this.coreApi = coreApi;
        this.appsApi = appsApi;
        this.customObjectsApi = customObjectsApi;
        this.sidecarClientFactory = sidecarClientFactory;
        this.k8sResourceUtils = k8sResourceUtils;
        this.authorityManager = authorityManager;
        
        this.dataCenter = dataCenter;
        this.dataCenterMetadata = dataCenter.getMetadata();
        this.dataCenterSpec = dataCenter.getSpec();
        
        // fingerprint used to identify which statefulset need to be updated.
        // this is important (I guess) because with multi-rack, we don't want to just update all the statefulset at once
        // so we need a mechanism to track which sts needs to be updated.
        this.datacenterFingerprint = DigestUtils.sha1Hex(appsApi.getApiClient().getJSON().getGson().toJson(dataCenter));
    
        // normalize Enterprise object
        if (this.dataCenterSpec.getEnterprise() == null) {
            this.dataCenterSpec.setEnterprise(new Enterprise());
        } else if (!this.dataCenterSpec.getEnterprise().getEnabled()) {
            this.dataCenterSpec.setEnterprise(new Enterprise());
        }
        
        this.dataCenterLabels = OperatorMetadata.datacenter(dataCenter);
    }
    
    void reconcileDataCenter() throws Exception {
        logger.info("Reconciling DataCenter {}.", dataCenterMetadata.getName());
        
        // create the public service (what clients use to discover the data center)
        createOrReplaceNodesService();
        
        // create the public service to access elasticsearch
        createOrReplaceElasticsearchService();
        
        // create the seed-node service (what C* nodes use to discover the DC seeds)
        final V1Service seedNodesService = createOrReplaceSeedNodesService();
        
        if (dataCenterSpec.getSsl()) {
            createKeystoreIfNotExists();
        }
        
        // sorted map of rack -> statefulset
        TreeMap<String, V1StatefulSet> statefulSetMap = new TreeMap<>(RACK_NAME_COMPARATOR);
        // TODO: ensure validation of racks > 0
        for (int rackIdx = 0; rackIdx < dataCenterSpec.getRacks(); rackIdx++) {
            int numPods = dataCenterSpec.getReplicas() / dataCenterSpec.getRacks();
            if (dataCenterSpec.getReplicas() % dataCenterSpec.getRacks() > rackIdx) {
                numPods++;
            }
            
            statefulSetMap.put(rackName(rackIdx), reconcileRack(rackIdx, numPods, seedNodesService));
        }
        
        createOrScaleAllStatefulsets(statefulSetMap);
        
        if (dataCenterSpec.getPrometheusSupport()) {
            createOrReplacePrometheusServiceMonitor();
        }
        
        logger.info("Reconciled DataCenter.");
    }
    
    private V1StatefulSet reconcileRack(final int rackIdx, final int numPods, final V1Service seedNodesService) throws Exception {
        // create configmaps and their volume mounts (operator-defined config, and user overrides)
        final List<ConfigMapVolumeMount> configMapVolumeMounts;
        {
            final ImmutableList.Builder<ConfigMapVolumeMount> builder = ImmutableList.builder();
            
            builder.add(createOrReplaceOperatorConfigMap(seedNodesService, rackName(rackIdx)));
            
            if (dataCenterSpec.getUserConfigMapVolumeSource() != null) {
                builder.add(new ConfigMapVolumeMount("user-config-volume", "/tmp/user-config", dataCenterSpec.getUserConfigMapVolumeSource()));
            }
            
            configMapVolumeMounts = builder.build();
        }
        
        logger.info("Reconciled DataCenter.");
        // create the StatefulSet for the Rack nodes
        return constructRackStatefulSet(rackIdx, numPods, configMapVolumeMounts);
    }
    
    private String dataCenterChildObjectName(final String nameFormat) {
        return String.format(nameFormat, dataCenterMetadata.getName());
    }
    
    private String rackChildObjectName(final String rack, final String nameFormat) {
        return String.format(nameFormat, dataCenterMetadata.getName() + "-" + rack);
    }
    
    private V1ObjectMeta dataCenterChildObjectMetadata(final String nameFormat) {
        return new V1ObjectMeta()
                .name(dataCenterChildObjectName(nameFormat))
                .namespace(dataCenterMetadata.getNamespace())
                .labels(dataCenterLabels)
                .putAnnotationsItem(OperatorMetadata.DATACENTER_FINGERPRINT, datacenterFingerprint);
    }
    
    private V1ObjectMeta rackChildObjectMetadata(final String rack, final String nameFormat) {
        return new V1ObjectMeta()
                .name(rackChildObjectName(rack, nameFormat))
                .namespace(dataCenterMetadata.getNamespace())
                .labels(OperatorMetadata.rack(dataCenter, rack))
                .putAnnotationsItem(OperatorMetadata.DATACENTER_FINGERPRINT, datacenterFingerprint);
    }
    
    private String rackName(final int rackIdx) {
        return "rack" + (rackIdx+1);
    }
    
    private static class ConfigMapVolumeMount {
        final String name, mountPath;
        
        final V1ConfigMapVolumeSource volumeSource;
        
        private ConfigMapVolumeMount(final String name, final String mountPath, final V1ConfigMapVolumeSource volumeSource) {
            this.name = name;
            this.mountPath = mountPath;
            this.volumeSource = volumeSource;
        }
    }
    
    private V1ContainerPort interNodePort() {
        V1ContainerPort port = new V1ContainerPort().name("internode").containerPort(dataCenterSpec.getStoragePort());
        return (dataCenterSpec.getHostPortEnabled()) ? port.hostPort(dataCenterSpec.getStoragePort()) : port;
    }
    
    private V1ContainerPort interNodeSslPort() {
        V1ContainerPort port = new V1ContainerPort().name("internode-ssl").containerPort(dataCenterSpec.getSslStoragePort());
        return (dataCenterSpec.getHostPortEnabled()) ? port.hostPort(dataCenterSpec.getSslStoragePort()) : port;
    }
    
    private V1ContainerPort nativePort() {
        V1ContainerPort port = new V1ContainerPort().name("cql").containerPort(dataCenterSpec.getNativePort());
        return (dataCenterSpec.getHostPortEnabled()) ? port.hostPort(dataCenterSpec.getNativePort()) : port;
    }
    
    private V1StatefulSet constructRackStatefulSet(final int rackIdx, final int numPods, final Iterable<ConfigMapVolumeMount> configMapVolumeMounts) throws ApiException {
        final String rack = rackName(rackIdx);
        final V1ObjectMeta statefulSetMetadata = rackChildObjectMetadata(rack, "%s");
        
        final V1Container cassandraContainer = new V1Container()
                .name("elassandra")
                .image(dataCenterSpec.getElassandraImage())
                .imagePullPolicy(dataCenterSpec.getImagePullPolicy())
                .terminationMessagePolicy("FallbackToLogsOnError")
                .addPortsItem(interNodePort())
                .addPortsItem(interNodeSslPort())
                .addPortsItem(nativePort())
                .addPortsItem(new V1ContainerPort().name("jmx").containerPort(7199))
                .resources(dataCenterSpec.getResources())
                .securityContext(new V1SecurityContext()
                        .runAsUser(999L)
                        .capabilities(new V1Capabilities().add(ImmutableList.of(
                                "IPC_LOCK",
                                "SYS_RESOURCE"
                        ))))
                .readinessProbe(new V1Probe()
                        .exec(new V1ExecAction()
                                .addCommandItem("/ready-probe.sh")
                                .addCommandItem(dataCenterSpec.getElasticsearchEnabled() ? "9200" : dataCenterSpec.getNativePort().toString())
                        )
                        .initialDelaySeconds(15)
                        .timeoutSeconds(5)
                )
                .addVolumeMountsItem(new V1VolumeMount()
                        .name("data-volume")
                        .mountPath("/var/lib/cassandra")
                )
                .addVolumeMountsItem(new V1VolumeMount()
                        .name("nodeinfo")
                        .mountPath("/nodeinfo")
                )
                .addVolumeMountsItem(new V1VolumeMount()
                        .name("pod-info")
                        .mountPath("/etc/podinfo")
                )
                .addVolumeMountsItem(new V1VolumeMount()
                        .name("sidecar-config-volume")
                        .mountPath("/tmp/sidecar-config-volume")
                )
                .addArgsItem("/tmp/sidecar-config-volume")
                .addEnvItem(new V1EnvVar().name("NAMESPACE").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.namespace"))))
                .addEnvItem(new V1EnvVar().name("POD_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.name"))))
                .addEnvItem(new V1EnvVar().name("POD_IP").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("status.podIP"))))
                .addEnvItem(new V1EnvVar().name("NODE_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("spec.nodeName"))));
        
        for (V1EnvVar envVar : this.dataCenterSpec.getEnv()) {
            if (envVar.getName().equals("NODEINFO_SECRET")) {
                cassandraContainer.addEnvItem(new V1EnvVar()
                        .name("NODEINFO_TOKEN")
                        .valueFrom(new V1EnvVarSource().secretKeyRef(new V1SecretKeySelector().name(envVar.getValue()).key("token"))));
                
                break;
            }
        }
        
        if (dataCenterSpec.getElasticsearchEnabled()) {
            cassandraContainer.addPortsItem(new V1ContainerPort().name("elasticsearch").containerPort(9200));
            cassandraContainer.addPortsItem(new V1ContainerPort().name("transport").containerPort(9300));
            cassandraContainer.addEnvItem(new V1EnvVar().name("CASSANDRA_DAEMON").value("org.apache.cassandra.service.ElassandraDaemon"));
            
            // if enterprise JMX is enabled, stop search with a pre-stop hook
            if (dataCenterSpec.getEnterprise().getJmx()) {
                cassandraContainer.lifecycle(new V1Lifecycle().preStop(new V1Handler().exec(new V1ExecAction()
                        .addCommandItem("curl -X POST http://localhost:8080/enterprise/search/disable"))));
            }
        } else {
            cassandraContainer.addEnvItem(new V1EnvVar().name("CASSANDRA_DAEMON").value("org.apache.cassandra.service.CassandraDaemon"));
        }
        
        if (dataCenterSpec.getPrometheusSupport()) {
            cassandraContainer.addPortsItem(new V1ContainerPort().name("prometheus").containerPort(9500));
        }
        
        final V1Container sidecarContainer = new V1Container()
                .name("sidecar")
                .env(dataCenterSpec.getEnv())
                .image(dataCenterSpec.getSidecarImage())
                .imagePullPolicy(dataCenterSpec.getImagePullPolicy())
                .securityContext(new V1SecurityContext().runAsUser(999L).runAsGroup(999L))
                .addPortsItem(new V1ContainerPort().name("http").containerPort(8080))
                .addVolumeMountsItem(new V1VolumeMount()
                        .name("data-volume")
                        .mountPath("/var/lib/cassandra")
                )
                .addVolumeMountsItem(new V1VolumeMount()
                        .name("sidecar-config-volume")
                        .mountPath("/tmp/sidecar-config-volume")
                );
        
        
        final V1PodSpec podSpec = new V1PodSpec()
                .securityContext(new V1PodSecurityContext().fsGroup(999L))
                .addInitContainersItem(fileLimitInit())
                .addInitContainersItem(vmMaxMapCountInit())
                .addInitContainersItem(nodeInfoInit())
                .addContainersItem(cassandraContainer)
                .addContainersItem(sidecarContainer)
                .addVolumesItem(new V1Volume()
                        .name("pod-info")
                        .downwardAPI(new V1DownwardAPIVolumeSource()
                                .addItemsItem(new V1DownwardAPIVolumeFile()
                                        .path("labels")
                                        .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.labels"))
                                )
                                .addItemsItem(new V1DownwardAPIVolumeFile()
                                        .path("annotations")
                                        .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.annotations"))
                                )
                                .addItemsItem(new V1DownwardAPIVolumeFile()
                                        .path("namespace")
                                        .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.namespace"))
                                )
                                .addItemsItem(new V1DownwardAPIVolumeFile()
                                        .path("name")
                                        .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.name"))
                                )
                        )
                )
                .addVolumesItem(new V1Volume()
                        .name("sidecar-config-volume")
                        .emptyDir(new V1EmptyDirVolumeSource())
                )
                .addVolumesItem(new V1Volume()
                        .name("nodeinfo")
                        .emptyDir(new V1EmptyDirVolumeSource())
                );
        
        {
            final String secret = dataCenterSpec.getImagePullSecret();
            if (!Strings.isNullOrEmpty(secret)) {
                final V1LocalObjectReference pullSecret = new V1LocalObjectReference().name(secret);
                podSpec.addImagePullSecretsItem(pullSecret);
            }
        }
        
        // add node affinity for rack
        if (dataCenterSpec.getRackNodeSelectors() != null && dataCenterSpec.getRackNodeSelectors().containsKey(rack)) {
            podSpec.affinity(new V1Affinity()
                    .nodeAffinity(new V1NodeAffinity()
                            .requiredDuringSchedulingIgnoredDuringExecution(dataCenterSpec.getRackNodeSelectors().get(rack))));
        }
        
        // add configmap volumes
        for (final ConfigMapVolumeMount configMapVolumeMount : configMapVolumeMounts) {
            cassandraContainer.addVolumeMountsItem(new V1VolumeMount()
                    .name(configMapVolumeMount.name)
                    .mountPath(configMapVolumeMount.mountPath)
            );
            
            // provide access to config map volumes in the sidecar, these reside in /tmp though and are not overlayed into /etc/cassandra
            sidecarContainer.addVolumeMountsItem(new V1VolumeMount()
                    .name(configMapVolumeMount.name)
                    .mountPath(configMapVolumeMount.mountPath));
            
            // the Cassandra container entrypoint overlays configmap volumes
            cassandraContainer.addArgsItem(configMapVolumeMount.mountPath);
            
            podSpec.addVolumesItem(new V1Volume()
                    .name(configMapVolumeMount.name)
                    .configMap(configMapVolumeMount.volumeSource)
            );
        }
        
        if (dataCenterSpec.getUserSecretVolumeSource() != null) {
            cassandraContainer.addVolumeMountsItem(new V1VolumeMount()
                    .name("user-secret-volume")
                    .mountPath("/tmp/user-secret-config"));
            
            podSpec.addVolumesItem(new V1Volume()
                    .name("user-secret-volume")
                    .secret(dataCenterSpec.getUserSecretVolumeSource())
            );
        }
        
        if (dataCenterSpec.getSsl()) {
            cassandraContainer.addVolumeMountsItem(new V1VolumeMount().name("operator-keystore").mountPath("/tmp/operator-keystore"));
            podSpec.addVolumesItem(new V1Volume().name("operator-keystore")
                    .secret(new V1SecretVolumeSource().secretName(dataCenterChildObjectName("%s-keystore"))
                            .addItemsItem(new V1KeyToPath().key("keystore.p12").path("keystore.p12"))));
            
            cassandraContainer.addVolumeMountsItem(new V1VolumeMount().name("operator-truststore").mountPath("/tmp/operator-truststore"));
            podSpec.addVolumesItem(new V1Volume().name("operator-truststore")
                    .secret(new V1SecretVolumeSource()
                            .secretName(dataCenterChildObjectName(this.authorityManager.getPublicCaSecretName()))
                            .addItemsItem(new V1KeyToPath().key(AuthorityManager.SECRET_CACERT_PEM).path(AuthorityManager.SECRET_CACERT_PEM))
                            .addItemsItem(new V1KeyToPath().key(AuthorityManager.SECRET_TRUSTSTORE_P12).path(AuthorityManager.SECRET_TRUSTSTORE_P12))));
        }
        
        
        if (dataCenterSpec.getRestoreFromBackup() != null) {
            logger.debug("Restore requested.");
            
            // custom objects api doesn't give us a nice way to pass in the type we want so we do it manually
            final BackupTask backup;
            {
                final Call call = customObjectsApi.getNamespacedCustomObjectCall("stable.strapdata.com", "v1", "default", "elassandra-backups", dataCenterSpec.getRestoreFromBackup(), null, null);
                backup = customObjectsApi.getApiClient().<BackupTask>execute(call, new TypeToken<BackupTask>() {
                }.getType()).getData();
            }
            
            podSpec.addInitContainersItem(new V1Container()
                    .name("sidecar-restore")
                    .env(dataCenterSpec.getEnv())
                    .image(dataCenterSpec.getSidecarImage())
                    .imagePullPolicy(dataCenterSpec.getImagePullPolicy())
                    .securityContext(new V1SecurityContext().runAsUser(999L).runAsGroup(999L))
                    .command(ImmutableList.of(
                            "java", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCGroupMemoryLimitForHeap",
                            "-cp", "/app/resources:/app/classes:/app/libs/*",
                            "com.strapdata.strapkop.sidecar.SidecarRestore",
                            "-bb", backup.getSpec().getTarget(), // bucket name
                            "-c", dataCenterMetadata.getName(), // clusterID == DcName. Backup dc and restore dc must have the same name
                            "-bi", rackChildObjectName(rack, "%s"), // pod name prefix
                            "-s", backup.getMetadata().getName(), // backup tag used to find the manifest file
                            "--bs", backup.getSpec().getBackupType(),
                            "-rs",
                            "--shared-path", "/tmp", // elassandra can't run as root,
                            "--cd", "/tmp/sidecar-config-volume" // location where the restore task can write config fragments
                    ))
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("pod-info")
                            .mountPath("/etc/podinfo")
                    ).addVolumeMountsItem(new V1VolumeMount()
                            .name("sidecar-config-volume")
                            .mountPath("/tmp/sidecar-config-volume")
                    ).addVolumeMountsItem(new V1VolumeMount()
                            .name("data-volume")
                            .mountPath("/var/lib/cassandra")
                    )
            );
        }
        
        final Map<String, String> rackLabels = OperatorMetadata.rack(dataCenter, rack);
    
        return new V1StatefulSet()
                .metadata(statefulSetMetadata)
                .spec(new V1StatefulSetSpec()
                        // if the serviceName references a headless service, kubeDNS to create an A record for
                        // each pod : $(podName).$(serviceName).$(namespace).svc.cluster.local
                        .serviceName(dataCenterChildObjectName("%s"))
                        .replicas(numPods)
                        .selector(new V1LabelSelector().matchLabels(rackLabels))
                        .template(new V1PodTemplateSpec()
                                .metadata(new V1ObjectMeta().labels(rackLabels))
                                .spec(podSpec)
                        )
                        .addVolumeClaimTemplatesItem(new V1PersistentVolumeClaim()
                                .metadata(new V1ObjectMeta().name("data-volume"))
                                .spec(dataCenterSpec.getDataVolumeClaim())
                        )
                );
    }
    
    private V1Container fileLimitInit() {
        return new V1Container()
                .securityContext(new V1SecurityContext().privileged(dataCenterSpec.getPrivilegedSupported()))
                .name("increase-ulimit")
                .image("busybox")
                .imagePullPolicy("IfNotPresent")
                .command(ImmutableList.of("sh", "-c", "ulimit -l unlimited"));
    }
    
    private V1Container vmMaxMapCountInit() {
        return new V1Container()
                .securityContext(new V1SecurityContext().privileged(dataCenterSpec.getPrivilegedSupported()))
                .name("increase-vm-max-map-count")
                .image("busybox")
                .imagePullPolicy("IfNotPresent")
                .terminationMessagePolicy("FallbackToLogsOnError")
                .command(ImmutableList.of("sysctl", "-w", "vm.max_map_count=1048575"));
    }
    
    // Nodeinfo init container
    private V1Container nodeInfoInit() {
        return new V1Container()
                .securityContext(new V1SecurityContext().privileged(dataCenterSpec.getPrivilegedSupported()))
                .name("nodeinfo")
                .image("bitnami/kubectl")
                .imagePullPolicy("IfNotPresent")
                .terminationMessagePolicy("FallbackToLogsOnError")
                .command(ImmutableList.of("sh", "-c",
                        "kubectl get no -Lfailure-domain.beta.kubernetes.io/zone --token=\"$NODEINFO_TOKEN\" | grep ${NODE_NAME} | awk '{printf(\"%s\",$6)}' > /nodeinfo/zone && " +
                                "kubectl get no -Lbeta.kubernetes.io/instance-type --token=\"$NODEINFO_TOKEN\" | grep ${NODE_NAME} | awk '{printf(\"%s\",$6)}' > /nodeinfo/instance-type && " +
                                "kubectl get no -Lstoragetier --token=\"$NODEINFO_TOKEN\" | grep ${NODEINFO_TOKEN} | awk '{printf(\"%s\",$6)}' > /nodeinfo/storagetier && " +
                                "kubectl get no -Lkubernetes.strapdata.com/public-ip --token=\"$NODEINFO_TOKEN\" | grep ${NODE_NAME} | awk '{printf(\"%s\",$6)}' > /nodeinfo/public-ip"))
                .addVolumeMountsItem(new V1VolumeMount()
                        .name("nodeinfo")
                        .mountPath("/nodeinfo")
                );
    }
    
    private static void configMapVolumeAddFile(final V1ConfigMap configMap, final V1ConfigMapVolumeSource volumeSource, final String path, final String content) {
        final String encodedKey = path.replaceAll("\\W", "_");
        
        configMap.putDataItem(encodedKey, content);
        volumeSource.addItemsItem(new V1KeyToPath().key(encodedKey).path(path));
    }
    
    private static String toYamlString(final Object object) {
        final DumperOptions options = new DumperOptions();
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(object);
    }
    
    private static final long MB = 1024 * 1024;
    private static final long GB = MB * 1024;
    
    private ConfigMapVolumeMount createOrReplaceOperatorConfigMap(final V1Service seedNodesService, final String rack) throws IOException, ApiException {
        final V1ConfigMap configMap = new V1ConfigMap()
                .metadata(rackChildObjectMetadata(rack, "%s-operator-config"));
        
        final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());
        
        // cassandra.yaml overrides
        {
            final Map<String, Object> config = new HashMap<>(); // can't use ImmutableMap as some values are null
            
            config.put("cluster_name", dataCenterSpec.getClusterName());
            config.put("num_tokens", "16");
            
            config.put("listen_address", null); // let C* discover the listen address
            // broadcast_rpc is set dynamically from entry-point.sh according to env $POD_IP
            config.put("rpc_address", "0.0.0.0"); // bind rpc to all addresses (allow localhost access)
            
            // messy -- constructs via org.apache.cassandra.config.ParameterizedClass.ParameterizedClass(java.util.Map<java.lang.String,?>)
            config.put("endpoint_snitch", "org.apache.cassandra.locator.GossipingPropertyFileSnitch");
            config.put("seed_provider", ImmutableList.of(ImmutableMap.of(
                    "class_name", "com.strapdata.cassandra.k8s.SeedProvider",
                    "parameters", ImmutableList.of(ImmutableMap.of("service", seedNodesService.getMetadata().getName()))
            )));
            
            config.put("storage_port", dataCenterSpec.getStoragePort());
            config.put("ssl_storage_port", dataCenterSpec.getSslStoragePort());
            config.put("native_transport_port", dataCenterSpec.getNativePort());
            
            configMapVolumeAddFile(configMap, volumeSource, "cassandra.yaml.d/001-operator-overrides.yaml", toYamlString(config));
        }
        
        // GossipingPropertyFileSnitch config
        {
            final Properties rackDcProperties = new Properties();
            
            rackDcProperties.setProperty("dc", dataCenterSpec.getDatacenterName());
            rackDcProperties.setProperty("rack", rack);
            rackDcProperties.setProperty("prefer_local", "true");
            
            final StringWriter writer = new StringWriter();
            rackDcProperties.store(writer, "generated by cassandra-operator");
            
            configMapVolumeAddFile(configMap, volumeSource, "cassandra-rackdc.properties", writer.toString());
        }
        
        // prometheus support
        if (dataCenterSpec.getPrometheusSupport()) {
            configMapVolumeAddFile(configMap, volumeSource, "cassandra-env.sh.d/001-cassandra-exporter.sh",
                    "JVM_OPTS=\"${JVM_OPTS} -javaagent:${CASSANDRA_HOME}/agents/cassandra-exporter-agent.jar=@${CASSANDRA_CONF}/cassandra-exporter.conf\"");
        }
        
        // this does not work with elassandra because it needs to run as root. It has been moved to the init container
        // tune ulimits
        // configMapVolumeAddFile(configMap, volumeSource, "cassandra-env.sh.d/002-cassandra-limits.sh",
        //        "ulimit -l unlimited\n" // unlimited locked memory
        //);
        
        // heap size and GC settings
        // TODO: tune
        {
            final long coreCount = 4; // TODO: not hard-coded
            final long memoryLimit = dataCenterSpec.getResources().getLimits().get("memory").getNumber().longValue();
            
            // same as stock cassandra-env.sh
            final long jvmHeapSize = Math.max(
                    Math.min(memoryLimit / 2, 1 * GB),
                    Math.min(memoryLimit / 4, 8 * GB)
            );
            
            final long youngGenSize = Math.min(
                    MB * coreCount,
                    jvmHeapSize / 4
            );
            
            final boolean useG1GC = (jvmHeapSize > 8 * GB);
            
            final StringWriter writer = new StringWriter();
            try (final PrintWriter printer = new PrintWriter(writer)) {
                printer.format("-Xms%d%n", jvmHeapSize); // min heap size
                printer.format("-Xmx%d%n", jvmHeapSize); // max heap size
                
                // copied from stock jvm.options
                if (!useG1GC) {
                    printer.format("-Xmn%d%n", youngGenSize); // young gen size
                    
                    printer.println("-XX:+UseParNewGC");
                    printer.println("-XX:+UseConcMarkSweepGC");
                    printer.println("-XX:+CMSParallelRemarkEnabled");
                    printer.println("-XX:SurvivorRatio=8");
                    printer.println("-XX:MaxTenuringThreshold=1");
                    printer.println("-XX:CMSInitiatingOccupancyFraction=75");
                    printer.println("-XX:+UseCMSInitiatingOccupancyOnly");
                    printer.println("-XX:CMSWaitDuration=10000");
                    printer.println("-XX:+CMSParallelInitialMarkEnabled");
                    printer.println("-XX:+CMSEdenChunksRecordAlways");
                    printer.println("-XX:+CMSClassUnloadingEnabled");
                    
                } else {
                    printer.println("-XX:+UseG1GC");
                    printer.println("-XX:G1RSetUpdatingPauseTimePercent=5");
                    printer.println("-XX:MaxGCPauseMillis=500");
                    
                    if (jvmHeapSize > 16 * GB) {
                        printer.println("-XX:InitiatingHeapOccupancyPercent=70");
                    }
                    
                    // TODO: tune -XX:ParallelGCThreads, -XX:ConcGCThreads
                }
                
                // OOM Error handling
                printer.println("-XX:+HeapDumpOnOutOfMemoryError");
                printer.println("-XX:+CrashOnOutOfMemoryError");
            }
            
            configMapVolumeAddFile(configMap, volumeSource, "jvm.options.d/001-jvm-memory-gc.options", writer.toString());
        }
        
        // TODO: maybe tune -Dcassandra.available_processors=number_of_processors - Wait till we build C* for Java 11
        // not sure if k8s exposes the right number of CPU cores inside the container
        
        // strapdata ssl support
        {
            addSslConfig(configMap, volumeSource);
        }
        
        //strapdata authentication support
        {
            addAuthenticationConfig(configMap, volumeSource);
        }
        
        // strapdata enterprise support
        {
            addEnterpriseConfig(configMap, volumeSource);
        }
        
        k8sResourceUtils.createOrReplaceNamespacedConfigMap(configMap);
        
        return new ConfigMapVolumeMount("operator-config-volume", "/tmp/operator-config", volumeSource);
    }
    
    private void addSslConfig(V1ConfigMap configMap, V1ConfigMapVolumeSource volumeSource) {
        if (!dataCenterSpec.getSsl()) {
            return;
        }
        
        final Map<String, Object> cassandraConfig = new HashMap<>();
        
        cassandraConfig.put("server_encryption_options", ImmutableMap.builder()
                .put("internode_encryption", "all")
                .put("keystore", "/tmp/operator-keystore/keystore.p12")
                .put("keystore_password", "changeit")
                .put("truststore", "/tmp/operator-truststore/truststore.p12")
                .put("truststore_password", "changeit")
                .put("protocol", "TLSv1.2")
                .put("algorithm", "SunX509")
                .put("store_type", "PKCS12")
                .put("cipher_suites", ImmutableList.of("TLS_RSA_WITH_AES_256_CBC_SHA"))
                .put("require_client_auth", true)
                .build()
        );
        
        cassandraConfig.put("client_encryption_options", ImmutableMap.builder()
                .put("enabled", true)
                .put("keystore", "/tmp/operator-keystore/keystore.p12")
                .put("keystore_password", "changeit")
                .put("truststore", "/tmp/operator-truststore/truststore.p12")
                .put("truststore_password", "changeit")
                .put("protocol", "TLSv1.2")
                .put("store_type", "PKCS12")
                .put("algorithm", "SunX509")
                .put("require_client_auth", false)
                .put("cipher_suites", ImmutableList.of("TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"))
                .build()
        );
        
        configMapVolumeAddFile(configMap, volumeSource, "cassandra.yaml.d/002-ssl.yaml", toYamlString(cassandraConfig));
        
        final String cqlshrc =
                "[connection]\n" +
                        "factory = cqlshlib.ssl.ssl_transport_factory\n" +
                        "port = " + dataCenterSpec.getNativePort() + "\n" +
                        "ssl = true\n" +
                        "\n" +
                        "[ssl]\n" +
                        "certfile = /tmp/operator-truststore/cacert.pem\n" +
                        "validate = true\n";
        
        configMapVolumeAddFile(configMap, volumeSource, "cqlshrc", cqlshrc);
        
        configMapVolumeAddFile(configMap, volumeSource, "curlrc", "cacert = /tmp/operator-truststore/cacert.pem");
        
        final String envScript = "" +
                "mkdir -p ~/.cassandra\n" +
                "cp ${CASSANDRA_CONF}/cqlshrc ~/.cassandra/cqlshrc\n" +
                "cp ${CASSANDRA_CONF}/curlrc ~/.curlrc\n";
        
        configMapVolumeAddFile(configMap, volumeSource, "cassandra-env.sh.d/002-ssl.sh", envScript);
    }
    
    private void addAuthenticationConfig(V1ConfigMap configMap, V1ConfigMapVolumeSource volumeSource) {
        switch (dataCenterSpec.getAuthentication()) {
            case NONE:
                break;
            case CASSANDRA:
                configMapVolumeAddFile(configMap, volumeSource, "cassandra.yaml.d/002-authentication.yaml",
                        toYamlString(ImmutableMap.of(
                                "authenticator", "PasswordAuthenticator",
                                "authorizer", "CassandraAuthorizer")));
                break;
            case LDAP:
                configMapVolumeAddFile(configMap, volumeSource, "cassandra.yaml.d/002-authentication.yaml",
                        toYamlString(ImmutableMap.of(
                                "authenticator", "com.strapdata.cassandra.ldap.LDAPAuthenticator",
                                "authorizer", "CassandraAuthorizer",
                                "role_manager", "com.strapdata.cassandra.ldap.LDAPRoleManager")));
                //TODO: Add ldap.properties + ldap.pem +
                // -Dldap.properties.file=/usr/share/cassandra/conf/ldap.properties
                // -Dcom.sun.jndi.ldap.object.disableEndpointIdentification=true"
                break;
        }
    }
    
    private void addEnterpriseConfig(V1ConfigMap configMap, V1ConfigMapVolumeSource volumeSource) {
        final Enterprise enterprise = dataCenterSpec.getEnterprise();
        if (enterprise.getEnabled()) {
            final Map<String, Object> esConfig = new HashMap<>();
            
            esConfig.put("jmx", ImmutableMap.of(
                    "enabled", enterprise.getJmx()
            ));
            
            esConfig.put("https", ImmutableMap.of(
                    "enabled", enterprise.getHttps()
            ));
            
            esConfig.put("ssl", ImmutableMap.of("transport", ImmutableMap.of(
                    "enabled", enterprise.getSsl()
            )));
            
            if (enterprise.getAaa() == null) {
                esConfig.put("aaa", ImmutableMap.of("enabled", false));
            } else {
                esConfig.put("aaa", ImmutableMap.of(
                        "enabled", enterprise.getAaa().getEnabled(),
                        "shared_secret", Optional.ofNullable(enterprise.getAaa().getSharedSecret()).orElse("dummy-generated-shared-secret"),
                        "audit", ImmutableMap.of("enabled", enterprise.getAaa().getAudit())
                ));
            }
            
            esConfig.put("cbs", ImmutableMap.of(
                    "enabled", enterprise.getCbs()
            ));
            
            configMapVolumeAddFile(configMap, volumeSource, "elasticsearch.yml.d/002-enterprise.yaml", toYamlString(esConfig));
            
            configMapVolumeAddFile(configMap, volumeSource, "cassandra-env.sh.d/002-enterprise.sh",
                    "JVM_OPTS=\"$JVM_OPTS -Dcassandra.custom_query_handler_class=org.elassandra.index.EnterpriseElasticQueryHandler\"");
            // TODO: override com exporter in cassandra-env.sh.d/001-cassandra-exporter.sh
        }
    }
    
    private V1Service createOrReplaceSeedNodesService() throws ApiException {
        final V1ObjectMeta serviceMetadata = dataCenterChildObjectMetadata("%s-seeds")
                // tolerate-unready-endpoints - allow the seed provider can discover the other seeds (and itself) before the readiness-probe gives the green light
                .putAnnotationsItem("service.alpha.kubernetes.io/tolerate-unready-endpoints", "true");
        
        final V1Service service = new V1Service()
                .metadata(serviceMetadata)
                .spec(new V1ServiceSpec()
                        .publishNotReadyAddresses(true)
                        .clusterIP("None")
                        // a port needs to be defined for the service to be resolvable (#there-was-a-bug-ID-and-now-I-cant-find-it)
                        .ports(ImmutableList.of(
                                new V1ServicePort().name("internode").port(
                                        dataCenterSpec.getSsl() ? dataCenterSpec.getSslStoragePort() : dataCenterSpec.getStoragePort())))
                        // only select the pod number 0 as seed
                        .selector(OperatorMetadata.pod(dataCenter, rackName(0), rackChildObjectName(rackName(0), "%s-0")))
                );
        k8sResourceUtils.createOrReplaceNamespacedService(service);
        
        return service;
    }
    
    private void createOrReplaceNodesService() throws ApiException {
        final V1ObjectMeta serviceMetadata = dataCenterChildObjectMetadata("%s");
        
        final V1Service service = new V1Service()
                .metadata(serviceMetadata)
                .spec(new V1ServiceSpec()
                        .clusterIP("None")
                        .addPortsItem(new V1ServicePort().name("cql").port(dataCenterSpec.getNativePort()))
                        .addPortsItem(new V1ServicePort().name("jmx").port(7199))
                        .selector(dataCenterLabels)
                );
        
        if (dataCenterSpec.getElasticsearchEnabled()) {
            service.getSpec().addPortsItem(new V1ServicePort().name("elasticsearch").port(9200));
        }
        
        if (dataCenterSpec.getPrometheusSupport()) {
            service.getSpec().addPortsItem(new V1ServicePort().name("prometheus").port(9500));
        }
        
        k8sResourceUtils.createOrReplaceNamespacedService(service);
    }
    
    private void createOrReplaceElasticsearchService() throws ApiException {
        final V1Service service = new V1Service()
                .metadata(dataCenterChildObjectMetadata("%s-elasticsearch"))
                .spec(new V1ServiceSpec()
                        .type("ClusterIP")
                        .addPortsItem(new V1ServicePort().name("elasticsearch").port(9200))
                        .selector(dataCenterLabels)
                );
        k8sResourceUtils.createOrReplaceNamespacedService(service);
    }
    
    private static final Pattern STATEFUL_SET_POD_NAME_PATTERN = Pattern.compile(".*rack(?<rack>\\d+)-(?<index>\\d+)");
    
    // comparator comparing StatefulSet Pods based on their names (which include a rack index and a node index)
    // "newest" pod first.
    static final Comparator<V1Pod> STATEFUL_SET_POD_NEWEST_FIRST_COMPARATOR = Comparator.comparingLong((V1Pod p) -> {
        final String podName = p.getMetadata().getName();
        final Matcher matcher = STATEFUL_SET_POD_NAME_PATTERN.matcher(podName);
        
        if (!matcher.matches()) {
            throw new IllegalArgumentException(String.format("Pod %s name doesn't match expression %s", podName, STATEFUL_SET_POD_NAME_PATTERN));
        }
        
        // max 100.000 nodes per rack... enough at the moment I guess
        return Long.valueOf(matcher.group("rack")) * 100000 + Long.valueOf(matcher.group("index"));
    }).reversed();
    
    
    static final Comparator<String> RACK_NAME_COMPARATOR = Comparator.comparingInt((String rack) -> Integer.valueOf(rack.substring("rack".length())));
    
    private void createOrScaleAllStatefulsets(final TreeMap<String, V1StatefulSet> statefulSetMap) throws Exception {
        
        // construct a map of existing statefulset
        TreeMap<String, V1StatefulSet> existingStatefulSetMap = new TreeMap<>(RACK_NAME_COMPARATOR);
        for (Map.Entry<String, V1StatefulSet> entry : statefulSetMap.entrySet()) {
            final String rack = entry.getKey();
            final V1StatefulSet sts = entry.getValue();
            
            // try to read the existing statefulset
            try {
                // populate the map if it exists
                existingStatefulSetMap.put(rack, appsApi.readNamespacedStatefulSet(sts.getMetadata().getName(), sts.getMetadata().getNamespace(), null, null, null));
            } catch (ApiException e) {
                if (e.getCode() != 404) throw e;
                // otherwise create it
                appsApi.createNamespacedStatefulSet(sts.getMetadata().getNamespace(), sts, null, null, null);
            }
        }
        
        // if some of the sts didn't exist we abort the reconciliation for now. The sts creation will trigger a new reconciliation anyway
        if (existingStatefulSetMap.size() < statefulSetMap.size()) {
            return;
        }
        
        final StatefulSetsReplacer statefulSetsReplacer = new StatefulSetsReplacer(coreApi, appsApi, k8sResourceUtils, sidecarClientFactory, dataCenter, statefulSetMap, existingStatefulSetMap);
        statefulSetsReplacer.replace();
    }
    
    private void createOrReplacePrometheusServiceMonitor() throws ApiException {
        final String name = dataCenterChildObjectName("%s");
        
        final ImmutableMap<String, Object> prometheusServiceMonitor = ImmutableMap.<String, Object>builder()
                .put("apiVersion", "monitoring.coreos.com/v1")
                .put("kind", "ServiceMonitor")
                .put("metadata", ImmutableMap.<String, Object>builder()
                        .put("name", name)
                        .put("labels", ImmutableMap.<String, Object>builder()
                                .putAll(dataCenterLabels)
                                .putAll(Optional.ofNullable(dataCenterSpec.getPrometheusServiceMonitorLabels()).orElse(ImmutableMap.of()))
                                .build()
                        )
                        .build()
                )
                .put("spec", ImmutableMap.<String, Object>builder()
                        .put("selector", ImmutableMap.<String, Object>builder()
                                .put("matchLabels", ImmutableMap.<String, Object>builder()
                                        .putAll(dataCenterLabels)
                                        .build()
                                )
                                .build()
                        )
                        .put("endpoints", ImmutableList.<Map<String, Object>>builder()
                                .add(ImmutableMap.<String, Object>builder()
                                        .put("port", "prometheus")
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .build();
        
        K8sResourceUtils.createOrReplaceResource(
                () -> customObjectsApi.createNamespacedCustomObject("monitoring.coreos.com", "v1", dataCenterMetadata.getNamespace(), "servicemonitors", prometheusServiceMonitor, null),
                () -> customObjectsApi.replaceNamespacedCustomObject("monitoring.coreos.com", "v1", dataCenterMetadata.getNamespace(), "servicemonitors", name, prometheusServiceMonitor)
        );
    }
    
    private void createKeystoreIfNotExists() throws Exception {
        final V1ObjectMeta certificatesMetadata = dataCenterChildObjectMetadata("%s-keystore");
        
        // check if secret exists
        try {
            coreApi.readNamespacedSecret(certificatesMetadata.getName(), certificatesMetadata.getNamespace(), null, null, null);
            return; // do not create the certificates if already exists
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }
        
        // generate statefulset wilcard certificate in a PKCS12 keystore
        final String wildcardStatefulsetName = "*." + dataCenterChildObjectName("%s") + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local";
        final String headlessServiceName = dataCenterChildObjectName("%s") + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local";
        final String elasticsearchServiceName = dataCenterChildObjectName("%s") + "-elasticsearch." + dataCenterMetadata.getNamespace() + ".svc.cluster.local";
        @SuppressWarnings("UnstableApiUsage")
        final V1Secret certificatesSecret = new V1Secret()
                .metadata(certificatesMetadata)
                .putDataItem("keystore.p12",
                        authorityManager.issueCertificateKeystore(
                                wildcardStatefulsetName,
                                ImmutableList.of(wildcardStatefulsetName, headlessServiceName, elasticsearchServiceName, "localhost"),
                                ImmutableList.of(InetAddresses.forString("127.0.0.1")),
                                dataCenterMetadata.getName(),
                                "changeit"
                        ));
        
        coreApi.createNamespacedSecret(dataCenterMetadata.getNamespace(), certificatesSecret, null, null, null);
    }
}