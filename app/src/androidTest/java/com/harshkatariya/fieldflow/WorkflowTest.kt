package com.harshkatariya.fieldflow

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import com.harshkatariya.fieldflow.data.SyncDependencies
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.*

/** Exercises the shipped activity, Room, real ViewModel, and navigation. */
@RunWith(AndroidJUnit4::class)
class WorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun openDemo() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Continue as Alex Morgan").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Ready, Alex.").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithText("Continue as Alex Morgan").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Continue as Alex Morgan").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Ready, Alex.").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun offlineInspectionJourney() {
        openDemo()
        compose.onNodeWithText("Open work orders").performClick()
        compose.onAllNodesWithText("HVAC preventive service")[0].performScrollTo().performClick()
        compose.waitForIdle()
        val resume = compose.onAllNodesWithText("Resume inspection").fetchSemanticsNodes().isNotEmpty()
        compose.onNodeWithText(if (resume) "Resume inspection" else "Start inspection").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Draft saves automatically on this device.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Review inspection").performScrollTo().performClick()
        compose.onNodeWithText("Before you submit").performScrollTo().assertExists()
        compose.onNodeWithText("Submit inspection").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Continue editing").performScrollTo().performClick()
        compose.onAllNodesWithText("PASS")[0].performScrollTo().performClick()
        compose.onNodeWithText("YES").performScrollTo().performClick()
        compose.onNodeWithText("Reading (°C)").performScrollTo().performTextReplacement("24")
        compose.onAllNodesWithText("PASS")[1].performScrollTo().performClick()
        compose.onNodeWithText("Review inspection").performScrollTo().performClick()
        compose.onNodeWithText("Continue editing").performScrollTo().performClick()
        compose.onNodeWithText("Reading (°C)").performScrollTo().assertTextContains("24")
        compose.onNodeWithText("Review inspection").performScrollTo().performClick()
        compose.onNodeWithText("Submit inspection").performScrollTo().performClick()
        compose.onNodeWithText("Confirm submission").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sync center").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("PENDING")[0].performScrollTo().assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Sync center").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("PENDING")[0].performScrollTo().assertExists()
    }
    @Test fun searchEmptyAndManualScannerError() {
        openDemo()
        compose.onNodeWithText("Assets").performClick()
        compose.onNodeWithText("Search code, name or location").performTextInput("nonexistent-equipment")
        compose.onNodeWithText("No matching equipment.").assertExists()
        compose.onNodeWithText("Scan or enter equipment code").performClick()
        compose.onNodeWithText("Equipment code, e.g. FF-001").performScrollTo().performTextInput("unknown")
        compose.onNodeWithText("Find equipment").performScrollTo().performClick()
        compose.onNodeWithText("Unknown equipment:", substring = true).performScrollTo().assertExists()
    }
    @Test fun exhaustedOperationRemainsVisibleInSyncCenter() {
        openDemo()
        val deps = EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext(), SyncDependencies::class.java)
        val inspectionId = runBlocking {
            val id = deps.repository().start("wo-1")
            com.harshkatariya.fieldflow.core.Seed.history.first().answers.forEach { deps.repository().answer(id, it) }
            deps.repository().submit(id)
            val dao = deps.repository().database(deps.sessions().session.value!!).dao()
            val operation = dao.operationFor(id)!!
            dao.operation(operation.copy(status = "FAILED", attempts = 8, error = "Connection failed after eight attempts", failureKind = "TRANSIENT"))
            dao.inspection(dao.inspection(id)!!.copy(syncStatus = "FAILED"))
            id
        }
        compose.onNodeWithText("Sync").performClick()
        compose.onNodeWithText("FAILED").performScrollTo().assertExists()
        compose.onNodeWithText("Connection failed after eight attempts", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Inspection ${inspectionId.take(8)}").performScrollTo().performClick()
        compose.onNodeWithText("Inspection record").assertExists()
    }
    @Test fun cameraPhotoPersistsWithDefect() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.harshkatariya.fieldflow android.permission.CAMERA").close()
        openDemo()
        compose.onNodeWithText("Open work orders").performClick()
        compose.onAllNodesWithText("HVAC preventive service")[0].performScrollTo().performClick()
        val resume = compose.onAllNodesWithText("Resume inspection").fetchSemanticsNodes().isNotEmpty()
        compose.onNodeWithText(if (resume) "Resume inspection" else "Start inspection").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Draft saves automatically on this device.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Report a defect", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Description (at least 5 characters)").performTextInput("Fictional loose safety cover")
        compose.onNodeWithText("Save defect").performScrollTo().performClick()
        compose.onNodeWithText("Review inspection").performScrollTo().performClick()
        compose.onAllNodesWithText("Attach defect photo")[0].performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Capture photo") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Capture photo").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Inspection photo evidence").fetchSemanticsNodes().isNotEmpty() }
        val deps = EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext(), SyncDependencies::class.java)
        runBlocking {
            val db = deps.repository().database(deps.sessions().session.value!!)
            val drafts = db.dao().forWork("wo-1", "tech-alex")
            val files = drafts.flatMap { db.dao().photos(it.id) }
            assertTrue(files.isNotEmpty()); assertTrue(files.all { File(it.path).exists() && File(it.path).length() > 100 })
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Inspection photo evidence").fetchSemanticsNodes().isNotEmpty() }
    }
}
