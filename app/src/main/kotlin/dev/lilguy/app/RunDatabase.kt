package dev.lilguy.app

import dev.lilguy.runlog.RunStore
import dev.lilguy.runlog.SyncTarget

/**
 * Where the app records its runs: the device's run store (`runs/lilguy.db` on the Mac, the app's
 * files dir on the TV) and, when [sync] is set, the server it sends rows to. [resume] is a run
 * id, or "latest", to carry on from its latest checkpoint instead of starting fresh.
 */
class RunDatabase(
    val store: RunStore,
    val sync: SyncTarget?,
    /** 'desktop' or 'tv'. */
    val platform: String,
    val host: String?,
    val gitSha: String? = null,
    val gitDirty: Boolean? = null,
    val resume: String? = null,
)
