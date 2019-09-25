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
 */

package ru.yanus171.feedexfork.activity;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.MenuItem;
import android.widget.TextView;

import ru.yanus171.feedexfork.R;

public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.about_flym);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        String title;
        PackageManager manager = this.getPackageManager();
        try {
            PackageInfo info = manager.getPackageInfo(this.getPackageName(), 0);
            title = "<big><b>" + getString( R.string.app_name ) + "</b></big><br/>" + getString( R.string.about_title_version ) + " " + info.versionName;
        } catch (NameNotFoundException unused) {
            title = getString( R.string.app_name );
        }
        TextView titleView = findViewById(R.id.about_title);
        titleView.setText(Html.fromHtml(title));

        TextView contentView = findViewById(R.id.about_content);
        //String html = getString(R.string.about_us_content);
        //if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        //    html = Html.fromHtml(html,Html.FROM_HTML_MODE_LEGACY);
        //} else {
        //    html = Html.fromHtml(html);
        //}
        contentView.setMovementMethod( LinkMovementMethod.getInstance() );
        contentView.setClickable(true);
        final String content = getString(R.string.about_us_content)
            .replace( "***developerList***", getString( R.string.developerList ) )
            .replace( "***translatorList***", getString( R.string.translatorList) );
        contentView.setText(Html.fromHtml(content));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case android.R.id.home:
                finish();
                return true;//
        }
        return (super.onOptionsItemSelected(menuItem));
    }

}

