package dev.openpocket.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class SyncPlanner {
    static List<MediaCatalog.Item> fresh(List<MediaCatalog.Item> items, Set<String> synced, Set<String> inFlight) {
        List<MediaCatalog.Item> result = new ArrayList<>();
        for (MediaCatalog.Item item : items)
            if (!synced.contains(item.path()) && !inFlight.contains(item.path())) result.add(item);
        return result;
    }
}
