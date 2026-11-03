package com.glasssign.app.data

/**
 * Pure display model for an account's identity: the 账号 page card, the account detail header and both binding
 * sheets. No state, no I/O -- the same inputs always produce the same texts.
 *
 * SOURCES (nothing else may be used, and nothing missing may be invented):
 *  - Account.providerUserId -- the platform's stable account id. Supplement only.
 *  - Account.nickname       -- the user's own remark. Never rewritten, always its own line.
 *  - the account's bound roles -- the official role display name exactly as the platform returned it, e.g.
 *    "明日方舟 · 清寒#7810". The whole name (prefix included) is official data and is never rewritten.
 *
 * RULES the JVM test (AccountDisplayTest) holds this file to:
 *  1. The default title is an official readable role name, byte-for-byte as returned (the "<game> · " prefix is
 *     official data and stays): an official card "名称#tag" first, otherwise the first official role name. A tag
 *     is never rewritten -- a card whose tag would need trimming is not a card -- and a "#" is never appended to
 *     a name that came without one (role ids and UID tails are not tags).
 *  2. The platform UID stays a supplementary, masked line under the title (existing maskedIdentity rule).
 *  3. A role id / target id is never presented as the platform account id. A role display name counts as an
 *     identity only when its own name part is readable: the "<game> · " prefix is a label, so it can never make an
 *     id-only name ("明日方舟 · 12345678") look like one.
 *  4. The user's own nickname is a separate, editable line. It is never merged into the title, never overwritten,
 *     and never repeated when it already is the title.
 *  5. Several roles -> one clear first row plus an "N 项角色" count; no list of names in the title. When the title
 *     already shows the only role, [rolesLine] is null and the title itself carries the role.
 *  6. Unknown identity says 身份待同步 and never renders a provider label twice ("森空岛 · 森空岛").
 *  7. No official role name and no tag -> the title is the user's own nickname (a real identity the user chose),
 *     else the provider label. Never a fabricated "#".
 */
data class AccountDisplay(
    /** Default identity line. */
    val title:String,
    /** True when [title] came from an official role name (a "#tag" card or a plain role name). */
    val titleFromPlatform:Boolean,
    /** The user's remark as its own line, or null when blank / identical to [title]. */
    val customNote:String?,
    /** Masked platform UID line, or null when the platform returned no stable account id. */
    val platformLine:String?,
    /**
     * Roles summary, or null when no role is known or when [title] already shows the only known role. The UI shows
     * [PENDING_ROLES] only when [hasRoleData] is false, so null here never contradicts a visible role title.
     */
    val rolesLine:String?,
    /** True when at least one official role name was read (so [title] may already carry that role). */
    val hasRoleData:Boolean=false,
)

internal const val UNKNOWN_IDENTITY="身份待同步"
internal const val PENDING_ROLES="角色待同步"

/** A readable name contains at least one letter (CJK included); an id or a bare tag is not a name. */
private fun String.hasLetter():Boolean = any{Character.isLetter(it)}

/**
 * The name part of an official role display name, i.e. everything after the provider's "<game> · " label.
 * Roles are stored as "<game> · <nickname>" (local/Providers.kt), so the game label must not be mistaken for the
 * account's own name: "明日方舟 · 12345678" is an id, not a nickname.
 */
private fun String.namePart():String {
    val mark=lastIndexOf(" · ")
    return if(mark<0) this else substring(mark+3).trim()
}

/**
 * Split a platform account-card name into (name, tag). Only "名称#tag" with both halves non-empty and a tag that
 * needs no trimming is accepted (a tag is never rewritten); anything else is returned untouched as the name with
 * no tag.
 */
internal fun splitAccountTag(value:String):Pair<String,String?> {
    val text=value
    val cut=text.indexOf('#')
    val second=if(cut<0) -1 else text.indexOf('#',cut+1)
    if(cut<0||second>=0)return text to null
    val rawName=text.substring(0,cut)
    val rawTag=text.substring(cut+1)
    val name=rawName.trim()
    if(name.isEmpty())return text to null
    // The tag is kept exactly as returned -- no trimming, no re-deriving -- so a padded tag is not a card.
    if(rawTag.isEmpty())return text to null
    if(rawTag.trim()!=rawTag)return text to null
    for(ch in rawTag)if(ch.isWhitespace())return text to null
    return name to rawTag
}

/**
 * The default account title taken from the account's official role display names, or null when the platform
 * returned nothing usable. Priority: an official card "名称#tag" first (byte-for-byte, the tag is never
 * re-derived), then any other readable official role name. A name is used exactly as returned -- the
 * "<game> · " prefix is part of the official data and stays -- but a role whose own name part is an id is never a
 * title, and a "#" is never appended to a name that came without one (role ids and UID tails are not tags).
 * [customNote] is excluded: a user remark must never become the default identity (不通过角色昵称合并账号).
 */
internal fun platformAccountName(roles:List<String>,customNote:String):String? {
    val note=customNote.trim()
    var fallback:String?=null
    for(role in roles) {
        val text=role.trim()
        val namePart=text.namePart()
        if(!namePart.hasLetter())continue
        if(text==note)continue
        val parts=splitAccountTag(text)
        val tag=parts.second
        if(tag!=null)return parts.first+"#"+tag
        if(fallback==null)fallback=text
    }
    return fallback
}

/** The card name inside one role row, or null when the row carries no tag. */
internal fun cardNameOf(role:String):String? {
    val parts=splitAccountTag(role)
    if(parts.second==null)return null
    return parts.first
}

/** Official role names worth listing: whole display names, id-only entries dropped, de-duplicated. */
internal fun roleNames(roles:List<String>):List<String> =
    roles.map{it.trim()}.filter{it.namePart().hasLetter()}.distinct()

/** The masked platform UID line. Blank -> no line at all (never a placeholder, never a role id). */
internal fun providerUidLine(providerUserId:String):String? {
    val uid=providerUserId.trim()
    return if(uid.isBlank()) null else "账号 "+maskedIdentity(uid)
}

fun accountDisplay(provider:String,nickname:String,providerUserId:String,boundRoles:List<String>):AccountDisplay {
    val note=nickname.trim()
    // Read from the RAW role names: the card name keeps its official "<game> · " prefix.
    val platform=platformAccountName(boundRoles,note)
    val names=roleNames(boundRoles)
    // Rows: whole official role names, minus one that the title already shows (as a full row or as a card name).
    val cards=boundRoles.mapNotNull{cardNameOf(it)}.toSet()
    val rows=names.filter{it!=platform&&it!=note&&it !in cards}
    val title=platform ?: (if(note.isNotBlank()) note else providerLabel(provider))
    var shownNote:String?=null
    if(note.isNotBlank()&&note!=title) shownNote=note
    return AccountDisplay(
        title=title,
        titleFromPlatform=platform!=null,
        customNote=shownNote,
        platformLine=providerUidLine(providerUserId),
        rolesLine=rolesLine(rows),
        hasRoleData=names.isNotEmpty(),
    )
}

/**
 * Identity of a not-yet-confirmed binding session. [roleChoices] may be empty (roles not read yet).
 * The local draft account id is deliberately not accepted: it is a draft id, not a platform identity, and must
 * never be shown as one.
 */
fun pendingDisplay(provider:String,nickname:String,providerUserId:String,roleChoices:List<RoleChoice>,selectedRoleIds:Set<String>):AccountDisplay {
    val chosen=roleChoices.filter{it.targetId in selectedRoleIds}.ifEmpty{roleChoices}
    return accountDisplay(provider,nickname,providerUserId,chosen.map{it.name})
}

/** One clear first row plus a count, never a list of names in the title. */
internal fun rolesLine(rows:List<String>):String? {
    if(rows.isEmpty())return null
    return if(rows.size==1) rows[0] else rows[0]+" 等 · 共 "+rows.size+" 项角色"
}

/** Provider label. One token, so a line built from it can never read "森空岛 · 森空岛". */
internal fun providerLabel(provider:String):String {
    val id=provider.trim()
    return when(id) {
        "miyoushe"->"米游社"
        "skland"->"森空岛"
        "tagged"->"塔吉多"
        "kurobbs"->"库街区"
        ""->UNKNOWN_IDENTITY
        else->id
    }
}
