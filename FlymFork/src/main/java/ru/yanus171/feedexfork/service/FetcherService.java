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

package ru.yanus171.feedexfork.service;

import android.app.IntentService;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import android.text.Html;
import android.text.TextUtils;
import android.util.Pair;
import android.util.SparseLongArray;
import android.util.Xml;
import android.widget.Toast;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;

import ru.yanus171.feedexfork.Constants;
import ru.yanus171.feedexfork.MainApplication;
import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.activity.HomeActivity;
import ru.yanus171.feedexfork.parser.HTMLParser;
import ru.yanus171.feedexfork.parser.OPML;
import ru.yanus171.feedexfork.parser.RssAtomParser;
import ru.yanus171.feedexfork.provider.FeedData;
import ru.yanus171.feedexfork.provider.FeedData.EntryColumns;
import ru.yanus171.feedexfork.provider.FeedData.FeedColumns;
import ru.yanus171.feedexfork.provider.FeedData.TaskColumns;
import ru.yanus171.feedexfork.provider.FeedDataContentProvider;
import ru.yanus171.feedexfork.utils.ArticleTextExtractor;
import ru.yanus171.feedexfork.utils.DebugApp;
import ru.yanus171.feedexfork.utils.FileUtils;
import ru.yanus171.feedexfork.utils.HtmlUtils;
import ru.yanus171.feedexfork.utils.NetworkUtils;
import ru.yanus171.feedexfork.utils.PrefUtils;
import ru.yanus171.feedexfork.utils.Timer;
import ru.yanus171.feedexfork.utils.UiUtils;
import ru.yanus171.feedexfork.view.StatusText;

import static ru.yanus171.feedexfork.MainApplication.NOTIFICATION_CHANNEL_ID;

public class FetcherService extends IntentService {

    public static final String ACTION_REFRESH_FEEDS = FeedData.PACKAGE_NAME + ".REFRESH";
    public static final String ACTION_MOBILIZE_FEEDS = FeedData.PACKAGE_NAME + ".MOBILIZE_FEEDS";
    private static final String ACTION_LOAD_LINK = FeedData.PACKAGE_NAME + ".LOAD_LINK";
    //public static final String ACTION_DOWNLOAD_IMAGES = FeedData.PACKAGE_NAME + ".DOWNLOAD_IMAGES";

    private static final int THREAD_NUMBER = 3;
    private static final int MAX_TASK_ATTEMPT = 3;

    private static final int FETCHMODE_DIRECT = 1;
    private static final int FETCHMODE_REENCODE = 2;
    public static final int FETCHMODE_EXERNAL_LINK = 3;

    private static final String CHARSET = "charset=";
    private static final String CONTENT_TYPE_TEXT_HTML = "text/html";
    private static final String HREF = "href=\"";

    private static final String HTML_BODY = "<body";
    private static final String ENCODING = "encoding=\"";
    public static final String CUSTOM_KEEP_TIME = "customKeepTime";

    public static Boolean mCancelRefresh = false;
    private static final ArrayList<Long> mActiveEntryIDList = new ArrayList<>();
    private static Boolean mIsDownloadImageCursorNeedsRequery = false;

    private static volatile Boolean mIsDeletingOld = false;

    public static final ArrayList<MarkItem> mMarkAsStarredFoundList = new ArrayList<>();

    /* Allow different positions of the "rel" attribute w.r.t. the "href" attribute */
    public static final Pattern FEED_LINK_PATTERN = Pattern.compile(
            "[.]*<link[^>]* ((rel=alternate|rel=\"alternate\")[^>]* href=\"[^\"]*\"|href=\"[^\"]*\"[^>]* (rel=alternate|rel=\"alternate\"))[^>]*>",
            Pattern.CASE_INSENSITIVE);
    public static int mMaxImageDownloadCount = PrefUtils.getImageDownloadCount();
    private boolean mDeleteOld = true;

    public static StatusText.FetcherObservable Status() {
        if (mStatusText == null) {
            mStatusText = new StatusText.FetcherObservable();
        }
        return mStatusText;
    }

    private static StatusText.FetcherObservable mStatusText = null;

    public FetcherService() {
        super(FetcherService.class.getSimpleName());
        HttpURLConnection.setFollowRedirects(true);
    }

    public static boolean hasMobilizationTask(long entryId) {
        Cursor cursor = MainApplication.getContext().getContentResolver().query(TaskColumns.CONTENT_URI, TaskColumns.PROJECTION_ID,
                TaskColumns.ENTRY_ID + '=' + entryId + Constants.DB_AND + TaskColumns.IMG_URL_TO_DL + Constants.DB_IS_NULL, null, null);

        boolean result = cursor.getCount() > 0;
        cursor.close();

        return result;
    }

    public static void addImagesToDownload(String entryId, ArrayList<String> images) {
        if (images != null && !images.isEmpty()) {
            ContentValues[] values = new ContentValues[images.size()];
            for (int i = 0; i < images.size(); i++) {
                values[i] = new ContentValues();
                values[i].put(TaskColumns.ENTRY_ID, entryId);
                values[i].put(TaskColumns.IMG_URL_TO_DL, images.get(i));
            }

            MainApplication.getContext().getContentResolver().bulkInsert(TaskColumns.CONTENT_URI, values);
        }
    }

    public static void addEntriesToMobilize(Long[] entriesId) {
        ContentValues[] values = new ContentValues[entriesId.length];
        for (int i = 0; i < entriesId.length; i++) {
            values[i] = new ContentValues();
            values[i].put(TaskColumns.ENTRY_ID, entriesId[i]);
        }

        MainApplication.getContext().getContentResolver().bulkInsert(TaskColumns.CONTENT_URI, values);
    }

    static boolean isBatteryLow() {

        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent battery = MainApplication.getContext().registerReceiver(null, ifilter);
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        float batteryPct = level / (float)scale * 100;

        long lowLevelPct = 20;
        try {
            lowLevelPct = Math.max(50, Long.parseLong(PrefUtils.getString("refresh.min_update_battery_level", 20)) );
        } catch (Exception ignored) {
        }
        return batteryPct < lowLevelPct;
    }

    @Override
    public void onHandleIntent(final Intent intent) {
        if (intent == null) { // No intent, we quit
            return;
        }
        Status().ClearError();

        if (intent.hasExtra(Constants.FROM_AUTO_BACKUP)) {
            LongOper(R.string.exportingToFile, new Runnable() {
                @Override
                public void run() {
                try {
                    OPML.exportToFile(OPML.GetAutoBackupOPMLFileName());
                    PrefUtils.putLong( AutoJobService.LAST_JOB_OCCURED + PrefUtils.AUTO_BACKUP_INTERVAL, System.currentTimeMillis() );
                    UiUtils.RunOnGuiThread(  new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText( MainApplication.getContext(), getString(R.string.auto_backup_opml_file_created) + OPML.GetAutoBackupOPMLFileName(), Toast.LENGTH_LONG ).show();
                        }
                    });
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                    DebugApp.SendException( e, FetcherService.this );
                }
                }
            });
            return;
        } else if (intent.hasExtra(Constants.FROM_IMPORT)) {
            LongOper(R.string.importingFromFile, new Runnable() {
                @Override
                public void run() {
                    try {
                        OPML.importFromFile( intent.getStringExtra( Constants.EXTRA_FILENAME ) );
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (Exception e) {
                        e.printStackTrace();
                        DebugApp.SendException(e, FetcherService.this);
                    }
                }
            });
            return;
        }


        mIsWiFi = GetIsWifi();

        mDeleteOld = intent.getBooleanExtra(Constants.EXTRA_DELETE_OLD, true);
        final boolean isFromAutoRefresh = intent.getBooleanExtra(Constants.FROM_AUTO_REFRESH, false);

        if (ACTION_MOBILIZE_FEEDS.equals(intent.getAction())) {
            mobilizeAllEntries(isFromAutoRefresh);
            downloadAllImages();
        } else if (ACTION_LOAD_LINK.equals(intent.getAction())) {
            LongOper(R.string.loadingLink, new Runnable() {
                @Override
                public void run() {
                    LoadLink(GetExtrenalLinkFeedID(),
                            intent.getStringExtra(Constants.URL_TO_LOAD),
                            intent.getStringExtra(Constants.TITLE_TO_LOAD),
                            FetcherService.ForceReload.No,
                            true,
                            true);
                    downloadAllImages();
                }
            } );

        //} else if (ACTION_DOWNLOAD_IMAGES.equals(intent.getAction())) {
        //    downloadAllImages();
        } else { // == Constants.ACTION_REFRESH_FEEDS
            LongOper(R.string.RefreshFeeds, new Runnable() {
                @Override
                public void run() {
                    long keepTime = (long) (GetDefaultKeepTime() * 86400000L);
                    long keepDateBorderTime = keepTime > 0 && mDeleteOld ? System.currentTimeMillis() - keepTime : 0;

                    String feedId = intent.getStringExtra(Constants.FEED_ID);
                    String groupId = intent.getStringExtra(Constants.GROUP_ID);

                    mMarkAsStarredFoundList.clear();
                    int newCount;
                    try {
                        newCount = (feedId == null ?
                                refreshFeeds(keepDateBorderTime, groupId, isFromAutoRefresh) :
                                refreshFeed(feedId, keepDateBorderTime, isFromAutoRefresh));

                    } finally {
                        if (mMarkAsStarredFoundList.size() > 5) {
                            ArrayList<String> list = new ArrayList<>();
                            for (MarkItem item : mMarkAsStarredFoundList)
                                list.add(item.mCaption);
                            ShowNotification(TextUtils.join(", ", list),
                                    R.string.markedAsStarred,
                                    new Intent(FetcherService.this, HomeActivity.class),
                                    Constants.NOTIFICATION_ID_MANY_ITEMS_MARKED_STARRED);
                        } else if (mMarkAsStarredFoundList.size() > 0)
                            for (MarkItem item : mMarkAsStarredFoundList) {
                                Uri entryUri = getEntryUri(item.mLink, item.mFeedID);

                                int ID = -1;
                                try {
                                    if (entryUri != null)
                                        ID = Integer.parseInt(entryUri.getLastPathSegment());
                                } catch (Throwable ignored) {

                                }

                                ShowNotification(item.mCaption,
                                        R.string.markedAsStarred,
                                        new Intent(Intent.ACTION_VIEW, entryUri),
                                        ID);
                            }
                    }

                    if (newCount > 0) {
                        if (PrefUtils.getBoolean(PrefUtils.NOTIFICATIONS_ENABLED, true)) {
                            Cursor cursor = getContentResolver().query(EntryColumns.CONTENT_URI, new String[]{Constants.DB_COUNT}, EntryColumns.WHERE_UNREAD, null, null);

                            cursor.moveToFirst();
                            newCount = cursor.getInt(0); // The number has possibly changed
                            cursor.close();

                            if (newCount > 0) {
                                ShowNotification(getResources().getQuantityString(R.plurals.number_of_new_entries, newCount, newCount),
                                        R.string.flym_feeds,
                                        new Intent(FetcherService.this, HomeActivity.class),
                                        Constants.NOTIFICATION_ID_NEW_ITEMS_COUNT);
                            }
                        } else if (Constants.NOTIF_MGR != null) {
                            Constants.NOTIF_MGR.cancel(Constants.NOTIFICATION_ID_NEW_ITEMS_COUNT);
                        }
                    }

                    mobilizeAllEntries(isFromAutoRefresh);
                    downloadAllImages();
                    if ( mDeleteOld )
                        deleteOldEntries(keepDateBorderTime);
                    deleteGhostImages();
                    if ( isFromAutoRefresh && Build.VERSION.SDK_INT >= 21 )
                        PrefUtils.putLong( AutoJobService.LAST_JOB_OCCURED + PrefUtils.REFRESH_INTERVAL, System.currentTimeMillis() );
                }
            } );
        }
    }

    private void deleteGhostImages() {

        int status = Status().Start( getString( R.string.deltingGhostImages ) );
        final Cursor cursor = MainApplication.getContext().getContentResolver().query( EntryColumns.CONTENT_URI, new String[] {EntryColumns.LINK},null, null, null );
        final HashSet<String> mapEntryID = new HashSet<>();
        while  ( cursor.moveToNext() )
            mapEntryID.add( NetworkUtils.getImageEntryCode( cursor.getString( 0 ) ) );
        cursor.close();

        int deletedImageCount = 0;
        String[] fileNames = FileUtils.INSTANCE.GetImagesFolder().list();
        if (fileNames != null  )
            for (String fileName : fileNames) {
                String[] list = TextUtils.split( fileName, "_" );
                if ( list.length != 3 || list.length >= 2 && !mapEntryID.contains( list[0] ) ) {
                    if ( new File( FileUtils.INSTANCE.GetImagesFolder(), fileName ).delete() )
                        deletedImageCount++;
                    Status().ChangeProgress(getString(R.string.deleteImages) + String.format( " %d", deletedImageCount ) );
                    if (FetcherService.isCancelRefresh())
                        break;

                }
            }
        Status().End( status );
    }

    private void LongOper( int textID, Runnable oper ) {
        LongOper( getString( textID ), oper );
    }

    private void LongOper( String title, Runnable oper ) {
        startForeground(Constants.NOTIFICATION_ID_REFRESH_SERVICE, StatusText.GetNotification("", title));
        Status().SetNotificationTitle( title );
        PrefUtils.putBoolean(PrefUtils.IS_REFRESHING, true);
        synchronized (mCancelRefresh) {
            mCancelRefresh = false;
        }
        try {
            oper.run();
        } catch (Exception e) {
            e.printStackTrace();
            //Toast.makeText( this, getString( R.string.error ) + ": " + e.getMessage(), Toast.LENGTH_LONG ).show();
            DebugApp.SendException( e, this );
        } finally {
            Status().SetNotificationTitle( "" );
            PrefUtils.putBoolean(PrefUtils.IS_REFRESHING, false);
            stopForeground(true);
            synchronized (mCancelRefresh) {
                mCancelRefresh = false;
            }
        }
    }

    public static float GetDefaultKeepTime() {
        return Float.parseFloat(PrefUtils.getString(PrefUtils.KEEP_TIME, "4"));
    }

    private static boolean mIsWiFi = false;
    private boolean GetIsWifi() {
        ConnectivityManager cm = (ConnectivityManager) MainApplication.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return (ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI );
    }
    public static boolean isCancelRefresh() {
        synchronized (mCancelRefresh) {
            if ( !mIsWiFi && Status().mBytesRecievedLast > PrefUtils.getMaxSingleRefreshTraffic() * 1024 * 1024 )
                return true;
            //if (mCancelRefresh) {
            //    MainApplication.getContext().getContentResolver().delete( TaskColumns.CONTENT_URI, null, null );
            //}
            return mCancelRefresh;
        }
    }

    private static boolean isEntryIDActive(long id) {
        synchronized (mActiveEntryIDList) {
            return mActiveEntryIDList.contains( id );
        }
    }
    public static void addActiveEntryID( long value ) {
        synchronized (mActiveEntryIDList) {
            if ( !mActiveEntryIDList.contains( value ) )
                mActiveEntryIDList.add( value );
        }
    }
    public static void removeActiveEntryID( long value ) {
        synchronized (mActiveEntryIDList) {
            if ( mActiveEntryIDList.contains( value ) )
                mActiveEntryIDList.remove( value );
        }
    }
    public static void clearActiveEntryID() {
        synchronized (mActiveEntryIDList) {
            mActiveEntryIDList.clear();
        }
    }
    private static boolean isDownloadImageCursorNeedsRequery() {
        synchronized (mIsDownloadImageCursorNeedsRequery) {
            return mIsDownloadImageCursorNeedsRequery;
        }
    }
    public static void setDownloadImageCursorNeedsRequery( boolean value ) {
        synchronized (mIsDownloadImageCursorNeedsRequery) {
            mIsDownloadImageCursorNeedsRequery = value;
        }
    }

    public static void CancelStarNotification( long entryID ) {
        if ( Constants.NOTIF_MGR != null ) {
            Constants.NOTIF_MGR.cancel((int) entryID);
            Constants.NOTIF_MGR.cancel(Constants.NOTIFICATION_ID_MANY_ITEMS_MARKED_STARRED);
            Constants.NOTIF_MGR.cancel(Constants.NOTIFICATION_ID_NEW_ITEMS_COUNT);
        }
    }

    private void mobilizeAllEntries( boolean fromAutoRefresh) {
        int status = Status().Start(getString(R.string.mobilizeAll)); try {
            ContentResolver cr = getContentResolver();
            //Status().ChangeProgress("query DB");
            Cursor cursor = cr.query(TaskColumns.CONTENT_URI, new String[]{TaskColumns._ID, TaskColumns.ENTRY_ID, TaskColumns.NUMBER_ATTEMPT},
                    TaskColumns.IMG_URL_TO_DL + Constants.DB_IS_NULL, null, null);
            Status().ChangeProgress("");
            ArrayList<ContentProviderOperation> operations = new ArrayList<>();

            while (cursor.moveToNext() && !isCancelRefresh()) {
                int status1 = Status().Start(String.format("%d/%d", cursor.getPosition(), cursor.getCount())); try {
                    long taskId = cursor.getLong(0);
                    long entryId = cursor.getLong(1);
                    int nbAttempt = 0;
                    if (!cursor.isNull(2)) {
                        nbAttempt = cursor.getInt(2);
                    }

                    if ( mobilizeEntry(cr, entryId, ArticleTextExtractor.MobilizeType.Yes, IsAutoDownloadImages(fromAutoRefresh, cr, entryId), true, false)) {
                        cr.delete(TaskColumns.CONTENT_URI(taskId), null, null);//operations.add(ContentProviderOperation.newDelete(TaskColumns.CONTENT_URI(taskId)).build());
                    } else {
                        if (nbAttempt + 1 > MAX_TASK_ATTEMPT) {
                            operations.add(ContentProviderOperation.newDelete(TaskColumns.CONTENT_URI(taskId)).build());
                        } else {
                            ContentValues values = new ContentValues();
                            values.put(TaskColumns.NUMBER_ATTEMPT, nbAttempt + 1);
                            operations.add(ContentProviderOperation.newUpdate(TaskColumns.CONTENT_URI(taskId)).withValues(values).build());
                        }
                    }

                } finally {
                    Status().End( status1 );
                }
            }

            cursor.close();

            if (!operations.isEmpty()) {
                Status().ChangeProgress(R.string.applyOperations);
                try {
                    cr.applyBatch(FeedData.AUTHORITY, operations);
                } catch (Throwable ignored) {
                }
            }
        } finally { Status().End( status ); }


    }

    private AutoDownloadEntryImages IsAutoDownloadImages(boolean fromAutoRefresh, ContentResolver cr, long entryId) {
        AutoDownloadEntryImages result = AutoDownloadEntryImages.Yes;
        if ( fromAutoRefresh ) {
            Cursor curEntry = cr.query( EntryColumns.CONTENT_URI( entryId ), new String[] { EntryColumns.FEED_ID }, null, null, null );
            if ( curEntry.moveToFirst() ) {
                Cursor curFeed = cr.query( FeedColumns.CONTENT_URI( curEntry.getInt( 0 ) ), new String[] { FeedColumns.IS_IMAGE_AUTO_LOAD }, null, null, null );
                if ( curFeed.moveToFirst() )
                    result = curFeed.isNull( 0 ) || curFeed.getInt( 0 ) == 1 ? AutoDownloadEntryImages.Yes : AutoDownloadEntryImages.No;
                curFeed.close();
            }
            curEntry.close();
        }
        return result;
    }

    public enum AutoDownloadEntryImages {Yes, No}

    public static boolean mobilizeEntry(final ContentResolver cr,
                                        final long entryId,
                                        final ArticleTextExtractor.MobilizeType mobilize,
                                        final AutoDownloadEntryImages autoDownloadEntryImages,
                                        final boolean isCorrectTitle,
                                        final boolean isShowError ) {
        boolean success = false;

        Uri entryUri = EntryColumns.CONTENT_URI(entryId);
        Cursor entryCursor = cr.query(entryUri, null, null, null, null);

        if (entryCursor.moveToFirst()) {
            int linkPos = entryCursor.getColumnIndex(EntryColumns.LINK);
            final String link = entryCursor.getString(linkPos);
            if ( !FileUtils.INSTANCE.isMobilized(link, entryCursor ) ) { // If we didn't already mobilized it
                int abstractHtmlPos = entryCursor.getColumnIndex(EntryColumns.ABSTRACT);
                int titlePos = entryCursor.getColumnIndex(EntryColumns.TITLE);
                final int feedId = entryCursor.getColumnIndex(EntryColumns.FEED_ID);
                HttpURLConnection connection = null;

                try {

                    // Try to find a text indicator for better content extraction
                    String contentIndicator = null;
                    String text = entryCursor.getString(abstractHtmlPos);
                    if (!TextUtils.isEmpty(text)) {
                        text = Html.fromHtml(text).toString();
                        if (text.length() > 60) {
                            contentIndicator = text.substring(20, 40);
                        }
                    }

                    connection = NetworkUtils.setupConnection(link);

                    String mobilizedHtml;
                    Status().ChangeProgress(R.string.extractContent);

                    if (FetcherService.isCancelRefresh())
                        return false;
                    Document doc = Jsoup.parse(connection.getInputStream(), null, "");

                    String title = entryCursor.getString(titlePos);
                    //if ( entryCursor.isNull( titlePos ) || title == null || title.isEmpty() || title.startsWith("http")  ) {
                    if ( isCorrectTitle ) {
                        Elements titleEls = doc.getElementsByTag("title");
                        if (!titleEls.isEmpty())
                            title = titleEls.first().text();
                    }

                    mobilizedHtml = ArticleTextExtractor.extractContent(doc, link, contentIndicator, mobilize, true);

                    Status().ChangeProgress("");

                    if (mobilizedHtml != null) {
                        Status().ChangeProgress(R.string.improveHtmlContent);
                        mobilizedHtml = HtmlUtils.improveHtmlContent(mobilizedHtml, NetworkUtils.getBaseUrl(link), mobilize);
                        Status().ChangeProgress("");
                        ContentValues values = new ContentValues();
                        FileUtils.INSTANCE.saveMobilizedHTML(link, mobilizedHtml, values);
                        if ( title != null )
                            values.put(EntryColumns.TITLE, title);

                        ArrayList<String> imgUrlsToDownload = null;
                        if (autoDownloadEntryImages == AutoDownloadEntryImages.Yes && NetworkUtils.needDownloadPictures()) {
                            imgUrlsToDownload = HtmlUtils.getImageURLs(mobilizedHtml);
                        }

                        String mainImgUrl;
                        if (imgUrlsToDownload != null) {
                            mainImgUrl = HtmlUtils.getMainImageURL(imgUrlsToDownload);
                        } else {
                            mainImgUrl = HtmlUtils.getMainImageURL(mobilizedHtml);
                        }

                        if (mainImgUrl != null) {
                            values.put(EntryColumns.IMAGE_URL, mainImgUrl);
                        }

                        cr.update( entryUri, values, null, null );//operations.add(ContentProviderOperation.newUpdate(entryUri).withValues(values).build());

                        success = true;
                        if ( imgUrlsToDownload != null && !imgUrlsToDownload.isEmpty() ) {
                            addImagesToDownload(String.valueOf(entryId), imgUrlsToDownload);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    if ( isShowError ) {
                        String title = "";
                        Cursor cursor = cr.query( FeedColumns.CONTENT_URI( feedId ), new String[]{ FeedColumns.NAME }, null, null, null);
                        if ( cursor.moveToFirst() && cursor.isNull( 0 ) )
                            title = cursor.getString( 0 );
                        cursor.close();
                        Status().SetError(title + ": ", String.valueOf( feedId ), String.valueOf( entryId ), e);
                    } else {
                        ContentValues values = new ContentValues();
                        FileUtils.INSTANCE.saveMobilizedHTML( link, e.toString(), values );
                        cr.update( entryUri, values, null, null );
                    }
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            } else { // We already mobilized it
                success = true;
                //operations.add(ContentProviderOperation.newDelete(TaskColumns.CONTENT_URI(taskId)).build());
            }
        }
        entryCursor.close();
        return success;
    }



    public static Intent GetIntent( String extra ) {
        return new Intent(MainApplication.getContext(), FetcherService.class).putExtra( extra, true );
    }
    public static void StartServiceOpenExternalLink( final String url, final String title) {
        FetcherService.StartService( new Intent(MainApplication.getContext(), FetcherService.class)
                .setAction( ACTION_LOAD_LINK )
                .putExtra(Constants.URL_TO_LOAD, url)
                .putExtra(Constants.TITLE_TO_LOAD, url) );
    }

    public enum ForceReload {Yes, No}
//    public static void OpenLink( Uri entryUri ) {
//        PrefUtils.putString(PrefUtils.LAST_ENTRY_URI, entryUri.toString());
//        Intent intent = new Intent(MainApplication.getContext(), HomeActivity.class);
//        intent.setFlags( Intent.FLAG_ACTIVITY_NEW_TASK );
//        MainApplication.getContext().startActivity( intent );
//    }

    public static Uri GetEnryUri( final String url ) {
        Timer timer = new Timer( "GetEnryUri" );
        Uri entryUri = null;
        String url1 = url.replace("https:", "http:");
        String url2 = url.replace("http:", "https:");
        ContentResolver cr = MainApplication.getContext().getContentResolver();
        Cursor cursor = cr.query(EntryColumns.CONTENT_URI,
                new String[]{EntryColumns._ID, EntryColumns.FEED_ID},
                EntryColumns.LINK + "='" + url1 + "'" + Constants.DB_OR + EntryColumns.LINK + "='" + url2 + "'",
                null,
                null);
        try {
            if (cursor.moveToFirst())
                entryUri = Uri.withAppendedPath( EntryColumns.ENTRIES_FOR_FEED_CONTENT_URI( cursor.getString(1) ), cursor.getString(0) );
        } finally {
            cursor.close();
        }
        timer.End();
        return entryUri;
    }
    public static Pair<Uri,Boolean> LoadLink(final String feedID,
                                             final String url,
                                             final String title,
                                             final ForceReload forceReload,
                                             final boolean isCorrectTitle,
                                             final boolean isShowError ) {
        boolean load;
        final ContentResolver cr = MainApplication.getContext().getContentResolver();
        int status = FetcherService.Status().Start(MainApplication.getContext().getString(R.string.loadingLink)); try {
            Uri entryUri = GetEnryUri( url );
            if ( entryUri != null ) {
                load = (forceReload == ForceReload.Yes);
                if (load) {
                    ContentValues values = new ContentValues();
                    values.put(EntryColumns.DATE, (new Date()).getTime());
                    cr.update(entryUri, values, null, null);//operations.add(ContentProviderOperation.newUpdate(entryUri).withValues(values).build());
                }
            } else {

                ContentValues values = new ContentValues();
                values.put(EntryColumns.TITLE, title);
                values.put(EntryColumns.SCROLL_POS, 0);
                //values.put(EntryColumns.ABSTRACT, NULL);
                //values.put(EntryColumns.IMAGE_URL, NULL);
                //values.put(EntryColumns.AUTHOR, NULL);
                //values.put(EntryColumns.ENCLOSURE, NULL);
                values.put(EntryColumns.DATE, (new Date()).getTime());
                values.put(EntryColumns.LINK, url);

                //values.put(EntryColumns.MOBILIZED_HTML, enclosureString);
                //values.put(EntryColumns.ENCLOSURE, enclosureString);
                entryUri = cr.insert(EntryColumns.ENTRIES_FOR_FEED_CONTENT_URI(feedID), values);
                load = true;
            }

            if ( forceReload == ForceReload.Yes )
                FileUtils.INSTANCE.deleteMobilized( entryUri );

            if ( load && !FetcherService.isCancelRefresh() )
                mobilizeEntry(cr, Long.parseLong(entryUri.getLastPathSegment()), ArticleTextExtractor.MobilizeType.Yes, AutoDownloadEntryImages.Yes,  isCorrectTitle, isShowError);
            return new Pair<>(entryUri, load);
        } finally {
            FetcherService.Status().End(status);
        }
        //stopForeground( true );
    }

    private static String mExtrenalLinkFeedID = "";
    public static String GetExtrenalLinkFeedID() {
        //Timer timer = new Timer( "GetExtrenalLinkFeedID()" );
        synchronized ( mExtrenalLinkFeedID ) {
            if (mExtrenalLinkFeedID.isEmpty()) {

                ContentResolver cr = MainApplication.getContext().getContentResolver();
                Cursor cursor = cr.query(FeedColumns.CONTENT_URI,
                        FeedColumns.PROJECTION_ID,
                        FeedColumns.FETCH_MODE + "=" + FetcherService.FETCHMODE_EXERNAL_LINK, null, null);
                if (cursor.moveToFirst())
                    mExtrenalLinkFeedID = cursor.getString(0);
                cursor.close();

                if (mExtrenalLinkFeedID.isEmpty()) {
                    ContentValues values = new ContentValues();
                    values.put(FeedColumns.FETCH_MODE, FetcherService.FETCHMODE_EXERNAL_LINK);
                    values.put(FeedColumns.NAME, MainApplication.getContext().getString(R.string.externalLinks));
                    mExtrenalLinkFeedID = cr.insert(FeedColumns.CONTENT_URI, values).getLastPathSegment();
                }
            }
        }
        //timer.End();
        return mExtrenalLinkFeedID;
    }

    private static void downloadAllImages() {
        StatusText.FetcherObservable obs = Status();
        int status = obs.Start(MainApplication.getContext().getString(R.string.AllImages)); try {

            ContentResolver cr = MainApplication.getContext().getContentResolver();
            Cursor cursor = cr.query(TaskColumns.CONTENT_URI, new String[]{TaskColumns._ID, TaskColumns.ENTRY_ID, TaskColumns.IMG_URL_TO_DL,
                    TaskColumns.NUMBER_ATTEMPT, EntryColumns.LINK}, TaskColumns.IMG_URL_TO_DL + Constants.DB_IS_NOT_NULL, null, null);
            ArrayList<ContentProviderOperation> operations = new ArrayList<>();

            while (cursor.moveToNext() && !isCancelRefresh() && !isDownloadImageCursorNeedsRequery()) {
                int status1 = obs.Start(String.format("%d/%d", cursor.getPosition() + 1, cursor.getCount())); try {
                //int status1 = obs.Start(String.format("%d", cursor.getPosition() + 1, cursor.getCount())); try {
                    long taskId = cursor.getLong(0);
                    long entryId = cursor.getLong(1);
                    String entryLink = cursor.getString(4);
                    String imgPath = cursor.getString(2);
                    int nbAttempt = 0;
                    if (!cursor.isNull(3)) {
                        nbAttempt = cursor.getInt(3);
                    }

                    try {
                        NetworkUtils.downloadImage(entryId, entryLink, imgPath, true);

                        // If we are here, everything is OK
                        operations.add(ContentProviderOperation.newDelete(TaskColumns.CONTENT_URI(taskId)).build());
                    } catch (Exception e) {
                        if (nbAttempt + 1 > MAX_TASK_ATTEMPT) {
                            operations.add(ContentProviderOperation.newDelete(TaskColumns.CONTENT_URI(taskId)).build());
                        } else {
                            ContentValues values = new ContentValues();
                            values.put(TaskColumns.NUMBER_ATTEMPT, nbAttempt + 1);
                            operations.add(ContentProviderOperation.newUpdate(TaskColumns.CONTENT_URI(taskId)).withValues(values).build());
                        }
                    }
                } finally {
                    obs.End( status1 );
                }

            }

            cursor.close();

            if (!operations.isEmpty()) {
                obs.ChangeProgress(R.string.applyOperations);
                try {
                    cr.applyBatch(FeedData.AUTHORITY, operations);
                } catch (Throwable ignored) {

                }
            }
        } finally { obs.End( status ); }

        if ( isDownloadImageCursorNeedsRequery() ) {
            setDownloadImageCursorNeedsRequery( false );
            downloadAllImages();
        }
    }

    public static void downloadEntryImages( long entryId, String entryLink, ArrayList<String> imageList ) {
        StatusText.FetcherObservable obs = Status();
        int status = obs.Start(MainApplication.getContext().getString(R.string.EntryImages)); try {
            for( String imgPath: imageList ) {
                if ( isCancelRefresh() || !isEntryIDActive( entryId ) )
                    break;
                int status1 = obs.Start(String.format("%d/%d", imageList.indexOf(imgPath) + 1, imageList.size()));
                try {
                    NetworkUtils.downloadImage(entryId, entryLink, imgPath, true);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    obs.End(status1);
                }
            }
        } finally { obs.End( status ); }
    }


    private void deleteOldEntries(final long defaultKeepDateBorderTime) {
        if ( !mIsDeletingOld ) {
            int status = Status().Start(MainApplication.getContext().getString(R.string.deleteOldEntries));
            ContentResolver cr = MainApplication.getContext().getContentResolver();
            final Cursor cursor = cr.query(FeedColumns.CONTENT_URI,
                    new String[]{FeedColumns._ID, FeedColumns.OPTIONS},
                    FeedColumns.LAST_UPDATE + Constants.DB_IS_NOT_NULL, null, null);
            try {
                mIsDeletingOld = true;
                while (cursor.moveToNext()) {
                    long keepDateBorderTime = defaultKeepDateBorderTime;
                    final String jsonText = cursor.isNull( 1 ) ? "" : cursor.getString(1);
                    if ( !jsonText.isEmpty() )
                        try {
                            JSONObject jsonOptions = new JSONObject(jsonText);
                            if (jsonOptions.has(CUSTOM_KEEP_TIME))
                                keepDateBorderTime = jsonOptions.getDouble(CUSTOM_KEEP_TIME) == 0 ? 0 : System.currentTimeMillis() - (long) (jsonOptions.getDouble(CUSTOM_KEEP_TIME) * 86400000l);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    final long feedID = cursor.getLong(0);
                    DeleteOldEntries(feedID, keepDateBorderTime);
                }
            } finally {
                Status().End(status);
                cursor.close();
                mIsDeletingOld = false;
            }
        }
    }

    private void DeleteOldEntries(final long feedID, final long keepDateBorderTime) {
        if (keepDateBorderTime > 0 && !isCancelRefresh() ) {
            ContentResolver cr = MainApplication.getContext().getContentResolver();

            String where = EntryColumns.DATE + '<' + keepDateBorderTime + Constants.DB_AND + EntryColumns.WHERE_NOT_FAVORITE;
            // Delete the entries, the cache files will be deleted by the content provider
            cr.delete(EntryColumns.ENTRIES_FOR_FEED_CONTENT_URI(feedID), where, null);
        }
    }

    private int refreshFeeds(final long keepDateBorderTime, String groupID, final boolean isFromAutoRefresh) {

        ContentResolver cr = getContentResolver();
        final Cursor cursor;
        String where = PrefUtils.getBoolean( PrefUtils.REFRESH_ONLY_SELECTED, false ) && isFromAutoRefresh ? FeedColumns.IS_AUTO_REFRESH + Constants.DB_IS_TRUE : null;
        if ( groupID != null )
            cursor = cr.query(FeedColumns.FEEDS_FOR_GROUPS_CONTENT_URI(groupID), FeedColumns.PROJECTION_ID, null, null, null);
        else
            cursor = cr.query(FeedColumns.CONTENT_URI, FeedColumns.PROJECTION_ID, where, null, null);
        int nbFeed = cursor.getCount();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_NUMBER, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        });
        int globalResult = 0;
        CompletionService<Integer> completionService = new ExecutorCompletionService<>(executor);
        while (cursor.moveToNext()) {
            //Status().Start(String.format("%d from %d", cursor.getPosition(), cursor.getCount()));
            final String feedId = cursor.getString(0);
            completionService.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    int result = 0;
                    try {
                        if (!isCancelRefresh())
                            result = refreshFeed(feedId, keepDateBorderTime, isFromAutoRefresh);
                    } catch (Exception ignored) {
                    }
                    return result;
                }
            });
            //Status().End();
        }
        cursor.close();

        for (int i = 0; i < nbFeed; i++) {
            try {
                Future<Integer> f = completionService.take();
                globalResult += f.get();
            } catch (Exception ignored) {
            }
        }

        executor.shutdownNow(); // To purge all threads
        return globalResult;
    }

    private int refreshFeed(String feedId, long keepDateBorderTime, boolean fromAutoRefresh) {


        int newCount = 0;

        if ( GetExtrenalLinkFeedID().equals( feedId ) )
            return 0;

        ContentResolver cr = getContentResolver();
        Cursor cursor = cr.query(FeedColumns.CONTENT_URI(feedId), null, null, null, null);

        if (cursor.moveToFirst()) {
            int urlPosition = cursor.getColumnIndex(FeedColumns.URL);
            int idPosition = cursor.getColumnIndex(FeedColumns._ID);
            int titlePosition = cursor.getColumnIndex(FeedColumns.NAME);
            if ( cursor.isNull( cursor.getColumnIndex(FeedColumns.REAL_LAST_UPDATE) ) ) {
                mDeleteOld = false;
                keepDateBorderTime = 0;
            }
            boolean isRss = true;
            try {
                JSONObject jsonOptions  = new JSONObject( cursor.getString( cursor.getColumnIndex(FeedColumns.OPTIONS) ) );
                isRss = jsonOptions.getBoolean( "isRss" );
            } catch (Exception e) {
                e.printStackTrace();
            }

            //int showTextInEntryList = cursor.getColumnIndex(FeedColumns.SHOW_TEXT_IN_ENTRY_LIST);

            String id = cursor.getString(idPosition);
            String feedUrl = cursor.getString(urlPosition);
            int status = Status().Start(cursor.getString(titlePosition));
            try {

                if ( isRss )
                    newCount = ParseRSSAndAddEntries(feedUrl, cursor, keepDateBorderTime, id, fromAutoRefresh);
                else
                    newCount = HTMLParser.Parse(cursor.getString(idPosition), feedUrl);
            } finally {
                Status().End(status);
            }
        }
        cursor.close();
        return newCount;
    }

    private void ShowNotification(String text, int captionID, Intent intent, int ID){
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder notifBuilder = new NotificationCompat.Builder(MainApplication.getContext()) //
                .setContentIntent(contentIntent) //
                .setSmallIcon(R.mipmap.ic_launcher) //
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher)) //
                //.setTicker(text) //
                .setWhen(System.currentTimeMillis()) //
                .setAutoCancel(true) //
                .setContentTitle(getString(captionID)) //
                .setLights(0xffffffff, 0, 0);
        if (Build.VERSION.SDK_INT >= 26 )
            notifBuilder.setChannelId( NOTIFICATION_CHANNEL_ID );

        if (PrefUtils.getBoolean(PrefUtils.NOTIFICATIONS_VIBRATE, false)) {
            notifBuilder.setVibrate(new long[]{0, 1000});
        }

        String ringtone = PrefUtils.getString(PrefUtils.NOTIFICATIONS_RINGTONE, null);
        if (ringtone != null && ringtone.length() > 0) {
            notifBuilder.setSound(Uri.parse(ringtone));
        }

        if (PrefUtils.getBoolean(PrefUtils.NOTIFICATIONS_LIGHT, false)) {
            notifBuilder.setLights(0xffffffff, 300, 1000);
        }

        Notification nf;
        if (Build.VERSION.SDK_INT < 16)
            nf = notifBuilder.setContentText(text).build();
        else
            nf = new NotificationCompat.BigTextStyle(notifBuilder.setContentText(text)).bigText(text).build();

        if (Constants.NOTIF_MGR != null) {
            Constants.NOTIF_MGR.notify(ID, nf);
        }

    }

    private Uri getEntryUri(String entryLink, String feedID) {
        Uri entryUri = null;
        Cursor cursor = MainApplication.getContext().getContentResolver().query(
                EntryColumns.ENTRIES_FOR_FEED_CONTENT_URI(feedID),
                new String[]{EntryColumns._ID},
                EntryColumns.LINK + "='" + entryLink + "'",
                null,
                null);
        if (cursor.moveToFirst())
            entryUri = EntryColumns.CONTENT_URI(cursor.getLong(0));
        cursor.close();
        return entryUri;
    }


    private static String ToString (InputStream inputStream, Xml.Encoding encoding ) throws
    IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        //InputStream inputStream = connection.getInputStream();

        byte[] byteBuffer = new byte[4096];

        int n;
        while ((n = inputStream.read(byteBuffer)) > 0) {
            FetcherService.Status().AddBytes(n);
            outputStream.write(byteBuffer, 0, n);
        }
        String content = outputStream.toString(encoding.name()).replace(" & ", " &amp; ");
        content = content.replaceAll( "<[a-z]+?:", "<" );
        content = content.replaceAll( "</[a-z]+?:", "</" );
        content = content.replace( "&mdash;", "-" );

        return content;
    }


    private int ParseRSSAndAddEntries(String feedUrl, Cursor cursor, long keepDateBorderTime, String feedId, boolean fromAutoRefresh) {
        RssAtomParser handler = null;

        int fetchModePosition = cursor.getColumnIndex(FeedColumns.FETCH_MODE);
        int realLastUpdatePosition = cursor.getColumnIndex(FeedColumns.REAL_LAST_UPDATE);
        int retrieveFullscreenPosition = cursor.getColumnIndex(FeedColumns.RETRIEVE_FULLTEXT);
        int autoImageDownloadPosition = cursor.getColumnIndex(FeedColumns.IS_IMAGE_AUTO_LOAD);
        int titlePosition = cursor.getColumnIndex(FeedColumns.NAME);
        int urlPosition = cursor.getColumnIndex(FeedColumns.URL);
        int iconPosition = cursor.getColumnIndex(FeedColumns.ICON);

        HttpURLConnection connection = null;
        ContentResolver cr = MainApplication.getContext().getContentResolver();
        try {

            connection = NetworkUtils.setupConnection(feedUrl);
            String contentType = connection.getContentType();
            int fetchMode = cursor.getInt(fetchModePosition);

            boolean autoDownloadImages = cursor.isNull(autoImageDownloadPosition) || cursor.getInt(autoImageDownloadPosition) == 1;

            if (fetchMode == 0) {
                if (contentType != null) {
                    int index = contentType.indexOf(CHARSET);

                    if (index > -1) {
                        int index2 = contentType.indexOf(';', index);

                        try {
                            Xml.findEncodingByName(index2 > -1 ? contentType.substring(index + 8, index2) : contentType.substring(index + 8));
                            fetchMode = FETCHMODE_DIRECT;
                        } catch (UnsupportedEncodingException ignored) {
                            fetchMode = FETCHMODE_REENCODE;
                        }
                    } else {
                        fetchMode = FETCHMODE_REENCODE;
                    }

                } else {
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

                    char[] chars = new char[20];

                    int length = bufferedReader.read(chars);

                    FetcherService.Status().AddBytes(length);

                    String xmlDescription = new String(chars, 0, length);

                    connection.disconnect();
                    connection = NetworkUtils.setupConnection(connection.getURL());

                    int start = xmlDescription.indexOf(ENCODING);

                    if (start > -1) {
                        try {
                            Xml.findEncodingByName(xmlDescription.substring(start + 10, xmlDescription.indexOf('"', start + 11)));
                            fetchMode = FETCHMODE_DIRECT;
                        } catch (UnsupportedEncodingException ignored) {
                            fetchMode = FETCHMODE_REENCODE;
                        }
                    } else {
                        // absolutely no encoding information found
                        fetchMode = FETCHMODE_DIRECT;
                    }
                }

                ContentValues values = new ContentValues();
                values.put(FeedColumns.FETCH_MODE, fetchMode);
                cr.update(FeedColumns.CONTENT_URI(feedId), values, null, null);
            }

            handler = new RssAtomParser(new Date(cursor.getLong(realLastUpdatePosition)),
                    keepDateBorderTime,
                    feedId,
                    cursor.getString(titlePosition),
                    feedUrl,
                    cursor.getInt(retrieveFullscreenPosition) == 1);
            handler.setFetchImages(NetworkUtils.needDownloadPictures() && !(fromAutoRefresh && !autoDownloadImages));

            InputStream inputStream = connection.getInputStream();

            switch (fetchMode) {
                default:
                case FETCHMODE_DIRECT: {
                    if (contentType != null) {
                        int index = contentType.indexOf(CHARSET);

                        int index2 = contentType.indexOf(';', index);

                        parseXml(//cursor.getString(urlPosition),
                                inputStream,
                                Xml.findEncodingByName(index2 > -1 ? contentType.substring(index + 8, index2) : contentType.substring(index + 8)),
                                handler);

                    } else {
                        InputStreamReader reader = new InputStreamReader(connection.getInputStream());
                        parseXml(reader, handler);

                    }
                    break;
                }
                case FETCHMODE_REENCODE: {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

                    byte[] byteBuffer = new byte[4096];

                    int n;
                    while ((n = inputStream.read(byteBuffer)) > 0) {
                        FetcherService.Status().AddBytes(n);
                        outputStream.write(byteBuffer, 0, n);
                    }

                    String xmlText = outputStream.toString();

                    int start = xmlText != null ? xmlText.indexOf(ENCODING) : -1;

                    if (start > -1) {
                        parseXml( new StringReader(new String(outputStream.toByteArray(),
                                                   xmlText.substring(start + 10,
                                                                     xmlText.indexOf('"', start + 11)))),
                                  handler );
                    } else {
                        // use content type
                        if (contentType != null) {
                            int index = contentType.indexOf(CHARSET);

                            if (index > -1) {
                                int index2 = contentType.indexOf(';', index);

                                try {
                                    StringReader reader = new StringReader(new String(outputStream.toByteArray(), index2 > -1 ? contentType.substring(
                                            index + 8, index2) : contentType.substring(index + 8)));
                                    parseXml(reader, handler);
                                } catch (Exception ignored) {
                                }
                            } else {
                                StringReader reader = new StringReader(new String(outputStream.toByteArray()));
                                parseXml(reader, handler);
                            }
                        }
                    }
                    break;
                }
            }


            connection.disconnect();
        } catch(FileNotFoundException e){
            if (handler == null || (!handler.isDone() && !handler.isCancelled())) {
                ContentValues values = new ContentValues();

                // resets the fetch mode to determine it again later
                values.put(FeedColumns.FETCH_MODE, 0);

                values.put(FeedColumns.ERROR, getString(R.string.error_feed_error));
                cr.update(FeedColumns.CONTENT_URI(feedId), values, null, null);
                FetcherService.Status().SetError( cursor.getString(titlePosition) + ": " + getString(R.string.error_feed_error), feedId, "", e);
            }
        } catch(Exception e){
            if (handler == null || (!handler.isDone() && !handler.isCancelled())) {
                ContentValues values = new ContentValues();

                // resets the fetch mode to determine it again later
                values.put(FeedColumns.FETCH_MODE, 0);

                values.put(FeedColumns.ERROR, e.getMessage() != null ? e.getMessage() : getString(R.string.error_feed_process));
                cr.update(FeedColumns.CONTENT_URI(feedId), values, null, null);

                FetcherService.Status().SetError(cursor.getString(titlePosition) + ": " + e.toString(),
                        feedId, "", e);
            }
        } finally{
            /* check and optionally find favicon */
            try {
                if (handler != null && cursor.getBlob(iconPosition) == null) {
                    if (handler.getFeedLink() != null)
                        NetworkUtils.retrieveFavicon(this, new URL(handler.getFeedLink()), feedId);
                    else
                        NetworkUtils.retrieveFavicon(this, connection.getURL(), feedId);
                }
            } catch (Throwable ignored) {
            }

            if (connection != null) {
                connection.disconnect();
            }
        }
        return handler != null ? handler.getNewCount() : 0;
    }

    private static void parseXml ( InputStream in, Xml.Encoding
        encoding,
                ContentHandler contentHandler) throws IOException, SAXException {
            Status().ChangeProgress(R.string.parseXml);
            Xml.parse(ToString(in, encoding), contentHandler);
            Status().ChangeProgress("");
            Status().AddBytes(contentHandler.toString().length());
        }

        private static void parseXml (Reader reader,
                ContentHandler contentHandler) throws IOException, SAXException {
            Status().ChangeProgress(R.string.parseXml);
            Xml.parse(reader, contentHandler);
            Status().ChangeProgress("");
            Status().AddBytes(contentHandler.toString().length());
        }

        public static void cancelRefresh () {
            synchronized (mCancelRefresh) {
                MainApplication.getContext().getContentResolver().delete( TaskColumns.CONTENT_URI, null, null );
                mCancelRefresh = true;
            }
        }

        public static void deleteAllFeedEntries ( Uri uri ){
            int status = Status().Start("deleteAllFeedEntries");
            try {
                ContentResolver cr = MainApplication.getContext().getContentResolver();
                cr.delete(uri, EntryColumns.WHERE_NOT_FAVORITE, null);
                if ( FeedDataContentProvider.URI_MATCHER.match(uri) == FeedDataContentProvider.URI_ENTRIES_FOR_FEED ) {
                    String feedID = uri.getPathSegments().get( 1 );
                    ContentValues values = new ContentValues();
                    values.putNull(FeedColumns.LAST_UPDATE);
                    values.putNull(FeedColumns.REAL_LAST_UPDATE);
                    cr.update(FeedColumns.CONTENT_URI(feedID), values, null, null);
                }
            } finally {
                Status().End(status);
            }

        }

        public static void createTestData () {
            int status = Status().Start("createTestData");
            try {
                {
                    final String testFeedID = "10000";
                    final String testAbstract1 = "safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd ";
                    String testAbstract = "";
                    for (int i = 0; i < 10; i++)
                        testAbstract += testAbstract1;
                    //final String testAbstract2 = "sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs fffffffffffffff fffffffffffffff fffffffffffffff fffffffffffffff fffffffffffffff fffffffffffffff fffffffffffffff fffffffffffffff";

                    deleteAllFeedEntries(EntryColumns.ENTRIES_FOR_FEED_CONTENT_URI( testFeedID) );

                    ContentResolver cr = MainApplication.getContext().getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(FeedColumns._ID, testFeedID);
                    values.put(FeedColumns.NAME, "testFeed");
                    values.putNull(FeedColumns.IS_GROUP);
                    //values.putNull(FeedColumns.GROUP_ID);
                    values.putNull(FeedColumns.LAST_UPDATE);
                    values.put(FeedColumns.FETCH_MODE, 0);
                    cr.insert(FeedColumns.CONTENT_URI, values);

                    for (int i = 0; i < 30; i++) {
                        values.clear();
                        values.put(EntryColumns._ID, i);
                        values.put(EntryColumns.ABSTRACT, testAbstract);
                        values.put(EntryColumns.TITLE, "testTitle" + i);
                        cr.insert(EntryColumns.ENTRIES_FOR_FEED_CONTENT_URI(testFeedID), values);
                    }
                }

                {
                    // small
                    final String testFeedID = "10001";
                    final String testAbstract1 = "safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd safdkhfgsadjkhgfsakdhgfasdhkgf sadfdasfdsafasdfasd ";
                    String testAbstract = "";
                    for (int i = 0; i < 1; i++)
                        testAbstract += testAbstract1;
                    //final String testAbstract2 = "sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs sfdsdafsdafs fffffffffffffff fffffffffffffff fffffffffffffff fffffffffffffff fffffffffffffff fffffffffffffff fffffffffffffff fffffffffffffff";

                    deleteAllFeedEntries(EntryColumns.ENTRIES_FOR_FEED_CONTENT_URI( testFeedID) );

                    ContentResolver cr = MainApplication.getContext().getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(FeedColumns._ID, testFeedID);
                    values.put(FeedColumns.NAME, "testFeedSmall");
                    values.putNull(FeedColumns.IS_GROUP);
                    //values.putNull(FeedColumns.GROUP_ID);
                    values.putNull(FeedColumns.LAST_UPDATE);
                    values.put(FeedColumns.FETCH_MODE, 0);
                    cr.insert(FeedColumns.CONTENT_URI, values);

                    for (int i = 0; i < 30; i++) {
                        values.clear();
                        values.put(EntryColumns._ID, 100 + i);
                        values.put(EntryColumns.ABSTRACT, testAbstract);
                        values.put(EntryColumns.TITLE, "testTitleSmall" + i);
                        cr.insert(EntryColumns.ENTRIES_FOR_FEED_CONTENT_URI(testFeedID), values);
                    }
                }
            } finally {
                Status().End(status);
            }

        }

    public static void StartService(Intent intent) {
        final Context context = MainApplication.getContext();

        final boolean isFromAutoRefresh = intent.getBooleanExtra(Constants.FROM_AUTO_REFRESH, false);
        //boolean isOpenActivity = intent.getBooleanExtra(Constants.OPEN_ACTIVITY, false);

        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        // Connectivity issue, we quit
        if (networkInfo == null || networkInfo.getState() != NetworkInfo.State.CONNECTED) {
            if (ACTION_REFRESH_FEEDS.equals(intent.getAction()) && !isFromAutoRefresh) {
                // Display a toast in that case
                UiUtils.RunOnGuiThread( new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(context, R.string.network_error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
            return;
        }

        boolean skipFetch = isFromAutoRefresh && PrefUtils.getBoolean(PrefUtils.REFRESH_WIFI_ONLY, false)
                && networkInfo.getType() != ConnectivityManager.TYPE_WIFI;
        // We need to skip the fetching process, so we quit
        if (skipFetch)
            return;

        if (isFromAutoRefresh && Build.VERSION.SDK_INT < 26 && isBatteryLow())
            return;

        final boolean foreground = !ACTION_MOBILIZE_FEEDS.equals(intent.getAction());
        if (Build.VERSION.SDK_INT >= 26 && foreground)
            context.startForegroundService(intent);
        else
            context.startService( intent );
    }

    static Intent GetStartIntent() {
        return new Intent(MainApplication.getContext(), FetcherService.class)
                .setAction( FetcherService.ACTION_REFRESH_FEEDS );
    }


}
