package com.jerreader.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class],
    version = 1,
    exportSchema = true
)
abstract class JerreaderDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao

    companion object {
        fun build(context: Context): JerreaderDatabase = Room.databaseBuilder(
            context.applicationContext,
            JerreaderDatabase::class.java,
            "jerreader-library.db"
        ).build()
    }
}
