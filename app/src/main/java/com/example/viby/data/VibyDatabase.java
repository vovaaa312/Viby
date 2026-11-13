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
        version = 11, exportSchema = false)
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

    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `youtubePosition` INTEGER");
        }
    };

    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `pending_downloads` "
                    + "ADD COLUMN `queuePosition` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `pending_downloads` "
                    + "ADD COLUMN `paused` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `pending_downloads` "
                    + "ADD COLUMN `trackOrderJson` TEXT");
            db.execSQL("ALTER TABLE `tracks` "
                    + "ADD COLUMN `downloaded` INTEGER NOT NULL DEFAULT 1");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `sourceUrl` TEXT");
            db.execSQL("UPDATE `pending_downloads` SET `queuePosition` = `id`");
        }
    };

    private static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `playlist_sources` "
                    + "ADD COLUMN `youtubePlaylistId` TEXT");
            db.execSQL("ALTER TABLE `playlist_sources` "
                    + "ADD COLUMN `youtubeSyncEnabled` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `playlist_sources` "
                    + "ADD COLUMN `youtubeOwnerChannelId` TEXT");
            db.execSQL("ALTER TABLE `tracks` "
                    + "ADD COLUMN `youtubePlaylistItemId` TEXT");
        }
    };

    private static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `downloadState` TEXT NOT NULL "
                    + "DEFAULT 'DOWNLOADED'");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `downloadErrorCode` TEXT");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `downloadErrorMessage` TEXT");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `downloadAttemptCount` "
                    + "INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `downloadLastAttemptAt` "
                    + "INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `downloadFormat` TEXT");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `downloadRequiresAuthorization` "
                    + "INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `downloadProgress` "
                    + "INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE `tracks` SET `downloadState` = 'WAITING' "
                    + "WHERE `downloaded` = 0");
        }
    };

    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `album` TEXT");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `favorite` "
                    + "INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `playCount` "
                    + "INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `lastPlayedAt` "
                    + "INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `metadataScanned` "
                    + "INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `addedInViby` "
                    + "INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `loudnessLufs` REAL");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `loudnessPeak` REAL");
            db.execSQL("ALTER TABLE `tracks` ADD COLUMN `loudnessScanned` "
                    + "INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * No schema change. Version 11 briefly renamed existing radios on a test device; that
     * was dropped, but the version stays so that device does not see a downgrade.
     */
    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
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
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3,
                                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                                    MIGRATION_9_10, MIGRATION_10_11)
                            .build();
                }
            }
        }
        return instance;
    }
}
