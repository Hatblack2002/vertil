package com.vertil.core

import com.vertil.core.identity.VertilIdentity
import com.vertil.core.prompt.SystemPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityAndPromptTest {

    @Test
    fun `identity constants are stable`() {
        assertEquals("VERTIL", VertilIdentity.NAME)
        assertEquals("Vertil Jivenson", VertilIdentity.DEVELOPER)
        assertEquals("1.0.0", VertilIdentity.IDENTITY_VERSION)
    }

    @Test
    fun `greeting includes name and developer`() {
        assertTrue(VertilIdentity.GREETING.contains("VERTIL"))
        assertTrue(VertilIdentity.GREETING.contains("Vertil Jivenson"))
    }

    @Test
    fun `self description clarifies model is engine not identity`() {
        assertTrue(VertilIdentity.SELF_DESCRIPTION.contains("modelo"))
        assertTrue(VertilIdentity.SELF_DESCRIPTION.contains("VERTIL"))
    }

    @Test
    fun `isIdentityQuestion detects common phrasings`() {
        assertTrue(VertilIdentity.isIdentityQuestion("quien eres"))
        assertTrue(VertilIdentity.isIdentityQuestion("  Quién Eres  "))
        assertTrue(VertilIdentity.isIdentityQuestion("tu nombre"))
        assertTrue(VertilIdentity.isIdentityQuestion("who are you"))
    }

    @Test
    fun `isIdentityQuestion rejects normal queries`() {
        assertTrue(!VertilIdentity.isIdentityQuestion("busca archivos pdf"))
        assertTrue(!VertilIdentity.isIdentityQuestion("organiza mis descargas"))
    }

    @Test
    fun `system prompt includes identity and security rules`() {
        val prompt = SystemPrompt.build("TestModel", "1.5 GB")
        assertTrue(prompt.contains("VERTIL"))
        assertTrue(prompt.contains("Vertil Jivenson"))
        assertTrue(prompt.contains("herramientas"))
        assertTrue(prompt.lowercase().contains("seguridad"))
        assertTrue(prompt.contains("TestModel"))
    }

    @Test
    fun `system prompt has version tags`() {
        val prompt = SystemPrompt.build(null, null)
        assertTrue(prompt.contains("Policy version: ${SystemPrompt.POLICY_VERSION}"))
        assertTrue(prompt.contains("Tool API version: ${SystemPrompt.TOOL_API_VERSION}"))
    }
}
