/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2017-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.blobstore.gcloud.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.AttributesLocation;
import org.sonatype.nexus.blobstore.BlobIdLocationResolver;
import org.sonatype.nexus.blobstore.BlobStoreSupport;
import org.sonatype.nexus.blobstore.BlobSupport;
import org.sonatype.nexus.blobstore.DefaultBlobIdLocationResolver;
import org.sonatype.nexus.blobstore.MetricsInputStream;
import org.sonatype.nexus.blobstore.StreamMetrics;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobAttributes;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobMetrics;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.api.BlobStoreUsageChecker;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaService;
import org.sonatype.nexus.common.log.DryRunPrefix;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.logging.task.ProgressLogIntervalHelper;
import org.sonatype.nexus.scheduling.CancelableHelper;
import org.sonatype.nexus.scheduling.PeriodicJobService;
import org.sonatype.nexus.scheduling.PeriodicJobService.PeriodicJob;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.annotation.Timed;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobField;
import com.google.cloud.storage.Storage.BlobGetOption;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.StorageClass;
import com.google.cloud.storage.StorageException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.hash.HashCode;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.cache.CacheLoader.from;
import static com.google.common.collect.Streams.stream;
import static java.lang.String.format;
import static org.sonatype.nexus.blobstore.DirectPathLocationStrategy.DIRECT_PATH_ROOT;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.createQuotaCheckJob;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.FAILED;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.NEW;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STOPPED;

/**
 * Google Cloud Storage backed {@link BlobStore}.
 */
@Named(GoogleCloudBlobStore.TYPE)
public class GoogleCloudBlobStore
    extends BlobStoreSupport<GoogleAttributesLocation>
{
  public static final String TYPE = "Google Cloud Storage";

  public static final String CONFIG_KEY = TYPE.toLowerCase();

  public static final String BUCKET_KEY = "bucket";

  public static final String CREDENTIAL_FILE_KEY = "credential_file";

  public static final String LOCATION_KEY = "location";

  public static final String BLOB_CONTENT_SUFFIX = ".bytes";

  static final String CONTENT_PREFIX = "content";

  public static final String METADATA_FILENAME = "metadata.properties";

  public static final String TYPE_KEY = "type";

  public static final String TYPE_V1 = "gcp/1";

  private static final String FILE_V1 = "file/1";

  private final GoogleCloudStorageFactory storageFactory;

  private final ShardedCounterMetricsStore metricsStore;

  private Storage storage;

  private Bucket bucket;

  private GoogleCloudDatastoreFactory datastoreFactory;

  private DeletedBlobIndex deletedBlobIndex;

  private LoadingCache<BlobId, GoogleCloudStorageBlob> liveBlobs;

  private MetricRegistry metricRegistry;

  private PeriodicJobService periodicJobService;

  private BlobStoreQuotaService quotaService;

  private PeriodicJob quotaCheckingJob;

  private final int quotaCheckInterval;
  
  @Inject
  public GoogleCloudBlobStore(final GoogleCloudStorageFactory storageFactory,
                              final BlobIdLocationResolver blobIdLocationResolver,
                              final PeriodicJobService periodicJobService,
                              final ShardedCounterMetricsStore metricsStore,
                              final GoogleCloudDatastoreFactory datastoreFactory,
                              final DryRunPrefix dryRunPrefix,
                              final MetricRegistry metricRegistry,
                              final BlobStoreQuotaService quotaService,
                              @Named("${nexus.blobstore.quota.warnIntervalSeconds:-60}")
                              final int quotaCheckInterval)
  {
    super(blobIdLocationResolver, dryRunPrefix);
    this.periodicJobService = periodicJobService;
    this.storageFactory = checkNotNull(storageFactory);
    this.metricsStore = metricsStore;
    this.datastoreFactory = datastoreFactory;
    this.metricRegistry = metricRegistry;
    this.quotaService = quotaService;
    this.quotaCheckInterval = quotaCheckInterval;
  }

  @Override
  protected void doStart() throws Exception {
    GoogleCloudPropertiesFile metadata = new GoogleCloudPropertiesFile(bucket, METADATA_FILENAME);
    if (metadata.exists()) {
      metadata.load();
      String type = metadata.getProperty(TYPE_KEY);
      checkState(TYPE_V1.equals(type) || FILE_V1.equals(type),
          "Unsupported blob store type/version: %s in %s", type, metadata);
    }
    else {
      // assumes new blobstore, write out type
      metadata.setProperty(TYPE_KEY, TYPE_V1);
      metadata.store();
    }
    liveBlobs = CacheBuilder.newBuilder().weakValues().recordStats().build(from(GoogleCloudStorageBlob::new));
    
    wrapWithGauge("liveBlobsCache.size", () -> liveBlobs.size());
    wrapWithGauge("liveBlobsCache.hitCount", () -> liveBlobs.stats().hitCount());
    wrapWithGauge("liveBlobsCache.missCount", () -> liveBlobs.stats().missCount());
    wrapWithGauge("liveBlobsCache.totalLoadTime", () -> liveBlobs.stats().totalLoadTime());
    wrapWithGauge("liveBlobsCache.evictionCount", () -> liveBlobs.stats().evictionCount());
    wrapWithGauge("liveBlobsCache.requestCount", () -> liveBlobs.stats().requestCount());

    metricsStore.setBlobStore(this);
    metricsStore.start();
    this.quotaCheckingJob = periodicJobService.schedule(createQuotaCheckJob(this, quotaService, log), quotaCheckInterval);
  }

  @Override
  protected void doStop() throws Exception {
    liveBlobs = null;
    metricsStore.stop();
    quotaCheckingJob.cancel();
  }

  protected void wrapWithGauge(String nameSuffix, Supplier valueSupplier) {
    metricRegistry.gauge(
        format("%s@%s.%s",GoogleCloudBlobStore.class.getName(), getBlobStoreConfiguration().getName(), nameSuffix),
        () -> () -> valueSupplier.get());
  }

  @Override
  protected Blob doCreate(final InputStream blobData,
                          final Map<String, String> headers,
                          @Nullable final BlobId blobId)
  {
    return createInternal(headers, destination -> {
      try (InputStream data = blobData) {
        MetricsInputStream input = new MetricsInputStream(data);
        bucket.create(destination, input);
        return input.getMetrics();
      }
    }, blobId);
  }

  @Override
  @Guarded(by = STARTED)
  public Blob create(final Path path, final Map<String, String> map, final long size, final HashCode hash) {
    throw new BlobStoreException("hard links not supported", null);
  }

  @Override
  @Guarded(by = STARTED)
  public Blob copy(final BlobId blobId, final Map<String, String> headers) {
    GoogleCloudStorageBlob sourceBlob = (GoogleCloudStorageBlob) checkNotNull(get(blobId));

    return createInternal(headers, destination -> {
      sourceBlob.getBlob().copyTo(getConfiguredBucketName(), destination);

      BlobMetrics metrics = sourceBlob.getMetrics();
      return new StreamMetrics(metrics.getContentSize(), metrics.getSha1Hash());
    }, null);
  }

  @Nullable
  @Override
  @Guarded(by = STARTED)
  public Blob get(final BlobId blobId) {
    return get(blobId, false);
  }

  @Nullable
  @Override
  @Guarded(by = STARTED)
  @Timed
  public Blob get(final BlobId blobId, final boolean includeDeleted) {
    checkNotNull(blobId);

    final GoogleCloudStorageBlob blob = liveBlobs.getUnchecked(blobId);

    if (blob.isStale()) {
      Lock lock = blob.lock();
      try {
        if (blob.isStale()) {
          GoogleCloudBlobAttributes blobAttributes = new GoogleCloudBlobAttributes(bucket, attributePath(blobId));
          boolean loaded = blobAttributes.load();
          if (!loaded) {
            log.warn("Attempt to access non-existent blob {} ({})", blobId, blobAttributes);
            return null;
          }

          if (blobAttributes.isDeleted() && !includeDeleted) {
            log.warn("Attempt to access soft-deleted blob {} ({})", blobId, blobAttributes);
            return null;
          }

          blob.refresh(blobAttributes.getHeaders(), blobAttributes.getMetrics());
        }
      }
      catch (IOException e) {
        throw new BlobStoreException(e, blobId);
      }
      finally {
        lock.unlock();
      }
    }

    log.debug("Accessing blob {}", blobId);
    return blob;
  }

  @Override
  protected boolean doDelete(final BlobId blobId, final String reason) {
    final GoogleCloudStorageBlob blob = liveBlobs.getUnchecked(blobId);

    Lock lock = blob.lock();
    try {
      log.debug("Soft deleting blob {}", blobId);

      GoogleCloudBlobAttributes blobAttributes = new GoogleCloudBlobAttributes(bucket, attributePath(blobId));

      boolean loaded = blobAttributes.load();
      if (!loaded) {
        log.warn("Attempt to mark-for-delete non-existent blob {}", blobId);
        return false;
      }
      else if (blobAttributes.isDeleted()) {
        log.debug("Attempt to delete already-deleted blob {}", blobId);
        return false;
      }

      blobAttributes.setDeleted(true);
      blobAttributes.setDeletedReason(reason);
      blobAttributes.store();

      // add the blobId to the soft-deleted index
      deletedBlobIndex.add(blobId);
      blob.markStale();

      return true;
    }
    catch (Exception e) {
      throw new BlobStoreException(e, blobId);
    }
    finally {
      lock.unlock();
    }
  }

  @Override
  protected boolean doDeleteHard(final BlobId blobId) {
    try {
      log.debug("Hard deleting blob {}", blobId);

      boolean blobDeleted = storage.delete(getConfiguredBucketName(), contentPath(blobId));
      if (blobDeleted) {
        String attributePath = attributePath(blobId);
        BlobAttributes attributes = getBlobAttributes(blobId);
        metricsStore.recordDeletion(blobId, attributes.getMetrics().getContentSize());
        storage.delete(getConfiguredBucketName(), attributePath);
        deletedBlobIndex.remove(blobId);
      }

      return blobDeleted;
    }
    finally {
      liveBlobs.invalidate(blobId);
    }
  }

  @Override
  @Guarded(by = STARTED)
  public BlobStoreMetrics getMetrics() {
    return metricsStore.getMetrics();
  }

  @Override
  @Guarded(by = STARTED)
  public void compact() {
    compact(null);
  }

  @Override
  @Guarded(by = STARTED)
  public void doCompact(@Nullable final BlobStoreUsageChecker blobStoreUsageChecker) {
    log.info("Begin deleted blobs processing");
    ProgressLogIntervalHelper progressLogger = new ProgressLogIntervalHelper(log, 60);
    final AtomicInteger counter = new AtomicInteger(0);
    deletedBlobIndex.getContents().forEach(blobId -> {
      CancelableHelper.checkCancellation();

      deleteHard(blobId);
      counter.incrementAndGet();

      progressLogger.info("Elapsed time: {}, processed: {}", progressLogger.getElapsed(),
          counter.get());
    });
    progressLogger.flush();
  }

  @Override
  public BlobStoreConfiguration getBlobStoreConfiguration() {
    return this.blobStoreConfiguration;
  }

  @Override
  protected BlobAttributes getBlobAttributes(final GoogleAttributesLocation attributesFilePath) throws IOException {
    GoogleCloudBlobAttributes googleCloudBlobAttributes = new GoogleCloudBlobAttributes(bucket,
        attributesFilePath.getFullPath());
    googleCloudBlobAttributes.load();
    return googleCloudBlobAttributes;
  }

  @Override
  protected void doInit(final BlobStoreConfiguration configuration) {
    try {
      this.storage = storageFactory.create(blobStoreConfiguration);

      String location = configuration.attributes(CONFIG_KEY).get(LOCATION_KEY, String.class);
      this.bucket = getOrCreateStorageBucket(location);

      this.deletedBlobIndex = new DeletedBlobIndex(this.datastoreFactory, blobStoreConfiguration);
    }
    catch (Exception e) {
      throw new BlobStoreException("Unable to initialize blob store bucket: " + getConfiguredBucketName(), e, null);
    }
  }

  protected Bucket getOrCreateStorageBucket(final String location) {
    Bucket bucket = storage.get(getConfiguredBucketName());
    if (bucket == null) {
      bucket = storage.create(
          BucketInfo.newBuilder(getConfiguredBucketName())
              .setLocation(location)
              .setStorageClass(StorageClass.REGIONAL)
              .build());
    }

    return bucket;
  }

  @Override
  @Guarded(by = {NEW, STOPPED, FAILED})
  public void remove() {
    metricsStore.removeData();
    deletedBlobIndex.removeData();

    // TODO delete bucket only if it is empty
  }

  @Override
  @Guarded(by = STARTED)
  public Stream<BlobId> getBlobIdStream() {
    return getBlobIdStream(CONTENT_PREFIX);
  }

  @Override
  @Guarded(by = STARTED)
  public Stream<BlobId> getDirectPathBlobIdStream(final String prefix) {
    String subpath = format("%s/%s/%s", CONTENT_PREFIX, DIRECT_PATH_ROOT, prefix);
    return getBlobIdStream(subpath);
  }

  private Stream<BlobId> getBlobIdStream(final String subpath) {
    return blobStream(subpath)
        .filter(blob -> blob.getName().endsWith(BLOB_ATTRIBUTE_SUFFIX))
        .map(GoogleAttributesLocation::new)
        .map(this::getBlobIdFromAttributeFilePath)
        .map(BlobId::new);
  }

  Stream<BlobInfo> blobStream(final String path) {
    return stream(bucket.list(BlobListOption.prefix(path)).iterateAll()).map(c -> c);
  }

  /**
   * This method exists as a workaround to some unexpected behavior in
   * {@link BlobStoreSupport#getBlobIdFromAttributeFilePath(AttributesLocation)}.
   *
   * @param attributesLocation
   * @return the BlobId for the location as a String key
   */
  @Override
  protected String getBlobIdFromAttributeFilePath(final GoogleAttributesLocation attributesLocation) {
    if (attributesLocation.getFileName().startsWith(DefaultBlobIdLocationResolver.TEMPORARY_BLOB_ID_PREFIX)) {
      String name = attributesLocation.getFileName();
      return StringUtils.removeEnd(name.substring(name.lastIndexOf('/') + 1), BLOB_ATTRIBUTE_SUFFIX);
    }
    return super.getBlobIdFromAttributeFilePath(attributesLocation);
  }

  /**
   * @return the {@link BlobAttributes} for the blob, or null
   * @throws BlobStoreException if an {@link IOException} occurs
   */
  @Override
  @Guarded(by = STARTED)
  public BlobAttributes getBlobAttributes(final BlobId blobId) {
    try {
      GoogleCloudBlobAttributes blobAttributes = new GoogleCloudBlobAttributes(bucket, attributePath(blobId));
      return blobAttributes.load() ? blobAttributes : null;
    }
    catch (IOException e) {
      log.error("Unable to load GoogleCloudBlobAttributes for blob id: {}", blobId, e);
      throw new BlobStoreException(e, blobId);
    }
  }

  @Override
  @Guarded(by = STARTED)
  public void setBlobAttributes(final BlobId blobId, final BlobAttributes blobAttributes) {
    GoogleCloudBlobAttributes existing = (GoogleCloudBlobAttributes) getBlobAttributes(blobId);
    if (existing != null) {
      try {
        existing.updateFrom(blobAttributes);
        existing.store();
      }
      catch (IOException e) {
        log.error("Unable to set GoogleCloudBlobAttributes for blob id: {}", blobId, e);
      }
    }
  }

  /**
   * @return true if a blob exists in the store with the provided {@link BlobId}
   * @throws BlobStoreException if an IOException occurs
   */
  @Override
  @Guarded(by = STARTED)
  public boolean exists(final BlobId blobId) {
    checkNotNull(blobId);
    return getBlobAttributes(blobId) != null;
  }

  @Override
  public boolean isStorageAvailable() {
    return true;
  }

  @Override
  protected String attributePathString(final BlobId blobId) {
    return attributePath(blobId);
  }

  @Override
  @Guarded(by = STARTED)
  public boolean isWritable() {
    try {
      List<Boolean> results = storage.testIamPermissions(getConfiguredBucketName(),
          Arrays.asList("storage.objects.create", "storage.objects.delete"));
      return !results.contains(false);
    }
    catch (StorageException e) {
      throw new BlobStoreException("failed to retrive User ACL for " + getConfiguredBucketName(), e, null);
    }
  }

  Blob createInternal(final Map<String, String> headers,
                      final BlobIngester ingester,
                      @Nullable final BlobId assignedBlobId)
  {
    checkNotNull(headers);

    checkArgument(headers.containsKey(BLOB_NAME_HEADER), "Missing header: %s", BLOB_NAME_HEADER);
    checkArgument(headers.containsKey(CREATED_BY_HEADER), "Missing header: %s", CREATED_BY_HEADER);

    final BlobId blobId = getBlobId(headers, assignedBlobId);

    final String blobPath = contentPath(blobId);
    final String attributePath = attributePath(blobId);
    final GoogleCloudStorageBlob blob = liveBlobs.getUnchecked(blobId);
    Lock lock = blob.lock();
    try {
      log.debug("Writing blob {} to {}", blobId, blobPath);

      final StreamMetrics streamMetrics = ingester.ingestTo(blobPath);
      final BlobMetrics metrics = new BlobMetrics(new DateTime(), streamMetrics.getSha1(), streamMetrics.getSize());
      blob.refresh(headers, metrics);

      GoogleCloudBlobAttributes blobAttributes = new GoogleCloudBlobAttributes(bucket, attributePath, headers, metrics);

      blobAttributes.store();
      metricsStore.recordAddition(blobId, metrics.getContentSize());

      return blob;
    }
    catch (IOException e) {
      deleteNonExplosively(attributePath);
      deleteNonExplosively(blobPath);
      throw new BlobStoreException(e, blobId);
    }
    finally {
      lock.unlock();
    }
  }

  long getSoftDeletedBlobCount() {
    return this.deletedBlobIndex.getContents().count();
  }

  @VisibleForTesting
  DeletedBlobIndex getDeletedBlobIndex() {
    return this.deletedBlobIndex;
  }

  @VisibleForTesting
  void flushMetricsStore() {
    this.metricsStore.flush();
  }

  /**
   * Intended for use only within catch blocks that intend to throw their own {@link BlobStoreException}
   * for another good reason.
   *
   * @param contentPath the path within the configured bucket to delete
   */
  private void deleteNonExplosively(final String contentPath) {
    try {
      storage.delete(getConfiguredBucketName(), contentPath);
    }
    catch (Exception e) {
      log.warn("caught exception attempting to delete during cleanup", e);
    }
  }

  /**
   * Returns path for blob-id content file relative to root directory.
   */
  private String contentPath(final BlobId id) {
    return getLocation(id) + BLOB_CONTENT_SUFFIX;
  }

  /**
   * Returns path for blob-id attribute file relative to root directory.
   */
  private String attributePath(final BlobId id) {
    return getLocation(id) + BLOB_ATTRIBUTE_SUFFIX;
  }

  /**
   * Returns the location for a blob ID based on whether or not the blob ID is for a temporary or permanent blob.
   */
  private String getLocation(final BlobId id) {
    return CONTENT_PREFIX + "/" + blobIdLocationResolver.getLocation(id);
  }

  private String getConfiguredBucketName() {
    return blobStoreConfiguration.attributes(CONFIG_KEY).require(BUCKET_KEY).toString();
  }

  class GoogleCloudStorageBlob
      extends BlobSupport
  {
    GoogleCloudStorageBlob(BlobId blobId) {
      super(blobId);
    }

    @Override
    public InputStream doGetInputStream() {
      com.google.cloud.storage.Blob blob = getBlob();
      ReadChannel channel = blob.reader();
      return Channels.newInputStream(channel);
    }

    com.google.cloud.storage.Blob getBlob() {
      return bucket.get(contentPath(getId()), BlobGetOption.fields(BlobField.MEDIA_LINK));
    }
  }

  private interface BlobIngester
  {
    StreamMetrics ingestTo(final String destination) throws IOException;
  }
}
