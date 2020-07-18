package dev.ferrand.chunky.octree.utils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class SingleThreadReadWriteCache implements WritableFileCache {

    private static class Buffer {
        public boolean dirty;
        public long[] data;
        public BufferListNode node;
    }

    private static class BufferListNode {
        public int index;
        public BufferListNode next;
        public BufferListNode prev;
    }

    private final RandomAccessFile file;
    private final long bufferSize;
    private final int bufferShift;
    private final long mask;
    private final ArrayList<Buffer> buffers;
    private long fileLength;
    private BufferListNode loadedBuffersFirst;
    private BufferListNode loadedBuffersLast;
    private int loadedBuffersCount;
    private final int bufferCount;

    private final byte[] rawData;

    private int diskReads = 0;
    private int diskWrites = 0;

    public SingleThreadReadWriteCache(File fileToCache, int bufferShift, int bufferCount) throws IOException {
        file = new RandomAccessFile(fileToCache, "rw");
        this.bufferShift = bufferShift;
        bufferSize = (1L << bufferShift);
        mask = bufferSize-1;
        buffers = new ArrayList<>();
        fileLength = 0;
        expandFileIfNeeded(Files.size(fileToCache.toPath()));
        loadedBuffersFirst = null;
        loadedBuffersLast = null;
        loadedBuffersCount = 0;
        this.bufferCount = bufferCount;
        rawData = new byte[(int) (bufferSize * 8)];
    }

    private void expandFileIfNeeded(long size) throws IOException {
        if(fileLength < size*8) {
            file.setLength(size*8);
            fileLength = size*8;
            int numBuffer = (int) (size >>> bufferShift);
            while(buffers.size() < numBuffer)
                buffers.add(null);
        }
    }

    /**
     * Read the file to load a buffer
     * Pre-condition:
     *  - bufferIndex < buffers.size()
     *  - the buffer is not already loaded
     * @param bufferIndex
     * @throws IOException
     */
    private void loadBuffer(int bufferIndex) throws IOException {
        ++diskReads;
        Buffer newBuffer = new Buffer();
        newBuffer.dirty = false;
        expandFileIfNeeded(bufferSize * (bufferIndex+1));
        file.seek(bufferIndex * bufferSize * 8);
        file.readFully(rawData);
        long[] bufferData = new long[(int) bufferSize];
        for(int i = 0; i < bufferSize; ++i) {
            int rawIndex = 8*i;
            bufferData[i] =
                ((long)(rawData[rawIndex] & 0xFF) << 56)
                | ((long)(rawData[rawIndex+1] & 0xFF) << 48)
                | ((long)(rawData[rawIndex+2] & 0xFF) << 40)
                | ((long)(rawData[rawIndex+3] & 0xFF) << 32)
                | ((long)(rawData[rawIndex+4] & 0xFF) << 24)
                | ((long)(rawData[rawIndex+5] & 0xFF) << 16)
                | ((long)(rawData[rawIndex+6] & 0xFF) << 8)
                | (long)(rawData[rawIndex+7] & 0xFF);
        }
        newBuffer.data = bufferData;

        buffers.set(bufferIndex, newBuffer);
        ++loadedBuffersCount;
    }

    /**
     * Writes the content of a buffer to the file
     * Pre-condition:
     *  - bufferIndex < buffers.size()
     *  - buffers.get(bufferIndex) != null
     *  - buffers.get(bufferIndex).dirty == true
     * @param bufferIndex
     * @throws IOException
     */
    private void saveBuffer(int bufferIndex) throws IOException {
        ++diskWrites;
        long[] data = buffers.get(bufferIndex).data;
        for(int i = 0; i < bufferSize; ++i) {
            int rawIndex = 8*i;
            rawData[rawIndex] = (byte) ((data[i] >>> 56) & 0xFF);
            rawData[rawIndex+1] = (byte) ((data[i] >>> 48) & 0xFF);
            rawData[rawIndex+2] = (byte) ((data[i] >>> 40) & 0xFF);
            rawData[rawIndex+3] = (byte) ((data[i] >>> 32) & 0xFF);
            rawData[rawIndex+4] = (byte) ((data[i] >>> 24) & 0xFF);
            rawData[rawIndex+5] = (byte) ((data[i] >>> 16) & 0xFF);
            rawData[rawIndex+6] = (byte) ((data[i] >>> 8) & 0xFF);
            rawData[rawIndex+7] = (byte) (data[i] & 0xFF);
        }

        file.seek(bufferIndex * bufferSize * 8);
        file.write(rawData);
    }

    /**
     * Remove a buffer from the cache, saves it if it is dirty
     * Pre-condition:
     *  - bufferIndex < buffers.size()
     *  - buffers.get(bufferIndex) != null
     * @param bufferIndex
     */
    private void removeBufferFromCache(int bufferIndex) throws IOException {
        if(buffers.get(bufferIndex).dirty) {
            saveBuffer(bufferIndex);
        }
        buffers.set(bufferIndex, null);
        --loadedBuffersCount;
    }

    /**
     * Remove the buffer that wasn't used for the longer
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
        BufferListNode node = buffers.get(bufferIndex).node;
        if(node.prev == null) {
            return;
        }
        node.prev.next = node.next;
        if(node.next == null) {
            loadedBuffersLast = node.prev;
        } else {
            node.next.prev = node.prev;
        }
        node.prev = null;
        node.next = loadedBuffersFirst;
        loadedBuffersFirst.prev = node;
        loadedBuffersFirst = node;
    }

    private void addBufferToCache(int bufferIndex) throws IOException {
        expandFileIfNeeded((bufferIndex+1) * bufferSize);
        if(buffers.get(bufferIndex) != null) {
            notifyUsage(bufferIndex);
            return;
        }

        removeOldestBuffer();
        loadBuffer(bufferIndex);
        trackBuffer(bufferIndex);
    }

    @Override
    public void write(long position, long value) throws IOException {
        int bufferIndex = (int) (position >>> bufferShift);
        int indexInBuffer = (int) (position & mask);
        addBufferToCache(bufferIndex);
        buffers.get(bufferIndex).data[indexInBuffer] = value;
        buffers.get(bufferIndex).dirty = true;
    }

    @Override
    public long read(long position) throws IOException {
        int bufferIndex = (int) (position >>> bufferShift);
        int indexInBuffer = (int) (position & mask);
        addBufferToCache(bufferIndex);
        return buffers.get(bufferIndex).data[indexInBuffer];
    }
}
