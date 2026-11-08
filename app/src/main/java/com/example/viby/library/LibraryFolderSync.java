package com.example.viby.library;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mirrors the app-owned Music folder into a user-chosen Storage Access Framework folder.
 * yt-dlp needs real file paths, so downloads still land in app storage; the mirror is the
 * copy that survives uninstalling the app and lets the library be restored afterwards.
 *
 * Uses DocumentsContract directly: one query per directory instead of one per file.
 */
public final class LibraryFolderSync implements LibraryRelinker.Mirror {

    private static final String TAG = "LibraryFolderSync";
    /** Generic type so providers never append a second extension to our file names. */
    private static final String BINARY_MIME = "application/octet-stream";
    private static final int BUFFER_SIZE = 64 * 1024;

    public static final class Result {
        public int copied;
        public int skipped;
        public int failed;
        public long copiedBytes;
    }

    private static final class Child {
        final String documentId;
        final long size;
        final boolean directory;

        Child(String documentId, long size, boolean directory) {
            this.documentId = documentId;
            this.size = size;
            this.directory = directory;
        }
    }

    private final ContentResolver resolver;
    private final Uri treeUri;
    private final String rootId;
    /** documentId → (display name → child), filled lazily, one query per directory. */
    private final Map<String, Map<String, Child>> listings = new HashMap<>();

    private LibraryFolderSync(Context context, Uri treeUri) {
        this.resolver = context.getContentResolver();
        this.treeUri = treeUri;
        this.rootId = DocumentsContract.getTreeDocumentId(treeUri);
    }

    /** Null when no folder is chosen or Android revoked the persisted permission. */
    @Nullable
    public static LibraryFolderSync open(Context context) {
        Uri tree = StorageSettings.mirrorTree(context);
        if (tree == null || !hasWritePermission(context, tree)) {
            return null;
        }
        try {
            return new LibraryFolderSync(context.getApplicationContext(), tree);
        } catch (IllegalArgumentException invalid) {
            Log.w(TAG, "Invalid mirror tree", invalid);
            return null;
        }
    }

    public static boolean hasWritePermission(Context context, Uri tree) {
        for (UriPermission permission : context.getContentResolver()
                .getPersistedUriPermissions()) {
            if (permission.getUri().equals(tree) && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    /** Human-readable folder label such as "primary:Music/Viby" → "Music/Viby". */
    public static String displayName(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            return path.isEmpty() ? id : path;
        } catch (IllegalArgumentException invalid) {
            return tree.toString();
        }
    }

    /** Copies every existing library file whose mirror copy is missing or has another size. */
    public Result mirrorFiles(Iterable<File> files, File musicRoot) {
        Result result = new Result();
        for (File file : files) {
            String relative = LibraryPaths.relativePath(musicRoot, file.getAbsolutePath());
            if (relative == null || !file.isFile()) {
                continue;
            }
            try {
                String[] segments = relative.split("/");
                String parentId = rootId;
                for (int i = 0; i < segments.length - 1; i++) {
                    parentId = ensureDirectory(parentId, segments[i]);
                }
                String name = segments[segments.length - 1];
                Child existing = listing(parentId).get(name);
                if (existing != null && !existing.directory && existing.size == file.length()) {
                    result.skipped++;
                    continue;
                }
                if (existing != null) {
                    DocumentsContract.deleteDocument(resolver, documentUri(existing.documentId));
                    listing(parentId).remove(name);
                }
                Uri created = DocumentsContract.createDocument(
                        resolver, documentUri(parentId), BINARY_MIME, name);
                if (created == null) {
                    throw new IOException("Provider refused to create " + relative);
                }
                try (InputStream input = new FileInputStream(file);
                     OutputStream output = openOutput(created)) {
                    copy(input, output);
                }
                listing(parentId).put(name, new Child(
                        DocumentsContract.getDocumentId(created), file.length(), false));
                result.copied++;
                result.copiedBytes += file.length();
            } catch (Exception error) {
                Log.w(TAG, "Mirror copy failed: " + relative, error);
                result.failed++;
            }
        }
        return result;
    }

    /** Creates or overwrites a small text file directly in the mirror root. */
    public void writeRootText(String name, String text) throws IOException {
        Child existing = listing(rootId).get(name);
        Uri target = null;
        if (existing != null && !existing.directory) {
            target = documentUri(existing.documentId);
        }
        if (target == null) {
            target = DocumentsContract.createDocument(
                    resolver, documentUri(rootId), BINARY_MIME, name);
            if (target == null) {
                throw new IOException("Provider refused to create " + name);
            }
            listing(rootId).put(name, new Child(DocumentsContract.getDocumentId(target),
                    0L, false));
        }
        try (OutputStream output = openOutput(target)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    public String readRootText(String name) throws IOException {
        Child child = listing(rootId).get(name);
        if (child == null || child.directory) {
            return null;
        }
        try (InputStream input = resolver.openInputStream(documentUri(child.documentId))) {
            if (input == null) {
                return null;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            copy(input, bytes);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public boolean restore(String relativePath, File destination) {
        try {
            String[] segments = relativePath.split("/");
            String parentId = rootId;
            for (int i = 0; i < segments.length - 1; i++) {
                Child directory = listing(parentId).get(segments[i]);
                if (directory == null || !directory.directory) {
                    return false;
                }
                parentId = directory.documentId;
            }
            Child file = listing(parentId).get(segments[segments.length - 1]);
            if (file == null || file.directory) {
                return false;
            }
            File temp = new File(destination.getPath() + ".restore.tmp");
            try (InputStream input = resolver.openInputStream(documentUri(file.documentId));
                 OutputStream output = new FileOutputStream(temp)) {
                if (input == null) {
                    return false;
                }
                copy(input, output);
            }
            if (temp.length() != file.size && file.size > 0) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                return false;
            }
            if (!temp.renameTo(destination)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                return false;
            }
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Restore failed: " + relativePath, error);
            return false;
        }
    }

    private String ensureDirectory(String parentId, String name) throws IOException {
        Child existing = listing(parentId).get(name);
        if (existing != null && existing.directory) {
            return existing.documentId;
        }
        Uri created = DocumentsContract.createDocument(resolver, documentUri(parentId),
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) {
            throw new IOException("Provider refused to create folder " + name);
        }
        String id = DocumentsContract.getDocumentId(created);
        listing(parentId).put(name, new Child(id, 0L, true));
        listings.put(id, new LinkedHashMap<>());
        return id;
    }

    private Map<String, Child> listing(String documentId) {
        Map<String, Child> cached = listings.get(documentId);
        if (cached != null) {
            return cached;
        }
        Map<String, Child> children = new LinkedHashMap<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        };
        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                long size = cursor.isNull(2) ? 0L : cursor.getLong(2);
                boolean directory = DocumentsContract.Document.MIME_TYPE_DIR
                        .equals(cursor.getString(3));
                if (id != null && name != null) {
                    children.put(name, new Child(id, size, directory));
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Cannot list mirror folder", error);
        }
        listings.put(documentId, children);
        return children;
    }

    private Uri documentUri(String documentId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
    }

    private OutputStream openOutput(Uri uri) throws IOException {
        OutputStream output;
        try {
            output = resolver.openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
            output = resolver.openOutputStream(uri, "w");
        }
        if (output == null) {
            throw new IOException("Cannot open " + uri);
        }
        return output;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
    }
}
