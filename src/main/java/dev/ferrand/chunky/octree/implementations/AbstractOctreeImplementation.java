package dev.ferrand.chunky.octree.implementations;

import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.world.Material;
import se.llbit.math.Octree;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * An abstract octree implementation that implements a number of method
 * required byt the OctreeImplementation interface by using
 * the NodeId abstraction
 */
public abstract class AbstractOctreeImplementation implements Octree.OctreeImplementation {
    protected Octree.NodeId getHelper(int x, int y, int z) {
        Octree.NodeId node = getRoot();
        int level = getDepth();
        while(isBranch(node)) {
            level -= 1;
            int lx = x >>> level;
            int ly = y >>> level;
            int lz = z >>> level;
            node = getChild(node, (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1)));
        }

        return node;
    }

    @Override
    public void set(int type, int x, int y, int z) {
        set(new Octree.Node(type), x, y, z);
    }

    @Override
    public Octree.Node get(int x, int y, int z) {
        Octree.NodeId node = getHelper(x, y, z);
        return new Octree.DataNode(getType(node), getData(node));
    }

    @Override
    public Material getMaterial(int x, int y, int z, BlockPalette palette) {
        return palette.get(getType(getHelper(x, y, z)));
    }

    @Override
    public void store(DataOutputStream out) throws IOException {
        out.writeInt(getDepth());
        storeNode(out, getRoot());
    }

    protected void storeNode(DataOutputStream out, Octree.NodeId node) throws IOException {
        if(isBranch(node)) {
            out.writeInt(Octree.BRANCH_NODE);
            for(int i = 0; i < 8; ++i) {
                storeNode(out, getChild(node, i));
            }
        } else {
            int type = getType(node);
            int data = getData(node);
            if(data != 0) {
                out.writeInt(type | Octree.DATA_FLAG);
                out.writeInt(data);
            } else {
                out.writeInt(type);
            }
        }
    }

    @Override
    public long nodeCount() {
        return countNodes(getRoot());
    }

    private long countNodes(Octree.NodeId node) {
        long total = 1;
        if(isBranch(node)) {
            for(int i = 0; i < 8; ++i) {
                total += countNodes(getChild(node, i));
            }
        }
        return total;
    }
}
