package org.openclone.app.clone

import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType

/** Builds a compiled binary VectorDrawable resource from bounded path data. */
internal object VectorDrawableXmlEncoder {

    fun encode(width: Int, height: Int, pixels: IntArray): ByteArray {
        val paths = VectorIconPathEncoder.encode(width, height, pixels)
        require(paths.isNotEmpty()) { "The selected icon has no visible pixels to encode." }

        val document = ResXmlDocument()
        val root = document.newElement("vector")
        root.stringAttribute("width", android.R.attr.width, width.toString() + "dp")
        root.stringAttribute("height", android.R.attr.height, height.toString() + "dp")
        root.stringAttribute("viewportWidth", android.R.attr.viewportWidth, width.toString())
        root.stringAttribute("viewportHeight", android.R.attr.viewportHeight, height.toString())

        paths.forEach { vectorPath ->
            val path = root.newElement("path")
            path.getOrCreateAndroidAttribute("fillColor", android.R.attr.fillColor).apply {
                setValueType(ValueType.COLOR_ARGB8)
                setData(vectorPath.fillColor)
            }
            path.stringAttribute("pathData", android.R.attr.pathData, vectorPath.pathData)
        }

        document.refreshFull()
        return document.bytes
    }

    private fun ResXmlElement.stringAttribute(name: String, attributeId: Int, value: String) {
        getOrCreateAndroidAttribute(name, attributeId).setValueAsString(value)
    }
}
