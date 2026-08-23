package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiSkillConfig
import org.json.JSONObject

object AiSkillPromptTool {

    const val TOOL_LOAD_SKILL = "load_ai_skill"

    fun resolvedTools(activeSkills: List<AiSkillConfig>): List<AiResolvedTool> {
        val skills = activeSkills
            .filter { it.enabled }
            .distinctBy { it.id }
        if (skills.isEmpty()) return emptyList()
        return listOf(
            AiResolvedTool(TOOL_LOAD_SKILL, loadSkillDefinition()) { args ->
                loadSkill(args, skills)
            }
        )
    }

    fun catalogPrompt(activeSkills: List<AiSkillConfig>): String {
        val skills = activeSkills
            .filter { it.enabled }
            .distinctBy { it.id }
        if (skills.isEmpty()) return ""
        return buildString {
            append("Agent skills are available for this window, but their full SKILL.md content is not preloaded.\n")
            append("Use the load_ai_skill tool only when a skill is relevant to the current task, then follow the returned prompt as workflow instructions.\n")
            append("Scripts are not supported in this app; skill content is prompt/instruction text only.\n")
            append("Available skills:\n")
            skills.forEach { skill ->
                append("- id: ").append(skill.id)
                    .append("\n  name: ").append(skill.name.ifBlank { "Skill" })
                if (skill.description.isNotBlank()) {
                    append("\n  description: ").append(skill.description)
                }
                if (skill.sourceUrl.isNotBlank()) {
                    append("\n  source: ").append(skill.sourceUrl)
                }
                append("\n")
            }
        }
    }

    fun catalogTokenText(activeSkills: List<AiSkillConfig>): String {
        return activeSkills
            .filter { it.enabled }
            .distinctBy { it.id }
            .joinToString(separator = "\n") { skill ->
                listOf(skill.id, skill.name, skill.description, skill.sourceUrl)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
    }

    private fun loadSkill(args: JSONObject?, activeSkills: List<AiSkillConfig>): String {
        args ?: return error("missing arguments")
        val skillId = args.optString("skillId").trim()
        val name = args.optString("name").trim()
        if (skillId.isBlank() && name.isBlank()) {
            return error("skillId or name is required")
        }
        val skill = activeSkills
            .filter { it.enabled }
            .firstOrNull { skill ->
                skill.id == skillId ||
                        (name.isNotBlank() && skill.name.equals(name, ignoreCase = true))
            }
            ?: return error("skill is not available in this window")
        return JSONObject()
            .put("ok", true)
            .put("skillId", skill.id)
            .put("name", skill.name)
            .put("description", skill.description)
            .put("sourceUrl", skill.sourceUrl)
            .put("content", skill.content)
            .put("scriptsSupported", false)
            .toString()
    }

    private fun loadSkillDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_LOAD_SKILL)
                put(
                    "description",
                    "Load one selected AI skill prompt on demand. Use only when the skill is relevant. Scripts are not supported; returned content is instruction text."
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("skillId", JSONObject().apply {
                            put("type", "string")
                            put("description", "Skill id from the available skill catalog.")
                        })
                        put("name", JSONObject().apply {
                            put("type", "string")
                            put("description", "Skill name from the available skill catalog.")
                        })
                    })
                    put("additionalProperties", false)
                })
            })
        }
    }

    private fun error(message: String) = JSONObject()
        .put("ok", false)
        .put("error", message)
        .toString()
}
