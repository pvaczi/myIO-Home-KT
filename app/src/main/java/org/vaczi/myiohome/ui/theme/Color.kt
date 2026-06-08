package org.vaczi.myiohome.ui.theme

import androidx.compose.ui.graphics.Color

// --- Dynamic/blue theme fallback (used when dynamic color is unavailable, < Android 12) ----------
val Blue80 = Color(0xFFA8C8FF)
val BlueGrey80 = Color(0xFFBFC7DC)
val Cyan80 = Color(0xFF9CD5E0)

val Blue40 = Color(0xFF2C5DA8)
val BlueGrey40 = Color(0xFF565E71)
val Cyan40 = Color(0xFF246A78)

// --- Classic myIO "green" theme: AMOLED-black background + saturated dark greens -----------------
// Recreates the original PV app look (black background, deep/saturated greens — not pastel). Always
// rendered dark (see Theme.kt). Greens are accessible against pure black; the black surfaces are a
// deliberate AMOLED choice (M3's default dark surface is a slightly-raised tone, not #000000).
val GreenPrimaryDark = Color(0xFF4CC95F)          // saturated accent (sliders, switches, dropdown caret)
val GreenOnPrimaryDark = Color(0xFF00390F)
val GreenPrimaryContainerDark = Color(0xFF0F4A1C) // lit tile — deep green like the old #004411/#006611
val GreenOnPrimaryContainerDark = Color(0xFF9BF0A0)
val GreenSecondaryDark = Color(0xFFA9CCA4)
val GreenOnSecondaryDark = Color(0xFF14341A)
val GreenSecondaryContainerDark = Color(0xFF21401F)
val GreenOnSecondaryContainerDark = Color(0xFFC5E8BF)
val GreenTertiaryDark = Color(0xFF6FD0A8)
val GreenOnTertiaryDark = Color(0xFF003824)
val GreenTertiaryContainerDark = Color(0xFF0F4D38)
val GreenOnTertiaryContainerDark = Color(0xFF8CECC4)

// Black surfaces. surfaceVariant = the unlit (off) tile background — a very dark green-grey.
val GreenBackgroundDark = Color(0xFF000000)
val GreenOnBackgroundDark = Color(0xFFDDE5D8)
val GreenSurfaceDark = Color(0xFF000000)
val GreenOnSurfaceDark = Color(0xFFDDE5D8)
val GreenSurfaceVariantDark = Color(0xFF1B231A)
val GreenOnSurfaceVariantDark = Color(0xFFBFC9BC)
val GreenSurfaceContainerLowestDark = Color(0xFF000000)
val GreenSurfaceContainerLowDark = Color(0xFF0B0F0A)
val GreenSurfaceContainerDark = Color(0xFF11160F)
val GreenSurfaceContainerHighDark = Color(0xFF1B211A)
val GreenSurfaceContainerHighestDark = Color(0xFF262C24)
val GreenOutlineDark = Color(0xFF8C938A)
