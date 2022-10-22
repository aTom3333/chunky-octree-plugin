package dev.ferrand.chunky.octree.implementations;

import se.llbit.chunky.PersistentSettings;
import se.llbit.chunky.block.Block;
import se.llbit.chunky.block.UnknownBlock;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.world.Material;
import se.llbit.math.Octree;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

import static se.llbit.math.Octree.*;

/**
 * This is a packed representation of an octree
 * the whole octree is stored in a int array to reduce memory usage and
 * hopefully improve performance by being more cache-friendly
 */
public class StatsOctree extends AbstractOctreeImplementation {
    /**
     * The whole tree data is store in a int array
     * <p>
     * Each node is made of several values :
     * - the node type (could be a branch node or the type of block contained)
     * - optional additional data
     * - the index of its first child (if branch node)
     * <p>
     * As nodes are stored linearly, we place siblings nodes in a row and so
     * we only need the index of the first child as the following are just after
     * <p>
     * The node type is always positive, we can use this knowledge to compress the node to 2 ints:
     * one will contains the index of the first child if it is positive or the negation of the type
     * the other will contain the additional data
     * <p>
     * This implementation is inspired by this stackoverflow answer
     * https://stackoverflow.com/questions/41946007/efficient-and-well-explained-implementation-of-a-quadtree-for-2d-collision-det#answer-48330314
     * <p>
     * Note: Only leaf nodes can have additional data. In theory
     * we could potentially optimize further by only storing the index for branch nodes
     * by that would make other operations more complex. Most likely not worth it but could be an idea
     * <p>
     * When dealing with huge octree, the maximum size of an array may be a limitation
     * When this occurs this implementation wan no longer be used and we must fallback on another one.
     * Here we'll throw an exception that the caller can catch
     */
    private int[] treeData;

    /**
     * The max size of an array we allow is a bit less than the max value an integer can have
     */
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 16;

    /**
     * When adding nodes to the octree, the treeData array may have to grow
     * We implement a simple growing dynamic array, like an ArrayList
     * We don't we use ArrayList because it only works with objects
     * and having an array of Integer instead of int would increase the memory usage.
     * size gives us the size of the dynamic array, the capacity is given by treeData.length
     */
    private int size;
    /**
     * When removing nodes form the tree, it leaves holes in the array.
     * Those holes could be reused later when new nodes need to be added
     * We use a free list to keep of the location of the holes.
     * freeHead gives use the index of the head of the free list, if it is -1, there is no
     * holes that can be reused and the size of the array must be increased
     */
    private int freeHead;

    private int depth;

    private int totalNodes;
    private int maxNodesCount;
    private int finalSize;
    private int branchNode;
    private int leafNodes;
    private int leafNodesAtFullDepth;
    private long creationTime;
    private long finalizationStartTime;
    private long finalizationEndTime;
    private int maxType;
    private int allocation;
    private long blockCount;

    private BlockPalette palette;

    private static final class NodeId implements Octree.NodeId {
        int nodeIndex;

        public NodeId(int nodeIndex) {
            this.nodeIndex = nodeIndex;
        }
    }

    @Override
    public Octree.NodeId getRoot() {
        return new NodeId(0);
    }

    @Override
    public boolean isBranch(Octree.NodeId node) {
        return treeData[((NodeId) node).nodeIndex] > 0;
    }

    @Override
    public Octree.NodeId getChild(Octree.NodeId parent, int childNo) {
        return new NodeId(treeData[((NodeId) parent).nodeIndex] + childNo);
    }

    @Override
    public int getType(Octree.NodeId node) {
        return -treeData[((NodeId) node).nodeIndex];
    }

    /**
     * A custom exception that signals the octree is too big for this implementation
     */
    public static class OctreeTooBigException extends RuntimeException {
    }

    /**
     * Constructor building a tree with capacity for some nodes
     *
     * @param depth     The depth of the tree
     * @param nodeCount The number of nodes this tree will contain
     */
    public StatsOctree(int depth, long nodeCount) {
        this.depth = depth;
        long arraySize = Math.max(nodeCount, 64);
        if(arraySize > (long) MAX_ARRAY_SIZE)
            throw new OctreeTooBigException();
        treeData = new int[(int) arraySize];
        treeData[0] = 0;
        size = 1;
        freeHead = -1; // No holes

        totalNodes = 1;
        maxNodesCount = 0;
        finalSize = 0;
        branchNode = 0;
        leafNodes = 1;
        leafNodesAtFullDepth = 0;
        creationTime = System.nanoTime();
        finalizationStartTime = 0;
        finalizationEndTime = 0;
        maxType = 0;
        allocation = 1;
        blockCount = 0;
    }

    /**
     * Constructs an empty octree
     *
     * @param depth The depth of the tree
     */
    public StatsOctree(int depth) {
        this.depth = depth;
        treeData = new int[1024];
        // Add a root node
        treeData[0] = 0;
        size = 1;
        freeHead = -1;

        totalNodes = 1;
        maxNodesCount = 0;
        finalSize = 0;
        branchNode = 0;
        leafNodes = 1;
        leafNodesAtFullDepth = 0;
        creationTime = System.nanoTime();
        finalizationStartTime = 0;
        finalizationEndTime = 0;
        maxType = 0;
        allocation = 1;
        blockCount = 0;
    }

    /**
     * Finds space in the array to put 8 nodes
     * We find space by searching in the free list
     * if this fails we append at the end of the array
     * if the size is greater than the capacity, we allocate a new array
     *
     * @return the index at the beginning of a free space in the array of size 16 ints (8 nodes)
     */
    private int findSpace() {
        // Look in free list
        if(freeHead != -1) {
            int index = freeHead;
            freeHead = treeData[freeHead];
            return index;
        }

        // append in array if we have the capacity
        if(size + 8 <= treeData.length) {
            int index = size;
            size += 8;
            return index;
        }

        // We need to grow the array
        long newSize = (long) Math.ceil(treeData.length * 1.5);
        // We need to check the array won't be too big
        if(newSize > (long) MAX_ARRAY_SIZE) {
            // We can allocate less memory than initially wanted if the next block will still be able to fit
            // If not, this implementation isn't suitable
            if(MAX_ARRAY_SIZE - treeData.length > 8) {
                // If by making the new array be of size MAX_ARRAY_SIZE we can still fit the block requested
                newSize = MAX_ARRAY_SIZE;
            } else {
                // array is too big
                throw new se.llbit.math.PackedOctree.OctreeTooBigException();
            }
        }
        allocation++;
        int[] newArray = new int[(int) newSize];
        System.arraycopy(treeData, 0, newArray, 0, size);
        treeData = newArray;
        // and then append
        int index = size;
        size += 8;
        return index;
    }

    /**
     * free space at the given index, simply add the 16 ints block beginning at index to the free list
     *
     * @param index the index of the beginning of the block to free
     */
    private void freeSpace(int index) {
        treeData[index] = freeHead;
        freeHead = index;
    }

    /**
     * Subdivide a node, give to each child the same type and data that this node previously had
     *
     * @param nodeIndex The index of the node to subdivide
     */
    private void subdivideNode(int nodeIndex) {
        totalNodes += 8;
        maxNodesCount = Math.max(totalNodes, maxNodesCount);
        leafNodes += 7;
        branchNode++;
        int childrenIndex = findSpace();
        for(int i = 0; i < 8; ++i) {
            treeData[childrenIndex + i] = treeData[nodeIndex]; // copy type
        }
        treeData[nodeIndex] = childrenIndex; // Make the node a parent node pointing to its children
    }

    /**
     * Merge a parent node so it becomes a leaf node
     *
     * @param nodeIndex    The index of the node to merge
     * @param typeNegation The negation of the type (the value directly stored in the array)
     */
    private void mergeNode(int nodeIndex, int typeNegation) {
        totalNodes -= 8;
        leafNodes -= 7;
        branchNode--;
        int childrenIndex = treeData[nodeIndex];
        freeSpace(childrenIndex); // Delete children
        treeData[nodeIndex] = typeNegation; // Make the node a leaf one
    }

    /**
     * Compare two nodes
     *
     * @param firstNodeIndex  The index of the first node
     * @param secondNodeIndex The index of the second node
     * @return true id the nodes compare equals, false otherwise
     */
    private boolean nodeEquals(int firstNodeIndex, int secondNodeIndex) {
        return treeData[firstNodeIndex] == treeData[secondNodeIndex]; // compare types
    }

    /**
     * Compare two nodes
     *
     * @param firstNodeIndex The index of the first node
     * @param secondNode     The second node (most likely outside of tree)
     * @return true id the nodes compare equals, false otherwise
     */
    private boolean nodeEquals(int firstNodeIndex, Node secondNode) {
        boolean firstIsBranch = treeData[firstNodeIndex] > 0;
        boolean secondIsBranch = (secondNode.type == BRANCH_NODE);
        return ((firstIsBranch && secondIsBranch) || -treeData[firstNodeIndex] == secondNode.type); // compare types (don't forget that in the tree the negation of the type is stored)
    }

    @Override
    public void set(int type, int x, int y, int z) {
        if(type != ANY_TYPE) {
            maxType = Math.max(maxType, type);
        }
        if(type != 0)
            ++blockCount;

        int[] parents = new int[depth]; // better to put as a field to preventallocation at each invocation?
        int nodeIndex = 0;
        int parentLevel = depth - 1;
        int position = 0;
        for(int i = depth - 1; i >= 0; --i) {
            parents[i] = nodeIndex;

            if(-treeData[nodeIndex] == type) {
                return;
            } else if(treeData[nodeIndex] <= 0) { // It's a leaf node
                subdivideNode(nodeIndex);
                parentLevel = i;
            }

            int xbit = 1 & (x >> i);
            int ybit = 1 & (y >> i);
            int zbit = 1 & (z >> i);
            position = (xbit << 2) | (ybit << 1) | zbit;
            nodeIndex = treeData[nodeIndex] + position;

        }
        int finalNodeIndex = treeData[parents[0]] + position;
        treeData[finalNodeIndex] = -type; // Store negation of the type

        int childrenIndex = treeData[parents[0]];
        FullDepthSiblings siblings = new FullDepthSiblings(
                treeData[childrenIndex + 0],
                treeData[childrenIndex + 1],
                treeData[childrenIndex + 2],
                treeData[childrenIndex + 3],
                treeData[childrenIndex + 4],
                treeData[childrenIndex + 5],
                treeData[childrenIndex + 6],
                treeData[childrenIndex + 7]
        );
        if(!siblingsToIndex.containsKey(siblings)) {
            siblingsToIndex.put(siblings, fullDepthSiblingsDictionary.size());
            for(int i = 0; i < 8; i++) {
                fullDepthSiblingsDictionary.add(treeData[childrenIndex+i]);
            }
        }

        // Merge nodes where all children have been set to the same type.
        for(int i = 0; i <= parentLevel; ++i) {
            int parentIndex = parents[i];

            boolean allSame = true;
            for(int j = 0; j < 8; ++j) {
                int childIndex = treeData[parentIndex] + j;
                if(!nodeEquals(childIndex, nodeIndex)) {
                    allSame = false;
                    break;
                }
            }

            if(allSame) {
                mergeNode(parentIndex, treeData[nodeIndex]);
            } else {
                break;
            }
        }
    }

    private int getNodeIndex(int x, int y, int z) {
        int nodeIndex = 0;
        int level = depth;
        while(treeData[nodeIndex] > 0) {
            level -= 1;
            int lx = x >>> level;
            int ly = y >>> level;
            int lz = z >>> level;
            nodeIndex = treeData[nodeIndex] + (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1));
        }
        return nodeIndex;
    }

    @Override
    public Material getMaterial(int x, int y, int z, BlockPalette palette) {
        this.palette = palette;
        // Building the dummy node is useless here
        int nodeIndex = getNodeIndex(x, y, z);
        if(treeData[nodeIndex] > 0) {
            return UnknownBlock.UNKNOWN;
        }
        return palette.get(-treeData[nodeIndex]);
    }

    @Override
    public int getDepth() {
        return depth;
    }

    public static StatsOctree load(DataInputStream in) throws IOException {
        int depth = in.readInt();
        StatsOctree tree = new StatsOctree(depth);
        tree.loadNode(in, 0);
        tree.statisticsLoading();
        return tree;
    }

    public static StatsOctree loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
        int depth = in.readInt();
        StatsOctree tree = new StatsOctree(depth, nodeCount);
        tree.loadNode(in, 0);
        tree.statisticsLoading();
        return tree;
    }

    private void loadNode(DataInputStream in, int nodeIndex) throws IOException {
        int type = in.readInt();
        if(type == BRANCH_NODE) {
            int childrenIndex = findSpace();
            treeData[nodeIndex] = childrenIndex;
            for(int i = 0; i < 8; ++i) {
                loadNode(in, childrenIndex + i);
            }
        } else {
            treeData[nodeIndex] = -type; // negation of type
        }
    }

    @Override
    public void startFinalization() {
        finalizationStartTime = System.nanoTime();
    }

    @Override
    public void endFinalization() {
        // There is a bunch of ANY_TYPE nodes we should try to merge
        finalizationNode(0);
        finalizationEndTime = System.nanoTime();

        statistics();
    }

    private void finalizationNode(int nodeIndex) {
        boolean canMerge = true;
        int mergedType = -ANY_TYPE;
        for(int i = 0; i < 8; ++i) {
            int childIndex = treeData[nodeIndex] + i;
            if(treeData[childIndex] > 0) {
                finalizationNode(childIndex);
                // The node may have been merged, retest if it still a branch node
                if(treeData[childIndex] > 0) {
                    canMerge = false;
                }
            }
            if(canMerge) {
                if(mergedType == -ANY_TYPE) {
                    mergedType = treeData[childIndex];
                } else if(!(treeData[childIndex] == -ANY_TYPE || (treeData[childIndex] == mergedType))) {
                    canMerge = false;
                }
            }
        }
        if(canMerge) {
            mergeNode(nodeIndex, mergedType);
        }
    }

    static private void show(String str, long number) {
        System.out.printf("%s : %,d\n", str, number);
    }

    static private String formatSize(long size) {
        double finalSize = size;
        String[] suffix = {
                "B", "kB", "MB", "GB", "TB"
        };
        int idx = 0;
        while(idx < suffix.length && (finalSize > 10000 || finalSize < -10000)) {
            idx++;
            finalSize /= 1000;
        }
        return String.format("%,.1f%s", finalSize, suffix[idx]);
    }

    static private void showSize(String str, long number) {
        System.out.printf("%s : %s\n", str, formatSize(number));
    }

    static private void compare(String str, long building, long walking) {
        show(str + " (computed during building)", building);
        show(str + " (computed by walking the tree)", walking);
        System.out.flush();
        System.err.flush();
        if(building != walking) {
            System.out.println("Error: values for " + str + " are different");
        }
    }

    private class FullDepthSiblings {
        public int child0, child1, child2, child3, child4, child5, child6, child7;

        public FullDepthSiblings(int child0, int child1, int child2, int child3, int child4, int child5, int child6, int child7) {
            this.child0 = child0;
            this.child1 = child1;
            this.child2 = child2;
            this.child3 = child3;
            this.child4 = child4;
            this.child5 = child5;
            this.child6 = child6;
            this.child7 = child7;
        }

        @Override
        public boolean equals(Object o) {
            if(this == o) return true;
            if(o == null || getClass() != o.getClass()) return false;
            FullDepthSiblings that = (FullDepthSiblings) o;
            return child0 == that.child0 &&
                    child1 == that.child1 &&
                    child2 == that.child2 &&
                    child3 == that.child3 &&
                    child4 == that.child4 &&
                    child5 == that.child5 &&
                    child6 == that.child6 &&
                    child7 == that.child7;
        }

        @Override
        public int hashCode() {
            return Objects.hash(child0, child1, child2, child3, child4, child5, child6, child7);
        }
    }

    private ArrayList<Integer> fullDepthSiblingsDictionary = new ArrayList<>();
    private HashMap<FullDepthSiblings, Integer> siblingsToIndex = new HashMap<>();

    private void statistics() {
        long sizeDictionaryBuilding = fullDepthSiblingsDictionary.size();
        fullDepthSiblingsDictionary = new ArrayList<>();
        siblingsToIndex = new HashMap<>();

        int totalNodesBuilding = totalNodes;
        totalNodes = 1;
        int branchNodeBuilding = branchNode;
        branchNode = 1;
        int leafNodesBuilding = leafNodes;
        leafNodes = 0;
        int maxTypeBuilding = maxType;
        maxType = 0;


        int[] typeHistogram = new int[maxTypeBuilding + 1];

        computeStatForNode(0, 1, typeHistogram);

        double totalTime = (double) (finalizationEndTime - creationTime) / 1e9;
        double loadingTime = (double) (finalizationStartTime - creationTime) / 1e9;
        double finalizationTime = (double) (finalizationEndTime - finalizationStartTime) / 1e9;

        System.out.printf("total time: %fs\n", totalTime);
        System.out.printf("loading time: %fs\n", loadingTime);
        System.out.printf("finalization time: %fs\n", finalizationTime);

        show("number of non-air blocks", blockCount);
        compare("number of nodes", totalNodesBuilding, totalNodes);
        compare("number of branch nodes", branchNodeBuilding, branchNode);
        compare("number of leaf nodes", leafNodesBuilding, leafNodes);
        if(branchNode + leafNodes == totalNodes)
            System.out.println("total nodes is the sum of the number of branch and leaf nodes");
        else
            System.out.println("Error: total nodes is NOT the sum of the number of branch and leaf nodes");
        if(7 * branchNode == leafNodes - 1)
            System.out.println("numbers respect the theoretical, leaves = 7*n+1, branches = n, for some n (being the number of split minus the number of merge)");
        else
            System.out.println("Error: numbers does NOT respect the theoretical, leaves = 7*n+1, branches = n, for some n (being the number of split minus the number of merge)");
        show("maximum number of nodes during construction", maxNodesCount);
        showSize("size needed to store the maximum number of nodes", 4 * (long)maxNodesCount);
        showSize("size of final allocation", (long)treeData.length * 4);
        show("number of (re)allocation during building", allocation);
        showSize("size needed for final number of nodes", (long)totalNodes * 4);
        showSize("size wasted by over-allocation", (long)treeData.length * 4 - 4 * (long)maxNodesCount);
        showSize("size wasted during construction", (long)treeData.length * 4 - 4 * (long)totalNodes);
        show("depth of the octree", depth);
        show("number of leaf nodes living at the full depth", leafNodesAtFullDepth);
        show("maximum block type stored during building (excluding ANY)", maxTypeBuilding);
        show("maximum block type stored (excluding ANY)", maxType);
        if(maxType < 65535) {
            System.out.println("the maximum block type fits into a 16 bits integer, leaf nodes at full depth could be made more compact");
            System.out.printf("size saved by such optimization: %s (%.2f%% of size needed for total)\n", formatSize((long)leafNodesAtFullDepth * 2), (double) leafNodesAtFullDepth * 2 / (totalNodes * 4) * 100);
        } else {
            System.out.println("the maximum block type does NOT fit into a 16 bits integer, leaf nodes at full depth could not be made more compact");
        }

        show("number of unique 2x2x2 group during building", sizeDictionaryBuilding / 8);
        show("number of unique 2x2x2 group", fullDepthSiblingsDictionary.size() / 8);
        showSize("size used by the group dictionary (if using 32 bits, during building)", sizeDictionaryBuilding*4);
        showSize("size used by the group dictionary (if using 32 bits, after building)", (long)fullDepthSiblingsDictionary.size()*4);
        showSize("size used by the group dictionary (if using 16 bits, during building)", sizeDictionaryBuilding*2);
        showSize("size used by the group dictionary (if using 16 bits, after building)", (long)fullDepthSiblingsDictionary.size()*2);
        showSize("memory saved by using a dictionary (if leaf were stored by 32 bits)", (long)leafNodesAtFullDepth * 4);
        showSize("memory saved by using a dictionary (if leaf were stored by 16 bits)", (long)leafNodesAtFullDepth * 2);
        showSize("memory difference if using a dictionary (with 32 bits types, with dictionary created during building)", sizeDictionaryBuilding*4 - (long)leafNodesAtFullDepth * 4);
        showSize("memory difference if using a dictionary (with 32 bits types, with dictionary created after building)", (long)fullDepthSiblingsDictionary.size()*4 - (long)leafNodesAtFullDepth * 4);
        showSize("memory difference if using a dictionary (with 16 bits types, with dictionary created during building)", sizeDictionaryBuilding*2 - (long)leafNodesAtFullDepth * 2);
        showSize("memory difference if using a dictionary (with 16 bits types, with dictionary created after building)", (long)fullDepthSiblingsDictionary.size()*2 - (long)leafNodesAtFullDepth * 2);

        long cubeBlocks = 0;
        long nonCubeBlocks = 0;

        System.out.println("Block type histogram (type: number of occurrence):");
        for(int i = 0; i < typeHistogram.length; ++i) {
            if(typeHistogram[i] > 0) {
                System.out.printf("%d: %d\n", i, typeHistogram[i]);
                if(palette != null && i > 0) {
                    Block block = palette.get(i);
                    if(block.localIntersect)
                        nonCubeBlocks += typeHistogram[i];
                    else
                        cubeBlocks += typeHistogram[i];
                }
            }
        }

        long nonAirBlocks = cubeBlocks + nonCubeBlocks;

        if(palette != null) {
            show("Non air blocks", nonAirBlocks);
            System.out.printf("Cube blocks: %,d (%f%%)\n", cubeBlocks, (double)cubeBlocks / nonAirBlocks * 100);
            System.out.printf("Non cube blocks: %,d (%f%%)\n", nonCubeBlocks, (double)nonCubeBlocks / nonAirBlocks * 100);
        }

        System.out.println("-----");
    }

    private void statisticsLoading() {
        fullDepthSiblingsDictionary = new ArrayList<>();
        siblingsToIndex = new HashMap<>();
        totalNodes = 1;
        branchNode = 1;
        leafNodes = 0;
        maxType = 0;

        int[] typeHistogram = new int[100000];

        computeStatForNode(0, 1, typeHistogram);

        show("number of nodes", totalNodes);
        show("number of branch nodes", branchNode);
        show("number of leaf nodes", leafNodes);
        if(branchNode + leafNodes == totalNodes)
            System.out.println("total nodes is the sum of the number of branch and leaf nodes");
        else
            System.out.println("Error: total nodes is NOT the sum of the number of branch and leaf nodes");
        if(7 * branchNode == leafNodes - 1)
            System.out.println("numbers respect the theoretical, leaves = 7*n+1, branches = n, for some n (being the number of split minus the number of merge)");
        else
            System.out.println("Error: numbers does NOT respect the theoretical, leaves = 7*n+1, branches = n, for some n (being the number of split minus the number of merge)");
        showSize("size of final allocation", (long)treeData.length * 4);
        show("number of (re)allocation during building", allocation);
        showSize("size needed for final number of nodes", (long)totalNodes * 4);
        showSize("size wasted by over-allocation", (long)treeData.length * 4 - 4 * (long)totalNodes);
        show("depth of the octree", depth);
        show("number of leaf nodes living at the full depth", leafNodesAtFullDepth);
        show("maximum block type stored (excluding ANY)", maxType);
        if(maxType < 65535) {
            System.out.println("the maximum block type fits into a 16 bits integer, leaf nodes at full depth could be made more compact");
            System.out.printf("size saved by such optimization: %s (%.2f%% of size needed for total)\n", formatSize(leafNodesAtFullDepth * 2), (double) leafNodesAtFullDepth * 2 / (totalNodes * 4) * 100);
        } else {
            System.out.println("the maximum block type does NOT fit into a 16 bits integer, leaf nodes at full depth could not be made more compact");
        }

        show("number of unique 2x2x2 group", fullDepthSiblingsDictionary.size() / 8);
        showSize("size used by the group dictionary (if using 32 bits)", (long)fullDepthSiblingsDictionary.size()*4);
        showSize("size used by the group dictionary (if using 16 bits)", (long)fullDepthSiblingsDictionary.size()*2);
        showSize("memory saved by using a dictionary (if leaf were stored by 32 bits)", (long)leafNodesAtFullDepth / 8 * 7 * 4);
        showSize("memory saved by using a dictionary (if leaf were stored by 16 bits)", (long)leafNodesAtFullDepth / 4 * 3 * 4);
        showSize("memory difference if using a dictionary (with 32 bits types)", (long)fullDepthSiblingsDictionary.size()*4 - (long)leafNodesAtFullDepth / 8 * 7 * 4);
        showSize("memory difference if using a dictionary (with 16 bits types)", (long)fullDepthSiblingsDictionary.size()*2 - (long)leafNodesAtFullDepth / 4 * 3 * 4);


        System.out.println("Block type histogram (type: number of occurrence):");
        for(int i = 0; i < typeHistogram.length; ++i) {
            if(typeHistogram[i] > 0) {
                System.out.printf("%d: %d\n", i, typeHistogram[i]);
            }
        }
    }

    private void computeStatForNode(int nodeIndex, int currentDepth, int[] typeHistogram) {
        totalNodes += 8;
        if(currentDepth >= depth) {
            leafNodesAtFullDepth += 8;
            int childrenIndex = treeData[nodeIndex];
            FullDepthSiblings siblings = new FullDepthSiblings(
                    treeData[childrenIndex + 0],
                    treeData[childrenIndex + 1],
                    treeData[childrenIndex + 2],
                    treeData[childrenIndex + 3],
                    treeData[childrenIndex + 4],
                    treeData[childrenIndex + 5],
                    treeData[childrenIndex + 6],
                    treeData[childrenIndex + 7]
            );
            if(!siblingsToIndex.containsKey(siblings)) {
                siblingsToIndex.put(siblings, fullDepthSiblingsDictionary.size());
                for(int i = 0; i < 8; i++) {
                    fullDepthSiblingsDictionary.add(treeData[childrenIndex+i]);
                }
            }
        }
        for(int i = 0; i < 8; ++i) {
            int childIndex = treeData[nodeIndex] + i;
            if(treeData[childIndex] > 0) {
                computeStatForNode(childIndex, currentDepth + 1, typeHistogram);
                branchNode++;
            } else {
                leafNodes++;
                if(-treeData[childIndex] != ANY_TYPE) {
                    int type = -treeData[childIndex];
                    maxType = Math.max(maxType, type);
                    if(type < typeHistogram.length)
                        typeHistogram[type]++;
                    else
                        System.err.println("Error while computing statistics");
                }
            }
        }
    }

    static public void initImplementation() {
        Octree.addImplementationFactory("STATS", new ImplementationFactory() {
            @Override
            public OctreeImplementation create(int depth) {
                return new StatsOctree(depth);
            }

            @Override
            public OctreeImplementation load(DataInputStream in) throws IOException {
                return StatsOctree.load(in);
            }

            @Override
            public OctreeImplementation loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
                return StatsOctree.loadWithNodeCount(nodeCount, in);
            }

            @Override
            public boolean isOfType(OctreeImplementation implementation) {
                return implementation instanceof StatsOctree;
            }

            @Override
            public String getDescription() {
                return "Same as PACKED but computes some statistics during loading";
            }
        });
    }
}
