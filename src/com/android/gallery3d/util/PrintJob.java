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

package com.android.gallery3d.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument.Page;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.print.pdf.PrintedPdfDocument;

import com.android.gallery3d.filtershow.cache.ImageLoader;

import java.io.FileOutputStream;
import java.io.IOException;

public class PrintJob {
    private static final String LOG_TAG = "PrintJob";
    private static final boolean CROP_TO_FILL_PAGE = true;
    // will be <= 300 dpi on A4 (8.3×11.7) paper
    // with a worst case of 150 dpi
    private final static int MAX_PRINT_SIZE = 3500;

    public static void printBitmap(final Context context, final String jobName,
            final Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        PrintManager printManager = (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
        printManager.print(jobName,
                new PrintDocumentAdapter() {
                    private PrintAttributes mAttributes;

                    @Override
                    public void onLayout(PrintAttributes oldPrintAttributes,
                                         PrintAttributes newPrintAttributes,
                                         CancellationSignal cancellationSignal,
                                         LayoutResultCallback layoutResultCallback,
                                         Bundle bundle) {

                        mAttributes = newPrintAttributes;

                        PrintDocumentInfo info = new PrintDocumentInfo.Builder(jobName)
                                .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                                .setPageCount(1)
                                .build();
                        boolean changed = !newPrintAttributes.equals(oldPrintAttributes);
                        layoutResultCallback.onLayoutFinished(info, changed);
                    }

                    @Override
                    public void onWrite(PageRange[] pageRanges, ParcelFileDescriptor fileDescriptor,
                                        CancellationSignal cancellationSignal,
                                        WriteResultCallback writeResultCallback) {
                        PrintedPdfDocument pdfDocument = new PrintedPdfDocument(context,
                                mAttributes);
                        try {
                            Page page = pdfDocument.startPage(1);

                            RectF content = new RectF(page.getInfo().getContentRect());
                            Matrix matrix = new Matrix();

                            // Compute and apply scale to fill the page.
                            float scale = content.width() / bitmap.getWidth();
                            if (CROP_TO_FILL_PAGE) {
                                scale = Math.max(scale, content.height() / bitmap.getHeight());
                            } else {
                                scale = Math.min(scale, content.height() / bitmap.getHeight());
                            }
                            matrix.postScale(scale, scale);

                            // Center the content.
                            final float translateX = (content.width()
                                    - bitmap.getWidth() * scale) / 2;
                            final float translateY = (content.height()
                                    - bitmap.getHeight() * scale) / 2;
                            matrix.postTranslate(translateX, translateY);

                            // Draw the bitmap.
                            page.getCanvas().drawBitmap(bitmap, matrix, null);

                            // Finish the page.
                            pdfDocument.finishPage(page);

                            try {
                                // Write the document.
                                pdfDocument.writeTo(new FileOutputStream(
                                        fileDescriptor.getFileDescriptor()));
                                // Done.
                                writeResultCallback.onWriteFinished(
                                        new PageRange[] { PageRange.ALL_PAGES });
                            } catch (IOException ioe) {
                                // Failed.
                                Log.e(LOG_TAG, "Error writing printed content", ioe);
                                writeResultCallback.onWriteFailed(null);
                            }
                        } finally {
                            if (pdfDocument != null) {
                                pdfDocument.close();
                            }
                            if (fileDescriptor != null) {
                                try {
                                    fileDescriptor.close();
                                } catch (IOException ioe) {
                                    /* ignore */
                                }
                            }
                        }
                    }
                }, null);
    }

    public static void printBitmapAtUri(Context context, String imagePrint, Uri uri) {
        // TODO: load full size images. For now, it's better to constrain ourselves.
        Bitmap bitmap = ImageLoader.loadConstrainedBitmap(uri, context, MAX_PRINT_SIZE, null, false);
        printBitmap(context, imagePrint, bitmap);
    }
}