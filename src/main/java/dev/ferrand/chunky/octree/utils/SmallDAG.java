package dev.ferrand.chunky.octree.utils;

import se.llbit.math.Octree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Function;

import static se.llbit.math.Octree.ANY_TYPE;

public class SmallDAG {
  /**
   * Where the node data is stored, as with other implementation
   * a negative value indicates a leaf and the type is the absolute value.
   * A positive value indicates a branch node and acts as a pointer
   * to the children (see below).
   * As with other implementations, nodes are store by group of 8 siblings.
   * Unlike other implementation, we make sure here that siblings groups are
   * always aligned on index that are a multiple of 8, this means that an index
   * that refers to a whole sibling group doesn't need to store the 3 lower bits.
   * This in turns mean that those bit can be reused to increase the number of
   * nodes that can be indexed by 15 bits from 2^15 to 2^18.
   * This means that branch nodes don't directly store the index of their children
   * but they store that index divided by 8 (shifted right by 3)
   */
  private short[] treeData;
  /**
   * Index of the head of the free list divided by 8
   * -1 if the free list is empty
   */
  private short freeHead;
  /**
   * Size of the treeData array (not divided by 8)
   */
  private int size;

  /**
   * The short representation of ANY_TYPE.
   * Chosen to fit in 15 bits (in a way to still be a positive number
   * when stored in a 16 bits integer)
   */
  private static final int SMALL_ANY_TYPE = 0x7FFF;

  /**
   * Hash map: hash of subtree -> index of subtree + number of use of the subtree
   * -1 represent an empty slot
   */
  private short[] indexMap;
  private short[] countMap;
  private int hashMapSize;

  private final double loadFactor = 0.75;

  private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 16;

  private static final int DEPTH = 5;

  public static class TypeToBigException extends RuntimeException {
  }

  private static int smallToBig(short smallType) {
    int type = ((int) smallType) & 0x7FFF;
    if(type == SMALL_ANY_TYPE)
      type = ANY_TYPE;
    return type;
  }

  private static short bigToSmall(int type) {
    if(type != ANY_TYPE && type > 0x7FFF)
      throw new TypeToBigException();
    return (short) (type == ANY_TYPE ? SMALL_ANY_TYPE : type);
  }


  public SmallDAG() {
    treeData = new short[64];
    treeData[0] = 1;
    size = 16;
    freeHead = -1;
    initHashMap(64);
    int childrenHash = hashSubTree((short) 1) % indexMap.length;
    indexMap[childrenHash] = 1;
    countMap[childrenHash] = 1;
    hashMapSize = 1;
  }

  private short findSpace() {
    if(freeHead != -1) {
      int longFreeHead = freeHead << 3;
      short index = freeHead;
      freeHead = treeData[longFreeHead];
      return index;
    }

    if(size+8 <= treeData.length) {
      assert size % 8 == 0;
      short index = (short) (size >> 3);
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
        // Should never happens as this implementation will only store 64*64*64 blocks at max
        // ie 299593 nodes in the worst case
        throw new se.llbit.math.PackedOctree.OctreeTooBigException();
      }
    }
    short[] newArray = new short[(int) newSize];
    System.arraycopy(treeData, 0, newArray, 0, size);
    treeData = newArray;
    // and then append
    short index = (short) (size >>> 3);
    size += 8;
    return index;
  }

  private void freeSpace(short index) {
    int longIndex = index << 3;
    treeData[longIndex] = freeHead;
    freeHead = index;
  }

  private void initHashMap(int size) {
    indexMap = new short[size];
    countMap = new short[size];
    final short empty = (short)-1;
    Arrays.fill(indexMap, empty);
    hashMapSize = 0;
  }

  private int hashSubTree(short index) {
    //detectCycle();
    return hashSubTree(index, 7, 0);
  }

  private int hashSubTree(short index, int hash, int counter) {
    if(counter > 8) {
      // Something's wrong, I can feel it
      throw new RuntimeException("cycle?");
    }
    int nodeIndex = index << 3;
    for(int i = 0; i < 8; ++i) {
      int childIndex = nodeIndex + i;
      short value = treeData[childIndex];
      hash = hashNode(hash, value, counter);
    }
    return hash & 0x7FFFFFFF;
  }

  private int hashNode(int hash, short value, int counter) {
    if(value <= 0) {
      hash = ((hash << 5) - hash) + (short)(-value);
    } else {
      hash = ((hash << 5) - hash) + (short)(-1);
      hash = hashSubTree(value, hash, counter+1);
    }
    return hash & 0x7FFFFFFF;
  }

  private int hashSubTree(short[] siblings) {
    //detectCycle();
    int hash = 7;
    for(int i = 0; i < 8; ++i) {
      short value = siblings[i];
      hash = hashNode(hash, value, 1);
    }
    return hash & 0x7FFFFFFF;
  }

  private boolean areSubTreeEquals(short index1, short index2) {
    if(index1 == index2)
      return true;

    int baseIndex1 = index1 << 3;
    int baseIndex2 = index2 << 3;

    for(int i = 0; i < 8; ++i) {
      int childIndex1 = baseIndex1 + i;
      int childIndex2 = baseIndex2 + i;
      short value1 = treeData[childIndex1];
      short value2 = treeData[childIndex2];
      if(value1 > 0 && value2 > 0) {
        // 2 branches, recurse
        if(!areSubTreeEquals(value1, value2))
          return false;
      } else {
        // Different type or one is leaf and other is branch
        if(value1 != value2)
          return false;
      }
    }

    return true;
  }

  private boolean areSubTreeEquals(short index, short[] siblings) {
    int baseIndex = index << 3;

    for(int i = 0; i < 8; ++i) {
      short value1 = treeData[baseIndex+i];
      short value2 = siblings[i];
      if(value1 > 0 && value2 > 0) {
        // 2 branches, recurse
        if(!areSubTreeEquals(value1, value2))
          return false;
      } else {
        // Different type or one is leaf and other is branch
        if(value1 != value2)
          return false;
      }
    }

    return true;
  }

  private int findSubTreeInHashMap(short index, int hash) {
    int hashMapIndex = hash % indexMap.length;

    while(true) {
      short otherSubTreeIndex = indexMap[hashMapIndex];
      if(otherSubTreeIndex == -1) // empty bucket
        return -1;

      if(areSubTreeEquals(index, otherSubTreeIndex))
        return hashMapIndex;

      hashMapIndex = (hashMapIndex + 1) % indexMap.length;
    }
  }

  private int findSubTreeInHashMap(short[] siblings, int hash) {
    int hashMapIndex = hash % indexMap.length;

    while(true) {
      short otherSubTreeIndex = indexMap[hashMapIndex];
      if(otherSubTreeIndex == -1) // empty bucket
        return -1;

      if(areSubTreeEquals(otherSubTreeIndex, siblings))
        return hashMapIndex;

      hashMapIndex = (hashMapIndex + 1) % indexMap.length;
    }
  }

  private void growHashMap() {
    int newHashMapCapacity = (int)(indexMap.length * 1.5);

    short[] newIndexMap = new short[newHashMapCapacity];
    short[] newCountMap = new short[newHashMapCapacity];
    Arrays.fill(newIndexMap, (short)-1);

    for(int i = 0; i < indexMap.length; ++i) {
      if(indexMap[i] == (short)-1)
        continue; // Skip empty buckets

      int hash = hashSubTree(indexMap[i]);
      int indexInMap = hash % newHashMapCapacity;
      while(newIndexMap[indexInMap] != (short)-1)
        indexInMap = (indexInMap+1) % newHashMapCapacity;

      newIndexMap[indexInMap] = indexMap[i];
      newCountMap[indexInMap] = countMap[i];
    }

    indexMap = newIndexMap;
    countMap = newCountMap;
  }

  private int insertInHashMap(int hash, short index, short count) {
    if(hashMapSize > indexMap.length * loadFactor)
      growHashMap();

    int hashMapIndex = hash % indexMap.length;

    while(true) {
      if(indexMap[hashMapIndex] == -1) {
        // Found empty bucket, insert here
        indexMap[hashMapIndex] = index;
        countMap[hashMapIndex] = count;
        ++hashMapSize;
        return hashMapIndex;
      }

      hashMapIndex = (hashMapIndex+1) % indexMap.length;
    }
  }

  void freeHashMapElement(int hashMapIndex) {
    short empty = (short)-1;

    // Remove element from hash map
    // Dut to the addressing scheme used, we need to remove and reinsert every element
    // placed after the one being removed until reaching an empty bucket
    indexMap[hashMapIndex] = empty;
    countMap[hashMapIndex] = 0;

    hashMapIndex = (hashMapIndex+1) % indexMap.length;
    while(indexMap[hashMapIndex] != -1) {
      // save element
      short index = indexMap[hashMapIndex];
      short count = countMap[hashMapIndex];
      // remove element from hashmap
      indexMap[hashMapIndex] = empty;
      countMap[hashMapIndex] = 0;
      // reinsert
      --hashMapSize;
      insertInHashMap(hashSubTree(index), index, count);
      hashMapIndex = (hashMapIndex+1) % indexMap.length;
    }

    --hashMapSize;
  }

  private void releaseHashMapElement(short index, int hash) {
    int hashMapIndex = findSubTreeInHashMap(index, hash);
    if(hashMapIndex == -1)
      throw new RuntimeException("Element not in hashmap");

    if(countMap[hashMapIndex] > 1)
      --countMap[hashMapIndex];
    else
      freeHashMapElement(hashMapIndex);
  }

  private int addHashMapElement(short index, int hash) {
    int hashMapIndex = findSubTreeInHashMap(index, hash);
    if(hashMapIndex != -1) {
      countMap[hashMapIndex]++;
      return hashMapIndex;
    }

    return insertInHashMap(hash, index, (short)1);
  }

  private int editSiblings(short index, boolean canBeInplace, short[] siblings, int modifiedHash) {
    int modifiedHashMapIndex = findSubTreeInHashMap(siblings, modifiedHash);
    boolean alreadyExists = modifiedHashMapIndex != -1;

    if(canBeInplace && alreadyExists) {
      // Use the existing one and remove the old one
      freeSpace(index); // Remove the node
      countMap[modifiedHashMapIndex]++;
      return modifiedHashMapIndex;
    }
    if(!canBeInplace && alreadyExists) {
      // Use the existing one and decrement the old
      countMap[modifiedHashMapIndex]++;
      return modifiedHashMapIndex;
    }
    if(canBeInplace /*&& !alreadyExists*/) {
      // reinsert the hash map entry (the node doesn't need to be reallocated)
      int longIndex = index << 3;
      System.arraycopy(siblings, 0, treeData, longIndex, 8);
      return insertInHashMap(modifiedHash, index, (short) 1);
    }
    /* if(!canBeInplace && !alreadyExists) */
    // Allocate a new node and hash map element
    short newIndex = findSpace();
    int longNewIndex = newIndex << 3;
    System.arraycopy(siblings, 0, treeData, longNewIndex, 8);
    return insertInHashMap(modifiedHash, newIndex, (short)1);
  }

  private int addSiblings(short[] siblings, int hash) {
    int hashMapIndex = findSubTreeInHashMap(siblings, hash);
    if(hashMapIndex != -1) {
      countMap[hashMapIndex]++;
      return hashMapIndex;
    }

    short index = findSpace();
    int longIndex = index << 3;
    System.arraycopy(siblings, 0, treeData, longIndex, 8);
    return insertInHashMap(hash, index, (short)1);
  }

  public void set(int type, int x, int y, int z) {
//    detectCycle();
    short encodedType = (short)-bigToSmall(type);

    int[] parents = new int[DEPTH+1];
    int[] childNumbers = new int[DEPTH+1];
    boolean[] canChangeInPlace = new boolean[DEPTH+1];
    int nodeIndex = 0;
    int parentLevel = DEPTH;
    int position = 0;
    short[] siblings = new short[8];
    int level;
    for(level = DEPTH; level >= 0; --level) {
      parents[level] = nodeIndex;

      if(treeData[nodeIndex] == encodedType) {
        return;
      } else if(treeData[nodeIndex] <= 0) {
        // subdivide node
        for(int i = level+1; i <= DEPTH; ++i) {
//          detectCycle();
          short index = treeData[parents[i]];
          int hash = hashSubTree(index);
          int hashMapIndex = findSubTreeInHashMap(index, hash);
          if(hashMapIndex == -1)
            throw new RuntimeException("lol");
          canChangeInPlace[i] = countMap[hashMapIndex] == 1;

          releaseHashMapElement(index, hash);
        }
//        detectCycle();

        Arrays.fill(siblings, treeData[nodeIndex]);
        int hash = hashSubTree(siblings);
        int hashMapIndex = addSiblings(siblings, hash);
//        detectCycle();
        int levelAncestor = level+1;
        while(true) {
//          detectCycle();
          short indexOfNewSiblings = indexMap[hashMapIndex];
          int parentIndex = parents[levelAncestor];
          short siblingIndex = treeData[parentIndex];
          int siblingLongIndex = siblingIndex << 3;
          assert (parents[levelAncestor - 1] >= siblingLongIndex) && (parents[levelAncestor - 1] < (siblingLongIndex + 8));
          System.arraycopy(treeData, siblingLongIndex, siblings, 0, 8);
          siblings[childNumbers[levelAncestor]] = indexOfNewSiblings;
          hashMapIndex = editSiblings(siblingIndex, canChangeInPlace[levelAncestor], siblings, hashSubTree(siblings));
//          detectCycle();
//          if(indexMap[hashMapIndex] == siblingIndex) {
//            // Inplace edit, no need to change parents
//            break;
//          }
          if(levelAncestor == DEPTH) {
            // Went back to the root
            // Never shared so direct write is safe
            treeData[0] = indexMap[hashMapIndex];
            parents[DEPTH-1] = (treeData[0] << 3) + childNumbers[DEPTH];
            if(level == DEPTH-1) {
              // First iteration
              nodeIndex = parents[DEPTH-1];
            }
            break;
          }
          parents[levelAncestor-1] = (indexMap[hashMapIndex] << 3) + childNumbers[levelAncestor];
          if(levelAncestor == level+1) {
            // First iteration
            nodeIndex = parents[level];
          }
          ++levelAncestor;
        }
//        detectCycle();

        parentLevel = level;
      }
      int xbit = 1 & (x >> level);
      int ybit = 1 & (y >> level);
      int zbit = 1 & (z >> level);
      position = (xbit << 2) | (ybit << 1) | zbit;
      childNumbers[level] = position;
      nodeIndex = (treeData[nodeIndex] << 3) + position;
    }

    ++level;

    for(int i = level+1; i <= DEPTH; ++i) {
//      detectCycle();
      short index = treeData[parents[i]];
      int hash = hashSubTree(index);
      int hashMapIndex = findSubTreeInHashMap(index, hash);
      if(hashMapIndex == -1)
        throw new RuntimeException("lol");
      canChangeInPlace[i] = countMap[hashMapIndex] == 1;

      releaseHashMapElement(index, hash);
    }
//    detectCycle();
    short siblingsToEditIndex = treeData[parents[level]];
    System.arraycopy(treeData, siblingsToEditIndex << 3, siblings, 0, 8);
    assert siblings[childNumbers[level]] <= 0;
    siblings[childNumbers[level]] = encodedType;
    int hash = hashSubTree(siblings);
    int hashMapIndex = editSiblings(siblingsToEditIndex, canChangeInPlace[level], siblings, hash);
//    detectCycle();
    int levelAncestor = level+1;
    while(true /*siblingsToEditIndex != indexMap[hashMapIndex]*/) {
//      detectCycle();
      // Copy on write, propagate change to parents until an inplace modification
      short indexOfNewSiblings = indexMap[hashMapIndex];
      int parentIndex = parents[levelAncestor];
      short siblingIndex = treeData[parentIndex];
      int siblingLongIndex = siblingIndex << 3;
      assert (parents[levelAncestor - 1] >= siblingLongIndex) && (parents[levelAncestor - 1] < (siblingLongIndex + 8));
      System.arraycopy(treeData, siblingLongIndex, siblings, 0, 8);
      siblings[childNumbers[levelAncestor]] = indexOfNewSiblings;
      hashMapIndex = editSiblings(siblingIndex, canChangeInPlace[levelAncestor], siblings, hashSubTree(siblings));
//      detectCycle();
//      if(indexMap[hashMapIndex] == siblingIndex) {
//        // Inplace edit, no need to change parents
//        break;
//      }
      if(levelAncestor == DEPTH) {
        // Went back to the root
        // Never shared so direct write is safe
        treeData[0] = indexMap[hashMapIndex];
        parents[DEPTH-1] = (treeData[0] << 3) + childNumbers[DEPTH];
        break;
      }
      parents[levelAncestor-1] = (indexMap[hashMapIndex] << 3) + childNumbers[levelAncestor];
      if(levelAncestor == level+1) {
        // First iteration
        nodeIndex = parents[level+1-1];
      }
      ++levelAncestor;
    }
//    detectCycle();
//    int groupIndex = treeData[parents[0]];
//    short shortType = bigToSmall(data.type);
//    short[] previousGroup = new short[8];
//    for(int i = 0; i < 8; ++i)
//      previousGroup[i] = dict[groupIndex + i];
//    previousGroup[position] = shortType;
//    int newGroupIndex = findGroup(
//            previousGroup[0],
//            previousGroup[1],
//            previousGroup[2],
//            previousGroup[3],
//            previousGroup[4],
//            previousGroup[5],
//            previousGroup[6],
//            previousGroup[7]
//    );
//    treeData[parents[0]] = newGroupIndex;
//
//    // Merge nodes where all children have been set to the same type.
//    for(int i = 0; i <= parentLevel; ++i) {
//      int parentIndex = parents[i];
//
//      if(i == 0) {
//        boolean allSame = true;
//        short first = dict[newGroupIndex];
//        for(int j = 1; j < 8; ++j) {
//          int childType = dict[newGroupIndex + j];
//          if(childType != first) {
//            allSame = false;
//            break;
//          }
//        }
//
//        if(allSame) {
//          // Can't free the space in the dictionary because it might be used by others groups
//          // Ideally, we would keep a reference count around
//          int type = smallToBig(first);
//          treeData[parents[0]] = -type;
//          nodeIndex = parents[0];
//        } else {
//          break;
//        }
//      } else {
//        boolean allSame = true;
//        for(int j = 0; j < 8; ++j) {
//          int childIndex = treeData[parentIndex] + j;
//          if(!nodeEquals(childIndex, nodeIndex)) {
//            allSame = false;
//            break;
//          }
//        }
//
//        if(allSame) {
//          mergeNode(parentIndex, treeData[nodeIndex]);
//          nodeIndex = parentIndex;
//        } else {
//          break;
//        }
//      }
//    }
  }

  private void detectCycle() {
    if(true)
      return;
    ArrayList<Short> visited = new ArrayList<>();
    visited.add((short) 0);
    detectCycleNode(0, visited);
  }

  private void detectCycleNode(int nodeIndex, ArrayList<Short> visited) {
    if(visited.size() > DEPTH+2)
      throw new RuntimeException("too depth");
    short index = treeData[nodeIndex];
    if(index <= 0)
      return;
    if(visited.contains(index))
      throw new RuntimeException("Cycle detected");
    visited.add(index);
    int longIndex = index << 3;
    for(int i = 0; i < 8; ++i)
      detectCycleNode(longIndex+i, visited);
    visited.remove(visited.size()-1);
  }

  private int getNodeIndex(int x, int y, int z) {
    int nodeIndex = 0;
    int level = 6;
    while(treeData[nodeIndex] > 0) {
      level -= 1;
      int lx = x >>> level;
      int ly = y >>> level;
      int lz = z >>> level;
      nodeIndex = (treeData[nodeIndex] << 3) + (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1));
    }
    return nodeIndex;
  }

  public int get(int x, int y, int z) {
    return smallToBig((short) -treeData[getNodeIndex(x, y, z)]);
  }

  public interface ISmallDAGBasedNodeId extends Octree.NodeId {
    <R> R visit(Function<IExternalSmallDAGBasedNodeId, R> visitor1, Function<SmallDAGNodeId, R> visitor2);
  }

  public interface IExternalSmallDAGBasedNodeId extends ISmallDAGBasedNodeId {
    @Override
    default <R> R visit(Function<IExternalSmallDAGBasedNodeId, R> visitor1, Function<SmallDAGNodeId, R> visitor2) {
      return visitor1.apply(this);
    }
  }

  public static class SmallDAGNodeId implements ISmallDAGBasedNodeId {
    private int nodeIndex;

    public SmallDAGNodeId(int nodeIndex) {
      this.nodeIndex = nodeIndex;
    }

    @Override
    public <R> R visit(Function<IExternalSmallDAGBasedNodeId, R> visitor1, Function<SmallDAGNodeId, R> visitor2) {
      return visitor2.apply(this);
    }
  }

  public SmallDAGNodeId getRoot() {
    return new SmallDAGNodeId(0);
  }

  public boolean isBranch(SmallDAGNodeId nodeId) {
    return treeData[nodeId.nodeIndex] > 0;
  }

  public int getType(SmallDAGNodeId nodeId) {
    return smallToBig((short) -treeData[nodeId.nodeIndex]);
  }

  public SmallDAGNodeId getChild(SmallDAGNodeId nodeId, int childNo) {
    return new SmallDAGNodeId((treeData[nodeId.nodeIndex] << 3) + childNo);
  }


  public static void main(String[] args) {
    SmallDAG t = new SmallDAG();
    t.set(3, 0, 0, 0);
    t.set(3, 1, 0, 0);
    t.set(3, 2, 0, 0);
    t.set(3, 3, 0, 0);
    t.set(3, 4, 0, 0);
    t.set(3, 5, 0, 0);
    t.set(3, 6, 0, 0);
    t.set(3, 7, 0, 0);
    t.set(3, 8, 0, 0);
    t.set(3, 9, 0, 0);
    t.set(3, 10, 0, 0);
    t.set(3, 11, 0, 0);
    t.set(3, 12, 0, 0);
    t.set(3, 13, 0, 0);
    t.set(3, 14, 0, 0);
    t.set(3, 15, 0, 0);
    t.set(3, 0, 0, 1);
    t.set(3, 1, 0, 1);
    t.set(3, 2, 0, 1);
    t.set(3, 3, 0, 1);
    t.set(3, 4, 0, 1);
    t.set(3, 5, 0, 1);
    t.set(3, 6, 0, 1);
    t.set(3, 7, 0, 1);
    t.set(3, 8, 0, 1);
    t.set(3, 9, 0, 1);
    t.set(3, 10, 0, 1);
    t.set(3, 11, 0, 1);
    t.set(3, 12, 0, 1);
    t.set(3, 13, 0, 1);
    System.out.println("yo");
  }

}