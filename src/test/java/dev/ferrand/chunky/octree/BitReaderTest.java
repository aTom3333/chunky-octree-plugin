package dev.ferrand.chunky.octree;

import org.junit.Test;

import static org.junit.Assert.*;

public class BitReaderTest {

    @Test
    public void oneSmallRead() {
        BitReader reader = new BitReader(new byte[]{(byte) 0b101_00000}, 0);
        assertEquals(0b101, reader.read(3));
    }

    @Test
    public void severalSmallRead() {
        BitReader reader = new BitReader(new byte[]{(byte) 0b101_0010_1, (byte) 0b00_000000}, 0);
        assertEquals(0b101, reader.read(3));
        assertEquals(0b0010, reader.read(4));
        assertEquals(0b100, reader.read(3));
    }

    @Test
    public void oneBigRead() {
        BitReader reader = new BitReader(new byte[]{(byte) 0b10100011, (byte) 0b01100011, (byte) 0b10110101, (byte) 0b1_0000000}, 0);
        assertEquals(0b1010001101100011101101011, reader.read(25));
    }

    @Test
    public void severalRead() {
        BitReader reader = new BitReader(new byte[]{(byte) 0b01101000, (byte) 0b1011_1010, (byte) 0b00110110, (byte) 0b00111011, (byte) 0b01011_000}, 0);
        assertEquals(0b011010001011, reader.read(12));
        assertEquals(0b1010001101100011101101011, reader.read(25));
    }
}