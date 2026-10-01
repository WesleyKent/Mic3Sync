package dev.openpocket.probe;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public final class SyncPlannerTest {
    @Test public void onlyReturnsPathsThatAreNeitherSyncedNorDownloading() {
        var old = new MediaCatalog.Item("/data/emmc0/old.wav");
        var active = new MediaCatalog.Item("/data/emmc0/active.wav");
        var fresh = new MediaCatalog.Item("/data/emmc0/fresh.wav");
        List<MediaCatalog.Item> result = SyncPlanner.fresh(
                List.of(old, active, fresh), Set.of(old.path()), Set.of(active.path()));
        assertEquals(List.of(fresh), result);
    }
}
