package dev.ferrand.chunky.octree.utils;

import java.io.IOException;

public class SynchronizedWritableCache implements WritableFileCache {
    private final WritableFileCache wrapped;

    public SynchronizedWritableCache(WritableFileCache wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public void write(long position, long value) throws IOException {
        synchronized(wrapped) {
            wrapped.write(position, value);
        }
    }

    @Override
    public long read(long position) throws IOException {
        synchronized(wrapped) {
            return wrapped.read(position);
        }
    }
}
