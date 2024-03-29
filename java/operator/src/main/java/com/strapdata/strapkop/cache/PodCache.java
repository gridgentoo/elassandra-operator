package com.strapdata.strapkop.cache;

import com.strapdata.model.Key;
import io.kubernetes.client.models.V1Pod;

import javax.inject.Singleton;

@Singleton
public class PodCache extends Cache<Key, V1Pod> {
}
