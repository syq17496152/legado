package io.legado.app.help.ai

import kotlinx.coroutines.CancellationException

object AiAgentInterruption {

    const val USER_STOPPED_GENERATION = "User stopped generation"
    const val USER_STOPPED_READ_AI = "User stopped read ai"
    const val START_NEW_READ_AI_CHAT = "Start new read ai chat"
    const val SUPERSEDED_READ_AI_QUESTION = "Superseded by next read ai question"

    fun isUserCancellation(throwable: Throwable): Boolean {
        if (throwable !is CancellationException) return false
        val message = throwable.message.orEmpty()
        return message == USER_STOPPED_GENERATION ||
                message == USER_STOPPED_READ_AI ||
                message == START_NEW_READ_AI_CHAT ||
                message == SUPERSEDED_READ_AI_QUESTION
    }

    fun systemCancellationMessage(throwable: Throwable): String {
        val message = throwable.message.orEmpty().trim()
        return if (message.isNotBlank()) {
            "AI task was interrupted: $message"
        } else {
            "AI task was interrupted by the system"
        }
    }
}
