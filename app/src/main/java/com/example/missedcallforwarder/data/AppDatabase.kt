package com.example.missedcallforwarder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun toStatus(value: String): ForwardStatus = ForwardStatus.valueOf(value)

    @TypeConverter
    fun fromStatus(status: ForwardStatus): String = status.name
}

@Database(entities = [ForwardLog::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun forwardLogDao(): ForwardLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "forwarder.db"
                ).build().also { INSTANCE = it }
            }
    }
}
