// Adapted from APK Toolbox by bl3xand (GPL-3.0-only). Upstream: https://github.com/bl3xand/APK-Toolbox
package org.openclone.app.clone

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.DisplayMetrics
import android.util.TypedValue
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayOutputStream
import java.io.File
import org.xmlpull.v1.XmlPullParser

/**
 * Gives a clone a launcher icon that can be told apart from the original's: the same picture
 * with a coloured dot in the corner.
 *
 * Nothing is added to the app's resources. The file that defines the icon is swapped for an
 * equivalent one that stacks the dot on top of the app's own layers, so the size of the app and
 * of its resource table does not matter.
 */
enum class BadgePosition(val centerX: Float, val centerY: Float) {
    TOP_LEFT(0.24f, 0.24f),
    TOP_RIGHT(0.76f, 0.24f),
    BOTTOM_LEFT(0.24f, 0.76f),
    BOTTOM_RIGHT(0.76f, 0.76f)
}

class IconBadger(private val context: Context) {

    /**
     * Returns safe file replacements and a bounded report for every attempted icon resource.
     * Unsupported resources are left untouched; [seed] picks the colour of the clone badge.
     */
    fun replacements(
        app: ApplicationInfo,
        seed: String,
        position: BadgePosition = BadgePosition.BOTTOM_RIGHT,
        customIconFile: File? = null,
        addBadge: Boolean = true
    ): IconReplacementResult {
        val packageManager = context.packageManager
        val resources = packageManager.getResourcesForApplication(app)
        val color = Color.HSVToColor(floatArrayOf(((seed.hashCode() and 0x7FFFFFFF) % 360).toFloat(), 0.75f, 0.9f))
        val customBitmap = customIconFile?.takeIf { it.isFile && it.canRead() }
            ?.let { file -> runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }

        val diagnostics = ArrayList<IconReplacementDiagnostic>()
        var omittedDiagnostics = 0
        fun record(
            action: IconReplacementAction,
            resourceId: Int,
            resourcePath: String?,
            densityDpi: Int,
            status: IconDiagnosticStatus,
            detail: String
        ) {
            if (diagnostics.size < MAX_DIAGNOSTICS) {
                diagnostics += IconReplacementDiagnostic(action, resourceId, resourcePath, densityDpi, status, detail)
            } else {
                omittedDiagnostics++
            }
        }

        if (customIconFile != null && customBitmap == null) {
            record(
                IconReplacementAction.CUSTOM_ICON, 0, null, 0, IconDiagnosticStatus.FAILED,
                "The selected image is missing, unreadable, or could not be decoded."
            )
        }

        // Activities may carry an icon of their own, which the launcher prefers.
        val activityIcons = runCatching {
            packageManager.getPackageArchiveInfo(app.sourceDir, PackageManager.GET_ACTIVITIES)
                ?.activities?.map { it.icon }
        }.getOrNull().orEmpty()
        val iconIds = (listOf(app.icon) + activityIcons).filter { it != 0 }.distinct()
        val replacements = LinkedHashMap<String, ByteArray>()
        val visitedIconPaths = HashSet<String>()
        val visitedForegroundPaths = HashSet<String>()
        var badgeApplied = false
        var customIconApplied = false

        try {
            if (iconIds.isEmpty()) {
                if (customBitmap != null) {
                    record(
                        IconReplacementAction.CUSTOM_ICON, 0, null, 0, IconDiagnosticStatus.UNSUPPORTED,
                        "No launcher icon resource IDs were declared for this application."
                    )
                }
                if (addBadge) {
                    record(
                        IconReplacementAction.BADGE, 0, null, 0, IconDiagnosticStatus.UNSUPPORTED,
                        "No launcher icon resource IDs were declared for this application."
                    )
                }
            }

            for (icon in iconIds) {
                // Launchers may request a density different from the screen's density. Resolve
                // each qualifier separately and report each distinct resource path once.
                for (density in DENSITIES) {
                    val value = TypedValue()
                    val resolvedPath = runCatching {
                        if (density == 0) resources.getValue(icon, value, true)
                        else resources.getValueForDensity(icon, density, value, true)
                        value.string?.toString()
                    }
                    if (resolvedPath.isFailure) {
                        val error = resolvedPath.exceptionOrNull()
                        val reason = error?.message ?: error?.javaClass?.simpleName ?: "unknown error"
                        if (customBitmap != null) {
                            record(
                                IconReplacementAction.CUSTOM_ICON, icon, null, density, IconDiagnosticStatus.FAILED,
                                "Could not resolve the icon resource: " + reason.take(120)
                            )
                        }
                        if (addBadge) {
                            record(
                                IconReplacementAction.BADGE, icon, null, density, IconDiagnosticStatus.FAILED,
                                "Could not resolve the icon resource: " + reason.take(120)
                            )
                        }
                        continue
                    }

                    val path = resolvedPath.getOrNull()
                    if (path.isNullOrBlank()) {
                        if (customBitmap != null) {
                            record(
                                IconReplacementAction.CUSTOM_ICON, icon, null, density, IconDiagnosticStatus.FAILED,
                                "The resource did not resolve to a file-backed drawable."
                            )
                        }
                        if (addBadge) {
                            record(
                                IconReplacementAction.BADGE, icon, null, density, IconDiagnosticStatus.FAILED,
                                "The resource did not resolve to a file-backed drawable."
                            )
                        }
                        continue
                    }
                    if (!visitedIconPaths.add(path)) continue

                    when (IconResourceKind.fromPath(path)) {
                        IconResourceKind.PNG -> {
                            if (customBitmap == null && !addBadge) continue
                            val replacement = runCatching {
                                bitmapIcon(resources, icon, density, color, position, customBitmap, addBadge)
                            }
                            if (replacement.isSuccess) {
                                replacements[path] = replacement.getOrThrow()
                                if (customBitmap != null) {
                                    customIconApplied = true
                                    record(
                                        IconReplacementAction.CUSTOM_ICON, icon, path, density, IconDiagnosticStatus.APPLIED,
                                        "Replaced the raster launcher icon."
                                    )
                                }
                                if (addBadge) {
                                    badgeApplied = true
                                    record(
                                        IconReplacementAction.BADGE, icon, path, density, IconDiagnosticStatus.APPLIED,
                                        "Added the clone badge to the raster launcher icon."
                                    )
                                }
                            } else {
                                val error = replacement.exceptionOrNull()
                                val reason = error?.message ?: error?.javaClass?.simpleName ?: "unknown error"
                                if (customBitmap != null) {
                                    record(
                                        IconReplacementAction.CUSTOM_ICON, icon, path, density, IconDiagnosticStatus.FAILED,
                                        "Could not render the replacement image: " + reason.take(120)
                                    )
                                }
                                if (addBadge) {
                                    record(
                                        IconReplacementAction.BADGE, icon, path, density, IconDiagnosticStatus.FAILED,
                                        "Could not render the clone badge: " + reason.take(120)
                                    )
                                }
                            }
                        }

                        IconResourceKind.XML -> {
                            if (customBitmap != null) {
                                val foregroundResult = runCatching { adaptiveForegroundId(resources, icon) }
                                val foreground = foregroundResult.getOrNull()
                                if (foregroundResult.isFailure) {
                                    val error = foregroundResult.exceptionOrNull()
                                    record(
                                        IconReplacementAction.CUSTOM_ICON, icon, path, density, IconDiagnosticStatus.FAILED,
                                        "Could not inspect the adaptive icon foreground: " +
                                            (error?.message ?: error?.javaClass?.simpleName ?: "unknown error").take(120)
                                    )
                                } else if (foreground == null) {
                                    val root = runCatching { xmlRootName(resources, icon) }.getOrNull() ?: "unknown"
                                    if (root == "vector") {
                                        val replacement = runCatching { vectorIcon(customBitmap) }
                                        if (replacement.isSuccess) {
                                            replacements[path] = replacement.getOrThrow()
                                            customIconApplied = true
                                            record(
                                                IconReplacementAction.CUSTOM_ICON, icon, path, density, IconDiagnosticStatus.APPLIED,
                                                "Replaced a direct VectorDrawable using a bounded 48x48 raster-to-vector conversion (maximum 32 colors)."
                                            )
                                        } else {
                                            val error = replacement.exceptionOrNull()
                                            record(
                                                IconReplacementAction.CUSTOM_ICON, icon, path, density, IconDiagnosticStatus.FAILED,
                                                "Could not convert the custom image to VectorDrawable paths: " +
                                                    (error?.message ?: error?.javaClass?.simpleName ?: "unknown error").take(120)
                                            )
                                        }
                                    } else {
                                        record(
                                            IconReplacementAction.CUSTOM_ICON, icon, path, density, IconDiagnosticStatus.UNSUPPORTED,
                                            "XML root <" + root + "> is not a plain adaptive icon with a direct foreground reference or a direct vector drawable."
                                        )
                                    }
                                } else {
                                    var resolvedForeground = false
                                    for (layerDensity in DENSITIES) {
                                        val layerValue = TypedValue()
                                        val layerPathResult = runCatching {
                                            if (layerDensity == 0) resources.getValue(foreground, layerValue, true)
                                            else resources.getValueForDensity(foreground, layerDensity, layerValue, true)
                                            layerValue.string?.toString()
                                        }
                                        if (layerPathResult.isFailure) {
                                            val error = layerPathResult.exceptionOrNull()
                                            record(
                                                IconReplacementAction.CUSTOM_ICON, foreground, null, layerDensity, IconDiagnosticStatus.FAILED,
                                                "Could not resolve the adaptive foreground: " +
                                                    (error?.message ?: error?.javaClass?.simpleName ?: "unknown error").take(120)
                                            )
                                            continue
                                        }
                                        val layerPath = layerPathResult.getOrNull()
                                        if (layerPath.isNullOrBlank()) {
                                            record(
                                                IconReplacementAction.CUSTOM_ICON, foreground, null, layerDensity, IconDiagnosticStatus.FAILED,
                                                "The adaptive foreground did not resolve to a file-backed drawable."
                                            )
                                            continue
                                        }
                                        resolvedForeground = true
                                        if (!visitedForegroundPaths.add(layerPath)) continue

                                        when (IconResourceKind.fromPath(layerPath)) {
                                            IconResourceKind.PNG -> {
                                                val replacement = runCatching {
                                                    bitmapIcon(
                                                        resources, foreground, layerDensity, color, position,
                                                        customBitmap, addBadge = false
                                                    )
                                                }
                                                if (replacement.isSuccess) {
                                                    replacements[layerPath] = replacement.getOrThrow()
                                                    customIconApplied = true
                                                    record(
                                                        IconReplacementAction.CUSTOM_ICON, foreground, layerPath, layerDensity, IconDiagnosticStatus.APPLIED,
                                                        "Replaced a direct PNG adaptive-icon foreground."
                                                    )
                                                } else {
                                                    val error = replacement.exceptionOrNull()
                                                    record(
                                                        IconReplacementAction.CUSTOM_ICON, foreground, layerPath, layerDensity, IconDiagnosticStatus.FAILED,
                                                        "Could not render the adaptive foreground: " +
                                                            (error?.message ?: error?.javaClass?.simpleName ?: "unknown error").take(120)
                                                    )
                                                }
                                            }

                                            IconResourceKind.XML -> {
                                                val root = runCatching { xmlRootName(resources, foreground) }.getOrNull() ?: "unknown"
                                                if (root == "vector") {
                                                    val replacement = runCatching { vectorIcon(customBitmap) }
                                                    if (replacement.isSuccess) {
                                                        replacements[layerPath] = replacement.getOrThrow()
                                                        customIconApplied = true
                                                        record(
                                                            IconReplacementAction.CUSTOM_ICON, foreground, layerPath, layerDensity, IconDiagnosticStatus.APPLIED,
                                                            "Replaced a VectorDrawable adaptive foreground using a bounded 48x48 raster-to-vector conversion (maximum 32 colors)."
                                                        )
                                                    } else {
                                                        val error = replacement.exceptionOrNull()
                                                        record(
                                                            IconReplacementAction.CUSTOM_ICON, foreground, layerPath, layerDensity, IconDiagnosticStatus.FAILED,
                                                            "Could not convert the custom image to VectorDrawable paths: " +
                                                                (error?.message ?: error?.javaClass?.simpleName ?: "unknown error").take(120)
                                                        )
                                                    }
                                                } else {
                                                    val detail = if (root == "layer-list") {
                                                        "Nested layer-list foreground traversal is not implemented yet."
                                                    } else {
                                                        "XML foreground type <" + root + "> is not supported yet."
                                                    }
                                                    record(
                                                        IconReplacementAction.CUSTOM_ICON, foreground, layerPath, layerDensity, IconDiagnosticStatus.UNSUPPORTED,
                                                        detail
                                                    )
                                                }
                                            }

                                            IconResourceKind.OTHER -> {
                                                val extension = layerPath.substringAfterLast('.', "").ifBlank { "unknown" }
                                                record(
                                                    IconReplacementAction.CUSTOM_ICON, foreground, layerPath, layerDensity, IconDiagnosticStatus.UNSUPPORTED,
                                                    "Adaptive foreground resource type ." + extension + " is not supported yet."
                                                )
                                            }
                                        }
                                    }
                                    if (!resolvedForeground) {
                                        record(
                                            IconReplacementAction.CUSTOM_ICON, foreground, path, density, IconDiagnosticStatus.UNSUPPORTED,
                                            "No file-backed adaptive foreground variant could be resolved."
                                        )
                                    }
                                }
                            }

                            if (addBadge) {
                                val replacement = runCatching { adaptiveIcon(resources, icon, color, position) }
                                when {
                                    replacement.isSuccess && replacement.getOrNull() != null -> {
                                        replacements[path] = replacement.getOrThrow()!!
                                        badgeApplied = true
                                        record(
                                            IconReplacementAction.BADGE, icon, path, density, IconDiagnosticStatus.APPLIED,
                                            "Updated the adaptive-icon wrapper while retaining its background and supported layers."
                                        )
                                    }
                                    replacement.isFailure -> {
                                        val error = replacement.exceptionOrNull()
                                        record(
                                            IconReplacementAction.BADGE, icon, path, density, IconDiagnosticStatus.FAILED,
                                            "Could not rewrite the adaptive-icon XML: " +
                                                (error?.message ?: error?.javaClass?.simpleName ?: "unknown error").take(120)
                                        )
                                    }
                                    else -> record(
                                        IconReplacementAction.BADGE, icon, path, density, IconDiagnosticStatus.UNSUPPORTED,
                                        "The XML is not a plain adaptive icon with direct background and foreground references."
                                    )
                                }
                            }
                        }

                        IconResourceKind.OTHER -> {
                            val extension = path.substringAfterLast('.', "").ifBlank { "unknown" }
                            if (customBitmap != null) {
                                record(
                                    IconReplacementAction.CUSTOM_ICON, icon, path, density, IconDiagnosticStatus.UNSUPPORTED,
                                    "Launcher resource type ." + extension + " is not supported for custom replacement yet."
                                )
                            }
                            if (addBadge) {
                                record(
                                    IconReplacementAction.BADGE, icon, path, density, IconDiagnosticStatus.UNSUPPORTED,
                                    "Clone badge injection supports PNG resources and plain adaptive-icon XML only."
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            customBitmap?.recycle()
        }

        return IconReplacementResult(
            replacements = replacements.toMap(),
            badgeApplied = badgeApplied,
            customIconApplied = customIconApplied,
            diagnostics = diagnostics.toList(),
            omittedDiagnostics = omittedDiagnostics
        )
    }

    private fun xmlRootName(resources: Resources, resourceId: Int): String? {
        var root: String? = null
        resources.getXml(resourceId).use { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
                event = parser.next()
            }
            if (event == XmlPullParser.START_TAG) root = parser.name
        }
        return root
    }

    /** Returns the direct foreground resource ID of a plain adaptive-icon XML resource. */
    private fun adaptiveForegroundId(resources: Resources, icon: Int): Int? {
        var depth = 0
        var foreground = 0
        resources.getXml(icon).use { parser ->
            while (true) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth == 1 && parser.name != "adaptive-icon") return null
                        if (depth == 2 && parser.name == "foreground") {
                            foreground = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0)
                        }
                    }
                }
            }
        }
        return foreground.takeIf { it != 0 }
    }

    /**
     * The app's `<adaptive-icon>` again, with the same background, and a foreground that is the
     * original one plus the dot. Null if the original is not a plain adaptive icon.
     */
    private fun adaptiveIcon(resources: Resources, icon: Int, color: Int, position: BadgePosition): ByteArray? {
        val layers = HashMap<String, Int>()
        resources.getXml(icon).use { parser ->
            var depth = 0
            while (true) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth == 1 && parser.name != "adaptive-icon") return null
                        if (depth == 2) layers[parser.name] = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0)
                    }
                }
            }
        }
        val background = layers["background"]?.takeIf { it != 0 } ?: return null
        val foreground = layers["foreground"]?.takeIf { it != 0 } ?: return null

        val document = ResXmlDocument()
        val root = document.newElement("adaptive-icon")
        root.newElement("background").reference(background)
        val stack = root.newElement("foreground").newElement("layer-list")
        stack.newElement("item").reference(foreground)
        // A white disc with a coloured one on top. A stroke would do for the ring, but its width
        // is a fixed length while everything else here is a share of the icon.
        stack.newElement("item").disc(DOT_SHARE + 2 * RING_SHARE, Color.WHITE, position.centerX, position.centerY)
        stack.newElement("item").disc(DOT_SHARE, color, position.centerX, position.centerY)
        // Themed icons are drawn from this layer alone, in one colour: the dot goes here too,
        // or a clone on a themed home screen would look exactly like the original.
        layers["monochrome"]?.takeIf { it != 0 }?.let { monochrome ->
            val themed = root.newElement("monochrome").newElement("layer-list")
            themed.newElement("item").reference(monochrome)
            themed.newElement("item").disc(DOT_SHARE + 2 * RING_SHARE, Color.WHITE, position.centerX, position.centerY)
        }
        document.refreshFull()
        return document.bytes
    }

    /**
     * A filled circle, [share] of the visible icon across, centred where the dot belongs. It is
     * placed with insets, which are fractions of the layer, so it keeps its place and size at
     * any icon size.
     */
    private fun ResXmlElement.disc(share: Float, color: Int, centerX: Float, centerY: Float) {
        val startX = (LAYER_MARGIN + VISIBLE_DP * (centerX - share / 2)) / LAYER_DP
        val endX = 1f - (LAYER_MARGIN + VISIBLE_DP * (centerX + share / 2)) / LAYER_DP
        val startY = (LAYER_MARGIN + VISIBLE_DP * (centerY - share / 2)) / LAYER_DP
        val endY = 1f - (LAYER_MARGIN + VISIBLE_DP * (centerY + share / 2)) / LAYER_DP
        val shape = newElement("inset").apply {
            fraction("insetLeft", android.R.attr.insetLeft, startX)
            fraction("insetTop", android.R.attr.insetTop, startY)
            fraction("insetRight", android.R.attr.insetRight, endX)
            fraction("insetBottom", android.R.attr.insetBottom, endY)
        }.newElement("shape")
        shape.value("shape", android.R.attr.shape, ValueType.DEC, SHAPE_OVAL)
        shape.newElement("solid").value("color", android.R.attr.color, ValueType.COLOR_ARGB8, color)
    }

    /**
     * Re-encodes the selected raster image as a compact VectorDrawable so resource-table entries
     * that point to XML keep their expected drawable type. The bounded 48px viewport and 32-colour
     * palette intentionally trade a little fidelity for predictable APK size and parse cost.
     */
    private fun vectorIcon(customBitmap: Bitmap): ByteArray {
        val size = VectorIconPathEncoder.VIEWPORT_SIZE
        val raster = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val scale = minOf(size.toFloat() / customBitmap.width, size.toFloat() / customBitmap.height)
            val width = customBitmap.width * scale
            val height = customBitmap.height * scale
            val destination = android.graphics.RectF(
                (size - width) / 2f,
                (size - height) / 2f,
                (size + width) / 2f,
                (size + height) / 2f
            )
            Canvas(raster).drawBitmap(
                customBitmap,
                null,
                destination,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            val pixels = IntArray(size * size)
            raster.getPixels(pixels, 0, size, 0, 0, size, size)
            return VectorDrawableXmlEncoder.encode(size, size, pixels)
        } finally {
            raster.recycle()
        }
    }

    /** A plain bitmap icon: the same picture, redrawn with the dot on it. */
    private fun bitmapIcon(resources: Resources, icon: Int, density: Int, color: Int, position: BadgePosition, customBitmap: Bitmap? = null, addBadge: Boolean = true): ByteArray {
        val drawable = if (customBitmap == null) {
            if (density == 0) resources.getDrawable(icon, null) else resources.getDrawableForDensity(icon, density, null) ?: error("Launcher icon resource not found for density $density")
        } else null
        val size = (drawable?.intrinsicWidth ?: customBitmap!!.width).coerceIn(MIN_BITMAP, MAX_BITMAP)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (customBitmap != null) {
            val scale = minOf(size.toFloat() / customBitmap.width, size.toFloat() / customBitmap.height)
            val width = customBitmap.width * scale
            val height = customBitmap.height * scale
            val destination = android.graphics.RectF((size - width) / 2f, (size - height) / 2f, (size + width) / 2f, (size + height) / 2f)
            canvas.drawBitmap(customBitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            drawable!!.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // A launcher sizes a plain picture by how much of it is filled, so the dot would come
        // out differently on every app. A frame too faint to see makes every picture count as
        // filled edge to edge: all of them are then shrunk alike, and so are their dots.
        if (addBadge) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = Color.argb(FRAME_ALPHA, 255, 255, 255)
            canvas.drawRect(0.5f, 0.5f, size - 0.5f, size - 0.5f, paint)
            paint.style = Paint.Style.FILL
    
            val ring = size * RING_SHARE / LEGACY_SCALE
            val radius = size * DOT_SHARE / LEGACY_SCALE / 2
            // As far out as the dot of an adaptive icon, or as far as the picture has room for.
            val centerX = if (position.centerX < 0.5f) {
                maxOf(size * (0.5f + (position.centerX - 0.5f) / LEGACY_SCALE), radius + ring)
            } else {
                minOf(size * (0.5f + (position.centerX - 0.5f) / LEGACY_SCALE), size - radius - ring)
            }
            val centerY = if (position.centerY < 0.5f) {
                maxOf(size * (0.5f + (position.centerY - 0.5f) / LEGACY_SCALE), radius + ring)
            } else {
                minOf(size * (0.5f + (position.centerY - 0.5f) / LEGACY_SCALE), size - radius - ring)
            }
            paint.color = Color.WHITE
            canvas.drawCircle(centerX, centerY, radius + ring, paint)
            paint.color = color
            canvas.drawCircle(centerX, centerY, radius, paint)
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun ResXmlElement.reference(resourceId: Int) =
        value("drawable", android.R.attr.drawable, ValueType.REFERENCE, resourceId)

    private fun ResXmlElement.fraction(name: String, attribute: Int, share: Float) =
        value(name, attribute, ValueType.FRACTION, complex(share, TypedValue.COMPLEX_UNIT_FRACTION))

    /**
     * A dimension or fraction in the fixed-point form compiled resources store them in: a 24-bit
     * mantissa, a radix saying where its binary point is, and the unit.
     */
    private fun complex(value: Float, unit: Int): Int {
        val bits = (value * (1 shl 23) + 0.5f).toLong()
        val (radix, shift) = when {
            bits and 0x7FFFFFL == 0L -> COMPLEX_RADIX_23P0 to 23
            bits and 0x7FFFFFL.inv() == 0L -> COMPLEX_RADIX_0P23 to 0
            bits and 0x7FFFFFFFL.inv() == 0L -> COMPLEX_RADIX_8P15 to 8
            bits and 0x7FFFFFFFFFL.inv() == 0L -> COMPLEX_RADIX_16P7 to 16
            else -> COMPLEX_RADIX_23P0 to 23
        }
        val mantissa = ((bits shr shift) and 0xFFFFFF).toInt()
        return (mantissa shl 8) or (radix shl 4) or unit
    }

    private fun ResXmlElement.value(name: String, attribute: Int, type: ValueType, data: Int) {
        getOrCreateAndroidAttribute(name, attribute).apply {
            setValueType(type)
            setData(data)
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val SHAPE_OVAL = 1
        const val COMPLEX_RADIX_23P0 = 0
        const val COMPLEX_RADIX_16P7 = 1
        const val COMPLEX_RADIX_8P15 = 2
        const val COMPLEX_RADIX_0P23 = 3

        // An adaptive layer is 108dp, of which the middle 72dp is seen.
        const val LAYER_DP = 108f
        const val VISIBLE_DP = 72f

        // One dot for every kind of icon, given as shares of the part of the icon that is
        // seen: how far across the coloured disc is, how wide the white ring around it, and
        // where the centre is, counted from the top left. The place is the bottom right corner:
        // as far out along the diagonal as a round mask leaves room for the whole dot, so that
        // it covers as little of the picture as it can. A plain picture ends sooner than that
        // once it is shrunk (see LEGACY_SCALE); its dot sits in the corner of the picture.
        const val DOT_SHARE = 0.2f
        const val RING_SHARE = 0.02f
        const val DOT_CENTER = 0.76f

        private const val LAYER_MARGIN = (LAYER_DP - VISIBLE_DP) / 2

        // A plain picture is shown smaller than an adaptive icon: a launcher shrinks one that
        // is filled edge to edge to about this share to fit it inside its mask. The dot is
        // drawn larger and further out by as much, so that on screen it lands where the dot
        // of an adaptive icon does, at the same size.
        const val LEGACY_SCALE = 0.62f

        /** Just above what a launcher still counts as a visible pixel. */
        const val FRAME_ALPHA = 48
        const val MAX_DIAGNOSTICS = 64

        const val MIN_BITMAP = 48
        const val MAX_BITMAP = 512

        /** The screen's own density first, then every bucket a launcher may ask for. */
        val DENSITIES = intArrayOf(
            0, DisplayMetrics.DENSITY_LOW, DisplayMetrics.DENSITY_MEDIUM, DisplayMetrics.DENSITY_HIGH,
            DisplayMetrics.DENSITY_XHIGH, DisplayMetrics.DENSITY_XXHIGH, DisplayMetrics.DENSITY_XXXHIGH,
        )
    }
}
