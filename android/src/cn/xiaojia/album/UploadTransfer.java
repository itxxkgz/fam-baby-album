package cn.xiaojia.album;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class UploadTransfer {
    interface Listener {
        void sent(long bytes) throws IOException;

        void saving() throws IOException;
    }

    static void copy(InputStream source, OutputStream destination, Listener listener)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long written = 0, lastUpdate = 0;
        int count;
        while ((count = source.read(buffer)) != -1) {
            destination.write(buffer, 0, count);
            written += count;
            long now = System.nanoTime();
            if (lastUpdate == 0 || now - lastUpdate >= 200_000_000L) {
                listener.sent(written);
                lastUpdate = now;
            }
        }
        listener.sent(written);
    }

    static boolean needsConfirmation(boolean waitingForReply, int httpStatus) {
        return waitingForReply && (httpStatus == 0 || httpStatus >= 500);
    }

    static int percent(long bytes, long size) {
        return size <= 0 ? -1 : (int) Math.min(99, 100.0 * bytes / size);
    }
}
