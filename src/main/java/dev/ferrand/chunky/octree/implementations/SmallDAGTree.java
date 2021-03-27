package dev.ferrand.chunky.octree.implementations;

import dev.ferrand.chunky.octree.utils.SmallDAG;
import it.unimi.dsi.fastutil.ints.IntIntMutablePair;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.world.Material;
import se.llbit.math.Octree;

import java.io.*;
import java.util.ArrayList;

import static se.llbit.math.Octree.BRANCH_NODE;

public class SmallDAGTree implements Octree.OctreeImplementation {
  private ArrayList<SmallDAG> dags;
  private int[] treeData;
  private int size;
  private final int depth;
  private final int upperDepth;
  private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 16;

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

  @Override
  public void set(Octree.Node node, int x, int y, int z) {
    set(node.type, x, y, z);
  }

  @Override
  public Octree.Node get(int x, int y, int z) {
    int index = 0;
    int level = depth;
    while(true) {
      --level;
      int lx = x >>> level;
      int ly = y >>> level;
      int lz = z >>> level;
      index = treeData[index] + (((lx & 1) << 2) | ((ly & 1) << 1) | (lz & 1));
      if(index <= 0)
        return new Octree.Node(-index);
      if(level == 6) {
        int dagIndex = treeData[index];
        if(dagIndex <= 0)
          return new Octree.Node(-dagIndex);
        if(dagIndex > dags.size())
          throw new RuntimeException("oob");
        SmallDAG dag = dags.get(dagIndex-1);
        return new Octree.Node(dag.get(x, y, z));
      }
    }
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
    return blockPalette.get(get(x, y, z).type);
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
  public int getData(Octree.NodeId nodeId) {
    return 0;
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

  static private SmallDAGTree load(DataInputStream in) throws IOException {
    int depth = in.readInt();
    SmallDAGTree tree = new SmallDAGTree(depth);
    tree.loadNode(in, depth-1, 0, 0, 0);
    return tree;
  }

  private void loadNode(DataInputStream in, int level, int x, int y, int z) throws IOException {
    int type = in.readInt();
    if(type == BRANCH_NODE) {
      for(int dx = 0; dx < 2; ++dx)
      for(int dy = 0; dy < 2; ++dy)
      for(int dz = 0; dz < 2; ++dz) {
        loadNode(in, level-1, x + (dx << level), y + (dy << level), z + (dz << level));
      }
    } else {
      for(int localX = x; localX < x + (1 << (level+1)); ++localX)
      for(int localY = y; localY < y + (1 << (level+1)); ++localY)
      for(int localZ = z; localZ < z + (1 << (level+1)); ++localZ)
        set(type, localX, localY, localZ);
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
