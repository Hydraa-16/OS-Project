package com.vit.devicememorymonitor

data class ProcMeminfo(
    val memTotalKb: Long,
    val memFreeKb: Long,
    val memAvailableKb: Long,
    val buffersKb: Long,
    val cachedKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long,
    val dirtyKb: Long,
    val slabKb: Long
)

data class SysInfoSnapshot(
    val totalRamBytes: Long,
    val freeRamBytes: Long,
    val bufferRamBytes: Long,
    val totalSwapBytes: Long,
    val freeSwapBytes: Long,
    val uptimeSec: Long,
    val procs: Long
)

object NativeBridge {

    val isAvailable: Boolean = try {
        System.loadLibrary("memmon")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    external fun readProcMeminfo(): LongArray?
    external fun readSysinfo(): LongArray?

    fun procMeminfo(): ProcMeminfo? {
        if (!isAvailable) return null
        val v = readProcMeminfo() ?: return null
        if (v.size < 9) return null
        return ProcMeminfo(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8])
    }

    fun sysInfo(): SysInfoSnapshot? {
        if (!isAvailable) return null
        val v = readSysinfo() ?: return null
        if (v.size < 7) return null
        return SysInfoSnapshot(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
    }
}