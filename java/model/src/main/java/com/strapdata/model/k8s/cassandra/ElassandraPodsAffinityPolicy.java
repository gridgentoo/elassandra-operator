package com.strapdata.model.k8s.cassandra;

/**
 * Elassandra pods affinity policy.
 */
public enum ElassandraPodsAffinityPolicy {
    STRICT, /* schedule elassandra pods only on nodes in the matching the failure-domain.beta.kubernetes.io/zone label */
    SLACK   /* schedule elassandra pods preferably on nodes in the matching the failure-domain.beta.kubernetes.io/zone label */
}
