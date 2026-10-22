package com.iris.assistant;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lightweight, zero-dependency ContentProvider for sharing archived media and documents
 * securely with external viewers via content:// URIs with read permissions, avoiding
 * FileUriExposedException on modern Android without pulling in AndroidX dependencies.
 */
public class IrisFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.iris.assistant.fileprovider";

    /** Build a content:// URI pointing to an archived file for external sharing. */
    public static Uri uriFor(Context context, String storedName) {
        if (storedName == null || storedName.trim().isEmpty()) return null;
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .path(storedName)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Context unavailable");
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) throw new FileNotFoundException("Missing file name");
        File file = ArchiveImages.fileFor(context, name);
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("File not found: " + name);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "application/octet-stream";
        return ArchiveImagePolicy.mimeTypeFor(name);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context context = getContext();
        if (context == null) return null;
        String name = uri.getLastPathSegment();
        if (name == null) return null;
        File file = ArchiveImages.fileFor(context, name);
        if (file == null || !file.exists()) return null;

        String[] cols = projection != null ? projection : new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
        };
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add("iris-" + file.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Vault files are read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Vault files are read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Vault files are read-only");
    }
}
