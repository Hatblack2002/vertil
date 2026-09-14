package com.vertil.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vertil.persistence.daos.ActivityDao
import com.vertil.persistence.daos.AutomationDao
import com.vertil.persistence.daos.ChatMessageDao
import com.vertil.persistence.daos.GrantedFolderDao
import com.vertil.persistence.daos.ModelDao
import com.vertil.persistence.entities.ActivityEntity
import com.vertil.persistence.entities.AutomationEntity
import com.vertil.persistence.entities.ChatMessageEntity
import com.vertil.persistence.entities.GrantedFolderEntity
import com.vertil.persistence.entities.ModelEntity
import java.io.File

@Database(
    entities = [
        ModelEntity::class,
        ActivityEntity::class,
        ChatMessageEntity::class,
        AutomationEntity::class,
        GrantedFolderEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class VertilDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun activityDao(): ActivityDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun automationDao(): AutomationDao
    abstract fun grantedFolderDao(): GrantedFolderDao

    companion object {
        @Volatile private var instance: VertilDatabase? = null
        @Volatile private var appContext: Context? = null

        /** Directorio donde se copian los modelos importados. */
        val modelsDir: File
            get() {
                val ctx = appContext!!
                val dir = File(ctx.getDatabasePath("vertil.db").parentFile, "models")
                if (!dir.exists()) dir.mkdirs()
                return dir
            }

        fun get(context: Context): VertilDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    appContext = context.applicationContext
                    Room.databaseBuilder(
                        context.applicationContext,
                        VertilDatabase::class.java,
                        "vertil.db"
                    ).fallbackToDestructiveMigration().build().also { instance = it }
                }
            }
    }
}
