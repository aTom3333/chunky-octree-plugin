package dev.ferrand.chunky.octree.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.print.PaperSource;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import se.llbit.chunky.PersistentSettings;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.chunky.ui.IntegerAdjuster;
import se.llbit.chunky.ui.render.RenderControlsTab;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

public class OctreeTab extends ScrollPane implements RenderControlsTab, Initializable {

    @FXML private IntegerAdjuster bytesForIndex;
    @FXML private IntegerAdjuster bytesForType;
    @FXML private IntegerAdjuster bitsForData;

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
        bytesForIndex.setName("Bytes for index");
        bytesForIndex.setRange(1, 8);
        bytesForIndex.setAndUpdate(PersistentSettings.settings.getInt("compressedSiblings.bytesForIndex", 4));
        bytesForIndex.setTooltip("The number of bytes used to represent an index.\n" +
                "Using less will reduce memory usage but the maximum nodes that can be stored will be lower.\n" +
                "4 is a good default.");
        bytesForIndex.onValueChange(val -> PersistentSettings.setIntOption("compressedSiblings.bytesForIndex", val));

        bytesForType.setName("Bytes for type");
        bytesForType.setRange(1, 4);
        bytesForType.setAndUpdate(PersistentSettings.settings.getInt("compressedSiblings.bytesForType", 2));
        bytesForType.setTooltip("The number of bytes used to represent a block type.\n" +
                "Using less will reduce memory usage but will limit the number of different blocks possible in the scene.\n" +
                "2 is a good default.");
        bytesForType.onValueChange(val -> PersistentSettings.setIntOption("compressedSiblings.bytesForType", val));

        bitsForData.setName("Bits for data");
        bitsForData.setRange(1, 29);
        bitsForData.setAndUpdate(PersistentSettings.settings.getInt("compressedSiblings.bitsForData", 14));
        bitsForData.setTooltip("The number of bits used to index a dictionary of data.\n" +
                "Using less will reduce memory usage but will limit the number of different data value possible in a scene.\n" +
                "14 is a good default.");
        bitsForData.onValueChange(val -> PersistentSettings.setIntOption("compressedSiblings.bitsForData", val));
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
