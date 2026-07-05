package com.hrmonitor.heartmonitor

/**
 * Shared state between Activity and Service.
 * Simple singleton — works because the service keeps the process alive.
 */
object HeartRateState {
    var bpm: Int = 0
    var status: String = "Stopped"
    var isRunning: Boolean = false
    /** Overlay text size in sp — 12..48, default 20 */
    var overlayTextSize: Int = 20

    var onBpmChanged: ((Int) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onSizeChanged: ((Int) -> Unit)? = null
}
