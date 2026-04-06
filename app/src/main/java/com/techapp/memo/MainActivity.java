package com.techapp.memo;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.graphics.Color;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView webView;

    // ===== Android Bridge：提供给 JS 调用的接口 =====
    public class AndroidBridge {

        private Context mContext;

        public AndroidBridge(Context context) {
            this.mContext = context;
        }

        /**
         * JS 调用：saveCSV(csvContent, fileName)
         * 将 CSV 内容保存到手机下载目录
         */
        @JavascriptInterface
        public String saveCSV(String csvContent, String fileName) {
            try {
                String savedPath = "";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ 使用 MediaStore
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri itemUri = mContext.getContentResolver().insert(collection, values);

                    if (itemUri != null) {
                        try (OutputStream os = mContext.getContentResolver().openOutputStream(itemUri);
                             Writer writer = new OutputStreamWriter(os, "UTF-8")) {
                            writer.write(csvContent);
                        }
                        values.clear();
                        values.put(MediaStore.Downloads.IS_PENDING, 0);
                        mContext.getContentResolver().update(itemUri, values, null, null);
                        savedPath = "下载/" + fileName;
                    }
                } else {
                    // Android 9 及以下：直接写到下载目录
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) downloadsDir.mkdirs();
                    File outFile = new File(downloadsDir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(outFile);
                         Writer writer = new OutputStreamWriter(fos, "UTF-8")) {
                        writer.write(csvContent);
                    }
                    savedPath = outFile.getAbsolutePath();
                }

                // Toast 需要在主线程执行
                final String msg = "已导出到: " + savedPath;
                runOnUiThread(() -> Toast.makeText(mContext, msg, Toast.LENGTH_LONG).show());
                return "导出成功 📊 已保存到下载目录";

            } catch (Exception e) {
                final String errMsg = "导出失败: " + e.getMessage();
                runOnUiThread(() -> Toast.makeText(mContext, errMsg, Toast.LENGTH_SHORT).show());
                return "导出失败，请检查存储权限";
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 沉浸式状态栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.parseColor("#050d1a"));
            window.setNavigationBarColor(Color.parseColor("#050d1a"));
        }

        // 全屏
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        // WebView 配置
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);      // 启用 localStorage
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(false);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.setBackgroundColor(Color.parseColor("#050d1a"));

        // 注册 JS 接口
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");

        // 加载本地 HTML
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
