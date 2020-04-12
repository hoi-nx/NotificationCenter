package com.example.custom;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Toast;

import com.example.custom.camera.CameraController;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements NotificationCenter.NotificationCenterDelegate {


    private boolean deviceHasGoodCamera;
    private boolean noCameraPermissions;
    private boolean noGalleryPermissions;
    protected ColorDrawable backDrawable = new ColorDrawable(0xff000000);

    private RecyclerView gridView;
    private GridLayoutManager layoutManager;

    private int itemSize = AndroidUtilities.dp(80);
    private int itemsPerRow = 3;

    private MediaController.AlbumEntry selectedAlbumEntry;
    private MediaController.AlbumEntry galleryAlbumEntry;

    private PhotoAttachAdapter adapter;
    private float cornerRadius = 1.0f;
    private boolean mediaEnabled = true;
    private static ArrayList<Object> cameraPhotos = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.albumsDidLoaded);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.cameraInitied);

        MediaController.getInstance().checkGallery();
        checkCamera(true);
        loadGalleryPhotos();

        if (AndroidUtilities.isTablet()) {
            itemsPerRow = 4;
        } else if (AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
            itemsPerRow = 4;
        } else {
            itemsPerRow = 3;
        }

        if (Build.VERSION.SDK_INT >= 23) {
            noGalleryPermissions = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
        }
        if (galleryAlbumEntry != null) {
            for (int a = 0; a < Math.min(100, galleryAlbumEntry.photos.size()); a++) {
                MediaController.PhotoEntry photoEntry = galleryAlbumEntry.photos.get(a);
                photoEntry.reset();
            }
        }

        gridView = findViewById(R.id.gridView);

        gridView.setClipToPadding(false);
        gridView.setItemAnimator(null);
        gridView.setLayoutAnimation(null);
        gridView.setVerticalScrollBarEnabled(false);
        gridView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
            }
        });

        layoutManager = new GridLayoutManager(this, itemSize) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        };

//        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
//            @Override
//            public int getSpanSize(int position) {
//                if (position == adapter.itemsCount - 1) {
//                    return layoutManager.getSpanCount();
//                }
//                return itemSize + (position % itemsPerRow != itemsPerRow - 1 ? AndroidUtilities.dp(5) : 0);
//            }
//        });

        gridView.setLayoutManager(layoutManager);
        gridView.setAdapter(adapter = new PhotoAttachAdapter(this, true));

    }


    public void checkCamera(boolean request) {

        boolean old = deviceHasGoodCamera;
        boolean old2 = noCameraPermissions;
        if (Build.VERSION.SDK_INT >= 23) {
            if (noCameraPermissions = (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)) {
                if (request) {
                    try {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, 17);
                    } catch (Exception ignore) {

                    }
                }
                deviceHasGoodCamera = false;
            } else {
                deviceHasGoodCamera = CameraController.getInstance().isCameraInitied();
            }
        } else {
            deviceHasGoodCamera = CameraController.getInstance().isCameraInitied();
        }

//        if ((old != deviceHasGoodCamera || old2 != noCameraPermissions) && adapter != null) {
//            adapter.notifyDataSetChanged();
//        }
//        if (isShowing() && deviceHasGoodCamera && baseFragment != null && backDrawable.getAlpha() != 0 && !cameraOpened) {
//            showCamera();
//        }
    }

    @Override
    public void didReceivedNotification(int id, Object... args) {
        if (id == NotificationCenter.albumsDidLoaded) {
//            if (adapter != null) {
            galleryAlbumEntry = MediaController.allMediaAlbumEntry;
            if (selectedAlbumEntry == null) {
                selectedAlbumEntry = galleryAlbumEntry;
            } else {
                for (int a = 0; a < MediaController.allMediaAlbums.size(); a++) {
                    MediaController.AlbumEntry entry = MediaController.allMediaAlbums.get(a);
                    if (entry.bucketId == selectedAlbumEntry.bucketId && entry.videoOnly == selectedAlbumEntry.videoOnly) {
                        selectedAlbumEntry = entry;
                        break;
                    }
                }
            }
//                loading = false;
//                progressView.showTextView();
                adapter.notifyDataSetChanged();
//                cameraAttachAdapter.notifyDataSetChanged();
//                if (!selectedPhotosOrder.isEmpty() && galleryAlbumEntry != null) {
//                    for (int a = 0, N = selectedPhotosOrder.size(); a < N; a++) {
//                        int imageId = (Integer) selectedPhotosOrder.get(a);
//                        MediaController.PhotoEntry entry = galleryAlbumEntry.photosByIds.get(imageId);
//                        if (entry != null) {
//                            selectedPhotos.put(imageId, entry);
//                        }
//                    }
//                }
//                updateAlbumsDropDown();
//            }
        }
    }

    private class PhotoAttachAdapter extends RecyclerView.Adapter {

        private Context mContext;
        private boolean needCamera;
        private ArrayList<RecyclerView.ViewHolder> viewsCache = new ArrayList<>(8);
        private int itemsCount;

        public PhotoAttachAdapter(Context context, boolean camera) {
            mContext = context;
            needCamera = camera;
            for (int a = 0; a < 8; a++) {
                viewsCache.add(createHolder());
            }
        }

        public RecyclerView.ViewHolder createHolder() {
            PhotoAttachPhotoCell cell = new PhotoAttachPhotoCell(mContext);
            if (Build.VERSION.SDK_INT >= 21 && this == adapter) {
                cell.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        PhotoAttachPhotoCell photoCell = (PhotoAttachPhotoCell) view;
                        int position = (Integer) photoCell.getTag();
                        if (needCamera && selectedAlbumEntry == galleryAlbumEntry) {
                            position++;
                        }
                        if (position == 0) {
                            int rad = AndroidUtilities.dp(8 * cornerRadius);
                            outline.setRoundRect(0, 0, view.getMeasuredWidth() + rad, view.getMeasuredHeight() + rad, rad);
                        } else if (position == itemsPerRow - 1) {
                            int rad = AndroidUtilities.dp(8 * cornerRadius);
                            outline.setRoundRect(-rad, 0, view.getMeasuredWidth(), view.getMeasuredHeight() + rad, rad);
                        } else {
                            outline.setRect(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
                        }
                    }
                });
                cell.setClipToOutline(true);
            }
            cell.setDelegate(v -> {
                if (!mediaEnabled) {
                    return;
                }
                int index = (Integer) v.getTag();
                MediaController.PhotoEntry photoEntry = v.getPhotoEntry();
            });
            return new RecyclerView.ViewHolder(cell) {
                @Override
                public String toString() {
                    return super.toString();
                }
            };
        }

//        private MediaController.PhotoEntry getPhoto(int position) {
//            if (needCamera && selectedAlbumEntry == galleryAlbumEntry) {
//                position--;
//            }
//            return getPhotoEntryAtPosition(position);
//        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0: {
                    if (needCamera && selectedAlbumEntry == galleryAlbumEntry) {
                        position--;
                    }
                    PhotoAttachPhotoCell cell = (PhotoAttachPhotoCell) holder.itemView;
                    if (this == adapter) {
                        cell.setItemSize(itemSize);
                    } else {
//                        cell.setIsVertical(cameraPhotoLayoutManager.getOrientation() == LinearLayoutManager.VERTICAL);
                    }

                    MediaController.PhotoEntry photoEntry = getPhotoEntryAtPosition(position);
                    cell.setPhotoEntry(photoEntry, needCamera && selectedAlbumEntry == galleryAlbumEntry, position == getItemCount() - 1);

                    cell.getImageView().setTag(position);
                    cell.setTag(position);
                    break;
                }
                case 1: {
                    PhotoAttachCameraCell cameraCell = (PhotoAttachCameraCell) holder.itemView;
//                    if (cameraView != null && cameraView.isInitied()) {
//                        cameraCell.setVisibility(View.INVISIBLE);
//                    } else {
//                        cameraCell.setVisibility(View.VISIBLE);
//                    }
                    cameraCell.setItemSize(itemSize);
                    break;
                }
                case 3: {
                    PhotoAttachPermissionCell cell = (PhotoAttachPermissionCell) holder.itemView;
                    cell.setItemSize(itemSize);
                    cell.setType(needCamera && noCameraPermissions && position == 0 ? 0 : 1);
                    break;
                }
            }
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            RecyclerView.ViewHolder holder = null;
            switch (viewType) {
                case 0:
                    if (!viewsCache.isEmpty()) {
                        holder = viewsCache.get(0);
                        viewsCache.remove(0);
                    } else {
                        holder = createHolder();
                    }
                    break;
                case 1:
                    PhotoAttachCameraCell cameraCell = new PhotoAttachCameraCell(mContext);
                    if (Build.VERSION.SDK_INT >= 21) {
                        cameraCell.setOutlineProvider(new ViewOutlineProvider() {
                            @Override
                            public void getOutline(View view, Outline outline) {
                                int rad = AndroidUtilities.dp(8 * cornerRadius);
                                outline.setRoundRect(0, 0, view.getMeasuredWidth() + rad, view.getMeasuredHeight() + rad, rad);
                            }
                        });
                        cameraCell.setClipToOutline(true);
                    }
                    holder = new RecyclerView.ViewHolder(cameraCell) {

                    };
                    break;
                case 2:
                    holder = new RecyclerView.ViewHolder(new View(mContext)) {

                    };
                    break;
                case 3:
                default:
                    holder = new RecyclerView.ViewHolder(new PhotoAttachPermissionCell(mContext)) {

                    };
                    break;
            }
            return holder;
        }

        @Override
        public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
//            if (holder.itemView instanceof PhotoAttachCameraCell) {
//                PhotoAttachCameraCell cell = (PhotoAttachCameraCell) holder.itemView;
//                cell.updateBitmap();
//            }
        }

        @Override
        public int getItemCount() {
            if (!mediaEnabled) {
                return 1;
            }
            int count = 0;
            if (needCamera && selectedAlbumEntry == galleryAlbumEntry) {
                count++;
            }
            if (noGalleryPermissions && this == adapter) {
                count++;
            }
            count += cameraPhotos.size();
            if (selectedAlbumEntry != null) {
                count += selectedAlbumEntry.photos.size();
            }
            if (this == adapter) {
                count++;
            }
            return itemsCount = count;
        }

        @Override
        public int getItemViewType(int position) {
            if (!mediaEnabled) {
                return 2;
            }
            if (needCamera && position == 0 && selectedAlbumEntry == galleryAlbumEntry) {
                if (noCameraPermissions) {
                    return 3;
                } else {
                    return 1;
                }
            }
            if (this == adapter && position == itemsCount - 1) {
                return 2;
            } else if (noGalleryPermissions) {
                return 3;
            }
            return 0;
        }


    }

    public void checkStorage() {
        if (noGalleryPermissions && Build.VERSION.SDK_INT >= 23) {
            noGalleryPermissions = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
            if (!noGalleryPermissions) {
                loadGalleryPhotos();
            }

        }
    }

    private MediaController.PhotoEntry getPhotoEntryAtPosition(int position) {
        if (position < 0) {
            return null;
        }
        int cameraCount = cameraPhotos.size();
        if (position < cameraCount) {
            return (MediaController.PhotoEntry) cameraPhotos.get(position);
        }
        position -= cameraCount;
        if (position < MediaController.allMediaAlbumEntry.photos.size()) {
            return MediaController.allMediaAlbumEntry.photos.get(position);
        }
        return null;
    }

    public void loadGalleryPhotos() {
        MediaController.AlbumEntry albumEntry;
        albumEntry = MediaController.allMediaAlbumEntry;
        if (albumEntry == null && Build.VERSION.SDK_INT >= 21) {
            MediaController.loadGalleryPhotosAlbums(0);
        }
    }
}
