package dev.ferrand.chunky.octree.implementations;

import dev.ferrand.chunky.octree.utils.DynamicIntArrayWithFreeList;
import org.apache.commons.math3.util.Pair;
import se.llbit.chunky.PersistentSettings;
import se.llbit.math.Octree;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static se.llbit.math.Octree.BRANCH_NODE;
import static se.llbit.math.Octree.DATA_FLAG;

public class ForestOctree extends AbstractOctreeImplementation {
    /**
     * This implementation flattens the base of the tree to turn it into a forest
     * // TODO Add more explanations
     */

    private interface SubTree {
        boolean isLeaf();
    }

    private static class Leaf implements SubTree {
        private int type, data, level;

        public Leaf(int type, int data, int level) {
            this.type = type;
            this.data = data;
            this.level = level;
        }

        @Override
        public boolean isLeaf() {
            return true;
        }
    }

    private static class PackedSubTree implements SubTree {
        public final DynamicIntArrayWithFreeList treeData;

        public PackedSubTree() {
            treeData = new DynamicIntArrayWithFreeList(64, 16);
        }

        @Override
        public boolean isLeaf() {
            return false;
        }
    }

    private interface NodeIdInterface extends Octree.NodeId {
        boolean isComplete();
    }

    private static class IncompleteNodeId implements NodeIdInterface {
        public final int x, y, z;
        public final int currentDepth;

        public IncompleteNodeId(int x, int y, int z, int currentDepth) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.currentDepth = currentDepth;
        }

        @Override
        public boolean isComplete() {
            return false;
        }
    }

    private static class CompleteNodeId implements NodeIdInterface {
        public final SubTree subTree;
        public final int index;

        public CompleteNodeId(SubTree subTree, int index) {
            this.subTree = subTree;
            this.index = index;
        }

        @Override
        public boolean isComplete() {
            return true;
        }
    }

    private final int cutLevel;
    private final int depth;
    private final SubTree[] subtrees;

    public ForestOctree(int depth, int cutLevel) {
        this.depth = depth;
        this.cutLevel = cutLevel;
        if(cutLevel > depth) {
            throw new RuntimeException("Can't have a cut level bigger than the depth of the tree");
        }
        subtrees = new SubTree[1 << (3*cutLevel)];
        for(int i = 0; i < subtrees.length; ++i) {
            subtrees[i] = new Leaf(0, 0, depth);
        }
    }

    public ForestOctree(int depth) {
        this(depth, PersistentSettings.settings.getInt("forest.cutLevel", 4));
    }

    private int subTreeIndex(int x, int y, int z) {
        return ((x >>> (depth-cutLevel)) << (2*cutLevel)) | ((y >>> (depth-cutLevel)) << cutLevel) | (z >>> (depth-cutLevel));
    }

    private int subTreeIndexWithNodeCoordinate(int x, int y, int z) {
        return (x << (2*cutLevel)) | (y << cutLevel) | z;
    }

    @Override
    public int getDepth() {
        return depth;
    }

    @Override
    public Octree.NodeId getRoot() {
        return cutLevel == 0 ? new CompleteNodeId(subtrees[0], 0) : new IncompleteNodeId(0, 0, 0, 0);
    }

    @Override
    public boolean isBranch(Octree.NodeId nodeId) {
        if(!((NodeIdInterface)nodeId).isComplete()) {
            return true;
        }
        CompleteNodeId node = (CompleteNodeId)nodeId;
        if(node.subTree.isLeaf()) {
            return false;
        }

        PackedSubTree subtree = (PackedSubTree)node.subTree;
        return subtree.treeData.get(node.index) > 0;
    }

    @Override
    public Octree.NodeId getChild(Octree.NodeId nodeId, int childNo) {
        if(!((NodeIdInterface)nodeId).isComplete()) {
            IncompleteNodeId node = (IncompleteNodeId)nodeId;
            int x = (node.x << 1) | ((childNo >>> 2) & 1);
            int y = (node.y << 1) | ((childNo >>> 1) & 1);
            int z = (node.z << 1) | (childNo & 1);
            int currentDepth = node.currentDepth + 1;
            if(currentDepth >= cutLevel) {
                return new CompleteNodeId(subtrees[subTreeIndexWithNodeCoordinate(x, y, z)], 0);
            } else {
                return new IncompleteNodeId(x, y, z, currentDepth);
            }
        }

        CompleteNodeId node = (CompleteNodeId)nodeId;
        // Assume node.subtree is not Leaf
        PackedSubTree subtree = (PackedSubTree)node.subTree;
        return new CompleteNodeId(subtree, subtree.treeData.get(node.index) + 2 * childNo);
    }

    @Override
    public int getType(Octree.NodeId nodeId) {
        // Assume node is complete
        CompleteNodeId node = (CompleteNodeId)nodeId;
        if(node.subTree.isLeaf()) {
            Leaf leaf = (Leaf)node.subTree;
            return leaf.type;
        } else {
            PackedSubTree subtree = (PackedSubTree) node.subTree;
            return -subtree.treeData.get(node.index);
        }
    }

    @Override
    public int getData(Octree.NodeId nodeId) {
        // Assume node is complete
        CompleteNodeId node = (CompleteNodeId)nodeId;
        if(node.subTree.isLeaf()) {
            Leaf leaf = (Leaf)node.subTree;
            return leaf.data;
        } else {
            PackedSubTree subtree = (PackedSubTree)node.subTree;
            return subtree.treeData.get(node.index+1);
        }
    }


    private void subdivideNode(PackedSubTree subtree, int nodeIndex) {
        int childrenIndex = subtree.treeData.findSpace();
        for(int i = 0; i < 8; ++i) {
            subtree.treeData.set(childrenIndex + 2 * i, subtree.treeData.get(nodeIndex)); // copy type
            subtree.treeData.set(childrenIndex + 2 * i + 1, subtree.treeData.get(nodeIndex + 1)); // copy data
        }
        subtree.treeData.set(nodeIndex, childrenIndex); // Make the node a parent node pointing to its children
        subtree.treeData.set(nodeIndex + 1, 0); // reset its data
    }

    private void mergeNode(PackedSubTree subtree, int nodeIndex, int typeNegation, int data) {
        int childrenIndex = subtree.treeData.get(nodeIndex);
        subtree.treeData.freeSpace(childrenIndex); // Delete children
        subtree.treeData.set(nodeIndex, typeNegation); // Make the node a leaf one
        subtree.treeData.set(nodeIndex + 1, data);
    }

    private boolean nodeEquals(PackedSubTree subtree, int firstNodeIndex, int secondNodeIndex) {
        boolean firstIsBranch = subtree.treeData.get(firstNodeIndex) > 0;
        boolean secondIsBranch = subtree.treeData.get(secondNodeIndex) > 0;
        return ((firstIsBranch && secondIsBranch) || subtree.treeData.get(firstNodeIndex) == subtree.treeData.get(secondNodeIndex)) // compare types
                && subtree.treeData.get(firstNodeIndex + 1) == subtree.treeData.get(secondNodeIndex + 1); // compare data
    }

    private boolean nodeEquals(PackedSubTree subtree, int firstNodeIndex, Octree.Node secondNode) {
        boolean firstIsBranch = subtree.treeData.get(firstNodeIndex) > 0;
        boolean secondIsBranch = (secondNode.type == BRANCH_NODE);
        return ((firstIsBranch && secondIsBranch) || -subtree.treeData.get(firstNodeIndex) == secondNode.type) // compare types (don't forget that in the tree the negation of the type is stored)
                && subtree.treeData.get(firstNodeIndex + 1) == secondNode.getData(); // compare data
    }


    public void setComplete(Octree.Node node, int subTreeIndex, int subX, int subY, int subZ) {
        if(subtrees[subTreeIndex].isLeaf()) {
            Leaf leaf = (Leaf)subtrees[subTreeIndex];
            if(leaf.type != node.type || leaf.data != node.getData()) {
                // Subdivide node
                PackedSubTree subTree = new PackedSubTree();
                subTree.treeData.pushBack(-leaf.type);
                subTree.treeData.pushBack(leaf.data);
                subtrees[subTreeIndex] = subTree;
            } else {
                return;
            }
        }

        // Same thing as what packed octree does
        PackedSubTree subTree = (PackedSubTree)subtrees[subTreeIndex];
        int[] parents = new int[depth-cutLevel]; // better to put as a field to prevent allocation at each invocation?
        int nodeIndex = 0;
        int parentLevel = depth - cutLevel - 1;
        int position = 0;
        for(int i = depth - cutLevel - 1; i >= 0; --i) {
            parents[i] = nodeIndex;

            if(nodeEquals(subTree, nodeIndex, node)) {
                return;
            } else if(subTree.treeData.get(nodeIndex) <= 0) { // It's a leaf node
                subdivideNode(subTree, nodeIndex);
                parentLevel = i;
            }

            int xbit = 1 & (subX >> i);
            int ybit = 1 & (subY >> i);
            int zbit = 1 & (subZ >> i);
            position = (xbit << 2) | (ybit << 1) | zbit;
            nodeIndex = subTree.treeData.get(nodeIndex) + position * 2;

        }
        int finalNodeIndex = nodeIndex;
        subTree.treeData.set(finalNodeIndex, -node.type); // Store negation of the type
        subTree.treeData.set(finalNodeIndex + 1, node.getData());

        // Merge nodes where all children have been set to the same type.
        for(int i = 0; i <= parentLevel; ++i) {
            int parentIndex = parents[i];

            boolean allSame = true;
            for(int j = 0; j < 8; ++j) {
                int childIndex = subTree.treeData.get(parentIndex) + 2 * j;
                if(!nodeEquals(subTree, childIndex, nodeIndex)) {
                    allSame = false;
                    break;
                }
            }

            if(allSame) {
                mergeNode(subTree, parentIndex, subTree.treeData.get(nodeIndex), subTree.treeData.get(nodeIndex + 1));
            } else {
                break;
            }
        }

        // If after merging the subtree is completely merged, replace by a leaf
        if(subTree.treeData.get(0) <= 0) {
            int type = -subTree.treeData.get(0);
            int data = subTree.treeData.get(0);

            subtrees[subTreeIndex] = new Leaf(type, data, depth-cutLevel);
        }
    }

    void updateLeafLevel(int x, int y, int z, int currentDepth) {
        if(currentDepth == cutLevel) {
            SubTree subTree = subtrees[subTreeIndexWithNodeCoordinate(x, y, z)];
            if(!subTree.isLeaf()) {
                throw new RuntimeException("unexpected error");
            }
            Leaf leaf = (Leaf)subTree;
            leaf.level--;
        } else {
            updateLeafLevel((x << 1), (y << 1), (z << 1), currentDepth+1);
            updateLeafLevel((x << 1), (y << 1), (z << 1) + 1, currentDepth+1);
            updateLeafLevel((x << 1), (y << 1) + 1, (z << 1), currentDepth+1);
            updateLeafLevel((x << 1), (y << 1) + 1, (z << 1) + 1, currentDepth+1);
            updateLeafLevel((x << 1) + 1, (y << 1), (z << 1), currentDepth+1);
            updateLeafLevel((x << 1) + 1, (y << 1), (z << 1) + 1, currentDepth+1);
            updateLeafLevel((x << 1) + 1, (y << 1) + 1, (z << 1), currentDepth+1);
            updateLeafLevel((x << 1) + 1, (y << 1) + 1, (z << 1) + 1, currentDepth+1);
        }
    }

    @Override
    public void set(Octree.Node node, int x, int y, int z) {
        int subtreeIndex = subTreeIndex(x, y, z);
        SubTree subTree = subtrees[subtreeIndex];
        if(subTree.isLeaf()) {
            Leaf leaf = (Leaf)subTree;
            if(leaf.type == node.type && leaf.data == node.getData()) {
                return; // No need to set
            }
            if(leaf.level > depth - cutLevel) {
                // Just reduce the level of this leaf and others of its "family"
                updateLeafLevel(x >>> leaf.level, y >>> leaf.level, z >>> leaf.level, depth - leaf.level);
                leaf.type = node.type;
                leaf.data = node.getData();
            } else {
                // The leaf needs to be replaced by a real subtree
                int mask = (1 << (depth - cutLevel)) - 1;
                setComplete(node, subtreeIndex, x & mask, y & mask, z & mask);
            }
        } else {
            int mask = (1 << (depth - cutLevel)) - 1;
            setComplete(node, subtreeIndex, x & mask, y & mask, z & mask);
            // TODO Merge leaves
        }
    }

    public static ForestOctree load(DataInputStream in) throws IOException {
        int depth = in.readInt();
        ForestOctree octree = new ForestOctree(depth);
        octree.loadNodeIncomplete(in, 0, 0, 0, 0);
        return octree;
    }

    public static ForestOctree load(DataInputStream in, int cutLevel) throws IOException {
        int depth = in.readInt();
        ForestOctree octree = new ForestOctree(depth, cutLevel);
        octree.loadNodeIncomplete(in, 0, 0, 0, 0);
        return octree;
    }

    private void loadNodeComplete(DataInputStream in, PackedSubTree subTree, int nodeIndex) throws IOException {
        int type = in.readInt();
        if(type == BRANCH_NODE) {
            int childrenIndex = subTree.treeData.findSpace();
            subTree.treeData.set(nodeIndex, childrenIndex);
            subTree.treeData.set(nodeIndex+1, 0);
            for(int i = 0; i < 8; ++i) {
                loadNodeComplete(in, subTree, childrenIndex + 2 * i);
            }
        } else {
            int data = 0;
            if((type & DATA_FLAG) != 0) {
                type ^=DATA_FLAG;
                data = in.readInt();
            }
            subTree.treeData.set(nodeIndex, -type);
            subTree.treeData.set(nodeIndex+1, data);
        }
    }

    private void setLeaf(int type, int data, int level, int x, int y, int z, int currentDepth) {
        if(currentDepth == cutLevel) {
            int leafIndex = subTreeIndexWithNodeCoordinate(x, y, z);
            Leaf leaf = (Leaf)subtrees[leafIndex];
            leaf.type = type;
            leaf.data = data;
            leaf.level = level;
        } else {
            setLeaf(type, data, level, (x << 1), (y << 1), (z << 1), currentDepth+1);
            setLeaf(type, data, level, (x << 1), (y << 1), (z << 1) + 1, currentDepth+1);
            setLeaf(type, data, level, (x << 1), (y << 1) + 1, (z << 1), currentDepth+1);
            setLeaf(type, data, level, (x << 1), (y << 1) + 1, (z << 1) + 1, currentDepth+1);
            setLeaf(type, data, level, (x << 1) + 1, (y << 1), (z << 1), currentDepth+1);
            setLeaf(type, data, level, (x << 1) + 1, (y << 1), (z << 1) + 1, currentDepth+1);
            setLeaf(type, data, level, (x << 1) + 1, (y << 1) + 1, (z << 1), currentDepth+1);
            setLeaf(type, data, level, (x << 1) + 1, (y << 1) + 1, (z << 1) + 1, currentDepth+1);
        }
    }

    private void loadNodeIncomplete(DataInputStream in, int x, int y, int z, int currentDepth) throws IOException {
        int type = in.readInt();
        if(type == BRANCH_NODE) {
            if(currentDepth == cutLevel) {
                int subTreeIndex = subTreeIndexWithNodeCoordinate(x, y, z);
                PackedSubTree subTree = new PackedSubTree();
                subTree.treeData.pushBack(0); // Placeholder for index
                subTree.treeData.pushBack(0);
                int childrenIndex = subTree.treeData.findSpace();
                subTree.treeData.set(0, childrenIndex);
                for(int i = 0; i < 8; ++i) {
                    loadNodeComplete(in, subTree, childrenIndex + 2*i);
                }
                subtrees[subTreeIndex] = subTree;
            } else {
                loadNodeIncomplete(in, (x << 1), (y << 1), (z << 1), currentDepth+1);
                loadNodeIncomplete(in, (x << 1), (y << 1), (z << 1) + 1, currentDepth+1);
                loadNodeIncomplete(in, (x << 1), (y << 1) + 1, (z << 1), currentDepth+1);
                loadNodeIncomplete(in, (x << 1), (y << 1) + 1, (z << 1) + 1, currentDepth+1);
                loadNodeIncomplete(in, (x << 1) + 1, (y << 1), (z << 1), currentDepth+1);
                loadNodeIncomplete(in, (x << 1) + 1, (y << 1), (z << 1) + 1, currentDepth+1);
                loadNodeIncomplete(in, (x << 1) + 1, (y << 1) + 1, (z << 1), currentDepth+1);
                loadNodeIncomplete(in, (x << 1) + 1, (y << 1) + 1, (z << 1) + 1, currentDepth+1);
            }
        } else {
            int data = 0;
            if((type & DATA_FLAG) != 0) {
                type ^= DATA_FLAG;
                data = in.readInt();
            }
            setLeaf(type, data, depth-currentDepth, x, y, z, currentDepth);
        }
    }

    private Pair<Boolean, Octree.Node> areAllChildSame(Octree.NodeId nodeId) {
        if(((NodeIdInterface)nodeId).isComplete()) {
            return new Pair<>(false, null);
        }
        int levels = 0;
        NodeIdInterface node = (NodeIdInterface)nodeId;
        while(!node.isComplete()) {
            node = (NodeIdInterface) getChild(node, 0);
            ++levels;
        }
        if(((CompleteNodeId)node).subTree.isLeaf() && ((Leaf)((CompleteNodeId) node).subTree).level == depth - (cutLevel - levels)) {
            // The leaf covers the whole volume represented by the nodeId given in parameter
            Leaf leaf = (Leaf) ((CompleteNodeId) node).subTree;
            return new Pair<>(true, new Octree.DataNode(leaf.type, leaf.data));
        }
        return new Pair<>(false, null);
    }

    @Override
    protected void storeNode(DataOutputStream out, Octree.NodeId node) throws IOException {
        int type = 0;
        int data = 0;

        if(isBranch(node)) {
            Pair<Boolean, Octree.Node> result = areAllChildSame(node);
            if(!result.getFirst()) {
                out.writeInt(Octree.BRANCH_NODE);
                for(int i = 0; i < 8; ++i) {
                    storeNode(out, getChild(node, i));
                }
                return;
            } else {
                type = result.getSecond().type;
                data = result.getSecond().getData();
            }
        } else {
            type = getType(node);
            data = getData(node);
        }

        if(data != 0) {
            out.writeInt(type | Octree.DATA_FLAG);
            out.writeInt(data);
        } else {
            out.writeInt(type);
        }
    }

    /**
     * The whole purpose of this octree implementation is to speed up this method
     */
    @Override
    public Pair<Octree.NodeId, Integer> getWithLevel(int x, int y, int z) {
        SubTree subTree = subtrees[subTreeIndex(x, y, z)];
        if(subTree.isLeaf()) {
            Leaf leaf = (Leaf)subTree;
            return new Pair<>(new CompleteNodeId(leaf, 0), leaf.level);
        } else {
            // walk down the remaining nodes
            // Inline the use of isBranch/getChild methods
            PackedSubTree packedSubTree = (PackedSubTree)subTree;
            int nodeIndex = 0;
            int level = depth - cutLevel;
            while(packedSubTree.treeData.get(nodeIndex) > 0) {
                level -= 1;
                int lx = x >>> level;
                int ly = y >>> level;
                int lz = z >>> level;
                nodeIndex = packedSubTree.treeData.get(nodeIndex) + (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1)) * 2;
            }

            return new Pair<>(new CompleteNodeId(packedSubTree, nodeIndex), level);
        }
    }

    static public void initImplementation() {
        Octree.addImplementationFactory("FOREST", new Octree.ImplementationFactory() {
            @Override
            public Octree.OctreeImplementation create(int depth) {
                return new ForestOctree(depth);
            }

            @Override
            public Octree.OctreeImplementation load(DataInputStream in) throws IOException {
                return ForestOctree.load(in);
            }

            @Override
            public Octree.OctreeImplementation loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
                return ForestOctree.load(in);
            }

            @Override
            public boolean isOfType(Octree.OctreeImplementation implementation) {
                return implementation instanceof ForestOctree;
            }

            @Override
            public String getDescription() {
                return "Cuts the octree into multiple subtrees to speed up access.";
            }
        });
    }
}
