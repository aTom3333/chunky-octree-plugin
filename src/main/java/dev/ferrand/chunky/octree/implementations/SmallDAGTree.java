package dev.ferrand.chunky.octree.implementations;

import dev.ferrand.chunky.octree.utils.SmallDAG;
import it.unimi.dsi.fastutil.ints.IntIntMutablePair;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.world.Material;
import se.llbit.math.Octree;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import static dev.ferrand.chunky.octree.utils.SmallDAG.SMALL_ANY_TYPE;
import static se.llbit.math.Octree.*;

public class SmallDAGTree implements Octree.OctreeImplementation {
  private final ArrayList<SmallDAG> dags;
  private int[] treeData;
  private int size;
  private final int depth;
  private final int upperDepth;
  private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 16;
  /**
   * dense, temporary representation of a tree
   */
  private List<short[]> tempTree = new ArrayList<>();

  public SmallDAGTree(int depth) {
    this.depth = depth;
    this.upperDepth = depth - 6;
    treeData = new int[64];
    size = 8;
    dags = new ArrayList<>();
  }

  static class NodeId implements SmallDAG.IExternalSmallDAGBasedNodeId {
    int index;
    int curDepth;
    SmallDAGTree self;

    public NodeId(int index, int curDepth, SmallDAGTree self) {
      this.index = index;
      this.curDepth = curDepth;
      this.self = self;
    }

    @Override
    public int getType() {
      return -self.treeData[index];
    }
  }

  private int findSpace() {
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

  private void subdivideNode(int nodeIndex) {
    int childrenIndex = findSpace();
    for(int i = 0; i < 8; ++i) {
      treeData[childrenIndex + i] = treeData[nodeIndex]; // copy type
    }
    treeData[nodeIndex] = childrenIndex; // Make the node a parent node pointing to its children
  }

  @Override
  public void set(int type, int x, int y, int z) {
    if(type == 0)
      return;
    int nodeIndex = 0;
    for(int i = depth - 1; i >= 6; --i) {
      if(treeData[nodeIndex] == -type) {
        return;
      } else if(treeData[nodeIndex] <= 0) { // It's a leaf node
        subdivideNode(nodeIndex);
      }

      int xbit = 1 & (x >> i);
      int ybit = 1 & (y >> i);
      int zbit = 1 & (z >> i);
      int position = (xbit << 2) | (ybit << 1) | zbit;
      nodeIndex = treeData[nodeIndex] + position;
    }
    int dagIndex = treeData[nodeIndex];
    if(dagIndex <= 0) {
      dags.add(new SmallDAG());
      dagIndex = dags.size();
      treeData[nodeIndex] = dagIndex;
    }
    SmallDAG dag = dags.get(dagIndex-1);
    dag.set(type, x, y, z);
  }

  static private int splitBy3(int a)
  {
    int x = a & 0xff; // we only look at the first 8 bits
    // Here we have the bits          abcd efgh
    x = (x | x << 8) & 0x0f00f00f; // shift left 32 bits, OR with self, and 0001000000001111000000001111000000001111000000001111000000000000
    // Here we have         abcd 0000 0000 efgh
    x = (x | x << 4) & 0xc30c30c3; // shift left 32 bits, OR with self, and 0001000011000011000011000011000011000011000011000011000100000000
    // Here we have    ab00 00cd 0000 ef00 00gh
    x = (x | x << 2) & 0x49249249;
    // Here we have a0 0b00 c00d 00e0 0f00 g00h
    return x;
  }

  @Override
  public void setCube(int cubeDepth, int[] types, int x, int y, int z) {
    if(cubeDepth > 6) {
      Octree.OctreeImplementation.super.setCube(cubeDepth, types, x, y, z);
      return;
    }

    int size = 1 << cubeDepth;

    for(int nextLevel = tempTree.size(); nextLevel <= cubeDepth; ++nextLevel)
      tempTree.add(new short[1 << (3*nextLevel)]);

    // Write all the types from in the last level of the temp tree in morton order
    // (so children are back to back in the array)
    for(int cz = 0; cz < size; ++cz) {
      for(int cy = 0; cy < size; ++cy) {
        for(int cx = 0; cx < size; ++cx) {
          int linearIdx = (cz << (2*cubeDepth)) + (cy << cubeDepth) + cx;
          int mortonIdx = (splitBy3(cx) << 2) | (splitBy3(cy) << 1) | splitBy3(cz);
          tempTree.get(cubeDepth)[mortonIdx] = (short) -SmallDAG.bigToSmall(types[linearIdx]);
        }
      }
    }

    // Construct levels from the level deeper until the root of the temp tree
    for(int curDepth = cubeDepth-1; curDepth >= 0; --curDepth) {
      int numElem = (1 << (3 * curDepth));
      for(int parentIdx = 0; parentIdx < numElem; ++parentIdx)
      {
        int childrenIdx = parentIdx * 8;
        boolean mergeable = true;
        short firstType = tempTree.get(curDepth+1)[childrenIdx];
        for(int childNo = 1; childNo < 8; ++childNo) {
          if(tempTree.get(curDepth+1)[childrenIdx+childNo] > 0) {
            mergeable = false;
            break;
          }
          if(firstType == -SMALL_ANY_TYPE)
            firstType = tempTree.get(curDepth+1)[childrenIdx+childNo];
          else if(firstType != tempTree.get(curDepth+1)[childrenIdx+childNo] && tempTree.get(curDepth+1)[childrenIdx+childNo] != -SMALL_ANY_TYPE) {
            mergeable = false;
            break;
          }
        }
        if(mergeable)
        {
          tempTree.get(curDepth)[parentIdx] = firstType;
        }
        else
        {
          tempTree.get(curDepth)[parentIdx] = 1;
        }
      }
    }

    int nodeIndex = 0;
    for(int i = depth - 1; i >= 6; --i) {
      if(treeData[nodeIndex] <= 0) {
        subdivideNode(nodeIndex);
      }

      int xbit = 1 & (x >> i);
      int ybit = 1 & (y >> i);
      int zbit = 1 & (z >> i);
      int position = (xbit << 2) | (ybit << 1) | zbit;
      nodeIndex = treeData[nodeIndex] + position;
    }
    int dagIndex = treeData[nodeIndex];
    if(dagIndex <= 0) {
      dags.add(new SmallDAG());
      dagIndex = dags.size();
      treeData[nodeIndex] = dagIndex;
    }
    SmallDAG dag = dags.get(dagIndex-1);
    dag.setCube(cubeDepth, tempTree, x, y, z);
  }

  @Override
  public void getWithLevel(IntIntMutablePair outTypeAndLevel, int x, int y, int z) {
    int index = 0;
    int level = depth;
    while(true) {
      --level;
      int lx = x >>> level;
      int ly = y >>> level;
      int lz = z >>> level;
      index = treeData[index] + (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1));
      if(treeData[index] <= 0) {
        outTypeAndLevel.left(-treeData[index]).right(level);
        return;
      }
      if(level == 6) {
        int dagIndex = treeData[index];
        if(dagIndex > dags.size())
          throw new RuntimeException("oob");
        SmallDAG dag = dags.get(dagIndex-1);
        dag.getWithLevel(outTypeAndLevel, x, y, z);
        return;
      }
    }
  }

  @Override
  public Material getMaterial(int x, int y, int z, BlockPalette blockPalette) {
    int index = 0;
    int level = depth;
    int type;
    while(true) {
      --level;
      int lx = x >>> level;
      int ly = y >>> level;
      int lz = z >>> level;
      index = treeData[index] + (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1));
      if(index <= 0) {
        type = -index;
        break;
      }
      if(level == 6) {
        int dagIndex = treeData[index];
        if(dagIndex <= 0) {
          type = -dagIndex;
          break;
        }
        if(dagIndex > dags.size())
          throw new RuntimeException("oob");
        SmallDAG dag = dags.get(dagIndex-1);
        type = dag.get(x, y, z);
        break;
      }
    }
    return blockPalette.get(type);
  }

  @Override
  public int getDepth() {
    return depth;
  }

  @Override
  public long nodeCount() {
    return 0;
  }

  @Override
  public Octree.NodeId getRoot() {
    return new NodeId(0, 0, this);
  }

  @Override
  public boolean isBranch(Octree.NodeId nodeId) {
    return ((SmallDAG.ISmallDAGBasedNodeId)nodeId).visit(
      n -> treeData[((NodeId)n).index] > 0,
      n -> n.self.isBranch(n)
    );
  }

  @Override
  public Octree.NodeId getChild(Octree.NodeId nodeId, int i) {
    return ((SmallDAG.ISmallDAGBasedNodeId)nodeId).visit(
      n -> {
        NodeId id = (NodeId) n;
        if(id.curDepth == upperDepth) {
          SmallDAG dag = dags.get(treeData[id.index] - 1);
          return dag.getChild(dag.getRoot(), i);
        }
        return new NodeId(treeData[id.index] + i, id.curDepth+1, id.self);
      },
      n -> n.self.getChild(n, i)
    );
  }

  @Override
  public int getType(Octree.NodeId nodeId) {
    return ((SmallDAG.ISmallDAGBasedNodeId)nodeId).getType();
  }

  @Override
  public void endFinalization() {
    for(SmallDAG dag : dags)
      dag.removeHashMapData();
  }

  @Override
  public void store(DataOutputStream out) throws IOException {
    out.writeInt(getDepth());
    storeNode(out, getRoot());
  }

  private void storeNode(DataOutputStream out, Octree.NodeId node) throws IOException {
    if(isBranch(node)) {
      out.writeInt(BRANCH_NODE);
      for(int i = 0; i < 8; ++i) {
        storeNode(out, getChild(node, i));
      }
    } else {
      out.writeInt(getType(node));
    }
  }

  static private SmallDAGTree load(DataInputStream in) throws IOException {
    int depth = in.readInt();
    SmallDAGTree tree = new SmallDAGTree(depth);
    tree.loadNode(in, depth-1, 0);
    return tree;
  }

  private void loadNode(DataInputStream in, int level, int nodeIndex) throws IOException {
    int type = in.readInt();

    if(level == 5 && type == BRANCH_NODE) {
      SmallDAG dag = new SmallDAG();
      dags.add(dag);
      int dagIndex = dags.size();
      treeData[nodeIndex] = dagIndex;
      dag.load(in);
    } else if(type == BRANCH_NODE) {
      int childrenIndex = findSpace();
      treeData[nodeIndex] = childrenIndex;
      for(int i = 0; i < 8; ++i) {
        loadNode(in, level-1, childrenIndex + i);
      }
    } else {
      treeData[nodeIndex] = -type; // negation of type
    }
  }

  static public void initImplementation() {
    Octree.addImplementationFactory("DAG_TREE", new Octree.ImplementationFactory() {
      @Override
      
      public Octree.OctreeImplementation create(int depth) {
        return new SmallDAGTree(depth);
      }

      @Override
      public Octree.OctreeImplementation load(DataInputStream in) throws IOException {
        return SmallDAGTree.load(in);
      }

      @Override
      public Octree.OctreeImplementation loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
        return SmallDAGTree.load(in);
      }

      @Override
      public boolean isOfType(Octree.OctreeImplementation implementation) {
        return implementation instanceof SmallDAG;
      }

      @Override
      public String getDescription() {
        return "Use 16 bits representation nistead of 32 bits for most of the nodes and deduplicate subtrees to save memory.";
      }
    });
  }
}
