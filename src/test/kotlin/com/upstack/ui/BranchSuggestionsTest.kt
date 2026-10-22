package com.upstack.ui

import com.intellij.codeInsight.lookup.CharFilter
import com.upstack.stack.AddBranch
import com.upstack.stack.AddBranchKind
import org.junit.Assert.assertEquals
import org.junit.Test

class BranchSuggestionsTest {

    private val names = listOf(
        "feat/business-configuration-pay-runs",
        "feat/payments",
        "feature/pay-runs",
        "fix/pay-runs",
        "main",
        "release/1.2.0",
    )
    private val suggestions = BranchSuggestions(names.map { AddBranch(it, AddBranchKind.REMOTE, "origin") })

    private fun matching(typed: String) = names.filter { suggestions.createPrefixMatcher(typed).prefixMatches(it) }

    @Test
    fun `the prefix of the layer below lists every branch under it`() {
        assertEquals(listOf("feat/business-configuration-pay-runs", "feat/payments", "feature/pay-runs"), matching("feat/"))
    }

    @Test
    fun `after the prefix any word of the name finds the branch`() {
        assertEquals(listOf("feat/business-configuration-pay-runs", "feat/payments", "feature/pay-runs"), matching("feat/pay"))
        assertEquals(listOf("feat/business-configuration-pay-runs"), matching("feat/conf"))
        assertEquals(listOf("feat/business-configuration-pay-runs"), matching("FEAT/Business"))
    }

    @Test
    fun `without the prefix a word finds it anywhere`() {
        assertEquals(listOf("feat/business-configuration-pay-runs", "feature/pay-runs", "fix/pay-runs"), matching("pay-runs"))
    }

    @Test
    fun `any prefix works, with any number of levels`() {
        val others = listOf(
            "fix/pay-runs",
            "chore/deps-update",
            "victor/feat/payments-v2",
            "victor/fix/login",
            "ABC-123/login-page",
            "release/2026.10/hotfix",
        )
        val provider = BranchSuggestions(others.map { AddBranch(it, AddBranchKind.LOCAL) })
        fun matching(typed: String) = others.filter { provider.createPrefixMatcher(typed).prefixMatches(it) }

        assertEquals(listOf("fix/pay-runs"), matching("fix/pay"))
        assertEquals(listOf("chore/deps-update"), matching("chore/dep"))
        assertEquals(listOf("victor/feat/payments-v2", "victor/fix/login"), matching("victor/"))
        assertEquals(listOf("victor/feat/payments-v2"), matching("victor/feat/pay"))
        assertEquals(listOf("victor/feat/payments-v2"), matching("victor/pay"))
        assertEquals(listOf("ABC-123/login-page"), matching("abc-123/login"))
        assertEquals(listOf("release/2026.10/hotfix"), matching("release/2026.10/hot"))
        assertEquals(listOf("victor/fix/login", "ABC-123/login-page"), matching("login"))
    }

    @Test
    fun `the field starts with the prefix of the layer below, whatever it is`() {
        assertEquals("feat/", AddLayerDialog.prefixOf("feat/business-configuration-pay-runs"))
        assertEquals("fix/", AddLayerDialog.prefixOf("fix/pay-runs"))
        assertEquals("victor/feat/", AddLayerDialog.prefixOf("victor/feat/payments-v2"))
        assertEquals("ABC-123/", AddLayerDialog.prefixOf("ABC-123/login-page"))
        assertEquals("", AddLayerDialog.prefixOf("payments"))
    }

    @Test
    fun `an empty field lists everything`() {
        assertEquals(names, matching(""))
    }

    @Test
    fun `slash dot and dash keep filtering instead of closing or inserting`() {
        for (c in listOf('/', '.', '-', '_')) assertEquals("$c", CharFilter.Result.ADD_TO_PREFIX, suggestions.acceptChar(c))
        assertEquals(listOf("release/1.2.0"), matching("release/1.2"))
    }

    @Test
    fun `a space closes the list without inserting a branch`() {
        assertEquals(CharFilter.Result.HIDE_LOOKUP, suggestions.acceptChar(' '))
    }
}
