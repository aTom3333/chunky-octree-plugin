package dev.ferrand.chunky.octree;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.*;

public class CompressedSiblingsOctreeTest {

    private void testWithResources(String inputFile, String expectedFile) throws IOException {
        InputStream input = CompressedSiblingsOctree.class.getClassLoader().getResourceAsStream(inputFile);
        InputStream expected = CompressedSiblingsOctree.class.getClassLoader().getResourceAsStream(expectedFile);
        assertNotNull(input);
        assertNotNull(expected);

        CompressedSiblingsOctree octree = CompressedSiblingsOctree.load(new DataInputStream(input));
        int size = octree.treeSize;
        for(int i = 0; i < size; ++i) {
            byte expectedByte = (byte) expected.read();
            byte actualByte = octree.treeData[i];
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
}