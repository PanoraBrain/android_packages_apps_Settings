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

import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.icu.text.MessageFormat;
import android.provider.Settings;

import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A preference controller handling the logic for updating summary of hidden apps.
 */
public final class HideAppListController extends BasePreferenceController {
    private static final String TAG = "HideAppListController";
    private int mHidden = 0;
    private Context mContext = null;

    public HideAppListController(Context context, String preferenceKey) {
        super(context, preferenceKey);
        mContext = context;
    }

    @Override
    public int getAvailabilityStatus() {
        return AVAILABLE;
    }

    @Override
    public CharSequence getSummary() {
        String flattenedString = Settings.Secure.getString(mContext.getContentResolver(), Settings.Secure.HIDE_APPLIST);
        if (flattenedString != null && !flattenedString.isBlank()) {
            mHidden = flattenedString.split(",").length;
        }
        MessageFormat msgFormat = new MessageFormat(
            mContext.getResources().getString(R.string.hide_applist_summary),
            Locale.getDefault());
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("count", mHidden);
        return msgFormat.format(arguments);
    }
}
