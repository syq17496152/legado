package io.legado.app.ui.association

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ParagraphRuleVar
import java.io.File

class ParagraphRulePackageImporter(
    private val database: AppDatabase
) {
    fun inspect(file: File): ParagraphRuleImportInspection {
        val packageData = ParagraphRulePackageParser.parse(file)
        val existingNames = HashSet<String>()
        for (rule in database.paragraphRuleDao.all()) {
            existingNames.add(rule.name)
        }
        val conflicts = countParagraphRuleNameConflicts(
            importedNames = packageData.entries.map { it.rule.name },
            existingNames = existingNames
        )
        return ParagraphRuleImportInspection(packageData, conflicts)
    }

    suspend fun import(
        inspection: ParagraphRuleImportInspection,
        strategy: ParagraphRuleConflictStrategy
    ): ParagraphRuleImportResult = database.withTransaction {
        importInTransaction(inspection, strategy)
    }

    private fun importInTransaction(
        inspection: ParagraphRuleImportInspection,
        strategy: ParagraphRuleConflictStrategy
    ): ParagraphRuleImportResult {
        val dao = database.paragraphRuleDao
        val existingByName = HashMap<String, ParagraphRule>()
        val usedNames = HashSet<String>()
        for (rule in dao.all()) {
            val key = rule.name
            existingByName.putIfAbsent(key, rule)
            usedNames.add(key)
        }
        var nextOrder = (dao.maxOrder() ?: -1) + 1
        var inserted = 0
        var overwritten = 0
        var skipped = 0
        var renamed = 0

        for (entry in inspection.packageData.entries) {
            val imported = entry.rule
            val key = imported.name
            val conflict = existingByName[key]
            if (conflict == null) {
                val order = nextOrder++
                val id = insertNew(entry, imported.name, order)
                existingByName[key] = imported.copy(id = id, order = order)
                usedNames.add(key)
                inserted++
            } else if (strategy == ParagraphRuleConflictStrategy.SKIP) {
                skipped++
            } else if (strategy == ParagraphRuleConflictStrategy.OVERWRITE) {
                val updated = imported.copy(
                    id = conflict.id,
                    name = conflict.name,
                    order = conflict.order,
                    updateTime = System.currentTimeMillis()
                )
                dao.update(updated)
                if (entry.varsIncluded) replaceVars(conflict.id, entry.vars)
                existingByName[key] = updated
                overwritten++
            } else {
                val renamedValue = numberedImportedName(imported.name, usedNames)
                val renamedKey = renamedValue
                val order = nextOrder++
                val id = insertNew(entry, renamedValue, order)
                existingByName[renamedKey] = imported.copy(id = id, name = renamedValue, order = order)
                usedNames.add(renamedKey)
                inserted++
                renamed++
            }
        }
        return ParagraphRuleImportResult(inserted, overwritten, skipped, renamed)
    }

    private fun insertNew(entry: ParagraphRuleImportEntry, name: String, order: Int): Long {
        val id = database.paragraphRuleDao.insert(
            entry.rule.copy(
                id = 0L,
                name = name,
                order = order,
                updateTime = System.currentTimeMillis()
            )
        )
        if (entry.varsIncluded) replaceVars(id, entry.vars)
        return id
    }

    private fun replaceVars(ruleId: Long, vars: Map<String, String>) {
        val dao = database.paragraphRuleDao
        dao.deleteVars(ruleId)
        for ((name, value) in vars) {
            dao.putVar(ParagraphRuleVar(ruleId, name, value))
        }
    }

}

internal fun countParagraphRuleNameConflicts(
    importedNames: List<String>,
    existingNames: Set<String>
): Int {
    val usedNames = HashSet(existingNames)
    var conflictCount = 0
    for (name in importedNames) {
        if (!usedNames.add(name)) conflictCount++
    }
    return conflictCount
}

internal fun numberedImportedName(baseName: String, usedNames: Set<String>): String {
    var index = 2
    while (true) {
        val suffix = " ($index)"
        val prefix = baseName
            .take((MAX_PARAGRAPH_RULE_NAME_CHARS - suffix.length).coerceAtLeast(1))
            .trimEnd()
        val candidate = prefix + suffix
        if (candidate !in usedNames) return candidate
        index++
    }
}
