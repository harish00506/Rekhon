package com.aicfo.app.di

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
     * Where the market-data proxy is — which today is nowhere.
     * Why:    the §22 Market Data API is specified and unbuilt, and no issue in the backlog builds
     *         it. The alternative to shipping unconfigured is pointing the client at AMFI or a
     *         crypto exchange directly, which EXT-001 and P-01 forbid outright and which would be
     *         very hard to unpick once shipped. So the app ships with no backend, which is a
     *         supported state rather than a broken one (P-04).
     *
     *         **This binding is the whole switch.** When a proxy exists, its URL and pins go here
     *         and nothing else in the app changes — no call site, no ViewModel, no screen.
     * Result: [NetworkConfig.UNCONFIGURED].
     * Input:  none. Output: [NetworkConfig].
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    @Provides
    @Singleton
    fun provideNetworkConfig(): NetworkConfig = NetworkConfig.UNCONFIGURED

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
