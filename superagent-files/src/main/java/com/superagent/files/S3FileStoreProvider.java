package com.superagent.files;

import com.superagent.core.file.FileStore;
import com.superagent.core.file.FileStoreProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * S3-compatible object storage: AWS S3, and anything speaking its API given an endpoint — MinIO, Cloudflare R2,
 * Tencent COS, Huawei OBS, …. Objects are {@code <prefix>/<file id>}; the bucket should be private, since every
 * download goes through SuperAgent's own permission checks.
 */
@Component
public class S3FileStoreProvider implements FileStoreProvider {

    @Override
    public String type() {
        return "s3";
    }

    @Override
    public String label() {
        return "S3-compatible (AWS S3, MinIO, R2, COS…)";
    }

    @Override
    public List<Field> fields() {
        return List.of(
                new Field("endpoint", "Endpoint", false, false, "https://s3.amazonaws.com — leave empty for AWS", true),
                new Field("region", "Region", false, true, "us-east-1", true),
                new Field("bucket", "Bucket", false, true, "superagent-files", true),
                new Field("prefix", "Path prefix", false, false, "files/", true),
                new Field("accessKeyId", "Access key ID", false, true, "", false),
                new Field("secretAccessKey", "Secret access key", true, true, "", false),
                new Field("pathStyle", "Path-style addressing (MinIO)", false, false, "false", false));
    }

    @Override
    public FileStore open(Map<String, String> config) {
        String endpoint = config.get("endpoint");
        var builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(config.get("region")))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.get("accessKeyId"), config.get("secretAccessKey"))))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled("true".equalsIgnoreCase(config.get("pathStyle"))).build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.strip()));
        }
        return new S3FileStore(builder.build(), config.get("bucket"), prefix(config.get("prefix")));
    }

    static String prefix(String raw) {
        String p = raw == null ? "" : raw.strip().replaceAll("^/+", "");
        return p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }

    static final class S3FileStore implements FileStore {

        private final S3Client s3;
        private final String bucket;
        private final String prefix;

        S3FileStore(S3Client s3, String bucket, String prefix) {
            this.s3 = s3;
            this.bucket = bucket;
            this.prefix = prefix;
        }

        private String key(String id) {
            if (id.isBlank() || id.contains("/") || id.contains("..")) {
                throw new IllegalArgumentException("Bad file id: " + id);
            }
            return prefix + id;
        }

        @Override
        public void put(String id, Path source, String contentType) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key(id))
                    .contentType(contentType == null ? "application/octet-stream" : contentType).build(),
                    RequestBody.fromFile(source));
        }

        @Override
        public InputStream open(String id) throws IOException {
            try {
                return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key(id)).build());
            } catch (NoSuchKeyException e) {
                throw new NoSuchFileException(prefix + id);
            }
        }

        @Override
        public void delete(String id) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(id)).build());
        }
    }
}
