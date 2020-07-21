package dev.ferrand.chunky.octree.utils;

import se.llbit.math.PackedOctree;

public class DynamicIntArrayWithFreeList {
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 16;
    private int[] data;
    private int size;
    private int freeHead;
    private final int groupSize;

    public DynamicIntArrayWithFreeList(int initialCapacity, int groupSize) {
        data = new int[initialCapacity];
        size = 0;
        freeHead = -1;
        this.groupSize = groupSize;
        assert groupSize > 0;
    }

    public void pushBack(int value) {
        if(size == data.length) {
            // We need to grow the array
            long newSize = (long) Math.ceil(data.length * 1.5);
            // We need to check the array won't be too big
            if(newSize > (long) MAX_ARRAY_SIZE) {
                // We can allocate less memory than initially wanted if the next block will still be able to fit
                // If not, this implementation isn't suitable
                if(MAX_ARRAY_SIZE - data.length > groupSize) {
                    // If by making the new array be of size MAX_ARRAY_SIZE we can still fit the block requested
                    newSize = MAX_ARRAY_SIZE;
                } else {
                    // array is too big
                    throw new RuntimeException("Array is too big");
                }
            }
            int[] newArray = new int[(int) newSize];
            System.arraycopy(data, 0, newArray, 0, size);
            data = newArray;
        }
        data[size] = value;
        ++size;
    }

    public int findSpace() {
        // Look in free list
        if(freeHead != -1) {
            int index = freeHead;
            freeHead = data[freeHead];
            return index;
        }

        // append in array if we have the capacity
        if(size + groupSize <= data.length) {
            int index = size;
            size += groupSize;
            return index;
        }

        // We need to grow the array
        long newSize = (long) Math.ceil(data.length * 1.5);
        // We need to check the array won't be too big
        if(newSize > (long) MAX_ARRAY_SIZE) {
            // We can allocate less memory than initially wanted if the next block will still be able to fit
            // If not, this implementation isn't suitable
            if(MAX_ARRAY_SIZE - data.length > groupSize) {
                // If by making the new array be of size MAX_ARRAY_SIZE we can still fit the block requested
                newSize = MAX_ARRAY_SIZE;
            } else {
                // array is too big
                throw new RuntimeException("Array is too big");
            }
        }
        int[] newArray = new int[(int) newSize];
        System.arraycopy(data, 0, newArray, 0, size);
        data = newArray;
        // and then append
        int index = size;
        size += groupSize;
        return index;
    }

    public int get(int index) {
        return data[index];
    }

    public void set(int index, int value) {
        data[index] = value;
    }

    public void freeSpace(int index) {
        data[index] = freeHead;
        freeHead = index;
    }
}
