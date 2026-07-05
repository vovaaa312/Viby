package com.example.viby.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(entities = {Track.class}, version = 1, exportSchema = false)
public abstract class VibyDatabase extends RoomDatabase {

    private static volatile VibyDatabase instance;

    /** Пул для операций с БД вне UI-потока (Java, без корутин). */
    public static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    public abstract TrackDao trackDao();

    public static VibyDatabase get(Context context) {
        if (instance == null) {
            synchronized (VibyDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    VibyDatabase.class,
                                    "viby.db")
                            .build();
                }
            }
        }
        return instance;
    }
}
