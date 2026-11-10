package xyz.selykvpn.app.data

import androidx.core.content.FileProvider

/** Shares exported settings files (a separate class: the direct build has its own FileProvider for updates). */
class ExportsProvider : FileProvider()
