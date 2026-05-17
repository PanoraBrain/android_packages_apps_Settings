package co.aospa.settings.aospalab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;

import com.android.settings.applications.PifDataPreference;
import com.android.settings.applications.SpoofingUtils;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;
import com.android.settingslib.widget.MainSwitchPreference;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

@SearchIndexable
public class AospaLabSettings extends DashboardFragment {

    private static final String TAG = "AospaLabSettings";

    private static final String PIF_DATA_KEY = "pif_data_setting";
    private static final String PIF_PROPS_KEY = "pif_props";
    private static final String PIF_UPDATE_KEY = "pif_update";
    private static final String KEY_RANDOM_PROPERTIES_BUTTON = "update_pif_auto_random";

    private static final String SYS_SPOOF_PI = "persist.sys.pihooks.pi";
    private static final String SYS_SPOOF_PHOTOS = "persist.sys.pihooks.photos";
    private static final String SYS_TRICKYSTORE_ENABLED = "persist.sys.trickystore.enabled";
    private static final String SETTING_TRICKYSTORE_ENABLED = "spoof_trickystore_enabled";


    private ActivityResultLauncher<Intent> mPifFilePickerLauncher;

    private PifDataPreference mPifDataPreference;
    private Preference mRandomPropertiesButton;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.aospa_lab_settings;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.VIEW_UNKNOWN;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mPifFilePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Preference pref = findPreference(PIF_DATA_KEY);
                        if (pref instanceof PifDataPreference) {
                            ((PifDataPreference) pref).handleFileSelected(result.getData().getData());
                        }
                    }
                }
        );
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);


        mPifDataPreference = findPreference(PIF_DATA_KEY);

        if (mPifDataPreference != null) {
            mPifDataPreference.setFilePickerLauncher(mPifFilePickerLauncher);
        }

        MainSwitchPreference spoofPi = findPreference(SYS_SPOOF_PI);
        if (spoofPi != null) {
            spoofPi.setChecked(SystemProperties.getBoolean(SYS_SPOOF_PI, true));
            spoofPi.addOnSwitchChangeListener((switchView, isChecked) -> {
                SystemProperties.set(SYS_SPOOF_PI, isChecked ? "true" : "false");
                killTargetPackages(true);
            });
        }

        Preference spoofPhotos = findPreference(SYS_SPOOF_PHOTOS);
        if (spoofPhotos != null) {
            spoofPhotos.setOnPreferenceChangeListener((preference, newValue) -> {
                killTargetPackages(false);
                return true;
            });
        }

        // TrickyStore master switch removed from Aospa Lab screen

        Preference pifProps = findPreference(PIF_PROPS_KEY);
        if (pifProps != null) {
            pifProps.setOnPreferenceClickListener(preference -> {
                showPifProps();
                return true;
            });
        }

        Preference pifUpdate = findPreference(PIF_UPDATE_KEY);
        if (pifUpdate != null) {
            pifUpdate.setOnPreferenceClickListener(preference -> {
                if (!SystemProperties.getBoolean(SYS_SPOOF_PI, true)) {
                    Toast.makeText(getContext(), "Enable Play Integrity Spoofing first", Toast.LENGTH_SHORT).show();
                    return true;
                }
                new UpdatePifTask().execute();
                return true;
            });
        }

        mRandomPropertiesButton = findPreference(KEY_RANDOM_PROPERTIES_BUTTON);
        if (mRandomPropertiesButton != null) {
            mRandomPropertiesButton.setOnPreferenceClickListener(preference -> {
                getRandomFingerprint();
                return true;
            });
        }
    }

    private void showPifProps() {
        String fetchedPif = Settings.Secure.getString(getContext().getContentResolver(),
                Settings.Secure.FETCHED_PIF);
        String pifData = Settings.Secure.getString(getContext().getContentResolver(),
                Settings.Secure.PIF_DATA);

        StringBuilder sb = new StringBuilder();
        sb.append("Auto-updated PIF:\n");
        if (fetchedPif != null && !fetchedPif.isEmpty()) {
            try {
                JSONObject json = new JSONObject(fetchedPif);
                sb.append(json.toString(4));
            } catch (JSONException e) {
                sb.append(fetchedPif);
            }
        } else {
            sb.append("Not set");
        }

        sb.append("\n\nManually imported PIF:\n");
        if (pifData != null && !pifData.isEmpty()) {
            try {
                JSONObject json = new JSONObject(pifData);
                sb.append(json.toString(4));
            } catch (JSONException e) {
                sb.append(pifData);
            }
        } else {
            sb.append("Not set");
        }

        new AlertDialog.Builder(getContext())
                .setTitle("Play Integrity Fix Properties")
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private class UpdatePifTask extends AsyncTask<Void, Void, String> {
        private static final String PIF_URL = "https://raw.githubusercontent.com/Neoteric-OS/android_vendor_gms_spoof/refs/heads/master/gms_certified_props.json";

        @Override
        protected String doInBackground(Void... voids) {
            try {
                URL url = new URL(PIF_URL);
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    return response.toString();
                } finally {
                    urlConnection.disconnect();
                }
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(String result) {
            if (result != null) {
                Settings.Secure.putString(getContext().getContentResolver(),
                        Settings.Secure.PIF_DATA, "");
                Settings.Secure.putString(getContext().getContentResolver(),
                        Settings.Secure.FETCHED_PIF, result);
                killTargetPackages(true);
                Toast.makeText(getContext(), "PIF updated successfully", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Failed to update PIF", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void killTargetPackages(boolean isGms) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (isGms) {
                am.getClass().getMethod("forceStopPackage", String.class).invoke(am, "com.google.android.gms");
                am.getClass().getMethod("forceStopPackage", String.class).invoke(am, "com.android.vending");
            } else {
                am.getClass().getMethod("forceStopPackage", String.class).invoke(am, "com.google.android.apps.photos");
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to kill packages", e);
        }
    }

    private void getRandomFingerprint() {
        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Please wait")
                .setMessage("Fetching PIF properties...")
                .setCancelable(false)
                .setView(new ProgressBar(requireContext()))
                .create();
        dialog.show();

        new Thread(() -> {
            try {
                Map<String, String> newValues = SpoofingUtils.getRandomFingerprint(
                        SystemProperties.get("persist.sys.pihooks_DEVICE", ""));

                String spoofedModel = newValues.get("MODEL");

                JSONObject jsonProps = new JSONObject();
                for (Map.Entry<String, String> entry : newValues.entrySet()) {
                    jsonProps.put(entry.getKey(), entry.getValue());
                    SystemProperties.set("persist.sys.pihooks_" + entry.getKey(), entry.getValue());
                }

                String jsonString = jsonProps.toString();

                Settings.Secure.putString(getContext().getContentResolver(),
                        Settings.Secure.PIF_DATA, jsonString);

                String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                        java.util.Locale.getDefault()).format(new java.util.Date());
                Settings.Secure.putString(getContext().getContentResolver(),
                        Settings.Secure.PIF_DATA_TIMESTAMP, timestamp);

                try {
                    java.io.File pifFile = new java.io.File("/data/system/pif.json");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(pifFile);
                    fos.write(jsonString.getBytes());
                    fos.close();
                    pifFile.setReadable(true, false);
                } catch (java.io.IOException e) {
                    android.util.Log.e(TAG, "Failed to write pif.json", e);
                }

                mHandler.post(() -> {
                    if (getContext() != null) {
                        Toast.makeText(getContext(),
                                getString(R.string.toast_spoofing_success, spoofedModel),
                                Toast.LENGTH_LONG).show();

                        killTargetPackages(true);

                        if (mPifDataPreference != null) {
                            mPifDataPreference.setSummary(mPifDataPreference.getSummary());
                        }
                    }
                });
            } catch (Exception e) {
                android.util.Log.e(TAG, "Error generating random PIF", e);
                mHandler.post(() -> {
                    if (getContext() != null) {
                        Toast.makeText(getContext(), R.string.toast_spoofing_failure, Toast.LENGTH_SHORT).show();
                    }
                });
            } finally {
                mHandler.post(dialog::dismiss);
            }
        }).start();
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.aospa_lab_settings);
}
