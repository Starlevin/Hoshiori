package com.glasssign.app.local

import com.glasssign.app.data.Account
import com.glasssign.app.data.GameBinding
import com.glasssign.app.ui.accountScopeSummary
import com.glasssign.app.ui.gamesLine
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM regression test for the real-device contradiction the user reported on the 账号 page:
 *
 *   one 米游社 account listed "原神 · 崩坏：星穹铁道 · 绝区零" (3 games) with "2 项角色签到" underneath;
 *   another listed 3 games with "1 项角色签到".
 *
 * Both numbers were real, but they came from different tables: the game titles from `Account.gameIds`
 * (the legacy `accounts.games` column) and the count from `game_bindings`. Per the code:
 *   - `SignRepository.setBindings` sets `roleScope = true`, and `learnBindings` returns early once
 *     roleScope is true -- i.e. roleScope=true means "the user confirmed the role scope, game_bindings is
 *     authoritative"; roleScope=false means the rows may be legacy/unscoped and gameIds is all there is.
 * So the card must pick ONE source per account and say which one it used.
 *
 * No Android runtime and no org.json Object are used on purpose (the app module does not enable
 * `returnDefaultValues`), [accountScopeSummary] only reads data-class fields.
 *
 * Run: gradlew -p android :app:testDebugUnitTest --tests "*AccountSummary*"
 */
class AccountSummaryV070Test {

    private fun account(
        id: String = "fixture-main",
        provider: String = "miyoushe",
        gameIds: List<String> = listOf("genshin", "honkai_sr", "zzz"),
        enabled: Boolean = true,
        roleScope: Boolean = false,
    ) = Account(
        id = id,
        provider = provider,
        nickname = "合成账号",
        enabled = enabled,
        createdAt = "2026-11-02T08:00:00+08:00",
        authState = "valid",
        bindingPending = false,
        gameIds = gameIds,
        roleScope = roleScope,
    )

    private fun binding(target: String, gameId: String, enabled: Boolean = true, accountId: String = "fixture-main") =
        GameBinding(accountId = accountId, targetId = target, gameId = gameId, name = target, enabled = enabled)

    // ------------------------------------------------------------------ the reported contradiction

    @Test fun confirmedRoleScopeWithThreeLegacyGamesAndTwoBindingsListsOnlyTheBoundGames() {
        // Exactly the user's first card: 3 legacy gameIds, 2 enabled game_bindings.
        val a = account(roleScope = true)
        val bindings = listOf(
            binding("genshin|uid-1|cn_gf01", "genshin"),
            binding("zzz|uid-1|cn_gf01", "zzz"),
        )
        val summary = accountScopeSummary(a, bindings)

        assertEquals("listed games must come from the bindings", listOf("genshin", "zzz"), summary.gameIds)
        assertFalse("an unscoped game must not be presented as covered", "honkai_sr" in summary.gameIds)
        assertTrue(summary.fromBindings)
        assertEquals(2, summary.enabledRoles)
        assertEquals("已确定角色范围 · 2 个游戏 / 2 项角色", summary.line)
        // The line must never read as "3 games" above a count of 2 (the reported contradiction).
        assertTrue(summary.gamesLine().contains("原神"))
        assertTrue(summary.gamesLine().contains("绝区零"))
        assertFalse("星穹铁道 has no binding, so it must not be listed", summary.gamesLine().contains("星穹铁道"))
    }

    @Test fun unconfirmedRoleScopeKeepsLegacyGamesAndSaysSoInsteadOfHidingThem() {
        val a = account(roleScope = false)
        val summary = accountScopeSummary(a, listOf(binding("genshin|uid-1|cn_gf01", "genshin")))

        assertEquals("legacy gameIds are the only evidence and must not be deleted", listOf("genshin", "honkai_sr", "zzz"), summary.gameIds)
        assertFalse(summary.fromBindings)
        assertEquals(1, summary.enabledRoles)
        assertTrue("the user must be told the scope is not confirmed", summary.line.contains("尚未确定角色范围"))
        // Marker says "not yet confirmed", never "wrong" and never silently drops games.
        assertFalse(summary.line.contains("错误"))
        assertTrue(summary.line.contains("3 个游戏"))
    }

    @Test fun confirmedRoleScopeWithNoEnabledBindingSaysNoEnabledRoles() {
        val summary = accountScopeSummary(
            account(roleScope = true),
            listOf(binding("genshin|uid-1|cn_gf01", "genshin", enabled = false)),
        )
        assertTrue(summary.gameIds.isEmpty())
        assertEquals(0, summary.enabledRoles)
        assertEquals("已确定角色范围 · 暂无启用角色", summary.line)
        // No empty/blank line: the baseline fell back to "游戏/角色待读取" for an empty title list, which
        // contradicts "已确定角色范围" and made the card look broken.
        assertEquals("游戏/角色待读取", summary.gamesLine())
    }

    // ------------------------------------------------------------------ ordering & dedupe

    @Test fun gamesAreDeduplicatedAndKeepBindingOrder() {
        val summary = accountScopeSummary(
            account(roleScope = true, gameIds = emptyList()),
            listOf(
                binding("zzz|uid|s", "zzz"),
                binding("zzz|uid2|s", "zzz"),
                binding("genshin|uid|s", "genshin"),
            ),
        )
        assertEquals("two role bindings of zzz are one game", listOf("zzz", "genshin"), summary.gameIds)
        assertEquals("but all three role bindings count", 3, summary.enabledRoles)
        assertEquals("已确定角色范围 · 2 个游戏 / 3 项角色", summary.line)
    }

    @Test fun blankGameIdFromABindingIsIgnored() {
        val summary = accountScopeSummary(
            account(roleScope = true, gameIds = emptyList()),
            listOf(binding("genshin|uid|s", "genshin"), binding("unknown|uid|s", "")),
        )
        assertEquals(listOf("genshin"), summary.gameIds)
        assertEquals("a blank gameId must not create an empty game slot", 1, summary.gameIds.size)
        assertFalse(summary.gamesLine().contains(" ·  · "))
    }

    @Test fun bindingsOfOtherAccountsAreNotCounted() {
        // The caller passes only this account's bindings, but guard the pure function against a mixed list.
        val summary = accountScopeSummary(
            account(id = "fixture-main", roleScope = true),
            listOf(binding("genshin|uid|s", "genshin", accountId = "fixture-other")),
        )
        assertTrue(summary.gameIds.isEmpty())
        assertEquals(0, summary.enabledRoles)
    }

    // ------------------------------------------------------------------ Summary line stability

    @Test fun summaryIsPureAndDeterministic() {
        val a = account(roleScope = true)
        val b = listOf(binding("genshin|uid|s", "genshin"), binding("zzz|uid|s", "zzz"))
        val first = accountScopeSummary(a, b)
        val second = accountScopeSummary(a, b)
        assertEquals(first, second)
        assertEquals("no write, no mutation of the input", b, listOf(binding("genshin|uid|s", "genshin"), binding("zzz|uid|s", "zzz")))
        assertEquals("legacy path is deterministic too", accountScopeSummary(account(roleScope = false), emptyList()), accountScopeSummary(account(roleScope = false), emptyList()))
    }

    @Test fun emptyLegacyAccountStillSaysScopeIsUnconfirmedRatherThanClaimingGames() {
        val summary = accountScopeSummary(account(gameIds = emptyList(), roleScope = false), emptyList())
        assertTrue(summary.gameIds.isEmpty())
        assertTrue(summary.line.contains("尚未确定角色范围"))
        assertTrue(summary.line.contains("游戏待读取"))
        assertEquals("游戏/角色待读取", summary.gamesLine())
    }

    @Test fun disabledAccountKeepsScopeInformation() {
        val summary = accountScopeSummary(account(enabled = false, roleScope = true), listOf(binding("genshin|uid|s", "genshin")))
        assertEquals(listOf("genshin"), summary.gameIds)
        assertEquals("已确定角色范围 · 1 个游戏 / 1 项角色", summary.line)
    }
}
