package org.vaczi.myiohome.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Android 17 (API 37) gates plain LAN access behind the runtime permission ACCESS_LOCAL_NETWORK for apps
 * targeting 37. This app is a LAN client, so without it nothing works. Below API 37 the permission does not
 * exist and LAN access comes with INTERNET.
 */
enum class LocalNetworkGate {
    /** Network use allowed (permission granted, or not needed on this Android version). */
    OPEN,

    /** Not asked yet: show the rationale, then request. */
    ASK,

    /** Denied, the system dialog can still be shown: offer to request again. */
    DENIED_RETRY,

    /** Denied for good (or "don't ask again"): only the system settings page can grant it. */
    DENIED_SETTINGS,
    ;

    /** Polling and discovery may run only when the gate is open. */
    val allowsNetwork: Boolean get() = this == OPEN

    companion object {
        const val PERMISSION: String = Manifest.permission.ACCESS_LOCAL_NETWORK
        const val FIRST_API_WITH_PERMISSION = 37

        /**
         * Pure decision. [denied]: the request was answered with "no" in this session;
         * [showRationale]: `shouldShowRequestPermissionRationale` right after that denial.
         */
        fun decide(sdkInt: Int, granted: Boolean, denied: Boolean, showRationale: Boolean): LocalNetworkGate = when {
            sdkInt < FIRST_API_WITH_PERMISSION -> OPEN
            granted -> OPEN
            !denied -> ASK
            showRationale -> DENIED_RETRY
            else -> DENIED_SETTINGS
        }

        fun isGranted(context: Context): Boolean =
            Build.VERSION.SDK_INT < FIRST_API_WITH_PERMISSION ||
                ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}

/** Opens this app's system settings page (where the permission can be granted by hand). */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Full-screen explanation shown instead of the app content while the local-network permission is missing. */
@Composable
fun LocalNetworkGateScreen(gate: LocalNetworkGate, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Helyi hálózati engedély", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                when (gate) {
                    LocalNetworkGate.ASK ->
                        "A myIO-eszközök a helyi hálózaton érhetők el – ehhez az Android 17 külön engedélyt kér."
                    LocalNetworkGate.DENIED_RETRY ->
                        "Az engedély nélkül az alkalmazás nem éri el a myIO-eszközöket a helyi hálózaton."
                    else ->
                        "Az engedély nélkül az alkalmazás nem éri el a myIO-eszközöket a helyi hálózaton. " +
                            "Az Android már nem kérdez újra: add meg az engedélyt a rendszerbeállításokban " +
                            "(Engedélyek → Közeli eszközök)."
                },
                textAlign = TextAlign.Center,
            )
            if (gate == LocalNetworkGate.DENIED_SETTINGS) {
                Button(onClick = onOpenSettings) { Text("Beállítások megnyitása") }
            } else {
                Button(onClick = onRequest) {
                    Text(if (gate == LocalNetworkGate.ASK) "Tovább" else "Engedély kérése újra")
                }
            }
        }
    }
}
