package dev.ferrand.chunky.octree.utils;

import it.unimi.dsi.fastutil.ints.IntIntMutablePair;
import se.llbit.math.Octree;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static se.llbit.math.Octree.ANY_TYPE;
import static se.llbit.math.Octree.BRANCH_NODE;

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
  public static final int SMALL_ANY_TYPE = 0x7FFF;

  /**
   * Hash map: hash of subtree -> index of subtree + number of use of the subtree
   * -1 represent an empty slot
   */
  private short[] indexMap;
  private short[] countMap;
  private int hashMapSize;

  /**
   * The hashes are cached because computing the hash every time is expensive
   */
  private short[] cachedHashes;

  private final double loadFactor = 0.75;

  private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 16;

  private static final int DEPTH = 5;

  public static class TypeToBigException extends RuntimeException {
  }

  public static int smallToBig(short smallType) {
    int type = ((int) smallType) & 0x7FFF;
    if(type == SMALL_ANY_TYPE)
      type = ANY_TYPE;
    return type;
  }

  public static short bigToSmall(int type) {
    if(type != ANY_TYPE && type > 0x7FFF)
      throw new TypeToBigException();
    return (short) (type == ANY_TYPE ? SMALL_ANY_TYPE : type);
  }


  public SmallDAG() {
    treeData = new short[64];
    treeData[0] = 1;
    size = 16;
    freeHead = -1;
    cachedHashes = new short[8]; // 1 8th of treeData
    Arrays.fill(cachedHashes, (short) -1);
    initHashMap(64);
    short childrenHash = hashSubTree((short) 1);
    int childrenHashMapIndex = childrenHash % indexMap.length;
    indexMap[childrenHashMapIndex] = 1;
    countMap[childrenHashMapIndex] = 1;
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
    int newShortSize = (int) (newSize / 8);
    short[] newCachedHashes = new short[newShortSize];
    System.arraycopy(treeData, 0, newArray, 0, size);

    // grow the cachedHashes array at the same time
    System.arraycopy(cachedHashes, 0, newCachedHashes, 0, size / 8);
    // Fill the end with empty values
    Arrays.fill(newCachedHashes, size / 8, newShortSize, (short) -1);
    treeData = newArray;
    cachedHashes = newCachedHashes;
    // and then append
    short index = (short) (size >>> 3);
    size += 8;

    return index;
  }

  private void freeSpace(short index) {
    int longIndex = index << 3;
    treeData[longIndex] = freeHead;
    freeHead = index;
    cachedHashes[index] = -1;
  }

  private void initHashMap(int size) {
    indexMap = new short[size];
    countMap = new short[size];
    final short empty = (short)-1;
    Arrays.fill(indexMap, empty);
    hashMapSize = 0;
  }

  private void writeAt(short[] siblings, short index) {
    int longIndex = index << 3;
    System.arraycopy(siblings, 0, treeData, longIndex, 8);
    cachedHashes[index] = -1;
  }

  private short hashSubTree(short index) {
    //detectCycle();
//    short hash1 = hashSubTree(index, 0);
    short hash2 = hashSubTreeCached(index, 0);
//    if(hash1 != hash2)
//      throw new RuntimeException("bad hash");
    return hash2;
  }

  private short hashSubTree(short index, int counter) {
    short hash = 7;
    if(counter > 8) {
      // Something's wrong, I can feel it
      throw new RuntimeException("cycle?");
    }
    int nodeIndex = index << 3;
    for(int i = 0; i < 8; ++i) {
      int childIndex = nodeIndex + i;
      short value = treeData[childIndex];
      hash = (short) (((hash << 5) - hash) + hashNode(value, counter));
    }
    hash = (short) (hash & 0x7FFF);
    return hash;
  }

  private short hashSubTreeCached(short index, int counter) {
    short cachedHash = cachedHashes[index];
    if(cachedHash >= 0)
      return cachedHash;
    short hash = 7;
    if(counter > 8) {
      // Something's wrong, I can feel it
      throw new RuntimeException("cycle?");
    }
    int nodeIndex = index << 3;
    for(int i = 0; i < 8; ++i) {
      int childIndex = nodeIndex + i;
      short value = treeData[childIndex];
      hash = (short) (((hash << 5) - hash) + hashNodeCached(value, counter));
    }
    hash = (short) (hash & 0x7FFF);
    cachedHashes[index] = hash;
    return hash;
  }

  private short hashNode(short value, int counter) {
    short hash = 13;
    if(value <= 0) {
      hash = (short) (((hash << 5) - hash) + (short)(-value));
    } else {
      hash = (short) (((hash << 5) - hash) + (short)(-1));
      hash = (short) (((hash << 5) - hash) + hashSubTree(value, counter+1));
    }
    return (short) (hash & 0x7FFF);
  }

  private short hashNodeCached(short value, int counter) {
    short hash = 13;
    if(value <= 0) {
      hash = (short) (((hash << 5) - hash) + (short)(-value));
    } else {
      hash = (short) (((hash << 5) - hash) + (short)(-1));
      hash = (short) (((hash << 5) - hash) + hashSubTreeCached(value, counter+1));
    }
    return (short) (hash & 0x7FFF);
  }

  private short hashSubTree(short[] siblings) {
    //detectCycle();
    short hash = 7;
    for(int i = 0; i < 8; ++i) {
      short value = siblings[i];
      hash = (short) (((hash << 5) - hash) + hashNodeCached(value, 1));
    }
    return (short) (hash & 0x7FFF);
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

  private int findSubTreeInHashMap(short index, short hash) {
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

  private int findSubTreeInHashMap(short[] siblings, short hash) {
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

      short hash = hashSubTree(indexMap[i]);
      int indexInMap = hash % newHashMapCapacity;
      while(newIndexMap[indexInMap] != (short)-1)
        indexInMap = (indexInMap+1) % newHashMapCapacity;

      newIndexMap[indexInMap] = indexMap[i];
      newCountMap[indexInMap] = countMap[i];
    }

    indexMap = newIndexMap;
    countMap = newCountMap;
  }

  private int insertInHashMap(short hash, short index, short count) {
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

  private boolean releaseHashMapElement(short index, short hash) {
    int hashMapIndex = findSubTreeInHashMap(index, hash);
    if(hashMapIndex == -1)
      throw new RuntimeException("Element not in hashmap");

    if(countMap[hashMapIndex] > 1) {
      --countMap[hashMapIndex];
      return false;
    } else {
      freeHashMapElement(hashMapIndex);
      return true;
    }
  }

  private int addHashMapElement(short index, short hash) {
    int hashMapIndex = findSubTreeInHashMap(index, hash);
    if(hashMapIndex != -1) {
      countMap[hashMapIndex]++;
      return hashMapIndex;
    }

    return insertInHashMap(hash, index, (short)1);
  }

  private int editSiblings(short index, boolean canBeInplace, short[] siblings, short modifiedHash) {
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
      writeAt(siblings, index);
      return insertInHashMap(modifiedHash, index, (short) 1);
    }
    /* if(!canBeInplace && !alreadyExists) */
    // Allocate a new node and hash map element
    short newIndex = findSpace();
    writeAt(siblings, newIndex);
    return insertInHashMap(modifiedHash, newIndex, (short)1);
  }

  private int addSiblings(short[] siblings, short hash) {
    int hashMapIndex = findSubTreeInHashMap(siblings, hash);
    if(hashMapIndex != -1) {
      countMap[hashMapIndex]++;
      return hashMapIndex;
    }

    short index = findSpace();
    writeAt(siblings, index);
    return insertInHashMap(hash, index, (short)1);
  }

  private void prepareAncestorsForEditing(int[] parents, boolean[] canChangeInPlace, int level) {
    for(int i = DEPTH; i >= level; --i) {
      short index = treeData[parents[i]];
      short hash = hashSubTree(index);
      int hashMapIndex = findSubTreeInHashMap(index, hash);
      if(hashMapIndex == -1)
        throw new RuntimeException("lol");
      canChangeInPlace[i] = countMap[hashMapIndex] == 1;

      releaseHashMapElement(index, hash);
      cachedHashes[index] = -1;
    }
  }

  private void updateAncestorsAfterEdit(int[] parents, int[] childNumbers, short[] siblings, int level, int hashMapIndex, boolean[] canChangeInplace) {
    int levelAncestor = level+1;
    while(levelAncestor <= DEPTH) {
      short indexOfNewSiblings = indexMap[hashMapIndex];
      int parentIndex = parents[levelAncestor];
      short siblingIndex = treeData[parentIndex];
      int siblingLongIndex = siblingIndex << 3;
      assert (parents[levelAncestor - 1] >= siblingLongIndex) && (parents[levelAncestor - 1] < (siblingLongIndex + 8));
      System.arraycopy(treeData, siblingLongIndex, siblings, 0, 8);
      siblings[childNumbers[levelAncestor]] = indexOfNewSiblings;
      hashMapIndex = editSiblings(siblingIndex, canChangeInplace[levelAncestor], siblings, hashSubTree(siblings));
      if(indexMap[hashMapIndex] == siblingIndex) {
        // Inplace edit, no need to change parents
        break;
      }
      if(levelAncestor == DEPTH) {
        // Went back to the root
        // Never shared so direct write is safe
        treeData[0] = indexMap[hashMapIndex];
        parents[DEPTH - 1] = (treeData[0] << 3) + childNumbers[DEPTH];
        break;
      }
      parents[levelAncestor - 1] = (indexMap[hashMapIndex] << 3) + childNumbers[levelAncestor];
      ++levelAncestor;
    }

    // Re-add to the hash map the subtrees that have been removed earlier
    // but not re-added because they weren't directly changed
    for(int i = levelAncestor + 1; i < DEPTH + 1; ++i) {
      short index = treeData[parents[i]];
      short newHash = hashSubTree(index);
      addHashMapElement(index, newHash);
    }
  }

  public void set(int type, int x, int y, int z) {
//    System.err.printf("t.set(%d, %d, %d, %d);\n", type, x, y, z);
//    System.err.flush();
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
        prepareAncestorsForEditing(parents, canChangeInPlace, level+1);

        Arrays.fill(siblings, treeData[nodeIndex]);
        short hash = hashSubTree(siblings);
        int hashMapIndex = addSiblings(siblings, hash);
        updateAncestorsAfterEdit(parents, childNumbers, siblings, level, hashMapIndex, canChangeInPlace);
        nodeIndex = parents[level];

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

    prepareAncestorsForEditing(parents, canChangeInPlace, level);
    short siblingsToEditIndex = treeData[parents[level]];
    System.arraycopy(treeData, siblingsToEditIndex << 3, siblings, 0, 8);
    assert siblings[childNumbers[level]] <= 0;
    siblings[childNumbers[level]] = encodedType;
    short hash = hashSubTree(siblings);
    int hashMapIndex = editSiblings(siblingsToEditIndex, canChangeInPlace[level], siblings, hash);
    updateAncestorsAfterEdit(parents, childNumbers, siblings, level, hashMapIndex, canChangeInPlace);


    // Merge nodes where all children have been set to the same type.
    if(parentLevel >= DEPTH)
      parentLevel = DEPTH-1;
    for(int mergeLevel = 0; mergeLevel <= parentLevel; ++mergeLevel) {
      int parentIndex = parents[mergeLevel];

      boolean allSame = true;
      short siblingsIndex = treeData[parentIndex];
      int longSiblingsIndex = siblingsIndex << 3;
      short first = treeData[longSiblingsIndex];
      if(first > 0)
        break;
      for(int j = 1; j < 8; ++j) {
        if(first != treeData[longSiblingsIndex + j]) {
          allSame = false;
          break;
        }
      }

      if(allSame) {
        prepareAncestorsForEditing(parents, canChangeInPlace, mergeLevel+1);
        if(releaseHashMapElement(siblingsIndex, hashSubTree(siblingsIndex)))
          freeSpace(siblingsIndex);
        short parentSiblingsIndex = treeData[parents[mergeLevel+1]];
        int longParentSiblingsIndex = parentSiblingsIndex << 3;
        System.arraycopy(treeData, longParentSiblingsIndex, siblings, 0, 8);
        siblings[childNumbers[mergeLevel+1]] = first;
        hashMapIndex = editSiblings(parentSiblingsIndex, canChangeInPlace[mergeLevel+1], siblings, hashSubTree(siblings));
        updateAncestorsAfterEdit(parents, childNumbers, siblings, mergeLevel+1, hashMapIndex, canChangeInPlace);
      } else {
        break;
      }
    }
  }

  public void setCube(int cubeDepth, List<short[]> tempTree, int x, int y, int z) {
    int[] parents = new int[DEPTH+1];
    int[] childNumbers = new int[DEPTH+1];
    boolean[] canChangeInPlace = new boolean[DEPTH+1];
    int nodeIndex = 0;
    int parentLevel = DEPTH;
    int position = 0;
    short[] siblings = new short[8];
    int level;
    for(level = DEPTH; level >= cubeDepth; --level) {
      parents[level] = nodeIndex;

      if(treeData[nodeIndex] <= 0) {
        // subdivide node
        prepareAncestorsForEditing(parents, canChangeInPlace, level+1);

        Arrays.fill(siblings, treeData[nodeIndex]);
        short hash = hashSubTree(siblings);
        int hashMapIndex = addSiblings(siblings, hash);
        updateAncestorsAfterEdit(parents, childNumbers, siblings, level, hashMapIndex, canChangeInPlace);
        nodeIndex = parents[level];

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

    prepareAncestorsForEditing(parents, canChangeInPlace, level);
    short siblingsToEditIndex = treeData[parents[level]];
    System.arraycopy(treeData, siblingsToEditIndex << 3, siblings, 0, 8);

    if(siblings[childNumbers[level]] > 0) {
      // free the subtree that is about to be replaced
      maybeFreeSubtree(siblings[childNumbers[level]]);
    }
    assert siblings[childNumbers[level]] <= 0;

    short indexOfInsertedSubTree = insertTempTree(tempTree, 0, 0);

    siblings[childNumbers[level]] = indexOfInsertedSubTree;
    short hash = hashSubTree(siblings);
    int hashMapIndex = editSiblings(siblingsToEditIndex, canChangeInPlace[level], siblings, hash);
    updateAncestorsAfterEdit(parents, childNumbers, siblings, level, hashMapIndex, canChangeInPlace);


    // Merge nodes where all children have been set to the same type.
    if(parentLevel >= DEPTH)
      parentLevel = DEPTH-1;
    for(int mergeLevel = 0; mergeLevel <= parentLevel; ++mergeLevel) {
      int parentIndex = parents[mergeLevel];

      boolean allSame = true;
      short siblingsIndex = treeData[parentIndex];
      int longSiblingsIndex = siblingsIndex << 3;
      short first = treeData[longSiblingsIndex];
      if(first > 0)
        break;
      for(int j = 1; j < 8; ++j) {
        if(first != treeData[longSiblingsIndex + j]) {
          allSame = false;
          break;
        }
      }

      if(allSame) {
        prepareAncestorsForEditing(parents, canChangeInPlace, mergeLevel+1);
        if(releaseHashMapElement(siblingsIndex, hashSubTree(siblingsIndex)))
          freeSpace(siblingsIndex);
        short parentSiblingsIndex = treeData[parents[mergeLevel+1]];
        int longParentSiblingsIndex = parentSiblingsIndex << 3;
        System.arraycopy(treeData, longParentSiblingsIndex, siblings, 0, 8);
        siblings[childNumbers[mergeLevel+1]] = first;
        hashMapIndex = editSiblings(parentSiblingsIndex, canChangeInPlace[mergeLevel+1], siblings, hashSubTree(siblings));
        updateAncestorsAfterEdit(parents, childNumbers, siblings, mergeLevel+1, hashMapIndex, canChangeInPlace);
      } else {
        break;
      }
    }
  }

  private short insertTempTree(List<short[]> tempTree, int level, int startIdx) {
    if(tempTree.get(level)[startIdx] <= 0)
      return tempTree.get(level)[startIdx];

    short[] siblings = new short[8];
    for(int i = 0; i < 8; ++i) {
      siblings[i] = insertTempTree(tempTree, level+1, startIdx*8 + i);
    }
    int hashMapIndex = addSiblings(siblings, hashSubTree(siblings));

    return indexMap[hashMapIndex];
  }

  private void maybeFreeSubtree(short siblingIndex) {
    short hash = hashSubTree(siblingIndex);
    // Decrement the ref counter of the node
    if(releaseHashMapElement(siblingIndex, hash)) {
      // if the node has been removed from the hash map (because refcount == 0)
      // do the same operation on its children and free it
      for(int i = 0; i < 8; ++i) {
        short childIndex = treeData[siblingIndex*8 + i];
        if(childIndex > 0)
          maybeFreeSubtree(childIndex);
      }
      freeSpace(siblingIndex);
    }
  }

  private void detectCycle() {
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

  public void getWithLevel(IntIntMutablePair outTypeAndLevel, int x, int y, int z) {
    int level = 6;
    int index = 0;
    while(treeData[index] > 0) {
      --level;
      int lx = x >>> level;
      int ly = y >>> level;
      int lz = z >>> level;
      int position = (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1));
      index = (treeData[index] << 3) + position;
    }

    outTypeAndLevel.left(smallToBig((short) -treeData[index])).right(level);
  }

  public interface ISmallDAGBasedNodeId extends Octree.NodeId {
    <R> R visit(Function<IExternalSmallDAGBasedNodeId, R> visitor1, Function<SmallDAGNodeId, R> visitor2);
    int getType();
  }

  public interface IExternalSmallDAGBasedNodeId extends ISmallDAGBasedNodeId {
    @Override
    default <R> R visit(Function<IExternalSmallDAGBasedNodeId, R> visitor1, Function<SmallDAGNodeId, R> visitor2) {
      return visitor1.apply(this);
    }
  }

  public static class SmallDAGNodeId implements ISmallDAGBasedNodeId {
    private int nodeIndex;
    public final SmallDAG self;

    public SmallDAGNodeId(int nodeIndex, SmallDAG self) {
      this.nodeIndex = nodeIndex;
      this.self = self;
    }

    @Override
    public <R> R visit(Function<IExternalSmallDAGBasedNodeId, R> visitor1, Function<SmallDAGNodeId, R> visitor2) {
      return visitor2.apply(this);
    }

    @Override
    public int getType() {
      return smallToBig((short) -self.treeData[nodeIndex]);
    }
  }

  public SmallDAGNodeId getRoot() {
    return new SmallDAGNodeId(0, this);
  }

  public boolean isBranch(SmallDAGNodeId nodeId) {
    return treeData[nodeId.nodeIndex] > 0;
  }

  public int getType(SmallDAGNodeId nodeId) {
    return smallToBig((short) -treeData[nodeId.nodeIndex]);
  }

  public SmallDAGNodeId getChild(SmallDAGNodeId nodeId, int childNo) {
    return new SmallDAGNodeId((treeData[nodeId.nodeIndex] << 3) + childNo, this);
  }

  public void removeHashMapData() {
    indexMap = null;
    countMap = null;
    cachedHashes = null;
  }

  private short insertStreamTree(DataInputStream in) throws IOException {
    int type = in.readInt();
    if(type != BRANCH_NODE)
      return (short)-bigToSmall(type);

    short[] siblings = new short[8];
    for(int i = 0; i < 8; ++i) {
      siblings[i] = insertStreamTree(in);
    }
    int hashMapIndex = addSiblings(siblings, hashSubTree(siblings));

    return indexMap[hashMapIndex];
  }

  public void load(DataInputStream in) throws IOException {
    short[] siblings = new short[8];
    for(int i = 0; i < 8; ++i) {
      siblings[i] = insertStreamTree(in);
    }
    int hashMapIndex = addSiblings(siblings, hashSubTree(siblings));

    short value = indexMap[hashMapIndex];
    treeData[0] = value;
    removeHashMapData();
  }

  public static void main(String[] args) {
    SmallDAG t = new SmallDAG();
    ArrayList<short[]> tempTree = new ArrayList<>();
    tempTree.add(new short[]{1});
    tempTree.add(new short[]{0, -1, 0, 0, 1, 1, 1, 0});
    tempTree.add(new short[]{
            0, 0, 0, 0, 0, 0, 0, 0, // unused
            0, 0, 0, 0, 0, 0, 0, 0, // unused
            0, 0, 0, 0, 0, 0, 0, 0, // unused
            0, 0, 0, 0, 0, 0, 0, 0, // unused
            0, 0, -1, 0, 0, 0, -1, 0,
            0, 0, 0, 0, -1, 0, 0, 0,
            0, 0, -1, 0, 0, 0, -1, 0,
            0, 0, 0, 0, 0, 0, 0, 0, // unused
    });
    t.setCube(2, tempTree, 0, 0, 0);
    t.setCube(2, tempTree, 0, 0, 0);
    System.out.println("yo");
  }

}
