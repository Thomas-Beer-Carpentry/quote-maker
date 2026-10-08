package nz.co.timbertakeoff.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ClientEntity::class, JobEntity::class, TaskEntity::class],
    version = 1,
    exportSchema = true
)
abstract class EstimatorDatabase : RoomDatabase() {
    abstract fun estimatorDao(): EstimatorDao

    companion object {
        fun open(context: Context): EstimatorDatabase = Room.databaseBuilder(
            context.applicationContext,
            EstimatorDatabase::class.java,
            "quote-maker.db"
        ).build()
    }
}
