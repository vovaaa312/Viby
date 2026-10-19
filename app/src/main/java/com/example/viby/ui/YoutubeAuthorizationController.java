package com.example.viby.ui;

import android.app.PendingIntent;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.example.viby.R;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;

import java.util.Collections;
import java.util.function.Consumer;

/** Owns Google Identity authorization and the single in-flight YouTube OAuth request. */
final class YoutubeAuthorizationController {

    private static final String YOUTUBE_WRITE_SCOPE =
            "https://www.googleapis.com/auth/youtube.force-ssl";

    private final MainActivity activity;
    private final YoutubeAuthorizationSession session = new YoutubeAuthorizationSession();
    private final ActivityResultLauncher<IntentSenderRequest> launcher;

    YoutubeAuthorizationController(MainActivity activity) {
        this.activity = activity;
        launcher = activity.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    if (result.getData() == null) {
                        finish(null, new IllegalStateException(activity.getString(
                                R.string.youtube_sync_auth_cancelled)));
                        return;
                    }
                    try {
                        AuthorizationResult authorization = Identity
                                .getAuthorizationClient(activity)
                                .getAuthorizationResultFromIntent(result.getData());
                        finish(authorization, null);
                    } catch (ApiException error) {
                        finish(null, error);
                    }
                });
    }

    void request(Consumer<String> onToken, Consumer<Exception> onError) {
        if (!session.begin(onToken, onError)) {
            onError.accept(new IllegalStateException(
                    activity.getString(R.string.youtube_sync_authorizing)));
            return;
        }
        AuthorizationRequest request = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(
                        new Scope(YOUTUBE_WRITE_SCOPE)))
                .build();
        Identity.getAuthorizationClient(activity).authorize(request)
                .addOnSuccessListener(authorization -> {
                    if (authorization.hasResolution()) {
                        PendingIntent pendingIntent = authorization.getPendingIntent();
                        if (pendingIntent == null) {
                            finish(null, new IllegalStateException(activity.getString(
                                    R.string.youtube_sync_auth_cancelled)));
                            return;
                        }
                        launcher.launch(new IntentSenderRequest.Builder(
                                pendingIntent.getIntentSender()).build());
                    } else {
                        finish(authorization, null);
                    }
                })
                .addOnFailureListener(error -> finish(null,
                        error instanceof Exception ? (Exception) error : new Exception(error)));
    }

    void destroy() {
        session.clear();
    }

    private void finish(AuthorizationResult authorization, Exception error) {
        String accessToken = authorization != null ? authorization.getAccessToken() : null;
        session.complete(accessToken, error, new IllegalStateException(
                activity.getString(R.string.youtube_sync_auth_cancelled)));
    }
}
