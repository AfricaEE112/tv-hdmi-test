package ua.domivka;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Віддає завантажений APK оновлення системному встановлювачу (content://, бо file://
 * для застосунків під Android 7+ заборонено). Лише читання, лише один файл.
 */
public class UpdateProvider extends ContentProvider {

    static final String AUTHORITY = "ua.domivka.update";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File file() {
        return new File(getContext().getCacheDir(), Updater.APK_NAME);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!Updater.APK_NAME.equals(uri.getLastPathSegment())) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(file(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{"domivka.apk", file().length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
