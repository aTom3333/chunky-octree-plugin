package dev.ferrand.chunky.octree;

import dev.ferrand.chunky.octree.implementations.CompressedSiblingsOctree;
import se.llbit.chunky.Plugin;
import se.llbit.chunky.main.Chunky;
import se.llbit.chunky.main.ChunkyOptions;
import se.llbit.chunky.ui.ChunkyFx;

public class OctreePlugin implements Plugin {
    @Override
    public void attach(Chunky chunky) {
        CompressedSiblingsOctree.initImplementation();
    }

    public static void main(String[] args) {
        // Start Chunky normally with this plugin attached.
        Chunky.loadDefaultTextures();
        Chunky chunky = new Chunky(ChunkyOptions.getDefaults());
        new OctreePlugin().attach(chunky);
        ChunkyFx.startChunkyUI(chunky);
    }
}
