package app.darksquare.blindfold.data.lichess

import app.darksquare.blindfold.domain.repo.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LichessRepositoryImpl @Inject constructor(
    private val http: OkHttpClient,
    private val tokens: TokenStore,
    private val oauth: OAuthCoordinator
) : LichessRepository {

    private val _isAuthed = MutableStateFlow(tokens.accessToken() != null)
    private val json = Json { ignoreUnknownKeys = true }

    override fun isAuthenticated(): Flow<Boolean> = _isAuthed.asStateFlow()

    override suspend fun authenticateWithToken(token: String): Boolean {
        val t = token.trim()
        if (t.isBlank()) return false
        tokens.store(t)
        _isAuthed.value = tokens.accessToken() != null
        return _isAuthed.value
    }

    override suspend fun logout() {
        tokens.clear()
        _isAuthed.value = false
    }

    override suspend fun seekGame(timeMinutes: Int, incrementSec: Int, rated: Boolean) {
        val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
        val bodyText = "time=$timeMinutes&increment=$incrementSec&rated=$rated&variant=standard&color=random"
        val body = bodyText.toRequestBody(mediaType)
        val request = buildAuthedRequest("/api/board/seek").post(body).build()
        http.newCall(request).execute().use { resp ->
            requireOk(resp)
        }
    }

    override fun streamIncomingEvents(): Flow<LichessEvent> = flow {
        val request = buildAuthedRequest("/api/stream/event").get().build()
        http.newCall(request).execute().use { resp ->
            requireOk(resp)
            resp.body?.source()?.use { src ->
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val event = parseEvent(json.parseToJsonElement(line))
                    if (event != null) emit(event)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun streamGameState(gameId: String): Flow<LichessGameState> = flow {
        val request = buildAuthedRequest("/api/board/game/stream/$gameId").get().build()
        http.newCall(request).execute().use { resp ->
            requireOk(resp)
            resp.body?.source()?.use { src ->
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val state = parseGameState(gameId, json.parseToJsonElement(line))
                    if (state != null) emit(state)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun makeMove(gameId: String, uci: String, draw: Boolean) {
        val url = "/api/board/game/$gameId/move/$uci" + if (draw) "?offeringDraw=true" else ""
        val request = buildAuthedRequest(url).post("".toRequestBody(null)).build()
        http.newCall(request).execute().use { resp -> requireOk(resp) }
    }

    override suspend fun resign(gameId: String) {
        val request = buildAuthedRequest("/api/board/game/$gameId/resign").post("".toRequestBody(null)).build()
        http.newCall(request).execute().use { resp -> requireOk(resp) }
    }

    override suspend fun abort(gameId: String) {
        val request = buildAuthedRequest("/api/board/game/$gameId/abort").post("".toRequestBody(null)).build()
        http.newCall(request).execute().use { resp -> requireOk(resp) }
    }

    override suspend fun chat(gameId: String, room: String, text: String) {
        val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
        val bodyText = "room=$room&text=$text"
        val body = bodyText.toRequestBody(mediaType)
        val request = buildAuthedRequest("/api/board/game/$gameId/chat").post(body).build()
        http.newCall(request).execute().use { resp -> requireOk(resp) }
    }

    override suspend fun dailyPuzzle(): LichessPuzzle = fetchPuzzle("/api/puzzle/daily")
    override suspend fun nextPuzzle(): LichessPuzzle = fetchPuzzle("/api/puzzle/next")

    private fun fetchPuzzle(path: String): LichessPuzzle {
        val request = buildAuthedRequest(path).get().build()
        http.newCall(request).execute().use { resp ->
            requireOk(resp)
            val root = json.parseToJsonElement(resp.body!!.string()).jsonObject
            val game = root["game"]?.jsonObject ?: throw IllegalStateException("no game")
            val puzzle = root["puzzle"]?.jsonObject ?: throw IllegalStateException("no puzzle")
            
            val solution = puzzle["solution"]?.jsonArray
                ?.map { it.jsonPrimitive.content } ?: emptyList()
            val themes = puzzle["themes"]?.jsonArray
                ?.map { it.jsonPrimitive.content } ?: emptyList()

            return LichessPuzzle(
                id = puzzle["id"]!!.jsonPrimitive.content,
                fen = puzzle["fen"]?.jsonPrimitive?.content ?: "",
                solutionUci = solution,
                rating = puzzle["rating"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1500,
                themes = themes
            )
        }
    }

    private fun buildAuthedRequest(path: String): Request.Builder {
        val token = tokens.accessToken() ?: throw IllegalStateException("not authenticated")
        return Request.Builder()
            .url("https://lichess.org$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/x-ndjson")
    }

    private fun requireOk(resp: Response) {
        if (!resp.isSuccessful) {
            val msg = resp.body?.string()?.take(200) ?: "no body"
            throw IllegalStateException("Lichess ${resp.code}: $msg")
        }
    }

    private fun parseEvent(el: JsonElement): LichessEvent? {
        val obj = el.jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        return when (type) {
            "gameStart" -> {
                val g = obj["game"]!!.jsonObject
                val opponent = g["opponent"]?.jsonObject?.get("username")?.jsonPrimitive?.content ?: "?"
                LichessEvent.GameStart(
                    id = g["id"]!!.jsonPrimitive.content,
                    opponent = opponent,
                    whiteToMove = g["color"]?.jsonPrimitive?.content == "white"
                )
            }
            "gameFinish" -> {
                val g = obj["game"]!!.jsonObject
                LichessEvent.GameFinish(
                    id = g["id"]!!.jsonPrimitive.content,
                    winner = g["winner"]?.jsonPrimitive?.content
                )
            }
            "challenge" -> {
                val c = obj["challenge"]!!.jsonObject
                val from = c["challenger"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "?"
                LichessEvent.Challenge(
                    id = c["id"]!!.jsonPrimitive.content,
                    from = from
                )
            }
            else -> null
        }
    }

    private fun parseGameState(gameId: String, el: JsonElement): LichessGameState? {
        val obj = el.jsonObject
        val type = obj["type"]?.jsonPrimitive?.content
        val state = when (type) {
            "gameFull" -> obj["state"]!!.jsonObject
            "gameState" -> obj
            else -> return null
        }
        val movesStr = state["moves"]?.jsonPrimitive?.content ?: ""
        val moves = movesStr.split(' ').filter { it.isNotBlank() }
        
        return LichessGameState(
            gameId = gameId,
            fen = "",
            movesUci = moves,
            whiteMs = state["wtime"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            blackMs = state["btime"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            status = state["status"]?.jsonPrimitive?.content ?: "started",
            lastMove = null
        )
    }
}

interface TokenStore { fun accessToken(): String?; fun store(token: String); fun clear() }
interface OAuthCoordinator {
    fun buildAuthUrl(scopes: List<String>): String
    suspend fun exchangeCode(redirectUri: String)
}
