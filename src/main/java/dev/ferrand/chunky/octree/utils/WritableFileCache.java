package dev.ferrand.chunky.octree.utils;

import java.io.IOException;

public interface WritableFileCache extends FileCache {
    @Override
    default boolean isWritable() {
        return true;
    }

    void write(long position, long value) throws IOException;
}
