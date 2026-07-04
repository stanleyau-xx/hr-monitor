package com.hrmonitor.heartmonitor

/**
 * Shared state between Activity and Service.
 * Simple singleton — works because the service keeps the process alive.
 */
object HeartRateState {
    var bpm: Int = 0
    var status: String = "Stopped"
    var isRunning: Boolean = false

    var onBpmChanged: ((Int) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
}
