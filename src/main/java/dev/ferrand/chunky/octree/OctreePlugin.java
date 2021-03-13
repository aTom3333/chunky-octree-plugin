package dev.ferrand.chunky.octree;

import dev.ferrand.chunky.octree.implementations.*;
import dev.ferrand.chunky.octree.ui.OctreeTab;
import se.llbit.chunky.Plugin;
import se.llbit.chunky.main.Chunky;
import se.llbit.chunky.main.ChunkyOptions;
import se.llbit.chunky.ui.ChunkyFx;
import se.llbit.chunky.ui.render.RenderControlsTabTransformer;

public class OctreePlugin implements Plugin {
    @Override
    public void attach(Chunky chunky) {
        RenderControlsTabTransformer previousTransformer = chunky.getRenderControlsTabTransformer();
        chunky.setRenderControlsTabTransformer(tabs -> {
            tabs = previousTransformer.apply(tabs);
            tabs.add(new OctreeTab());
            return tabs;
        });

        CompressedSiblingsOctree.initImplementation();
        DiskOctree.initImplementation();
        GcPackedOctree.initImplementation();
        StatsOctree.initImplementation();
        SmallLeafOctree.initImplementation();
        DictionaryOctree.initImplementation();
        SmallDAGTree.initImplementation();
    }

    public static void main(String[] args) {
        // Start Chunky normally with this plugin attached.
        Chunky.loadDefaultTextures();
        Chunky chunky = new Chunky(ChunkyOptions.getDefaults());
        new OctreePlugin().attach(chunky);
        ChunkyFx.startChunkyUI(chunky);
    }
}
