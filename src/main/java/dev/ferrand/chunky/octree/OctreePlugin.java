package dev.ferrand.chunky.octree;

import se.llbit.chunky.Plugin;
import se.llbit.chunky.main.Chunky;
import se.llbit.chunky.main.ChunkyOptions;
import se.llbit.chunky.ui.ChunkyFx;
import se.llbit.math.Octree;
import se.llbit.math.PackedOctree;

import java.io.DataInputStream;
import java.io.IOException;

public class OctreePlugin implements Plugin {
    @Override
    public void attach(Chunky chunky) {
        Octree.addImplementationFactory("PACKED2", new Octree.ImplementationFactory() {
            @Override
            public Octree.OctreeImplementation create(int depth) {
                return new PackedOctree(depth);
            }

            @Override
            public Octree.OctreeImplementation load(DataInputStream in) throws IOException {
                return PackedOctree.load(in);
            }

            @Override
            public Octree.OctreeImplementation loadWithNodeCount(long nodeCount, DataInputStream in) throws IOException {
                return PackedOctree.loadWithNodeCount(nodeCount, in);
            }

            @Override
            public boolean isOfType(Octree.OctreeImplementation octreeImplementation) {
                return octreeImplementation instanceof PackedOctree;
            }

            @Override
            public String getDescription() {
                return "A test copy";
            }
        });
    }

    public static void main(String[] args) {
        // Start Chunky normally with this plugin attached.
        Chunky.loadDefaultTextures();
        Chunky chunky = new Chunky(ChunkyOptions.getDefaults());
        new OctreePlugin().attach(chunky);
        ChunkyFx.startChunkyUI(chunky);
    }
}
