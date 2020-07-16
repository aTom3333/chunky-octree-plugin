package dev.ferrand.chunky.octree.implementations;

import dev.ferrand.chunky.octree.implementations.CompressedSiblingsOctree;
import org.junit.Test;

import java.io.*;

import static org.junit.Assert.*;

public class CompressedSiblingsOctreeTest {

    private void testWithResources(String inputFile, String expectedFile) throws IOException {
        InputStream input = CompressedSiblingsOctree.class.getClassLoader().getResourceAsStream(inputFile);
        InputStream expected = CompressedSiblingsOctree.class.getClassLoader().getResourceAsStream(expectedFile);
        assertNotNull(input);
        assertNotNull(expected);

        CompressedSiblingsOctree octree = CompressedSiblingsOctree.load(new DataInputStream(input));
        long size = octree.treeData.getSize();
        for(long i = 0; i < size; ++i) {
            byte expectedByte = (byte) expected.read();
            byte actualByte = octree.treeData.get(i);
            assertEquals(expectedByte, actualByte);
        }
    }

    @Test
    public void simpleTree() throws IOException {
        testWithResources("simpleTree.bin", "simpleTreeRaw.bin");
    }

    @Test
    public void simpleTreeWithData() throws IOException {
        testWithResources("simpleTreeWithData.bin", "simpleTreeWithDataRaw.bin");
    }

    @Test
    public void loadStore() throws IOException {
        InputStream input = CompressedSiblingsOctree.class.getClassLoader().getResourceAsStream("onechunk.bin");
        InputStream expected = CompressedSiblingsOctree.class.getClassLoader().getResourceAsStream("onechunk.bin");

        assertNotNull(input);
        assertNotNull(expected);

        CompressedSiblingsOctree octree = CompressedSiblingsOctree.load(new DataInputStream(input));
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