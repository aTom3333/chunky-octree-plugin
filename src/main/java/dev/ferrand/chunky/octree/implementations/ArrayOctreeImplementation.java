package dev.ferrand.chunky.octree.implementations;

import org.apache.commons.math3.util.Pair;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.world.Material;
import se.llbit.log.Log;
import se.llbit.math.Octree;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class ArrayOctreeImplementation implements Octree.OctreeImplementation {
    /**
     * Not really an octree per say, simply holds a 3D array of every blocks
     */
    private final long[] data;
    private final int depth;
    private final int subStride;

    private static class NodeId implements Octree.NodeId {
        public final int type;
        public final int data;

        public NodeId(int type, int data) {
            this.type = type;
            this.data = data;
        }
    }

    public ArrayOctreeImplementation(int depth) {
        this.depth = depth;
        int stride = (1 << depth);
        this.subStride = stride / 16;
        data = new long[stride * stride * stride];
    }

    // Change this function to improve memory access pattern
    private int getIndex(int x, int y, int z) {
        int bigX = x / 16;
        int bigY = y / 16;
        int bigZ = z / 16;
        int subX = x % 16;
        int subY = y % 16;
        int subZ = z % 16;
        return ((((((bigX * subStride) + bigZ) * subStride + bigY) * subStride + subX) * 16 + subZ) * 16 + subY);
    }

    @Override
    public void set(int type, int x, int y, int z) {
        data[getIndex(x, y, z)] = type;
    }

    @Override
    public void set(Octree.Node node, int x, int y, int z) {
        long value = ((long)node.getData() << 32) | node.type;
        data[getIndex(x, y, z)] = value;
    }

    @Override
    public int getDepth() {
        return depth;
    }

    @Override
    public Octree.NodeId getRoot() {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean isBranch(Octree.NodeId nodeId) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Octree.NodeId getChild(Octree.NodeId nodeId, int i) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public int getType(Octree.NodeId nodeId) {
        return ((NodeId)nodeId).type;
    }

    @Override
    public int getData(Octree.NodeId nodeId) {
        return ((NodeId)nodeId).data;
    }

    @Override
    public Pair<Octree.NodeId, Integer> getWithLevel(int x, int y, int z) {
        long value = data[getIndex(x, y, z)];
        return new Pair<>(
            new NodeId((int)value, (int)(value >>> 32)),
                0
        );
    }

    @Override
    public Octree.Node get(int x, int y, int z) {
        long value = data[getIndex(x, y, z)];
        return new Octree.DataNode((int)value, (int) (value >>> 32));
    }

    @Override
    public Material getMaterial(int x, int y, int z, BlockPalette blockPalette) {
        long value = data[getIndex(x, y, z)];
        return blockPalette.get((int)value);
    }

    @Override
    public void store(DataOutputStream dataOutputStream) throws IOException {
        Log.warn("Store not implemented in ArrayOctree, octree not saved");
    }

    @Override
    public long nodeCount() {
        throw new RuntimeException("nodeCount not implemented");
    }

    static public void initImplementation() {
        Octree.addImplementationFactory("ARRAY", new Octree.ImplementationFactory() {
            @Override
            public Octree.OctreeImplementation create(int depth) {
                return new ArrayOctreeImplementation(depth);
            }

            @Override
            public Octree.OctreeImplementation load(DataInputStream in) throws IOException {
                throw new RuntimeException("Not implemented");
            }

            @Override
            public Octree.OctreeImplementation loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
                throw new RuntimeException("Not implemented");
            }

            @Override
            public boolean isOfType(Octree.OctreeImplementation octreeImplementation) {
                return octreeImplementation instanceof ArrayOctreeImplementation;
            }

            @Override
            public String getDescription() {
                return "Implementation that provide fast octree loading at the cost of memory";
            }
        });
    }
}
