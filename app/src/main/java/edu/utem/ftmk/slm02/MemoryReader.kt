package edu.utem.ftmk.slm02

import android.os.Debug


/**
 * BITP 3453 Mobile Application Development
 *
 * @author Emaliana Kasmuri FTMK, UTeM
 *
 * Purpose:
 * The object reports on different aspects of memory consumption during the inferencing.
 */

object MemoryReader {

    /**
     * This method returns the current Java heap size in KB
     */
    fun javaHeapKb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024
    }

    /**
     * This method returns the current native heap size in KB
     */
    fun nativeHeapKb(): Long {
        return Debug.getNativeHeapAllocatedSize() / 1024
    }

    /**
     * This method returns the current total process size in KB
     */
    fun totalPssKb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong()
    }
}