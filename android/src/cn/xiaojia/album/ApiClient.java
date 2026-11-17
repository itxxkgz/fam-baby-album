package cn.xiaojia.album;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class ApiClient {
    // disconnect() can wait for a socket read on some platforms. Keep it off the
    // UI thread, with a fixed worker count instead of a thread for every swipe.
    private static final ExecutorService DOWNLOAD_CANCELLATIONS =
            Executors.newFixedThreadPool(
                    2,
                    task -> {
                        Thread thread = new Thread(task, "album-download-cancel");
                        thread.setDaemon(true);
                        return thread;
                    });

    static final class DownloadCancellation {
        private volatile boolean cancelled;
        private HttpURLConnection connection;

        void cancel() {
            HttpURLConnection active;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                active = connection;
            }
            if (active != null)
                DOWNLOAD_CANCELLATIONS.execute(
                        () -> {
                            try {
                                active.disconnect();
                            } catch (RuntimeException ignored) {
                            }
                        });
        }

        boolean isCancelled() {
            return cancelled;
        }

        void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("照片读取已取消");
        }

        synchronized void attach(HttpURLConnection active) throws InterruptedIOException {
            check();
            connection = active;
        }

        synchronized void detach(HttpURLConnection active) {
            if (connection == active) connection = null;
        }
    }

    static final class HttpError extends IOException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Context context;
    volatile String baseUrl;
    volatile String token;
    volatile String childId;

    ApiClient(Context context) {
        this.context = context.getApplicationContext();
        baseUrl = context.getSharedPreferences("album", 0).getString("server", "");
        token = context.getSharedPreferences("album", 0).getString("token", "");
        childId = context.getSharedPreferences("album", 0).getString("selected_child", "");
    }

    void configure(String address) throws IOException {
        String normalized = address.trim().replaceAll("/+$", "");
        if (!normalized.matches("^https?://.+")) normalized = "http://" + normalized;
        try {
            URL parsed = new URL(normalized);
            if (parsed.getHost().isEmpty()
                    || parsed.getUserInfo() != null
                    || parsed.getQuery() != null
                    || parsed.getRef() != null) throw new IOException("服务器地址不正确，请检查后重试");
        } catch (MalformedURLException error) {
            throw new IOException("请输入正确的服务器地址");
        }
        if (!normalized.equals(baseUrl)) childId = "";
        baseUrl = normalized;
    }

    void saveLogin(String newToken) {
        token = newToken;
        context.getSharedPreferences("album", 0)
                .edit()
                .putString("server", baseUrl)
                .putString("token", token)
                .apply();
    }

    void logout() {
        token = "";
        childId = "";
        context.getSharedPreferences("album", 0).edit().remove("token")
                .remove("selected_child").remove("selected_child_owner").apply();
    }

    void selectChild(String value) {
        childId = value == null ? "" : value;
        context.getSharedPreferences("album", 0).edit().putString("selected_child", childId).apply();
    }

    String cacheScope() { return BabyScope.cacheScope(baseUrl, token, childId); }

    ApiClient snapshot() {
        ApiClient copy = new ApiClient(context);
        copy.baseUrl = baseUrl;
        copy.token = token;
        copy.childId = childId;
        return copy;
    }

    String url(String path) throws IOException {
        String result =
                path.startsWith("http://") || path.startsWith("https://")
                        ? path
                        : baseUrl + (path.startsWith("/") ? "" : "/") + path;
        URL origin = new URL(baseUrl), target = new URL(result);
        int op = origin.getPort() < 0 ? origin.getDefaultPort() : origin.getPort();
        int tp = target.getPort() < 0 ? target.getDefaultPort() : target.getPort();
        if (!origin.getProtocol().equals(target.getProtocol())
                || !origin.getHost().equalsIgnoreCase(target.getHost())
                || op != tp) throw new IOException("文件地址与当前服务器不一致");
        return result;
    }

    Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        if (!childId.isEmpty()) headers.put("X-FAM-Child", childId);
        return headers;
    }

    HttpURLConnection connection(String path, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url(path)).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (!childId.isEmpty()) connection.setRequestProperty("X-FAM-Child", childId);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    JSONObject request(String method, String path, JSONObject body) throws Exception {
        return request(method, path, body, null);
    }

    JSONObject request(
            String method, String path, JSONObject body, DownloadCancellation cancellation)
            throws Exception {
        if (cancellation != null) cancellation.check();
        HttpURLConnection connection = connection(path, method);
        try {
            if (cancellation != null) {
                cancellation.attach(connection);
                cancellation.check();
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            if (cancellation != null) cancellation.check();
            String response =
                    readText(
                            code >= 400 ? connection.getErrorStream() : connection.getInputStream(),
                            cancellation);
            if (cancellation != null) cancellation.check();
            if (code < 200 || code >= 300) throw new HttpError(code, message(code, response));
            if (response.isEmpty()) return new JSONObject();
            if (response.trim().startsWith("["))
                return new JSONObject().put("items", new JSONArray(response));
            return new JSONObject(response);
        } finally {
            if (cancellation != null) cancellation.detach(connection);
            connection.disconnect();
        }
    }

    void download(String path, OutputStream target) throws IOException {
        download(path, target, null);
    }

    void download(String path, OutputStream target, DownloadCancellation cancellation)
            throws IOException {
        if (cancellation != null) cancellation.check();
        HttpURLConnection connection = connection(path, "GET");
        try {
            if (cancellation != null) {
                cancellation.attach(connection);
                cancellation.check();
            }
            int status = connection.getResponseCode();
            if (cancellation != null) cancellation.check();
            if (status != 200)
                throw new HttpError(status, message(status, readText(connection.getErrorStream())));
            try (InputStream stream = connection.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                while (true) {
                    if (cancellation != null) cancellation.check();
                    int count = stream.read(buffer);
                    if (cancellation != null) cancellation.check();
                    if (count == -1) break;
                    target.write(buffer, 0, count);
                }
            }
        } finally {
            if (cancellation != null) cancellation.detach(connection);
            connection.disconnect();
        }
    }

    JSONObject upload(Uri source, String name, String mime, String fallbackCapturedAt)
            throws Exception {
        return upload(source, name, mime, fallbackCapturedAt, null);
    }

    JSONObject upload(Uri source, String name, String mime, String fallbackCapturedAt, JSONArray children)
            throws Exception {
        String boundary = "AlbumUpload" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = connection("/api/upload", "POST");
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(64 * 1024);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try {
            try (OutputStream raw = new BufferedOutputStream(connection.getOutputStream())) {
                if (children != null) write(raw, "--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"child_ids\"\r\n\r\n"
                        + children.toString() + "\r\n");
                if (fallbackCapturedAt != null && !fallbackCapturedAt.isEmpty()) {
                    write(
                            raw,
                            "--"
                                    + boundary
                                    + "\r\n"
                                    + "Content-Disposition: form-data;"
                                    + " name=\"fallback_captured_at\"\r\n\r\n"
                                    + fallbackCapturedAt
                                    + "\r\n");
                }
                String safeName = name.replace("\"", "").replace("\r", "").replace("\n", "");
                write(
                        raw,
                        "--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                                + safeName
                                + "\"\r\nContent-Type: "
                                + mime
                                + "\r\n\r\n");
                try (InputStream stream = context.getContentResolver().openInputStream(source)) {
                    if (stream == null) throw new IOException("无法读取选中的文件");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = stream.read(buffer)) != -1) raw.write(buffer, 0, count);
                }
                write(raw, "\r\n--" + boundary + "--\r\n");
            }
            int code = connection.getResponseCode();
            String response =
                    readText(
                            code >= 400
                                    ? connection.getErrorStream()
                                    : connection.getInputStream());
            if (code < 200 || code >= 300) throw new HttpError(code, message(code, response));
            return new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            return "";
        }
    }

    private static void write(OutputStream stream, String text) throws IOException {
        stream.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String readText(InputStream stream) throws IOException {
        return readText(stream, null);
    }

    private static String readText(InputStream stream, DownloadCancellation cancellation)
            throws IOException {
        if (cancellation != null) cancellation.check();
        if (stream == null) return "";
        try (InputStream input = stream;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            while (true) {
                if (cancellation != null) cancellation.check();
                int count = input.read(buffer);
                if (cancellation != null) cancellation.check();
                if (count == -1) break;
                output.write(buffer, 0, count);
                if (output.size() > 8 * 1024 * 1024) throw new IOException("服务器返回的数据过多，请缩小查看范围");
            }
            return output.toString("UTF-8");
        }
    }

    static String message(int code, String response) {
        try {
            JSONObject object = new JSONObject(response);
            Object detail = object.opt("detail");
            String text =
                    detail instanceof String ? (String) detail : object.optString("message", "");
            if (!text.isEmpty()
                    && text.length() < 200
                    && text.matches(".*[\\u4e00-\\u9fff].*")
                    && !text.contains("Traceback")
                    && !text.contains("http")) return text;
        } catch (Exception ignored) {
        }
        if (code == 401) return "登录已失效，请重新登录";
        if (code == 403) return "当前账号没有这项操作权限，请联系相册管理员";
        if (code == 404) return "没有找到这项内容";
        if (code == 413) return "文件超过服务器允许的大小";
        if (code == 409) return "当前状态已变化，请刷新后重试";
        if (code == 422) return "填写的信息不符合要求，请检查后重试";
        if (code == 429) return "操作较频繁，请稍后重试";
        return "服务器暂时无法完成操作，请稍后重试";
    }
}
