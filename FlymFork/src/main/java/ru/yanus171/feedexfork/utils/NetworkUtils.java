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

package ru.yanus171.feedexfork.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.text.Html;

import okhttp3.OkHttpClient;
import okhttp3.OkUrlFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Pattern;

import ru.yanus171.feedexfork.Constants;
import ru.yanus171.feedexfork.MainApplication;
import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.provider.FeedData;
import ru.yanus171.feedexfork.service.FetcherService;
import ru.yanus171.feedexfork.view.EntryView;

public class NetworkUtils {

    //public static final File IMAGE_FOLDER_FILE = new File(MainApplication.getContext().getCacheDir(), "images/");
    //public static final String IMAGE_FOLDER = IMAGE_FOLDER_FILE.getAbsolutePath() + '/';
    private static final String TEMP_PREFIX = "TEMP__";
    //public static final File FOLDER = new File(Environment.getExternalStorageDirectory(), "feedex/");
    private static final String ID_SEPARATOR = "__";

    private static final String FILE_FAVICON = "/favicon.ico";
    private static final String PROTOCOL_SEPARATOR = "://";

    private static final CookieManager COOKIE_MANAGER = new CookieManager() {{
        CookieHandler.setDefault(this);
    }};

    public static String getDownloadedOrDistantImageUrl(long entryId, String imgUrl) {
        File dlImgFile = new File(NetworkUtils.getDownloadedImagePath(entryId, imgUrl));
        if (dlImgFile.exists()) {
            return Uri.fromFile(dlImgFile).toString();
        } else {
            return null;//imgUrl;
        }
    }

    public static String getDownloadedImagePath(long entryId, String imgUrl) {
        return getDownloadedImagePath(entryId, "", imgUrl);
    }
    private static String getDownloadedImagePath(long entryId, String prefix, String imgUrl) {
        final String lastSegment = imgUrl.contains( "/" ) ? imgUrl.substring(imgUrl.lastIndexOf("/")) : imgUrl;
        String fileExtension = lastSegment.contains(".") ? lastSegment.substring(lastSegment.lastIndexOf(".")) : "";
        if ( fileExtension.contains( "?" ) )
            fileExtension = fileExtension.replace( fileExtension.substring(fileExtension.lastIndexOf("?") + 1), "" );

        return FileUtils.GetImagesFolder().getAbsolutePath() + "/" + prefix + entryId + ID_SEPARATOR +
               StringUtils.getMd5(imgUrl
                       .replace(" ", HtmlUtils.URL_SPACE) ) + fileExtension.replace("?", "");
    }

    private static String getTempDownloadedImagePath(long entryId, String imgUrl) {
        return getDownloadedImagePath(entryId, TEMP_PREFIX, imgUrl);
        //return FileUtils.GetImagesFolder().getAbsolutePath() + "/" + TEMP_PREFIX + entryId + ID_SEPARATOR + StringUtils.getMd5(imgUrl);
    }

    public static void downloadImage(final long entryId, String imgUrl, boolean isSizeLimit ) throws IOException {
        if ( FetcherService.isCancelRefresh() )
            return;
        String tempImgPath = getTempDownloadedImagePath(entryId, imgUrl);
        String finalImgPath = getDownloadedImagePath(entryId, imgUrl);


        if (!new File(tempImgPath).exists() && !new File(finalImgPath).exists()) {
            boolean abort = false;
            boolean success = false;
            HttpURLConnection imgURLConnection = null;
            try {
                //IMAGE_FOLDER_FILE.mkdir(); // create images dir

                // Compute the real URL (without "&eacute;", ...)
                String realUrl = Html.fromHtml(imgUrl).toString();
                imgURLConnection = setupConnection(realUrl);

                int size = imgURLConnection.getContentLength();
                int maxImageDownloadSize = PrefUtils.getImageMaxDownloadSizeInKb() * 1024;
                if ( !isSizeLimit || size <= maxImageDownloadSize ) {

                    FileOutputStream fileOutput = new FileOutputStream(tempImgPath); try {
                        InputStream inputStream = imgURLConnection.getInputStream(); try {

                            int bytesRecieved = 0;
                            int progressBytes = 0;
                            final int cStep = 1024 * 10;
                            byte[] buffer = new byte[2048];
                            int bufferLength;
                            FetcherService.Status().ChangeProgress(getProgressText(bytesRecieved));
                            while (!FetcherService.isCancelRefresh() && ( bufferLength = inputStream.read(buffer) ) > 0) {
                                if (isSizeLimit && size > maxImageDownloadSize) {
                                    abort = true;
                                    break;
                                }
                                fileOutput.write(buffer, 0, bufferLength);
                                bytesRecieved += bufferLength;
                                progressBytes += bufferLength;
                                if (progressBytes >= cStep) {
                                    progressBytes = 0;
                                    FetcherService.Status().ChangeProgress(getProgressText(bytesRecieved));
                                }
                            }
                            success = true;
                            FetcherService.Status().AddBytes(bytesRecieved);

                        } finally {
                            inputStream.close();
                        }
                    } finally {
                        fileOutput.flush();
                        fileOutput.close();
                    }

                    if ( !abort )
                        new File(tempImgPath).renameTo(new File(finalImgPath));
                    else
                        new File(tempImgPath).delete();
                }
            } catch (IOException e) {
                new File(tempImgPath).delete();
                throw e;
            } finally {
                if (imgURLConnection != null) {
                    imgURLConnection.disconnect();
                }
            }

            if ( success && !abort )
                EntryView.NotifyToUpdate( entryId );
        }
        //if ( updateGUI )
    }

    private static String getProgressText(int bytesRecieved) {
        return String.format("%d KB ...", bytesRecieved / 1024);
    }

    public static synchronized void deleteEntriesImagesCache(Uri entriesUri, String selection, String[] selectionArgs) {
        if (FileUtils.GetImagesFolder().exists()) {
            Context context = MainApplication.getContext();
            PictureFilenameFilter filenameFilter = new PictureFilenameFilter();

            Cursor cursor = MainApplication.getContext().getContentResolver().query(entriesUri, FeedData.EntryColumns.PROJECTION_ID, selection, selectionArgs, null);

            while (cursor.moveToNext() && !FetcherService.isCancelRefresh()) {
                filenameFilter.setEntryId(cursor.getString(0));

                File[] files = FileUtils.GetImagesFolder().listFiles(filenameFilter);
                if (files != null) {
                    if ( files.length > 0 )
                        FetcherService.Status().ChangeProgress(context.getString(R.string.deleteImages) + String.format( " %d", files.length ) );
                    for (File file : files) {
                        file.delete();
                        if ( FetcherService.isCancelRefresh() )
                            break;
                    }
                }
            }
            cursor.close();
        }
    }

    public static boolean needDownloadPictures() {
        String fetchPictureMode = PrefUtils.getString(PrefUtils.PRELOAD_IMAGE_MODE, Constants.FETCH_PICTURE_MODE_ALWAYS_PRELOAD);

        boolean downloadPictures = false;
        if (PrefUtils.getBoolean(PrefUtils.DISPLAY_IMAGES, true)) {
            if (Constants.FETCH_PICTURE_MODE_ALWAYS_PRELOAD.equals(fetchPictureMode)) {
                downloadPictures = true;
            } else if (Constants.FETCH_PICTURE_MODE_WIFI_ONLY_PRELOAD.equals(fetchPictureMode)) {
                ConnectivityManager cm = (ConnectivityManager) MainApplication.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI ) {
                    downloadPictures = true;
                }
            }
        }
        return downloadPictures;
    }

    public static String getBaseUrl(String link) {
        String baseUrl = link;
        int index = link.lastIndexOf('/'); // this also covers https://
        if (index > -1) {
            baseUrl = link.substring(0, index + 1);
        }

        return baseUrl;
    }

    public static String getUrlDomain(String link) {
        String result = link;
        result = result.replaceAll( "http.+?//", "" );
        result = result.replaceAll( "http.+?/", "" );
        if ( result.endsWith( "/" ) )
            result = result.substring(0, result.length() );
        int index = result.lastIndexOf('/'); // this also covers https://
        if (index > -1) {
            result = result.substring(0, index + 1);
        }
        result = result.replace( "www.", "" );
        return result;
    }
    public static byte[] getBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        byte[] buffer = new byte[4096];

        int n;
        while ((n = inputStream.read(buffer)) > 0) {
            FetcherService.Status().AddBytes( n );
            output.write(buffer, 0, n);
        }

        byte[] result = output.toByteArray();

        output.close();
        inputStream.close();
        return result;
    }

    public static void retrieveFavicon(Context context, URL url, String id) {
        boolean success = false;

        ContentResolver cr = context.getContentResolver();
        Cursor cursor  = cr.query(FeedData.FeedColumns.CONTENT_URI( id ), new String[] {FeedData.FeedColumns.ICON}, null, null, null  ); try {
            if (!cursor.moveToFirst() || cursor.getBlob(cursor.getColumnIndex( FeedData.FeedColumns.ICON )) != null )
                return;
        } finally {
            cursor.close();
        }
        HttpURLConnection iconURLConnection = null;

        try {
            iconURLConnection = setupConnection(new URL(url.getProtocol() + PROTOCOL_SEPARATOR + url.getHost() + FILE_FAVICON));

            byte[] iconBytes = getBytes(iconURLConnection.getInputStream());
            if (iconBytes != null && iconBytes.length > 0) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.length);
                if (bitmap != null) {
                    if (bitmap.getWidth() != 0 && bitmap.getHeight() != 0) {
                        ContentValues values = new ContentValues();
                        values.put(FeedData.FeedColumns.ICON, iconBytes);
                        context.getContentResolver().update(FeedData.FeedColumns.CONTENT_URI(id), values, null, null);
                        success = true;
                    }
                    bitmap.recycle();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (iconURLConnection != null) {
                iconURLConnection.disconnect();
            }
        }

        if (!success) {
            // no icon found or error
            ContentValues values = new ContentValues();
            values.putNull(FeedData.FeedColumns.ICON);
            context.getContentResolver().update(FeedData.FeedColumns.CONTENT_URI(id), values, null, null);
        }
    }

    public static HttpURLConnection setupConnection(String url) throws IOException {
        return setupConnection(new URL(url));
    }

    public static HttpURLConnection setupConnection(URL url) throws IOException {
        HttpURLConnection connection;
        FetcherService.Status().ChangeProgress(R.string.setupConnection);

        connection = new OkUrlFactory(new OkHttpClient()).open(url);

        connection.setDoInput(true);
        connection.setDoOutput(false);
        connection.setRequestProperty("User-agent", "Mozilla/5.0 (compatible) AppleWebKit Chrome Safari"); // some feeds need this to work properly
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("accept", "*/*");

        COOKIE_MANAGER.getCookieStore().removeAll(); // Cookie is important for some sites, but we clean them each times
        connection.connect();
        FetcherService.Status().ChangeProgress("");
        return connection;
    }

    static public String ToString( InputStream inputStream ) throws IOException {
        int ch;
        StringBuilder sb = new StringBuilder();
        while((ch = inputStream.read()) != -1)
                sb.append((char)ch);
        if ( inputStream.markSupported() )
            inputStream.reset();
        return sb.toString();
    }

    private static class PictureFilenameFilter implements FilenameFilter {
        private static final String REGEX = "__.*";

        private Pattern mPattern;

        public PictureFilenameFilter(String entryId) {
            setEntryId(entryId);
        }

        PictureFilenameFilter() {
        }

        void setEntryId(String entryId) {
            mPattern = Pattern.compile(entryId + REGEX);
        }

        @Override
        public boolean accept(File dir, String filename) {
            return mPattern.matcher(filename).find();
        }
    }
}
