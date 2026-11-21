package tech.tamerin.contextfriendpro

import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds use the App Check debug provider. Its token appears in logcat; register it in the
 * Firebase console (App Check > Apps > Manage debug tokens). Never commit or ship the token.
 */
fun installAppCheck(app: FirebaseApp) {
    FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
}
