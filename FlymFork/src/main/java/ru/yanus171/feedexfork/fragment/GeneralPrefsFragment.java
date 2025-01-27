/**
 * Flym
 * <p/>
 * Copyright (c) 2012-2015 Frederic Julian
 * <p/>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p/>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * <p/>
 * <p/>
 * Some parts of this software are based on "Sparse rss" under the MIT license (see
 * below). Please refers to the original project to identify which parts are under the
 * MIT license.
 * <p/>
 * Copyright (c) 2010-2012 Stefan Handschuh
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package ru.yanus171.feedexfork.fragment;

import android.app.Activity;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.text.TextUtils;

import ru.yanus171.feedexfork.MainApplication;
import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.activity.BaseActivity;
import ru.yanus171.feedexfork.service.AutoJobService;
import ru.yanus171.feedexfork.utils.Brightness;
import ru.yanus171.feedexfork.utils.FileUtils;
import ru.yanus171.feedexfork.utils.PrefUtils;
import ru.yanus171.feedexfork.utils.UiUtils;
import ru.yanus171.feedexfork.view.ColorPreference;
import ru.yanus171.feedexfork.view.StorageSelectPreference;

import static ru.yanus171.feedexfork.utils.PrefUtils.DATA_FOLDER;

public class GeneralPrefsFragment extends PreferenceFragment implements  PreferenceScreen.OnPreferenceClickListener {
    public static Boolean mSetupChanged = false;

    private Preference.OnPreferenceChangeListener mOnRefreshChangeListener = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            Activity activity = getActivity();
            if (activity != null) {
                if (Build.VERSION.SDK_INT >= 21 )
                    AutoJobService.init(activity);
            }
            return true;
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if ( PrefUtils.getString( DATA_FOLDER, "" ).isEmpty() )
            PrefUtils.putString( DATA_FOLDER, FileUtils.INSTANCE.GetDefaultStoragePath().getAbsolutePath() );

        addPreferencesFromResource(R.xml.general_preferences);

        setRingtoneSummary();

        Preference preference = findPreference(PrefUtils.REFRESH_ENABLED);
        preference.setOnPreferenceChangeListener(mOnRefreshChangeListener);
        preference = findPreference(PrefUtils.REFRESH_INTERVAL);
        preference.setOnPreferenceChangeListener(mOnRefreshChangeListener);

        if ( Build.VERSION.SDK_INT > 28 )
            findPreference("use_standard_file_manager").setEnabled( false );

        Preference.OnPreferenceChangeListener onRestartPreferenceChangeListener = new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                PrefUtils.putString(preference.getKey(), (String) newValue);
                PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext()).edit().commit(); // to be sure all prefs are written
                Process.killProcess(Process.myPid()); // Restart the app
                // this return statement will never be reached
                return true;
            }
        };
        findPreference(PrefUtils.THEME).setOnPreferenceChangeListener(onRestartPreferenceChangeListener);

        preference = findPreference(PrefUtils.LANGUAGE);
        preference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                PrefUtils.putString(PrefUtils.LANGUAGE, (String)newValue);
                PreferenceManager.getDefaultSharedPreferences(MainApplication.getContext()).edit().commit(); // to be sure all prefs are written
                android.os.Process.killProcess(android.os.Process.myPid()); // Restart the app
                // this return statement will never be reached
                return true;
            }
        });


        if ( PrefUtils.getBoolean(PrefUtils.BRIGHTNESS_GESTURE_ENABLED, false ) )
            ApplyBrightness( getPreferenceScreen(), (BaseActivity) getActivity());
    }

    private static void ApplyBrightness(PreferenceScreen screen, final BaseActivity activity ) {
        for ( int i = 0; i < screen.getPreferenceCount(); i++ ) {
            if (screen.getPreference(i) instanceof PreferenceScreen ||
                    screen.getPreference(i) instanceof ColorPreference)
                screen.getPreference(i).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        SetupBrightness(preference, activity);
                        return false;
                    }
                });
            if (screen.getPreference(i) instanceof PreferenceScreen)
                ApplyBrightness((PreferenceScreen) screen.getPreference(i), activity);
        }
    }


    @Override
    public void onDestroy() {
        SetupChanged();
        super.onDestroy();
    }

    public static void SetupChanged() {
        mSetupChanged = true;
        UiUtils.InvalidateTypeFace();
    }

    @Override
    public void onResume() {
        // The ringtone summary text should be updated using
        // OnSharedPreferenceChangeListener(), but I can't get it to work.
        // Updating in onResume is a very simple hack that seems to work, but is inefficient.
        setRingtoneSummary();

        super.onResume();

    }

    private void setRingtoneSummary() {
        Preference ringtone_preference = findPreference(PrefUtils.NOTIFICATIONS_RINGTONE);
        Uri ringtoneUri = Uri.parse(PrefUtils.getString(PrefUtils.NOTIFICATIONS_RINGTONE, ""));
        if (TextUtils.isEmpty(ringtoneUri.toString())) {
            ringtone_preference.setSummary(R.string.settings_notifications_ringtone_none);
        } else {
            Ringtone ringtone = RingtoneManager.getRingtone(MainApplication.getContext(), ringtoneUri);
            if (ringtone == null) {
                ringtone_preference.setSummary(R.string.settings_notifications_ringtone_none);
            } else {
                ringtone_preference.setSummary(ringtone.getTitle(MainApplication.getContext()));
            }
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        SetupBrightness(preference, getActivity());
        return true;
    }

    private static void SetupBrightness(Preference preference, Activity activity) {
        if ( preference instanceof PreferenceScreen) {
            PreferenceScreen screen = (PreferenceScreen) preference;
            Brightness br =  ((BaseActivity) activity ).mBrightness;
            if (screen.getDialog() != null) {
                Brightness.Companion.setBrightness(br.getMCurrentAlpha(), screen.getDialog().getWindow());
            }
        }
    }
}
