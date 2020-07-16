package dev.ferrand.chunky.octree.utils;

import java.io.IOException;

/**
 * provide a cache over a given file
 */
public interface FileCache {
    default boolean isWritable() {
        return false;
    }

    long read(long position) throws IOException;
}
