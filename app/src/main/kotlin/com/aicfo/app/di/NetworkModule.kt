package com.aicfo.app.di

import com.aicfo.app.BuildConfig
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.network.MarketDataApi
import com.aicfo.core.network.MarketDataFactory
import com.aicfo.core.network.NetworkConfig
import com.aicfo.data.repository.DemoModeRepository
import com.aicfo.data.repository.MarketPriceRepository
import com.aicfo.data.repository.RepositoryFactory
import com.aicfo.domain.engines.investment.InvestmentEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The entire network object graph, in one file (issue 6.5; §16, §22, P-01, P-04).
 *
 * Why:  its own module rather than three more functions in `RepositoryModule` or `CoreModule`, and
 *       the reason is not the function count. **Everything that could ever put this app on the
 *       network is declared here**, so the question "what can this build talk to?" is answered by
 *       reading one short file — together with `core/network`'s manifest, which holds the only
 *       `INTERNET` permission in thirty-five modules. Scattering these bindings among the others
 *       would make that question a search instead of a read.
 * What: the configuration, the client it produces, and the one repository that uses it.
 * Result: a graph that, as configured today, contains no HTTP stack at all.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /**
     * Where the market-data proxy is, and what certificate it must present.
     *
     * Why:    issue 6.5 wrote that "this binding is the whole switch. When a proxy exists, its URL
     *         and pins go here and nothing else in the app changes." Issue 6.7 built the proxy, and
     *         this is that change — the only one on the client side, and it is still one function.
     *
     *         The URL and pins arrive as `BuildConfig` fields fed from Gradle properties that
     *         **default to empty**, so the shipping build is byte-for-byte the unconfigured one
     *         issue 6.5 described: [MarketDataFactory] branches on the blank base URL before it
     *         constructs anything, and no `OkHttpClient`, connection pool, DNS resolver or socket
     *         comes into existence. Having no backend remains a supported state, not a broken one
     *         (P-04) — the app is offline-first and the proxy is optional to it.
     * What:   a blank base URL means [NetworkConfig.UNCONFIGURED]; anything else is a configured
     *         host with the pins that came with it.
     * Result: [NetworkConfig]. Note what is **not** here: no fallback pin, no "if pins are missing,
     *         carry on". [NetworkConfig]'s own `init` refuses a non-https host and a host with no
     *         pins, so a misconfigured build fails at construction rather than shipping a client
     *         that trusts any CA on the device. That refusal is the point of the class.
     * Input:  none. Output: [NetworkConfig].
     * Changelog: 2026-08-29 — Created for issue 6.5.
     *            2026-08-30 — Issue 6.7: reads BuildConfig, so a build can be pointed at the proxy.
     */
    @Provides
    @Singleton
    fun provideNetworkConfig(): NetworkConfig =
        if (BuildConfig.MARKET_BASE_URL.isBlank()) {
            NetworkConfig.UNCONFIGURED
        } else {
            NetworkConfig(
                baseUrl = BuildConfig.MARKET_BASE_URL,
                pins = BuildConfig.MARKET_PINS.split(',').map(String::trim).filter(String::isNotEmpty),
            )
        }

    /**
     * The market-data client.
     * Why:    built through [MarketDataFactory] because the implementations are `internal`
     *         (ARC-003). With [config] unconfigured the factory returns an object that constructs
     *         no OkHttp client, so this graph holds no connection pool, no DNS resolver and no
     *         socket — the offline claim is a property of the object graph, not of a runtime check.
     * Result: a [MarketDataApi] that is inert in a shipping build.
     * Input:  [config]. Output: [MarketDataApi].
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    @Provides
    @Singleton
    fun provideMarketDataApi(config: NetworkConfig): MarketDataApi = MarketDataFactory.create(config)

    /**
     * The price refresher (issue 6.5, FR-INV-004).
     * Why:    takes the gated [CfoDatabase] like every repository binding, so a locked app cannot
     *         read what somebody owns. Follows the demo (ADR-0006), so a refresh inside the demo
     *         touches the sample profile's holdings and never the real one's.
     * Result: a [MarketPriceRepository]. Input: the graph's shared dependencies. Output: the
     *         repository.
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt reads the signature; each argument is one binding.
    fun provideMarketPriceRepository(
        database: CfoDatabase,
        api: MarketDataApi,
        engine: InvestmentEngine,
        consents: ConsentStore,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): MarketPriceRepository =
        RepositoryFactory.marketPrice(
            database = database,
            api = api,
            engine = engine,
            consents = consents,
            clock = clock,
            dispatchers = dispatchers,
            activeProfileId = demoMode.activeProfileId,
        )
}
