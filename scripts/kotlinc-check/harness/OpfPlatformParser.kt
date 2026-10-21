// Harness-local stand-in for `android.util.Xml`, which does not exist on a plain JVM. It links the
// same parser library Android ships behind that call (kxml2, out of the Gradle cache), so the real
// EPUB parser objects run here untouched; the offline stub in ../stubs throws instead and is never
// compiled by run-harness. Compile this only together with OpfMetadataHarness.
package android.util

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

object Xml {
    @JvmStatic
    fun newPullParser(): XmlPullParser = PlatformParser()

    /**
     * Desktop kxml2 refuses `process-docdecl` outright, even when asked for `false` — while its own
     * built-in behavior for that value is exactly what Android's (feature-patched) parser would
     * deliver: the internal subset is never processed, so a doctype declares nothing and a
     * declared-entity attack fails parse as the caller's XXE defence expects. Absorb only that
     * delta here; every other feature reaches the desktop parser unfiltered, where a rejection is
     * a real signal.
     */
    private open class PlatformParser : KXmlParser() {
        override fun setFeature(name: String, value: Boolean) {
            if (name == XmlPullParser.FEATURE_PROCESS_DOCDECL && !value) return
            super.setFeature(name, value)
        }
    }
}
