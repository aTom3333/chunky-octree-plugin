package dev.ferrand.chunky.octree.implementations;

import dev.ferrand.chunky.octree.utils.SmallDAG;
import se.llbit.chunky.chunk.BlockPalette;
import se.llbit.chunky.world.Material;
import se.llbit.math.Octree;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class SmallDAGTree implements Octree.OctreeImplementation {
  SmallDAG chunk;
  int depth;

  public SmallDAGTree(int depth) {
    this.chunk = new SmallDAG();
    this.depth = depth;
  }

  static class NodeId implements SmallDAG.IExternalSmallDAGBasedNodeId {
    int level;

    public NodeId(int level) {
      this.level = level;
    }
  }

  @Override
  public void set(int type, int x, int y, int z) {
    if(x / 16 == 0 && y / 16 == 0 && z / 16 == 0)
      chunk.set(type, x, y, z);
  }

  @Override
  public void set(Octree.Node node, int x, int y, int z) {
    set(node.type, x, y, z);
  }

  @Override
  public Octree.Node get(int x, int y, int z) {
    if(x / 16 == 0 && y / 16 == 0 && z / 16 == 0)
      return new Octree.Node(chunk.get(x, y, z));
    return new Octree.Node(0);
  }

  @Override
  public Material getMaterial(int x, int y, int z, BlockPalette blockPalette) {
    return blockPalette.get(get(x, y, z).type);
  }

  @Override
  public void store(DataOutputStream dataOutputStream) throws IOException {

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
    return new NodeId(depth - 7);
  }

  @Override
  public boolean isBranch(Octree.NodeId nodeId) {
    return ((SmallDAG.ISmallDAGBasedNodeId)nodeId).visit(
      n -> ((NodeId)n).level >= 0,
      n -> chunk.isBranch(n)
    );
  }

  @Override
  public Octree.NodeId getChild(Octree.NodeId nodeId, int i) {
    return ((SmallDAG.ISmallDAGBasedNodeId)nodeId).visit(
      n -> {
        if(i > 0)
          return new NodeId(-1);
        if(((NodeId) n).level == 0)
          return chunk.getRoot();
        return new NodeId(((NodeId) n).level - 1);
      },
      n -> chunk.getChild(n, i)
    );
  }

  @Override
  public int getType(Octree.NodeId nodeId) {
    return ((SmallDAG.ISmallDAGBasedNodeId)nodeId).visit(
      n -> 0,
      n -> chunk.getType(n)
    );
  }

  @Override
  public int getData(Octree.NodeId nodeId) {
    return 0;
  }

  static public void initImplementation() {
    Octree.addImplementationFactory("DAG_TREE", new Octree.ImplementationFactory() {
      @Override
      public Octree.OctreeImplementation create(int depth) {
        return new SmallDAGTree(depth);
      }

      @Override
      public Octree.OctreeImplementation load(DataInputStream in) throws IOException {
        return null;
      }

      @Override
      public Octree.OctreeImplementation loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
        return null;
      }

      @Override
      public boolean isOfType(Octree.OctreeImplementation implementation) {
        return implementation instanceof SmallDAG;
      }

      @Override
      public String getDescription() {
        return "TODO";
      }
    });
  }
}
