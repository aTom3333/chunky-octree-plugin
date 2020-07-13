package dev.ferrand.chunky.octree;

public class BitReader {
    private byte[] data;
    private int index;
    private int remainingBits;

    public BitReader(byte[] data, int index) {
        this.data = data;
        this.index = index;
        remainingBits = 8;
    }

    public long read(int bitCount) {
        if(bitCount < remainingBits) {
            long bits = ((data[index] & ((1 << remainingBits) - 1)) >>> (remainingBits - bitCount));
            remainingBits -= bitCount;
            return bits;
        } else {
            long bits = ((data[index] & 0xFF) & ((1 << remainingBits) - 1));
            bitCount -= remainingBits;
            remainingBits = 8;
            ++index;
            while(bitCount >= 8) {
                bits <<= 8;
                bits |= (data[index] & 0xFF);
                ++index;
                bitCount -= 8;
            }
            bits <<= bitCount;
            bits |= ((data[index] & 0xFF) >>> (8 - bitCount));
            remainingBits -= bitCount;

            return bits;
        }
    }
}
