package app.darksquare.blindfold.domain.repo

import kotlinx.coroutines.flow.Flow

interface LichessRepository {
    fun isAuthenticated(): Flow<Boolean>
    suspend fun authenticateWithToken(token: String): Boolean
    suspend fun logout()
    suspend fun seekGame(timeMinutes: Int, incrementSec: Int, rated: Boolean)
    fun streamIncomingEvents(): Flow<LichessEvent>
    fun streamGameState(gameId: String): Flow<LichessGameState>
    suspend fun makeMove(gameId: String, uci: String, draw: Boolean = false)
    suspend fun resign(gameId: String)
    suspend fun abort(gameId: String)
    suspend fun chat(gameId: String, room: String, text: String)
    suspend fun dailyPuzzle(): LichessPuzzle
    suspend fun nextPuzzle(): LichessPuzzle
}
