package com.glasssign.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM tests for the account identity display model (data/AccountDisplay.kt).
 * Run: gradlew -p android :app:testDebugUnitTest --tests "*AccountDisplayTest"
 */
class AccountDisplayTest {

    private val sklandRoles=listOf("明日方舟 · 清寒#7810")
    private val uid="12345678"

    private fun display(provider:String,nickname:String,providerUserId:String,roles:List<String>):AccountDisplay=
        accountDisplay(provider,nickname,providerUserId,roles)

    private fun skland(note:String,uidValue:String):AccountDisplay=display("skland",note,uidValue,sklandRoles)

    // ------------------------------------------------------------- 1. official role name is the default title

    @Test fun officialCardNameIsTheDefaultTitleVerbatim() {
        val d=skland("",uid)
        assertEquals("明日方舟 · 清寒#7810",d.title)
        assertTrue(d.titleFromPlatform)
        assertTrue("the official tag survives untouched",d.title.endsWith("#7810"))
        assertNull("no user remark -> no remark line",d.customNote)
        assertEquals("账号 12••••78",d.platformLine)
        assertNull("this role name already is the title, so it is not repeated",d.rolesLine)
    }

    @Test fun officialCardBeatsPlainRoleNamesWhateverTheOrder() {
        val plain=listOf("明日方舟 · 小号","明日方舟 · 清寒#7810")
        assertEquals("an official #tag card wins over a plain role name","明日方舟 · 清寒#7810",display("skland","",uid,plain).title)
        assertEquals("the leftover role keeps its full official name","明日方舟 · 小号",display("skland","",uid,plain).rolesLine)
    }

    @Test fun tagWithLettersAndLeadingZerosIsNotAltered() {
        val d=display("skland","","",listOf("明日方舟 · アークナイツ#007"))
        assertEquals("明日方舟 · アークナイツ#007",d.title)
        assertTrue(d.titleFromPlatform)
    }

    @Test fun plainOfficialRoleNameIsAnIdentityTooButCarriesNoTag() {
        val d=display("skland","",uid,listOf("明日方舟 · 清寒"))
        assertEquals("the official role name is the title, unchanged","明日方舟 · 清寒",d.title)
        assertTrue(d.titleFromPlatform)
        assertFalse("a tag is never derived from an id",d.title.contains("#"))
        assertFalse("…including the last four digits of the UID",d.title.contains("5678"))
        assertEquals("账号 12••••78",d.platformLine)
        assertNull("the title already shows this role",d.rolesLine)
    }

    // ------------------------------------------------------------- 2. no invented tag

    @Test fun idLikeRoleNameIsNotATitleCandidate() {
        val d=display("skland","",uid,listOf("明日方舟 · "+uid))
        assertEquals("the game label must not make an id look like a name","森空岛",d.title)
        assertFalse(d.titleFromPlatform)
        assertFalse(d.title.contains(uid))
        assertNull("an id-only role name is not a row either",d.rolesLine)
        assertFalse(d.hasRoleData)
    }

    @Test fun anIdOnlyRoleNextToARealOneIsDroppedNotListed() {
        val d=display("skland","",uid,listOf("明日方舟 · "+uid,"明日方舟 · 清寒#7810"))
        assertEquals("明日方舟 · 清寒#7810",d.title)
        assertNull("the id-only role contributes no row",d.rolesLine)
        assertTrue(d.hasRoleData)
    }

    @Test fun roleUidIsNeverTurnedIntoATag() {
        val d=display("skland","","",listOf("明日方舟 · 清寒","原神 · 旅行者"))
        assertEquals("明日方舟 · 清寒",d.title)
        assertFalse(d.title.contains("#"))
        assertEquals("no id and no #tag anywhere -> no invented identity","原神 · 旅行者",d.rolesLine)
    }

    @Test fun tagParsingAcceptsOnlyOneNonEmptyTag() {
        assertEquals("清寒" to "7810",splitAccountTag("清寒#7810"))
        assertEquals("清春" to null,splitAccountTag("清春"))
        assertEquals("清#寒#1" to null,splitAccountTag("清#寒#1"))
        assertEquals("#7810" to null,splitAccountTag("#7810"))
        assertEquals("清寒#" to null,splitAccountTag("清寒#"))
        // The tag is never rewritten: a padded tag is not a card, so the whole string stays as the name.
        assertEquals("清寒# 7810" to null,splitAccountTag("清寒# 7810"))
        assertEquals("清寒#7810 " to null,splitAccountTag("清寒#7810 "))
        assertEquals("padded tag text is not a platform card","明日方舟 · 清寒# 7810",display("skland","","",listOf("明日方舟 · 清寒# 7810")).title)
    }

    // ------------------------------------------------------------- 3. user remark preserved

    @Test fun userRemarkIsASeparateLineAndNeverReplacesTheIdentity() {
        assertEquals("the official identity stays the title","明日方舟 · 清寒#7810",skland("主号",uid).title)
        assertEquals("the remark keeps its own line","主号",skland("主号",uid).customNote)
    }

    @Test fun aUserRemarkIsNeverPromotedToTheDefaultIdentity() {
        val d=skland("我的心夏#9999",uid)
        assertEquals("明日方舟 · 清寒#7810",d.title)
        assertEquals("我的心夏#9999",d.customNote)
    }

    @Test fun remarkEqualToTheRoleNameIsNotShownTwice() {
        val d=skland("明日方舟 · 清寒#7810",uid)
        assertEquals("明日方舟 · 清寒#7810",d.title)
        assertNull(d.customNote)
    }

    @Test fun remarkIsOnlyTrimmedNotRewritten() {
        val d=skland("  清寒  ",uid)
        assertEquals("明日方舟 · 清寒#7810",d.title)
        assertEquals("清寒",d.customNote)
    }

    @Test fun remarkIsKeptWhenThePlatformReturnedNothing() {
        val d=display("skland","主号","",emptyList())
        assertEquals("a user-chosen identity beats 身份待同步","主号",d.title)
        assertFalse(d.titleFromPlatform)
        assertNull("it already is the title, so it is not repeated",d.customNote)
        assertNull("no stable id returned -> no id line",d.platformLine)
    }

    // ------------------------------------------------------------- 4. UID vs role id

    @Test fun platformUidIsASupplementAndNeverTheRoleId() {
        val d=skland("",uid)
        assertEquals("账号 12••••78",d.platformLine)
        assertFalse("the raw uid must not be printed unmasked",d.platformLine!!.contains(uid))
        assertFalse("a role target id never becomes the account id line",d.platformLine!!.contains("arknights"))
        assertFalse(d.title.contains("arknights|"))
    }

    @Test fun theRoleUidInsideABindingTargetIsNotThePlatformUid() {
        val roleUid="987654321"
        val d=display("skland","","",listOf("明日方舟 · 清寒#7810"))
        assertEquals("明日方舟 · 清寒#7810",d.title)
        assertNull("an empty platform UID means no id line at all",d.platformLine)
        assertFalse(d.title.contains(roleUid))
    }

    // ------------------------------------------------------------- 5. multiple roles

    @Test fun severalRolesGetOneClearFirstRowPlusACount() {
        val d=display("skland","","",listOf("明日方舟 · 清寒#7810","明日方舟 · 小号"))
        assertEquals("the first readable platform card is the identity","明日方舟 · 清寒#7810",d.title)
        assertEquals("明日方舟 · 小号",d.rolesLine)
        assertFalse("the title stays one identity, not a list",d.title.contains("小号"))
    }

    @Test fun plainRoleNamesAreSummarisedWithACountAndTheFirstOneIsTheTitle() {
        val d=display("miyoushe","","",listOf("原神 · 旅行者","绝区零 · 绳匠","原神 · 小号"))
        assertEquals("the first official role name is the identity","原神 · 旅行者",d.title)
        assertFalse("no #tag anywhere -> never a made-up tag",d.title.contains("#"))
        assertEquals("绝区零 · 绳匠 等 · 共 2 项角色",d.rolesLine)
    }

    @Test fun duplicateRoleNamesCollapseButDistinctOnesCount() {
        val d=display("miyoushe","","",listOf("原神 · 旅行者","原神 · 旅行者","原神 · 小号"))
        assertEquals("原神 · 小号",d.rolesLine)
        assertEquals("原神 · 旅行者",d.title)
        assertTrue("a role was read, so the UI must not add 角色待同步",d.hasRoleData)
    }

    @Test fun aSingleRoleNameIsShownWithoutACount() {
        val d=display("miyoushe","","",listOf("原神 · 旅行者"))
        assertEquals("原神 · 旅行者",d.title)
        assertNull("the title already shows the only role",d.rolesLine)
        assertTrue(d.hasRoleData)
    }

    @Test fun aSingleRoleNameIsListedWhenTheTitleIsTheRemark() {
        val d=display("miyoushe","旅行者","",listOf("旅行者"))
        assertEquals("the name part is the title","旅行者",d.title)
        assertNull(d.customNote)
        assertNull("title and the only role agree, so it is not repeated",d.rolesLine)
        assertTrue(d.hasRoleData)
    }

    @Test fun duplicateRoleNamesAreNotCountedTwice() {
        val d=display("miyoushe","","",listOf("原神 · 旅行者","原神 · 旅行者"))
        assertEquals("原神 · 旅行者",d.title)
        assertNull("one distinct role has nothing to count",d.rolesLine)
        assertTrue(d.hasRoleData)
    }

    // ------------------------------------------------------------- 6. unknown identity

    @Test fun unknownIdentityNeverRepeatsTheProvider() {
        val d=display("skland","","",emptyList())
        assertEquals("森空岛",d.title)
        assertFalse(d.titleFromPlatform)
        assertFalse("the reported bug: 森空岛 · 森空岛",d.title.contains("·"))
        assertNull(d.rolesLine)
        assertNull(d.platformLine)
    }

    @Test fun completelyUnknownProviderFallsBackToTheMarker() {
        assertEquals(UNKNOWN_IDENTITY,display("","","",emptyList()).title)
        assertEquals(UNKNOWN_IDENTITY,display("   ","   ","   ",emptyList()).title)
    }

    // ------------------------------------------------------------- 7. pending sheet

    @Test fun pendingSessionSumsUpOnlyTheSelectedRoles() {
        val choices=listOf(
            RoleChoice("arknights|"+uid+"|1","arknights","明日方舟 · 清寒#7810"),
            RoleChoice("arknights|99|1","arknights","明日方舟 · 小号"),
        )
        val selected=pendingDisplay("skland","",uid,choices,setOf("arknights|"+uid+"|1"))
        assertEquals("明日方舟 · 清寒#7810",selected.title)
        assertNull("one selected role has nothing extra to count",selected.rolesLine)
        assertEquals("账号 12••••78",selected.platformLine)

        val none=pendingDisplay("skland","",uid,choices,emptySet())
        assertEquals("no selection yet -> every read role","明日方舟 · 清寒#7810",none.title)
        assertEquals("明日方舟 · 小号",none.rolesLine)
    }

    @Test fun pendingSessionWithUnreadRolesSaysPendingWithoutInventingAnIdentity() {
        val d=pendingDisplay("skland","我的号","",emptyList(),emptySet())
        assertEquals("我的号",d.title)
        assertNull("roles are not counted before they are read",d.rolesLine)
        assertFalse("nothing was read, so the UI may say 角色待同步",d.hasRoleData)
        assertNull(d.platformLine)
        assertEquals("角色待同步",PENDING_ROLES)
    }

    @Test fun pendingSessionPrefersTheOfficialCardOverTheTypedRemark() {
        val choices=listOf(RoleChoice("arknights|"+uid+"|1","arknights","明日方舟 · 清寒#7810"))
        val d=pendingDisplay("skland","我的号",uid,choices,setOf("arknights|"+uid+"|1"))
        assertEquals("明日方舟 · 清寒#7810",d.title)
        assertEquals("我的号",d.customNote)
    }

    @Test fun displayIsDeterministicAndDoesNotMutateInput() {
        val roles=listOf("明日方舟 · 清寒#7810")
        assertEquals(display("skland","","",roles),display("skland","","",roles))
        assertEquals(listOf("明日方舟 · 清寒#7810"),roles)
    }
}
