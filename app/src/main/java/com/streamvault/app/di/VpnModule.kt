package com.streamvault.app.di

import com.streamvault.app.vpn.VpnManager
import com.streamvault.app.vpn.VpnPanelBlockReporter
import com.streamvault.app.vpn.VpnReadinessGate
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class VpnModule {
    @Binds
    abstract fun bindVpnReadinessGate(impl: VpnManager): VpnReadinessGate

    @Binds
    abstract fun bindVpnPanelBlockReporter(impl: VpnManager): VpnPanelBlockReporter
}
