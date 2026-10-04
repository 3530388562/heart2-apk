package com.wahaha.particleheart;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 原生 WebView 外壳：把粒子爱心网页（assets/index.html）包成 APK。
 * 摄像头相关的两件关键事都在这里做，网页本身无需改动：
 *   1) AndroidManifest 声明 CAMERA 权限 + Android 6+ 运行时申请；
 *   2) 重写 WebChromeClient.onPermissionRequest，把摄像头授予网页（否则 getUserMedia 静默失败）。
 */
public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1001;
    private WebView webView;
    private LocalServer server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);                  // MediaPipe 缓存 WASM 可能用到
        ws.setMediaPlaybackRequiresUserGesture(false);  // 允许静音视频自动播放（摄像头预览）
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient());

        // 关键点①：网页请求摄像头时，由原生层把权限授予页面。不写这个，getUserMedia 永远失败。
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean hasCam = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                            || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                    if (hasCam) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        request.deny();
                    }
                });
            }
        });

        // 关键点②：Android 6+ 必须先把 CAMERA 权限申请下来，页面才能拿到摄像头
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }

        // 用本地 HTTP 服务托管页面 → 保证 isSecureContext === true（getUserMedia 前置条件），
        // 规避个别 WebView 不把 file:// 当安全上下文的坑。仅绑定 127.0.0.1，对外不可达。
        try {
            server = new LocalServer(getAssets());
            server.start();
            webView.loadUrl("http://127.0.0.1:8080/");
        } catch (IOException e) {
            webView.loadUrl("file:///android_asset/index.html");   // 端口占用等异常时兜底
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, perms, grantResults);
        // 无需额外处理：用户点网页里的「手势」按钮时才会调用 getUserMedia，那时权限应已授予；
        // 若用户此前拒绝了系统权限，网页会走自身的兜底提示。
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }

    /** 极简本地静态服务器：仅把 assets/index.html 以 text/html 返回，绑定 127.0.0.1。 */
    private static class LocalServer {
        private final android.content.res.AssetManager assets;
        private ServerSocket ss;
        private Thread thread;

        LocalServer(android.content.res.AssetManager assets) { this.assets = assets; }

        void start() throws IOException {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", 8080));
            thread = new Thread(this::loop);
            thread.start();
        }

        private void loop() {
            while (ss != null && !ss.isClosed()) {
                try {
                    handle(ss.accept());
                } catch (IOException e) {
                    break;
                }
            }
        }

        private void handle(Socket s) throws IOException {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String reqLine = br.readLine();
                String path = "/index.html";
                if (reqLine != null) {
                    String[] parts = reqLine.split(" ");
                    if (parts.length >= 2 && parts[1].startsWith("/")) path = parts[1];
                }
                // 只返回 assets 内文件，防目录穿越（../ 一律回退首页）
                String assetName = path.replaceAll("^/+", "");
                int q = assetName.indexOf('?');
                if (q >= 0) assetName = assetName.substring(0, q);
                if (assetName.isEmpty() || assetName.contains("..")) assetName = "index.html";

                OutputStream os = s.getOutputStream();
                try {
                    byte[] data = readAsset(assetName);
                    String header = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: " + mimeOf(assetName) + "\r\n"
                            + "Content-Length: " + data.length + "\r\n"
                            + "Access-Control-Allow-Origin: *\r\n"
                            + "Connection: close\r\n\r\n";
                    os.write(header.getBytes(StandardCharsets.UTF_8));
                    os.write(data);
                } catch (IOException notFound) {
                    String body = "404 Not Found";
                    String header = "HTTP/1.1 404 Not Found\r\n"
                            + "Content-Type: text/plain; charset=utf-8\r\n"
                            + "Content-Length: " + body.length() + "\r\n"
                            + "Connection: close\r\n\r\n";
                    os.write(header.getBytes(StandardCharsets.UTF_8));
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                os.flush();
            } finally {
                try { s.close(); } catch (IOException ignored) {}
            }
        }

        private static String mimeOf(String name) {
            name = name.toLowerCase();
            if (name.endsWith(".html")) return "text/html; charset=utf-8";
            if (name.endsWith(".js"))   return "application/javascript; charset=utf-8";
            if (name.endsWith(".wasm")) return "application/wasm";
            return "application/octet-stream";   // .data / .binarypb / .tflite 等
        }

        private byte[] readAsset(String name) throws IOException {
            InputStream is = assets.open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
            return bos.toByteArray();
        }

        void stop() {
            try { if (ss != null) ss.close(); } catch (IOException ignored) {}
            if (thread != null) thread.interrupt();
        }
    }
}
