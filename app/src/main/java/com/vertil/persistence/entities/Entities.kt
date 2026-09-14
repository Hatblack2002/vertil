package com.vertil.persistence.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "models", indices = [Index(value = ["id"], unique = true)])
data class ModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val filePath: String,
    val format: String,
    val sizeBytes: Long,
    val quantization: String?,
    val contextLength: Int?,
    val hashSha256: String?,
    val importedAt: Long,
    val isActive: Boolean,
    val state: String,
    val lastErrorMessage: String?
)

@Entity(tableName = "activity_log", indices = [Index(value = ["timestamp"])])
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val toolId: String,
    val operation: String,
    val success: Boolean,
    val message: String,
    val source: String?,
    val destination: String?,
    val errorCode: String?
)

@Entity(tableName = "chat_messages", indices = [Index(value = ["timestamp"])])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val modelId: String?,
    val tokensGenerated: Int = 0,
    val durationMs: Long = 0L,
    val tokensPerSecond: Float? = null,
    val toolCalls: String? = null // JSON serializado
)

@Entity(tableName = "automations", indices = [Index(value = ["id"])])
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val ruleJson: String, // serializado AutomationRule
    val enabled: Boolean,
    val createdAt: Long,
    val lastFiredAt: Long?
)

@Entity(tableName = "granted_folders", indices = [Index(value = ["uri"], unique = true)])
data class GrantedFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uri: String,
    val displayName: String,
    val grantedAt: Long
)
