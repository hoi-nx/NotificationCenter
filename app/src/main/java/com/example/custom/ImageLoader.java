package com.example.custom;/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */


import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.SparseArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class ImageLoader {

    private HashMap<String, Integer> bitmapUseCounts = new HashMap<>();
    private LruCache<BitmapDrawable> memCache;
    private HashMap<String, CacheImage> imageLoadingByUrl = new HashMap<>();
    private HashMap<String, CacheImage> imageLoadingByKeys = new HashMap<>();
    private SparseArray<CacheImage> imageLoadingByTag = new SparseArray<>();
    private HashMap<String, ThumbGenerateInfo> waitingForQualityThumb = new HashMap<>();
    private SparseArray<String> waitingForQualityThumbByTag = new SparseArray<>();
    private DispatchQueue cacheOutQueue = new DispatchQueue("cacheOutQueue");
    private DispatchQueue cacheThumbOutQueue = new DispatchQueue("cacheThumbOutQueue");
    private DispatchQueue thumbGeneratingQueue = new DispatchQueue("thumbGeneratingQueue");
    private DispatchQueue imageLoadQueue = new DispatchQueue("imageLoadQueue");
    private HashMap<String, String> replacedBitmaps = new HashMap<>();
    private ConcurrentHashMap<String, Float> fileProgresses = new ConcurrentHashMap<>();
    private HashMap<String, Integer> forceLoadingImages = new HashMap<>();
    private static ThreadLocal<byte[]> bytesLocal = new ThreadLocal<>();
    private static ThreadLocal<byte[]> bytesThumbLocal = new ThreadLocal<>();
    private static byte[] header = new byte[12];
    private static byte[] headerThumb = new byte[12];
    private int currentHttpTasksCount = 0;
    private int currentArtworkTasksCount = 0;
    private boolean canForce8888;
    private HashMap<String, Runnable> retryHttpsTasks = new HashMap<>();
    private int currentHttpFileLoadTasksCount = 0;

    private String ignoreRemoval = null;

    private volatile long lastCacheOutTime = 0;
    private int lastImageNum = 0;
    private long lastProgressUpdateTime = 0;

    private File telegramPath = null;

    public static final String AUTOPLAY_FILTER = "g";

    private class ThumbGenerateInfo {
        private String filter;
        private ArrayList<ImageReceiver> imageReceiverArray = new ArrayList<>();
        private ArrayList<Integer> imageReceiverGuidsArray = new ArrayList<>();
        private boolean big;
    }


    private class CacheOutTask implements Runnable {
        private Thread runningThread;
        private final Object sync = new Object();

        private CacheImage cacheImage;
        private boolean isCancelled;

        public CacheOutTask(CacheImage image) {
            cacheImage = image;
        }

        @Override
        public void run() {
            synchronized (sync) {
                runningThread = Thread.currentThread();
                Thread.interrupted();
                if (isCancelled) {
                    return;
                }
            }

            {
                Long mediaId = null;
                boolean mediaIsVideo = false;
                Bitmap image = null;
                boolean needInvert = false;
                int orientation = 0;
                File cacheFileFinal = cacheImage.finalFilePath;
                byte[] secureDocumentHash;
                boolean canDeleteFile = true;
                boolean useNativeWebpLoader = false;

                if (Build.VERSION.SDK_INT < 19) {
                    RandomAccessFile randomAccessFile = null;
                    try {
                        randomAccessFile = new RandomAccessFile(cacheFileFinal, "r");
                        byte[] bytes;
                        if (cacheImage.imageType == ImageReceiver.TYPE_THUMB) {
                            bytes = headerThumb;
                        } else {
                            bytes = header;
                        }
                        randomAccessFile.readFully(bytes, 0, bytes.length);
                        String str = new String(bytes).toLowerCase();
                        str = str.toLowerCase();
                        if (str.startsWith("riff") && str.endsWith("webp")) {
                            useNativeWebpLoader = true;
                        }
                        randomAccessFile.close();
                    } catch (Exception e) {
                    } finally {
                        if (randomAccessFile != null) {
                            try {
                                randomAccessFile.close();
                            } catch (Exception e) {
                            }
                        }
                    }
                }

                String mediaThumbPath = null;
                if (cacheImage.imageLocation.path != null) {
                    String location = cacheImage.imageLocation.path;
                    if (location.startsWith("thumb://")) {
                        int idx = location.indexOf(":", 8);
                        if (idx >= 0) {
                            mediaId = Long.parseLong(location.substring(8, idx));
                            mediaIsVideo = false;
                            mediaThumbPath = location.substring(idx + 1);
                        }
                        canDeleteFile = false;
                    } else if (location.startsWith("vthumb://")) {
                        int idx = location.indexOf(":", 9);
                        if (idx >= 0) {
                            mediaId = Long.parseLong(location.substring(9, idx));
                            mediaIsVideo = true;
                        }
                        canDeleteFile = false;
                    } else if (!location.startsWith("http")) {
                        canDeleteFile = false;
                    }
                }

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;

                if (Build.VERSION.SDK_INT < 21) {
                    opts.inPurgeable = true;
                }

                float w_filter = 0;
                float h_filter = 0;
                int blurType = 0;
                boolean checkInversion = false;
                boolean force8888 = canForce8888;
                try {
                    if (cacheImage.filter != null) {
                        String[] args = cacheImage.filter.split("_");
                        if (args.length >= 2) {
                            w_filter = Float.parseFloat(args[0]) * AndroidUtilities.density;
                            h_filter = Float.parseFloat(args[1]) * AndroidUtilities.density;
                        }
                        if (cacheImage.filter.contains("b2")) {
                            blurType = 3;
                        } else if (cacheImage.filter.contains("b1")) {
                            blurType = 2;
                        } else if (cacheImage.filter.contains("b")) {
                            blurType = 1;
                        }
                        if (cacheImage.filter.contains("i")) {
                            checkInversion = true;
                        }
                        if (cacheImage.filter.contains("f")) {
                            force8888 = true;
                        }
                        if (!useNativeWebpLoader && w_filter != 0 && h_filter != 0) {
                            opts.inJustDecodeBounds = true;

                            if (mediaId != null && mediaThumbPath == null) {
                                if (mediaIsVideo) {
                                    MediaStore.Video.Thumbnails.getThumbnail(ApplicationLoader.applicationContext.getContentResolver(), mediaId, MediaStore.Video.Thumbnails.MINI_KIND, opts);
                                } else {
                                    MediaStore.Images.Thumbnails.getThumbnail(ApplicationLoader.applicationContext.getContentResolver(), mediaId, MediaStore.Images.Thumbnails.MINI_KIND, opts);
                                }
                            }
                            float photoW = opts.outWidth;
                            float photoH = opts.outHeight;
                            float scaleFactor;
                            if (w_filter >= h_filter && photoW > photoH) {
                                scaleFactor = Math.max(photoW / w_filter, photoH / h_filter);
                            } else {
                                scaleFactor = Math.min(photoW / w_filter, photoH / h_filter);
                            }
                            if (scaleFactor < 1.2f) {
                                scaleFactor = 1;
                            }
                            opts.inJustDecodeBounds = false;
                            if (scaleFactor > 1.0f && (photoW > w_filter || photoH > h_filter)) {
                                int sample = 1;
                                do {
                                    sample *= 2;
                                } while (sample * 2 < scaleFactor);
                                opts.inSampleSize = sample;
                            } else {
                                opts.inSampleSize = (int) scaleFactor;
                            }
                        }
                    } else if (mediaThumbPath != null) {
                        opts.inJustDecodeBounds = true;
                        opts.inPreferredConfig = force8888 ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                        FileInputStream is = new FileInputStream(cacheFileFinal);
                        image = BitmapFactory.decodeStream(is, null, opts);
                        is.close();
                        int photoW2 = opts.outWidth;
                        int photoH2 = opts.outHeight;
                        opts.inJustDecodeBounds = false;
                        float scaleFactor = Math.max(photoW2 / 200, photoH2 / 200);
                        if (scaleFactor < 1) {
                            scaleFactor = 1;
                        }
                        if (scaleFactor > 1.0f) {
                            int sample = 1;
                            do {
                                sample *= 2;
                            } while (sample * 2 < scaleFactor);
                            opts.inSampleSize = sample;
                        } else {
                            opts.inSampleSize = (int) scaleFactor;
                        }
                    }
                } catch (Throwable e) {
                }

                if (cacheImage.imageType == ImageReceiver.TYPE_THUMB) {
                    try {
                        lastCacheOutTime = System.currentTimeMillis();
                        synchronized (sync) {
                            if (isCancelled) {
                                return;
                            }
                        }

                        if (image == null) {
                            if (cacheFileFinal.length() == 0 || cacheImage.filter == null) {
                                cacheFileFinal.delete();
                            }
                        } else {
                            if (cacheImage.filter != null) {
                                float bitmapW = image.getWidth();
                                float bitmapH = image.getHeight();
                                if (!opts.inPurgeable && w_filter != 0 && bitmapW != w_filter && bitmapW > w_filter + 20) {
                                    float scaleFactor = bitmapW / w_filter;
                                    Bitmap scaledBitmap = Bitmaps.createScaledBitmap(image, (int) w_filter, (int) (bitmapH / scaleFactor), true);
                                    if (image != scaledBitmap) {
                                        image.recycle();
                                        image = scaledBitmap;
                                    }
                                }
                            }

                        }
                    } catch (Throwable e) {
                    }
                } else {
                    try {
                        int delay = 20;
                        if (mediaId != null) {
                            delay = 0;
                        }
                        if (delay != 0 && lastCacheOutTime != 0 && lastCacheOutTime > System.currentTimeMillis() - delay && Build.VERSION.SDK_INT < 21) {
                            Thread.sleep(delay);
                        }
                        lastCacheOutTime = System.currentTimeMillis();
                        synchronized (sync) {
                            if (isCancelled) {
                                return;
                            }
                        }

                        if (force8888 || cacheImage.filter == null || blurType != 0 || cacheImage.imageLocation.path != null) {
                            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        } else {
                            opts.inPreferredConfig = Bitmap.Config.RGB_565;
                        }

                        opts.inDither = false;
                        if (mediaId != null && mediaThumbPath == null) {
                            if (mediaIsVideo) {
                                image = MediaStore.Video.Thumbnails.getThumbnail(ApplicationLoader.applicationContext.getContentResolver(), mediaId, MediaStore.Video.Thumbnails.MINI_KIND, opts);
                            } else {
                                image = MediaStore.Images.Thumbnails.getThumbnail(ApplicationLoader.applicationContext.getContentResolver(), mediaId, MediaStore.Images.Thumbnails.MINI_KIND, opts);
                            }
                        }
                        if (image == null) {
                            if (canDeleteFile && (cacheFileFinal.length() == 0 || cacheImage.filter == null)) {
                                cacheFileFinal.delete();
                            }
                        } else {
                            boolean blured = false;
                            if (cacheImage.filter != null) {
                                float bitmapW = image.getWidth();
                                float bitmapH = image.getHeight();
                                if (!opts.inPurgeable && w_filter != 0 && bitmapW != w_filter && bitmapW > w_filter + 20) {
                                    Bitmap scaledBitmap;
                                    if (bitmapW > bitmapH && w_filter > h_filter) {
                                        float scaleFactor = bitmapW / w_filter;
                                        scaledBitmap = Bitmaps.createScaledBitmap(image, (int) w_filter, (int) (bitmapH / scaleFactor), true);
                                    } else {
                                        float scaleFactor = bitmapH / h_filter;
                                        scaledBitmap = Bitmaps.createScaledBitmap(image, (int) (bitmapW / scaleFactor), (int) h_filter, true);
                                    }
                                    if (image != scaledBitmap) {
                                        image.recycle();
                                        image = scaledBitmap;
                                    }
                                }
                            }

                        }
                    } catch (Throwable ignore) {

                    }
                }
                Thread.interrupted();

                onPostExecute(image != null ? new BitmapDrawable(image) : null);

            }
        }

        private void onPostExecute(final Drawable drawable) {
            AndroidUtilities.runOnUIThread(() -> {
                Drawable toSet = null;
                String decrementKey = null;
               if (drawable instanceof BitmapDrawable) {
                    BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
                    toSet = memCache.get(cacheImage.key);
                    if (toSet == null) {
                        memCache.put(cacheImage.key, bitmapDrawable);
                        toSet = bitmapDrawable;
                    } else {
                        Bitmap image = bitmapDrawable.getBitmap();
                        image.recycle();
                    }
                    if (toSet != null) {
                        incrementUseCount(cacheImage.key);
                        decrementKey = cacheImage.key;
                    }
                }
                final Drawable toSetFinal = toSet;
                final String decrementKetFinal = decrementKey;
                imageLoadQueue.postRunnable(() -> cacheImage.setImageAndClear(toSetFinal, decrementKetFinal));
            });
        }

        public void cancel() {
            synchronized (sync) {
                try {
                    isCancelled = true;
                    if (runningThread != null) {
                        runningThread.interrupt();
                    }
                } catch (Exception e) {
                    //don't promt
                }
            }
        }
    }

    private class CacheImage {

        protected String key;
        protected String url;
        protected String filter;
        protected String ext;
        protected ImageLocation imageLocation;
        protected Object parentObject;
        protected int size;
        protected boolean animatedFile;
        protected boolean lottieFile;
        protected int imageType;

        protected int currentAccount;
        protected String httpUrl;

        protected File finalFilePath;
        protected File tempFilePath;
        protected File encryptionKeyPath;

        protected CacheOutTask cacheTask;

        protected ArrayList<ImageReceiver> imageReceiverArray = new ArrayList<>();
        protected ArrayList<Integer> imageReceiverGuidsArray = new ArrayList<>();
        protected ArrayList<String> keys = new ArrayList<>();
        protected ArrayList<String> filters = new ArrayList<>();
        protected ArrayList<Integer> imageTypes = new ArrayList<>();

        public void addImageReceiver(ImageReceiver imageReceiver, String key, String filter, int type, int guid) {
            int index = imageReceiverArray.indexOf(imageReceiver);
            if (index >= 0) {
                imageReceiverGuidsArray.set(index, guid);
                return;
            }
            imageReceiverArray.add(imageReceiver);
            imageReceiverGuidsArray.add(guid);
            keys.add(key);
            filters.add(filter);
            imageTypes.add(type);
            imageLoadingByTag.put(imageReceiver.getTag(type), this);
        }

        public void replaceImageReceiver(ImageReceiver imageReceiver, String key, String filter, int type, int guid) {
            int index = imageReceiverArray.indexOf(imageReceiver);
            if (index == -1) {
                return;
            }
            if (imageTypes.get(index) != type) {
                index = imageReceiverArray.subList(index + 1, imageReceiverArray.size()).indexOf(imageReceiver);
                if (index == -1) {
                    return;
                }
            }
            imageReceiverGuidsArray.set(index, guid);
            keys.set(index, key);
            filters.set(index, filter);
        }

        public void removeImageReceiver(ImageReceiver imageReceiver) {
            int currentImageType = imageType;
            for (int a = 0; a < imageReceiverArray.size(); a++) {
                ImageReceiver obj = imageReceiverArray.get(a);
                if (obj == null || obj == imageReceiver) {
                    imageReceiverArray.remove(a);
                    imageReceiverGuidsArray.remove(a);
                    keys.remove(a);
                    filters.remove(a);
                    currentImageType = imageTypes.remove(a);
                    if (obj != null) {
                        imageLoadingByTag.remove(obj.getTag(currentImageType));
                    }
                    a--;
                }
            }
            if (imageReceiverArray.isEmpty()) {
                if (cacheTask != null) {
                    if (currentImageType == ImageReceiver.TYPE_THUMB) {
                        cacheThumbOutQueue.cancelRunnable(cacheTask);
                    } else {
                        cacheOutQueue.cancelRunnable(cacheTask);
                    }
                    cacheTask.cancel();
                    cacheTask = null;
                }
                if (url != null) {
                    imageLoadingByUrl.remove(url);
                }
                if (key != null) {
                    imageLoadingByKeys.remove(key);
                }
            }
        }

        public void setImageAndClear(final Drawable image, String decrementKey) {
            if (image != null) {
                final ArrayList<ImageReceiver> finalImageReceiverArray = new ArrayList<>(imageReceiverArray);
                final ArrayList<Integer> finalImageReceiverGuidsArray = new ArrayList<>(imageReceiverGuidsArray);
                AndroidUtilities.runOnUIThread(() -> {
                    {
                        for (int a = 0; a < finalImageReceiverArray.size(); a++) {
                            ImageReceiver imgView = finalImageReceiverArray.get(a);
                            imgView.setImageBitmapByKey(image, key, imageTypes.get(a), false, finalImageReceiverGuidsArray.get(a));
                        }
                    }
                    if (decrementKey != null) {
                        decrementUseCount(decrementKey);
                    }
                });
            }
            for (int a = 0; a < imageReceiverArray.size(); a++) {
                ImageReceiver imageReceiver = imageReceiverArray.get(a);
                imageLoadingByTag.remove(imageReceiver.getTag(imageType));
            }
            imageReceiverArray.clear();
            imageReceiverGuidsArray.clear();
            if (url != null) {
                imageLoadingByUrl.remove(url);
            }
            if (key != null) {
                imageLoadingByKeys.remove(key);
            }
        }
    }

    private static volatile ImageLoader Instance = null;

    public static ImageLoader getInstance() {
        ImageLoader localInstance = Instance;
        if (localInstance == null) {
            synchronized (ImageLoader.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new ImageLoader();
                }
            }
        }
        return localInstance;
    }

    public ImageLoader() {
        thumbGeneratingQueue.setPriority(Thread.MIN_PRIORITY);

        int memoryClass = ((ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();
        int maxSize;
        if (canForce8888 = memoryClass >= 192) {
            maxSize = 30;
        } else {
            maxSize = 15;
        }
        int cacheSize = Math.min(maxSize, memoryClass / 7) * 1024 * 1024;

        memCache = new LruCache<BitmapDrawable>(cacheSize) {
            @Override
            protected int sizeOf(String key, BitmapDrawable value) {
                return value.getBitmap().getByteCount();
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, final BitmapDrawable oldValue, BitmapDrawable newValue) {
                if (ignoreRemoval != null && ignoreRemoval.equals(key)) {
                    return;
                }
                final Integer count = bitmapUseCounts.get(key);
                if (count == null || count == 0) {
                    Bitmap b = oldValue.getBitmap();
                    if (!b.isRecycled()) {
                        b.recycle();
                    }
                }
            }
        };

    }


    public String getReplacedKey(String oldKey) {
        if (oldKey == null) {
            return null;
        }
        return replacedBitmaps.get(oldKey);
    }

    private void performReplace(String oldKey, String newKey) {
        BitmapDrawable b = memCache.get(oldKey);
        replacedBitmaps.put(oldKey, newKey);
        if (b != null) {
            BitmapDrawable oldBitmap = memCache.get(newKey);
            boolean dontChange = false;
            if (oldBitmap != null && oldBitmap.getBitmap() != null && b.getBitmap() != null) {
                Bitmap oldBitmapObject = oldBitmap.getBitmap();
                Bitmap newBitmapObject = b.getBitmap();
                if (oldBitmapObject.getWidth() > newBitmapObject.getWidth() || oldBitmapObject.getHeight() > newBitmapObject.getHeight()) {
                    dontChange = true;
                }
            }
            if (!dontChange) {
                ignoreRemoval = oldKey;
                memCache.remove(oldKey);
                memCache.put(newKey, b);
                ignoreRemoval = null;
            } else {
                memCache.remove(oldKey);
            }
        }
        Integer val = bitmapUseCounts.get(oldKey);
        if (val != null) {
            bitmapUseCounts.put(newKey, val);
            bitmapUseCounts.remove(oldKey);
        }
    }

    public void incrementUseCount(String key) {
        Integer count = bitmapUseCounts.get(key);
        if (count == null) {
            bitmapUseCounts.put(key, 1);
        } else {
            bitmapUseCounts.put(key, count + 1);
        }
    }

    public boolean decrementUseCount(String key) {
        Integer count = bitmapUseCounts.get(key);
        if (count == null) {
            return true;
        }
        if (count == 1) {
            bitmapUseCounts.remove(key);
            return true;
        } else {
            bitmapUseCounts.put(key, count - 1);
        }
        return false;
    }

    public void removeImage(String key) {
        bitmapUseCounts.remove(key);
        memCache.remove(key);
    }

    public boolean isInMemCache(String key, boolean animated) {

        return memCache.get(key) != null;

    }

    public void clearMemory() {
        memCache.evictAll();
    }

    private void removeFromWaitingForThumb(int TAG, ImageReceiver imageReceiver) {
        String location = waitingForQualityThumbByTag.get(TAG);
        if (location != null) {
            ThumbGenerateInfo info = waitingForQualityThumb.get(location);
            if (info != null) {
                int index = info.imageReceiverArray.indexOf(imageReceiver);
                if (index >= 0) {
                    info.imageReceiverArray.remove(index);
                    info.imageReceiverGuidsArray.remove(index);
                }
                if (info.imageReceiverArray.isEmpty()) {
                    waitingForQualityThumb.remove(location);
                }
            }
            waitingForQualityThumbByTag.remove(TAG);
        }
    }

    public void cancelLoadingForImageReceiver(final ImageReceiver imageReceiver, final boolean cancelAll) {
        if (imageReceiver == null) {
            return;
        }
        imageLoadQueue.postRunnable(() -> {

            for (int a = 0; a < 3; a++) {
                int imageType;
                if (a > 0 && !cancelAll) {
                    return;
                }
                if (a == 0) {
                    imageType = ImageReceiver.TYPE_THUMB;
                } else if (a == 1) {
                    imageType = ImageReceiver.TYPE_IMAGE;
                } else {
                    imageType = ImageReceiver.TYPE_MEDIA;
                }
                int TAG = imageReceiver.getTag(imageType);
                if (TAG != 0) {
                    if (a == 0) {
                        removeFromWaitingForThumb(TAG, imageReceiver);
                    }
                    CacheImage ei = imageLoadingByTag.get(TAG);
                    if (ei != null) {
                        ei.removeImageReceiver(imageReceiver);
                    }
                }
            }
        });
    }


    public void putImageToCache(BitmapDrawable bitmap, String key) {
        memCache.put(key, bitmap);
    }

    public void cancelForceLoadingForImageReceiver(final ImageReceiver imageReceiver) {
        if (imageReceiver == null) {
            return;
        }
        final String key = imageReceiver.getImageKey();
        if (key == null) {
            return;
        }
        imageLoadQueue.postRunnable(() -> forceLoadingImages.remove(key));
    }

    private void createLoadOperationForImageReceiver(final ImageReceiver imageReceiver, final String key, final String url, final String ext, final ImageLocation imageLocation, final String httpLocation, final String filter, final int size, final int cacheType, final int imageType, final int thumb, int guid) {
        if (imageReceiver == null || url == null || key == null || imageLocation == null) {
            return;
        }
        int TAG = imageReceiver.getTag(imageType);
        if (TAG == 0) {
            imageReceiver.setTag(TAG = lastImageNum, imageType);
            lastImageNum++;
            if (lastImageNum == Integer.MAX_VALUE) {
                lastImageNum = 0;
            }
        }

        final int finalTag = TAG;
        imageLoadQueue.postRunnable(() -> {
            boolean added = false;
            if (thumb != 2) {
                CacheImage alreadyLoadingUrl = imageLoadingByUrl.get(url);
                CacheImage alreadyLoadingCache = imageLoadingByKeys.get(key);
                CacheImage alreadyLoadingImage = imageLoadingByTag.get(finalTag);
                if (alreadyLoadingImage != null) {
                    if (alreadyLoadingImage == alreadyLoadingCache) {
                        added = true;
                    } else if (alreadyLoadingImage == alreadyLoadingUrl) {
                        if (alreadyLoadingCache == null) {
                            alreadyLoadingImage.replaceImageReceiver(imageReceiver, key, filter, imageType, guid);
                        }
                        added = true;
                    } else {
                        alreadyLoadingImage.removeImageReceiver(imageReceiver);
                    }
                }

                if (!added && alreadyLoadingCache != null) {
                    alreadyLoadingCache.addImageReceiver(imageReceiver, key, filter, imageType, guid);
                    added = true;
                }
                if (!added && alreadyLoadingUrl != null) {
                    alreadyLoadingUrl.addImageReceiver(imageReceiver, key, filter, imageType, guid);
                    added = true;
                }
            }

            if (!added) {
                boolean onlyCache = false;
                boolean isQuality = false;
                File cacheFile = null;
                boolean cacheFileExists = false;
                if (httpLocation != null) {
                    if (!httpLocation.startsWith("http")) {
                        onlyCache = true;
                        if (httpLocation.startsWith("thumb://")) {
                            int idx = httpLocation.indexOf(":", 8);
                            if (idx >= 0) {
                                cacheFile = new File(httpLocation.substring(idx + 1));
                            }
                        } else if (httpLocation.startsWith("vthumb://")) {
                            int idx = httpLocation.indexOf(":", 9);
                            if (idx >= 0) {
                                cacheFile = new File(httpLocation.substring(idx + 1));
                            }
                        } else {
                            cacheFile = new File(httpLocation);
                        }
                    }
                } else if (imageLocation.path != null) {
                    String location = imageLocation.path;
                    if (!location.startsWith("http") && !location.startsWith("athumb")) {
                        onlyCache = true;
                        if (location.startsWith("thumb://")) {
                            int idx = location.indexOf(":", 8);
                            if (idx >= 0) {
                                cacheFile = new File(location.substring(idx + 1));
                            }
                        } else if (location.startsWith("vthumb://")) {
                            int idx = location.indexOf(":", 9);
                            if (idx >= 0) {
                                cacheFile = new File(location.substring(idx + 1));
                            }
                        } else {
                            cacheFile = new File(location);
                        }
                    }
                }
                if (thumb != 2) {
                    CacheImage img = new CacheImage();
                    img.imageType = imageType;
                    img.key = key;
                    img.filter = filter;
                    img.imageLocation = imageLocation;
                    img.ext = ext;
//                    img.httpUrl = httpLocation;

                    img.addImageReceiver(imageReceiver, key, filter, imageType, guid);
                    if (onlyCache || cacheFileExists || cacheFile.exists()) {
                        img.finalFilePath = cacheFile;
                        img.imageLocation = imageLocation;
                        img.cacheTask = new CacheOutTask(img);
                        imageLoadingByKeys.put(key, img);
                        if (thumb != 0) {
                            cacheThumbOutQueue.postRunnable(img.cacheTask);
                        } else {
                            cacheOutQueue.postRunnable(img.cacheTask);
                        }
                    }
                }
            }
        });
    }


    public void loadImageForImageReceiver(ImageReceiver imageReceiver) {
        if (imageReceiver == null) {
            return;
        }
        boolean imageSet = false;
        int guid = imageReceiver.getNewGuid();

        String imageKey = imageReceiver.getImageKey();
        if (!imageSet && imageKey != null) {
            ImageLocation imageLocation = imageReceiver.getImageLocation();
            Drawable drawable;
            drawable = memCache.get(imageKey);
            if (drawable != null) {
                memCache.moveToFront(imageKey);
            }
            if (drawable != null) {
                cancelLoadingForImageReceiver(imageReceiver, true);
                imageReceiver.setImageBitmapByKey(drawable, imageKey, ImageReceiver.TYPE_IMAGE, true, guid);
            }
        }
        ImageLocation imageLocation = imageReceiver.getImageLocation();
        String imageFilter = imageReceiver.getImageFilter();
        boolean saveImageToCache = false;

        String imageUrl = null;
        imageKey = null;
        String ext = imageReceiver.getExt();
        if (ext == null) {
            ext = "jpg";
        }

        for (int a = 0; a < 2; a++) {
            ImageLocation object = null;
            if (a == 0) {
                object = imageLocation;
            }
            if (object == null) {
                continue;
            }
            String url = null;
        }

        if (imageKey != null && imageFilter != null) {
            imageKey += "@" + imageFilter;
        }

        if (imageLocation != null && imageLocation.path != null) {
            createLoadOperationForImageReceiver(imageReceiver, imageKey, imageUrl, ext, imageLocation, null, imageFilter, imageReceiver.getSize(), 1, ImageReceiver.TYPE_IMAGE, 0, guid);
        } else {
            int imageCacheType = imageReceiver.getCacheType();
            if (imageCacheType == 0 && saveImageToCache) {
                imageCacheType = 1;
            }
            createLoadOperationForImageReceiver(imageReceiver, imageKey, imageUrl, ext, imageLocation, null, imageFilter, imageReceiver.getSize(), imageCacheType, ImageReceiver.TYPE_IMAGE, 0, guid);
        }
    }


    public static boolean shouldSendImageAsDocument(String path, Uri uri) {
        BitmapFactory.Options bmOptions = new BitmapFactory.Options();
        bmOptions.inJustDecodeBounds = true;

        if (path == null && uri != null && uri.getScheme() != null) {
            String imageFilePath = null;
            if (uri.getScheme().contains("file")) {
                path = uri.getPath();
            } else {
                try {
                    path = AndroidUtilities.getPath(uri);
                } catch (Throwable e) {

                }
            }
        }

        if (path != null) {
            BitmapFactory.decodeFile(path, bmOptions);
        } else if (uri != null) {
            boolean error = false;
            try {
                InputStream inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                BitmapFactory.decodeStream(inputStream, null, bmOptions);
                inputStream.close();
            } catch (Throwable e) {
                return false;
            }
        }
        float photoW = bmOptions.outWidth;
        float photoH = bmOptions.outHeight;
        return photoW / photoH > 10.0f || photoH / photoW > 10.0f;
    }

//    public static Bitmap loadBitmap(String path, Uri uri, float maxWidth, float maxHeight, boolean useMaxScale) {
//        BitmapFactory.Options bmOptions = new BitmapFactory.Options();
//        bmOptions.inJustDecodeBounds = true;
//        InputStream inputStream = null;
//
//        if (path == null && uri != null && uri.getScheme() != null) {
//            String imageFilePath = null;
//            if (uri.getScheme().contains("file")) {
//                path = uri.getPath();
//            } else {
//                try {
//                    path = AndroidUtilities.getPath(uri);
//                } catch (Throwable e) {
//                }
//            }
//        }
//
//        if (path != null) {
//            BitmapFactory.decodeFile(path, bmOptions);
//        } else if (uri != null) {
//            boolean error = false;
//            try {
//                inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
//                BitmapFactory.decodeStream(inputStream, null, bmOptions);
//                inputStream.close();
//                inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
//            } catch (Throwable e) {
//                return null;
//            }
//        }
//        float photoW = bmOptions.outWidth;
//        float photoH = bmOptions.outHeight;
//        float scaleFactor = useMaxScale ? Math.max(photoW / maxWidth, photoH / maxHeight) : Math.min(photoW / maxWidth, photoH / maxHeight);
//        if (scaleFactor < 1) {
//            scaleFactor = 1;
//        }
//        bmOptions.inJustDecodeBounds = false;
//        bmOptions.inSampleSize = (int) scaleFactor;
//        if (bmOptions.inSampleSize % 2 != 0) {
//            int sample = 1;
//            while (sample * 2 < bmOptions.inSampleSize) {
//                sample *= 2;
//            }
//            bmOptions.inSampleSize = sample;
//        }
//        bmOptions.inPurgeable = Build.VERSION.SDK_INT < 21;
//
//        String exifPath = null;
//        if (path != null) {
//            exifPath = path;
//        } else if (uri != null) {
//            exifPath = AndroidUtilities.getPath(uri);
//        }
//
//        Matrix matrix = null;
//
//        if (exifPath != null) {
//            try {
//                ExifInterface exif = new ExifInterface(exifPath);
//                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
//                matrix = new Matrix();
//                switch (orientation) {
//                    case ExifInterface.ORIENTATION_ROTATE_90:
//                        matrix.postRotate(90);
//                        break;
//                    case ExifInterface.ORIENTATION_ROTATE_180:
//                        matrix.postRotate(180);
//                        break;
//                    case ExifInterface.ORIENTATION_ROTATE_270:
//                        matrix.postRotate(270);
//                        break;
//                }
//            } catch (Throwable ignore) {
//
//            }
//        }
//
//        Bitmap b = null;
//        if (path != null) {
//            try {
//                b = BitmapFactory.decodeFile(path, bmOptions);
//                if (b != null) {
//                    if (bmOptions.inPurgeable) {
//                        Utilities.pinBitmap(b);
//                    }
//                    Bitmap newBitmap = Bitmaps.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), matrix, true);
//                    if (newBitmap != b) {
//                        b.recycle();
//                        b = newBitmap;
//                    }
//                }
//            } catch (Throwable e) {
//                ImageLoader.getInstance().clearMemory();
//                try {
//                    if (b == null) {
//                        b = BitmapFactory.decodeFile(path, bmOptions);
//                        if (b != null && bmOptions.inPurgeable) {
//                            Utilities.pinBitmap(b);
//                        }
//                    }
//                    if (b != null) {
//                        Bitmap newBitmap = Bitmaps.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), matrix, true);
//                        if (newBitmap != b) {
//                            b.recycle();
//                            b = newBitmap;
//                        }
//                    }
//                } catch (Throwable e2) {
//                }
//            }
//        } else if (uri != null) {
//            try {
//                b = BitmapFactory.decodeStream(inputStream, null, bmOptions);
//                if (b != null) {
//                    if (bmOptions.inPurgeable) {
//                        Utilities.pinBitmap(b);
//                    }
//                    Bitmap newBitmap = Bitmaps.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), matrix, true);
//                    if (newBitmap != b) {
//                        b.recycle();
//                        b = newBitmap;
//                    }
//                }
//            } catch (Throwable e) {
//
//            } finally {
//                try {
//                    inputStream.close();
//                } catch (Throwable e) {
//
//                }
//            }
//        }
//
//        return b;
//    }

    public static String getHttpUrlExtension(String url, String defaultExt) {
        String ext = null;
        String last = Uri.parse(url).getLastPathSegment();
        if (!TextUtils.isEmpty(last) && last.length() > 1) {
            url = last;
        }
        int idx = url.lastIndexOf('.');
        if (idx != -1) {
            ext = url.substring(idx + 1);
        }
        if (ext == null || ext.length() == 0 || ext.length() > 4) {
            ext = defaultExt;
        }
        return ext;
    }

}
