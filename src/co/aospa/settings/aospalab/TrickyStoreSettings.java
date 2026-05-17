/*
 * SPDX-FileCopyrightText: 2026 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.settings.aospalab;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TrickyStoreSettings extends SettingsPreferenceFragment {

    private enum RevocationStatus {
        UNKNOWN, CHECKING, VALID, REVOKED, SUSPENDED, SOFT_BANNED
    }

    private static final String KEY_MASTER_SWITCH = "persist.sys.trickystore.enabled";
    private static final String KEY_FETCH_KEYBOX = "ts_fetch_keybox";
    private static final String KEY_IMPORT_KEYBOX = "ts_import_keybox";
    private static final String KEY_DELETE_KEYBOX = "ts_delete_keybox";
    private static final String KEY_REVOCATION_STATUS = "ts_revocation_status";
    private static final String KEY_MANAGE_TARGETS = "ts_manage_targets";
    private static final String KEY_IMPORT_TARGETS = "ts_import_targets";
    private static final String KEY_VERIFICATION_MODE = "ts_verification_mode";
    private static final String KEY_SECURITY_PATCH = "ts_security_patch";

    private static final String KEYBOX_KEY = "spoof_trickystore_keybox";
    private static final String TARGET_KEY = "spoof_trickystore_target";
    private static final String PATCH_KEY = "spoof_trickystore_patch";
    private static final String LAST_FETCHED_KEY = "spoof_trickystore_last_fetched";
    private static final String TRICKYSTORE_ENABLED_KEY = "spoof_trickystore_enabled";

    private static final String VENDING_PACKAGE = "com.android.vending";
    private static final String DROIDGUARD_PACKAGE = "com.google.android.gms.unstable";
    private static final String GMS_PACKAGE = "com.google.android.gms";

    private static final String REVOCATION_URL = "https://android.googleapis.com/attestation/status";
    private static final String OFFICIAL_KEYBOX_URL =
            "https://git.evolution-x.org/EvoX/keybox/raw/branch/main/keybox.xml";
    private static final String SOFTBANNED_API_URL =
            "https://git.evolution-x.org/api/v1/repos/EvoX/keybox/contents/softbanned";
    private static final String SOFTBANNED_RAW_BASE_URL =
            "https://git.evolution-x.org/EvoX/keybox/raw/branch/main/softbanned/";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<Intent> mKeyboxPickerLauncher;
    private ActivityResultLauncher<Intent> mTargetPickerLauncher;

    private Preference mFetchKeybox;
    private Preference mImportKeybox;
    private Preference mDeleteKeybox;
    private Preference mRevocationStatus;
    private Preference mManageTargets;
    private Preference mImportTargets;
    private Preference mVerificationMode;
    private Preference mSecurityPatch;

    private volatile boolean mIsCheckInProgress = false;
    private volatile boolean mIsKeyboxPickerOpen = false;
    private RevocationStatus mCurrentRevocationStatus = RevocationStatus.UNKNOWN;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.trickystore_settings);

        mFetchKeybox = findPreference(KEY_FETCH_KEYBOX);
        mImportKeybox = findPreference(KEY_IMPORT_KEYBOX);
        mDeleteKeybox = findPreference(KEY_DELETE_KEYBOX);
        mRevocationStatus = findPreference(KEY_REVOCATION_STATUS);
        mManageTargets = findPreference(KEY_MANAGE_TARGETS);
        mImportTargets = findPreference(KEY_IMPORT_TARGETS);
        mVerificationMode = findPreference(KEY_VERIFICATION_MODE);
        mSecurityPatch = findPreference(KEY_SECURITY_PATCH);

        com.android.settingslib.widget.MainSwitchPreference masterSwitch = findPreference(KEY_MASTER_SWITCH);
        if (masterSwitch != null) {
            masterSwitch.setChecked(SystemProperties.getBoolean(KEY_MASTER_SWITCH, false));
            masterSwitch.addOnSwitchChangeListener((switchView, isChecked) -> {
                SystemProperties.set(KEY_MASTER_SWITCH, isChecked ? "true" : "false");
                Settings.System.putInt(
                        requireContext().getContentResolver(),
                        TRICKYSTORE_ENABLED_KEY,
                        isChecked ? 1 : 0);
            });
        }

        mKeyboxPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    mIsKeyboxPickerOpen = false;
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        handleKeyboxImport(result.getData().getData());
                    }
                });

        mTargetPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        handleTargetImport(result.getData().getData());
                    }
                });

        if (mImportKeybox != null) {
            mImportKeybox.setOnPreferenceClickListener(pref -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*");
                mIsKeyboxPickerOpen = true;
                mKeyboxPickerLauncher.launch(intent);
                return true;
            });
        }

        if (mDeleteKeybox != null) {
            mDeleteKeybox.setOnPreferenceClickListener(pref -> {
                showDeleteKeyboxDialog();
                return true;
            });
        }

        if (mSecurityPatch != null) {
            mSecurityPatch.setOnPreferenceClickListener(pref -> {
                showPatchDateDialog();
                return true;
            });
        }

        if (mImportTargets != null) {
            mImportTargets.setOnPreferenceClickListener(pref -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/*");
                mTargetPickerLauncher.launch(intent);
                return true;
            });
        }

        if (mFetchKeybox != null) {
            mFetchKeybox.setOnPreferenceClickListener(pref -> {
                fetchOfficialKeybox(false);
                return true;
            });
        }

        if (mRevocationStatus != null) {
            mRevocationStatus.setEnabled(false);
        }

        refreshStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        syncEnabledSettingFromProperty();
        refreshStatus();
        if (isTrickyStoreEnabled()) {
            checkKeyboxRevocation();
            autoFetchIfNoKeybox();
        }
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.VIEW_UNKNOWN;
    }

    private void refreshStatus() {
        boolean keyboxExists = !TextUtils.isEmpty(getSecureString(KEYBOX_KEY));
        if (mImportKeybox != null) {
            mImportKeybox.setSummary(keyboxExists
                    ? getString(R.string.ts_keybox_installed)
                    : getString(R.string.ts_no_keybox));
        }
        if (mDeleteKeybox != null) {
            mDeleteKeybox.setEnabled(keyboxExists);
        }

        int targetCount = countTargets();
        if (mManageTargets != null) {
            if (targetCount > 0) {
                mManageTargets.setSummary(getResources().getQuantityString(
                        R.plurals.ts_target_apps_count, targetCount, targetCount));
            } else {
                mManageTargets.setSummary(getString(R.string.ts_no_targets));
            }
        }

        if (mVerificationMode != null) {
            mVerificationMode.setSummary(buildVerificationSummary());
        }

        if (mSecurityPatch != null) {
            String patchDate = getSecureString(PATCH_KEY);
            mSecurityPatch.setSummary(!TextUtils.isEmpty(patchDate)
                    ? patchDate
                    : getString(R.string.ts_no_patch));
        }

        RevocationStatus effective = keyboxExists ? mCurrentRevocationStatus : RevocationStatus.UNKNOWN;
        applyRevocationUi(effective);
        updateFetchButtonState(keyboxExists);
    }

    private void handleKeyboxImport(android.net.Uri uri) {
        if (uri == null || !isAdded()) return;
        try {
            byte[] bytes;
            try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                if (in == null) {
                    throw new IllegalStateException("No input stream");
                }
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                bytes = out.toByteArray();
            }
            String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
            Settings.Secure.putString(
                    requireContext().getContentResolver(),
                    KEYBOX_KEY,
                    encoded);
            saveLastFetchedTimestamp();
            killGms();
            toast(getString(R.string.ts_keybox_imported));
            mCurrentRevocationStatus = RevocationStatus.UNKNOWN;
            refreshStatus();
            checkKeyboxRevocation();
        } catch (Exception e) {
            toast(getString(R.string.ts_failed, e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private void handleTargetImport(android.net.Uri uri) {
        if (uri == null || !isAdded()) return;
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    requireContext().getContentResolver().openInputStream(uri)))) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (!first) {
                        sb.append("\n");
                    }
                    sb.append(line);
                    first = false;
                }
            }
            String text = sb.toString();
            Settings.Secure.putString(
                    requireContext().getContentResolver(),
                    TARGET_KEY,
                    text);
            toast(getString(R.string.ts_target_list_imported));
            refreshStatus();
        } catch (Exception e) {
            toast(getString(R.string.ts_failed, e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private int countTargets() {
        String content = getSecureString(TARGET_KEY);
        if (TextUtils.isEmpty(content)) return 0;
        int count = 0;
        for (String line : content.split("\n")) {
            if (!line.trim().isEmpty()) count++;
        }
        return count;
    }

    private void applyRevocationUi(RevocationStatus status) {
        if (mRevocationStatus == null) return;
        int iconRes;
        String summary;
        switch (status) {
            case VALID:
                iconRes = android.R.drawable.presence_online;
                summary = getString(R.string.ts_revocation_valid);
                break;
            case REVOKED:
                iconRes = android.R.drawable.presence_busy;
                summary = getString(R.string.ts_revocation_revoked, "");
                break;
            case SUSPENDED:
                iconRes = android.R.drawable.presence_away;
                summary = getString(R.string.ts_revocation_suspended, "");
                break;
            case SOFT_BANNED:
                iconRes = android.R.drawable.presence_busy;
                summary = getString(R.string.ts_revocation_soft_banned);
                break;
            case CHECKING:
                iconRes = android.R.drawable.presence_invisible;
                summary = getString(R.string.ts_revocation_checking);
                break;
            case UNKNOWN:
            default:
                iconRes = android.R.drawable.presence_invisible;
                summary = getString(R.string.ts_revocation_no_keybox);
                break;
        }
        mRevocationStatus.setIcon(iconRes);
        mRevocationStatus.setSummary(summary);
    }

    private void updateFetchButtonState(boolean keyboxExists) {
        if (mFetchKeybox == null) return;

        boolean isValid = mCurrentRevocationStatus == RevocationStatus.VALID
                || mCurrentRevocationStatus == RevocationStatus.SOFT_BANNED;

        if (isValid) {
            mFetchKeybox.setEnabled(false);
            mFetchKeybox.setSummary(getString(R.string.ts_fetch_keybox_blocked));
        } else {
            mFetchKeybox.setEnabled(true);
            String timestamp = getLastFetchedFormatted();
            if (timestamp != null && keyboxExists) {
                mFetchKeybox.setSummary(getString(R.string.ts_fetch_keybox_last_fetched, timestamp));
            } else {
                mFetchKeybox.setSummary(getString(R.string.ts_fetch_keybox_summary));
            }
        }
    }

    private void checkKeyboxRevocation() {
        if (mIsCheckInProgress) return;
        String raw = getSecureString(KEYBOX_KEY);
        if (TextUtils.isEmpty(raw)) {
            mCurrentRevocationStatus = RevocationStatus.UNKNOWN;
            applyRevocationUi(RevocationStatus.UNKNOWN);
            updateFetchButtonState(false);
            return;
        }

        mIsCheckInProgress = true;
        mCurrentRevocationStatus = RevocationStatus.CHECKING;
        applyRevocationUi(RevocationStatus.CHECKING);
        updateFetchButtonState(true);

        mExecutor.execute(() -> {
            try {
                RevocationStatus status = performRevocationCheck(raw);
                mHandler.post(() -> {
                    mIsCheckInProgress = false;
                    if (!isAdded()) return;
                    mCurrentRevocationStatus = status;
                    applyRevocationUi(status);
                    updateFetchButtonState(true);
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mIsCheckInProgress = false;
                    if (!isAdded()) return;
                    if (mRevocationStatus != null) {
                        mRevocationStatus.setSummary(
                                getString(R.string.ts_revocation_error, e.getMessage()));
                    }
                });
            }
        });
    }

    private RevocationStatus performRevocationCheck(String raw) throws Exception {
        String xml = decodeKeyboxForRevocation(raw);
        if (xml == null) return RevocationStatus.UNKNOWN;

        List<String> serials = extractCertSerials(xml);
        if (serials.isEmpty()) return RevocationStatus.UNKNOWN;

        JSONObject json = fetchRevocationJson();
        if (json == null) return RevocationStatus.UNKNOWN;

        JSONObject entries = json.optJSONObject("entries");
        if (entries != null) {
            for (String serial : serials) {
                JSONObject entry = entries.optJSONObject(serial);
                if (entry == null) continue;
                String status = entry.optString("status", "").toUpperCase(Locale.US);
                if ("REVOKED".equals(status)) {
                    mHandler.post(() -> fetchOfficialKeybox(true));
                    return RevocationStatus.REVOKED;
                }
                if ("SUSPENDED".equals(status)) {
                    return RevocationStatus.SUSPENDED;
                }
            }
        }

        if (isKeyboxSoftBanned(serials)) {
            mHandler.post(() -> fetchOfficialKeybox(true));
            return RevocationStatus.SOFT_BANNED;
        }

        return RevocationStatus.VALID;
    }

    private String decodeKeyboxForRevocation(String payload) {
        String trimmed = payload.trim();
        if (trimmed.startsWith("<")) return trimmed;
        try {
            byte[] decoded = Base64.decode(trimmed, Base64.DEFAULT);
            String xml = new String(decoded, StandardCharsets.UTF_8).trim();
            return xml.startsWith("<") ? xml : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> extractCertSerials(String xml) throws Exception {
        List<String> serials = new ArrayList<>();
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Pattern pattern = Pattern.compile(
                "-----BEGIN CERTIFICATE-----([\\s\\S]+?)-----END CERTIFICATE-----");
        Matcher matcher = pattern.matcher(xml);
        while (matcher.find()) {
            try {
                byte[] der = Base64.decode(
                        matcher.group(1).replaceAll("\\s", ""), Base64.DEFAULT);
                X509Certificate cert = (X509Certificate) factory
                        .generateCertificate(new ByteArrayInputStream(der));
                serials.add(cert.getSerialNumber().toString(16).toUpperCase(Locale.US));
            } catch (Exception ignored) {
            }
        }
        return serials;
    }

    private JSONObject fetchRevocationJson() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(REVOCATION_URL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return new JSONObject(sb.toString());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isKeyboxSoftBanned(List<String> serials) {
        List<String> files = fetchSoftBannedFileList();
        if (files == null) return false;
        for (String filename : files) {
            String xml = fetchRawKeybox(SOFTBANNED_RAW_BASE_URL + filename);
            if (xml == null) continue;
            try {
                List<String> bannedSerials = extractCertSerials(xml);
                for (String serial : serials) {
                    if (bannedSerials.contains(serial)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private List<String> fetchSoftBannedFileList() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(SOFTBANNED_API_URL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                JSONArray response = new JSONArray(sb.toString());
                List<String> files = new ArrayList<>();
                for (int i = 0; i < response.length(); i++) {
                    JSONObject obj = response.optJSONObject(i);
                    if (obj == null) continue;
                    String name = obj.optString("name", "");
                    if (name.endsWith(".xml")) {
                        files.add(name);
                    }
                }
                return files;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String fetchRawKeybox(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void autoFetchIfNoKeybox() {
        if (!isTrickyStoreEnabled()) return;
        if (mIsKeyboxPickerOpen) return;
        String existing = getSecureString(KEYBOX_KEY);
        if (!TextUtils.isEmpty(existing)) return;
        fetchOfficialKeybox(true);
    }

    private void fetchOfficialKeybox(boolean silent) {
        if (mFetchKeybox != null && !silent) {
            mFetchKeybox.setEnabled(false);
            mFetchKeybox.setSummary(getString(R.string.ts_fetch_keybox_fetching));
        }

        mExecutor.execute(() -> {
            String xml = null;
            String error = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(OFFICIAL_KEYBOX_URL).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    xml = sb.toString();
                } else {
                    error = "HTTP " + conn.getResponseCode();
                }
            } catch (Exception e) {
                error = e.getMessage();
            }

            final String fetchedXml = xml;
            final String errorMsg = error;
            mHandler.post(() -> {
                if (!silent) {
                    updateFetchButtonState(true);
                }
                if (!isAdded()) return;
                if (fetchedXml == null) {
                    if (!silent) {
                        toast(getString(R.string.ts_fetch_keybox_failed, errorMsg == null ? "" : errorMsg));
                    }
                    return;
                }
                if (!isTrickyStoreEnabled()) return;
                try {
                    String existing = getSecureString(KEYBOX_KEY);
                    String existingXml = existing == null ? null : decodeKeyboxForRevocation(existing);
                    if (existingXml != null && existingXml.trim().equals(fetchedXml.trim())) {
                        if (!silent) {
                            toast(getString(R.string.ts_fetch_keybox_same_file));
                        }
                        return;
                    }

                    List<String> fetchedSerials = extractCertSerials(fetchedXml);
                    JSONObject revocationJson = fetchRevocationJson();
                    JSONObject entries = revocationJson == null ? null : revocationJson.optJSONObject("entries");
                    if (entries != null && !fetchedSerials.isEmpty()) {
                        boolean fetchedRevoked = false;
                        for (String serial : fetchedSerials) {
                            JSONObject entry = entries.optJSONObject(serial);
                            if (entry == null) continue;
                            String status = entry.optString("status", "").toUpperCase(Locale.US);
                            if ("REVOKED".equals(status)) {
                                fetchedRevoked = true;
                                break;
                            }
                        }
                        if (fetchedRevoked) return;
                    }

                    String encoded = Base64.encodeToString(
                            fetchedXml.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                    Settings.Secure.putString(
                            requireContext().getContentResolver(),
                            KEYBOX_KEY,
                            encoded);
                    saveLastFetchedTimestamp();
                    killGms();
                    if (!silent) {
                        toast(getString(R.string.ts_fetch_keybox_success));
                    }
                    mCurrentRevocationStatus = RevocationStatus.UNKNOWN;
                    refreshStatus();
                    checkKeyboxRevocation();
                } catch (Exception e) {
                    if (!silent) {
                        toast(getString(R.string.ts_fetch_keybox_failed, e.getMessage() == null ? "" : e.getMessage()));
                    }
                }
            });
        });
    }

    private void saveLastFetchedTimestamp() {
        Settings.Secure.putLong(
                requireContext().getContentResolver(),
                LAST_FETCHED_KEY,
                System.currentTimeMillis());
    }

    private String getLastFetchedFormatted() {
        long millis = Settings.Secure.getLong(
                requireContext().getContentResolver(), LAST_FETCHED_KEY, 0L);
        if (millis == 0L) return null;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(millis));
    }

    private String buildVerificationSummary() {
        String content = getSecureString(TARGET_KEY);
        if (TextUtils.isEmpty(content)) {
            return getString(R.string.ts_verification_mode_auto);
        }

        int auto = 0;
        int cert = 0;
        int leaf = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.endsWith("!")) {
                cert++;
            } else if (trimmed.endsWith("?")) {
                leaf++;
            } else {
                auto++;
            }
        }

        if (auto == 0 && cert == 0 && leaf == 0) {
            return getString(R.string.ts_verification_mode_auto);
        }

        List<String> parts = new ArrayList<>();
        if (auto > 0) {
            parts.add(getString(R.string.ts_verification_auto_count, auto));
        }
        if (cert > 0) {
            parts.add(getString(R.string.ts_verification_cert_count, cert));
        }
        if (leaf > 0) {
            parts.add(getString(R.string.ts_verification_leaf_count, leaf));
        }
        return TextUtils.join(" | ", parts);
    }

    private void showDeleteKeyboxDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.ts_delete_keybox_title)
                .setMessage(R.string.ts_delete_keybox_message)
                .setPositiveButton(R.string.ts_action_delete, (dialog, which) -> {
                    try {
                        Settings.Secure.putString(
                                requireContext().getContentResolver(), KEYBOX_KEY, "");
                        Settings.Secure.putLong(
                                requireContext().getContentResolver(), LAST_FETCHED_KEY, 0L);
                        toast(getString(R.string.ts_keybox_deleted));
                        mCurrentRevocationStatus = RevocationStatus.UNKNOWN;
                        refreshStatus();
                    } catch (Exception e) {
                        toast(getString(R.string.ts_failed, e.getMessage() == null ? "" : e.getMessage()));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPatchDateDialog() {
        String current = getSecureString(PATCH_KEY);
        EditText input = new EditText(requireContext());
        input.setText(current == null ? "" : current);
        input.setHint(R.string.ts_patch_date_hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setPadding(48, 24, 48, 24);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.ts_security_patch)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.ts_action_delete, (d, w) -> {
                    Settings.Secure.putString(
                            requireContext().getContentResolver(), PATCH_KEY, "");
                    refreshStatus();
                })
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty() && !value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        toast(getString(R.string.ts_invalid_patch_date));
                        return;
                    }
                    Settings.Secure.putString(
                            requireContext().getContentResolver(), PATCH_KEY, value);
                    refreshStatus();
                    dialog.dismiss();
                }));

        dialog.show();
        if (TextUtils.isEmpty(current)) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        }
    }

    private void killGms() {
        try {
            ActivityManager am = (ActivityManager)
                    requireContext().getSystemService(Context.ACTIVITY_SERVICE);
            am.forceStopPackage(VENDING_PACKAGE);
            am.forceStopPackage(DROIDGUARD_PACKAGE);
            am.forceStopPackage(GMS_PACKAGE);
        } catch (Exception ignored) {
        }
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }



    private boolean isTrickyStoreEnabled() {
        boolean propertyEnabled = SystemProperties.getBoolean(KEY_MASTER_SWITCH, false);
        if (propertyEnabled) return true;
        return Settings.System.getInt(
                requireContext().getContentResolver(), TRICKYSTORE_ENABLED_KEY, 0) != 0;
    }

    private void syncEnabledSettingFromProperty() {
        boolean propertyEnabled = SystemProperties.getBoolean(KEY_MASTER_SWITCH, false);
        Settings.System.putInt(
                requireContext().getContentResolver(),
                TRICKYSTORE_ENABLED_KEY,
                propertyEnabled ? 1 : 0);
    }

    private String getSecureString(String key) {
        return Settings.Secure.getString(requireContext().getContentResolver(), key);
    }
}
