package dev.ferrand.chunky.octree.implementations;

import dev.ferrand.chunky.octree.utils.*;
import se.llbit.chunky.PersistentSettings;
import se.llbit.math.Octree;

import java.io.*;

import static se.llbit.math.Octree.*;
import static se.llbit.math.Octree.WHATEVER_TYPE;

public class DiskOctree extends AbstractOctreeImplementation {
    /**
     * A reimplementation of the big packed octree but stored on disk
     * (caching will be implemented later)
     */
    private final File treeFile;
    private long size;
    private long freeHead;
    private final int depth;
    private FileCache treeData;

    private final int cacheSize = PersistentSettings.settings.getInt("disk.cacheSize", 11);
    private final int cacheNumber = PersistentSettings.settings.getInt("disk.cacheNumber", 2048);

    private static final class NodeId implements Octree.NodeId {
        public long nodeIndex;

        public NodeId(long nodeIndex) {
            this.nodeIndex = nodeIndex;
        }
    }

    @Override
    public Octree.NodeId getRoot() {
        return new NodeId(0);
    }

    @Override
    public boolean isBranch(Octree.NodeId node) {
        return getAt(((NodeId)node).nodeIndex) > 0;
    }

    @Override
    public Octree.NodeId getChild(Octree.NodeId parent, int childNo) {
        return new NodeId(getAt(((NodeId)parent).nodeIndex) + childNo);
    }

    @Override
    public int getType(Octree.NodeId node) {
        return typeFromValue(getAt(((NodeId)node).nodeIndex));
    }

    @Override
    public int getData(Octree.NodeId node) {
        return dataFromValue(getAt(((NodeId)node).nodeIndex));
    }

    public DiskOctree(int depth) throws IOException {
        this.depth = depth;
        treeFile = File.createTempFile("disk-octree", ".bin");
        treeData = new SingleThreadReadWriteCache(treeFile, cacheSize, cacheNumber);
        setAt(0, 0);
        size = 1;
        freeHead = -1;
    }

    private long getAt(long index) {
        try {
            return treeData.read(index);
        } catch(IOException e) {
            throw new RuntimeException("Error while reading file of DiskOctree", e);
        }
    }

    private void setAt(long index, long value) {
        try {
            if(!treeData.isWritable())
                throw new RuntimeException("Error while writing to the octree file (the file cache is not writable)");
            ((WritableFileCache)treeData).write(index, value);
        } catch(IOException e) {
            throw new RuntimeException("Error while writing file of DiskOctree", e);
        }
    }

    private static int typeFromValue(long value) {
        return -(int) ((value & 0xFFFFFFFF00000000L) >> 32);
    }

    private static int dataFromValue(long value) {
        return (int) (value & 0xFFFFFFFFL);
    }

    private static long valueFromTypeData(int type, int data) {
        return (long)(-type) << 32 | data;
    }

    private long findSpace() {
        // Look in free list
        if(freeHead != -1) {
            long index = freeHead;
            freeHead = getAt(freeHead);
            return index;
        }

        long index = size;
        size += 8;
        return index;
    }

    private void freeSpace(long index) {
        setAt(index, freeHead);
        freeHead = index;
    }

    private void subdivideNode(long nodeIndex) {
        long childrenIndex = findSpace();
        for(int i = 0; i < 8; ++i) {
            setAt(childrenIndex + i, getAt(nodeIndex));
        }
        setAt(nodeIndex, childrenIndex); // Make the node a parent node pointing to its children
    }

    private void mergeNode(long nodeIndex, long value) {
        long childrenIndex = getAt(nodeIndex);
        freeSpace(childrenIndex); // Delete children
        setAt(nodeIndex, value);
    }

    private boolean nodeEquals(long firstNodeIndex, long secondNodeIndex) {
        long value1 = getAt(firstNodeIndex);
        long value2 = getAt(secondNodeIndex);
        return value1 == value2;
    }

    private boolean nodeEquals(long firstNodeIndex, Octree.Node secondNode) {
        long value1 = getAt(firstNodeIndex);
        boolean firstIsBranch = value1 > 0;
        boolean secondIsBranch = (secondNode.type == BRANCH_NODE);
        if(firstIsBranch && secondIsBranch)
            return false;
        else if(!firstIsBranch && !secondIsBranch)
            return typeFromValue(value1) == secondNode.type // compare types
                    && dataFromValue(value1) == secondNode.getData(); // compare data
        return false;
    }

    @Override
    public void set(int type, int x, int y, int z) {
        set(new Octree.Node(type), x, y, z);
    }

    @Override
    public void set(Octree.Node data, int x, int y, int z) {
        long[] parents = new long[depth]; // better to put as a field to preventallocation at each invocation?
        long nodeIndex = 0;
        int parentLevel = depth - 1;
        int position = 0;
        for (int i = depth - 1; i >= 0; --i) {
            parents[i] = nodeIndex;

            if (nodeEquals(nodeIndex, data)) {
                return;
            } else if (getAt(nodeIndex) <= 0) { // It's a leaf node
                subdivideNode(nodeIndex);
                parentLevel = i;
            }

            int xbit = 1 & (x >> i);
            int ybit = 1 & (y >> i);
            int zbit = 1 & (z >> i);
            position = (xbit << 2) | (ybit << 1) | zbit;
            nodeIndex = getAt(nodeIndex) + position;

        }
        long finalNodeIndex = getAt(parents[0]) + position;
        setAt(finalNodeIndex, valueFromTypeData(data.type, data.getData()));

        // Merge nodes where all children have been set to the same type.
        for (int i = 0; i <= parentLevel; ++i) {
            long parentIndex = parents[i];

            boolean allSame = true;
            for(int j = 0; j < 8; ++j) {
                long childIndex = getAt(parentIndex) + j;
                if(!nodeEquals(childIndex, nodeIndex)) {
                    allSame = false;
                    break;
                }
            }

            if (allSame) {
                mergeNode(parentIndex, getAt(nodeIndex));
            } else {
                break;
            }
        }
    }

    @Override
    public int getDepth() {
        return depth;
    }

    @Override
    public void startFinalization() {
    }

    @Override
    public void endFinalization() {
        // There is a bunch of WHATEVER nodes we should try to merge
        finalizationNode(0);
        try {
            if(treeData.isWritable()) {
                ((WritableFileCache)treeData).flush();
            }
            treeData = new ThreadSafeReadCache(treeFile, cacheSize, cacheNumber);
        } catch(IOException e) {
            throw new RuntimeException("Error while finalizing the octree", e);
        }
    }

    private void finalizationNode(long nodeIndex) {
        boolean canMerge = true;
        int mergedType = WHATEVER_TYPE;
        int mergedData = 0;
        for(int i = 0; i < 8; ++i) {
            long childIndex = getAt(nodeIndex) + i;
            if(getAt(childIndex) > 0) {
                finalizationNode(childIndex);
                // The node may have been merged, retest if it still a branch node
                if(getAt(childIndex) > 0) {
                    canMerge = false;
                }
            }
            if(canMerge) {
                if(mergedType == WHATEVER_TYPE) {
                    long value = getAt(childIndex);
                    mergedType = typeFromValue(value);
                    mergedData = dataFromValue(value);
                } else if(!(typeFromValue(getAt(childIndex)) == WHATEVER_TYPE || getAt(childIndex) == valueFromTypeData(mergedType, mergedData))) {
                    canMerge = false;
                }
            }
        }
        if(canMerge) {
            mergeNode(nodeIndex, valueFromTypeData(mergedType, mergedData));
        }
    }

    public static DiskOctree load(DataInputStream in) throws IOException {
        int depth = in.readInt();
        DiskOctree tree = new DiskOctree(depth);
        tree.loadNode(in, 0);
        if(tree.treeData.isWritable()) {
            ((WritableFileCache)tree.treeData).flush();
        }
        tree.treeData = new ThreadSafeReadCache(tree.treeFile, tree.cacheSize, tree.cacheNumber);
        return tree;
    }

    private void loadNode(DataInputStream in, long nodeIndex) throws IOException {
        int type = in.readInt();
        if(type == BRANCH_NODE) {
            long childrenIndex = findSpace();
            setAt(nodeIndex, childrenIndex);
            for (int i = 0; i < 8; ++i) {
                loadNode(in, childrenIndex + i);
            }
        } else {
            if ((type & DATA_FLAG) == 0) {
                setAt(nodeIndex, valueFromTypeData(type, 0));
            } else {
                int data = in.readInt();
                setAt(nodeIndex, valueFromTypeData(type ^ DATA_FLAG, 0));
            }
        }
    }

    static public void initImplementation() {
        Octree.addImplementationFactory("DISK", new Octree.ImplementationFactory() {
            @Override
            public Octree.OctreeImplementation create(int depth) {
                try {
                    return new DiskOctree(depth);
                } catch(IOException e) {
                    throw new RuntimeException("Error while creating the DIsk Octree", e);
                }
            }

            @Override
            public Octree.OctreeImplementation load(DataInputStream in) throws IOException {
                return DiskOctree.load(in);
            }

            @Override
            public Octree.OctreeImplementation loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
                return DiskOctree.load(in);
            }

            @Override
            public boolean isOfType(Octree.OctreeImplementation implementation) {
                return implementation instanceof DiskOctree;
            }

            @Override
            public String getDescription() {
                return "A version of the BigPacked octree but stored on disk. Very slow but doesn't consume RAM.";
            }
        });
    }
}
