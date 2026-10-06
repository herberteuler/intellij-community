// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.indexing.impl.storage;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IntRef;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.SystemProperties;
import com.intellij.util.ThrowableRunnable;
import com.intellij.util.containers.ConcurrentIntObjectMap;
import com.intellij.util.indexing.IdFilter;
import com.intellij.util.indexing.StorageException;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.DataInputOutputUtil;
import com.intellij.util.io.DataOutputStream;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.IOUtil;
import com.intellij.util.io.KeyDescriptor;
import com.intellij.util.io.StorageLockContext;
import com.intellij.util.io.keyStorage.AppendableObjectStorage;
import com.intellij.util.io.keyStorage.AppendableStorageBackedByResizableMappedFile;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static com.intellij.concurrency.ConcurrentCollectionFactory.createConcurrentIntObjectMap;

/**
 * A data structure to store key hashes to virtual file id mappings.
 */
@ApiStatus.Internal
public final class KeyHashLog<Key> implements Closeable {
  private static final Logger LOG = Logger.getInstance(KeyHashLog.class);
  private static final boolean ENABLE_CACHED_HASH_IDS = SystemProperties.getBooleanProperty("idea.index.cashed.hashids", true);

  private final @NotNull KeyDescriptor<Key> myKeyDescriptor;
  private final @NotNull Path myBaseStorageFile;
  private final @Nullable StorageLockContext myStorageLockContext;
  private final @NotNull AppendableObjectStorage<int[]> myKeyHashToVirtualFileMapping;

  private final @NotNull ConcurrentIntObjectMap<Boolean> myInvalidatedSessionIds = createConcurrentIntObjectMap();
  /// `myKeyHashToVirtualFileMapping.getCurrentLength()` at the last moment the file was scanned;
  private volatile int myScannedUpToOffsetExclusive;

  public KeyHashLog(@NotNull KeyDescriptor<Key> descriptor, @NotNull Path baseStorageFile) throws IOException {
    this(descriptor, baseStorageFile, null);
  }

  /** Shares the index storage lock context with key-hash mapping files opened next to the main index storage. */
  public KeyHashLog(@NotNull KeyDescriptor<Key> descriptor,
                    @NotNull Path baseStorageFile,
                    @Nullable StorageLockContext storageLockContext) throws IOException {
    this(descriptor, baseStorageFile, storageLockContext, /*compact: */true);
  }

  private KeyHashLog(@NotNull KeyDescriptor<Key> descriptor,
                     @NotNull Path baseStorageFile,
                     @Nullable StorageLockContext storageLockContext,
                     boolean compact) throws IOException {
    myKeyDescriptor = descriptor;
    myBaseStorageFile = baseStorageFile;
    myStorageLockContext = storageLockContext;
    if (compact && isRequiresCompaction()) {
      performCompaction();
    }
    myKeyHashToVirtualFileMapping = openMapping(getDataFile(), /*size: */ 4096, myStorageLockContext);
  }

  private static @NotNull AppendableStorageBackedByResizableMappedFile<int[]> openMapping(@NotNull Path dataFile,
                                                                                          int size,
                                                                                          @Nullable StorageLockContext storageLockContext)
    throws IOException {
    return new AppendableStorageBackedByResizableMappedFile<>(dataFile,
                                                              size,
                                                              storageLockContext,
                                                              /*pageSize: */ IOUtil.MiB,
                                                              /*valuesAreAligned: */ true,
                                                              IntPairAsArrayExternalizer.INSTANCE);
  }

  public void addKeyHashToVirtualFileMapping(Key key, int inputId) throws StorageException {
    appendKeyHashToVirtualFileMappingToLog(key, inputId);
  }

  public void removeKeyHashToVirtualFileMapping(Key key, int inputId) throws StorageException {
    appendKeyHashToVirtualFileMappingToLog(key, -inputId);
  }

  public @NotNull IntSet getSuitableKeyHashes(@NotNull IdFilter filter, @NotNull Project project) throws StorageException {
    IdFilter.FilterScopeType filteringScopeType = filter.getFilteringScopeType();

    long l = System.currentTimeMillis();

    @NotNull Path sessionProjectCacheFile = getSavedProjectFileValueIds(
      myScannedUpToOffsetExclusive,
      filteringScopeType == IdFilter.FilterScopeType.OTHER ? IdFilter.FilterScopeType.PROJECT_AND_LIBRARIES : filteringScopeType,
      project
    );
    int currentFileLength = myKeyHashToVirtualFileMapping.getCurrentLength();

    boolean shouldCacheResult = (filteringScopeType == IdFilter.FilterScopeType.PROJECT_AND_LIBRARIES);
    IntSet hashMaskSet = null;
    if (ENABLE_CACHED_HASH_IDS
        && currentFileLength == myScannedUpToOffsetExclusive
        && shouldCacheResult) {
      if (myInvalidatedSessionIds.remove(currentFileLength) == null) {
        try {
          hashMaskSet = loadProjectHashes(sessionProjectCacheFile);
        }
        catch (IOException ignored) {
        }
      }
    }

    if (hashMaskSet == null) {
      if (ENABLE_CACHED_HASH_IDS && myScannedUpToOffsetExclusive != 0) {
        try {
          FileUtil.delete(sessionProjectCacheFile);
        }
        catch (NoSuchFileException ignored) { }
        catch (IOException e) {
          LOG.error(e);
        }
      }

      hashMaskSet = getSuitableKeyHashes(filter);

      if (ENABLE_CACHED_HASH_IDS && shouldCacheResult) {
        saveHashedIds(hashMaskSet, currentFileLength, sessionProjectCacheFile);
      }
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("Scanned keyHashToVirtualFileMapping of " + myBaseStorageFile + " for " + (System.currentTimeMillis() - l));
    }

    return hashMaskSet;
  }

  private void appendKeyHashToVirtualFileMappingToLog(Key key, int inputId) throws StorageException {
    if (inputId == 0) return;
    try {
      withLock(
        () -> myKeyHashToVirtualFileMapping.append(new int[]{myKeyDescriptor.getHashCode(key), inputId}),
        /* read: */ false
      );
    }
    catch (IOException e) {
      throw new StorageException(e);
    }
    invalidateKeyHashToVirtualFileMappingCache();
  }

  public @NotNull IntSet getSuitableKeyHashes(@NotNull IdFilter idFilter) throws StorageException {
    ProgressManager.checkCanceled();
    try {
      Int2ObjectMap<IntSet> hash2inputIds = new Int2ObjectOpenHashMap<>(1000);
      IntRef uselessRecords = new IntRef(0);

      myKeyHashToVirtualFileMapping.processAll((offset, key) -> {
        int keyHash = key[0];
        int inputId = key[1];

        //Throttle the check (probability of any 5-bit pattern in a good hash is ~1/32)
        if ((keyHash & 0b11111) == 0) ProgressManager.checkCanceled();

        int absInputId = Math.abs(inputId);
        if (!idFilter.containsFileId(absInputId)) return true;


        if (inputId > 0) {
          if (!hash2inputIds.computeIfAbsent(keyHash, __ -> new IntOpenHashSet(4)).add(inputId)) {
            uselessRecords.inc();
          }
        }
        else {
          IntSet inputIds = hash2inputIds.get(keyHash);
          if (inputIds != null) {
            inputIds.remove(absInputId);
            if (inputIds.isEmpty()) {
              hash2inputIds.remove(keyHash);
            }
          }
          uselessRecords.inc();
        }
        return true;
      });

      if (uselessRecords.get() >= hash2inputIds.size()) {
        setRequiresCompaction();
      }

      return hash2inputIds.keySet();
    }
    catch (IOException e) {
      throw new StorageException(e);
    }
  }

  void force() throws IOException {
    if (myKeyHashToVirtualFileMapping.isDirty()) {
      doForce();
    }
  }

  private void doForce() throws IOException {
    withLock(() -> myKeyHashToVirtualFileMapping.force(), /*read: */ false);
  }

  @Override
  public void close() throws IOException {
    withLock(() -> {
      myKeyHashToVirtualFileMapping.close();
    }, false);
  }

  private void performCompaction() throws IOException {
    Int2ObjectMap<IntSet> data = new Int2ObjectOpenHashMap<>();
    Path oldDataFile = getDataFile();

    AppendableStorageBackedByResizableMappedFile<int[]> oldMapping = openMapping(oldDataFile, 0, myStorageLockContext);
    try {
      oldMapping.processAll((offset, key) -> {
        int inputId = key[1];
        int keyHash = key[0];
        int absInputId = Math.abs(inputId);

        if (inputId > 0) {
          data.computeIfAbsent(keyHash, __ -> new IntOpenHashSet()).add(absInputId);
        }
        else {
          IntSet associatedInputIds = data.get(keyHash);
          if (associatedInputIds != null) {
            associatedInputIds.remove(absInputId);
          }
        }
        return true;
      });
    }
    finally {
      oldMapping.lockRead();
      try {
        oldMapping.close();
      }
      finally {
        oldMapping.unlockRead();
      }
    }

    String dataFileName = oldDataFile.getFileName().toString();
    String newDataFileName = "new." + dataFileName;
    Path newDataFile = oldDataFile.resolveSibling(newDataFileName);
    AppendableStorageBackedByResizableMappedFile<int[]> newMapping = openMapping(newDataFile, 32 * 2 * data.size(), myStorageLockContext);

    newMapping.lockWrite();
    try {
      try {
        for (Int2ObjectMap.Entry<IntSet> entry : data.int2ObjectEntrySet()) {
          int keyHash = entry.getIntKey();
          IntIterator inputIdIterator = entry.getValue().iterator();
          while (inputIdIterator.hasNext()) {
            int inputId = inputIdIterator.nextInt();
            newMapping.append(new int[]{keyHash, inputId});
          }
        }
      }
      finally {
        newMapping.close();
      }
    }
    finally {
      newMapping.unlockWrite();
    }

    IOUtil.deleteAllFilesStartingWith(oldDataFile);

    try (DirectoryStream<Path> paths = Files.newDirectoryStream(newDataFile.getParent())) {
      for (Path path : paths) {
        String name = path.getFileName().toString();
        if (name.startsWith(newDataFileName)) {
          FileUtil.rename(path.toFile(), dataFileName + name.substring(newDataFileName.length()));
        }
      }
    }

    try {
      Files.delete(getCompactionMarker());
    }
    catch (IOException ignored) {
    }
  }


  private static @NotNull IntSet loadProjectHashes(@NotNull Path fileWithCaches) throws IOException {
    try (DataInputStream inputStream = new DataInputStream(new BufferedInputStream(Files.newInputStream(fileWithCaches)))) {
      int hashesCount = DataInputOutputUtil.readINT(inputStream);
      IntSet hashMaskSet = new IntOpenHashSet(hashesCount);
      while (hashesCount > 0) {
        hashMaskSet.add(DataInputOutputUtil.readINT(inputStream));
        --hashesCount;
      }
      return hashMaskSet;
    }
  }

  private void saveHashedIds(@NotNull IntSet hashMaskSet,
                             int largestId,
                             @NotNull Path fileToStoreCache) {
    boolean savedSuccessfully = true;
    try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(fileToStoreCache)))) {
      DataInputOutputUtil.writeINT(stream, hashMaskSet.size());
      IntIterator iterator = hashMaskSet.iterator();
      while (iterator.hasNext()) {
        DataInputOutputUtil.writeINT(stream, iterator.nextInt());
      }
    }
    catch (IOException ignored) {
      savedSuccessfully = false;
    }
    if (savedSuccessfully) {
      myScannedUpToOffsetExclusive = largestId;
    }
  }

  private static volatile Path mySessionDirectory;
  private static final Object mySessionDirectoryLock = new Object();

  private static Path getSessionDir() {
    Path sessionDirectory = mySessionDirectory;
    if (sessionDirectory == null) {
      synchronized (mySessionDirectoryLock) {
        sessionDirectory = mySessionDirectory;
        if (sessionDirectory == null) {
          try {
            mySessionDirectory = sessionDirectory = FileUtil
              .createTempDirectory(new File(PathManager.getTempPath()), Long.toString(System.currentTimeMillis()), "", true).toPath();
          }
          catch (IOException ex) {
            throw new RuntimeException("Can not create temp directory", ex);
          }
        }
      }
    }
    return sessionDirectory;
  }

  private @NotNull Path getSavedProjectFileValueIds(int id, @NotNull IdFilter.FilterScopeType scopeType, @NotNull Project project) {
    return getSessionDir().resolve(getDataFile().getFileName().toString() + "." + project.hashCode() + "." + id + "." + scopeType.getId());
  }

  private void invalidateKeyHashToVirtualFileMappingCache() {
    int lastScannedId = myScannedUpToOffsetExclusive;
    if (lastScannedId != 0) { // we have write lock
      myInvalidatedSessionIds.putIfAbsent(lastScannedId, Boolean.TRUE);
      myScannedUpToOffsetExclusive = 0;
    }
  }

  private <T extends Throwable> void withLock(ThrowableRunnable<T> r, boolean read) throws T {
    if (read) {
      myKeyHashToVirtualFileMapping.lockRead();
    }
    else {
      myKeyHashToVirtualFileMapping.lockWrite();
    }
    try {
      r.run();
    }
    finally {
      if (read) {
        myKeyHashToVirtualFileMapping.unlockRead();
      }
      else {
        myKeyHashToVirtualFileMapping.unlockWrite();
      }
    }
  }

  private void setRequiresCompaction() {
    Path marker = getCompactionMarker();
    if (Files.exists(marker)) {
      return;
    }
    try {
      Files.createDirectories(marker.getParent());
      Files.createFile(marker);
    }
    catch (FileAlreadyExistsException ignored) {
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  @VisibleForTesting
  public boolean isRequiresCompaction() {
    return Files.exists(getCompactionMarker());
  }

  private @NotNull Path getCompactionMarker() {
    Path dataFile = getDataFile();
    return dataFile.resolveSibling(dataFile.getFileName().toString() + ".require.compaction");
  }

  private @NotNull Path getDataFile() {
    return myBaseStorageFile.resolveSibling(myBaseStorageFile.getFileName() + ".project");
  }

  private static final class IntPairAsArrayExternalizer implements DataExternalizer<int[]> {
    private static final IntPairAsArrayExternalizer INSTANCE = new IntPairAsArrayExternalizer();

    @Override
    public void save(@NotNull DataOutput out, int[] value) throws IOException {
      DataInputOutputUtil.writeINT(out, value[0]);
      DataInputOutputUtil.writeINT(out, value[1]);
    }

    /// This externalizer is used _only_ privately in this class => we can be sure returned array doesn't leak
    /// from this class, and thread-local caching is ok:
    private static final ThreadLocal<int[]> PAIR = ThreadLocal.withInitial(() -> new int[2]);

    @Override
    public int[] read(@NotNull DataInput in) throws IOException {
      int[] result = PAIR.get();
      result[0] = DataInputOutputUtil.readINT(in);
      result[1] = DataInputOutputUtil.readINT(in);
      return result;
    }
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  public static void main(String[] args) throws Exception {
    String indexPath = args[0];
    EnumeratorStringDescriptor enumeratorStringDescriptor = EnumeratorStringDescriptor.INSTANCE;

    try (KeyHashLog<String> keyHashLog = new KeyHashLog<>(enumeratorStringDescriptor, Path.of(indexPath), null, false)) {
      IntSet allHashes = keyHashLog.getSuitableKeyHashes(IdFilter.ACCEPT_ALL);

      for (Integer hash : allHashes) {
        System.out.println("key hash = " + hash);
      }
    }
  }
}
