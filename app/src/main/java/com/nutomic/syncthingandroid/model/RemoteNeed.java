package com.nutomic.syncthingandroid.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * /rest/db/remoteneed?device=deviceId&folder=folderId
 */
public class RemoteNeed {
    public RemoteNeedItem[] files = new RemoteNeedItem[]{};
    public int page = 1;
    public int perpage = 0;

    public List<RemoteNeedItem> allItems() {
        return files == null ? Collections.emptyList() : Arrays.asList(files);
    }
}
