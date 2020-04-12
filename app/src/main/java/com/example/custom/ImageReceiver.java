/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package com.example.custom;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

public class ImageReceiver implements NotificationCenter.NotificationCenterDelegate {

    public abstract class RecyclableDrawable extends Drawable {
        public abstract void recycle();
    }

    @Override
    public void didReceivedNotification(int id, Object... args) {
        if (id == NotificationCenter.didReplacedPhotoInMemCache) {
            String oldKey = (String) args[0];
            if (currentMediaKey != null && currentMediaKey.equals(oldKey)) {
                currentMediaKey = (String) args[1];
                currentMediaLocation = (ImageLocation) args[2];

            }
            if (currentImageKey != null && currentImageKey.equals(oldKey)) {
                currentImageKey = (String) args[1];
                currentImageLocation = (ImageLocation) args[2];
                if (setImageBackup != null) {
                    setImageBackup.imageLocation = (ImageLocation) args[2];
                }
            }
            if (currentThumbKey != null && currentThumbKey.equals(oldKey)) {
                currentThumbKey = (String) args[1];
                currentThumbLocation = (ImageLocation) args[2];
            }

        }

    }

    public interface ImageReceiverDelegate {
        void didSetImage(ImageReceiver imageReceiver, boolean set, boolean thumb);

        default void onAnimationReady(ImageReceiver imageReceiver) {
        }
    }

    public static class BitmapHolder {

        private String key;
        private boolean recycleOnRelease;
        public Bitmap bitmap;

        public BitmapHolder(Bitmap b, String k) {
            bitmap = b;
            key = k;
            if (key != null) {
                ImageLoader.getInstance().incrementUseCount(key);
            }
        }

        public BitmapHolder(Bitmap b) {
            bitmap = b;
            recycleOnRelease = true;
        }

        public int getWidth() {
            return bitmap != null ? bitmap.getWidth() : 0;
        }

        public int getHeight() {
            return bitmap != null ? bitmap.getHeight() : 0;
        }

        public boolean isRecycled() {
            return bitmap == null || bitmap.isRecycled();
        }

        public void release() {
            if (key == null) {
                if (recycleOnRelease && bitmap != null) {
                    bitmap.recycle();
                }
                bitmap = null;
                return;
            }
            boolean canDelete = ImageLoader.getInstance().decrementUseCount(key);
            if (!ImageLoader.getInstance().isInMemCache(key, false)) {
                if (canDelete) {
                    bitmap.recycle();
                }
            }
            key = null;
            bitmap = null;
        }
    }

    private class SetImageBackup {
        public ImageLocation imageLocation;
        public String imageFilter;
        public Drawable thumb;
        public int size;
        public int cacheType;
        public String ext;
    }

    public final static int TYPE_IMAGE = 0;
    public final static int TYPE_THUMB = 1;
    private final static int TYPE_CROSSFDADE = 2;
    public final static int TYPE_MEDIA = 3;

    private int currentAccount;
    private View parentView;

    private int param;
    private Object currentParentObject;
    private boolean canceledLoading;
    private static PorterDuffColorFilter selectedColorFilter = new PorterDuffColorFilter(0xffdddddd, PorterDuff.Mode.MULTIPLY);
    private static PorterDuffColorFilter selectedGroupColorFilter = new PorterDuffColorFilter(0xffbbbbbb, PorterDuff.Mode.MULTIPLY);
    private boolean forceLoding;

    private SetImageBackup setImageBackup;

    private ImageLocation strippedLocation;
    private ImageLocation currentImageLocation;
    private String currentImageFilter;
    private String currentImageKey;
    private int imageTag;
    private Drawable currentImageDrawable;
    private BitmapShader imageShader;
    private int imageOrientation;
    private String currentHttpUrl;

    private ImageLocation currentThumbLocation;
    private String currentThumbFilter;
    private String currentThumbKey;
    private int thumbTag;
    private Drawable currentThumbDrawable;
    private BitmapShader thumbShader;
    private int thumbOrientation;

    private ImageLocation currentMediaLocation;
    private String currentMediaFilter;
    private String currentMediaKey;
    private int mediaTag;
    private Drawable currentMediaDrawable;
    private BitmapShader mediaShader;

    private Drawable staticThumbDrawable;

    private String currentExt;

    private int currentGuid;

    private int currentSize;
    private int currentCacheType;
    private boolean allowStartAnimation = true;
    private boolean useSharedAnimationQueue;
    private boolean allowDecodeSingleFrame;
    private int autoRepeat = 1;
    private boolean animationReadySent;

    private boolean crossfadeWithOldImage;
    private boolean crossfadingWithThumb;
    private Drawable crossfadeImage;
    private String crossfadeKey;
    private BitmapShader crossfadeShader;

    private boolean needsQualityThumb;
    private boolean shouldGenerateQualityThumb;
    private boolean currentKeyQuality;
    private boolean invalidateAll;

    private int imageX, imageY, imageW, imageH;
    private float sideClip;
    private RectF drawRegion = new RectF();
    private boolean isVisible = true;
    private boolean isAspectFit;
    private boolean forcePreview;
    private boolean forceCrossfade;
    private int roundRadius;

    private Paint roundPaint;
    private RectF roundRect = new RectF();

    private Matrix shaderMatrix = new Matrix();
    private float overrideAlpha = 1.0f;
    private int isPressed;
    private boolean centerRotation;
    private ImageReceiverDelegate delegate;
    private float currentAlpha;
    private long lastUpdateAlphaTime;
    private byte crossfadeAlpha = 1;
    private boolean manualAlphaAnimator;
    private boolean crossfadeWithThumb;
    private ColorFilter colorFilter;

    public ImageReceiver() {
        this(null);
    }

    public ImageReceiver(View view) {
        parentView = view;
        roundPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    }

    public void cancelLoadImage() {
        forceLoding = false;
        ImageLoader.getInstance().cancelLoadingForImageReceiver(this, true);
        canceledLoading = true;
    }

    public void setImage(ImageLocation imageLocation, String imageFilter, Drawable thumb) {
        if (setImageBackup != null) {
            setImageBackup.imageLocation = null;
            setImageBackup.thumb = null;
        }
        if (imageLocation == null) {
            for (int a = 0; a < 4; a++) {
                recycleBitmap(null, a);
            }
            currentImageLocation = null;
            currentImageFilter = null;
            currentImageKey = null;
            currentMediaLocation = null;
            currentMediaFilter = null;
            currentMediaKey = null;
            currentThumbLocation = null;
            currentThumbFilter = null;
            currentThumbKey = null;

            currentMediaDrawable = null;
            mediaShader = null;
            currentImageDrawable = null;
            imageShader = null;
            thumbShader = null;
            crossfadeShader = null;

            currentParentObject = null;
            currentCacheType = 0;
            staticThumbDrawable = thumb;
            currentAlpha = 1.0f;
            currentSize = 0;

            ImageLoader.getInstance().cancelLoadingForImageReceiver(this, true);
            if (parentView != null) {
                if (invalidateAll) {
                    parentView.invalidate();
                } else {
                    parentView.invalidate(imageX, imageY, imageX + imageW, imageY + imageH);
                }
            }
            if (delegate != null) {
                delegate.didSetImage(this, currentImageDrawable != null || currentThumbDrawable != null || staticThumbDrawable != null || currentMediaDrawable != null, currentImageDrawable == null && currentMediaDrawable == null);
            }
            return;
        }
        currentKeyQuality = false;
        if (crossfadeWithOldImage) {
            if (currentImageDrawable != null) {
                recycleBitmap(null, TYPE_CROSSFDADE);
                crossfadeShader = imageShader;
                crossfadeImage = currentImageDrawable;
                crossfadeKey = currentImageKey;
                crossfadingWithThumb = false;
                currentImageDrawable = null;
                currentImageKey = null;
            } else if (currentThumbDrawable != null) {
                recycleBitmap(null, TYPE_CROSSFDADE);
                crossfadeShader = thumbShader;
                crossfadeImage = currentThumbDrawable;
                crossfadeKey = currentThumbKey;
                crossfadingWithThumb = false;
                currentThumbDrawable = null;
                currentThumbKey = null;
            } else if (staticThumbDrawable != null) {
                recycleBitmap(null, TYPE_CROSSFDADE);
                crossfadeShader = thumbShader;
                crossfadeImage = staticThumbDrawable;
                crossfadingWithThumb = false;
                crossfadeKey = null;
                currentThumbDrawable = null;
                currentThumbKey = null;
            } else {
                recycleBitmap(null, TYPE_CROSSFDADE);
                crossfadeShader = null;
            }
        } else {
            recycleBitmap(null, TYPE_CROSSFDADE);
            crossfadeShader = null;
        }
        currentImageLocation = imageLocation;
        currentImageFilter = imageFilter;
        currentSize = 0;
        currentCacheType = 0;
        staticThumbDrawable = thumb;
        imageShader = null;
        thumbShader = null;
        mediaShader = null;
        currentAlpha = 1.0f;

        if (delegate != null) {
            delegate.didSetImage(this, currentImageDrawable != null || currentThumbDrawable != null || staticThumbDrawable != null || currentMediaDrawable != null, currentImageDrawable == null && currentMediaDrawable == null);
        }

        ImageLoader.getInstance().loadImageForImageReceiver(this);
        if (parentView != null) {
            if (invalidateAll) {
                parentView.invalidate();
            } else {
                parentView.invalidate(imageX, imageY, imageX + imageW, imageY + imageH);
            }
        }
    }

    public boolean getPressed() {
        return isPressed != 0;
    }

    public void setOrientation(int angle, boolean center) {
        while (angle < 0) {
            angle += 360;
        }
        while (angle > 360) {
            angle -= 360;
        }
        imageOrientation = thumbOrientation = angle;
        centerRotation = center;
    }

    public void setImageBitmap(Bitmap bitmap) {
        setImageBitmap(bitmap != null ? new BitmapDrawable(null, bitmap) : null);
    }

    public void setImageBitmap(Drawable bitmap) {
        ImageLoader.getInstance().cancelLoadingForImageReceiver(this, true);

        if (crossfadeWithOldImage) {
            if (currentImageDrawable != null) {
                recycleBitmap(null, TYPE_THUMB);
                recycleBitmap(null, TYPE_CROSSFDADE);
                recycleBitmap(null, TYPE_MEDIA);
                crossfadeShader = imageShader;
                crossfadeImage = currentImageDrawable;
                crossfadeKey = currentImageKey;
                crossfadingWithThumb = true;
            } else if (currentThumbDrawable != null) {
                recycleBitmap(null, TYPE_IMAGE);
                recycleBitmap(null, TYPE_CROSSFDADE);
                recycleBitmap(null, TYPE_MEDIA);
                crossfadeShader = thumbShader;
                crossfadeImage = currentThumbDrawable;
                crossfadeKey = currentThumbKey;
                crossfadingWithThumb = true;
            } else if (staticThumbDrawable != null) {
                recycleBitmap(null, TYPE_IMAGE);
                recycleBitmap(null, TYPE_THUMB);
                recycleBitmap(null, TYPE_CROSSFDADE);
                recycleBitmap(null, TYPE_MEDIA);
                crossfadeShader = thumbShader;
                crossfadeImage = staticThumbDrawable;
                crossfadingWithThumb = true;
                crossfadeKey = null;
            } else {
                for (int a = 0; a < 4; a++) {
                    recycleBitmap(null, a);
                }
                crossfadeShader = null;
            }
        } else {
            for (int a = 0; a < 4; a++) {
                recycleBitmap(null, a);
            }
        }

        if (staticThumbDrawable instanceof RecyclableDrawable) {
            RecyclableDrawable drawable = (RecyclableDrawable) staticThumbDrawable;
            drawable.recycle();
        }

        staticThumbDrawable = bitmap;
        if (roundRadius != 0 && bitmap instanceof BitmapDrawable) {
            Bitmap object = ((BitmapDrawable) bitmap).getBitmap();
            thumbShader = new BitmapShader(object, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        } else {
            thumbShader = null;
        }
        currentMediaLocation = null;
        currentMediaFilter = null;
        currentMediaDrawable = null;
        currentMediaKey = null;
        mediaShader = null;

        currentImageLocation = null;
        currentImageFilter = null;
        currentImageDrawable = null;
        currentImageKey = null;
        imageShader = null;

        currentThumbLocation = null;
        currentThumbFilter = null;
        currentThumbKey = null;

        currentKeyQuality = false;
        currentExt = null;
        currentSize = 0;
        currentCacheType = 0;
        currentAlpha = 1;

        if (setImageBackup != null) {
            setImageBackup.imageLocation = null;
            setImageBackup.thumb = null;
        }

        if (delegate != null) {
            delegate.didSetImage(this, currentThumbDrawable != null || staticThumbDrawable != null, true);
        }
        if (parentView != null) {
            if (invalidateAll) {
                parentView.invalidate();
            } else {
                parentView.invalidate(imageX, imageY, imageX + imageW, imageY + imageH);
            }
        }
        if (forceCrossfade && crossfadeWithOldImage && crossfadeImage != null) {
            currentAlpha = 0.0f;
            lastUpdateAlphaTime = System.currentTimeMillis();
            crossfadeWithThumb = currentThumbDrawable != null || staticThumbDrawable != null;
        }
    }

    public void clearImage() {
        for (int a = 0; a < 4; a++) {
            recycleBitmap(null, a);
        }
        ImageLoader.getInstance().cancelLoadingForImageReceiver(this, true);
    }

    public void onDetachedFromWindow() {
        if (currentImageLocation != null || currentMediaLocation != null || currentThumbLocation != null || staticThumbDrawable != null) {
            if (setImageBackup == null) {
                setImageBackup = new SetImageBackup();
            }


            setImageBackup.imageLocation = currentImageLocation;
            setImageBackup.imageFilter = currentImageFilter;

            setImageBackup.thumb = staticThumbDrawable;
            setImageBackup.size = currentSize;
            setImageBackup.ext = currentExt;
            setImageBackup.cacheType = currentCacheType;

        }
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.didReplacedPhotoInMemCache);


        clearImage();
    }

    public boolean onAttachedToWindow() {
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.didReplacedPhotoInMemCache);
        if (setImageBackup != null && (setImageBackup.imageLocation != null  || setImageBackup.thumb != null)) {
            setImage(setImageBackup.imageLocation, setImageBackup.imageFilter,setImageBackup.thumb);
            return true;
        }
        return false;
    }

    private void checkAlphaAnimation(boolean skip) {
        if (manualAlphaAnimator) {
            return;
        }
        if (currentAlpha != 1) {
            if (!skip) {
                long currentTime = System.currentTimeMillis();
                long dt = currentTime - lastUpdateAlphaTime;
                if (dt > 18) {
                    dt = 18;
                }
                currentAlpha += dt / 150.0f;
                if (currentAlpha > 1) {
                    currentAlpha = 1;
                    if (crossfadeImage != null) {
                        recycleBitmap(null, 2);
                        crossfadeShader = null;
                    }
                }
            }
            lastUpdateAlphaTime = System.currentTimeMillis();
            if (parentView != null) {
                if (invalidateAll) {
                    parentView.invalidate();
                } else {
                    parentView.invalidate(imageX, imageY, imageX + imageW, imageY + imageH);
                }
            }
        }
    }

    public void setManualAlphaAnimator(boolean value) {
        manualAlphaAnimator = value;
    }

    public float getCurrentAlpha() {
        return currentAlpha;
    }

    public void setCurrentAlpha(float value) {
        currentAlpha = value;
    }

    public Drawable getDrawable() {
        if (currentMediaDrawable != null) {
            return currentMediaDrawable;
        } else if (currentImageDrawable != null) {
            return currentImageDrawable;
        } else if (currentThumbDrawable != null) {
            return currentThumbDrawable;
        } else if (staticThumbDrawable != null) {
            return staticThumbDrawable;
        }
        return null;
    }

    public Bitmap getBitmap() {
        if (staticThumbDrawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) staticThumbDrawable).getBitmap();
        }
        return null;
    }

    public BitmapHolder getBitmapSafe() {
        Bitmap bitmap = null;
        String key = null;
        if (staticThumbDrawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) staticThumbDrawable).getBitmap();
        }
        if (bitmap != null) {
            return new BitmapHolder(bitmap, key);
        }
        return null;
    }

    public Bitmap getThumbBitmap() {
        if (currentThumbDrawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) currentThumbDrawable).getBitmap();
        } else if (staticThumbDrawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) staticThumbDrawable).getBitmap();
        }
        return null;
    }

    public BitmapHolder getThumbBitmapSafe() {
        Bitmap bitmap = null;
        String key = null;
        if (currentThumbDrawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) currentThumbDrawable).getBitmap();
            key = currentThumbKey;
        } else if (staticThumbDrawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) staticThumbDrawable).getBitmap();
        }
        if (bitmap != null) {
            return new BitmapHolder(bitmap, key);
        }
        return null;
    }

    public int getBitmapWidth() {
        Bitmap bitmap = getBitmap();
        if (bitmap == null) {
            if (staticThumbDrawable != null) {
                return staticThumbDrawable.getIntrinsicWidth();
            }
            return 1;
        }
        return imageOrientation % 360 == 0 || imageOrientation % 360 == 180 ? bitmap.getWidth() : bitmap.getHeight();
    }

    public int getBitmapHeight() {
        Bitmap bitmap = getBitmap();
        if (bitmap == null) {
            if (staticThumbDrawable != null) {
                return staticThumbDrawable.getIntrinsicHeight();
            }
            return 1;
        }
        return imageOrientation % 360 == 0 || imageOrientation % 360 == 180 ? bitmap.getHeight() : bitmap.getWidth();
    }

    public void setVisible(boolean value, boolean invalidate) {
        if (isVisible == value) {
            return;
        }
        isVisible = value;
        if (invalidate && parentView != null) {
            if (invalidateAll) {
                parentView.invalidate();
            } else {
                parentView.invalidate(imageX, imageY, imageX + imageW, imageY + imageH);
            }
        }
    }

    public boolean getVisible() {
        return isVisible;
    }

    public void setAlpha(float value) {
        overrideAlpha = value;
    }

    public void setCrossfadeAlpha(byte value) {
        crossfadeAlpha = value;
    }

    public boolean hasImageSet() {
        return currentImageDrawable != null || currentMediaDrawable != null || currentThumbDrawable != null || staticThumbDrawable != null || currentImageKey != null || currentMediaKey != null;
    }

    public boolean hasBitmapImage() {
        return currentImageDrawable != null || currentThumbDrawable != null || staticThumbDrawable != null || currentMediaDrawable != null;
    }

    public boolean hasNotThumb() {
        return currentImageDrawable != null || currentMediaDrawable != null;
    }

    public boolean hasStaticThumb() {
        return staticThumbDrawable != null;
    }

    public void setAspectFit(boolean value) {
        isAspectFit = value;
    }

    public boolean isAspectFit() {
        return isAspectFit;
    }

    public void setImageX(int x) {
        imageX = x;
    }

    public void setImageY(int y) {
        imageY = y;
    }

    public void setImageWidth(int width) {
        imageW = width;
    }

    public void setImageCoords(int x, int y, int width, int height) {
        imageX = x;
        imageY = y;
        imageW = width;
        imageH = height;
    }

    public void setSideClip(float value) {
        sideClip = value;
    }

    public float getCenterX() {
        return imageX + imageW / 2.0f;
    }

    public float getCenterY() {
        return imageY + imageH / 2.0f;
    }

    public int getImageX() {
        return imageX;
    }

    public int getImageX2() {
        return imageX + imageW;
    }

    public int getImageY() {
        return imageY;
    }

    public int getImageY2() {
        return imageY + imageH;
    }

    public int getImageWidth() {
        return imageW;
    }

    public int getImageHeight() {
        return imageH;
    }

    public float getImageAspectRatio() {
        return imageOrientation % 180 != 0 ? drawRegion.height() / drawRegion.width() : drawRegion.width() / drawRegion.height();
    }

    public String getExt() {
        return currentExt;
    }

    public boolean isInsideImage(float x, float y) {
        return x >= imageX && x <= imageX + imageW && y >= imageY && y <= imageY + imageH;
    }

    public RectF getDrawRegion() {
        return drawRegion;
    }

    public int getNewGuid() {
        return ++currentGuid;
    }

    public String getImageKey() {
        return currentImageKey;
    }

    public String getMediaKey() {
        return currentMediaKey;
    }

    public String getThumbKey() {
        return currentThumbKey;
    }

    public int getSize() {
        return currentSize;
    }

    public ImageLocation getMediaLocation() {
        return currentMediaLocation;
    }

    public ImageLocation getImageLocation() {
        return currentImageLocation;
    }

    public ImageLocation getThumbLocation() {
        return currentThumbLocation;
    }

    public String getMediaFilter() {
        return currentMediaFilter;
    }

    public String getImageFilter() {
        return currentImageFilter;
    }

    public String getHttpImageLocation() {
        return currentHttpUrl;
    }

    public String getThumbFilter() {
        return currentThumbFilter;
    }

    public int getCacheType() {
        return currentCacheType;
    }

    public void setForcePreview(boolean value) {
        forcePreview = value;
    }

    public void setForceCrossfade(boolean value) {
        forceCrossfade = value;
    }

    public boolean isForcePreview() {
        return forcePreview;
    }

    public void setRoundRadius(int value) {
        roundRadius = value;
    }

    public void setCurrentAccount(int value) {
        currentAccount = value;
    }

    public int getRoundRadius() {
        return roundRadius;
    }


    protected int getTag(int type) {
        if (type == TYPE_THUMB) {
            return thumbTag;
        } else if (type == TYPE_MEDIA) {
            return mediaTag;
        } else {
            return imageTag;
        }
    }

    protected void setTag(int value, int type) {
        if (type == TYPE_THUMB) {
            thumbTag = value;
        } else if (type == TYPE_MEDIA) {
            mediaTag = value;
        } else {
            imageTag = value;
        }
    }

    public void setParam(int value) {
        param = value;
    }

    public int getParam() {
        return param;
    }

    protected boolean setImageBitmapByKey(Drawable drawable, String key, int type, boolean memCache, int guid) {
        if (drawable == null || key == null || currentGuid != guid) {
            return false;
        }
        if (type == TYPE_IMAGE) {
            if (!key.equals(currentImageKey)) {
                return false;
            }
            currentImageDrawable = drawable;

            if (roundRadius != 0 && drawable instanceof BitmapDrawable) {

                BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
                imageShader = new BitmapShader(bitmapDrawable.getBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);

            } else {
                imageShader = null;
            }

            if (!memCache && !forcePreview || forceCrossfade) {
                boolean allowCorssfade = true;
                if (allowCorssfade && (currentThumbDrawable == null && staticThumbDrawable == null || currentAlpha == 1.0f || forceCrossfade)) {
                    currentAlpha = 0.0f;
                    lastUpdateAlphaTime = System.currentTimeMillis();
                    crossfadeWithThumb = crossfadeImage != null || currentThumbDrawable != null || staticThumbDrawable != null;
                }
            } else {
                currentAlpha = 1.0f;
            }
        } else if (type == TYPE_MEDIA) {
            if (!key.equals(currentMediaKey)) {
                return false;
            }
            currentMediaDrawable = drawable;
            if (roundRadius != 0 && drawable instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
                mediaShader = new BitmapShader(bitmapDrawable.getBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);

            } else {
                mediaShader = null;
            }

            if (currentImageDrawable == null) {
                boolean allowCorssfade = true;
                if (!memCache && !forcePreview || forceCrossfade) {
                    if (currentThumbDrawable == null && staticThumbDrawable == null || currentAlpha == 1.0f || forceCrossfade) {
                        currentAlpha = 0.0f;
                        lastUpdateAlphaTime = System.currentTimeMillis();
                        crossfadeWithThumb = crossfadeImage != null || currentThumbDrawable != null || staticThumbDrawable != null;
                    }
                } else {
                    currentAlpha = 1.0f;
                }
            }
        } else if (type == TYPE_THUMB) {
            if (currentThumbDrawable != null) {
                return false;
            }
            if (!key.equals(currentThumbKey)) {
                return false;
            }
            ImageLoader.getInstance().incrementUseCount(currentThumbKey);

            currentThumbDrawable = drawable;

//            if (roundRadius != 0 && drawable instanceof BitmapDrawable) {
//
//                BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
//                thumbShader = new BitmapShader(bitmapDrawable.getBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
//
//            } else {
//                thumbShader = null;
//            }
//            {
//                currentAlpha = 1.0f;
//            }
        }
        if (parentView != null) {
            if (invalidateAll) {
                parentView.invalidate();
            } else {
                parentView.invalidate(imageX, imageY, imageX + imageW, imageY + imageH);
            }
        }
        if (delegate != null) {
            delegate.didSetImage(this, currentImageDrawable != null || currentThumbDrawable != null || staticThumbDrawable != null || currentMediaDrawable != null, currentImageDrawable == null && currentMediaDrawable == null);
        }
        return true;
    }

    private void recycleBitmap(String newKey, int type) {
        String key;
        Drawable image;
        if (type == TYPE_MEDIA) {
            key = currentMediaKey;
            image = currentMediaDrawable;
        } else if (type == TYPE_CROSSFDADE) {
            key = crossfadeKey;
            image = crossfadeImage;
        } else if (type == TYPE_THUMB) {
            key = currentThumbKey;
            image = currentThumbDrawable;
        } else {
            key = currentImageKey;
            image = currentImageDrawable;
        }
        if (key != null && key.startsWith("-")) {
            String replacedKey = ImageLoader.getInstance().getReplacedKey(key);
            if (replacedKey != null) {
                key = replacedKey;
            }
        }
        String replacedKey = ImageLoader.getInstance().getReplacedKey(key);
        if (key != null && (newKey == null || !newKey.equals(key)) && image != null) {
            if (image instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) image).getBitmap();
                boolean canDelete = ImageLoader.getInstance().decrementUseCount(key);
                if (!ImageLoader.getInstance().isInMemCache(key, false)) {
                    if (canDelete) {
                        bitmap.recycle();
                    }
                }
            }
        }
        if (type == TYPE_MEDIA) {
            currentMediaKey = null;
            currentMediaDrawable = null;
        } else if (type == TYPE_CROSSFDADE) {
            crossfadeKey = null;
            crossfadeImage = null;
        } else if (type == TYPE_THUMB) {
            currentThumbDrawable = null;
            currentThumbKey = null;
        } else {
            currentImageDrawable = null;
            currentImageKey = null;
        }
    }

    public boolean draw(Canvas canvas) {
        try {
            Drawable drawable = null;

            int orientation = 0;
            BitmapShader shaderToUse = null;
           if (crossfadeImage != null && !crossfadingWithThumb) {
                drawable = crossfadeImage;
                shaderToUse = crossfadeShader;
                orientation = imageOrientation;
            } else if (staticThumbDrawable instanceof BitmapDrawable) {
                drawable = staticThumbDrawable;
                shaderToUse = thumbShader;
                orientation = thumbOrientation;
            } else if (currentThumbDrawable != null) {
                drawable = currentThumbDrawable;
                shaderToUse = thumbShader;
                orientation = thumbOrientation;
            }
            if (drawable != null) {
                if (crossfadeAlpha != 0) {
                     {
                        if (crossfadeWithThumb && currentAlpha != 1.0f) {
                            Drawable thumbDrawable = null;
                            BitmapShader thumbShaderToUse = null;
                            if (drawable == currentImageDrawable || drawable == currentMediaDrawable) {
                                if (crossfadeImage != null) {
                                    thumbDrawable = crossfadeImage;
                                    thumbShaderToUse = crossfadeShader;
                                } else if (currentThumbDrawable != null) {
                                    thumbDrawable = currentThumbDrawable;
                                    thumbShaderToUse = thumbShader;
                                } else if (staticThumbDrawable != null) {
                                    thumbDrawable = staticThumbDrawable;
                                    thumbShaderToUse = thumbShader;
                                }
                            } else if (drawable == currentThumbDrawable || drawable == crossfadeImage) {
                                if (staticThumbDrawable != null) {
                                    thumbDrawable = staticThumbDrawable;
                                    thumbShaderToUse = thumbShader;
                                }
                            } else if (drawable == staticThumbDrawable) {
                                if (crossfadeImage != null) {
                                    thumbDrawable = crossfadeImage;
                                    thumbShaderToUse = crossfadeShader;
                                }
                            }
                            if (thumbDrawable != null) {
                                drawDrawable(canvas, thumbDrawable, (int) (overrideAlpha * 255), thumbShaderToUse, thumbOrientation);
                            }
                        }
                        drawDrawable(canvas, drawable, (int) (overrideAlpha * currentAlpha * 255), shaderToUse, orientation);
                    }
                } else {
                    drawDrawable(canvas, drawable, (int) (overrideAlpha * 255), shaderToUse, orientation);
                }

                return true;
            } else if (staticThumbDrawable != null) {
                drawDrawable(canvas, staticThumbDrawable, (int) (overrideAlpha * 255), null, thumbOrientation);

                return true;
            }
        } catch (Exception e) {

        }
        return false;
    }

    private void drawDrawable(Canvas canvas, Drawable drawable, int alpha, BitmapShader shader, int orientation) {
        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;

            Paint paint;
            if (shader != null) {
                paint = roundPaint;
            } else {
                paint = bitmapDrawable.getPaint();
            }
            boolean hasFilter = paint != null && paint.getColorFilter() != null;
            if (hasFilter && isPressed == 0) {
                if (shader != null) {
                    roundPaint.setColorFilter(null);
                } else if (staticThumbDrawable != drawable) {
                    bitmapDrawable.setColorFilter(null);
                }
            } else if (!hasFilter && isPressed != 0) {
                if (isPressed == 1) {
                    if (shader != null) {
                        roundPaint.setColorFilter(selectedColorFilter);
                    } else {
                        bitmapDrawable.setColorFilter(selectedColorFilter);
                    }
                } else {
                    if (shader != null) {
                        roundPaint.setColorFilter(selectedGroupColorFilter);
                    } else {
                        bitmapDrawable.setColorFilter(selectedGroupColorFilter);
                    }
                }
            }
            if (colorFilter != null) {
                if (shader != null) {
                    roundPaint.setColorFilter(colorFilter);
                } else {
                    bitmapDrawable.setColorFilter(colorFilter);
                }
            }
            int bitmapW;
            int bitmapH;
            {
                if (orientation % 360 == 90 || orientation % 360 == 270) {
                    bitmapW = bitmapDrawable.getBitmap().getHeight();
                    bitmapH = bitmapDrawable.getBitmap().getWidth();
                } else {
                    bitmapW = bitmapDrawable.getBitmap().getWidth();
                    bitmapH = bitmapDrawable.getBitmap().getHeight();
                }
            }
            float realImageW = imageW - sideClip * 2;
            float realImageH = imageH - sideClip * 2;
            float scaleW = imageW == 0 ? 1.0f : (bitmapW / realImageW);
            float scaleH = imageH == 0 ? 1.0f : (bitmapH / realImageH);

            if (shader != null) {
                if (isAspectFit) {
                    float scale = Math.max(scaleW, scaleH);
                    bitmapW /= scale;
                    bitmapH /= scale;
                    drawRegion.set(imageX + (imageW - bitmapW) / 2, imageY + (imageH - bitmapH) / 2, imageX + (imageW + bitmapW) / 2, imageY + (imageH + bitmapH) / 2);

                    if (isVisible) {
                        roundPaint.setShader(shader);
                        shaderMatrix.reset();
                        shaderMatrix.setTranslate(drawRegion.left, drawRegion.top);
                        shaderMatrix.preScale(1.0f / scale, 1.0f / scale);

                        shader.setLocalMatrix(shaderMatrix);
                        roundPaint.setAlpha(alpha);
                        roundRect.set(drawRegion);
                        canvas.drawRoundRect(roundRect, roundRadius, roundRadius, roundPaint);
                    }
                } else {
                    roundPaint.setShader(shader);
                    float scale = 1.0f / Math.min(scaleW, scaleH);
                    roundRect.set(imageX + sideClip, imageY + sideClip, imageX + imageW - sideClip, imageY + imageH - sideClip);
                    shaderMatrix.reset();
                    if (Math.abs(scaleW - scaleH) > 0.0005f) {
                        if (bitmapW / scaleH > realImageW) {
                            bitmapW /= scaleH;
                            drawRegion.set(imageX - (bitmapW - realImageW) / 2, imageY, imageX + (bitmapW + realImageW) / 2, imageY + realImageH);
                        } else {
                            bitmapH /= scaleW;
                            drawRegion.set(imageX, imageY - (bitmapH - realImageH) / 2, imageX + realImageW, imageY + (bitmapH + realImageH) / 2);
                        }
                    } else {
                        drawRegion.set(imageX, imageY, imageX + realImageW, imageY + realImageH);
                    }
                    if (isVisible) {
                        shaderMatrix.reset();
                        shaderMatrix.setTranslate(drawRegion.left + sideClip, drawRegion.top + sideClip);
                        if (orientation == 90) {
                            shaderMatrix.preRotate(90);
                            shaderMatrix.preTranslate(0, -drawRegion.width());
                        } else if (orientation == 180) {
                            shaderMatrix.preRotate(180);
                            shaderMatrix.preTranslate(-drawRegion.width(), -drawRegion.height());
                        } else if (orientation == 270) {
                            shaderMatrix.preRotate(270);
                            shaderMatrix.preTranslate(-drawRegion.height(), 0);
                        }
                        shaderMatrix.preScale(scale, scale);

                        shader.setLocalMatrix(shaderMatrix);
                        roundPaint.setAlpha(alpha);
                        canvas.drawRoundRect(roundRect, roundRadius, roundRadius, roundPaint);
                    }
                }
            } else {
                if (isAspectFit) {
                    float scale = Math.max(scaleW, scaleH);
                    canvas.save();
                    bitmapW /= scale;
                    bitmapH /= scale;
                    drawRegion.set(imageX + (imageW - bitmapW) / 2.0f, imageY + (imageH - bitmapH) / 2.0f, imageX + (imageW + bitmapW) / 2.0f, imageY + (imageH + bitmapH) / 2.0f);
                    bitmapDrawable.setBounds((int) drawRegion.left, (int) drawRegion.top, (int) drawRegion.right, (int) drawRegion.bottom);

                    if (isVisible) {
                        try {
                            bitmapDrawable.setAlpha(alpha);
                            bitmapDrawable.draw(canvas);
                        } catch (Exception e) {
                        }
                    }
                    canvas.restore();
                } else {
                    if (Math.abs(scaleW - scaleH) > 0.00001f) {
                        canvas.save();
                        canvas.clipRect(imageX, imageY, imageX + imageW, imageY + imageH);

                        if (orientation % 360 != 0) {
                            if (centerRotation) {
                                canvas.rotate(orientation, imageW / 2, imageH / 2);
                            } else {
                                canvas.rotate(orientation, 0, 0);
                            }
                        }

                        if (bitmapW / scaleH > imageW) {
                            bitmapW /= scaleH;
                            drawRegion.set(imageX - (bitmapW - imageW) / 2.0f, imageY, imageX + (bitmapW + imageW) / 2.0f, imageY + imageH);
                        } else {
                            bitmapH /= scaleW;
                            drawRegion.set(imageX, imageY - (bitmapH - imageH) / 2.0f, imageX + imageW, imageY + (bitmapH + imageH) / 2.0f);
                        }

                        if (orientation % 360 == 90 || orientation % 360 == 270) {
                            float width = drawRegion.width() / 2;
                            float height = drawRegion.height() / 2;
                            float centerX = drawRegion.centerX();
                            float centerY = drawRegion.centerY();
                            bitmapDrawable.setBounds((int) (centerX - height), (int) (centerY - width), (int) (centerX + height), (int) (centerY + width));
                        } else {
                            bitmapDrawable.setBounds((int) drawRegion.left, (int) drawRegion.top, (int) drawRegion.right, (int) drawRegion.bottom);
                        }
                        if (isVisible) {
                            try {
                                bitmapDrawable.setAlpha(alpha);
                                bitmapDrawable.draw(canvas);
                            } catch (Exception e) {
                            }
                        }

                        canvas.restore();
                    } else {
                        canvas.save();
                        if (orientation % 360 != 0) {
                            if (centerRotation) {
                                canvas.rotate(orientation, imageW / 2, imageH / 2);
                            } else {
                                canvas.rotate(orientation, 0, 0);
                            }
                        }
                        drawRegion.set(imageX, imageY, imageX + imageW, imageY + imageH);
                        if (orientation % 360 == 90 || orientation % 360 == 270) {
                            float width = drawRegion.width() / 2;
                            float height = drawRegion.height() / 2;
                            float centerX = drawRegion.centerX();
                            float centerY = drawRegion.centerY();
                            bitmapDrawable.setBounds((int) (centerX - height), (int) (centerY - width), (int) (centerX + height), (int) (centerY + width));
                        } else {
                            bitmapDrawable.setBounds((int) drawRegion.left, (int) drawRegion.top, (int) drawRegion.right, (int) drawRegion.bottom);
                        }
                        if (isVisible) {
                            try {
                                bitmapDrawable.setAlpha(alpha);
                                bitmapDrawable.draw(canvas);
                            } catch (Exception e) {

                            }
                        }
                        canvas.restore();
                    }
                }
            }
        } else {
            drawRegion.set(imageX, imageY, imageX + imageW, imageY + imageH);
            drawable.setBounds((int) drawRegion.left, (int) drawRegion.top, (int) drawRegion.right, (int) drawRegion.bottom);
            if (isVisible) {
                try {
                    drawable.setAlpha(alpha);
                    drawable.draw(canvas);
                } catch (Exception e) {
                }
            }
        }
    }
}
