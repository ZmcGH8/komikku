// AM (DISCORD) -->

// Taken from Animiru. Thank you Quickdev for permission!

package eu.kanade.tachiyomi.data.connections.discord

import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.ConnectionsService
import exh.log.xLogE
import kotlinx.serialization.json.Json
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR
import timber.log.Timber

class Discord(id: Long) : ConnectionsService(id) {

    override fun nameStrRes() = KMR.strings.connections_discord

    override fun getLogo() = R.drawable.ic_discord_24dp

    @Suppress("MagicNumber")
    override fun getLogoColor() = Color.rgb(88, 101, 242)

    override fun logout() {
        super.logout()
        connectionsPreferences.connectionsToken(this).delete()
    }

    override suspend fun login(username: String, password: String) {
        // Not Needed
    }

    override val isLogged: Boolean
        get() = getToken().isNotBlank()

    override fun getToken(): String {
        return connectionsPreferences.connectionsToken(this).get()
    }

    private val json = globalAppGraph.json

    fun getAccounts(): List<DiscordAccount> {
        val accountsJson = connectionsPreferences.discordAccounts().get()
        return try {
            if (accountsJson.isNotBlank()) {
                json.decodeFromString<List<DiscordAccount>>(accountsJson)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse Discord accounts")
            emptyList()
        }
    }

    fun addAccount(account: DiscordAccount) {
        val accounts = getAccounts().toMutableList()

        if (account.isActive) {
            accounts.replaceAll { it.copy(isActive = false) }
            connectionsPreferences.connectionsToken(this).set(account.token)
        }

        val index = accounts.indexOfFirst { it.id == account.id }
        if (index >= 0) {
            accounts[index] = account
        } else {
            accounts.add(account)
        }

        saveAccounts(accounts)
    }

    // KMK -->
    fun removeAccount(accountId: String): Boolean {
        val accounts = getAccounts().toMutableList()
        val wasActive = accounts.firstOrNull { it.id == accountId }?.isActive == true
        accounts.removeAll { it.id == accountId }
        saveAccounts(accounts)

        if (wasActive) {
            val nextActive = accounts.firstOrNull { it.isActive }
            if (nextActive != null) {
                // Another account already holds the active slot: keep the session token
                // pointing at it instead of at the account that was just removed.
                connectionsPreferences.connectionsToken(this).set(nextActive.token)
            } else {
                // Removing the active account is a logout. Without this the token keeps
                // pointing at a removed account: isLogged stays true, start() happily
                // re-runs the RPC, and the notification and presence outlive the session
                // the user believes is gone.
                logout()
            }
        }
        return wasActive
    }
    // KMK <--

    fun setActiveAccount(accountId: String) {
        val accounts = getAccounts().toMutableList()
        accounts.replaceAll { it.copy(isActive = it.id == accountId) }
        saveAccounts(accounts)
        // Update active token (should restart RPC later)
        accounts.find { it.id == accountId }?.let { account ->
            connectionsPreferences.connectionsToken(this).set(account.token)
        }
    }

    fun restartRichPresence(context: android.content.Context) {
        // Direct restart via service intent
        DiscordRPCService.restart(context)
    }

    private fun saveAccounts(accounts: List<DiscordAccount>) {
        try {
            val accountsJson = json.encodeToString(accounts)
            connectionsPreferences.discordAccounts().set(accountsJson)
        } catch (e: Exception) {
            xLogE("Failed to save Discord accounts", e)
        }
    }
}
// <-- AM (DISCORD)
