// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.defaults

import io.github.sirallap.fulla.core.model.AccountType
import io.github.sirallap.fulla.core.model.AppliesTo
import io.github.sirallap.fulla.core.model.Config

data class DefaultCategory(val key: String, val appliesTo: AppliesTo, val icon: String, val colorIndex: Int, val names: Map<String, String>) {
    fun name(language: String): String = names[language] ?: names.getValue("en")
}

data class DefaultAccount(val key: String, val type: AccountType, val names: Map<String, String>) {
    fun name(language: String): String = names[language] ?: names.getValue("en")
}

/**
 * What a new household starts with: neutral categories and two accounts, in
 * its language. No people, no amounts. Generated from
 * testdata/defaults/categories.json, which the database embeds too; a test
 * checks all three agree.
 */
object Defaults {
    val categories: List<DefaultCategory> = listOf(
        DefaultCategory("housing", AppliesTo.EXPENSE, "home", 0, mapOf("en" to "Housing", "es" to "Vivienda", "fr" to "Logement", "de" to "Wohnen", "it" to "Casa", "pt" to "Habitação")),
        DefaultCategory("groceries", AppliesTo.EXPENSE, "shopping_cart", 1, mapOf("en" to "Groceries", "es" to "Supermercado", "fr" to "Courses", "de" to "Lebensmittel", "it" to "Spesa", "pt" to "Supermercado")),
        DefaultCategory("eating_out", AppliesTo.EXPENSE, "restaurant", 2, mapOf("en" to "Eating out", "es" to "Comer fuera", "fr" to "Restaurants", "de" to "Auswärts essen", "it" to "Mangiare fuori", "pt" to "Comer fora")),
        DefaultCategory("transport", AppliesTo.EXPENSE, "directions_bus", 3, mapOf("en" to "Transport", "es" to "Transporte", "fr" to "Transports", "de" to "Verkehr", "it" to "Trasporti", "pt" to "Transportes")),
        DefaultCategory("utilities", AppliesTo.EXPENSE, "bolt", 4, mapOf("en" to "Utilities", "es" to "Suministros", "fr" to "Factures", "de" to "Nebenkosten", "it" to "Utenze", "pt" to "Contas da casa")),
        DefaultCategory("subscriptions", AppliesTo.EXPENSE, "subscriptions", 5, mapOf("en" to "Subscriptions", "es" to "Suscripciones", "fr" to "Abonnements", "de" to "Abos", "it" to "Abbonamenti", "pt" to "Assinaturas")),
        DefaultCategory("health", AppliesTo.EXPENSE, "favorite", 6, mapOf("en" to "Health", "es" to "Salud", "fr" to "Santé", "de" to "Gesundheit", "it" to "Salute", "pt" to "Saúde")),
        DefaultCategory("shopping", AppliesTo.EXPENSE, "shopping_bag", 7, mapOf("en" to "Shopping", "es" to "Compras", "fr" to "Achats", "de" to "Einkäufe", "it" to "Acquisti", "pt" to "Compras")),
        DefaultCategory("leisure", AppliesTo.EXPENSE, "sports_esports", 8, mapOf("en" to "Leisure", "es" to "Ocio", "fr" to "Loisirs", "de" to "Freizeit", "it" to "Tempo libero", "pt" to "Lazer")),
        DefaultCategory("education", AppliesTo.EXPENSE, "school", 9, mapOf("en" to "Education", "es" to "Educación", "fr" to "Éducation", "de" to "Bildung", "it" to "Istruzione", "pt" to "Educação")),
        DefaultCategory("gifts", AppliesTo.EXPENSE, "redeem", 10, mapOf("en" to "Gifts", "es" to "Regalos", "fr" to "Cadeaux", "de" to "Geschenke", "it" to "Regali", "pt" to "Presentes")),
        DefaultCategory("other", AppliesTo.EXPENSE, "more_horiz", 11, mapOf("en" to "Other", "es" to "Otros gastos", "fr" to "Autres dépenses", "de" to "Sonstiges", "it" to "Altre spese", "pt" to "Outras despesas")),
        DefaultCategory("salary", AppliesTo.INCOME, "payments", 0, mapOf("en" to "Salary", "es" to "Salario", "fr" to "Salaire", "de" to "Gehalt", "it" to "Stipendio", "pt" to "Salário")),
        DefaultCategory("other_income", AppliesTo.INCOME, "savings", 1, mapOf("en" to "Other income", "es" to "Otros ingresos", "fr" to "Autres revenus", "de" to "Sonstige Einnahmen", "it" to "Altre entrate", "pt" to "Outras receitas")),
    )

    val uncategorized = DefaultCategory("uncategorized", AppliesTo.BOTH, "help", 11, mapOf("en" to "Uncategorized", "es" to "Sin categoría", "fr" to "Sans catégorie", "de" to "Ohne Kategorie", "it" to "Senza categoria", "pt" to "Sem categoria"))

    val accounts: List<DefaultAccount> = listOf(
        DefaultAccount("cash", AccountType.CASH, mapOf("en" to "Cash", "es" to "Efectivo", "fr" to "Espèces", "de" to "Bargeld", "it" to "Contanti", "pt" to "Dinheiro")),
        DefaultAccount("main", AccountType.CHECKING, mapOf("en" to "Main account", "es" to "Cuenta principal", "fr" to "Compte principal", "de" to "Hauptkonto", "it" to "Conto principale", "pt" to "Conta principal")),
    )

    /** Languages the defaults are written in. */
    val LANGUAGES = setOf("en", "es", "fr", "de", "it", "pt")

    /** The language defaults are written in for a locale, English when it is not one of [LANGUAGES]. */
    fun languageOf(locale: String): String = locale.substringBefore('-').substringBefore('_').lowercase().takeIf { it in LANGUAGES } ?: "en"

    private val categoryNames: Map<String, Map<String, String>> =
        (categories + uncategorized).flatMap { d -> d.names.values.map { it to d.names } }.toMap()
    private val accountNames: Map<String, Map<String, String>> =
        accounts.flatMap { d -> d.names.values.map { it to d.names } }.toMap()

    /**
     * A category or account as it reads in [language]: a name that is still
     * exactly one of the defaults, in whichever language the household was
     * created, is shown in the app's language instead. A name somebody chose
     * is left alone. Two people in one household each read the defaults in
     * their own language, and nothing stored changes.
     */
    fun localized(config: Config, language: String): Config {
        val lang = languageOf(language)
        return config.copy(
            categories = config.categories.map { c -> categoryNames[c.name]?.let { c.copy(name = it[lang] ?: c.name) } ?: c },
            accounts = config.accounts.map { a -> accountNames[a.name]?.let { a.copy(name = it[lang] ?: a.name) } ?: a },
        )
    }
}
