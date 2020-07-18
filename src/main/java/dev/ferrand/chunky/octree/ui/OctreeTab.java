package dev.ferrand.chunky.octree.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import se.llbit.chunky.PersistentSettings;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.chunky.ui.IntegerAdjuster;
import se.llbit.chunky.ui.render.RenderControlsTab;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Objects;
import java.util.ResourceBundle;

public class OctreeTab extends ScrollPane implements RenderControlsTab, Initializable {

    @FXML private IntegerAdjuster compressedSiblings_bytesForIndex;
    @FXML private IntegerAdjuster compressedSiblings_bytesForType;
    @FXML private IntegerAdjuster compressedSiblings_bitsForData;

    private static class CacheSize {
        public final int shift;
        private static String[] prefix = {"", "k", "M", "G"};

        public CacheSize(int shift) {
            this.shift = shift;
        }

        @Override
        public String toString() {
            long size = 1L << (shift+3);
            int prefixIdx = 0;
            while(size >= (1 << 10)) {
                size >>= 10;
                ++prefixIdx;
            }
            return String.valueOf(size) + prefix[prefixIdx] + "B";
        }

        @Override
        public boolean equals(Object o) {
            if(this == o) return true;
            if(o == null || getClass() != o.getClass()) return false;
            CacheSize cacheSize = (CacheSize) o;
            return shift == cacheSize.shift;
        }

        @Override
        public int hashCode() {
            return Objects.hash(shift);
        }
    }
    @FXML private ChoiceBox<CacheSize> disk_cacheSize;
    @FXML private IntegerAdjuster disk_cacheNumber;

    public OctreeTab() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/octree-tab.fxml"));
            loader.setRoot(this);
            loader.setController(this);
            loader.load();
        } catch(IOException e) {
            throw new RuntimeException("Error while initialization of Octree plug-in", e);
        }
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        compressedSiblings_bytesForIndex.setName("Bytes for index");
        compressedSiblings_bytesForIndex.setRange(1, 8);
        compressedSiblings_bytesForIndex.setAndUpdate(PersistentSettings.settings.getInt("compressedSiblings.bytesForIndex", 4));
        compressedSiblings_bytesForIndex.setTooltip("The number of bytes used to represent an index.\n" +
                "Using less will reduce memory usage but the maximum nodes that can be stored will be lower.\n" +
                "4 is a good default.");
        compressedSiblings_bytesForIndex.onValueChange(val -> PersistentSettings.setIntOption("compressedSiblings.bytesForIndex", val));

        compressedSiblings_bytesForType.setName("Bytes for type");
        compressedSiblings_bytesForType.setRange(1, 4);
        compressedSiblings_bytesForType.setAndUpdate(PersistentSettings.settings.getInt("compressedSiblings.bytesForType", 2));
        compressedSiblings_bytesForType.setTooltip("The number of bytes used to represent a block type.\n" +
                "Using less will reduce memory usage but will limit the number of different blocks possible in the scene.\n" +
                "2 is a good default.");
        compressedSiblings_bytesForType.onValueChange(val -> PersistentSettings.setIntOption("compressedSiblings.bytesForType", val));

        compressedSiblings_bitsForData.setName("Bits for data");
        compressedSiblings_bitsForData.setRange(1, 29);
        compressedSiblings_bitsForData.setAndUpdate(PersistentSettings.settings.getInt("compressedSiblings.bitsForData", 14));
        compressedSiblings_bitsForData.setTooltip("The number of bits used to index a dictionary of data.\n" +
                "Using less will reduce memory usage but will limit the number of different data value possible in a scene.\n" +
                "14 is a good default.");
        compressedSiblings_bitsForData.onValueChange(val -> PersistentSettings.setIntOption("compressedSiblings.bitsForData", val));


        ArrayList<CacheSize> sizes = new ArrayList<>();
        for(int i = 7; i <= 17; ++i) {
            sizes.add(new CacheSize(i));
        }
        disk_cacheSize.getItems().addAll(sizes.toArray(new CacheSize[0]));
        disk_cacheSize.getSelectionModel().select(new CacheSize(PersistentSettings.settings.getInt("disk.cacheSize", 11)));
        disk_cacheSize.setTooltip(new Tooltip("Size of each cache used to reduce the number of disk access"));
        disk_cacheSize.getSelectionModel().selectedItemProperty().addListener((observable, newvalue, oldvalue) -> {
            PersistentSettings.setIntOption("disk.cacheSize", newvalue.shift);
        });

        disk_cacheNumber.setName("Number of caches");
        disk_cacheNumber.setRange(1, 1024*1024);
        disk_cacheNumber.setTooltip("The number of caches used to reduce the number of disk access");
        disk_cacheNumber.setAndUpdate(PersistentSettings.settings.getInt("disk.cacheNumber", 2048));
        disk_cacheNumber.onValueChange(val -> PersistentSettings.setIntOption("disk.cacheNumber", val));
    }

    @Override
    public void update(Scene scene) {
    }

    @Override
    public String getTabTitle() {
        return "Advanced Octree Options";
    }

    @Override
    public Node getTabContent() {
        return this;
    }
}
