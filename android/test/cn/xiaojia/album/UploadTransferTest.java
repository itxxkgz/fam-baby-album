package cn.xiaojia.album;

import java.io.*;
import java.util.*;

public final class UploadTransferTest {
    public static void main(String[] args) throws Exception {
        byte[] photo = new byte[900_000];
        new Random(17).nextBytes(photo);
        ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
        List<Long> updates = new ArrayList<>();
        UploadTransfer.Listener listener =
                new UploadTransfer.Listener() {
                    public void sent(long bytes) {
                        updates.add(bytes);
                    }

                    public void saving() {
                        throw new AssertionError("服务器确认必须在完整请求发出后等待");
                    }
                };
        UploadTransfer.copy(new ByteArrayInputStream(photo), uploaded, listener);
        require(Arrays.equals(photo, uploaded.toByteArray()));
        require(updates.get(0) > 0 && updates.get(0) < photo.length);
        require(updates.get(updates.size() - 1) == photo.length);
        for (int i = 1; i < updates.size(); i++) require(updates.get(i) >= updates.get(i - 1));
        require(UploadTransfer.percent(5_000_000_000L, 10_000_000_000L) == 50);
        require(UploadTransfer.percent(10, 0) == -1 && UploadTransfer.percent(10, -1) == -1);
        require(UploadTransfer.percent(100, 100) == 99 && UploadTransfer.percent(120, 100) == 99);
        require(UploadTransfer.needsConfirmation(true, 0));
        require(UploadTransfer.needsConfirmation(true, 503));
        require(!UploadTransfer.needsConfirmation(true, 413));
        require(!UploadTransfer.needsConfirmation(false, 0));
        updates.clear();
        UploadTransfer.copy(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), listener);
        require(updates.equals(Collections.singletonList(0L)));
        updates.clear();
        try {
            UploadTransfer.copy(
                    new ByteArrayInputStream(photo),
                    new OutputStream() {
                        public void write(int b) throws IOException {
                            throw new IOException();
                        }

                        public void write(byte[] b, int off, int len) throws IOException {
                            throw new IOException();
                        }
                    },
                    listener);
            throw new AssertionError("写入失败时应中断本次上传");
        } catch (IOException expected) {
            require(updates.isEmpty());
        }
    }

    private static void require(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
