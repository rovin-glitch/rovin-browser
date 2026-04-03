package com.igalia.wolvic.browser.api.impl

import com.igalia.wolvic.browser.api.WResult
import com.igalia.wolvic.browser.api.WSession

class SessionFinderImpl : WSession.SessionFinder {

    override fun find(searchString: String?, flags: Int): WResult<WSession.SessionFinder.FinderResult> {
        val finderResult = WSession.SessionFinder.FinderResult().apply {
            found = false
            current = 0
            total = 0
        }
        return WResult.fromValue(finderResult)
    }

    override fun clear() {
    }

    override fun getDisplayFlags(): Int {
        return 0
    }

    override fun setDisplayFlags(flags: Int) {
    }
}
