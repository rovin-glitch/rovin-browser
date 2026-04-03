package com.igalia.wolvic.utils;

import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RovinAssetHttpServer {
    private static final String LOGTAG = SystemUtils.createLogtag(RovinAssetHttpServer.class);
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private final AssetManager mAssets;
    private final String mAssetRoot;
    private final ExecutorService mExecutor;
    private ServerSocket mServerSocket;
    private Thread mAcceptThread;
    private volatile boolean mRunning;
    private volatile int mPort = -1;

    public RovinAssetHttpServer(@NonNull AssetManager assets, @Nullable String assetRoot) {
        mAssets = assets;
        mAssetRoot = normalizeAssetRoot(assetRoot);
        mExecutor = Executors.newCachedThreadPool();
    }

    @Nullable
    public synchronized String start() {
        if (mRunning && mPort > 0) {
            return getBaseUrl();
        }

        try {
            mServerSocket = new ServerSocket(0, 8, InetAddress.getByName(LOOPBACK_HOST));
            mPort = mServerSocket.getLocalPort();
            mRunning = true;
            mAcceptThread = new Thread(this::acceptLoop, "RovinAssetHttpServer");
            mAcceptThread.setDaemon(true);
            mAcceptThread.start();
            Log.i(LOGTAG, "Started bundled asset server on port " + mPort);
            return getBaseUrl();
        } catch (IOException e) {
            Log.e(LOGTAG, "Failed to start bundled asset server", e);
            stop();
            return null;
        }
    }

    public synchronized void stop() {
        mRunning = false;
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException ignored) {
            }
            mServerSocket = null;
        }
        if (mAcceptThread != null) {
            mAcceptThread.interrupt();
            mAcceptThread = null;
        }
        mExecutor.shutdownNow();
        mPort = -1;
    }

    @Nullable
    private String getBaseUrl() {
        return mPort > 0 ? "http://" + LOOPBACK_HOST + ":" + mPort + "/" : null;
    }

    private void acceptLoop() {
        while (mRunning && mServerSocket != null) {
            try {
                Socket socket = mServerSocket.accept();
                mExecutor.execute(() -> handleConnection(socket));
            } catch (IOException e) {
                if (mRunning) {
                    Log.w(LOGTAG, "Bundled asset server accept failed", e);
                }
                return;
            }
        }
    }

    private void handleConnection(@NonNull Socket socket) {
        try (Socket ignored = socket;
             BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII))) {

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeError(output, 400, "Bad Request");
                return;
            }

            String method = parts[0];
            String rawPath = parts[1];

            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                // Ignore request headers for now.
            }

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                writeError(output, 405, "Method Not Allowed");
                return;
            }

            String assetPath = resolveAssetPath(rawPath);
            if (assetPath == null) {
                writeError(output, 404, "Not Found");
                return;
            }

            byte[] payload = readAsset(assetPath);
            if (payload == null) {
                writeError(output, 404, "Not Found");
                return;
            }

            writeOk(output, guessContentType(assetPath), payload, "HEAD".equals(method));
        } catch (IOException e) {
            Log.w(LOGTAG, "Bundled asset request failed", e);
        }
    }

    @Nullable
    private String resolveAssetPath(@NonNull String rawPath) {
        String path = rawPath;
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }

        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            path = "index.html";
        }
        if (path.contains("..")) {
            return null;
        }
        if (mAssetRoot.isEmpty()) {
            return path;
        }
        return mAssetRoot + "/" + path;
    }

    @Nullable
    private byte[] readAsset(@NonNull String assetPath) {
        try (InputStream stream = mAssets.open(assetPath);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            Log.w(LOGTAG, "Bundled asset missing: " + assetPath);
            return null;
        }
    }

    private void writeOk(@NonNull BufferedOutputStream output, @NonNull String contentType, @NonNull byte[] payload, boolean headOnly) throws IOException {
        output.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + payload.length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (!headOnly) {
            output.write(payload);
        }
        output.flush();
    }

    private void writeError(@NonNull BufferedOutputStream output, int statusCode, @NonNull String reason) throws IOException {
        byte[] payload = reason.getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 " + statusCode + " " + reason + "\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + payload.length + "\r\n" +
                "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(payload);
        output.flush();
    }

    @NonNull
    private String guessContentType(@NonNull String assetPath) {
        String lowercase = assetPath.toLowerCase(Locale.US);
        if (lowercase.endsWith(".html")) return "text/html; charset=utf-8";
        if (lowercase.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lowercase.endsWith(".json")) return "application/json; charset=utf-8";
        if (lowercase.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        if (lowercase.endsWith(".css")) return "text/css; charset=utf-8";
        if (lowercase.endsWith(".png")) return "image/png";
        if (lowercase.endsWith(".jpg") || lowercase.endsWith(".jpeg")) return "image/jpeg";
        if (lowercase.endsWith(".svg")) return "image/svg+xml";
        if (lowercase.endsWith(".mp3")) return "audio/mpeg";
        if (lowercase.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    @NonNull
    private static String normalizeAssetRoot(@Nullable String assetRoot) {
        if (assetRoot == null) {
            return "";
        }

        String normalized = assetRoot.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
