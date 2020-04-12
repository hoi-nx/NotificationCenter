/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package com.example.custom;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

public class MediaController {

    private static final String[] projectionPhotos = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.ORIENTATION
    };

    private static final String[] projectionVideo = {
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DURATION
    };
    public static class AlbumEntry {
        public int bucketId;
        public boolean videoOnly;
        public String bucketName;
        public PhotoEntry coverPhoto;
        public ArrayList<PhotoEntry> photos = new ArrayList<>();
        public SparseArray<PhotoEntry> photosByIds = new SparseArray<>();

        public AlbumEntry(int bucketId, String bucketName, PhotoEntry coverPhoto) {
            this.bucketId = bucketId;
            this.bucketName = bucketName;
            this.coverPhoto = coverPhoto;
        }

        public void addPhoto(PhotoEntry photoEntry) {
            photos.add(photoEntry);
            photosByIds.put(photoEntry.imageId, photoEntry);
        }
    }

    public class DispatchQueue extends Thread {

        private volatile Handler handler = null;
        private CountDownLatch syncLatch = new CountDownLatch(1);

        public DispatchQueue(final String threadName) {
            setName(threadName);
            start();
        }

        public void sendMessage(Message msg, int delay) {
            try {
                syncLatch.await();
                if (delay <= 0) {
                    handler.sendMessage(msg);
                } else {
                    handler.sendMessageDelayed(msg, delay);
                }
            } catch (Exception e) {
            }
        }

        public void cancelRunnable(Runnable runnable) {
            try {
                syncLatch.await();
                handler.removeCallbacks(runnable);
            } catch (Exception e) {

            }
        }

        public void postRunnable(Runnable runnable) {
            postRunnable(runnable, 0);
        }

        public void postRunnable(Runnable runnable, long delay) {
            try {
                syncLatch.await();
            } catch (Exception e) {

            }
            if (delay <= 0) {
                handler.post(runnable);
            } else {
                handler.postDelayed(runnable, delay);
            }
        }

        public void cleanupQueue() {
            try {
                syncLatch.await();
                handler.removeCallbacksAndMessages(null);
            } catch (Exception e) {

            }
        }

        public void handleMessage(Message inputMessage) {

        }

        public void recycle() {
            handler.getLooper().quit();
        }

        @Override
        public void run() {
            Looper.prepare();
            handler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    DispatchQueue.this.handleMessage(msg);
                }
            };
            syncLatch.countDown();
            Looper.loop();
        }
    }

    public static class PhotoEntry {
        public int bucketId;
        public int imageId;
        public long dateTaken;
        public int duration;
        public String path;
        public int orientation;
        public String thumbPath;
        public String imagePath;
        public boolean isVideo;
        public CharSequence caption;
        public boolean isFiltered;
        public boolean isPainted;
        public boolean isCropped;
        public boolean isMuted;
        public int ttl;
        public boolean canDeleteAfter;

        public PhotoEntry(int bucketId, int imageId, long dateTaken, String path, int orientation, boolean isVideo) {
            this.bucketId = bucketId;
            this.imageId = imageId;
            this.dateTaken = dateTaken;
            this.path = path;
            if (isVideo) {
                this.duration = orientation;
            } else {
                this.orientation = orientation;
            }
            this.isVideo = isVideo;
        }

        public void reset() {
            isFiltered = false;
            isPainted = false;
            isCropped = false;
            ttl = 0;
            imagePath = null;
            if (!isVideo) {
                thumbPath = null;
            }
        }
    }
    private final Object videoConvertSync = new Object();

    private boolean cancelCurrentVideoConversion = false;




    private static Runnable refreshGalleryRunnable;
    public static AlbumEntry allMediaAlbumEntry;
    public static AlbumEntry allPhotosAlbumEntry;
    public static AlbumEntry allVideosAlbumEntry;
    public static ArrayList<AlbumEntry> allMediaAlbums = new ArrayList<>();
    public static ArrayList<AlbumEntry> allPhotoAlbums = new ArrayList<>();
    private static Runnable broadcastPhotosRunnable;

    public volatile DispatchQueue globalQueue = new DispatchQueue("globalQueue");

    public void checkGallery() {
        if (Build.VERSION.SDK_INT < 24 || allPhotosAlbumEntry == null) {
            return;
        }
        final int prevSize = allPhotosAlbumEntry.photos.size();
        globalQueue.postRunnable(() -> {
            int count = 0;
            Cursor cursor = null;
            try {
                if (ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{"COUNT(_id)"}, null, null, null);
                    if (cursor != null) {
                        if (cursor.moveToNext()) {
                            count += cursor.getInt(0);
                        }
                    }
                }
            } catch (Throwable e) {

            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            try {
                if (ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI, new String[]{"COUNT(_id)"}, null, null, null);
                    if (cursor != null) {
                        if (cursor.moveToNext()) {
                            count += cursor.getInt(0);
                        }
                    }
                }
            } catch (Throwable e) {

            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            if (prevSize != count) {
                if (refreshGalleryRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(refreshGalleryRunnable);
                    refreshGalleryRunnable = null;
                }
                loadGalleryPhotosAlbums(0);
            }
        }, 2000);
    }

    private String[] mediaProjections;

    private static volatile MediaController Instance;

    public static MediaController getInstance() {
        MediaController localInstance = Instance;
        if (localInstance == null) {
            synchronized (MediaController.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new MediaController();
                }
            }
        }
        return localInstance;
    }

    private class GalleryObserverExternal extends ContentObserver {
        public GalleryObserverExternal() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            if (refreshGalleryRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(refreshGalleryRunnable);
            }
            AndroidUtilities.runOnUIThread(refreshGalleryRunnable = () -> {
                refreshGalleryRunnable = null;
                loadGalleryPhotosAlbums(0);
            }, 2000);
        }
    }
    private class GalleryObserverInternal extends ContentObserver {
        public GalleryObserverInternal() {
            super(null);
        }

        private void scheduleReloadRunnable() {
            AndroidUtilities.runOnUIThread(refreshGalleryRunnable = () -> {
//                if (PhotoViewer.getInstance().isVisible()) {
//                    scheduleReloadRunnable();
//                    return;
//                }
                refreshGalleryRunnable = null;
                loadGalleryPhotosAlbums(0);
            }, 2000);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            if (refreshGalleryRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(refreshGalleryRunnable);
            }
            scheduleReloadRunnable();
        }
    }

    public MediaController() {

        mediaProjections = new String[]{
                MediaStore.Images.ImageColumns.DATA,
                MediaStore.Images.ImageColumns.DISPLAY_NAME,
                MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
                Build.VERSION.SDK_INT > 28 ? MediaStore.Images.ImageColumns.DATE_MODIFIED : MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.Images.ImageColumns.TITLE,
                MediaStore.Images.ImageColumns.WIDTH,
                MediaStore.Images.ImageColumns.HEIGHT
        };

        ContentResolver contentResolver = ApplicationLoader.applicationContext.getContentResolver();
        try {
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, new GalleryObserverExternal());
        } catch (Exception e) {

        }
        try {
            contentResolver.registerContentObserver(MediaStore.Images.Media.INTERNAL_CONTENT_URI, true, new GalleryObserverInternal());
        } catch (Exception e) {

        }
        try {
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, new GalleryObserverExternal());
        } catch (Exception e) {

        }
        try {
            contentResolver.registerContentObserver(MediaStore.Video.Media.INTERNAL_CONTENT_URI, true, new GalleryObserverInternal());
        } catch (Exception e) {

        }
    }

    public static String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            } catch (Exception e) {
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    public static void loadGalleryPhotosAlbums(final int guid) {
        Thread thread = new Thread(() -> {
            final ArrayList<AlbumEntry> mediaAlbumsSorted = new ArrayList<>();
            final ArrayList<AlbumEntry> photoAlbumsSorted = new ArrayList<>();
            SparseArray<AlbumEntry> mediaAlbums = new SparseArray<>();
            SparseArray<AlbumEntry> photoAlbums = new SparseArray<>();
            AlbumEntry allPhotosAlbum = null;
            AlbumEntry allVideosAlbum = null;
            AlbumEntry allMediaAlbum = null;
            String cameraFolder = null;
            try {
                cameraFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath() + "/" + "Camera/";
            } catch (Exception e) {
            }
            Integer mediaCameraAlbumId = null;
            Integer photoCameraAlbumId = null;

            Cursor cursor = null;
            try {
                if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 23 && ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projectionPhotos, null, null, Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Images.Media.DATE_TAKEN + " DESC");
                    if (cursor != null) {
                        int imageIdColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID);
                        int bucketIdColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
                        int bucketNameColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                        int dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                        int dateColumn = cursor.getColumnIndex(Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Images.Media.DATE_TAKEN);
                        int orientationColumn = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION);

                        while (cursor.moveToNext()) {
                            int imageId = cursor.getInt(imageIdColumn);
                            int bucketId = cursor.getInt(bucketIdColumn);
                            String bucketName = cursor.getString(bucketNameColumn);
                            String path = cursor.getString(dataColumn);
                            long dateTaken = cursor.getLong(dateColumn);
                            int orientation = cursor.getInt(orientationColumn);

                            if (path == null || path.length() == 0) {
                                continue;
                            }

                            PhotoEntry photoEntry = new PhotoEntry(bucketId, imageId, dateTaken, path, orientation, false);

                            if (allPhotosAlbum == null) {
                                allPhotosAlbum = new AlbumEntry(0, ApplicationLoader.applicationContext.getString(R.string.AllPhotos), photoEntry);
                                photoAlbumsSorted.add(0, allPhotosAlbum);
                            }
                            if (allMediaAlbum == null) {
                                allMediaAlbum = new AlbumEntry(0,ApplicationLoader.applicationContext.getString(R.string.AllMedia), photoEntry);
                                mediaAlbumsSorted.add(0, allMediaAlbum);
                            }
                            allPhotosAlbum.addPhoto(photoEntry);
                            allMediaAlbum.addPhoto(photoEntry);

                            AlbumEntry albumEntry = mediaAlbums.get(bucketId);
                            if (albumEntry == null) {
                                albumEntry = new AlbumEntry(bucketId, bucketName, photoEntry);
                                mediaAlbums.put(bucketId, albumEntry);
                                if (mediaCameraAlbumId == null && cameraFolder != null && path != null && path.startsWith(cameraFolder)) {
                                    mediaAlbumsSorted.add(0, albumEntry);
                                    mediaCameraAlbumId = bucketId;
                                } else {
                                    mediaAlbumsSorted.add(albumEntry);
                                }
                            }
                            albumEntry.addPhoto(photoEntry);

                            albumEntry = photoAlbums.get(bucketId);
                            if (albumEntry == null) {
                                albumEntry = new AlbumEntry(bucketId, bucketName, photoEntry);
                                photoAlbums.put(bucketId, albumEntry);
                                if (photoCameraAlbumId == null && cameraFolder != null && path != null && path.startsWith(cameraFolder)) {
                                    photoAlbumsSorted.add(0, albumEntry);
                                    photoCameraAlbumId = bucketId;
                                } else {
                                    photoAlbumsSorted.add(albumEntry);
                                }
                            }
                            albumEntry.addPhoto(photoEntry);
                        }
                    }
                }
            } catch (Throwable e) {
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception e) {
                    }
                }
            }

            try {
                if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 23 && ApplicationLoader.applicationContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projectionVideo, null, null, Build.VERSION.SDK_INT > 28 ? MediaStore.Video.Media.DATE_MODIFIED : MediaStore.Video.Media.DATE_TAKEN + " DESC");
                    if (cursor != null) {
                        int imageIdColumn = cursor.getColumnIndex(MediaStore.Video.Media._ID);
                        int bucketIdColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                        int bucketNameColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                        int dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
                        int dateColumn = cursor.getColumnIndex(Build.VERSION.SDK_INT > 28 ? MediaStore.Video.Media.DATE_MODIFIED : MediaStore.Video.Media.DATE_TAKEN);
                        int durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);

                        while (cursor.moveToNext()) {
                            int imageId = cursor.getInt(imageIdColumn);
                            int bucketId = cursor.getInt(bucketIdColumn);
                            String bucketName = cursor.getString(bucketNameColumn);
                            String path = cursor.getString(dataColumn);
                            long dateTaken = cursor.getLong(dateColumn);
                            long duration = cursor.getLong(durationColumn);


                            if (path == null || path.length() == 0) {
                                continue;
                            }

                            PhotoEntry photoEntry = new PhotoEntry(bucketId, imageId, dateTaken, path, (int) (duration / 1000), true);

                            if (allVideosAlbum == null) {
                                allVideosAlbum = new AlbumEntry(0,ApplicationLoader.applicationContext.getString(R.string.AllVideos), photoEntry);
                                allVideosAlbum.videoOnly = true;
                                int index = 0;
                                if (allMediaAlbum != null) {
                                    index++;
                                }
                                if (allPhotosAlbum != null) {
                                    index++;
                                }
                                mediaAlbumsSorted.add(index, allVideosAlbum);
                            }
                            if (allMediaAlbum == null) {
                                allMediaAlbum = new AlbumEntry(0, ApplicationLoader.applicationContext.getString(R.string.AllMedia), photoEntry);
                                mediaAlbumsSorted.add(0, allMediaAlbum);
                            }
                            allVideosAlbum.addPhoto(photoEntry);
                            allMediaAlbum.addPhoto(photoEntry);

                            AlbumEntry albumEntry = mediaAlbums.get(bucketId);
                            if (albumEntry == null) {
                                albumEntry = new AlbumEntry(bucketId, bucketName, photoEntry);
                                mediaAlbums.put(bucketId, albumEntry);
                                if (mediaCameraAlbumId == null && cameraFolder != null && path != null && path.startsWith(cameraFolder)) {
                                    mediaAlbumsSorted.add(0, albumEntry);
                                    mediaCameraAlbumId = bucketId;
                                } else {
                                    mediaAlbumsSorted.add(albumEntry);
                                }
                            }

                            albumEntry.addPhoto(photoEntry);
                        }
                    }
                }
            } catch (Throwable e) {

            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception e) {

                    }
                }
            }
            for (int a = 0; a < mediaAlbumsSorted.size(); a++) {
                Collections.sort(mediaAlbumsSorted.get(a).photos, (o1, o2) -> {
                    if (o1.dateTaken < o2.dateTaken) {
                        return 1;
                    } else if (o1.dateTaken > o2.dateTaken) {
                        return -1;
                    }
                    return 0;
                });
            }
            broadcastNewPhotos(guid, mediaAlbumsSorted, photoAlbumsSorted, mediaCameraAlbumId, allMediaAlbum, allPhotosAlbum, allVideosAlbum, 0);
        });
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void broadcastNewPhotos(final int guid, final ArrayList<AlbumEntry> mediaAlbumsSorted, final ArrayList<AlbumEntry> photoAlbumsSorted, final Integer cameraAlbumIdFinal, final AlbumEntry allMediaAlbumFinal, final AlbumEntry allPhotosAlbumFinal, final AlbumEntry allVideosAlbumFinal, int delay) {
        if (broadcastPhotosRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(broadcastPhotosRunnable);
        }
        AndroidUtilities.runOnUIThread(broadcastPhotosRunnable = () -> {
//            if (PhotoViewer.getInstance().isVisible()) {
//                broadcastNewPhotos(guid, mediaAlbumsSorted, photoAlbumsSorted, cameraAlbumIdFinal, allMediaAlbumFinal, allPhotosAlbumFinal, allVideosAlbumFinal, 1000);
//                return;
//            }
            allMediaAlbums = mediaAlbumsSorted;
            allPhotoAlbums = photoAlbumsSorted;
            broadcastPhotosRunnable = null;
            allPhotosAlbumEntry = allPhotosAlbumFinal;
            allMediaAlbumEntry = allMediaAlbumFinal;
            allVideosAlbumEntry = allVideosAlbumFinal;
            NotificationCenter.getInstance().postNotificationName(NotificationCenter.albumsDidLoaded, guid, mediaAlbumsSorted, photoAlbumsSorted, cameraAlbumIdFinal);

        }, delay);
    }

    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }

    @SuppressLint("NewApi")
    public static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        int lastColorFormat = 0;
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                lastColorFormat = colorFormat;
                if (!(codecInfo.getName().equals("OMX.SEC.AVC.Encoder") && colorFormat == 19)) {
                    return colorFormat;
                }
            }
        }
        return lastColorFormat;
    }

    private int findTrack(MediaExtractor extractor, boolean audio) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (audio) {
                if (mime.startsWith("audio/")) {
                    return i;
                }
            } else {
                if (mime.startsWith("video/")) {
                    return i;
                }
            }
        }
        return -5;
    }

    private void checkConversionCanceled() {
        boolean cancelConversion;
        synchronized (videoConvertSync) {
            cancelConversion = cancelCurrentVideoConversion;
        }
        if (cancelConversion) {
            throw new RuntimeException("canceled conversion");
        }
    }
    //==============================================================================================

}
