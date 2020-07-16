package dev.ferrand.chunky.octree.utils;

import dev.ferrand.chunky.octree.utils.BitWriter;
import org.junit.Test;

import static org.junit.Assert.*;

public class BitWriterTest {

    public static byte[] subarray(byte[] input, int size) {
        byte[] result = new byte[size];
        System.arraycopy(input, 0, result, 0, size);
        return result;
    }

    @Test
    public void oneSmallWrite() {
        BitWriter writer = new BitWriter();
        writer.write(3, 0b101);

        assertEquals(1, writer.getSize());
        assertArrayEquals(new byte[]{(byte) 0b101_00000}, subarray(writer.getData(), 1));
    }

    @Test
    public void severalSmallWrite() {
        BitWriter writer = new BitWriter();
        writer.write(3, 0b101);
        writer.write(4, 0b0010);
        writer.write(3, 0b100);

        assertEquals(2, writer.getSize());
        assertArrayEquals(new byte[]{(byte) 0b101_0010_1, (byte) 0b00_000000}, subarray(writer.getData(), 2));
    }

    @Test
    public void oneBigWrite() {
        BitWriter writer = new BitWriter();
        writer.write(25, 0b1010001101100011101101011);

        assertEquals(4, writer.getSize());
        assertArrayEquals(new byte[]{(byte) 0b10100011, (byte) 0b01100011, (byte) 0b10110101, (byte) 0b1_0000000}, subarray(writer.getData(), 4));
    }

    @Test
    public void severalWrite() {
        BitWriter writer = new BitWriter();
        writer.write(12, 0b011010001011);
        writer.write(25, 0b1010001101100011101101011);

        assertEquals(5, writer.getSize());
        assertArrayEquals(new byte[]{(byte) 0b01101000, (byte) 0b1011_1010, (byte) 0b00110110, (byte) 0b00111011, (byte) 0b01011_000}, subarray(writer.getData(), 5));
    }
}