package dev.ferrand.chunky.octree.implementations;

import org.apache.commons.math3.util.Pair;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.world.Material;
import se.llbit.math.Octree;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static se.llbit.math.Octree.*;

/**
 * This is a packed representation of an octree
 * the whole octree is stored in a int array to reduce memory usage and
 * hopefully improve performance by being more cache-friendly
 */
public class SmallLeafOctree implements OctreeImplementation {
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
  private int freeHead32;
  private int freeHead16;

  private int depth;

  private static int SMALL_ANY_TYPE = 0xFFFF;

  public enum NodeOccupancy {
    FULL, LOW, HIGH, CACHED
  }

  private static final class NodeId implements Octree.NodeId {
    int nodeIndex;
    NodeOccupancy occupancy;
    int currentDepth;

    public NodeId(int nodeIndex, NodeOccupancy occupancy, int currentDepth) {
      this.nodeIndex = nodeIndex;
      this.occupancy = occupancy;
      this.currentDepth = currentDepth;
    }

    public static NodeId cachedType(int type) {
      return new NodeId(type, NodeOccupancy.CACHED, -1);
    }
  }

  @Override
  public Octree.NodeId getRoot() {
    return new NodeId(0, NodeOccupancy.FULL, 1);
  }

  @Override
  public boolean isBranch(Octree.NodeId node) {
    return ((NodeId) node).occupancy == NodeOccupancy.FULL && treeData[((NodeId) node).nodeIndex] > 0;
  }

  @Override
  public Octree.NodeId getChild(Octree.NodeId parent, int childNo) {
    NodeId parentNodeId = (NodeId) parent;
    if(parentNodeId.currentDepth == depth) {
      return new NodeId(treeData[parentNodeId.nodeIndex] + (childNo >>> 1), (childNo & 1) == 0 ? NodeOccupancy.HIGH : NodeOccupancy.LOW, parentNodeId.currentDepth + 1);
    }
    return new NodeId(treeData[((NodeId) parent).nodeIndex] + childNo, NodeOccupancy.FULL, parentNodeId.currentDepth + 1);
  }

  @Override
  public int getType(Octree.NodeId node) {
    NodeId nodeId = (NodeId) node;
    int type = 0;
    switch(nodeId.occupancy) {
      case HIGH:
        type = treeData[nodeId.nodeIndex] >>> 16;
        break;
      case LOW:
        type = treeData[nodeId.nodeIndex] & 0xFFFF;
        break;
      case FULL:
        return -treeData[nodeId.nodeIndex];
      case CACHED:
        return nodeId.nodeIndex;
    }
    if(type == SMALL_ANY_TYPE)
      return ANY_TYPE;
    return type;
  }

  @Override
  public int getData(Octree.NodeId node) {
    return 0;
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
  public SmallLeafOctree(int depth, long nodeCount) {
    this.depth = depth;
    long arraySize = Math.max(nodeCount, 64);
    if(arraySize > (long) MAX_ARRAY_SIZE)
      throw new OctreeTooBigException();
    treeData = new int[(int) arraySize];
    treeData[0] = 0;
    size = 1;
    freeHead32 = -1; // No holes
    freeHead16 = -1; // No holes
  }

  /**
   * Constructs an empty octree
   *
   * @param depth The depth of the tree
   */
  public SmallLeafOctree(int depth) {
    this.depth = depth;
    treeData = new int[64];
    // Add a root node
    treeData[0] = 0;
    size = 1;
    freeHead32 = -1;
    freeHead16 = -1;
  }

  /**
   * Finds space in the array to put 8 nodes
   * We find space by searching in the free list
   * if this fails we append at the end of the array
   * if the size is greater than the capacity, we allocate a new array
   *
   * @return the index at the beginning of a free space in the array of size 16 ints (8 nodes)
   */
  private int findSpace32() {
    // Look in free list
    if(freeHead32 != -1) {
      int index = freeHead32;
      freeHead32 = treeData[freeHead32];
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

  private int findSpace16() {
    // Look in free list
    if(freeHead16 != -1) {
      int index = freeHead16;
      freeHead16 = treeData[freeHead16];
      return index;
    }

    // append in array if we have the capacity
    if(size + 4 <= treeData.length) {
      int index = size;
      size += 4;
      return index;
    }

    // We need to grow the array
    long newSize = (long) Math.ceil(treeData.length * 1.5);
    // We need to check the array won't be too big
    if(newSize > (long) MAX_ARRAY_SIZE) {
      // We can allocate less memory than initially wanted if the next block will still be able to fit
      // If not, this implementation isn't suitable
      if(MAX_ARRAY_SIZE - treeData.length > 4) {
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
    size += 4;
    return index;
  }

  /**
   * free space at the given index, simply add the 16 ints block beginning at index to the free list
   *
   * @param index the index of the beginning of the block to free
   */
  private void freeSpace32(int index) {
    treeData[index] = freeHead32;
    freeHead32 = index;
  }

  private void freeSpace16(int index) {
    treeData[index] = freeHead16;
    freeHead16 = index;
  }

  /**
   * Subdivide a node, give to each child the same type and data that this node previously had
   *
   * @param nodeIndex The index of the node to subdivide
   */
  private void subdivideNode(int nodeIndex, int currentDepth) {
    if(currentDepth == depth) {
      // We can store 8 types in only 4 4-bytes integer by using 2 bytes per type
      int childrenIndex = findSpace16();
      int type = -treeData[nodeIndex];
      int combinedType = (type << 16) | type;
      for(int i = 0; i < 4; ++i) {
        treeData[childrenIndex + i] = combinedType;
      }
      treeData[nodeIndex] = childrenIndex;
    } else {
      int childrenIndex = findSpace32();
      for(int i = 0; i < 8; ++i) {
        treeData[childrenIndex + i] = treeData[nodeIndex]; // copy type
      }
      treeData[nodeIndex] = childrenIndex; // Make the node a parent node pointing to its children
    }
  }

  /**
   * Merge a parent node so it becomes a leaf node
   *
   * @param nodeIndex    The index of the node to merge
   * @param typeNegation The negation of the type (the value directly stored in the array)
   */
  private void mergeNode(int nodeIndex, int typeNegation, int currentDepth) {
    int childrenIndex = treeData[nodeIndex];
    if(currentDepth == depth)
      freeSpace16(childrenIndex); // Delete children
    else
      freeSpace32(childrenIndex);
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
    set(new Node(type), x, y, z);
  }

  @Override
  public void set(Node data, int x, int y, int z) {
    int[] parents = new int[depth]; // better to put as a field to preventallocation at each invocation?
    int nodeIndex = 0;
    int parentLevel = depth - 1;
    int position = 0;
    int currentDepth = 1;
    for(int i = depth - 1; i >= 0; --i) {
      parents[i] = nodeIndex;
      if(nodeEquals(nodeIndex, data)) {
        return;
      } else if(treeData[nodeIndex] <= 0) {
        subdivideNode(nodeIndex, currentDepth);
        parentLevel = i;
      }
      int xbit = 1 & (x >> i);
      int ybit = 1 & (y >> i);
      int zbit = 1 & (z >> i);
      if(currentDepth == depth) {
        position = (xbit << 1) | ybit;
        nodeIndex = treeData[nodeIndex] + position;
        int mask = 0xFFFF << (16 * zbit);
        int type = data.type;
        if(type == ANY_TYPE)
          type = SMALL_ANY_TYPE;
        treeData[nodeIndex] &= mask;
        treeData[nodeIndex] |= (type << (16 * (1 - zbit)));
      } else {
        position = (xbit << 2) | (ybit << 1) | zbit;
        nodeIndex = treeData[nodeIndex] + position;
        ++currentDepth;
      }
    }
    if(currentDepth != depth) {
      int finalNodeIndex = treeData[nodeIndex] + position;
      treeData[finalNodeIndex] = -data.type; // Store negation of the type
    }

    // Merge nodes where all children have been set to the same type.
    for(int i = 0; i <= parentLevel; ++i) {
      int parentIndex = parents[i];

      if(currentDepth == depth) {
        boolean allSame = true;
        int value = treeData[treeData[parentIndex]] >>> 16;
        for(int j = 0; j < 4; j++) {
          int childIndex = treeData[parentIndex] + j;
          if(value != (treeData[childIndex] >>> 16) || value != (treeData[childIndex] & 0xFFFF)) {
            allSame = false;
            break;
          }
        }

        if(allSame) {
          if(value == SMALL_ANY_TYPE)
            value = ANY_TYPE;
          mergeNode(parentIndex, -value, currentDepth);
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
          mergeNode(parentIndex, treeData[nodeIndex], currentDepth);
        } else {
          break;
        }
      }

      --currentDepth;
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
    int mask = -zbit; //0b111111... if zbit == 1, 0b0000000... if zbit == 0
    int childIndex = nodeValue + ((xbit << 1) | ybit);
    int combinedType = treeData[childIndex];
    // Is branchless code really a good idea?
    int type = ((combinedType >>> 16) & ~mask) | ((combinedType & 0xFFFF) & mask);
    if(type == SMALL_ANY_TYPE)
      return ANY_TYPE;
    return type;
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
      return new Pair<>(NodeId.cachedType(-nodeValue), level);

    int xbit = x & 1;
    int ybit = y & 1;
    int zbit = z & 1;
    int mask = -zbit; //0b111111... if zbit == 1, 0b0000000... if zbit == 0
    int childIndex = nodeValue + ((xbit << 1) | ybit);
    int combinedType = treeData[childIndex];
    // Is branchless code really a good idea?
    int type = ((combinedType >>> 16) & ~mask) | ((combinedType & 0xFFFF) & mask);
    if(type == SMALL_ANY_TYPE)
      return new Pair<>(NodeId.cachedType(ANY_TYPE), 0);
    return new Pair<>(NodeId.cachedType(type), 0);
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

  @Override
  public int getDepth() {
    return depth;
  }

  public static SmallLeafOctree load(DataInputStream in) throws IOException {
    int depth = in.readInt();
    SmallLeafOctree tree = new SmallLeafOctree(depth);
    tree.loadNode(in, 0, 1);
    return tree;
  }

  public static SmallLeafOctree loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
    int depth = in.readInt();
    SmallLeafOctree tree = new SmallLeafOctree(depth, nodeCount);
    tree.loadNode(in, 0, 1);
    return tree;
  }

  private void loadNode(DataInputStream in, int nodeIndex, int currentDepth) throws IOException {
    int type = in.readInt();
    if(type == BRANCH_NODE) {
      if(currentDepth == depth) {
        int childrenIndex = findSpace16();
        treeData[nodeIndex] = childrenIndex;
        for(int i = 0; i < 4; ++i) {
          int high = in.readInt();
          if(high == ANY_TYPE)
            high = SMALL_ANY_TYPE;
          int low = in.readInt();
          if(low == ANY_TYPE)
            low = SMALL_ANY_TYPE;
          if(high > 65535 || high == BRANCH_NODE || low > 65535 || low == BRANCH_NODE) {
            throw new RuntimeException("Can't load octree into a SmallLeafOctree. Use another octree implementation");
          }
          treeData[childrenIndex + i] = (high << 16) | low;
        }
      } else {
        int childrenIndex = findSpace32();
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
  }

  private void finalizationNode(int nodeIndex, int currentDepth) {
    if(currentDepth == depth) {
      boolean canMerge = true;
      int mergedType = SMALL_ANY_TYPE;
      for(int i = 0; i < 4; ++i) {
        int childIndex = treeData[nodeIndex] + i;
        int combinedType = treeData[childIndex];
        if(mergedType == SMALL_ANY_TYPE) {
          mergedType = combinedType >>> 16;
        } else if(!((combinedType >>> 16) == SMALL_ANY_TYPE || ((combinedType >>> 16) == mergedType))) {
          canMerge = false;
          break;
        }
        if(mergedType == SMALL_ANY_TYPE) {
          mergedType = combinedType & 0xFFFF;
        } else if(!((combinedType & 0xFFFF) == SMALL_ANY_TYPE || ((combinedType & 0xFFFF) == mergedType))) {
          canMerge = false;
          break;
        }
      }
      if(canMerge) {
        if(mergedType == SMALL_ANY_TYPE)
          mergedType = ANY_TYPE;
        mergeNode(nodeIndex, -mergedType, currentDepth);
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
        mergeNode(nodeIndex, mergedType, currentDepth);
      }
    }
  }

  static public void initImplementation() {
    Octree.addImplementationFactory("SMALL_LEAF", new ImplementationFactory() {
      @Override
      public OctreeImplementation create(int depth) {
        return new SmallLeafOctree(depth);
      }

      @Override
      public OctreeImplementation load(DataInputStream in) throws IOException {
        return SmallLeafOctree.load(in);
      }

      @Override
      public OctreeImplementation loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
        return SmallLeafOctree.loadWithNodeCount(nodeCount, in);
      }

      @Override
      public boolean isOfType(OctreeImplementation implementation) {
        return implementation instanceof SmallLeafOctree;
      }

      @Override
      public String getDescription() {
        return "Variation of PACKED but with a more compact representation for leaves at full depth.";
      }
    });
  }
}
