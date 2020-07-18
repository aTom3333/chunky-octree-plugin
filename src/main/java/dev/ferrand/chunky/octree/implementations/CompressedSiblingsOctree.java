package dev.ferrand.chunky.octree.implementations;

import dev.ferrand.chunky.octree.utils.BitReader;
import dev.ferrand.chunky.octree.utils.BitWriter;
import dev.ferrand.chunky.octree.utils.DynamicByteArray;
import se.llbit.chunky.PersistentSettings;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.world.Material;
import se.llbit.log.Log;
import se.llbit.math.Octree;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class CompressedSiblingsOctree implements Octree.OctreeImplementation {
    /**
     * This works by grouping together siblings nodes into group and use a small representation fo this group
     * Instead of being a tree of node where each node can have 0 or 8 children, and holding data
     * for 0 or 1 block (or cube of the same blocks)
     * we can see this tree as being a tree of groups where each group can have n children
     * with n between 0 and 8, and data for 8-n blocks (or cube of the same blocks)
     *
     * So a group consist of an ordered collection of 8 nodes (that are siblings). Nodes can either be
     * a branch or a leaf, when a node is a branch, it holds an index to its children siblings group,
     * when it is a leaf it needs a type and eventually some additional data.
     * The type is the id of the block the node represents. Each type of block has its own id and
     * id are allocated linearly when needed, that is if the scene contains 500 distinct block types
     * id will span for 0 to 499. In this representation we choose to represent the type by a 16 bits integer
     * as most scene won't have that much type of block.
     * The data is a 32 bits integer that can sometimes be needed, when data is not needed, we can consider
     * it being 0. When looking at the data distribution, we see that the value 0 (sometime representing 0,
     * most of the time meaning no data) is the most common by a big margin. The second most common value
     * is 65536 (0x10000) (because it is used to represent a full water block and those are common).
     * We use this knowledge to optimally represents those 2 values.
     * The data of all leaf nodes of the group is packed together in a bit stream.
     * The decoding is as follow: read one bit, if it is 0, the data is 0 and next bit will be the start of the next encoded data.
     * If it is 1, read the next bit, if this one is 0, the data is 65536 and next bit will be the start of the next encoded data.
     * If the second bit was 1 too, read the next 14 bits and interpret them as an index into the data dictionary
     * (a simple int array).
     * When the node is a branch node, we use a 32 bits integer to hold the index of the first byte
     * of its children siblings group
     *
     * The full representation of a compressed siblings group is as follow:
     *  - One byte that describes if each node of the group is a branch or a leaf.
     *    One bit represents one node, 1 means the node is a branch, 0 means the node is a leaf.
     *    For example the byte 00101000 means nodes 2 and 4 are branches and the other are leaves.
     *  - 4*n bytes representing indexes where n is the number of branch nodes in the group
     *    (the number of 1 bits in the first byte). Each 4 bytes field is to be read as a big endian
     *    32 bits integer giving the index of the children siblings group
     *  - 2*(8-n) bytes representing the types (n is the number of branch). Each 2 bytes field is to be
     *    interpreted as a big endian 16 bits integer giving the type
     *  - some bytes containing 8-n encoded data. Those bytes must be read as a bit stream and used to decode
     *    the data as described earlier. The bit stream is padded to the next byte. The size taken the encoded
     *    data cannot be known without decoding it.
     *
     *
     *  This tree implementation doesn't lend itself to being writable (aka being usable for loading chunks)
     *  because when a tree is writable, some of its leaf nodes will be converted to branch nodes (and vice-versa but
     *  that less common) but with this representation that would mean changing the size of the sibling group,
     *  potentially needing to moving it if the next group in memory is too close, creating holes of variable size
     *  that would need to be filled later with new groups. Essentially that would mean implementing an almost
     *  full blown allocating algorithm.
     *  On top of that, at every change the data bit stream would need to be recomputed, worsening the performances
     */

    DynamicByteArray treeData = new DynamicByteArray();
    int depth;
    int[] dataDict;
    int dataDictSize;
    long rootChildrenIndex;

    private final int bytesForBranch = PersistentSettings.settings.getInt("compressedSiblings.bytesForIndex", 4);
    private final int bytesForType = PersistentSettings.settings.getInt("compressedSiblings.bytesForType", 2);
    private final int bitForData = PersistentSettings.settings.getInt("compressedSiblings.bitsForData", 14);

    /**
     * Checks that a given value fits in a given number of bits
     */
    public static boolean checkFitsBits(long value, int bits) {
        long mask = -(1L << bits);
        return (value & mask) == 0;
    }
    /**
     * Checks that a given value fits in a given number of bytes
     */
    public static boolean checkFitsBytes(long value, int bytes) {
        return checkFitsBits(value, bytes * 8);
    }

    private static class SiblingInfo {
        public boolean isBranch = true;
        public long childrenIndex = 0;
        public int type = 0;
        public int data = 0;

        public void makeBranch(long childrenIndex) {
            isBranch = true;
            this.childrenIndex = childrenIndex;
        }

        public void makeLeaf(int type, int data) {
            isBranch = false;
            this.type = type;
            this.data = data;
        }

        public int compressedBits(int sizeBranch, int sizeType, int sizeData) {
            int total = 1; // For that encode if this is a branch or not
            if(isBranch) {
                total += 8 * sizeBranch; // sizeBranch bytes for the index
            } else {
                total += 8 * sizeType; // sizeType bytes for the type
                if(data == 0) {
                    total += 1; // 0 is stored as 0
                } else if(data == 65536) {
                    total += 2; // 65536 is stored as 10
                } else {
                    total += 2 + sizeData; // other values are stored as 11 + a sizeData bits index
                }
            }

            return total;
        }
    }

    private static class UncompressedSiblings {
        SiblingInfo[] siblings;

        public UncompressedSiblings() {
            siblings = new SiblingInfo[8];
            for(int i = 0; i < 8; ++i) {
                siblings[i] = new SiblingInfo();
            }
        }

        public long compress(CompressedSiblingsOctree dest, Map<Integer, Integer> dataToDataIndex) {
            int sizeInBits = 0;
            for(int i = 0; i < 8; ++i) {
                sizeInBits += siblings[i].compressedBits(dest.bytesForBranch, dest.bytesForType, dest.bitForData);
            }
            int sizeInBytes = (sizeInBits + 7) / 8;

            // Do the stuff
            int branchCounter = 0;
            int leafCounter = 0;
            byte firstByte = 0;
            byte[] indexArray = new byte[dest.bytesForBranch*8];
            byte[] typeArray = new byte[dest.bytesForType*8];
            BitWriter dataWriter = new BitWriter();

            for(int i = 0; i < 8; ++i) {
                if(siblings[i].isBranch) {
                    if(!checkFitsBytes(siblings[i].childrenIndex, dest.bytesForBranch)) {
                        String msg = String.format("Not enough bytes to store an index (%d). Change the number of bytes for index in the Advanced Octree Options tab.", siblings[i].childrenIndex);
                        System.out.println(siblings[i].childrenIndex);
                        Log.error(msg);
                        throw new RuntimeException(msg);
                    }

                    for(int j = 0; j < dest.bytesForBranch; ++j) {
                        indexArray[dest.bytesForBranch*branchCounter + j] = (byte)((siblings[i].childrenIndex >>> ((dest.bytesForBranch - 1 - j) * 8)) & 0xFF);
                    }
                    ++branchCounter;
                    firstByte |= (1 << (7 - i));
                } else {
                    if(!checkFitsBytes(siblings[i].type, dest.bytesForType)) {
                        String msg = String.format("Not enough bytes to store a type (%d). Change the number of bytes for type in the Advanced Octree Options tab.", siblings[i].type);
                        Log.error(msg);
                        throw new RuntimeException(msg);
                    }

                    for(int j = 0; j < dest.bytesForType; ++j) {
                        typeArray[dest.bytesForType*leafCounter + j] = (byte)((siblings[i].type >>> ((dest.bytesForType - 1 - j) * 8)) & 0xFF);
                    }
                    ++leafCounter;
                    int data = siblings[i].data;
                    if(data == 0) {
                        dataWriter.write(1, 0b0);
                    } else if(data == 65536) {
                        dataWriter.write(2, 0b10);
                    } else {
                        dataWriter.write(2, 0b11);
                        int dataIndex = dest.getDataIndex(data, dataToDataIndex);
                        if(!checkFitsBits(dataIndex, dest.bitForData)) {
                            String msg = "Not enough bits to store a data piece. Change the number of bits for data in the Advanced Octree Options tab.";
                            Log.error(msg);
                            throw new RuntimeException(msg);
                        }
                        dataWriter.write(dest.bitForData, dataIndex);
                    }
                }
            }

            assert branchCounter + leafCounter == 8;
            assert 1 + dest.bytesForBranch*branchCounter + dest.bytesForType*leafCounter + dataWriter.getSize() == sizeInBytes;

            // Write first byte
            long thisGroupIndex = dest.treeData.getSize();
            dest.treeData.pushBack(firstByte);

            // Write child indices
            dest.treeData.addElems(indexArray, dest.bytesForBranch*branchCounter);

            // Write types
            dest.treeData.addElems(typeArray, dest.bytesForType*leafCounter);

            // Write data
            dest.treeData.addElems(dataWriter.getData(), dataWriter.getSize());

            return thisGroupIndex;
        }
    }

    private int getDataIndex(int data, Map<Integer, Integer> dataToDataIndex) {
        if(dataToDataIndex.containsKey(data)) {
            int index = dataToDataIndex.get(data);
            assert dataDict[index] == data;
            return index;
        } else {
            ensureCapacityDataDict(1);
            int index = dataDictSize;
            ++dataDictSize;
            dataDict[index] = data;
            dataToDataIndex.put(data, index);
            return index;
        }
    }

    private interface NodeIdInterface extends Octree.NodeId {
        boolean isRoot();
    }

    private static final class NodeId implements NodeIdInterface {
        public long groupIndex;
        public int childNo;

        public NodeId(long groupIndex, int childNo) {
            this.groupIndex = groupIndex;
            this.childNo = childNo;
        }

        @Override
        public boolean isRoot() {
            return false;
        }
    }

    private static final class RootNodeId implements NodeIdInterface {
        @Override
        public boolean isRoot() {
            return true;
        }
    }

    public CompressedSiblingsOctree(int depth) {
        this.depth = depth;
        dataDict = new int[64];
        dataDictSize = 0;
    }

    private void ensureCapacityDataDict(int entries) {
        if(dataDict.length < dataDictSize + entries) {
            // Need reallocation
            int newCapacity = (int)Math.max(Math.floor(dataDict.length * 1.5), dataDictSize+entries);
            // TODO Handle overflow and stuff
            int[] newArray = new int[newCapacity];
            System.arraycopy(dataDict, 0, newArray, 0, dataDictSize);
            dataDict = newArray;
        }
    }

    private boolean isBranch(long groupIndex, int childNo) {
        // Use bits of first byte
        return ((treeData.get(groupIndex) >>> (7-childNo)) & 1) != 0;
    }

    private long getChildIndex(long groupIndex, int childNo) {
        int branchBeforeCount = 0;
        byte childrenTypes = treeData.get(groupIndex);
        for(int i = 0; i < childNo; ++i) {
            byte mask = (byte) (1 << 7-i);
            if((childrenTypes & mask) != 0) {
                ++branchBeforeCount;
            }
        }

        long childIndexIndex = groupIndex + 1 + bytesForBranch*branchBeforeCount;

        long childIndex = 0;
        for(int i = 0; i < bytesForBranch; ++i) {
            childIndex <<= 8;
            childIndex |= (treeData.get(childIndexIndex+i) & 0xFF);
        }

        return childIndex;
    }

    private int getTypeOnly(long groupIndex, int childNo) {
        int branchChildren = 0;
        int leafChildrenBefore = 0;
        byte childrenTypes = treeData.get(groupIndex);
        for(int i = 0; i < 8; ++i) {
            byte mask = (byte) (1 << 7-i);
            if((childrenTypes & mask) != 0) {
                ++branchChildren;
            } else if(i < childNo) {
                ++leafChildrenBefore;
            }
        }

        long typeIndex = groupIndex + 1 + bytesForBranch*branchChildren + bytesForType*leafChildrenBefore;

        int type = 0;
        for(int i = 0; i < bytesForType; ++i) {
            type <<= 8;
            type |= (treeData.get(typeIndex+i) & 0xFF);
        }

        return type;
    }

    private int extractData(BitReader bitReader) {
        byte firstBit = (byte) bitReader.read(1);
        if(firstBit == 0) {
            // 0 means data is 0
            return 0;
        }
        byte secondBit = (byte) bitReader.read(1);
        if(secondBit == 0) {
            // 01 means data is 65536
            return 65536;
        }
        // 11 means we need to read the next n bits and they will be an index in the dataDict array
        int dataIndex = (int) bitReader.read(bitForData);
        return dataDict[dataIndex];
    }

    private int getDataOnly(long groupIndex, int childNo) {
        int branchChildren = 0;
        int leafChildrenBefore = 0;
        byte childrenTypes = treeData.get(groupIndex);
        for(int i = 0; i < 8; ++i) {
            byte mask = (byte) (1 << 7-i);
            if((childrenTypes & mask) != 0) {
                ++branchChildren;
            } else if(i < childNo) {
                ++leafChildrenBefore;
            }
        }
        long startDataIndex = groupIndex + 1 + bytesForBranch*branchChildren + bytesForType*(8-branchChildren);
        byte[] bitstream = treeData.subArray(startDataIndex, ((2+bitForData) * 8 + 7) / 8);
        BitReader bitReader = new BitReader(bitstream, 0);
        // We need to read every data entry until the right one
        int data = 0;
        for(int i = 0; i <= leafChildrenBefore; ++i) {
            data = extractData(bitReader);
        }

        return data;
    }

    // TODO Make a function to get type and data that is more efficient than getting one at a time

    @Override
    public Octree.NodeId getRoot() {
        return new RootNodeId();
    }

    @Override
    public boolean isBranch(Octree.NodeId nodeId) {
        if(((NodeIdInterface)nodeId).isRoot()) {
            return true;
        } else {
            NodeId node = ((NodeId)nodeId);
            return isBranch(node.groupIndex, node.childNo);
        }
    }

    @Override
    public Octree.NodeId getChild(Octree.NodeId nodeId, int i) {
        if(((NodeIdInterface)nodeId).isRoot()) {
            return new NodeId(rootChildrenIndex, i);
        } else {
            NodeId node = ((NodeId)nodeId);
            long childGroupIndex = getChildIndex(node.groupIndex, node.childNo);
            return new NodeId(childGroupIndex, i);
        }
    }

    @Override
    public int getType(Octree.NodeId nodeId) {
        if(((NodeIdInterface)nodeId).isRoot()) {
            return 0;
        } else {
            NodeId node = ((NodeId)nodeId);
            return getTypeOnly(node.groupIndex, node.childNo);
        }
    }

    @Override
    public int getData(Octree.NodeId nodeId) {
        if(((NodeIdInterface)nodeId).isRoot()) {
            return 0;
        } else {
            NodeId node = ((NodeId)nodeId);
            return getDataOnly(node.groupIndex, node.childNo);
        }
    }

    @Override
    public void set(int type, int x, int y, int z) {
        Log.error("This octree doesn't support write operation. To use it you have to load an existing octree");
        throw new RuntimeException("Operation not supported");
    }

    @Override
    public void set(Octree.Node node, int x, int y, int z) {
        Log.error("This octree doesn't support write operation. To use it you have to load an existing octree");
        throw new RuntimeException("Operation not supported");
    }

    private Octree.NodeId getHelper(int x, int y, int z) {
        Octree.NodeId node = getRoot();
        int level = depth;
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
        out.writeInt(depth);
        storeNode(out, getRoot());
    }

    private void storeNode(DataOutputStream out, Octree.NodeId node) throws IOException {
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
    public int getDepth() {
        return depth;
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

    public static CompressedSiblingsOctree load(DataInputStream in) throws IOException {
        int depth = in.readInt();
        CompressedSiblingsOctree octree = new CompressedSiblingsOctree(depth);
        // Remove root data from the stream
        int rootType = in.readInt();
        if(rootType != Octree.BRANCH_NODE) {
            throw new RuntimeException("Leaf root node not supported by this octree implementation");
        }
        UncompressedSiblings[] ancestors = new UncompressedSiblings[depth];
        ancestors[0] = new UncompressedSiblings();
        Map<Integer, Integer> dataToDataIndex = new HashMap<>();
        for(int i = 0; i < 8; ++i) {
            octree.loadNode(in, 0, ancestors, i, dataToDataIndex);
        }
        long rootIndex = ancestors[0].compress(octree, dataToDataIndex);
        octree.rootChildrenIndex = rootIndex;

        return octree;
    }

    private void loadNode(DataInputStream in, int currentDepth, UncompressedSiblings[] ancestors, int childNumber, Map<Integer, Integer> dataToDataIndex) throws IOException {
        int type = in.readInt();
        if(type == Octree.BRANCH_NODE) {
            // Write in the siblings group this node is a part of that it is a branch
            ancestors[currentDepth].siblings[childNumber].makeBranch(-1);

            // Create new Siblings group for the children of this node
            ancestors[currentDepth+1] = new UncompressedSiblings();
            for(int i = 0; i < 8; ++i) {
                loadNode(in, currentDepth+1, ancestors, i, dataToDataIndex);
            }

            // The created siblings group is now complete and can be saved compressed
            long savedIndex = ancestors[currentDepth+1].compress(this, dataToDataIndex);
            ancestors[currentDepth].siblings[childNumber].childrenIndex = savedIndex;

        } else {
            int data = 0;
            if((type & Octree.DATA_FLAG) != 0) {
                type ^= Octree.DATA_FLAG;
                data = in.readInt();
            }
            if(type == Octree.ANY_TYPE) {
                type = 0; // Replace by anything but don't keep it because we can't store it
            }
            ancestors[currentDepth].siblings[childNumber].makeLeaf(type, data);
        }
    }

    static public void initImplementation() {
        Octree.addImplementationFactory("COMPRESSED_SIBLINGS", new Octree.ImplementationFactory() {
            @Override
            public Octree.OctreeImplementation create(int depth) {
                return new CompressedSiblingsOctree(depth);
            }

            @Override
            public Octree.OctreeImplementation load(DataInputStream in) throws IOException {
                return CompressedSiblingsOctree.load(in);
            }

            @Override
            public Octree.OctreeImplementation loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
                return CompressedSiblingsOctree.load(in);
            }

            @Override
            public boolean isOfType(Octree.OctreeImplementation octreeImplementation) {
                return octreeImplementation instanceof CompressedSiblingsOctree;
            }

            @Override
            public String getDescription() {
                return "More memory efficient tree, works by grouping together node siblings and compressing them. Limited in the size of scene, the number of different block that can be used or in the number of distinct data value possible.";
            }
        });
    }
}
