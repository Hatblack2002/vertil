package com.vertil.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationValidatorTest {

    @Test
    fun `valid rule passes`() {
        val rule = AutomationRule(
            id = "r1",
            name = "PDF organizer",
            description = "PDFs to Documents",
            trigger = Trigger(TriggerType.FILE_EXTENSION, mapOf("extension" to "pdf", "folder" to "/Downloads")),
            action = Action(ActionType.MOVE_TO_FOLDER, mapOf("destination" to "/Documents")),
            createdAt = 0L
        )
        val result = AutomationValidator.validate(rule)
        assertTrue(result.errors.toString(), result.isValid)
    }

    @Test
    fun `empty name fails`() {
        val rule = AutomationRule(
            id = "r1", name = "", description = "",
            trigger = Trigger(TriggerType.FILE_EXTENSION, mapOf("extension" to "pdf")),
            action = Action(ActionType.MOVE_TO_FOLDER, mapOf("destination" to "/x")),
            createdAt = 0L
        )
        assertFalse(AutomationValidator.validate(rule).isValid)
    }

    @Test
    fun `FILE_EXTENSION requires extension condition`() {
        val rule = AutomationRule(
            id = "r1", name = "test", description = "",
            trigger = Trigger(TriggerType.FILE_EXTENSION, emptyMap()),
            action = Action(ActionType.MOVE_TO_FOLDER, mapOf("destination" to "/x")),
            createdAt = 0L
        )
        assertFalse(AutomationValidator.validate(rule).isValid)
    }

    @Test
    fun `MOVE_TO_FOLDER requires destination`() {
        val rule = AutomationRule(
            id = "r1", name = "test", description = "",
            trigger = Trigger(TriggerType.FILE_EXTENSION, mapOf("extension" to "pdf")),
            action = Action(ActionType.MOVE_TO_FOLDER, emptyMap()),
            createdAt = 0L
        )
        assertFalse(AutomationValidator.validate(rule).isValid)
    }

    @Test
    fun `RENAME_PATTERN requires pattern param`() {
        val rule = AutomationRule(
            id = "r1", name = "test", description = "",
            trigger = Trigger(TriggerType.FILE_EXTENSION, mapOf("extension" to "jpg")),
            action = Action(ActionType.RENAME_PATTERN, emptyMap()),
            createdAt = 0L
        )
        assertFalse(AutomationValidator.validate(rule).isValid)
    }
}
