package dev.ferrand.chunky.octree.utils;

import dev.ferrand.chunky.octree.utils.DynamicByteArray;
import org.junit.Test;

import static org.junit.Assert.*;

public class DynamicByteArrayTest {

    @Test
    public void basicWriteRead() {
        DynamicByteArray array = new DynamicByteArray();
        for(int i = 0; i < 50; ++i) {
            array.pushBack((byte) i);
        }

        assertEquals(50, array.getSize());

        for(int i = 0; i < 50; ++i) {
            assertEquals(i, array.get(i));
        }
    }

    @Test
    public void bigWriteRead() {
        DynamicByteArray array = new DynamicByteArray();
        long size = (1 << 24);
        for(long i = 0; i < size; ++i) {
            array.pushBack((byte) (i & 0x7F));
        }

        assertEquals(size, array.getSize());

        for(long i = 0; i < size; ++i) {
            assertEquals((byte) (i & 0x7F), array.get(i));
        }
    }

    @Test
    public void bigWriteElems() {
        DynamicByteArray array = new DynamicByteArray();
        long size = (1 << 24);
        long offset = 50;

        byte[] value = new byte[(int) size];
        for(long i = 0; i < size; ++i) {
            value[(int) i] = (byte) (i & 0x7F);
        }

        array.writeElems(value, offset, 0, (int) size);

        assertEquals(offset + size, array.getSize());

        for(long i = 0; i < size; ++i) {
            assertEquals((byte) (i & 0x7F), array.get(offset + i));
        }
    }

    @Test
    public void bigReadElems() {
        DynamicByteArray array = new DynamicByteArray();
        long size = (1 << 24);
        long offset = 50;

        for(long i = 0; i < offset+size; ++i) {
            array.pushBack((byte)(i & 0x7F));
        }

        byte[] result = array.subArray(offset, (int) size);

        for(long i = 0; i < size; ++i) {
            assertEquals((byte) ((offset+i) & 0x7F), result[(int) i]);
        }
    }

}