/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package com.example.custom;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

public class PhotoAttachPhotoCell extends FrameLayout {

    private BackupImageView imageView;
    private FrameLayout container;
    private FrameLayout checkFrame;
    private CheckBox2 checkBox;
    private TextView videoTextView;
    private FrameLayout videoInfoContainer;
    private AnimatorSet animatorSet;
    private boolean isLast;
    private boolean pressed;
    private static Rect rect = new Rect();
    private PhotoAttachPhotoCellDelegate delegate;
    private boolean itemSizeChanged;
    private boolean needCheckShow;
    private int itemSize;
    private boolean isVertical;
    private boolean zoomOnSelect = true;

    private MediaController.PhotoEntry photoEntry;

    private Paint backgroundPaint = new Paint();
    private AnimatorSet animator;

    public interface PhotoAttachPhotoCellDelegate {
        void onCheckClick(PhotoAttachPhotoCell v);
    }

    public PhotoAttachPhotoCell(Context context) {
        super(context);

        setWillNotDraw(false);

        container = new FrameLayout(context);
        addView(container, LayoutHelper.createFrame(80, 80));

        imageView = new BackupImageView(context);
        container.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        videoInfoContainer = new FrameLayout(context) {

            private RectF rect = new RectF();

            @Override
            protected void onDraw(Canvas canvas) {
                rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                Paint chat_timeBackgroundPaint =  new Paint(Paint.ANTI_ALIAS_FLAG);
                chat_timeBackgroundPaint.setColor(0x66000000);
                canvas.drawRoundRect(rect, AndroidUtilities.dp(4), AndroidUtilities.dp(4), chat_timeBackgroundPaint);
            }
        };
        videoInfoContainer.setWillNotDraw(false);
        videoInfoContainer.setPadding(AndroidUtilities.dp(5), 0, AndroidUtilities.dp(5), 0);
        container.addView(videoInfoContainer, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 17, Gravity.BOTTOM | Gravity.LEFT, 4, 0, 0, 4));

        ImageView imageView1 = new ImageView(context);
        imageView1.setImageResource(R.drawable.ic_launcher_background);
        videoInfoContainer.addView(imageView1, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        videoTextView = new TextView(context);
        videoTextView.setTextColor(0xffffffff);
//        videoTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        videoTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        videoTextView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        videoInfoContainer.addView(videoTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 13, -0.7f, 0, 0));

        checkBox = new CheckBox2(context, 24);
        checkBox.setDrawBackgroundAsArc(7);
        checkBox.setColor(0xff39b2f7, 0x08000000, 0xffffffff);
        addView(checkBox, LayoutHelper.createFrame(26, 26, Gravity.LEFT | Gravity.TOP, 52, 4, 0, 0));
        checkBox.setVisibility(VISIBLE);
        setFocusable(true);

        checkFrame = new FrameLayout(context);
        addView(checkFrame, LayoutHelper.createFrame(42, 42, Gravity.LEFT | Gravity.TOP, 38, 0, 0, 0));

        itemSize = AndroidUtilities.dp(80);
    }

    public void setIsVertical(boolean value) {
        isVertical = value;
    }

    public void setItemSize(int size) {
        itemSize = size;

        LayoutParams layoutParams = (LayoutParams) container.getLayoutParams();
        layoutParams.width = layoutParams.height = itemSize;

        layoutParams = (LayoutParams) checkFrame.getLayoutParams();
        layoutParams.gravity = Gravity.RIGHT | Gravity.TOP;
        layoutParams.leftMargin = 0;

        layoutParams = (LayoutParams) checkBox.getLayoutParams();
        layoutParams.gravity = Gravity.RIGHT | Gravity.TOP;
        layoutParams.leftMargin = 0;
        layoutParams.rightMargin = layoutParams.topMargin = AndroidUtilities.dp(5);
        checkBox.setDrawBackgroundAsArc(6);

        itemSizeChanged = true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (itemSizeChanged) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(itemSize, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(itemSize + AndroidUtilities.dp(5), MeasureSpec.EXACTLY));
        } else {
            if (isVertical) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(80), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(80 + (isLast ? 0 : 6)), MeasureSpec.EXACTLY));
            } else {
                super.onMeasure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(80 + (isLast ? 0 : 6)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(80), MeasureSpec.EXACTLY));
            }
        }
    }

    public MediaController.PhotoEntry getPhotoEntry() {
        return photoEntry;
    }

    public BackupImageView getImageView() {
        return imageView;
    }

    public float getScale() {
        return container.getScaleX();
    }

    public CheckBox2 getCheckBox() {
        return checkBox;
    }

    public FrameLayout getCheckFrame() {
        return checkFrame;
    }

    public View getVideoInfoContainer() {
        return videoInfoContainer;
    }

    public void setPhotoEntry(MediaController.PhotoEntry entry, boolean needCheckShow, boolean last) {
        pressed = false;
        photoEntry = entry;
        isLast = last;
        if (photoEntry.isVideo) {
            imageView.setOrientation(0, true);
            videoInfoContainer.setVisibility(VISIBLE);
            int minutes = photoEntry.duration / 60;
            int seconds = photoEntry.duration - minutes * 60;
            videoTextView.setText(String.format("%d:%02d", minutes, seconds));
        } else {
            videoInfoContainer.setVisibility(INVISIBLE);
        }
        if (photoEntry.thumbPath != null) {
            imageView.setImage(photoEntry.thumbPath, null, getContext().getResources().getDrawable(R.drawable.ic_launcher_background));
        } else if (photoEntry.path != null) {
            if (photoEntry.isVideo) {
                imageView.setImage("vthumb://" + photoEntry.imageId + ":" + photoEntry.path, null, getContext().getResources().getDrawable(R.drawable.ic_launcher_background));
            } else {
                imageView.setOrientation(photoEntry.orientation, true);
                imageView.setImage("thumb://" + photoEntry.imageId + ":" + photoEntry.path, null, getContext().getResources().getDrawable(R.drawable.ic_launcher_background));
            }
        } else {
            imageView.setImageDrawable(getContext().getResources().getDrawable(R.drawable.ic_launcher_background));
        }
//        boolean showing = needCheckShow && PhotoViewer.isShowingImage(photoEntry.path);
        boolean showing = needCheckShow ;
        imageView.getImageReceiver().setVisible(!showing, true);
        checkBox.setAlpha(showing ? 0.0f : 1.0f);
        videoInfoContainer.setAlpha(showing ? 0.0f : 1.0f);
        requestLayout();
    }


    public boolean isChecked() {
        return checkBox.isChecked();
    }

    public void setChecked(int num, boolean checked, boolean animated) {
        checkBox.setChecked(num, checked, animated);
        if (itemSizeChanged) {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (animated) {
                animator = new AnimatorSet();
                animator.playTogether(
                        ObjectAnimator.ofFloat(container, View.SCALE_X, checked ? 0.787f : 1.0f),
                        ObjectAnimator.ofFloat(container, View.SCALE_Y, checked ? 0.787f : 1.0f));
                animator.setDuration(200);
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (animator != null && animator.equals(animation)) {
                            animator = null;
                            if (!checked) {
                                setBackgroundColor(0);
                            }
                        }
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        if (animator != null && animator.equals(animation)) {
                            animator = null;
                        }
                    }
                });
                animator.start();
            } else {
                container.setScaleX(checked ? 0.787f : 1.0f);
                container.setScaleY(checked ? 0.787f : 1.0f);
            }
        }
    }

    public void setNum(int num) {
        checkBox.setNum(num);
    }

    public void setOnCheckClickLisnener(OnClickListener onCheckClickLisnener) {
        checkFrame.setOnClickListener(onCheckClickLisnener);
    }

    public void setDelegate(PhotoAttachPhotoCellDelegate delegate) {
        this.delegate = delegate;
    }

    public void callDelegate() {
        delegate.onCheckClick(this);
    }

    public void showImage() {
        imageView.getImageReceiver().setVisible(true, true);
    }

    public void showCheck(boolean show) {
        if (show && checkBox.getAlpha() == 1 || !show && checkBox.getAlpha() == 0) {
            return;
        }
        if (animatorSet != null) {
            animatorSet.cancel();
            animatorSet = null;
        }
        animatorSet = new AnimatorSet();
        animatorSet.setInterpolator(new DecelerateInterpolator());
        animatorSet.setDuration(180);
        animatorSet.playTogether(
                ObjectAnimator.ofFloat(videoInfoContainer, View.ALPHA, show ? 1.0f : 0.0f),
                ObjectAnimator.ofFloat(checkBox, View.ALPHA, show ? 1.0f : 0.0f));
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation.equals(animatorSet)) {
                    animatorSet = null;
                }
            }
        });
        animatorSet.start();
    }

    @Override
    public void clearAnimation() {
        super.clearAnimation();
        if (animator != null) {
            animator.cancel();
            animator = null;

            container.setScaleX(checkBox.isChecked() ? 0.787f : 1.0f);
            container.setScaleY(checkBox.isChecked() ? 0.787f : 1.0f);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean result = false;

        checkFrame.getHitRect(rect);
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (rect.contains((int) event.getX(), (int) event.getY())) {
                pressed = true;
                invalidate();
                result = true;
            }
        } else if (pressed) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                getParent().requestDisallowInterceptTouchEvent(true);
                pressed = false;
                playSoundEffect(SoundEffectConstants.CLICK);
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                delegate.onCheckClick(this);
                invalidate();
            } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                pressed = false;
                invalidate();
            } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                if (!(rect.contains((int) event.getX(), (int) event.getY()))) {
                    pressed = false;
                    invalidate();
                }
            }
        }
        if (!result) {
            result = super.onTouchEvent(event);
        }

        return result;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (checkBox.isChecked() || container.getScaleX() != 1.0f || !imageView.getImageReceiver().hasNotThumb() || imageView.getImageReceiver().getCurrentAlpha() != 1.0f || photoEntry != null) {
            backgroundPaint.setColor(0x08000000);
            canvas.drawRect(0, 0, imageView.getMeasuredWidth(), imageView.getMeasuredHeight(), backgroundPaint);
        }
    }


    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setEnabled(true);
        if (photoEntry != null && photoEntry.isVideo) {
            info.setText(getContext().getString(R.string.AttachVideo)+ ", " + photoEntry.duration);
        } else {
            info.setText(getContext().getString(R.string.AttachPhoto));
        }
        if (checkBox.isChecked()) {
            info.setSelected(true);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.acc_action_open_photo, getContext().getString(R.string.Open)));
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == R.id.acc_action_open_photo) {
            View parent = (View) getParent();
            parent.dispatchTouchEvent(MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, getLeft(), getTop() + getHeight() - 1, 0));
            parent.dispatchTouchEvent(MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, getLeft(), getTop() + getHeight() - 1, 0));
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
