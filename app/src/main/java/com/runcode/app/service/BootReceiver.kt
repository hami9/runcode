package com.runcode.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.runcode.app.RuncodeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver {
    constructor() : super()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val app = context.applicationContext as? RuncodeApp ?: return
            CoroutineScope(Dispatchers.Default).launch {
                val projects = app.appMetaDatabase.getAllProjects()
                val bootProjects = projects.filter { it.startOnBoot }
                bootProjects.forEach { project ->
                    app.serviceSupervisor.startProject(project)
                }
            }
        }
    }
}
