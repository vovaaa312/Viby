package com.example.viby.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(entities = {Track.class, PlaylistSource.class, PendingDownload.class},
        version = 3, exportSchema = false)
public abstract class VibyDatabase extends RoomDatabase {

    private static volatile VibyDatabase instance;

    /** Пул для операций с БД вне UI-потока (Java, без корутин). */
    public static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    public abstract TrackDao trackDao();

    public abstract PlaylistSourceDao playlistSourceDao();

    public abstract PendingDownloadDao pendingDownloadDao();

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_sources` ("
                    + "`playlistName` TEXT NOT NULL, "
                    + "`sourceUrl` TEXT NOT NULL, "
                    + "`updatedAt` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`playlistName`))");
        }
    };

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `pending_downloads` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`url` TEXT NOT NULL, "
                    + "`playlistName` TEXT, "
                    + "`isPlaylist` INTEGER NOT NULL, "
                    + "`createdAt` INTEGER NOT NULL)");
        }
    };

    public static VibyDatabase get(Context context) {
        if (instance == null) {
            synchronized (VibyDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    VibyDatabase.class,
                                    "viby.db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                            .build();
                }
            }
        }
        return instance;
    }
}
