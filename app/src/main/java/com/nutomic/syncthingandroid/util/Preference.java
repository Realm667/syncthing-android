package com.nutomic.syncthingandroid.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.nutomic.syncthingandroid.service.Constants;

public class Preference {

    public static boolean getStartServiceOnBoot(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false);
    }

    public static boolean getUseRoot(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getBoolean(Constants.PREF_USE_ROOT, false);
    }
}
