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
    fun rememberPending_keepsLongerFinalCandidateOverShorterFinal() {
        val coordinator = PendingTranslationCoordinator()

        coordinator.rememberPending("ありが", provisional = false)
        coordinator.rememberPending("ありがとうございます", provisional = false)

        assertEquals(PendingTranslationRequest("ありがとうございます", false), coordinator.peek())
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

    @Test
    fun requeueInFlight_preservesActiveWhenNothingElseQueued() {
        val coordinator = PendingTranslationCoordinator()
        coordinator.markInFlight("こんにちは", provisional = true)

        val requeued = coordinator.requeueInFlight()

        assertEquals(PendingTranslationRequest("こんにちは", true), requeued)
        assertEquals(PendingTranslationRequest("こんにちは", true), coordinator.consumeReady())
    }

    @Test
    fun hasInFlight_reflectsCurrentlyRunningTranslation() {
        val coordinator = PendingTranslationCoordinator()

        assertFalse(coordinator.hasInFlight())
        coordinator.markInFlight("こんにちは", provisional = true)
        assertTrue(coordinator.hasInFlight())
        coordinator.consumeReadyAfter("こんにちは")
        assertFalse(coordinator.hasInFlight())
    }
}
