package dev.ferrand.chunky.octree;

import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.world.Material;
import se.llbit.log.Log;
import se.llbit.math.Octree;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class CompressedSiblingsOctree implements Octree.OctreeImplementation {
    // TODO Add nice comments explaining how the tree works

    byte[] treeData;
    int treeSize;
    int depth;
    int[] dataDict;
    int dataDictSize;
    int rootChildrenIndex;

    // TODO Can we implement a freeList with holes of variable size?

    private static class SiblingInfo {
        public boolean isBranch = true;
        public int childrenIndex = 0;
        public int type = 0;
        public int data = 0;

        public void makeBranch(int childrenIndex) {
            isBranch = true;
            this.childrenIndex = childrenIndex;
        }

        public void makeLeaf(int type, int data) {
            isBranch = false;
            this.type = type;
            this.data = data;
        }

        public int compressedBits() {
            int total = 1; // For that encode if this is a branch or not
            if(isBranch) {
                total += 32; // 4 bytes for the index
            } else {
                total += 16; // 2 bytes for the type
                if(data == 0) {
                    total += 1; // 0 is stored as 0
                } else if(data == 65536) {
                    total += 2; // 65536 is stored as 10
                } else {
                    total += 16; // other values are stored as 11 + a 14 bits index
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

        public int compress(CompressedSiblingsOctree dest, Map<Integer, Short> dataToDataIndex) {
            int sizeInBits = 0;
            for(int i = 0; i < 8; ++i) {
                sizeInBits += siblings[i].compressedBits();
            }
            int sizeInBytes = (sizeInBits + 7) / 8;
            dest.ensureCapacityTree(sizeInBytes);

            // Do the stuff
            int branchCounter = 0;
            int leafCounter = 0;
            byte firstByte = 0;
            byte[] indexArray = new byte[4*8];
            byte[] typeArray = new byte[2*8];
            BitWriter dataWriter = new BitWriter();

            for(int i = 0; i < 8; ++i) {
                if(siblings[i].isBranch) {
                    indexArray[4*branchCounter] = (byte) ((siblings[i].childrenIndex >>> 24) & 0xFF);
                    indexArray[4*branchCounter + 1] = (byte) ((siblings[i].childrenIndex >>> 16) & 0xFF);
                    indexArray[4*branchCounter + 2] = (byte) ((siblings[i].childrenIndex >>> 8) & 0xFF);
                    indexArray[4*branchCounter + 3] = (byte) ((siblings[i].childrenIndex) & 0xFF);
                    ++branchCounter;
                    firstByte |= (1 << (7 - i));
                } else {
                    typeArray[2*leafCounter] = (byte) (((siblings[i].type >>> 8)) & 0xFF);
                    typeArray[2*leafCounter + 1] = (byte) (((siblings[i].type)) & 0xFF);
                    ++leafCounter;
                    int data = siblings[i].data;
                    if(data == 0) {
                        dataWriter.write(1, 0b0);
                    } else if(data == 65536) {
                        dataWriter.write(2, 0b10);
                    } else {
                        dataWriter.write(2, 0b11);
                        short dataIndex = dest.getDataIndex(data, dataToDataIndex);
                        dataWriter.write(14, dataIndex);
                    }
                }
            }

            assert branchCounter + leafCounter == 8;
            assert 1 + 4*branchCounter + 2*leafCounter + dataWriter.getSize() == sizeInBytes;

            // Write first byte
            int index = dest.treeSize;
            int thisGroupIndex = index;
            dest.treeData[index] = firstByte;
            ++index;

            // Write child indices
            System.arraycopy(indexArray, 0, dest.treeData, index, 4*branchCounter);
            index += 4*branchCounter;

            // Write types
            System.arraycopy(typeArray, 0, dest.treeData, index, 2*leafCounter);
            index += 2*leafCounter;

            // Write data
            System.arraycopy(dataWriter.getData(), 0, dest.treeData, index, dataWriter.getSize());

            dest.treeSize += sizeInBytes;

            return thisGroupIndex;
        }
    }

    private short getDataIndex(int data, Map<Integer, Short> dataToDataIndex) {
        if(dataToDataIndex.containsKey(data)) {
            short index = dataToDataIndex.get(data);
            assert dataDict[index] == data;
            return index;
        } else {
            ensureCapacityDataDict(1);
            short index = (short) dataDictSize;
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
        public int groupIndex;
        public int childNo;

        public NodeId(int groupIndex, int childNo) {
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
        treeData = new byte[64];
        treeSize = 0;
        dataDict = new int[64];
        dataDictSize = 0;
    }

    private void ensureCapacityTree(int bytes) {
        if(treeData.length < treeSize + bytes) {
            // Need reallocation
            int newCapacity = (int)Math.max(Math.floor(treeData.length * 1.5), treeSize+bytes);
            // TODO Handle overflow and stuff
            byte[] newArray = new byte[newCapacity];
            System.arraycopy(treeData, 0, newArray, 0, treeSize);
            treeData = newArray;
        }
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

    private boolean isBranch(int groupIndex, int childNo) {
        // Use bits of first byte
        return ((treeData[groupIndex] >>> (7-childNo)) & 1) != 0;
    }

    private int getChildIndex(int groupIndex, int childNo) {
        int branchBeforeCount = 0;
        byte childrenTypes = treeData[groupIndex];
        for(int i = 0; i < childNo; ++i) {
            byte mask = (byte) (1 << 7-i);
            if((childrenTypes & mask) != 0) {
                ++branchBeforeCount;
            }
        }

        int childIndexIndex = groupIndex + 1 + 4*branchBeforeCount;

        int childIndex = ((treeData[childIndexIndex] << 24) & 0xFF000000)
                       | ((treeData[childIndexIndex+1] << 16) & 0xFF0000)
                       | ((treeData[childIndexIndex+2] << 8) & 0xFF00)
                       | (treeData[childIndexIndex+3] & 0xFF);
        return childIndex;
    }

    private int getTypeOnly(int groupIndex, int childNo) {
        int branchChildren = 0;
        int leafChildrenBefore = 0;
        byte childrenTypes = treeData[groupIndex];
        for(int i = 0; i < 8; ++i) {
            byte mask = (byte) (1 << 7-i);
            if((childrenTypes & mask) != 0) {
                ++branchChildren;
            } else if(i < childNo) {
                ++leafChildrenBefore;
            }
        }

        int typeIndex = groupIndex + 1 + 4*branchChildren + 2*leafChildrenBefore;

        int type = ((treeData[typeIndex] << 8) & 0xFF00) | (treeData[typeIndex+1] & 0xFF);

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
        // 11 means we need to read the next 14 bits and they will be an index in the dataDict array
        int dataIndex = (int) bitReader.read(14);
        return dataDict[dataIndex];
    }

    private int getDataOnly(int groupIndex, int childNo) {
        int branchChildren = 0;
        int leafChildrenBefore = 0;
        byte childrenTypes = treeData[groupIndex];
        for(int i = 0; i < 8; ++i) {
            byte mask = (byte) (1 << 7-i);
            if((childrenTypes & mask) != 0) {
                ++branchChildren;
            } else if(i < childNo) {
                ++leafChildrenBefore;
            }
        }
        int startDataIndex = groupIndex + 1 + 4*branchChildren + 2*(8-branchChildren);
        BitReader bitReader = new BitReader(treeData, startDataIndex);
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
            int childGroupIndex = getChildIndex(node.groupIndex, node.childNo);
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
        try {
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
        } catch(Exception e) {
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
        Map<Integer, Short> dataToDataIndex = new HashMap<>();
        for(int i = 0; i < 8; ++i) {
            octree.loadNode(in, 0, ancestors, i, dataToDataIndex);
        }
        int rootIndex = ancestors[0].compress(octree, dataToDataIndex);
        octree.rootChildrenIndex = rootIndex;

        return octree;
    }

    private void loadNode(DataInputStream in, int currentDepth, UncompressedSiblings[] ancestors, int childNumber, Map<Integer, Short> dataToDataIndex) throws IOException {
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
            int savedIndex = ancestors[currentDepth+1].compress(this, dataToDataIndex);
            if(savedIndex < 0) {
                Thread.dumpStack();
            }
            ancestors[currentDepth].siblings[childNumber].childrenIndex = savedIndex;

        } else {
            int data = 0;
            if((type & Octree.DATA_FLAG) != 0) {
                type ^= Octree.DATA_FLAG;
                data = in.readInt();
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
