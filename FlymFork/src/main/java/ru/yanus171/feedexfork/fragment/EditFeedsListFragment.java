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

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.ListFragment;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.yanus171.feedexfork.Constants;
import ru.yanus171.feedexfork.MainApplication;
import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.activity.AddGoogleNewsActivity;
import ru.yanus171.feedexfork.activity.EditFeedActivity;
import ru.yanus171.feedexfork.adapter.FeedsCursorAdapter;
import ru.yanus171.feedexfork.parser.OPML;
import ru.yanus171.feedexfork.provider.FeedData.FeedColumns;
import ru.yanus171.feedexfork.service.FetcherService;
import ru.yanus171.feedexfork.utils.EntryUrlVoc;
import ru.yanus171.feedexfork.utils.FileUtils;
import ru.yanus171.feedexfork.utils.PrefUtils;
import ru.yanus171.feedexfork.utils.UiUtils;
import ru.yanus171.feedexfork.utils.WaitDialog;
import ru.yanus171.feedexfork.view.DragNDropExpandableListView;
import ru.yanus171.feedexfork.view.DragNDropListener;

public class EditFeedsListFragment extends ListFragment {

    private static final int REQUEST_PICK_OPML_FILE = 1;
    private static final int PERMISSIONS_REQUEST_IMPORT_FROM_OPML = 1;
    private static final int PERMISSIONS_REQUEST_EXPORT_TO_OPML = 2;

    private final ActionMode.Callback mFeedActionModeCallback = new ActionMode.Callback() {

        // Called when the action mode is created; startActionMode() was called
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // Inflate a menu resource providing context menu items
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.feed_context_menu, menu);
            return true;
        }

        // Called each time the action mode is shown. Always called after onCreateActionMode, but
        // may be called multiple times if the mode is invalidated.
        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false; // Return false if nothing is done
        }

        // Called when the user selects a contextual menu item
        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            @SuppressWarnings("unchecked")
            Pair<Long, String> tag = (Pair<Long, String>) mode.getTag();
            final long feedId = tag.first;
            final String title = tag.second;

            switch (item.getItemId()) {
                case R.id.menu_edit:
                    startActivity(new Intent(Intent.ACTION_EDIT).setData(FeedColumns.CONTENT_URI(feedId)));

                    mode.finish(); // Action picked, so close the CAB
                    return true;
                case R.id.menu_delete:
                    DeleteFeed(EditFeedsListFragment.this.getActivity(), FeedColumns.CONTENT_URI(feedId), title);

                    mode.finish(); // Action picked, so close the CAB
                    return true;
                default:
                    return false;
            }
        }

        // Called when the user exits the action mode
        @Override
        public void onDestroyActionMode(ActionMode mode) {
            for (int i = 0; i < mListView.getCount(); i++) {
                mListView.setItemChecked(i, false);
            }
        }
    };

    public static void DeleteFeed(final Activity activity,  final Uri feedUri, String title) {
        new AlertDialog.Builder(activity) //
                .setIcon(android.R.drawable.ic_dialog_alert) //
                .setTitle(title) //
                .setMessage(R.string.question_delete_feed) //
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new Thread() {
                            @Override
                            public void run() {
                                ContentResolver cr = MainApplication.getContext().getContentResolver();
                                cr.delete(feedUri, null, null);
                                EntryUrlVoc.INSTANCE.reinit();
                            }
                        }.start();
                        if ( activity instanceof EditFeedActivity )
                            activity.finish();
                    }
                }).setNegativeButton(android.R.string.no, null).show();
    }

    private final ActionMode.Callback mGroupActionModeCallback = new ActionMode.Callback() {

        // Called when the action mode is created; startActionMode() was called
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // Inflate a menu resource providing context menu items
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.edit_context_menu, menu);
            return true;
        }

        // Called each time the action mode is shown. Always called after onCreateActionMode, but
        // may be called multiple times if the mode is invalidated.
        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false; // Return false if nothing is done
        }

        // Called when the user selects a contextual menu item
        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            @SuppressWarnings("unchecked")
            Pair<Long, String> tag = (Pair<Long, String>) mode.getTag();
            final long groupId = tag.first;
            final String title = tag.second;

            switch (item.getItemId()) {
                case R.id.menu_edit:
                    final EditText input = new EditText(getActivity());
                    input.setSingleLine(true);
                    input.setText(title);
                    new AlertDialog.Builder(getActivity()) //
                            .setTitle(R.string.edit_group_title) //
                            .setView(input) //
                            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    new Thread() {
                                        @Override
                                        public void run() {
                                            String groupName = input.getText().toString();
                                            if (!groupName.isEmpty()) {
                                                ContentResolver cr = getActivity().getContentResolver();
                                                ContentValues values = new ContentValues();
                                                values.put(FeedColumns.NAME, groupName);
                                                cr.update(FeedColumns.CONTENT_URI(groupId), values, null, null);
                                            }
                                        }
                                    }.start();
                                }
                            }).setNegativeButton(android.R.string.cancel, null).show();

                    mode.finish(); // Action picked, so close the CAB
                    return true;
                case R.id.menu_delete:
                    new AlertDialog.Builder(getActivity()) //
                            .setIcon(android.R.drawable.ic_dialog_alert) //
                            .setTitle(title) //
                            .setMessage(R.string.question_delete_group) //
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    new Thread() {
                                        @Override
                                        public void run() {
                                            ContentResolver cr = getActivity().getContentResolver();
                                            cr.delete(FeedColumns.GROUPS_CONTENT_URI(groupId), null, null);
                                        }
                                    }.start();
                                }
                            }).setNegativeButton(android.R.string.no, null).show();

                    mode.finish(); // Action picked, so close the CAB
                    return true;
                default:
                    return false;
            }
        }

        // Called when the user exits the action mode
        @Override
        public void onDestroyActionMode(ActionMode mode) {
            for (int i = 0; i < mListView.getCount(); i++) {
                mListView.setItemChecked(i, false);
            }
        }
    };
    private DragNDropExpandableListView mListView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        setHasOptionsMenu(true);
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_edit_feed_list, container, false);

        mListView = rootView.findViewById(android.R.id.list);
        mListView.setFastScrollEnabled(true);
        mListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        mListView.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
            @Override
            public boolean onChildClick(ExpandableListView parent, View v, int groupPosition, int childPosition, long id) {
                startActivity(new Intent(Intent.ACTION_EDIT).setData(FeedColumns.CONTENT_URI(id)));
                return true;
            }
        });
        mListView.setOnGroupClickListener(new ExpandableListView.OnGroupClickListener() {
            @Override
            public boolean onGroupClick(ExpandableListView parent, View v, int groupPosition, long id) {
                if (v.findViewById(R.id.indicator).getVisibility() != View.VISIBLE) { // This is no a real group
                    startActivity(new Intent(Intent.ACTION_EDIT).setData(FeedColumns.CONTENT_URI(id)));
                    return true;
                }
                return false;
            }
        });
        mListView.setOnItemLongClickListener(new OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                AppCompatActivity activity = (AppCompatActivity) getActivity();
                if (activity != null) {
                    String title = ((TextView) view.findViewById(android.R.id.text1)).getText().toString();
                    Matcher m = Pattern.compile("(.*) \\([0-9]+\\)$").matcher(title);
                    if (m.matches()) {
                        title = m.group(1);
                    }

                    long feedId = mListView.getItemIdAtPosition(position);
                    ActionMode actionMode;
                    if (view.findViewById(R.id.indicator).getVisibility() == View.VISIBLE) { // This is a group
                        actionMode = activity.startSupportActionMode(mGroupActionModeCallback);
                    } else { // This is a feed
                        actionMode = activity.startSupportActionMode(mFeedActionModeCallback);
                    }
                    actionMode.setTag(new Pair<>(feedId, title));

                    mListView.setItemChecked(position, true);
                }
                return true;
            }
        });

        mListView.setAdapter(new FeedsCursorAdapter(getActivity(), FeedColumns.GROUPS_AND_ROOT_CONTENT_URI));

        mListView.setDragNDropListener(new DragNDropListener() {
            boolean fromHasGroupIndicator = false;

            @Override
            public void onStopDrag(View itemView) {
            }

            @Override
            public void onStartDrag(View itemView) {
                fromHasGroupIndicator = itemView.findViewById(R.id.indicator).getVisibility() == View.VISIBLE;
            }

            @Override
            public void onDrop(final int flatPosFrom, final int flatPosTo) {
                final boolean fromIsGroup = ExpandableListView.getPackedPositionType(mListView.getExpandableListPosition(flatPosFrom)) == ExpandableListView.PACKED_POSITION_TYPE_GROUP;
                final boolean toIsGroup = ExpandableListView.getPackedPositionType(mListView.getExpandableListPosition(flatPosTo)) == ExpandableListView.PACKED_POSITION_TYPE_GROUP;

                final boolean fromIsFeedWithoutGroup = fromIsGroup && !fromHasGroupIndicator;

                View toView = mListView.getChildAt(flatPosTo - mListView.getFirstVisiblePosition());
                boolean toIsFeedWithoutGroup = toIsGroup && toView.findViewById(R.id.indicator).getVisibility() != View.VISIBLE;

                final long packedPosTo = mListView.getExpandableListPosition(flatPosTo);
                final int packedGroupPosTo = ExpandableListView.getPackedPositionGroup(packedPosTo);

                if ((fromIsFeedWithoutGroup || !fromIsGroup) && toIsGroup && !toIsFeedWithoutGroup) {
                    new AlertDialog.Builder(getActivity()) //
                            .setTitle(R.string.to_group_title) //
                            .setMessage(R.string.to_group_message) //
                            .setPositiveButton(R.string.to_group_into, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    ContentValues values = new ContentValues();
                                    values.put(FeedColumns.PRIORITY, 1);
                                    values.put(FeedColumns.GROUP_ID, mListView.getItemIdAtPosition(flatPosTo));

                                    ContentResolver cr = getActivity().getContentResolver();
                                    cr.update(FeedColumns.CONTENT_URI(mListView.getItemIdAtPosition(flatPosFrom)), values, null, null);
                                    cr.notifyChange(FeedColumns.GROUPS_AND_ROOT_CONTENT_URI, null);
                                }
                            }).setNegativeButton(R.string.to_group_above, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            moveItem(fromIsGroup, toIsGroup, fromIsFeedWithoutGroup, packedPosTo, packedGroupPosTo, flatPosFrom);
                        }
                    }).show();
                } else {
                    moveItem(fromIsGroup, toIsGroup, fromIsFeedWithoutGroup, packedPosTo, packedGroupPosTo, flatPosFrom);
                }
            }

            @Override
            public void onDrag(int x, int y, ListView listView) {
            }
        });

        return rootView;
    }

    private void moveItem(boolean fromIsGroup, boolean toIsGroup, boolean fromIsFeedWithoutGroup, long packedPosTo, int packedGroupPosTo,
                          int flatPosFrom) {
        ContentValues values = new ContentValues();
        ContentResolver cr = getActivity().getContentResolver();

        if (fromIsGroup && toIsGroup) {
            values.put(FeedColumns.PRIORITY, packedGroupPosTo + 1);
            cr.update(FeedColumns.CONTENT_URI(mListView.getItemIdAtPosition(flatPosFrom)), values, null, null);
        } else if (!fromIsGroup && toIsGroup) {
            values.put(FeedColumns.PRIORITY, packedGroupPosTo + 1);
            values.putNull(FeedColumns.GROUP_ID);
            cr.update(FeedColumns.CONTENT_URI(mListView.getItemIdAtPosition(flatPosFrom)), values, null, null);
        } else if ((!fromIsGroup && !toIsGroup) || (fromIsFeedWithoutGroup && !toIsGroup)) {
            int groupPrio = ExpandableListView.getPackedPositionChild(packedPosTo) + 1;
            values.put(FeedColumns.PRIORITY, groupPrio);

            int flatGroupPosTo = mListView.getFlatListPosition(ExpandableListView.getPackedPositionForGroup(packedGroupPosTo));
            values.put(FeedColumns.GROUP_ID, mListView.getItemIdAtPosition(flatGroupPosTo));
            cr.update(FeedColumns.CONTENT_URI(mListView.getItemIdAtPosition(flatPosFrom)), values, null, null);
        }
    }

    @Override
    public void onDestroy() {
        getLoaderManager().destroyLoader(0); // This is needed to avoid an activity leak!
        super.onDestroy();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.feed_list, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_add_feed: {
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                builder.setTitle(R.string.menu_add_feed)
                        .setItems(new CharSequence[]{getString(R.string.add_custom_feed), getString(R.string.google_news_title)}, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    startActivity(new Intent(Intent.ACTION_INSERT).setData(FeedColumns.CONTENT_URI));
                                } else {
                                    startActivity(new Intent(getActivity(), AddGoogleNewsActivity.class));
                                }
                            }
                        });
                builder.show();
                return true;
            }
            case R.id.menu_add_group: {
                final EditText input = new EditText(getActivity());
                input.setSingleLine(true);
                new AlertDialog.Builder(getActivity()) //
                        .setTitle(R.string.add_group_title) //
                        .setView(input) //
                                // .setMessage(R.string.add_group_sentence) //
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                new Thread() {
                                    @Override
                                    public void run() {
                                        String groupName = input.getText().toString();
                                        if (!groupName.isEmpty()) {
                                            ContentResolver cr = getActivity().getContentResolver();
                                            ContentValues values = new ContentValues();
                                            values.put(FeedColumns.IS_GROUP, true);
                                            values.put(FeedColumns.NAME, groupName);
                                            cr.insert(FeedColumns.GROUPS_CONTENT_URI, values);
                                        }
                                    }
                                }.start();
                            }
                        }).setNegativeButton(android.R.string.cancel, null).show();
                return true;
            }
            case R.id.menu_export:
            case R.id.menu_import: {
                if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    // Should we show an explanation?
                    if (ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE)) {

                        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                        builder.setMessage(R.string.storage_request_explanation).setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                if (item.getItemId() == R.id.menu_export) {
                                    ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_EXPORT_TO_OPML);
                                } else if (item.getItemId() == R.id.menu_import) {
                                    ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_IMPORT_FROM_OPML);
                                }
                            }
                        }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                // User cancelled the dialog
                            }
                        });
                        builder.show();
                    } else {
                        // No explanation needed, we can request the permission.
                        if (item.getItemId() == R.id.menu_export) {
                            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_EXPORT_TO_OPML);
                        } else if (item.getItemId() == R.id.menu_import) {
                            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_IMPORT_FROM_OPML);
                        }
                    }
                } else {
                    if (item.getItemId() == R.id.menu_export) {
                        exportToOpml();
                    } else if (item.getItemId() == R.id.menu_import) {
                        importFromOpml();
                    }
                }

                return true;
            }
            /*case R.id.menu_fix_order: {
                FeedDataContentProvider.mPriorityManagement = false;

                final ProgressDialog pd = new ProgressDialog(getContext());
                pd.setMessage(getString(R.string.applyOperations));
                //pd.setCancelable(true);
                //pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                pd.setIndeterminate(true);
                pd.show();

                final Handler handler = new Handler();

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final ArrayList<ContentProviderOperation> operations = new ArrayList<>();
                        ContentResolver cr = MainApplication.getContext().getContentResolver();
                        try {
                            int order = 0;
                            {
                                final Cursor cur = cr.query(FeedColumns.GROUPS_AND_ROOT_CONTENT_URI, new String[]{FeedColumns._ID}, null, null, FeedColumns.PRIORITY + ", " + FeedColumns._ID);
                                while (cur.moveToNext()) {
                                    order++;
                                    operations.add(ContentProviderOperation.newUpdate(FeedColumns.CONTENT_URI(cur.getLong(0))).withValue(FeedColumns.PRIORITY, order).build());
                                }
                                cur.close();
                            }

                            {
                                final Cursor cur = cr.query(FeedColumns.GROUPS_CONTENT_URI, new String[]{FeedColumns._ID}, null, null, FeedColumns.PRIORITY + ", " + FeedColumns._ID);
                                while (cur.moveToNext()) {
                                    order = 0;
                                    Cursor curGroupFeeds = cr.query(FeedColumns.FEEDS_FOR_GROUPS_CONTENT_URI(cur.getLong(0)), new String[]{FeedColumns._ID}, null, null, FeedColumns.PRIORITY + ", " + FeedColumns._ID);
                                    while (curGroupFeeds.moveToNext()) {
                                        order++;
                                        operations.add(ContentProviderOperation.newUpdate(FeedColumns.CONTENT_URI(curGroupFeeds.getLong(0))).withValue(FeedColumns.PRIORITY, order).build());
                                    }
                                    curGroupFeeds.close();

                                }
                                cur.close();
                            }
                            try {
                                cr.applyBatch(FeedData.AUTHORITY, operations);
                            } catch (Throwable ignored) {
                                ignored.printStackTrace();
                            }
                        } finally {
                            FeedDataContentProvider.mPriorityManagement = true;
                        }
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                pd.cancel();
                            }
                        });

                    }
                }).start();


                return true;
            }*/
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_EXPORT_TO_OPML: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    exportToOpml();
                }
                return;
            }
            case PERMISSIONS_REQUEST_IMPORT_FROM_OPML: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    importFromOpml();
                }
                return;
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        if (requestCode == REQUEST_PICK_OPML_FILE) {
            if (resultCode == Activity.RESULT_OK) {
                FetcherService.StartService( FetcherService.GetIntent( Constants.FROM_IMPORT )
                        .putExtra( Constants.EXTRA_URI, data.getData().toString() ) );
            } else {
                displayCustomFilePicker();
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    private void displayCustomFilePicker() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

        builder.setTitle(R.string.select_file);

        try {
            final String[] fileNames = FileUtils.INSTANCE.getFolder().list(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String filename) {
                    return new File(dir, filename).isFile();
                }
            });
            builder.setItems(fileNames, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, final int which) {
                    FetcherService.StartService( FetcherService.GetIntent( Constants.FROM_IMPORT ).
                            putExtra( Constants.EXTRA_FILENAME, FileUtils.INSTANCE.getFolder().toString() + File.separator
                                    + fileNames[which]) );
                }
            });
            builder.show();
        } catch (Exception unused) {
            UiUtils.showMessage(getActivity(), R.string.error_feed_import);
        }
    }

    private void importFromOpml() {
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)
                || Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {

            if (PrefUtils.getBoolean("use_standard_file_manager", false)) {
                // First, try to use a file app
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);

                    intent.setType("*/*");
                    startActivityForResult(intent, REQUEST_PICK_OPML_FILE);
                } catch (Exception unused) { // Else use a custom file selector
                    unused.printStackTrace();
                    displayCustomFilePicker();
                }
            } else
                displayCustomFilePicker();
        } else {
            UiUtils.showMessage(getActivity(), R.string.error_external_storage_not_available);
        }
    }

    private void exportToOpml() {
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)
                || Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {

            new WaitDialog(getActivity(), R.string.exportingToFile, new Runnable() {
                @Override
                public void run() {
                    try {
                        final String dateTimeStr = new SimpleDateFormat( "yyyyMMdd_HHmmss" ).format( new Date( System.currentTimeMillis() ) );
                        final String filename =  FileUtils.INSTANCE.getFolder() +  "/HandyNewsReader_" + dateTimeStr + ".opml";

                        OPML.exportToFile(filename);
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                UiUtils.showMessage(getActivity(), String.format(getString(R.string.message_exported_to), filename));
                            }
                        });
                    } catch (IOException e) {
                        e.printStackTrace();
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                UiUtils.showMessage(getActivity(), R.string.error_feed_export);
                            }
                        });
                    }
                }
            }).execute();
        } else {
            UiUtils.showMessage(getActivity(), R.string.error_external_storage_not_available);
        }
    }
}
