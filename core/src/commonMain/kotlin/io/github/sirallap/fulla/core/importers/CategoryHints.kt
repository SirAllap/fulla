// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core.importers

import io.github.sirallap.fulla.core.defaults.Defaults
import io.github.sirallap.fulla.core.text.normalizeName

/**
 * What a category's name suggests, for categories that arrive in a file.
 *
 * [defaultKeys] recognises Fulla's own starting categories under any of their
 * six names and a few everyday synonyms, so a file's "Supermercado" lands on
 * the household's Groceries (whatever language the household was created in)
 * instead of becoming a second, iconless copy. [iconFor] picks an icon for a
 * category that really is new, from the words in its name.
 */
object CategoryHints {

    /** Words, beyond the defaults' own names, that mean a starting category. Compared normalised. */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "housing" to listOf("alquiler", "hipoteca", "rent", "mortgage", "loyer", "miete", "affitto", "renda", "aluguel"),
        "groceries" to listOf("supermercados", "alimentacion", "grocery", "food", "epicerie", "supermarkt", "supermercato", "mercearia"),
        "eating_out" to listOf("restaurantes", "restaurante", "restaurant", "bares", "ristoranti", "ristorante"),
        "transport" to listOf("transporte publico", "public transport"),
        "utilities" to listOf("facturas", "bills", "luz y agua"),
        "subscriptions" to listOf("suscripcion", "subscription", "abonnement", "abo", "abbonamento", "assinatura"),
        "health" to listOf("sanidad", "medico", "medical"),
        "leisure" to listOf("entretenimiento", "entertainment", "divertimento"),
        "education" to listOf("formacion", "training", "formation", "formazione", "formacao"),
        "gifts" to listOf("regalo", "gift", "cadeau", "geschenk", "regalo", "presente"),
        "other" to listOf("otros", "otro", "varios", "others", "misc", "autres", "altro", "altri", "outros"),
        "salary" to listOf("nomina", "sueldo", "payroll", "paycheck", "wages", "paie", "lohn", "busta paga", "ordenado"),
        "other_income" to listOf("otros", "varios", "others", "autres", "sonstige", "altri", "outros"),
    )

    private val names: Map<String, Set<String>> = Defaults.categories.associate { d ->
        d.key to (d.names.values + SYNONYMS[d.key].orEmpty()).map { it.normalizeName() }.toSet()
    }

    /** The starting categories [name] means, most likely first; empty when it is none of them. */
    fun defaultKeys(name: String): List<String> {
        val n = name.normalizeName()
        return Defaults.categories.map { it.key }.filter { n in names.getValue(it) }
    }

    internal fun synonymsOf(keys: List<String>): Set<String> = keys.flatMap { SYNONYMS[it].orEmpty() }.map { it.normalizeName() }.toSet()

    /** Word beginnings → icon, first match wins. Every icon is one the app draws. */
    private val ICONS: List<Pair<List<String>, String>> = listOf(
        listOf("farmac", "pharm", "apothek", "drogu") to "local_hospital",
        listOf("combust", "gasolin", "gasoil", "fuel", "petrol", "carbur", "benzin", "tanken", "diesel") to "local_gas_station",
        listOf("coche", "voiture", "macchina", "carro", "parking", "aparcamiento", "taller", "itv") to "directions_car",
        listOf("mascot", "perro", "gato", "veterin", "pet", "animal", "haustier", "chien", "chat") to "pets",
        listOf("viaje", "vacacion", "travel", "vuelo", "flight", "hotel", "voyage", "reise", "viagg", "viage", "ferias") to "flight",
        listOf("telefon", "movil", "phone", "mobile", "internet", "fibra", "handy", "telemovel") to "smartphone",
        listOf("gimnas", "gym", "deport", "sport", "fitness", "padel", "piscina") to "fitness_center",
        listOf("bebe", "nino", "ninos", "hijo", "guarder", "child", "kid", "baby", "enfant", "kind", "bambin", "crianca") to "child_care",
        listOf("cafe", "coffee", "cafeter", "kaffee") to "local_cafe",
        listOf("cine", "teatro", "concierto", "theat", "cinema", "concert", "kino") to "theater_comedy",
        listOf("loter", "lotto", "apuesta", "quiniela", "bet", "gioco", "jogo") to "confirmation_number",
        listOf("pelu", "barber", "hair", "estetic", "friseur", "coiff", "parrucch", "cabeleir") to "content_cut",
        listOf("seguro", "insur", "assur", "versicher", "assicur") to "shield",
        listOf("financ", "prestamo", "credito", "loan", "banco", "bank", "impuesto", "taxes", "hacienda", "fianza", "deposit", "steuer", "tasse", "impost", "kredit") to "account_balance",
        listOf("ropa", "cloth", "zapat", "moda", "vetement", "kleid", "abbigl", "roupa") to "checkroom",
        listOf("domicilio", "delivery", "restaur", "comida", "bar", "tapas", "pizza") to "restaurant",
        listOf("super", "mercado", "carnic", "fruter", "panader", "pescader", "grocer", "courses", "lebensmittel", "spesa") to "shopping_cart",
        listOf("luz", "agua", "electric", "suministr", "gas", "strom", "wasser", "bollett") to "bolt",
        listOf("suscrip", "subscri", "streaming", "abonn", "abbonam", "assinat") to "subscriptions",
        listOf("educa", "colegio", "school", "curso", "libro", "univers", "schule", "scuola", "escola") to "school",
        listOf("regalo", "gift", "cumple", "cadeau", "geschenk", "present") to "redeem",
        listOf("transport", "bus", "metro", "tren", "taxi", "train", "bahn") to "directions_bus",
        listOf("alquiler", "hipoteca", "hogar", "casa", "home", "rent", "mueble", "logement", "wohn", "miete") to "home",
        listOf("salud", "health", "medic", "dentist", "hospital", "sante", "gesund", "salute", "saude", "optica") to "favorite",
        listOf("compra", "shop", "achat", "einkauf", "acquist") to "shopping_bag",
        listOf("ocio", "leisure", "loisir", "freizeit", "juego", "game", "lazer") to "sports_esports",
        listOf("nomina", "sueldo", "salario", "salary", "paie", "gehalt", "stipend", "transfer") to "payments",
        listOf("ahorro", "saving", "inversion", "invest", "epargne", "sparen", "rispar", "poupan") to "savings",
        listOf("trabajo", "work", "oficina", "office", "travail", "arbeit", "lavoro", "trabalho") to "work",
        listOf("otro", "other", "vario", "misc", "autre", "sonstig", "altr", "outro") to "more_horiz",
    )

    /** An icon for a new category called [name], or null when nothing in it suggests one. */
    fun iconFor(name: String): String? {
        defaultKeys(name).firstOrNull()?.let { key -> return Defaults.categories.first { it.key == key }.icon }
        val words = name.normalizeName().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return ICONS.firstOrNull { (stems, _) -> words.any { w -> stems.any { w.startsWith(it) } } }?.second
    }
}
