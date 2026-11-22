package org.openclone.app.clone

import android.util.TypedValue
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
        root.dimensionAttribute("width", android.R.attr.width, width.toFloat())
        root.dimensionAttribute("height", android.R.attr.height, height.toFloat())
        root.floatAttribute("viewportWidth", android.R.attr.viewportWidth, width.toFloat())
        root.floatAttribute("viewportHeight", android.R.attr.viewportHeight, height.toFloat())

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

    private fun ResXmlElement.floatAttribute(name: String, attributeId: Int, value: Float) {
        getOrCreateAndroidAttribute(name, attributeId).apply {
            setValueType(ValueType.FLOAT)
            setData(java.lang.Float.floatToIntBits(value))
        }
    }

    private fun ResXmlElement.dimensionAttribute(name: String, attributeId: Int, value: Float) {
        getOrCreateAndroidAttribute(name, attributeId).apply {
            setValueType(ValueType.DIMENSION)
            setData(complex(value, TypedValue.COMPLEX_UNIT_DIP))
        }
    }

    /** Encodes a TypedValue complex dimension using Android's mantissa/radix/unit layout. */
    private fun complex(value: Float, unit: Int): Int {
        val bits = (value * (1 shl 23) + 0.5f).toLong()
        val (radix, shift) = when {
            bits and 0x7FFFFFL == 0L -> 0 to 23
            bits and 0x7FFFFFL.inv() == 0L -> 3 to 0
            bits and 0x7FFFFFFFL.inv() == 0L -> 2 to 8
            bits and 0x7FFFFFFFFFL.inv() == 0L -> 1 to 16
            else -> 0 to 23
        }
        val mantissa = ((bits shr shift) and 0xFFFFFF).toInt()
        return (mantissa shl 8) or (radix shl 4) or unit
    }
}
