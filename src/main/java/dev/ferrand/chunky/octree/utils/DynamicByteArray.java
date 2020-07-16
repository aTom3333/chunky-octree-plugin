package dev.ferrand.chunky.octree.utils;

import java.util.ArrayList;

public class DynamicByteArray {
    private final ArrayList<byte[]> data = new ArrayList<>();
    private long size;
    private long capacity;

    private static final int FULL_ARRAY_SHIFT = 22;
    private static final long MAX_ARRAY_SIZE = 1 << FULL_ARRAY_SHIFT;
    private static final long SUB_ARRAY_MASK = MAX_ARRAY_SIZE - 1;
    private static final long FULL_ARRAY_MASK = ~SUB_ARRAY_MASK;

    public DynamicByteArray(long initialCapacity) {
        this.capacity = initialCapacity;
        while(initialCapacity > MAX_ARRAY_SIZE) {
            data.add(new byte[(int) MAX_ARRAY_SIZE]);
            initialCapacity -= MAX_ARRAY_SIZE;
        }
        data.add(new byte[(int) initialCapacity]);
        size = 0;
    }

    public DynamicByteArray() {
        this(64);
    }

    private void grow(long minNewCapacity) {
        if(data.size() > 2) {
            // Add whole array
            data.add(new byte[(int) MAX_ARRAY_SIZE]);
            capacity += MAX_ARRAY_SIZE;
        } else if(data.size() == 2) {
            // Grow the second array or add a third one
            if(data.get(1).length == MAX_ARRAY_SIZE) {
                // Add array
                data.add(new byte[(int) MAX_ARRAY_SIZE]);
                capacity += MAX_ARRAY_SIZE;
            } else {
                // We have an array and a half, we'll add one half to make 2 whole arrays
                byte[] newArray = new byte[(int) MAX_ARRAY_SIZE];
                System.arraycopy(data.get(1), 0, newArray, 0, (int) (size & SUB_ARRAY_MASK));
                data.set(1, newArray);
                capacity = 2 * MAX_ARRAY_SIZE;
            }
        } else {
            // Grow the only array or add a second one
            if(capacity == MAX_ARRAY_SIZE) {
                data.add(new byte[(int) (MAX_ARRAY_SIZE/2)]);
                capacity += MAX_ARRAY_SIZE / 2;
            } else {
                long newCapacity = (long)Math.floor(capacity * 1.5); // compute new capacity
                newCapacity = Math.min(newCapacity, MAX_ARRAY_SIZE); // Don't grow over MAX_ARRAY_SIZE...
                newCapacity = Math.max(newCapacity, minNewCapacity); // Unless minNewCapacity is over MAX_ARRAY_SIZE
                long newArraySize = Math.min(newCapacity, MAX_ARRAY_SIZE);
                byte[] newArray = new byte[(int) newArraySize];
                System.arraycopy(data.get(0), 0, newArray, 0, (int) (size & SUB_ARRAY_MASK));
                data.set(0, newArray);
                capacity = newCapacity;
                if(newCapacity > newArraySize) {
                    data.add(new byte[(int) (MAX_ARRAY_SIZE/2)]);
                    capacity = MAX_ARRAY_SIZE * 3 / 2;
                }
            }
        }
    }

    public void ensureCapacity(long newCapacity) {
        while(newCapacity > capacity) {
            grow(newCapacity);
        }
    }

    public byte get(long index) {
        return data.get((int) (index >>> FULL_ARRAY_SHIFT))[(int) (index & SUB_ARRAY_MASK)];
    }

    public void set(long index, byte value) {
        data.get((int) (index >>> FULL_ARRAY_SHIFT))[(int) (index & SUB_ARRAY_MASK)] = value;
    }

    public void pushBack(byte value) {
        ensureCapacity(size+1);
        set(size, value);
        ++size;
    }

    public void writeElems(byte[] values, long fromArray, int fromValues, int count) {
        if(count == 0)
            return;
        long toArray = fromArray + count;
        // Use array copy to efficiently add several elements
        ensureCapacity(toArray);
        --toArray;
        long fullArrayIndex;
        while((fullArrayIndex = (fromArray >>> FULL_ARRAY_SHIFT)) != (toArray >>> FULL_ARRAY_SHIFT)) {
            // Copy left side when overlapping an edge
            long to = (fullArrayIndex + 1) * MAX_ARRAY_SIZE - 1;
            int subCount = (int) (to - fromArray + 1);
            System.arraycopy(values, fromValues, data.get((int) fullArrayIndex), (int) (fromArray & SUB_ARRAY_MASK), subCount);
            fromValues += subCount;
            fromArray += subCount;
        }

        // Copy the final part (and only when not overlapping an edge
        System.arraycopy(values, fromValues, data.get((int) fullArrayIndex), (int) (fromArray & SUB_ARRAY_MASK), (int) (toArray - fromArray + 1));

        size = Math.max(size, toArray+1);
    }

    public void addElems(byte[] values, int count) {
        writeElems(values, size, 0, count);
    }

    public byte[] subArray(long from, int count) {
        byte[] result = new byte[count];

        long toArray = from + count;
        toArray = Math.min(toArray, size);
        int fromResult = 0;
        --toArray;
        long fullArrayIndex;
        while((fullArrayIndex = (from >>> FULL_ARRAY_SHIFT)) != (toArray >>> FULL_ARRAY_SHIFT)) {
            // Copy left side when overlapping an edge
            long to = (fullArrayIndex + 1) * MAX_ARRAY_SIZE - 1;
            int subCount = (int) (to - from + 1);
            System.arraycopy(data.get((int) fullArrayIndex), (int) (from & SUB_ARRAY_MASK), result, fromResult, subCount);
            fromResult += subCount;
            from += subCount;
        }

        // Copy the final part (and only when not overlapping an edge
        System.arraycopy(data.get((int) fullArrayIndex), (int) (from & SUB_ARRAY_MASK), result, fromResult, (int) (toArray - from + 1));

        return result;
    }

    public long getSize() {
        return size;
    }
}
