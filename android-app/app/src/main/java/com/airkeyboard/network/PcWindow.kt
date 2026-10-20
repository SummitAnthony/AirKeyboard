package com.airkeyboard.network

/** A top-level window on the PC, from [AirKeyboardClient.requestWindows]; [id] goes back in "win" commands */
data class PcWindow(val id: String, val title: String)
