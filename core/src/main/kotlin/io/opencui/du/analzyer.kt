package io.opencui.du

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import java.util.*


/**
 * This is the central place to manage all the analyzers.
 */
object LanguageAnalzyer {
    val analyzers = mapOf(
            "en" to EnglishAnalyzer(),
            "zh" to SmartChineseAnalyzer()
    )

    fun get(lang: String) : Analyzer? {
        return analyzers[lang.lowercase(Locale.getDefault())]
    }

    fun  getUnstoppedAnalyzer(lang: String): Analyzer? {
        return when (lang.lowercase(Locale.getDefault())) {
            "en" -> StandardAnalyzer()
            "zh" -> SmartChineseAnalyzer()
            else -> null
        }
    }

    fun getStopSet(lang:String): CharArraySet? {
        return when (lang.lowercase(Locale.getDefault())) {
            "en" -> EnglishAnalyzer.getDefaultStopSet()
            "zh" -> SmartChineseAnalyzer.getDefaultStopSet()
            else -> null
        }
    }
}