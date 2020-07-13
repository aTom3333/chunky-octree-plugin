package dev.ferrand.chunky.octree;

public class BitWriter {
    private byte[] data;
    private int index;
    private int remainingBits;

    public BitWriter() {
        data = new byte[32]; // Should be enough (at least for now) I don't really want to make it dynamic
        index = 0;
        remainingBits = 8;
    }

    public void write(int bitCount, long bits) {
        bits &= ((1L << bitCount) - 1);
        if(bitCount < remainingBits) {
            data[index] |= (bits << (remainingBits - bitCount));
            remainingBits -= bitCount;
        } else {
            bitCount -= remainingBits;
            data[index] |= (bits >> bitCount);
            remainingBits = 8;
            ++index;

            while(bitCount >= 8) {
                bitCount -= 8;
                data[index] = (byte) ((bits >>> bitCount) & 0xFF);
                ++index;
            }

            data[index] |= (bits & ((1 << bitCount) - 1)) << (8 - bitCount);
            remainingBits -= bitCount;
        }
    }

    public byte[] getData() {
        return data;
    }

    public int getSize() {
        return remainingBits == 8 ? index : index+1;
    }
}
