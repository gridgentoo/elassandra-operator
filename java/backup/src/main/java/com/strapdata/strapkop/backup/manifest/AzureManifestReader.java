package com.strapdata.strapkop.backup.manifest;

import com.strapdata.strapkop.backup.common.AzureRemoteObjectReference;
import com.strapdata.strapkop.backup.common.Constants;
import com.strapdata.strapkop.backup.common.RemoteObjectReference;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AzureManifestReader extends ManifestReader {
    private static final Logger logger = LoggerFactory.getLogger(AzureManifestReader.class);

    private final CloudBlobContainer blobContainer;

    public AzureManifestReader(final CloudBlobClient cloudBlobClient,
                               final String rootBackupDir,
                               final String restoreFromNamespace,
                               final String restoreFromClusterId,
                               final String restoreFromBackupBucket)
            throws StorageException, URISyntaxException {
        super(rootBackupDir, restoreFromNamespace, restoreFromClusterId, restoreFromBackupBucket);
        this.blobContainer = cloudBlobClient.getContainerReference(restoreFromBackupBucket);
    }

    @Override
    public RemoteObjectReference objectKeyToRemoteReference(Path objectKey) throws Exception {
        String path = resolveRemotePath(objectKey);
        return new AzureRemoteObjectReference(objectKey, path,  blobContainer.getBlockBlobReference(path));
    }

    @Override
    public RemoteObjectReference taskDescriptionRemoteReference(String taskName) throws Exception {
        final String path = resolveTaskDescriptionRemotePath(taskName);
        return new AzureRemoteObjectReference(Paths.get(Constants.TASK_DESCRIPTION_DOWNLOAD_DIR).resolve(taskName), path, blobContainer.getBlockBlobReference(path));
    }

    public GlobalManifest aggregateManifest(final String manifestName) {
        Iterable<com.microsoft.azure.storage.blob.ListBlobItem> blobs = blobContainer.listBlobs(getPrefix());

        final int nodePathIndex = ("/" + restoreFromBackupBucket + "/" + restoreFromClusterId + "/").length();
        return Observable.fromIterable(blobs).observeOn(Schedulers.io())
                .doOnNext(blob -> logger.debug("AggregateManifest found nodePath '{}'", blob.getUri()))
                .map(blob -> {
                    final String nodePath = blob.getUri().getPath().substring(nodePathIndex);
                    final String nodeManifestPath = nodePath + "manifests/" + manifestName;
                    AzureRemoteObjectReference ref = (AzureRemoteObjectReference)objectKeyToRemoteReference(Paths.get(nodeManifestPath));
                    if (ref.blob.exists()) {
                        logger.debug("Manifest '{}' found for nodePath '{}'", manifestName, nodePath);
                        return new Tuple2<String, String>(nodePath.split("/")[0], "manifests/" + manifestName);
                    } else {
                        logger.debug("Manifest '{}' not found for nodePath '{}'", manifestName, nodePath);
                        return new Tuple2<String, String>(nodePath.split("/")[0], NOMANIFEST);
                    }
                })
                .filter(tuple -> !NOMANIFEST.equals(tuple._2)).collect(
                () -> new GlobalManifest(this.restoreFromClusterId, manifestName),
                (globalManifest, tuple) -> globalManifest.addManifest(tuple._1, tuple._2)
        ).blockingGet();
    }
}