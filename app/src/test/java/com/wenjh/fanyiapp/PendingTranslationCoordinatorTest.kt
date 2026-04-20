package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTranslationCoordinatorTest {
    @Test
    fun rememberPending_keepsLatestProvisionalCandidate() {
        val coordinator = PendingTranslationCoordinator()

        coordinator.rememberPending("之 わ之", provisional = true)
        coordinator.rememberPending("こんにちは", provisional = true)

        assertEquals(PendingTranslationRequest("こんにちは", true), coordinator.peek())
    }

    @Test
    fun rememberPending_finalCandidateOverridesEarlierProvisional() {
        val coordinator = PendingTranslationCoordinator()

        coordinator.rememberPending("ありが", provisional = true)
        coordinator.rememberPending("ありがとう", provisional = false)

        assertEquals(PendingTranslationRequest("ありがとう", false), coordinator.peek())
    }

    @Test
    fun consumeReady_returnsPendingRequestAndClearsQueue() {
        val coordinator = PendingTranslationCoordinator()
        coordinator.rememberPending("テストです", provisional = false)

        assertEquals(PendingTranslationRequest("テストです", false), coordinator.consumeReady())
        assertNull(coordinator.peek())
        assertNull(coordinator.consumeReady())
    }

    @Test
    fun hasPending_reflectsQueueState() {
        val coordinator = PendingTranslationCoordinator()

        assertFalse(coordinator.hasPending())
        coordinator.rememberPending("テスト", provisional = true)
        assertTrue(coordinator.hasPending())
        coordinator.consumeReady()
        assertFalse(coordinator.hasPending())
    }
}
