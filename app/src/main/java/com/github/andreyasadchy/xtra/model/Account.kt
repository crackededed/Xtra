package com.github.andreyasadchy.xtra.model

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

sealed class Account(val id: String?,
                     val login: String?,
                     val helixToken: String?,
                     val gqlToken: String?) {

    companion object {
        private var account: Account? = null

        fun get(context: Context): Account {
            return account ?: with(context.prefs()) {
                val helixToken = getString(C.TOKEN, null).takeUnless { it.isNullOrBlank() }
                val gqlToken = getString(
                    if (getBoolean(C.DEBUG_WEBVIEW_INTEGRITY, false)) C.GQL_TOKEN else C.GQL_TOKEN2,
                    null).takeUnless { it.isNullOrBlank() }
                if (!helixToken.isNullOrBlank() || !gqlToken.isNullOrBlank()) {
                    val id = getString(C.USER_ID, null).takeUnless { it.isNullOrBlank() }
                    val name = getString(C.USERNAME, null).takeUnless { it.isNullOrBlank() }
                    if (TwitchApiHelper.checkedValidation) {
                        LoggedIn(id, name, helixToken, gqlToken)
                    } else {
                        NotValidated(id, name, helixToken, gqlToken)
                    }
                } else {
                    NotLoggedIn()
                }
            }.also { account = it }
        }

        fun set(context: Context, account: Account?) {
            this.account = account
            context.prefs().edit {
                if (account != null) {
                    putString(C.USER_ID, account.id)
                    putString(C.USERNAME, account.login)
                    putString(C.TOKEN, account.helixToken)
                    putString(
                        if (context.prefs().getBoolean(C.DEBUG_WEBVIEW_INTEGRITY, false)) C.GQL_TOKEN else C.GQL_TOKEN2,
                        account.gqlToken)
                } else {
                    putString(C.USER_ID, null)
                    putString(C.USERNAME, null)
                    putString(C.TOKEN, null)
                    putString(C.GQL_TOKEN, null)
                    putString(C.GQL_TOKEN2, null)
                }
            }
        }

        fun validated() {
            account = LoggedIn(account as NotValidated)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Account

        if (id != other.id) return false
        if (login != other.login) return false
        if (helixToken != other.helixToken) return false
        if (gqlToken != other.gqlToken) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + login.hashCode()
        result = 31 * result + helixToken.hashCode()
        result = 31 * result + gqlToken.hashCode()
        return result
    }
}

class LoggedIn(id: String?, login: String?, helixToken: String?, gqlToken: String?) : Account(id, login, helixToken, gqlToken) {
    constructor(account: NotValidated) : this(account.id, account.login, account.helixToken, account.gqlToken)
}
class NotValidated(id: String?, login: String?, helixToken: String?, gqlToken: String?) : Account(id, login, helixToken, gqlToken)
class NotLoggedIn : Account(null, null, null, null)