package com.vertil.automation

import com.vertil.core.VertilResult
import com.vertil.core.log.VertilLog
import com.vertil.persistence.VertilDatabase
import com.vertil.persistence.entities.AutomationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class AutomationRepository(private val db: VertilDatabase) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val rules: Flow<List<AutomationRule>> = db.automationDao().observeAll().map { entities ->
        entities.mapNotNull { e ->
            runCatching { json.decodeFromString(AutomationRule.serializer(), e.ruleJson) }
                .getOrNull()?.copy(id = e.id)
        }
    }

    suspend fun save(rule: AutomationRule): VertilResult<Unit> {
        return try {
            val entity = AutomationEntity(
                id = rule.id,
                name = rule.name,
                description = rule.description,
                ruleJson = json.encodeToString(AutomationRule.serializer(), rule),
                enabled = rule.enabled,
                createdAt = rule.createdAt,
                lastFiredAt = rule.lastFiredAt
            )
            db.automationDao().insert(entity)
            VertilLog.i("AutoRepo", "Regla guardada: ${rule.name}")
            VertilResult.ok(Unit)
        } catch (t: Throwable) {
            VertilResult.fail("save failed: ${t.message}", t, "AutomationRepository")
        }
    }

    suspend fun delete(id: String): VertilResult<Unit> {
        return try { db.automationDao().delete(id); VertilResult.ok(Unit) }
        catch (t: Throwable) { VertilResult.fail("delete failed", t, "AutomationRepository") }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): VertilResult<Unit> {
        return try { db.automationDao().setEnabled(id, enabled); VertilResult.ok(Unit) }
        catch (t: Throwable) { VertilResult.fail("setEnabled failed", t, "AutomationRepository") }
    }
}
