package com.strapdata.model.k8s.cassandra;

public enum DataCenterPhase {
    CREATING, SCALING_DOWN, SCALING_UP, RUNNING, UPDATING, EXECUTING_TASK, ERROR
}
