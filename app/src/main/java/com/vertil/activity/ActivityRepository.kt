package com.vertil.activity

import com.vertil.core.VertilResult
import com.vertil.core.log.VertilLog
import com.vertil.persistence.VertilDatabase
import com.vertil.persistence.entities.ActivityEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repositorio del registro de actividad.
 */
class ActivityRepository(private val db: VertilDatabase) {

    val recent: Flow<List<ActivityEntry>> = db.activityDao().observeRecent().map { entities ->
        entities.map { it.toEntry() }
    }

    suspend fun log(
        toolId: String,
        operation: String,
        success: Boolean,
        message: String,
        source: String? = null,
        destination: String? = null,
        errorCode: String? = null
    ): VertilResult<Long> {
        return try {
            val ts = System.currentTimeMillis()
            val rowId = db.activityDao().insert(
                ActivityEntity(
                    timestamp = ts,
                    toolId = toolId,
                    operation = operation,
                    success = success,
                    message = message,
                    source = source,
                    destination = destination,
                    errorCode = errorCode
                )
            )
            VertilLog.i("ActivityRepo", "logged: $toolId/$operation success=$success")
            VertilResult.ok(rowId)
        } catch (t: Throwable) {
            VertilResult.fail("log failed: ${t.message}", t, "ActivityRepository")
        }
    }

    suspend fun clear(): VertilResult<Unit> {
        return try { db.activityDao().clear(); VertilResult.ok(Unit) }
        catch (t: Throwable) { VertilResult.fail("clear failed: ${t.message}", t, "ActivityRepository") }
    }

    suspend fun delete(id: Long): VertilResult<Unit> {
        return try { db.activityDao().delete(id); VertilResult.ok(Unit) }
        catch (t: Throwable) { VertilResult.fail("delete failed", t, "ActivityRepository") }
    }

    private fun ActivityEntity.toEntry(): ActivityEntry = ActivityEntry(
        id = id, timestamp = timestamp, toolId = toolId, operation = operation,
        success = success, message = message, source = source,
        destination = destination, errorCode = errorCode
    )
}
