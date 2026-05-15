package com.igalia.wolvic.browser;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.BuildConfig;
import com.igalia.wolvic.RovinProduct;
import com.igalia.wolvic.browser.api.WAllowOrDeny;
import com.igalia.wolvic.browser.api.WAutocomplete;
import com.igalia.wolvic.browser.api.WResult;
import com.igalia.wolvic.browser.api.WSession;
import com.igalia.wolvic.browser.api.WSlowScriptResponse;
import com.igalia.wolvic.browser.components.LoginDelegateWrapper;
import com.igalia.wolvic.browser.engine.Session;
import com.igalia.wolvic.browser.engine.SessionState;
import com.igalia.wolvic.browser.engine.SessionStore;
import com.igalia.wolvic.db.SitePermission;
import com.igalia.wolvic.ui.viewmodel.SitePermissionViewModel;
import com.igalia.wolvic.ui.widgets.UIWidget;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;
import com.igalia.wolvic.ui.widgets.WidgetPlacement;
import com.igalia.wolvic.ui.widgets.WindowWidget;
import com.igalia.wolvic.ui.widgets.prompts.AlertPromptWidget;
import com.igalia.wolvic.ui.widgets.prompts.AuthPromptWidget;
import com.igalia.wolvic.ui.widgets.prompts.ChoicePromptWidget;
import com.igalia.wolvic.ui.widgets.prompts.ColorPromptWidget;
import com.igalia.wolvic.ui.widgets.prompts.ConfirmPromptWidget;
import com.igalia.wolvic.ui.widgets.prompts.DateTimePromptWidget;
import com.igalia.wolvic.ui.widgets.prompts.FilePromptWidget;
import com.igalia.wolvic.ui.widgets.prompts.PromptWidget;
import com.igalia.wolvic.ui.widgets.prompts.SaveLoginPromptWidget;
import com.igalia.wolvic.ui.widgets.prompts.SelectLoginPromptWidget;
import com.igalia.wolvic.ui.widgets.prompts.TextPromptWidget;
import com.igalia.wolvic.ui.widgets.settings.SettingsView;
import com.igalia.wolvic.utils.StringUtils;
import com.igalia.wolvic.utils.UrlUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import android.util.Base64;
import mozilla.components.concept.storage.Login;

public class PromptDelegate implements
        WSession.PromptDelegate,
        WindowWidget.WindowListener,
        WSession.NavigationDelegate,
        WSession.ContentDelegate {
    private static final String LOGTAG = "PromptDelegate";
    private static final String ROVIN_HAPTIC_PROMPT_MESSAGE = "__rovin_haptic__";
    private static final String ROVIN_SAVE_PROMPT_MESSAGE = "__rovin_save__";
    private static final String ROVIN_READY_PROMPT_MESSAGE = "__rovin_ready__";
    private static final String ROVIN_LOG_PROMPT_PREFIX = "__rovin_log__:";
    private static final String ROVIN_SAVE_LOGS_PROMPT = "__rovin_save_logs__";
    private static final String ROVIN_LOADED_PROMPT_MESSAGE = "__rovin_is_fully_loaded__";
    private static final String ROVIN_IMMERSIVE_ACTIVE_MESSAGE = "__rovin_immersive_active__";
    private static final int ROVIN_SAVE_MAX_BYTES = 256 * 1024;

    private PromptWidget mPrompt;
    private ConfirmPromptWidget mSlowScriptPrompt;
    private Context mContext;
    private WindowWidget mAttachedWindow;
    private List<SitePermission> mAllowedPopUpSites;
    private List<SitePermission> mSavedLoginBlockedSites;
    private SitePermissionViewModel mViewModel;
    private WidgetManagerDelegate mWidgetManager;
    private SaveLoginPromptWidget mSaveLoginPrompt;
    private SelectLoginPromptWidget mSelectLoginPrompt;

    public PromptDelegate(@NonNull Context context) {
        mContext = context;
        mWidgetManager = (WidgetManagerDelegate) mContext;
        mViewModel = new SitePermissionViewModel(((Application)context.getApplicationContext()));
        mAllowedPopUpSites = new ArrayList<>();
        mSavedLoginBlockedSites = new ArrayList<>();
        mSaveLoginPrompt = null;
        mSelectLoginPrompt = null;
    }

    public void attachToWindow(@NonNull WindowWidget window) {
        if (window == mAttachedWindow) {
            return;
        }
        detachFromWindow();

        mAttachedWindow = window;
        mAttachedWindow.addWindowListener(this);
        mViewModel.getAll(SitePermission.SITE_PERMISSION_POPUP).observeForever(mPopUpSiteObserver);
        mViewModel.getAll(SitePermission.SITE_PERMISSION_AUTOFILL).observeForever(mSavedLoginExceptionsObserver);

        if (getSession() != null) {
            setUpSession(getSession());
        }
    }

    public void detachFromWindow() {
        if (getSession() != null) {
            cleanSession(getSession());
        }

        if (mAttachedWindow != null) {
            mAttachedWindow.removeWindowListener(this);
            mAttachedWindow = null;
        }
        mViewModel.getAll(SitePermission.SITE_PERMISSION_POPUP).removeObserver(mPopUpSiteObserver);
        mViewModel.getAll(SitePermission.SITE_PERMISSION_AUTOFILL).removeObserver(mSavedLoginExceptionsObserver);
    }

    private Session getSession() {
        if (mAttachedWindow != null) {
            return mAttachedWindow.getSession();
        }
        return null;
    }

    private void setUpSession(@NonNull Session aSession) {
        aSession.setPromptDelegate(this);
        aSession.addNavigationListener(this);
        aSession.addContentListener(this);
    }

    private void cleanSession(@NonNull Session aSession) {
        aSession.setPromptDelegate(null);
        aSession.removeNavigationListener(this);
        aSession.removeContentListener(this);
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onFilePrompt(@NonNull final WSession session, @NonNull final WSession.PromptDelegate.FilePrompt prompt) {
        final WResult<PromptResponse> result = WResult.create();

        FilePromptWidget filePromptWidget = new FilePromptWidget(mContext);
        filePromptWidget.setIsMultipleSelection(prompt.type() == FilePrompt.Type.MULTIPLE);
        filePromptWidget.setMimeTypes(prompt.mimeTypes());
        mPrompt = filePromptWidget;
        mPrompt.setTitle(prompt.title());
        mPrompt.setPromptDelegate(new FilePromptWidget.FilePromptDelegate() {
            @Override
            public void confirm(@NonNull Uri[] uris) {
                result.complete(prompt.confirm(mContext, uris));
            }

            @Override
            public void dismiss() {
                result.complete(prompt.dismiss());
            }
        });

        mPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
        mPrompt.getPlacement().parentAnchorY = 0.0f;
        mPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
        mPrompt.show(UIWidget.REQUEST_FOCUS, true);

        return result;
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onAlertPrompt(@NonNull WSession session, @NonNull AlertPrompt alertPrompt) {
        final WResult<PromptResponse> result = WResult.create();

        mPrompt = new AlertPromptWidget(mContext);
        mPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
        mPrompt.getPlacement().parentAnchorY = 0.0f;
        mPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
        mPrompt.setTitle(alertPrompt.title());
        mPrompt.setMessage(alertPrompt.message());
        mPrompt.setPromptDelegate(() -> result.complete(alertPrompt.dismiss()));
        mPrompt.show(UIWidget.REQUEST_FOCUS, true);

        return result;
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onButtonPrompt(@NonNull WSession session, @NonNull ButtonPrompt buttonPrompt) {
        final WResult<PromptResponse> result = WResult.create();

        mPrompt = new ConfirmPromptWidget(mContext);
        mPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
        mPrompt.getPlacement().parentAnchorY = 0.0f;
        mPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
        mPrompt.setTitle(buttonPrompt.title());
        mPrompt.setMessage(buttonPrompt.message());
        ((ConfirmPromptWidget)mPrompt).setButtons(new String[] {
                mContext.getResources().getText(R.string.ok_button).toString(),
                mContext.getResources().getText(R.string.cancel_button).toString()
        });
        mPrompt.setPromptDelegate(new ConfirmPromptWidget.ConfirmPromptDelegate() {
            @Override
            public void confirm(int index) {
                result.complete(buttonPrompt.confirm(index));
            }

            @Override
            public void dismiss() {
                result.complete(buttonPrompt.dismiss());
            }
        });
        mPrompt.show(UIWidget.REQUEST_FOCUS, true);

        return result;
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onTextPrompt(@NonNull WSession session, @NonNull TextPrompt textPrompt) {
        final WResult<PromptResponse> result = WResult.create();

        if (handleRovinSavePrompt(textPrompt, result)) {
            return result;
        }

        if (handleRovinHapticPrompt(textPrompt, result)) {
            return result;
        }

        if (handleRovinReadyPrompt(textPrompt, result)) {
            return result;
        }

        if (handleRovinLogPrompt(textPrompt, result)) {
            return result;
        }

        if (handleRovinSaveLogsPrompt(textPrompt, result)) {
            return result;
        }

        if (handleRovinLoadedPrompt(textPrompt, result)) {
            return result;
        }

        if (handleRovinImmersiveActivePrompt(textPrompt, result)) {
            return result;
        }

        // ROVIN: Deadlock Safety Valve
        // If the message starts with __rovin_ but wasn't handled by the specific handlers above,
        // we must CONSUME it and return immediately. This prevents unhandled bridge messages
        // from showing a hidden blocking native dialog that deadlocks the startup.
        String message = textPrompt.message();
        if (message != null && message.startsWith("__rovin_")) {
            Log.w(LOGTAG, "Rovin Safety Valve: Consuming unhandled bridge message: " + message);
            result.complete(textPrompt.confirm("ok"));
            return result;
        }

        mPrompt = new TextPromptWidget(mContext);
        mPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
        mPrompt.getPlacement().parentAnchorY = 0.0f;
        mPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
        mPrompt.setTitle(textPrompt.title());
        mPrompt.setMessage(textPrompt.message());
        ((TextPromptWidget)mPrompt).setDefaultText(textPrompt.defaultValue());
        mPrompt.setPromptDelegate(new TextPromptWidget.TextPromptDelegate() {
            @Override
            public void confirm(String message) {
                result.complete(textPrompt.confirm(message));
            }

            @Override
            public void dismiss() {
                result.complete(textPrompt.dismiss());
            }
        });
        mPrompt.show(UIWidget.REQUEST_FOCUS, true);

        return result;
    }

    private boolean handleRovinSavePrompt(@NonNull TextPrompt textPrompt, @NonNull WResult<PromptResponse> result) {
        if (!ROVIN_SAVE_PROMPT_MESSAGE.equals(textPrompt.message())) {
            return false;
        }

        if (!RovinProduct.isRuntime() || !(mContext instanceof VRBrowserActivity)) {
            result.complete(textPrompt.confirm("error|not_runtime"));
            return true;
        }

        final Session engineSession = mAttachedWindow != null ? mAttachedWindow.getSession() : null;
        final String currentUri = engineSession != null ? engineSession.getCurrentUri() : null;
        if (!isTrustedRovinSaveOrigin(currentUri)) {
            Log.w(LOGTAG, "Blocked Rovin save prompt from untrusted origin. uri=" + currentUri);
            result.complete(textPrompt.confirm("error|untrusted_origin"));
            return true;
        }

        final String payload = textPrompt.defaultValue();
        if (payload == null || payload.isBlank()) {
            result.complete(textPrompt.confirm("error|missing_command"));
            return true;
        }

        try {
            if ("get".equals(payload)) {
                result.complete(textPrompt.confirm(readRovinSaveJson()));
                return true;
            }
            if ("clear".equals(payload)) {
                clearRovinSave();
                result.complete(textPrompt.confirm("ok"));
                return true;
            }
            if (payload.startsWith("set|")) {
                final String encoded = payload.substring(4);
                final byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
                if (decoded.length > ROVIN_SAVE_MAX_BYTES) {
                    result.complete(textPrompt.confirm("error|too_large"));
                    return true;
                }
                final String json = new String(decoded, StandardCharsets.UTF_8);
                validateJsonObject(json);
                writeRovinSaveAtomic(decoded);
                result.complete(textPrompt.confirm("ok"));
                return true;
            }

            result.complete(textPrompt.confirm("error|unknown_command"));
            return true;
        } catch (IllegalArgumentException error) {
            result.complete(textPrompt.confirm("error|invalid_base64"));
            return true;
        } catch (JSONException error) {
            result.complete(textPrompt.confirm("error|invalid_json"));
            return true;
        } catch (IOException error) {
            Log.e(LOGTAG, "Rovin save IO failed.", error);
            result.complete(textPrompt.confirm("error|io"));
            return true;
        } catch (Exception error) {
            Log.e(LOGTAG, "Rovin save handler failed.", error);
            result.complete(textPrompt.confirm("error|unexpected"));
            return true;
        }
    }

    private boolean handleRovinHapticPrompt(@NonNull TextPrompt textPrompt, @NonNull WResult<PromptResponse> result) {
        if (!ROVIN_HAPTIC_PROMPT_MESSAGE.equals(textPrompt.message())) {
            return false;
        }

        if (!(mContext instanceof VRBrowserActivity)) {
            result.complete(textPrompt.dismiss());
            return true;
        }
        VRBrowserActivity activity = (VRBrowserActivity) mContext;

        final String payload = textPrompt.defaultValue();
        if (payload == null || payload.isBlank()) {
            result.complete(textPrompt.confirm(""));
            return true;
        }

        final String[] parts = payload.split("\\|");
        if (parts.length < 3) {
            Log.w(LOGTAG, "Ignoring malformed Rovin haptic payload: " + payload);
            result.complete(textPrompt.confirm(""));
            return true;
        }

        final int controllerId = "right".equals(parts[0]) ? 0 : 1;
        float pulseDuration = 50.0f;
        float pulseIntensity = 0.5f;
        try {
            pulseDuration = Math.max(1.0f, Math.min(500.0f, Float.parseFloat(parts[1])));
            pulseIntensity = Math.max(0.0f, Math.min(1.0f, Float.parseFloat(parts[2])));
        } catch (NumberFormatException error) {
            Log.w(LOGTAG, "Ignoring malformed Rovin haptic values: " + payload, error);
        }

        activity.triggerHapticPulse(pulseDuration, pulseIntensity, controllerId);
        result.complete(textPrompt.confirm(""));
        return true;
    }

    private boolean handleRovinReadyPrompt(@NonNull TextPrompt textPrompt, @NonNull WResult<PromptResponse> result) {
        if (!ROVIN_READY_PROMPT_MESSAGE.equals(textPrompt.message())) {
            return false;
        }

        if (!(mContext instanceof VRBrowserActivity)) {
            result.complete(textPrompt.dismiss());
            return true;
        }

        VRBrowserActivity activity = (VRBrowserActivity) mContext;
        Log.i(LOGTAG, "PromptDelegate: __rovin_ready__ received. Signaling Activity.");
        activity.signalReadyForVr();
        result.complete(textPrompt.confirm("ok"));
        return true;
    }

    private boolean handleRovinLoadedPrompt(@NonNull TextPrompt textPrompt, @NonNull WResult<PromptResponse> result) {
        if (!ROVIN_LOADED_PROMPT_MESSAGE.equals(textPrompt.message())) {
            return false;
        }

        if (!(mContext instanceof VRBrowserActivity)) {
            result.complete(textPrompt.dismiss());
            return true;
        }

        VRBrowserActivity activity = (VRBrowserActivity) mContext;
        String loadedStr = textPrompt.defaultValue();
        boolean loaded = "true".equalsIgnoreCase(loadedStr);
        
        Log.d(LOGTAG, "PromptDelegate: __rovin_is_fully_loaded__ response: " + loadedStr);
        activity.handleRovinLoadedResult(loaded);
        
        result.complete(textPrompt.confirm("ok"));
        return true;
    }

    private boolean handleRovinImmersiveActivePrompt(@NonNull TextPrompt textPrompt, @NonNull WResult<PromptResponse> result) {
        if (!ROVIN_IMMERSIVE_ACTIVE_MESSAGE.equals(textPrompt.message())) {
            return false;
        }

        if (!(mContext instanceof VRBrowserActivity)) {
            result.complete(textPrompt.dismiss());
            return true;
        }

        VRBrowserActivity activity = (VRBrowserActivity) mContext;
        Log.i(LOGTAG, "PromptDelegate: __rovin_immersive_active__ received. VR is confirmed stable.");
        activity.handleRovinImmersiveActive();
        
        result.complete(textPrompt.confirm("ok"));
        return true;
    }

    private boolean isTrustedRovinSaveOrigin(@Nullable String uri) {
        if (StringUtils.isEmpty(uri)) {
            return false;
        }

        // Allow bundled local content
        if (uri.startsWith("file:///android_asset/")) {
            return true;
        }

        final String host = UrlUtils.getHost(uri);
        if (StringUtils.isEmpty(host)) {
            // Only block if it's not a known local scheme we already handled above
            if (!UrlUtils.isFileUri(uri)) {
                Log.w(LOGTAG, "Rovin save: Blocked empty host for uri=" + uri);
            }
            return false;
        }

        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
            return true;
        }

        if (!StringUtils.isEmpty(BuildConfig.ROVIN_FIXED_TARGET_URL)) {
            final String fixedHost = UrlUtils.getHost(BuildConfig.ROVIN_FIXED_TARGET_URL);
            if (!StringUtils.isEmpty(fixedHost) && fixedHost.equalsIgnoreCase(host)) {
                return true;
            }
        }

        return false;
    }

    @NonNull
    private File getRovinSaveFile() {
        final String subdir = StringUtils.isEmpty(BuildConfig.ROVIN_SAVE_SUBDIR) ? "rovin-save" : BuildConfig.ROVIN_SAVE_SUBDIR;
        final String filename = StringUtils.isEmpty(BuildConfig.ROVIN_SAVE_FILENAME) ? "rovin-save.json" : BuildConfig.ROVIN_SAVE_FILENAME;
        final File baseDir = new File(mContext.getFilesDir(), subdir);
        return new File(baseDir, filename);
    }

    @NonNull
    private String readRovinSaveJson() throws IOException {
        final File saveFile = getRovinSaveFile();
        if (!saveFile.exists()) {
            return "";
        }

        final long length = saveFile.length();
        if (length > ROVIN_SAVE_MAX_BYTES) {
            return "error|too_large";
        }

        try (FileInputStream input = new FileInputStream(saveFile)) {
            final byte[] bytes = new byte[(int) length];
            int offset = 0;
            while (offset < bytes.length) {
                final int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            return new String(bytes, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private void validateJsonObject(@NonNull String json) throws JSONException {
        new JSONObject(json);
    }

    private void writeRovinSaveAtomic(@NonNull byte[] jsonBytes) throws IOException {
        final File saveFile = getRovinSaveFile();
        final File dir = saveFile.getParentFile();
        if (dir == null) {
            throw new IOException("Missing save directory.");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create save directory.");
        }

        final File tempFile = new File(dir, saveFile.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tempFile, false)) {
            output.write(jsonBytes);
            output.flush();
            output.getFD().sync();
        }

        if (saveFile.exists() && !saveFile.delete()) {
            throw new IOException("Failed to replace existing save file.");
        }

        if (!tempFile.renameTo(saveFile)) {
            throw new IOException("Atomic rename failed.");
        }
    }

    private void clearRovinSave() {
        try {
            final File saveFile = getRovinSaveFile();
            if (saveFile.exists() && !saveFile.delete()) {
                Log.w(LOGTAG, "Failed to delete save file: " + saveFile.getAbsolutePath());
            }
        } catch (Exception error) {
            Log.w(LOGTAG, "Failed to clear save file.", error);
        }
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onAuthPrompt(@NonNull WSession session, @NonNull AuthPrompt authPrompt) {
        final WResult<PromptResponse> result = WResult.create();

        mPrompt = new AuthPromptWidget(mContext);
        mPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
        mPrompt.getPlacement().parentAnchorY = 0.0f;
        mPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
        mPrompt.setTitle(authPrompt.title());
        mPrompt.setMessage(authPrompt.message());
        ((AuthPromptWidget)mPrompt).setAuthOptions(authPrompt.authOptions());
        mPrompt.setPromptDelegate(new AuthPromptWidget.AuthPromptDelegate() {
            @Override
            public void dismiss() {
                result.complete(authPrompt.dismiss());
            }

            @Override
            public void confirm(String password) {
                result.complete(authPrompt.confirm(password));
            }

            @Override
            public void confirm(String username, String password) {
                result.complete(authPrompt.confirm(username, password));
            }
        });
        mPrompt.show(UIWidget.REQUEST_FOCUS, true);

        return result;
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onChoicePrompt(@NonNull WSession session, @NonNull ChoicePrompt choicePrompt) {
        final WResult<PromptResponse> result = WResult.create();

        mPrompt = new ChoicePromptWidget(mContext);
        mPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
        mPrompt.getPlacement().parentAnchorY = 0.0f;
        mPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
        mPrompt.setTitle(choicePrompt.title());
        mPrompt.setMessage(choicePrompt.message());
        ((ChoicePromptWidget)mPrompt).setChoices(choicePrompt.choices());
        ((ChoicePromptWidget)mPrompt).setMenuType(choicePrompt.type());
        mPrompt.setPromptDelegate(new ChoicePromptWidget.ChoicePromptDelegate() {
            @Override
            public void confirm(String[] choices) {
                result.complete(choicePrompt.confirm(choices));
            }

            @Override
            public void dismiss() {
                result.complete(choicePrompt.dismiss());
            }
        });
        mPrompt.show(UIWidget.REQUEST_FOCUS, true);

        return result;
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onColorPrompt(@NonNull WSession session, @NonNull ColorPrompt prompt) {
        final WResult<PromptResponse> result = WResult.create();

        mPrompt = new ColorPromptWidget(mContext);
        mPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
        mPrompt.getPlacement().parentAnchorY = 0.0f;
        mPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
        mPrompt.setTitle(prompt.title());
        mPrompt.setPromptDelegate(new ColorPromptWidget.ColorPromptDelegate() {
            @Override
            public void confirm(@NonNull final String color) {
                result.complete(prompt.confirm(color));
            }

            @Override
            public void dismiss() {
                result.complete(prompt.dismiss());
            }
        });
        mPrompt.show(UIWidget.REQUEST_FOCUS, true);

        return result;
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onDateTimePrompt(@NonNull WSession session, @NonNull DateTimePrompt prompt) {
        final WResult<PromptResponse> result = WResult.create();

        mPrompt = new DateTimePromptWidget(mContext, prompt);
        mPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
        mPrompt.getPlacement().parentAnchorY = 0.0f;
        mPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
        mPrompt.setTitle(prompt.title());
        mPrompt.setPromptDelegate(new DateTimePromptWidget.DateTimePromptDelegate() {
            @Override
            public void confirm(@NonNull final String dateTime) {
                result.complete(prompt.confirm(dateTime));
            }
            @Override
            public void dismiss() {
                result.complete(prompt.dismiss());
            }
        });
        mPrompt.show(UIWidget.REQUEST_FOCUS, true);
        return result;
    }

    private Observer<List<SitePermission>> mPopUpSiteObserver = sites -> {
        mAllowedPopUpSites = sites;
    };

    private Observer<List<SitePermission>> mSavedLoginExceptionsObserver = sites -> {
        mSavedLoginBlockedSites = sites;
    };

    @Nullable
    @Override
    public WResult<PromptResponse> onPopupPrompt(@NonNull WSession aSession, @NonNull PopupPrompt popupPrompt) {
        final WResult<PromptResponse> result = WResult.create();

        if (!SettingsStore.getInstance(mContext).isPopUpsBlockingEnabled()) {
            result.complete(popupPrompt.confirm(WAllowOrDeny.ALLOW));

        } else {
            Session session = mAttachedWindow.getSession();
            if (session != null) {
                final String uri = UrlUtils.getHost(session.getCurrentUri());
                SitePermission site = mAllowedPopUpSites.stream().filter((item) -> UrlUtils.getHost(item.url).equals(uri)).findFirst().orElse(null);
                if (site != null) {
                    result.complete(popupPrompt.confirm(WAllowOrDeny.ALLOW));
                    session.setPopUpState(SessionState.POPUP_ALLOWED);
                } else {
                    result.complete(popupPrompt.confirm(WAllowOrDeny.DENY));
                    session.setPopUpState(SessionState.POPUP_BLOCKED);
                }

            } else {
                result.complete(popupPrompt.confirm(WAllowOrDeny.DENY));
            }
        }

        return result;
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onSharePrompt(@NonNull WSession session, @NonNull SharePrompt prompt) {
        // TODO implement share request
        final WResult<PromptResponse> result = WResult.create();
        result.cancel();
        return result;
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onLoginSave(@NonNull WSession session, final @NonNull AutocompleteRequest<WAutocomplete.LoginSaveOption> autocompleteRequest) {
        final WResult<PromptResponse> result = WResult.create();

        // We always get at least one item, at the moment only one item is support.
        if (autocompleteRequest.options().length > 0 && SettingsStore.getInstance(mContext).isLoginAutocompleteEnabled()) {
            WAutocomplete.LoginSaveOption saveOption = autocompleteRequest.options()[0];
            boolean originHasException = mSavedLoginBlockedSites.stream().anyMatch(site -> site.url.equals(saveOption.value.origin));
            if (originHasException || !SettingsStore.getInstance(mContext).isLoginAutocompleteEnabled()) {
                result.complete(autocompleteRequest.dismiss());

            } else {
                if (mSaveLoginPrompt == null) {
                    mSaveLoginPrompt = new SaveLoginPromptWidget(mContext);
                }
                mSaveLoginPrompt.setPromptDelegate(new SaveLoginPromptWidget.Delegate() {
                    @Override
                    public void dismiss(@NonNull Login login) {
                        result.complete(autocompleteRequest.dismiss());
                        SessionStore.get().addPermissionException(login.getOrigin(), SitePermission.SITE_PERMISSION_AUTOFILL);
                    }

                    @Override
                    public void confirm(@NonNull Login login) {
                        result.complete(autocompleteRequest.confirm(new WAutocomplete.LoginSaveOption(LoginDelegateWrapper.toLoginEntry(login))));
                    }
                });
                mSaveLoginPrompt.setDelegate(() -> result.complete(autocompleteRequest.dismiss()));
                mSaveLoginPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
                mSaveLoginPrompt.getPlacement().parentAnchorY = 0.0f;
                mSaveLoginPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
                mSaveLoginPrompt.setLogin(LoginDelegateWrapper.toLogin(saveOption.value));
                mSaveLoginPrompt.show(UIWidget.REQUEST_FOCUS, true);
            }

        } else {
            result.complete(autocompleteRequest.dismiss());
        }

        return result;
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onLoginSelect(@NonNull WSession session, final @NonNull AutocompleteRequest<WAutocomplete.LoginSelectOption> autocompleteRequest) {
        final WResult<PromptResponse> result = WResult.create();

        if (autocompleteRequest.options().length > 0 && SettingsStore.getInstance(mContext).isAutoFillEnabled()) {
            List<Login> logins = Arrays.stream(autocompleteRequest.options()).map(item -> LoginDelegateWrapper.toLogin(item.value)).collect(Collectors.toList());
            if (mSelectLoginPrompt == null) {
                mSelectLoginPrompt = new SelectLoginPromptWidget(mContext);
            }
            mSelectLoginPrompt.setPromptDelegate(new SelectLoginPromptWidget.Delegate() {
                @Override
                public void onLoginSelected(@NonNull Login login) {
                    result.complete(autocompleteRequest.confirm(new WAutocomplete.LoginSelectOption(LoginDelegateWrapper.toLoginEntry(login))));
                }

                @Override
                public void onSettingsClicked() {
                    result.complete(autocompleteRequest.dismiss());
                    mWidgetManager.getTray().toggleSettingsDialog(SettingsView.SettingViewType.LOGINS_AND_PASSWORDS);
                }
            });
            mSelectLoginPrompt.setItems(logins);
            mSelectLoginPrompt.setDelegate(() -> result.complete(autocompleteRequest.dismiss()));
            mSelectLoginPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
            mSelectLoginPrompt.getPlacement().parentAnchorY = 0.0f;
            mSelectLoginPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
            mSelectLoginPrompt.show(UIWidget.KEEP_FOCUS, true);

        } else {
            result.complete(autocompleteRequest.dismiss());
        }

        return result;
    }

    @Nullable
    @Override
    public WResult<WSlowScriptResponse> onSlowScript(@NonNull WSession aSession, @NonNull String aScriptFileName) {
        final WResult<WSlowScriptResponse> result = WResult.create();
        if (mSlowScriptPrompt == null) {
            mSlowScriptPrompt = new ConfirmPromptWidget(mContext);
            mSlowScriptPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
            mSlowScriptPrompt.getPlacement().parentAnchorY = 0.0f;
            mSlowScriptPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
            mSlowScriptPrompt.setTitle(mContext.getResources().getString(R.string.slow_script_dialog_title));
            mSlowScriptPrompt.setMessage(mContext.getResources().getString(R.string.slow_script_dialog_description, aScriptFileName));
            mSlowScriptPrompt.setButtons(new String[]{
                    mContext.getResources().getString(R.string.slow_script_dialog_action_wait),
                    mContext.getResources().getString(R.string.slow_script_dialog_action_stop)
            });
            mSlowScriptPrompt.setPromptDelegate(new ConfirmPromptWidget.ConfirmPromptDelegate() {
                @Override
                public void confirm(int index) {
                    result.complete(index == 0 ? WSlowScriptResponse.CONTINUE : WSlowScriptResponse.STOP);
                }
                @Override
                public void dismiss() {
                    result.complete(WSlowScriptResponse.CONTINUE);
                }
            });
            mSlowScriptPrompt.show(UIWidget.REQUEST_FOCUS, true);
        }

        return result.then(value -> {
            if (mSlowScriptPrompt != null && !mSlowScriptPrompt.isReleased()) {
                mSlowScriptPrompt.releaseWidget();
            }
            mSlowScriptPrompt = null;
            return WResult.fromValue(value);
        });
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onBeforeUnloadPrompt(@NonNull WSession session, @NonNull BeforeUnloadPrompt prompt) {
        final WResult<PromptResponse> result = WResult.create();

        mPrompt = new ConfirmPromptWidget(mContext);
        mPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
        mPrompt.getPlacement().parentAnchorY = 0.0f;
        mPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
        String message = mContext.getString(R.string.before_unload_prompt_message);
        if (!StringUtils.isEmpty(prompt.title())) {
            message = prompt.title();
        }
        mPrompt.setTitle(mContext.getString(R.string.before_unload_prompt_title));
        mPrompt.setMessage(message);
        ((ConfirmPromptWidget)mPrompt).setButtons(new String[] {
                mContext.getResources().getText(R.string.before_unload_prompt_leave).toString(),
                mContext.getResources().getText(R.string.before_unload_prompt_stay).toString()
        });
        mPrompt.setPromptDelegate(new ConfirmPromptWidget.ConfirmPromptDelegate() {
            @Override
            public void confirm(int index) {
                result.complete(prompt.confirm(index == 0 ? WAllowOrDeny.ALLOW : WAllowOrDeny.DENY));
            }

            @Override
            public void dismiss() {
                result.complete(prompt.dismiss());
            }
        });
        mPrompt.show(UIWidget.REQUEST_FOCUS, true);

        return result;
    }

    @Nullable
    @Override
    public WResult<PromptResponse> onRepostConfirmPrompt(@NonNull WSession session, @NonNull RepostConfirmPrompt prompt) {
        final WResult<PromptResponse> result = WResult.create();

        mPrompt = new ConfirmPromptWidget(mContext);
        mPrompt.getPlacement().parentHandle = mAttachedWindow.getHandle();
        mPrompt.getPlacement().parentAnchorY = 0.0f;
        mPrompt.getPlacement().translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.js_prompt_y_distance);
        mPrompt.setTitle(mContext.getString(R.string.repost_confirm_title));
        mPrompt.setMessage(mContext.getString(R.string.repost_confirm_message));
        ((ConfirmPromptWidget)mPrompt).setButtons(new String[] {
                mContext.getResources().getText(R.string.repost_confirm_continue).toString(),
                mContext.getResources().getText(R.string.cancel_button).toString()
        });
        mPrompt.setPromptDelegate(new ConfirmPromptWidget.ConfirmPromptDelegate() {
            @Override
            public void confirm(int index) {
                result.complete(prompt.confirm(index == 0 ? WAllowOrDeny.ALLOW : WAllowOrDeny.DENY));
            }

            @Override
            public void dismiss() {
                result.complete(prompt.dismiss());
            }
        });
        mPrompt.show(UIWidget.REQUEST_FOCUS, true);

        return result;
    }

    public void hideAllPrompts() {
        if (mPrompt != null) {
            mPrompt.hide(UIWidget.REMOVE_WIDGET);
        }
        if (mSlowScriptPrompt != null) {
            mSlowScriptPrompt.hide(UIWidget.REMOVE_WIDGET);
        }
        if (mSaveLoginPrompt != null) {
            mSaveLoginPrompt.hide(UIWidget.REMOVE_WIDGET);
        }
        if (mSelectLoginPrompt != null) {
            mSelectLoginPrompt.hide(UIWidget.REMOVE_WIDGET);
        }
    }

    // WindowWidget.WindowListener

    @Override
    public void onSessionChanged(@NonNull Session aOldSession, @NonNull Session aSession) {
        cleanSession(aOldSession);
        setUpSession(aSession);
    }


    private boolean handleRovinLogPrompt(@NonNull TextPrompt textPrompt, @NonNull WResult<PromptResponse> result) {
        String msg = textPrompt.message();
        if (msg == null || !msg.startsWith(ROVIN_LOG_PROMPT_PREFIX)) {
            return false;
        }

        Log.i("PromptDelegate", "RovinLog: " + msg);
        if (mContext instanceof VRBrowserActivity) {
            ((VRBrowserActivity) mContext).rovinLog("Web: " + msg.substring(ROVIN_LOG_PROMPT_PREFIX.length()));
        }
        result.complete(textPrompt.confirm("ok"));
        return true;
    }

    private boolean handleRovinSaveLogsPrompt(@NonNull TextPrompt textPrompt, @NonNull WResult<PromptResponse> result) {
        if (!ROVIN_SAVE_LOGS_PROMPT.equals(textPrompt.message())) {
            return false;
        }

        Log.i("PromptDelegate", "RovinSaveLogs: detected");
        if (mContext instanceof VRBrowserActivity) {
            ((VRBrowserActivity) mContext).rovinLog("System: Save logs requested from Web.");
        }
        result.complete(textPrompt.confirm("ok"));
        return true;
    }
}