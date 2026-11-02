// v0.45 – 2026-11-02: TV'de başka uygulama öndeyken de telefondan uygulama/link/TV ekranı açma (Google TV kumanda hizmeti + tvgonder://go)
package com.onur.tvgonder

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * Android, arka plandaki bir uygulamanın ekran açmasını engeller (TV'de cApp, YouTube… öndeyken).
 * Google TV'nin kendi kumanda hizmeti bu engele takılmaz: telefondan "tvgonder://go?..." linki gönderilir,
 * TV'de [GoActivity] bir an açılıp isteneni yapar ve kapanır.
 */
object TvGo {
    /** Telefonun seçili alıcısı, TV kumandası (hızlı) ile eşleşmiş TV mi. */
    fun can(ctx: Context, store: Store): Boolean = store.tvHost?.let { AtvRemote.isPaired(ctx, it) } == true

    /** Arka planda çağrılır. true: TV'ye gitti. */
    fun go(ctx: Context, store: Store, query: String): Boolean {
        val h = store.tvHost ?: return false
        return AtvRemote.launchBlocking(ctx, h, "tvgonder://go?$query")
    }

    fun openApp(ctx: Context, store: Store, pkg: String) = go(ctx, store, "a=open&pkg=" + Uri.encode(pkg))
    fun openLink(ctx: Context, store: Store, url: String) = go(ctx, store, "a=link&u=" + Uri.encode(url))
    fun mirror(ctx: Context, store: Store, id: String) = go(ctx, store, "a=mirror&id=" + Uri.encode(id))
}

/** TV'de görünmez ara ekran: öndeyken açma engeli olmaz; işi yapar ve kapanır. */
class GoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val u = intent?.data
        runCatching {
            when (u?.getQueryParameter("a")) {
                "open" -> u.getQueryParameter("pkg")?.let { Apps.open(this, it) }
                "link" -> u.getQueryParameter("u")?.let { url ->
                    val s = Uri.parse(url).scheme
                    if (s == "http" || s == "https") LinkOpener.open(this, url)
                }
                // Yalnız telefonun az önce (eşleşmiş olarak) istediği yansıtma: kimlik sunucuda bekler
                "mirror" -> u.getQueryParameter("id")?.let { id -> Hub.mirrorPending.remove(id)?.let { startActivity(it) } }
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
