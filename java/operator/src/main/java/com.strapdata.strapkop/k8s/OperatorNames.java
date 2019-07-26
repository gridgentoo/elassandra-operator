package com.strapdata.strapkop.k8s;

import com.strapdata.model.k8s.cassandra.DataCenter;

public class OperatorNames {
    
    
    public static String clusterChildObjectName(final String nameFormat, final DataCenter dataCenter) {
        return String.format(nameFormat, "elassandra-" + dataCenter.getSpec().getClusterName());
    }
    
    public static String dataCenterChildObjectName(final String nameFormat, final DataCenter dataCenter) {
        return String.format(nameFormat,
                "elassandra-" + dataCenter.getSpec().getClusterName()
                        + "-" + dataCenter.getSpec().getDatacenterName());
    }
    
    public static String rackChildObjectName(final String nameFormat, final DataCenter dataCenter, final String rack) {
        return String.format(nameFormat,
                "elassandra-" + dataCenter.getSpec().getClusterName()
                        + "-" + dataCenter.getSpec().getDatacenterName()
                        + "-" + rack);
    }
    
    public static String strapkopCredentials(final DataCenter dataCenter) {
        return OperatorNames.clusterChildObjectName("%s-credentials-strapkop", dataCenter);
    }
    
    public static String adminCredentials(final DataCenter dataCenter) {
        return OperatorNames.clusterChildObjectName("%s-credentials-admin", dataCenter);
    }
    
    public static String keystore(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-keystore", dataCenter);
    }
    
    public static String sharedSecret(final DataCenter dataCenter) {
        // TODO: check if this need to be set cluster wide
        return OperatorNames.dataCenterChildObjectName("%s-shared-secret", dataCenter);
    }
    
    public static String nodesService(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s", dataCenter);
    }
    
    public static String elasticsearchService(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-elasticsearch", dataCenter);
    }
    
    public static String seedsService(DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-seeds", dataCenter);
    }
    
    public static String prometheusServiceMonitor(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s", dataCenter);
    }
    
    public static String varConfig(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-operator-var-config", dataCenter);
    }

    public static String specConfig(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-operator-spec-config", dataCenter);
    }
    
    public static String rackConfig(final DataCenter dataCenter, final String rack) {
        return OperatorNames.rackChildObjectName("%s-operator-config", dataCenter, rack);
    }
    
    public static String stsName(final DataCenter dataCenter, final String rack) {
        return OperatorNames.rackChildObjectName("%s", dataCenter, rack);
    }
    
    public static String podName(final DataCenter dataCenter, final String rack, int podIndex) {
        return OperatorNames.rackChildObjectName("%s-" + podIndex, dataCenter, rack);
    }
}