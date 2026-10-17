package com.vit.devicememorymonitor

import android.app.Application

class MemoryApp : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        TrimLog.add(this, level)
    }
}