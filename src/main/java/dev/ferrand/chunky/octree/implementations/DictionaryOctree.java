package dev.ferrand.chunky.octree.implementations;

import se.llbit.math.Octree;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static se.llbit.math.Octree.*;

public class DictionaryOctree extends AbstractOctreeImplementation {
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

  private Map<Group, Integer> indexForGroup = new HashMap<>();

  private static final class NodeId implements Octree.NodeId {
    int nodeIndex;
    int currentDepth;

    public NodeId(int nodeIndex, int currentDepth) {
      this.nodeIndex = nodeIndex;
      this.currentDepth = currentDepth;
    }
  }

  private static int smallToBig(short smallType) {
    int type = ((int)smallType) & 0xFFFF;
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
    NodeId n = (NodeId)node;
    return n.currentDepth < depth && treeData[n.nodeIndex] > 0;
  }

  @Override
  public Octree.NodeId getChild(Octree.NodeId parent, int childNo) {
    NodeId p = (NodeId)parent;
    return new NodeId(treeData[p.nodeIndex] + childNo, p.currentDepth+1);
  }

  @Override
  public int getType(Octree.NodeId node) {
    NodeId n = (NodeId)node;
    if(n.currentDepth == depth) {
      return smallToBig(dict[n.nodeIndex]);
    }
    else
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
    Group group = Group.fromSingleType(bigToSmall(type));
    int childrenIndexDict = findGroup(group);
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

  private int findGroup(Group group) {
    Integer index = indexForGroup.get(group);
    if(index == null) {
      int dictIndex = findSpaceDict();
      dict[dictIndex] = group.type0;
      dict[dictIndex+1] = group.type1;
      dict[dictIndex+2] = group.type2;
      dict[dictIndex+3] = group.type3;
      dict[dictIndex+4] = group.type4;
      dict[dictIndex+5] = group.type5;
      dict[dictIndex+6] = group.type6;
      dict[dictIndex+7] = group.type7;
      indexForGroup.put(group, dictIndex);
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
      previousGroup[i] = dict[groupIndex+i];
    previousGroup[position] = shortType;
    int newGroupIndex = findGroup(Group.fromArray(previousGroup));
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

//  private int getNodeIndex(int x, int y, int z) {
//    int nodeIndex = 0;
//    int level = depth;
//    while(treeData[nodeIndex] > 0) {
//      level -= 1;
//      int lx = x >>> level;
//      int ly = y >>> level;
//      int lz = z >>> level;
//      nodeIndex = treeData[nodeIndex] + (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1));
//    }
//    return nodeIndex;
//  }
//
//  @Override
//  public Node get(int x, int y, int z) {
//    int nodeIndex = getNodeIndex(x, y, z);
//
//    Node node = new Node(treeData[nodeIndex] > 0 ? BRANCH_NODE : -treeData[nodeIndex]);
//
//    // Return dummy Node, will work if only type and data are used, breaks if children are needed
//    return node;
//  }
//
//  @Override
//  public Material getMaterial(int x, int y, int z, BlockPalette palette) {
//    // Building the dummy node is useless here
//    int nodeIndex = getNodeIndex(x, y, z);
//    if(treeData[nodeIndex] > 0) {
//      return UnknownBlock.UNKNOWN;
//    }
//    return palette.get(-treeData[nodeIndex]);
//  }

  @Override
  public int getDepth() {
    return depth;
  }

  public static DictionaryOctree load(DataInputStream in) throws IOException {
    int depth = in.readInt();
    DictionaryOctree tree = new DictionaryOctree(depth);
    tree.loadNode(in, 0);
    return tree;
  }

  public static DictionaryOctree loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
    int depth = in.readInt();
    DictionaryOctree tree = new DictionaryOctree(depth, nodeCount);
    tree.loadNode(in, 0);
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
    indexForGroup = null;
  }

  private void finalizationNode(int nodeIndex, int currentDepth) {
    if(currentDepth == depth) {
      boolean canMerge = true;
      short mergedType = (short)SMALL_ANY_TYPE;
      for(int i = 0; i < 8; ++i) {
        int childIndex = treeData[nodeIndex] + i;
        if(canMerge) {
          if(mergedType == SMALL_ANY_TYPE) {
            mergedType = dict[childIndex];
          } else if(!(dict[childIndex] == SMALL_ANY_TYPE || (dict[childIndex] == mergedType))) {
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
