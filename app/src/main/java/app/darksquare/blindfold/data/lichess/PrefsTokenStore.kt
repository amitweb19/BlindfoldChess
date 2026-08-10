package app.darksquare.blindfold.data.lichess

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrefsTokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) : TokenStore {

    private val prefs = context.getSharedPreferences("lichess_auth", Context.MODE_PRIVATE)

    override fun accessToken(): String? = prefs.getString("access_token", null)

    override fun store(token: String) {
        prefs.edit().putString("access_token", token).apply()
    }

    override fun clear() {
        prefs.edit().remove("access_token").apply()
    }
}
