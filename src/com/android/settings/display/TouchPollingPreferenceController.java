/*
 * Copyright (C) 2021 The Proton AOSP Project
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

package com.android.settings.display;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Settings;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;

import com.android.settings.R;
import com.android.settings.core.TogglePreferenceController;

public class TouchPollingPreferenceController extends TogglePreferenceController
        implements LifecycleObserver, OnStart, OnStop {

    // Settings can only set the debug.* property, so we need to persist it
    // in system settings. Match the stock setting name for backup compatibility.
    private static final String SETTINGS_KEY = "touch_polling_enabled";
    private static final String PROP_NAME = "debug.touch_polling_mode";

    private Preference mPreference;
    private final PowerManager mPowerManager;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mPreference != null)
                updateState(mPreference);
        }
    };

    public TouchPollingPreferenceController(Context context, String preferenceKey) {
        super(context, preferenceKey);
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    @Override
    public void onStart() {
        mContext.registerReceiver(mReceiver,
                new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
    }

    @Override
    public void onStop() {
        mContext.unregisterReceiver(mReceiver);
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mPreference = screen.findPreference(getPreferenceKey());
    }

    @Override
    public CharSequence getSummary() {
        return mContext.getString(mPowerManager.isPowerSaveMode()
                ? R.string.dark_ui_mode_disabled_summary_dark_theme_on
                : R.string.touch_polling_summary);
    }

    @Override
    public int getAvailabilityStatus() {
        return mContext.getResources().getBoolean(R.bool.config_supportTouchPollingMode)
            ? AVAILABLE
            : UNSUPPORTED_ON_DEVICE;
    }

    @Override
    public boolean setChecked(boolean value) {
        Settings.Secure.putInt(mContext.getContentResolver(), SETTINGS_KEY, value ? 1 : 0);
        SystemProperties.set(PROP_NAME, value ? "1" : "0");
        return true;
    }

    @Override
    public boolean isChecked() {
        // debug prop isn't persistent
        return Settings.Secure.getInt(mContext.getContentResolver(), SETTINGS_KEY, 0) == 1;
    }

    @Override
    public void updateState(Preference preference) {
        super.updateState(preference);
        refreshSummary(preference);
        preference.setEnabled(!mPowerManager.isPowerSaveMode());
    }

    @Override
    public int getSliceHighlightMenuRes() {
        return R.string.menu_key_display;
    }
}
