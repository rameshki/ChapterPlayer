package com.chapterplayer.di

import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.NotYetOnIosBookRepository
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/**
 * The bindings iOS cannot make for real yet (Stage 5 step 1): the book repository is still Android-only, so the Servers
 * pages get [NotYetOnIosBookRepository] (empty lists, "not available on iOS yet" for a sync). Step 2 replaces it.
 */
val notYetOnIosModule = module {
    single<BookRepository> { NotYetOnIosBookRepository }
}

/**
 * Starts Koin with [commonModules] plus the platform's own modules, once: a second call (the app entry and the root
 * view controller both ask) does nothing. [onStarted] runs after the first start only (the app-wide watches).
 * Android starts Koin itself in its Application with `sharedModules`.
 */
fun initKoin(platformModules: List<Module>, onStarted: (Koin) -> Unit = {}) {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    val koin = startKoin { modules(commonModules + platformModules) }.koin
    onStarted(koin)
}
