package com.flipduo.module

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow

class FlipDuoApplication : Application(), XposedServiceHelper.OnServiceListener {
    companion object { val service = MutableStateFlow<XposedService?>(null) }
    override fun onCreate() { super.onCreate(); XposedServiceHelper.registerListener(this) }
    override fun onServiceBind(value: XposedService) { service.value = value }
    override fun onServiceDied(value: XposedService) { if (service.value === value) service.value = null }
}
