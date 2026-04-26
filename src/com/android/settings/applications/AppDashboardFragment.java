/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.applications;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.settings.SettingsEnums;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.provider.SearchIndexableResource;
import android.provider.Settings;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settings.R;
import com.android.settings.applications.appcompat.UserAspectRatioAppsPreferenceController;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.widget.PreferenceCategoryController;
import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.search.SearchIndexable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONException;

/** Settings page for apps. */
@SearchIndexable
public class AppDashboardFragment extends DashboardFragment {

    private static final String TAG = "AppDashboardFragment";
    private static final String ADVANCED_CATEGORY_KEY = "advanced_category";
    private static final String ASPECT_RATIO_PREF_KEY = "aspect_ratio_apps";
    private static final String KEYBOX_DATA_KEY = "keybox_data_setting";
    private static final String PIF_DATA_KEY = "pif_data_setting";
    private static final String PIF_PROPS_KEY = "pif_props";
    private static final String PIF_UPDATE_KEY = "pif_update";

    private static final String SYS_SPOOF_PI = "persist.sys.pihooks.pi";
    private static final String SYS_SPOOF_PHOTOS = "persist.sys.pihooks.photos";

    private ActivityResultLauncher<Intent> mKeyboxFilePickerLauncher;
    private ActivityResultLauncher<Intent> mPifFilePickerLauncher;
    private KeyboxDataPreference mKeyboxDataPreference;
    private PifDataPreference mPifDataPreference;
    private AppsPreferenceController mAppsPreferenceController;

    private static List<AbstractPreferenceController> buildPreferenceControllers(Context context) {
        final List<AbstractPreferenceController> controllers = new ArrayList<>();
        controllers.add(new AppsPreferenceController(context));

        final UserAspectRatioAppsPreferenceController aspectRatioAppsPreferenceController =
                new UserAspectRatioAppsPreferenceController(context, ASPECT_RATIO_PREF_KEY);
        final AdvancedAppsPreferenceCategoryController advancedCategoryController =
                new AdvancedAppsPreferenceCategoryController(context, ADVANCED_CATEGORY_KEY);
        advancedCategoryController.setChildren(List.of(aspectRatioAppsPreferenceController));
        controllers.add(advancedCategoryController);

        return controllers;
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.MANAGE_APPLICATIONS;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    public int getHelpResource() {
        return R.string.help_url_apps_and_notifications;
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.apps;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mAppsPreferenceController = use(AppsPreferenceController.class);
        mAppsPreferenceController.setFragment(this /* fragment */);
        getSettingsLifecycle().addObserver(mAppsPreferenceController);

        final HibernatedAppsPreferenceController hibernatedAppsPreferenceController =
                use(HibernatedAppsPreferenceController.class);
        getSettingsLifecycle().addObserver(hibernatedAppsPreferenceController);

        mKeyboxFilePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    Preference pref = findPreference(KEYBOX_DATA_KEY);
                    if (pref instanceof KeyboxDataPreference) {
                        ((KeyboxDataPreference) pref).handleFileSelected(uri);
                    }
                }
            }
        );

        mPifFilePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    Preference pref = findPreference(PIF_DATA_KEY);
                    if (pref instanceof PifDataPreference) {
                        ((PifDataPreference) pref).handleFileSelected(uri);
                    }
                }
            }
        );
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mKeyboxDataPreference = findPreference(KEYBOX_DATA_KEY);
        mPifDataPreference = findPreference(PIF_DATA_KEY);

        if (mKeyboxDataPreference != null) {
            mKeyboxDataPreference.setFilePickerLauncher(mKeyboxFilePickerLauncher);
        }

        if (mPifDataPreference != null) {
            mPifDataPreference.setFilePickerLauncher(mPifFilePickerLauncher);
        }

        Preference spoofPi = findPreference(SYS_SPOOF_PI);
        if (spoofPi != null) {
            spoofPi.setOnPreferenceChangeListener((preference, newValue) -> {
                killTargetPackages(true);
                return true;
            });
        }

        Preference spoofPhotos = findPreference(SYS_SPOOF_PHOTOS);
        if (spoofPhotos != null) {
            spoofPhotos.setOnPreferenceChangeListener((preference, newValue) -> {
                killTargetPackages(false);
                return true;
            });
        }

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
                if (!android.os.SystemProperties.getBoolean(SYS_SPOOF_PI, true)) {
                    Toast.makeText(getContext(), "Enable Play Integrity Spoofing first", Toast.LENGTH_SHORT).show();
                    return true;
                }
                new UpdatePifTask().execute();
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

    @VisibleForTesting
    PreferenceCategoryController getAdvancedAppsPreferenceCategoryController() {
        return use(AdvancedAppsPreferenceCategoryController.class);
    }

    @Override
    protected List<AbstractPreferenceController> createPreferenceControllers(Context context) {
        return buildPreferenceControllers(context);
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider() {
                @Override
                public List<SearchIndexableResource> getXmlResourcesToIndex(
                        Context context, boolean enabled) {
                    final SearchIndexableResource sir = new SearchIndexableResource(context);
                    sir.xmlResId = R.xml.apps;
                    return Arrays.asList(sir);
                }

                @Override
                public List<AbstractPreferenceController> createPreferenceControllers(
                        Context context) {
                    return buildPreferenceControllers(context);
                }
            };
}
