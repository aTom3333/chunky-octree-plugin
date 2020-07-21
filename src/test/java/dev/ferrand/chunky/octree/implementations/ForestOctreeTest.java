package dev.ferrand.chunky.octree.implementations;

import org.junit.Test;

import java.io.*;

import static org.junit.Assert.*;

public class ForestOctreeTest {
    @Test
    public void treeWithOneBranch() throws IOException {
        InputStream input = ForestOctree.class.getClassLoader().getResourceAsStream("treeWithOneBranch.bin");
        InputStream expected = ForestOctree.class.getClassLoader().getResourceAsStream("treeWithOneBranch.bin");

        assertNotNull(input);
        assertNotNull(expected);

        ForestOctree octree = ForestOctree.load(new DataInputStream(input), 2);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        octree.store(new DataOutputStream(output));
        output.flush();

        byte[] data = output.toByteArray();
        int size = data.length;
        for(int i = 0; i < size; ++i) {
            byte expectedByte = (byte) expected.read();
            byte actualByte = data[i];
            assertEquals(expectedByte, actualByte);
        }
    }

    @Test
    public void loadStore() throws IOException {
        InputStream input = ForestOctree.class.getClassLoader().getResourceAsStream("onechunk.bin");
        InputStream expected = ForestOctree.class.getClassLoader().getResourceAsStream("onechunk.bin");

        assertNotNull(input);
        assertNotNull(expected);

        ForestOctree octree = ForestOctree.load(new DataInputStream(input));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        octree.store(new DataOutputStream(output));
        output.flush();

        byte[] data = output.toByteArray();
        int size = data.length;
        for(int i = 0; i < size; ++i) {
            byte expectedByte = (byte) expected.read();
            byte actualByte = data[i];
            assertEquals(expectedByte, actualByte);
        }
    }
}