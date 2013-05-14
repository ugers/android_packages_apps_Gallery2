/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.Video;
import android.provider.MediaStore.Video.VideoColumns;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.camera.Storage;
import com.android.camera.ui.FilmStripView;
import com.android.camera.ui.FilmStripView.ImageData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * A FilmStripDataProvider that provide data in the camera folder.
 *
 * The given view for camera preview won't be added until the preview info
 * has been set by setCameraPreviewInfo(int, int).
 */
public class CameraDataAdapter implements FilmStripView.DataAdapter {
    private static final String TAG = CameraDataAdapter.class.getSimpleName();

    private static final int DEFAULT_DECODE_SIZE = 3000;
    private static final String[] CAMERA_PATH = { Storage.DIRECTORY + "%" };

    private List<LocalData> mImages;

    private Listener mListener;
    private View mCameraPreviewView;
    private Drawable mPlaceHolder;

    private int mSuggestedWidth = DEFAULT_DECODE_SIZE;
    private int mSuggestedHeight = DEFAULT_DECODE_SIZE;

    public CameraDataAdapter(Drawable placeHolder) {
        mPlaceHolder = placeHolder;
    }

    public void setCameraPreviewInfo(View cameraPreview, int width, int height) {
        mCameraPreviewView = cameraPreview;
        addOrReplaceCameraData(buildCameraImageData(width, height));
    }

    public void requestLoad(ContentResolver resolver) {
        QueryTask qtask = new QueryTask();
        qtask.execute(resolver);
    }

    @Override
    public int getTotalNumber() {
        if (mImages == null) return 0;
        return mImages.size();
    }

    @Override
    public ImageData getImageData(int id) {
        if (mImages == null || id >= mImages.size()) return null;
        return mImages.get(id);
    }

    @Override
    public void suggestSize(int w, int h) {
        if (w <= 0 || h <= 0) {
            mSuggestedWidth  = mSuggestedHeight = DEFAULT_DECODE_SIZE;
        } else {
            mSuggestedWidth = (w < DEFAULT_DECODE_SIZE ? w : DEFAULT_DECODE_SIZE);
            mSuggestedHeight = (h < DEFAULT_DECODE_SIZE ? h : DEFAULT_DECODE_SIZE);
        }
    }

    @Override
    public View getView(Context c, int dataID) {
        if (mImages == null) return null;
        if (dataID >= mImages.size() || dataID < 0) {
            return null;
        }

        return mImages.get(dataID).getView(
                c, mSuggestedWidth, mSuggestedHeight, mPlaceHolder);
    }

    @Override
    public void setListener(Listener listener) {
        mListener = listener;
        if (mImages != null) mListener.onDataLoaded();
    }

    private LocalData buildCameraImageData(int width, int height) {
        LocalData d = new CameraPreviewData(width, height);
        return d;
    }

    private void addOrReplaceCameraData(LocalData data) {
        if (mImages == null) mImages = new ArrayList<LocalData>();
        if (mImages.size() == 0) {
            // No data at all.
            mImages.add(0, data);
            if (mListener != null) mListener.onDataLoaded();
            return;
        }

        LocalData first = mImages.get(0);
        if (first.getType() == ImageData.TYPE_CAMERA_PREVIEW) {
            // Replace the old camera data.
            mImages.set(0, data);
            if (mListener != null) {
                mListener.onDataUpdated(new UpdateReporter() {
                    @Override
                    public boolean isDataRemoved(int id) {
                        return false;
                    }

                    @Override
                    public boolean isDataUpdated(int id) {
                        if (id == 0) return true;
                        return false;
                    }
                });
            }
        } else {
            // Add a new camera data.
            mImages.add(0, data);
            if (mListener != null) {
                mListener.onDataLoaded();
            }
        }
    }

    private class QueryTask extends AsyncTask<ContentResolver, Void, List<LocalData>> {
        @Override
        protected List<LocalData> doInBackground(ContentResolver... resolver) {
            List<LocalData> l = new ArrayList<LocalData>();
            // Photos
            Cursor c = resolver[0].query(
                    Images.Media.EXTERNAL_CONTENT_URI,
                    LocalPhotoData.QUERY_PROJECTION,
                    MediaStore.Images.Media.DATA + " like ? ", CAMERA_PATH,
                    LocalPhotoData.QUERY_ORDER);
            if (c != null && c.moveToFirst()) {
                // build up the list.
                while (true) {
                    LocalData data = LocalPhotoData.buildFromCursor(c);
                    if (data != null) {
                        l.add(data);
                    } else {
                        Log.e(TAG, "Error loading data:"
                                + c.getString(LocalPhotoData.COL_DATA));
                    }
                    if (c.isLast()) break;
                    c.moveToNext();
                }
            }
            if (c != null) c.close();

            c = resolver[0].query(
                    Video.Media.EXTERNAL_CONTENT_URI,
                    LocalVideoData.QUERY_PROJECTION,
                    MediaStore.Video.Media.DATA + " like ? ", CAMERA_PATH,
                    LocalVideoData.QUERY_ORDER);
            if (c != null && c.moveToFirst()) {
                // build up the list.
                c.moveToFirst();
                while (true) {
                    LocalData data = LocalVideoData.buildFromCursor(c);
                    if (data != null) {
                        l.add(data);
                        Log.v(TAG, "video data added:" + data);
                    } else {
                        Log.e(TAG, "Error loading data:"
                                + c.getString(LocalVideoData.COL_DATA));
                    }
                    if (!c.isLast()) c.moveToNext();
                    else break;
                }
            }
            if (c != null) c.close();

            if (l.size() == 0) return null;

            Collections.sort(l);
            return l;
        }

        @Override
        protected void onPostExecute(List<LocalData> l) {
            boolean changed = (l != mImages);
            LocalData cameraData = null;
            if (mImages != null && mImages.size() > 0) {
                cameraData = mImages.get(0);
                if (cameraData.getType() != ImageData.TYPE_CAMERA_PREVIEW) {
                    cameraData = null;
                }
            }

            mImages = l;
            if (cameraData != null) {
                // camera view exists, so we make sure at least have 1 data in the list.
                if (mImages == null) mImages = new ArrayList<LocalData>();
                mImages.add(0, cameraData);
                if (mListener != null) {
                    // Only the camera data is not changed, everything else is changed.
                    mListener.onDataUpdated(new UpdateReporter() {
                        @Override
                        public boolean isDataRemoved(int id) {
                            return false;
                        }

                        @Override
                        public boolean isDataUpdated(int id) {
                            if (id == 0) return false;
                            return true;
                        }
                    });
                }
            } else {
                // both might be null.
                if (changed) mListener.onDataLoaded();
            }
        }
    }

    private abstract static class LocalData implements
            FilmStripView.ImageData,
            Comparable<LocalData> {
        public long id;
        public String title;
        public String mimeType;
        public long dateTaken;
        public long dateModified;
        public String path;
        // width and height should be adjusted according to orientation.
        public int width;
        public int height;

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public boolean isActionSupported(int action) {
            return false;
        }

        private int compare(long v1, long v2) {
            return ((v1 > v2) ? 1 : ((v1 < v2) ? -1 : 0));
        }

        @Override
        public int compareTo(LocalData d) {
            int cmp = compare(d.dateTaken, dateTaken);
            if (cmp != 0) return cmp;
            cmp = compare(d.dateModified, dateModified);
            if (cmp != 0) return cmp;
            cmp = d.title.compareTo(title);
            if (cmp != 0) return cmp;
            return compare(d.id, id);
        }

        @Override
        public abstract int getType();

        abstract View getView(Context c, int width, int height, Drawable placeHolder);
    }

    private class CameraPreviewData extends LocalData {
        CameraPreviewData(int w, int h) {
            width = w;
            height = h;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public int getType() {
            return ImageData.TYPE_CAMERA_PREVIEW;
        }

        @Override
        View getView(Context c, int width, int height, Drawable placeHolder) {
            return mCameraPreviewView;
        }
    }

    private static class LocalPhotoData extends LocalData {
        static final String QUERY_ORDER = ImageColumns.DATE_TAKEN + " DESC, "
                + ImageColumns._ID + " DESC";
        static final String[] QUERY_PROJECTION = {
            ImageColumns._ID,           // 0, int
            ImageColumns.TITLE,         // 1, string
            ImageColumns.MIME_TYPE,     // 2, string
            ImageColumns.DATE_TAKEN,    // 3, int
            ImageColumns.DATE_MODIFIED, // 4, int
            ImageColumns.DATA,          // 5, string
            ImageColumns.ORIENTATION,   // 6, int, 0, 90, 180, 270
            ImageColumns.WIDTH,         // 7, int
            ImageColumns.HEIGHT,        // 8, int
        };

        private static final int COL_ID = 0;
        private static final int COL_TITLE = 1;
        private static final int COL_MIME_TYPE = 2;
        private static final int COL_DATE_TAKEN = 3;
        private static final int COL_DATE_MODIFIED = 4;
        private static final int COL_DATA = 5;
        private static final int COL_ORIENTATION = 6;
        private static final int COL_WIDTH = 7;
        private static final int COL_HEIGHT = 8;

        // 32K buffer.
        private static final byte[] DECODE_TEMP_STORAGE = new byte[32 * 1024];

        // from MediaStore, can only be 0, 90, 180, 270;
        public int orientation;

        static LocalPhotoData buildFromCursor(Cursor c) {
            LocalPhotoData d = new LocalPhotoData();
            d.id = c.getLong(COL_ID);
            d.title = c.getString(COL_TITLE);
            d.mimeType = c.getString(COL_MIME_TYPE);
            d.dateTaken = c.getLong(COL_DATE_TAKEN);
            d.dateModified = c.getLong(COL_DATE_MODIFIED);
            d.path = c.getString(COL_DATA);
            d.orientation = c.getInt(COL_ORIENTATION);
            d.width = c.getInt(COL_WIDTH);
            d.height = c.getInt(COL_HEIGHT);
            if (d.width <= 0 || d.height <= 0) {
                Log.v(TAG, "warning! zero dimension for "
                        + d.path + ":" + d.width + "x" + d.height);
                Dimension dim = decodeDimension(d.path);
                if (dim != null) {
                    d.width = dim.width;
                    d.height = dim.height;
                } else {
                    Log.v(TAG, "warning! dimension decode failed for " + d.path);
                    Bitmap b = BitmapFactory.decodeFile(d.path);
                    if (b == null) return null;
                    d.width = b.getWidth();
                    d.height = b.getHeight();
                }
            }
            if (d.orientation == 90 || d.orientation == 270) {
                int b = d.width;
                d.width = d.height;
                d.height = b;
            }
            return d;
        }

        @Override
        View getView(Context c,
                int decodeWidth, int decodeHeight, Drawable placeHolder) {
            ImageView v = new ImageView(c);
            v.setImageDrawable(placeHolder);

            v.setScaleType(ImageView.ScaleType.FIT_XY);
            LoadBitmapTask task = new LoadBitmapTask(v, decodeWidth, decodeHeight);
            task.execute();
            return v;
        }

        @Override
        public String toString() {
            return "LocalPhotoData:" + ",data=" + path + ",mimeType=" + mimeType
                    + "," + width + "x" + height + ",orientation=" + orientation
                    + ",date=" + new Date(dateTaken);
        }

        @Override
        public int getType() {
            return TYPE_PHOTO;
        }

        private static Dimension decodeDimension(String path) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            Bitmap b = BitmapFactory.decodeFile(path, opts);
            if (b == null) return null;
            Dimension d = new Dimension();
            d.width = opts.outWidth;
            d.height = opts.outHeight;
            return d;
        }

        private static class Dimension {
            public int width;
            public int height;
        }

        private class LoadBitmapTask extends AsyncTask<Void, Void, Bitmap> {
            private ImageView mView;
            private int mDecodeWidth;
            private int mDecodeHeight;

            public LoadBitmapTask(ImageView v, int decodeWidth, int decodeHeight) {
                mView = v;
                mDecodeWidth = decodeWidth;
                mDecodeHeight = decodeHeight;
            }

            @Override
            protected Bitmap doInBackground(Void... v) {
                BitmapFactory.Options opts = null;
                Bitmap b;
                int sample = 1;
                while (mDecodeWidth * sample < width
                        || mDecodeHeight * sample < height) {
                    sample *= 2;
                }
                opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inTempStorage = DECODE_TEMP_STORAGE;
                if (isCancelled()) return null;
                b = BitmapFactory.decodeFile(path, opts);
                if (orientation != 0) {
                    if (isCancelled()) return null;
                    Matrix m = new Matrix();
                    m.setRotate((float) orientation);
                    b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, false);
                }
                return b;
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap == null) {
                    Log.e(TAG, "Cannot decode bitmap file:" + path);
                    return;
                }
                mView.setScaleType(ImageView.ScaleType.FIT_XY);
                mView.setImageBitmap(bitmap);
            }
        }
    }

    private static class LocalVideoData extends LocalData {
        static final String QUERY_ORDER = VideoColumns.DATE_TAKEN + " DESC, "
                + VideoColumns._ID + " DESC";
        static final String[] QUERY_PROJECTION = {
            VideoColumns._ID,           // 0, int
            VideoColumns.TITLE,         // 1, string
            VideoColumns.MIME_TYPE,     // 2, string
            VideoColumns.DATE_TAKEN,    // 3, int
            VideoColumns.DATE_MODIFIED, // 4, int
            VideoColumns.DATA,          // 5, string
            VideoColumns.WIDTH,         // 6, int
            VideoColumns.HEIGHT,        // 7, int
            VideoColumns.RESOLUTION
        };

        private static final int COL_ID = 0;
        private static final int COL_TITLE = 1;
        private static final int COL_MIME_TYPE = 2;
        private static final int COL_DATE_TAKEN = 3;
        private static final int COL_DATE_MODIFIED = 4;
        private static final int COL_DATA = 5;
        private static final int COL_WIDTH = 6;
        private static final int COL_HEIGHT = 7;

        public int resolutionW;
        public int resolutionH;

        static LocalVideoData buildFromCursor(Cursor c) {
            LocalVideoData d = new LocalVideoData();
            d.id = c.getLong(COL_ID);
            d.title = c.getString(COL_TITLE);
            d.mimeType = c.getString(COL_MIME_TYPE);
            d.dateTaken = c.getLong(COL_DATE_TAKEN);
            d.dateModified = c.getLong(COL_DATE_MODIFIED);
            d.path = c.getString(COL_DATA);
            d.width = c.getInt(COL_WIDTH);
            d.height = c.getInt(COL_HEIGHT);
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(d.path);
            String rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (d.width == 0 || d.height == 0) {
                d.width = Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                d.height = Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            }
            retriever.release();
            if (rotation.equals("90") || rotation.equals("270")) {
                int b = d.width;
                d.width = d.height;
                d.height = b;
            }
            return d;
        }

        @Override
        View getView(Context c,
                int decodeWidth, int decodeHeight, Drawable placeHolder) {
            ImageView v = new ImageView(c);
            v.setImageDrawable(placeHolder);

            v.setScaleType(ImageView.ScaleType.FIT_XY);
            LoadBitmapTask task = new LoadBitmapTask(v);
            task.execute();
            return v;
        }


        @Override
        public String toString() {
            return "LocalVideoData:" + ",data=" + path + ",mimeType=" + mimeType
                    + "," + width + "x" + height + ",date=" + new Date(dateTaken);
        }

        @Override
        public int getType() {
            return TYPE_PHOTO;
        }

        private static Dimension decodeDimension(String path) {
            Dimension d = new Dimension();
            return d;
        }

        private static class Dimension {
            public int width;
            public int height;
        }

        private class LoadBitmapTask extends AsyncTask<Void, Void, Bitmap> {
            private ImageView mView;

            public LoadBitmapTask(ImageView v) {
                mView = v;
            }

            @Override
            protected Bitmap doInBackground(Void... v) {
                android.media.MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(path);
                byte[] data = retriever.getEmbeddedPicture();
                Bitmap bitmap = null;
                if (data != null) {
                    bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                }
                if (bitmap == null) {
                    bitmap = (Bitmap) retriever.getFrameAtTime();
                }
                retriever.release();
                return bitmap;
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap == null) {
                    Log.e(TAG, "Cannot decode video file:" + path);
                    return;
                }
                mView.setImageBitmap(bitmap);
            }
        }
    }
}