package com.streamvault.app.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainPlayerEngine

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuxiliaryPlayerEngine

/**
 * Dedicated OkHttp client for video playback (stream byte transfer). It has its
 * OWN Dispatcher + ConnectionPool, isolated from the control-plane client used
 * by the FuzionTV backend and the Xtream catalogue. This prevents stuck live
 * sockets (infinite HTTP responses) from saturating maxRequestsPerHost and
 * killing the catalogue/heartbeat until the process is restarted (a box reboot).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackHttpClient