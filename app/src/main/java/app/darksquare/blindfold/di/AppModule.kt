package app.darksquare.blindfold.di

import app.darksquare.blindfold.engine.StockfishEngineRepository
import app.darksquare.blindfold.data.lichess.LichessRepositoryImpl
import app.darksquare.blindfold.data.stt.AndroidSttRepository
import app.darksquare.blindfold.data.tts.AndroidTtsRepository
import app.darksquare.blindfold.domain.parser.VoiceMoveParser
import app.darksquare.blindfold.domain.repo.ChessEngineRepository
import app.darksquare.blindfold.data.UserPhonemeStore
import app.darksquare.blindfold.domain.repo.LichessRepository
import app.darksquare.blindfold.domain.repo.SttRepository
import app.darksquare.blindfold.domain.repo.TtsRepository
import app.darksquare.blindfold.data.lichess.TokenStore
import app.darksquare.blindfold.data.lichess.PrefsTokenStore
import app.darksquare.blindfold.data.lichess.OAuthCoordinator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
abstract class RepoBindings {
    @Binds @Singleton abstract fun engine(impl: StockfishEngineRepository): ChessEngineRepository
    @Binds @Singleton abstract fun tts(impl: AndroidTtsRepository): TtsRepository
    @Binds @Singleton abstract fun stt(impl: AndroidSttRepository): SttRepository
    @Binds @Singleton abstract fun lichess(impl: LichessRepositoryImpl): LichessRepository
    @Binds @Singleton abstract fun tokenStore(impl: PrefsTokenStore): TokenStore
}

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun parser(store: UserPhonemeStore): VoiceMoveParser = VoiceMoveParser(store)

    @Provides @Singleton fun http(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)         // infinite streams
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @Singleton fun oauthCoordinator(): OAuthCoordinator = object : OAuthCoordinator {
        override fun buildAuthUrl(scopes: List<String>) = ""
        override suspend fun exchangeCode(redirectUri: String) {}
    }
}
