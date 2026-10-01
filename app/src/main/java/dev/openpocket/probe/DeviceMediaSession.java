package dev.openpocket.probe;

import android.content.ContentResolver;

interface DeviceMediaSession extends AutoCloseable {
    void open();
    void refreshMedia();
    void download(MediaCatalog.Item item, ContentResolver resolver);
    @Override void close();
}
