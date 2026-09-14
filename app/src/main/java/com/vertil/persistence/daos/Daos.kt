package com.vertil.persistence.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<com.vertil.persistence.entities.ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getById(id: String): com.vertil.persistence.entities.ModelEntity?

    @Query("SELECT * FROM models WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): com.vertil.persistence.entities.ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: com.vertil.persistence.entities.ModelEntity): Long

    @Query("UPDATE models SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE models SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean)

    @Query("UPDATE models SET state = :state, lastErrorMessage = :err WHERE id = :id")
    suspend fun updateState(id: String, state: String, err: String?)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT 200")
    fun observeRecent(): Flow<List<com.vertil.persistence.entities.ActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: com.vertil.persistence.entities.ActivityEntity): Long

    @Query("DELETE FROM activity_log")
    suspend fun clear()

    @Query("DELETE FROM activity_log WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<com.vertil.persistence.entities.ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: com.vertil.persistence.entities.ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun clear()

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<com.vertil.persistence.entities.AutomationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: com.vertil.persistence.entities.AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE automations SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Dao
interface GrantedFolderDao {
    @Query("SELECT * FROM granted_folders ORDER BY grantedAt DESC")
    fun observeAll(): Flow<List<com.vertil.persistence.entities.GrantedFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: com.vertil.persistence.entities.GrantedFolderEntity)

    @Query("DELETE FROM granted_folders WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
