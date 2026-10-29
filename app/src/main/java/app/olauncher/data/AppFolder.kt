package app.olauncher.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator

/**
 * A named group of drawer entries. [members] holds [folderKey]s, so membership
 * survives renames and is simply ignored for apps that are no longer installed.
 */
data class AppFolder(
    val id: String,
    val name: String,
    val members: List<String>,
) {
    companion object {
        fun listFromJson(json: String): List<AppFolder> {
            if (json.isBlank()) return emptyList()
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    val folder = array.getJSONObject(i)
                    val members = folder.getJSONArray("members")
                    AppFolder(
                        id = folder.getString("id"),
                        name = folder.getString("name"),
                        members = (0 until members.length()).map { members.getString(it) },
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

        fun listToJson(folders: List<AppFolder>): String = JSONArray().apply {
            folders.forEach { folder ->
                put(JSONObject().apply {
                    put("id", folder.id)
                    put("name", folder.name)
                    put("members", JSONArray(folder.members))
                })
            }
        }.toString()
    }
}

/** Same "package|user" form as hidden apps; pinned shortcuts use their identity. */
val AppModel.folderKey: String
    get() = when (this) {
        is AppModel.PinnedShortcut -> identity
        else -> appPackage + "|" + user.toString()
    }

/**
 * Pulls folder members out of the alphabetical [apps] list, adds a [AppModel.Folder]
 * row for each folder that still has visible members, and lists the members of
 * [expandedIds] folders right under their row. [position] is a [Constants.FolderPosition]:
 * folder rows go in by name among the apps, or as a block above or below them.
 */
fun groupIntoFolders(
    apps: List<AppModel>,
    folders: List<AppFolder>,
    expandedIds: Set<String>,
    position: Int = Constants.FolderPosition.ALPHABETICAL,
): MutableList<AppModel> {
    if (folders.isEmpty()) return apps.toMutableList()

    val folderIdByKey = mutableMapOf<String, String>()
    folders.forEach { folder -> folder.members.forEach { folderIdByKey.putIfAbsent(it, folder.id) } }

    val membersById = folders.associate { it.id to mutableListOf<AppModel>() }
    val looseApps = mutableListOf<AppModel>()
    for (app in apps) {
        val folderId = folderIdByKey[app.folderKey]
        if (folderId != null) membersById.getValue(folderId).add(app.asFolderMember())
        else looseApps.add(app)
    }

    val byLabel = compareBy<AppModel, String>(Collator.getInstance()) { it.appLabel }
    val folderRows = folders
        .filter { membersById.getValue(it.id).isNotEmpty() }
        .map { AppModel.Folder(id = it.id, name = it.name, isExpanded = it.id in expandedIds) }
        .sortedWith(byLabel)
    val rows = when (position) {
        Constants.FolderPosition.TOP -> folderRows + looseApps
        Constants.FolderPosition.BOTTOM -> looseApps + folderRows
        else -> (looseApps + folderRows).sortedWith(byLabel)
    }

    return rows.flatMapTo(mutableListOf()) { row ->
        if (row is AppModel.Folder && row.isExpanded) listOf(row) + membersById.getValue(row.id)
        else listOf(row)
    }
}

private fun AppModel.asFolderMember(): AppModel = when (this) {
    is AppModel.App -> copy(inFolder = true)
    is AppModel.PinnedShortcut -> copy(inFolder = true)
    else -> this
}
