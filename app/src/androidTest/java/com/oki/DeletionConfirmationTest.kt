package com.oki

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.oki.core.security.DeletionAuthenticator
import com.oki.core.security.LocalDeletionAuthenticator
import com.oki.core.storage.Appearance
import com.oki.core.ui.ConfirmDelete
import com.oki.core.ui.OkiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DeletionConfirmationTest {
    @get:Rule val rule = createComposeRule()

    private class FakeAuthenticator : DeletionAuthenticator {
        var callback: ((Boolean, String?) -> Unit)? = null
        var requests = 0

        override fun authenticate(title: String, result: (Boolean, String?) -> Unit) {
            requests++
            callback = result
        }

        override fun cancel() = Unit
    }

    @Test
    fun confirmationWaitsForAuthenticationAndDeletesOnlyOnce() {
        val auth = FakeAuthenticator()
        var deletes = 0
        rule.setContent {
            var open by remember { mutableStateOf(true) }
            CompositionLocalProvider(LocalDeletionAuthenticator provides auth) {
                OkiTheme(Appearance.DARK) {
                    if (open)
                        ConfirmDelete(
                            "Delete all app data?",
                            "A test fixture only.",
                            { open = false },
                            { deletes++ },
                            requireAuthentication = true,
                        )
                }
            }
        }
        rule.onNodeWithText("Verify & delete").performClick()
        rule.onNodeWithText("Verifying…").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(0, deletes)
            assertEquals(1, auth.requests)
            auth.callback!!(true, null)
            auth.callback!!(true, null)
        }
        rule.runOnIdle { assertEquals(1, deletes) }
    }

    @Test
    fun failedAuthenticationAndDialogCancellationNeverDelete() {
        val auth = FakeAuthenticator()
        var deletes = 0
        rule.setContent {
            var open by remember { mutableStateOf(true) }
            CompositionLocalProvider(LocalDeletionAuthenticator provides auth) {
                OkiTheme(Appearance.DARK) {
                    if (open)
                        ConfirmDelete(
                            "Clear completed tasks?",
                            "A test fixture only.",
                            { open = false },
                            { deletes++ },
                            requireAuthentication = true,
                        )
                }
            }
        }
        rule.onNodeWithText("Verify & delete").performClick()
        rule.runOnIdle { auth.callback!!(false, "Verification unavailable") }
        rule.onNodeWithText("Verification unavailable").assertExists()
        rule.runOnIdle { assertEquals(0, deletes) }
        rule.onNodeWithText("Verify & delete").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle {
            auth.callback!!(true, null)
            assertEquals(0, deletes)
        }
    }

    @Test
    fun individualDeletionNeedsOnlyConfirmationAndCancelKeepsTheRecord() {
        val auth = FakeAuthenticator()
        var deletes = 0
        var open by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalDeletionAuthenticator provides auth) {
                OkiTheme(Appearance.DARK) {
                    if (open)
                        ConfirmDelete(
                            "Delete task?",
                            "A test fixture only.",
                            { open = false },
                            { deletes++ },
                        )
                }
            }
        }
        rule.onNodeWithText("Phone verification required.").assertDoesNotExist()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle {
            assertEquals(0, deletes)
            assertEquals(0, auth.requests)
            open = true
        }
        rule.onNodeWithText("Delete", substring = false).performClick()
        rule.runOnIdle {
            assertEquals(1, deletes)
            assertEquals(0, auth.requests)
        }
    }
}
