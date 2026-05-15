/*
 * SPDX-FileCopyrightText: 2026 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.applications;

import android.content.Context;
import android.provider.Settings;

import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;

public final class TrickyStoreAppListController extends BasePreferenceController {
    private static final String KEY_MANAGE_TARGETS = "ts_manage_targets";
    private static final String SETTINGS_TARGETS = TrickyStoreAppListSettings.SETTINGS_TARGETS;

    public TrickyStoreAppListController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        return AVAILABLE;
    }

    @Override
    public CharSequence getSummary() {
        String flattenedString = Settings.Secure.getString(
                mContext.getContentResolver(), SETTINGS_TARGETS);
        int count = 0;
        if (flattenedString != null && !flattenedString.isBlank()) {
            for (String line : flattenedString.split("\n")) {
                if (!line.trim().isEmpty()) {
                    count++;
                }
            }
        }
        if (!KEY_MANAGE_TARGETS.equals(getPreferenceKey())) {
            return "";
        }
        if (count == 0) {
            return mContext.getString(R.string.ts_no_targets);
        }
        return mContext.getResources().getQuantityString(
                R.plurals.ts_target_apps_count, count, count);
    }
}
