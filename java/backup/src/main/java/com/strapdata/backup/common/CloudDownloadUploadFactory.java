package com.strapdata.backup.common;

import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.strapdata.backup.manifest.AWSManifestReader;
import com.strapdata.backup.manifest.AzureManifestReader;
import com.strapdata.backup.manifest.GCPManifestReader;
import com.strapdata.backup.manifest.ManifestReader;
import com.strapdata.backup.downloader.*;
import com.strapdata.backup.uploader.*;
import com.strapdata.model.backup.BackupArguments;
import com.strapdata.model.backup.RestoreArguments;
import com.strapdata.backup.downloader.*;
import com.strapdata.backup.uploader.*;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.strapdata.model.backup.StorageProvider;

import javax.naming.ConfigurationException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;

public class CloudDownloadUploadFactory {

    public static TransferManager getTransferManager() {
        /*
         * Environment Variables - AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY (RECOMMENDED since they are recognized by all the AWS SDKs and CLI except for .NET), or AWS_ACCESS_KEY and AWS_SECRET_KEY (only recognized by Java SDK)
         * Java System Properties - aws.accessKeyId and aws.secretKey
         * Credential profiles file at the default location (~/.aws/credentials) shared by all AWS SDKs and the AWS CLI
         * Credentials delivered through the Amazon EC2 container service if AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" environment variable is set and security manager has permission to access the variable,
         * Instance profile credentials delivered through the Amazon EC2 metadata service
         *
         */

        return TransferManagerBuilder.defaultTransferManager();
    }

    public static CloudBlobClient getCloudBlobClient() throws URISyntaxException, InvalidKeyException {
        
        //TODO: use azure SAS token ?
        
        // Seems that the azure-storage java SDK does not support credentials discovery. However az cli does support this
        // so here we try to reproduce the same behavior using AZURE_STORAGE_ACCOUNT and AZURE_STORAGE_KEY
        final String accountName = System.getenv("AZURE_STORAGE_ACCOUNT");
        String accessKey = System.getenv("AZURE_STORAGE_KEY");
        if (accessKey == null) {
            // az cli also uses AZURE_STORAGE_ACCESS_KEY because of a bug : https://github.com/MicrosoftDocs/azure-docs/issues/14365
            accessKey = System.getenv("AZURE_STORAGE_ACCESS_KEY");
        }
        
        
        // TODO: what to do when credentials are not specified ?
        final String connectionString = "DefaultEndpointsProtocol=https;"
                + String.format("AccountName=%s;", accountName)
                + String.format("AccountKey=%s", accessKey);
    
        final CloudStorageAccount account = CloudStorageAccount.parse(connectionString);
        return account.createCloudBlobClient();
    }

    public static Storage getGCPStorageClient() {
        /*
         * Instance profile,
         * GOOGLE_APPLICATION_CREDENTIALS env var, or
         * application_default_credentials.json default
         */
        return StorageOptions.getDefaultInstance().getService();
    }

    public static ManifestReader getManifestReader(StorageProvider provider, String bucket, String clusterName) throws URISyntaxException, StorageException, ConfigurationException, InvalidKeyException {
        switch (provider) {
            case AWS_S3:
                //TODO: support encrypted backups via KMS
                //AWS client set to auto detect credentials
                return new AWSManifestReader(getTransferManager(), clusterName, bucket);
            case AZURE_BLOB:
                //TODO: use SAS token?
                return new AzureManifestReader(getCloudBlobClient(), clusterName, bucket);
            case GCP_BLOB:
                return new GCPManifestReader(getGCPStorageClient(), clusterName, bucket);
            default:
        }
        throw new ConfigurationException("Could not create Manifest Reader");
    }

    public static SnapshotUploader getUploader(final BackupArguments arguments) throws URISyntaxException, StorageException, ConfigurationException, InvalidKeyException {
        //final String backupID, final String clusterID, final String backupBucket,

        switch (arguments.storageProvider) {
            case AWS_S3:
                //TODO: support encrypted backups via KMS
                //AWS client set to auto detect credentials
                return new AWSSnapshotUploader(getTransferManager(), arguments);
            case AZURE_BLOB:
                //TODO: use SAS token?
                return new AzureSnapshotUploader(getCloudBlobClient(), arguments);
            case GCP_BLOB:
                return new GCPSnapshotUploader(getGCPStorageClient(), arguments);
            case FILE:
                return new LocalFileSnapShotUploader(arguments);
        }
        throw new ConfigurationException("Could not create Snapshot Uploader");
    }


    public static Downloader getDownloader(final RestoreArguments arguments) throws URISyntaxException, StorageException, ConfigurationException, InvalidKeyException {
        switch (arguments.storageProvider) {
            case AWS_S3:
                //TODO: support encrypted backups via KMS
                //AWS client set to auto detect credentials
                return new AWSDownloader(getTransferManager(), arguments);
            case AZURE_BLOB:
                //TODO: use SAS token?
                return new AzureDownloader(getCloudBlobClient(), arguments);
            case GCP_BLOB:
                return new GCPDownloader(getGCPStorageClient(), arguments);
            case FILE:
                return new LocalFileDownloader(arguments);
        }
        throw new ConfigurationException("Could not create Snapshot Uploader");
    }


}
