package com.jerreader.shared.learning

data class SentencePractice(val answer: String, val prompt: String) {
    companion object {
        fun make(source: String, answer: String): SentencePractice? {
            val term = answer.trim()
            if (term.isEmpty() || !source.contains(term)) return null
            return SentencePractice(term, source.replace(term, "［____］"))
        }
    }
}
