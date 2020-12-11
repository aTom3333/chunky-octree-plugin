package dev.ferrand.chunky.octree.implementations;

import org.apache.commons.math3.util.Pair;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.world.Material;
import se.llbit.math.Octree;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static se.llbit.math.Octree.*;

public class DictionaryOctree implements OctreeImplementation {
  private int[] treeData;

  private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 16;

  private int size;

  private int freeHead;

  private int depth;

  private short[] dict;

  private int dictSize;

  private static int SMALL_ANY_TYPE = 0xFFFF;

  private static final class Group {
    short type0, type1, type2, type3, type4, type5, type6, type7;

    public Group(short type0, short type1, short type2, short type3, short type4, short type5, short type6, short type7) {
      this.type0 = type0;
      this.type1 = type1;
      this.type2 = type2;
      this.type3 = type3;
      this.type4 = type4;
      this.type5 = type5;
      this.type6 = type6;
      this.type7 = type7;
    }

    @Override
    public boolean equals(Object o) {
      if(this == o) return true;
      if(o == null || getClass() != o.getClass()) return false;
      Group group = (Group) o;
      return type0 == group.type0 &&
              type1 == group.type1 &&
              type2 == group.type2 &&
              type3 == group.type3 &&
              type4 == group.type4 &&
              type5 == group.type5 &&
              type6 == group.type6 &&
              type7 == group.type7;
    }

    @Override
    public int hashCode() {
      return Objects.hash(type0, type1, type2, type3, type4, type5, type6, type7);
    }

    public static Group fromArray(short[] array) {
      return new Group(array[0], array[1], array[2], array[3], array[4], array[5], array[6], array[7]);
    }

    public static Group fromSingleType(short type) {
      return new Group(type, type, type, type, type, type, type, type);
    }
  }

  //private Map<Group, Integer> indexForGroup = new HashMap<>();

  private int[] hashMapData;
  private int hashMapSize;

  private static final class NodeId implements Octree.NodeId {
    int nodeIndex;
    int currentDepth;

    public NodeId(int nodeIndex, int currentDepth) {
      this.nodeIndex = nodeIndex;
      this.currentDepth = currentDepth;
    }
  }

  private static int smallToBig(short smallType) {
    int type = ((int) smallType) & 0xFFFF;
    if(type == SMALL_ANY_TYPE)
      type = ANY_TYPE;
    return type;
  }

  private static short bigToSmall(int type) {
    if(type != ANY_TYPE && type > 0xFFFF)
      throw new OctreeTooBigException();
    short smallType = (short) (type == ANY_TYPE ? SMALL_ANY_TYPE : type);
    return smallType;
  }

  @Override
  public Octree.NodeId getRoot() {
    return new NodeId(0, 0);
  }

  @Override
  public boolean isBranch(Octree.NodeId node) {
    NodeId n = (NodeId) node;
    return n.currentDepth < depth && treeData[n.nodeIndex] > 0;
  }

  @Override
  public Octree.NodeId getChild(Octree.NodeId parent, int childNo) {
    NodeId p = (NodeId) parent;
    return new NodeId(treeData[p.nodeIndex] + childNo, p.currentDepth + 1);
  }

  @Override
  public int getType(Octree.NodeId node) {
    NodeId n = (NodeId) node;
    if(n.currentDepth == depth) {
      return smallToBig(dict[n.nodeIndex]);
    } else
      return -treeData[n.nodeIndex];
  }

  @Override
  public int getData(Octree.NodeId node) {
    return 0;
  }

  public static class OctreeTooBigException extends RuntimeException {
  }

  public DictionaryOctree(int depth, long nodeCount) {
    this.depth = depth;
    long arraySize = Math.max(nodeCount, 64);
    if(arraySize > (long) MAX_ARRAY_SIZE)
      throw new OctreeTooBigException();
    treeData = new int[(int) arraySize];
    treeData[0] = 0;
    size = 1;
    freeHead = -1; // No holes
    dict = new short[64];
    dictSize = 0;
    initHashMap();
  }

  public DictionaryOctree(int depth) {
    this.depth = depth;
    treeData = new int[1024];
    // Add a root node
    treeData[0] = 0;
    size = 1;
    freeHead = -1;
    dict = new short[64];
    dictSize = 0;
    initHashMap();
  }

  private void initHashMap() {
    hashMapData = new int[64];
    for(int i = 0; i < hashMapData.length; ++i)
      hashMapData[i] = -1; // -1 means empty bucket
    hashMapSize = 0;
  }

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
    int[] newArray = new int[(int) newSize];
    System.arraycopy(treeData, 0, newArray, 0, size);
    treeData = newArray;
    // and then append
    int index = size;
    size += 8;
    return index;
  }

  private int findSpaceDict() {
    // append in array if we have the capacity
    if(dictSize + 8 <= dict.length) {
      int index = dictSize;
      dictSize += 8;
      return index;
    }

    // We need to grow the array
    long newSize = (long) Math.ceil(dict.length * 1.5);
    // We need to check the array won't be too big
    if(newSize > (long) MAX_ARRAY_SIZE) {
      // We can allocate less memory than initially wanted if the next block will still be able to fit
      // If not, this implementation isn't suitable
      if(MAX_ARRAY_SIZE - dict.length > 8) {
        // If by making the new array be of size MAX_ARRAY_SIZE we can still fit the block requested
        newSize = MAX_ARRAY_SIZE;
      } else {
        // array is too big
        throw new se.llbit.math.PackedOctree.OctreeTooBigException();
      }
    }
    short[] newArray = new short[(int) newSize];
    System.arraycopy(dict, 0, newArray, 0, dictSize);
    dict = newArray;
    // and then append
    int index = dictSize;
    dictSize += 8;
    return index;
  }

  private void freeSpace(int index) {
    treeData[index] = freeHead;
    freeHead = index;
  }

  private void subdivideNode(int nodeIndex) {
    int childrenIndex = findSpace();
    for(int i = 0; i < 8; ++i) {
      treeData[childrenIndex + i] = treeData[nodeIndex]; // copy type
    }
    treeData[nodeIndex] = childrenIndex; // Make the node a parent node pointing to its children
  }

  private void subdivideNodeIntoDict(int nodeIndex) {
    int type = -treeData[nodeIndex];
    short t = bigToSmall(type);
    int childrenIndexDict = findGroup(t, t, t, t, t, t, t, t);
    treeData[nodeIndex] = childrenIndexDict; // Make the node a parent node pointing to its children
  }

  private void mergeNode(int nodeIndex, int typeNegation) {
    int childrenIndex = treeData[nodeIndex];
    freeSpace(childrenIndex); // Delete children
    treeData[nodeIndex] = typeNegation; // Make the node a leaf one
  }

  private boolean nodeEquals(int firstNodeIndex, int secondNodeIndex) {
    return treeData[firstNodeIndex] == treeData[secondNodeIndex]; // compare types
  }

  private boolean nodeEquals(int firstNodeIndex, Node secondNode) {
    boolean firstIsBranch = treeData[firstNodeIndex] > 0;
    boolean secondIsBranch = (secondNode.type == BRANCH_NODE);
    return ((firstIsBranch && secondIsBranch) || -treeData[firstNodeIndex] == secondNode.type); // compare types (don't forget that in the tree the negation of the type is stored)
  }

  private int hashMapHashGroup(short type0, short type1, short type2, short type3, short type4, short type5, short type6, short type7) {
    // Simplest hash ever?
    int hash = 7;
    hash = ((hash << 5) - hash) + type0;
    hash = ((hash << 5) - hash) + type1;
    hash = ((hash << 5) - hash) + type2;
    hash = ((hash << 5) - hash) + type3;
    hash = ((hash << 5) - hash) + type4;
    hash = ((hash << 5) - hash) + type5;
    hash = ((hash << 5) - hash) + type6;
    hash = ((hash << 5) - hash) + type7;
    hash %= hashMapData.length;
    hash += hashMapData.length;
    hash %= hashMapData.length;
    return hash;
  }

  private int hashMapGetIndexForGroup(short type0, short type1, short type2, short type3, short type4, short type5, short type6, short type7) {
    int indexInHashMap = hashMapHashGroup(type0, type1, type2, type3, type4, type5, type6, type7);
    while(true) {
      // Hash map using open addressing with the simplest method ever, if a bucket is taken, try the next
      int groupIndex = hashMapData[indexInHashMap];
      if(groupIndex == -1)
        return -1; // Not found
      if(type0 == dict[groupIndex]
        && type1 == dict[groupIndex + 1]
        && type2 == dict[groupIndex + 2]
        && type3 == dict[groupIndex + 3]
        && type4 == dict[groupIndex + 4]
        && type5 == dict[groupIndex + 5]
        && type6 == dict[groupIndex + 6]
        && type7 == dict[groupIndex + 7]
      ) {
        return groupIndex;
      } else {
        indexInHashMap = (indexInHashMap + 1) % hashMapData.length;
      }
    }
  }

  private void hashMapInsertIndexForGroupWithoutCheckingCapacity(short type0, short type1, short type2, short type3, short type4, short type5, short type6, short type7, int dictIndex) {
    int indexInHashMap = hashMapHashGroup(type0, type1, type2, type3, type4, type5, type6, type7);
    while(true) {
      if(hashMapData[indexInHashMap] == -1) {
        hashMapData[indexInHashMap] = dictIndex;
        return;
      }
      indexInHashMap = (indexInHashMap+1) % hashMapData.length;
    }
  }

  private void hashMapPutIndexForGroup(short type0, short type1, short type2, short type3, short type4, short type5, short type6, short type7, int dictIndex) {
    if(hashMapSize > hashMapData.length * 0.75) {
      // Allocate more and rehash everything
      int newHashMapCapacity = (int) (hashMapData.length * 1.5);
      hashMapData = new int[newHashMapCapacity]; // No need to keep the old one around while rehashing because every element is already in dict
      // Every bucket is empty
      Arrays.fill(hashMapData, -1);
      for(int i = 0; i < dictSize; i+=8) {
        hashMapInsertIndexForGroupWithoutCheckingCapacity(
                dict[i],
                dict[i+1],
                dict[i+2],
                dict[i+3],
                dict[i+4],
                dict[i+5],
                dict[i+6],
                dict[i+7],
                i
        );
      }
      // No need to add the one passed in parameter as it has already been added to dict
    } else {
      hashMapInsertIndexForGroupWithoutCheckingCapacity(type0, type1, type2, type3, type4, type5, type6, type7, dictIndex);
    }
    ++hashMapSize;
  }

  private int findGroup(short type0, short type1, short type2, short type3, short type4, short type5, short type6, short type7) {
    int index = hashMapGetIndexForGroup(type0, type1, type2, type3, type4, type5, type6, type7);
    if(index == -1) {
      int dictIndex = findSpaceDict();
      dict[dictIndex] = type0;
      dict[dictIndex + 1] = type1;
      dict[dictIndex + 2] = type2;
      dict[dictIndex + 3] = type3;
      dict[dictIndex + 4] = type4;
      dict[dictIndex + 5] = type5;
      dict[dictIndex + 6] = type6;
      dict[dictIndex + 7] = type7;
      hashMapPutIndexForGroup(type0, type1, type2, type3, type4, type5, type6, type7, dictIndex);
      return dictIndex;
    }
    return index;
  }

  @Override
  public void set(int type, int x, int y, int z) {
    set(new Node(type), x, y, z);
  }

  @Override
  public void set(Node data, int x, int y, int z) {
    int[] parents = new int[depth]; // better to put as a field to preventallocation at each invocation?
    int nodeIndex = 0;
    int parentLevel = depth - 1;
    int position = 0;
    for(int i = depth - 1; i >= 0; --i) {
      parents[i] = nodeIndex;

      if(nodeEquals(nodeIndex, data)) {
        return;
      } else if(treeData[nodeIndex] <= 0) { // It's a leaf node
        if(i == 0) {
          subdivideNodeIntoDict(nodeIndex);
        } else {
          subdivideNode(nodeIndex);
        }
        parentLevel = i;
      }
      int xbit = 1 & (x >> i);
      int ybit = 1 & (y >> i);
      int zbit = 1 & (z >> i);
      position = (xbit << 2) | (ybit << 1) | zbit;
      nodeIndex = treeData[nodeIndex] + position;
    }
    int groupIndex = treeData[parents[0]];
    short shortType = bigToSmall(data.type);
    short[] previousGroup = new short[8];
    for(int i = 0; i < 8; ++i)
      previousGroup[i] = dict[groupIndex + i];
    previousGroup[position] = shortType;
    int newGroupIndex = findGroup(
            previousGroup[0],
            previousGroup[1],
            previousGroup[2],
            previousGroup[3],
            previousGroup[4],
            previousGroup[5],
            previousGroup[6],
            previousGroup[7]
    );
    treeData[parents[0]] = newGroupIndex;

    // Merge nodes where all children have been set to the same type.
    for(int i = 0; i <= parentLevel; ++i) {
      int parentIndex = parents[i];

      if(i == 0) {
        boolean allSame = true;
        short first = dict[newGroupIndex];
        for(int j = 1; j < 8; ++j) {
          int childType = dict[newGroupIndex + j];
          if(childType != first) {
            allSame = false;
            break;
          }
        }

        if(allSame) {
          // Can't free the space in the dictionary because it might be used by others groups
          // Ideally, we would keep a reference count around
          int type = smallToBig(first);
          treeData[parents[0]] = -type;
          nodeIndex = parents[0];
        } else {
          break;
        }
      } else {
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
          nodeIndex = parentIndex;
        } else {
          break;
        }
      }
    }
  }

  private int getType(int x, int y, int z) {
    int level = depth;
    int nodeIndex = 0;
    int nodeValue = treeData[nodeIndex];
    while(nodeValue > 0 && level != 1) {
      level -= 1;
      int lx = x >>> level;
      int ly = y >>> level;
      int lz = z >>> level;
      nodeIndex = nodeValue + (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1));
      nodeValue = treeData[nodeIndex];
    }
    if(nodeValue <= 0)
      return -nodeValue;

    int xbit = x & 1;
    int ybit = y & 1;
    int zbit = z & 1;
    int childIndex = nodeValue + ((xbit << 2) | (ybit << 1) | zbit);
    return smallToBig(dict[childIndex]);
  }

  @Override
  public Pair<Octree.NodeId, Integer> getWithLevel(int x, int y, int z) {
    int level = depth;
    int nodeIndex = 0;
    int nodeValue = treeData[nodeIndex];
    while(nodeValue > 0 && level != 1) {
      level -= 1;
      int lx = x >>> level;
      int ly = y >>> level;
      int lz = z >>> level;
      nodeIndex = nodeValue + (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1));
      nodeValue = treeData[nodeIndex];
    }
    if(nodeValue <= 0)
      return new Pair<>(new NodeId(nodeIndex, depth - level), level);

    int xbit = x & 1;
    int ybit = y & 1;
    int zbit = z & 1;
    int childIndex = nodeValue + ((xbit << 2) | (ybit << 1) | zbit);
    return new Pair<>(new NodeId(childIndex, depth), 0);
  }

  @Override
  public Octree.Node get(int x, int y, int z) {
    return new Octree.Node(getType(x, y, z));
  }

  @Override
  public Material getMaterial(int x, int y, int z, BlockPalette palette) {
    int type = getType(x, y, z);
    return palette.get(type);
  }

  @Override
  public int getDepth() {
    return depth;
  }

  public static DictionaryOctree load(DataInputStream in) throws IOException {
    int depth = in.readInt();
    DictionaryOctree tree = new DictionaryOctree(depth);
    tree.loadNode(in, 0, 1);
    tree.hashMapData = null;
    return tree;
  }

  public static DictionaryOctree loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
    int depth = in.readInt();
    DictionaryOctree tree = new DictionaryOctree(depth, nodeCount);
    tree.loadNode(in, 0, 1);
    tree.hashMapData = null;
    return tree;
  }

  private void loadNode(DataInputStream in, int nodeIndex, int currentDepth) throws IOException {
    int type = in.readInt();
    if(type == BRANCH_NODE) {
      if(currentDepth == depth) {
        int childrenIndex = findGroup(
                bigToSmall(in.readInt()),
                bigToSmall(in.readInt()),
                bigToSmall(in.readInt()),
                bigToSmall(in.readInt()),
                bigToSmall(in.readInt()),
                bigToSmall(in.readInt()),
                bigToSmall(in.readInt()),
                bigToSmall(in.readInt())
        );
        treeData[nodeIndex] = childrenIndex;
      } else {
        int childrenIndex = findSpace();
        treeData[nodeIndex] = childrenIndex;
        for(int i = 0; i < 8; ++i) {
          loadNode(in, childrenIndex + i, currentDepth + 1);
        }
      }
    } else {
      if((type & DATA_FLAG) == 0) {
        treeData[nodeIndex] = -type; // negation of type
      } else {
        int data = in.readInt();
        treeData[nodeIndex] = -(type ^ DATA_FLAG);
      }
    }
  }

  @Override
  public void endFinalization() {
    // There is a bunch of ANY_TYPE nodes we should try to merge
    finalizationNode(0, 1);
    // The hashmap is no longer needed here
    hashMapData = null;
  }

  private void finalizationNode(int nodeIndex, int currentDepth) {
    if(currentDepth == depth) {
      boolean canMerge = true;
      short mergedType = (short) SMALL_ANY_TYPE;
      for(int i = 0; i < 8; ++i) {
        int childIndex = treeData[nodeIndex] + i;
        if(canMerge) {
          if(mergedType == (short) SMALL_ANY_TYPE) {
            mergedType = dict[childIndex];
          } else if(!(dict[childIndex] == (short) SMALL_ANY_TYPE || (dict[childIndex] == mergedType))) {
            canMerge = false;
          }
        }
      }
      if(canMerge) {
        treeData[nodeIndex] = -smallToBig(mergedType);
      }
    } else {
      boolean canMerge = true;
      int mergedType = -ANY_TYPE;
      for(int i = 0; i < 8; ++i) {
        int childIndex = treeData[nodeIndex] + i;
        if(treeData[childIndex] > 0) {
          finalizationNode(childIndex, currentDepth + 1);
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
  }

  @Override
  public void store(DataOutputStream out) throws IOException {
    out.writeInt(getDepth());
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

  static public void initImplementation() {
    Octree.addImplementationFactory("DICTIONARY", new ImplementationFactory() {
      @Override
      public OctreeImplementation create(int depth) {
        return new DictionaryOctree(depth);
      }

      @Override
      public OctreeImplementation load(DataInputStream in) throws IOException {
        return DictionaryOctree.load(in);
      }

      @Override
      public OctreeImplementation loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
        return DictionaryOctree.loadWithNodeCount(nodeCount, in);
      }

      @Override
      public boolean isOfType(OctreeImplementation implementation) {
        return implementation instanceof DictionaryOctree;
      }

      @Override
      public String getDescription() {
        return "Similar to PACKED but uses a dictionary to deduplicate similar 2*2*2 blocks groups.";
      }
    });
  }
}
