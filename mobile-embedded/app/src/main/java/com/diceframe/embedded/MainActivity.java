package com.diceframe.embedded;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 嵌入模式 POC:把主仓库 Python 后端跑在本机 127.0.0.1:18000,
 * WebView 加载后端直接服务的完整 WebUI(不依赖任何外部服务器,除 AI API 外离线可用)。
 */
public class MainActivity extends Activity {

    private static final String SERVER_URL = "http://127.0.0.1:18000/";
    private static final String ASSET_ROOT = "diceframe_backend";

    private TextView status;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setPadding(48, 96, 48, 32);
        layout.addView(status);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        layout.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(layout);

        status.setText("正在启动本机后端…(首次启动需要解压资源)");
        new Thread(this::boot).start();
    }

    private void boot() {
        try {
            File appRoot = new File(getFilesDir(), "app_root");
            copyAssetDir(ASSET_ROOT, appRoot);
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(getApplicationContext()));
            }
            Python.getInstance().getModule("poc_boot").callAttr("start", appRoot.getAbsolutePath());
            waitUntilReady(120);
            runOnUiThread(() -> {
                String token = readAccessToken(appRoot);
                status.setText("本机后端已就绪:" + SERVER_URL
                        + (token.isEmpty() ? "" : "\n访问密码:" + token));
                webView.loadUrl(SERVER_URL);
            });
        } catch (Throwable e) {
            String message = "启动失败:" + e;
            runOnUiThread(() -> status.setText(message));
        }
    }

    /** 递归解压 APK assets 中的后端目录;每次启动覆盖,避免版本标记逻辑引入陈旧资源。 */
    private void copyAssetDir(String assetPath, File outDir) throws IOException {
        String[] entries = getAssets().list(assetPath);
        if (entries == null || entries.length == 0) {
            return;
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建目录:" + outDir);
        }
        for (String entry : entries) {
            String childPath = assetPath + "/" + entry;
            File outFile = new File(outDir, entry);
            String[] nested = getAssets().list(childPath);
            if (nested != null && nested.length > 0) {
                copyAssetDir(childPath, outFile);
            } else {
                copyAssetFile(childPath, outFile);
            }
        }
    }

    private void copyAssetFile(String assetPath, File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录:" + parent);
        }
        try (InputStream in = getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    /** 首次启动生成的访问密码存在 data/access_token.txt;设备上没有控制台,直接显示给用户。 */
    private String readAccessToken(File appRoot) {
        File tokenFile = new File(appRoot, "data/access_token.txt");
        try (InputStream in = new java.io.FileInputStream(tokenFile)) {
            java.util.Scanner scanner = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next().trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void waitUntilReady(int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (serverResponds()) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IOException("本机后端在 " + timeoutSeconds + " 秒内未就绪");
    }

    private boolean serverResponds() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(SERVER_URL).openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            return connection.getResponseCode() < 500;
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
