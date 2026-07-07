package com.example.viby.ui;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.viby.R;
import com.example.viby.util.YtCookies;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * Логин в Google/YouTube через WebView. Как только пользователь оказался
 * на youtube.com с авторизационными куками — экспортируем их для yt-dlp.
 */
public class YoutubeLoginActivity extends AppCompatActivity {

    private static final String LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube"
                    + "&continue=https://m.youtube.com/";

    private WebView webView;
    private boolean done;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_login);

        MaterialToolbar toolbar = findViewById(R.id.loginToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.loginWebView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                maybeFinishLogin(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                maybeFinishLogin(url);
            }
        });
        webView.loadUrl(LOGIN_URL);
    }

    /** Пользователь долистал до youtube.com → пробуем снять куки. */
    private void maybeFinishLogin(String url) {
        if (done || url == null || !url.contains("youtube.com")) {
            return;
        }
        CookieManager.getInstance().flush();
        if (YtCookies.exportFromWebView(this)) {
            done = true;
            Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show();
            finish();
        }
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
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
