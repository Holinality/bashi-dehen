package com.holi.prdbus;

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

/** Grants the system installer temporary read access to a verified update only. */
public final class UpdateFileProvider extends ContentProvider {
    static final String MIME = "application/vnd.android.package-archive";

    static Uri uri(Context context, File file) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".update-files")
                .appendPath(file.getName()).build();
    }

    @Override public boolean onCreate() { return true; }

    private File file(Uri uri) {
        if (uri.getPathSegments().size() != 1) return null;
        return VerifiedUpdate.publishedFile(getContext(), uri.getLastPathSegment());
    }

    @Override public String getType(Uri uri) { return file(uri) == null ? null : MIME; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = file(uri);
        if (file == null || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] arguments, String order) {
        File file = file(uri);
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        if (file == null) return result;
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "BashiDeHen.apk";
            if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        result.addRow(values);
        return result;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
}
