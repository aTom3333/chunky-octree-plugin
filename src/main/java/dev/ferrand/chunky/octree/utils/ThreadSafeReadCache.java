package dev.ferrand.chunky.octree.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ThreadSafeReadCache implements FileCache {

    private static class Buffer {
        public long[] data = null;
        public BufferListNode node = null;
        public final ReadWriteLock lock = new ReentrantReadWriteLock();
    }

    private static class BufferListNode {
        public int index;
        public BufferListNode next;
        public BufferListNode prev;
    }

    private final ThreadLocal<RandomAccessFile> file;
    private final long bufferSize;
    private final int bufferShift;
    private final long mask;
    private final ArrayList<Buffer> buffers;
    private BufferListNode loadedBuffersFirst;
    private BufferListNode loadedBuffersLast;
    private int loadedBuffersCount;
    private final int bufferCount;

    private final Lock loadedBufferLock;

    private final ThreadLocal<byte[]> rawData;

    private int diskReads = 0;
    private int diskWrites = 0;

    public ThreadSafeReadCache(File fileToCache, int bufferShift, int bufferCount) throws IOException {
        file = ThreadLocal.withInitial(() -> {
            try {
                return new RandomAccessFile(fileToCache, "rw");
            } catch(FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        });
        this.bufferShift = bufferShift;
        bufferSize = (1L << bufferShift);
        mask = bufferSize-1;
        long fileSize = Files.size(fileToCache.toPath());
        long numBuffers = (fileSize + bufferSize - 1) / bufferSize;
        fileSize = numBuffers * bufferSize;
        file.get().setLength(fileSize);
        loadedBuffersFirst = null;
        loadedBuffersLast = null;
        loadedBuffersCount = 0;
        this.bufferCount = bufferCount;
        rawData = ThreadLocal.withInitial(() -> new byte[(int) (bufferSize * 8)]);
        loadedBufferLock = new ReentrantLock();
        buffers = new ArrayList<>((int)(numBuffers));
        for(int i = 0; i < numBuffers; ++i) {
            buffers.add(new Buffer());
        }
    }


    private long[] loadDataForBuffer(int bufferIndex) throws IOException {
        ++diskReads; // TODO atomic
        RandomAccessFile randomAccessFile = file.get();
        byte[] rawBuffer = rawData.get();
        randomAccessFile.seek(bufferIndex * bufferSize * 8);
        randomAccessFile.readFully(rawBuffer);
        long[] bufferData = new long[(int) bufferSize];
        for(int i = 0; i < bufferSize; ++i) {
            int rawIndex = 8*i;
            bufferData[i] =
                ((long)(rawBuffer[rawIndex] & 0xFF) << 56)
                | ((long)(rawBuffer[rawIndex+1] & 0xFF) << 48)
                | ((long)(rawBuffer[rawIndex+2] & 0xFF) << 40)
                | ((long)(rawBuffer[rawIndex+3] & 0xFF) << 32)
                | ((long)(rawBuffer[rawIndex+4] & 0xFF) << 24)
                | ((long)(rawBuffer[rawIndex+5] & 0xFF) << 16)
                | ((long)(rawBuffer[rawIndex+6] & 0xFF) << 8)
                | (long)(rawBuffer[rawIndex+7] & 0xFF);
        }
        ++loadedBuffersCount;

        return bufferData;
    }


    /**
     * Remove a buffer form the cache
     * Precondition:
     *  - loadedBufferLock is locked
     * @param bufferIndex
     * @throws IOException
     */
    private void removeBufferFromCache(int bufferIndex) throws IOException {
        Buffer buffer = buffers.get(bufferIndex);
        buffer.lock.writeLock().lock();
        try {
            buffer.node = null;
            buffer.data = null;
            --loadedBuffersCount;
        } finally {
            buffer.lock.writeLock().unlock();
        }
    }

    /**
     * Remove the buffer that wasn't used for the longer
     * precondition:
     *  - loadedBufferLock is locked
     */
    private void removeOldestBuffer() throws IOException {
        if(loadedBuffersCount >= bufferCount) {
            BufferListNode last = loadedBuffersLast;
            BufferListNode newLast = last.prev;
            newLast.next = null;
            loadedBuffersLast = newLast;
            removeBufferFromCache(last.index);
        }
    }

    /**
     * Track a buffer
     * Pre-condition:
     *  - loadedBufferLock is locked
     *  - the buffer with the given index is write locked
     * @param bufferIndex
     */
    private void trackBuffer(int bufferIndex) {
        BufferListNode node = new BufferListNode();
        buffers.get(bufferIndex).node = node;
        node.index = bufferIndex;
        if(loadedBuffersFirst == null) {
            loadedBuffersFirst = node;
            loadedBuffersLast = node;
        } else {
            node.next = loadedBuffersFirst;
            loadedBuffersFirst.prev = node;
            loadedBuffersFirst = node;
        }
    }

    private void notifyUsage(int bufferIndex) {
        // No op
    }

    @Override
    public long read(long position) throws IOException {
        int bufferIndex = (int) (position >>> bufferShift);
        int indexInBuffer = (int) (position & mask);
        Buffer buffer = buffers.get(bufferIndex);

        // Try to read
        buffer.lock.readLock().lock();
        try {
            if(buffer.data != null) {
                return buffer.data[indexInBuffer];
            }
        } finally {
            buffer.lock.readLock().unlock();
        }

        // Didn't succeed, need to load the buffer (and potentially trash another one)

        // Load the data without locking
        long[] data = loadDataForBuffer(bufferIndex);
        long result = data[indexInBuffer];
        // Lock everything needed to add the data to the cache
        loadedBufferLock.lock();
        try {
            if(loadedBuffersCount >= bufferCount) {
                // Need to trash a buffer
                removeOldestBuffer();
            }

            buffer.lock.writeLock().lock();
            try {
                buffer.data = data;
                trackBuffer(bufferIndex);
            } finally {
                buffer.lock.writeLock().unlock();
            }
        } finally {
            loadedBufferLock.unlock();
        }

        return result;
    }
}
