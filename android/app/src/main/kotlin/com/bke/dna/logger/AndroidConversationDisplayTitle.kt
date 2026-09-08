package com.bke.dna.logger

/** Lightweight library metadata derived during reconciliation, never during list rendering. */
val AndroidLogicalConversation.displayTitle: String
    get() {
        val candidate = nodes.asSequence()
            .filter { it.roles.toSet() == setOf("user") }
            .mapNotNull { node ->
                val revision = node.revisions.maxByOrNull { it.lastObservedAt } ?: return@mapNotNull null
                val text = revision.textParts.joinToString("\n\n").trim()
                if (text.isBlank()) return@mapNotNull null
                val created = node.createdAtValues.minOrNull().orEmpty()
                created to text
            }
            .sortedBy { it.first.ifBlank { "~" } }
            .map { it.second }
            .firstOrNull()
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(DISPLAY_TITLE_LIMIT)

        return candidate?.takeIf { it.isNotBlank() } ?: conversationNativeId
    }

private const val DISPLAY_TITLE_LIMIT = 120
