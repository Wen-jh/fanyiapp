package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class VoskModelManagerTest {
    @Test
    fun resolveModelState_reportsPreparingWhenModelMissing() {
        val root = createTempDir(prefix = "vosk-model-test-")
        val manager = VoskModelManager()

        val state = manager.resolveModelState(root)

        assertEquals(ModelPreparationState.PREPARING, state.state)
        root.deleteRecursively()
    }

    @Test
    fun resolveModelState_reportsReadyWhenModelMarkerExists() {
        val root = createTempDir(prefix = "vosk-model-test-")
        val modelDir = File(root, VoskModelManager.MODEL_DIR_NAME)
        modelDir.mkdirs()
        File(modelDir, ".ready").writeText("ok")
        val manager = VoskModelManager()

        val state = manager.resolveModelState(root)

        assertEquals(ModelPreparationState.READY, state.state)
        root.deleteRecursively()
    }

    @Test
    fun hasRequiredModelFiles_rejectsPlaceholderOnlyDirectory() {
        val root = createTempDir(prefix = "vosk-model-test-")
        File(root, "README.txt").writeText("placeholder")
        val manager = VoskModelManager()

        assertEquals(false, manager.hasRequiredModelFiles(root))
        root.deleteRecursively()
    }

    @Test
    fun hasRequiredModelFiles_acceptsExpectedVoskLayout() {
        val root = createTempDir(prefix = "vosk-model-test-")
        File(root, "am").mkdirs()
        File(root, "conf").mkdirs()
        File(root, "graph/phones").mkdirs()
        File(root, "am/final.mdl").writeText("x")
        File(root, "conf/model.conf").writeText("x")
        File(root, "graph/Gr.fst").writeText("x")
        File(root, "graph/HCLr.fst").writeText("x")
        File(root, "graph/phones/word_boundary.int").writeText("x")
        val manager = VoskModelManager()

        assertEquals(true, manager.hasRequiredModelFiles(root))
        root.deleteRecursively()
    }

    @Test
    fun hasRequiredModelFiles_rejectsZipStyleWrapperDirectoryUntilUnwrapped() {
        val root = createTempDir(prefix = "vosk-model-test-")
        val wrapped = File(root, "vosk-model-small-ja-0.22")
        File(wrapped, "am").mkdirs()
        File(wrapped, "conf").mkdirs()
        File(wrapped, "am/final.mdl").writeText("x")
        File(wrapped, "conf/model.conf").writeText("x")
        val manager = VoskModelManager()

        assertEquals(false, manager.hasRequiredModelFiles(root))
        assertEquals(true, manager.hasRequiredModelFiles(wrapped))
        root.deleteRecursively()
    }
}
