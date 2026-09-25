// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.core

import io.github.sirallap.fulla.core.importers.AmountColumns
import io.github.sirallap.fulla.core.importers.CategorizationRule
import io.github.sirallap.fulla.core.importers.CategoryHints
import io.github.sirallap.fulla.core.importers.ColumnGuess
import io.github.sirallap.fulla.core.importers.ColumnRole
import io.github.sirallap.fulla.core.importers.Csv
import io.github.sirallap.fulla.core.importers.CsvImporter
import io.github.sirallap.fulla.core.importers.ImportPlan
import io.github.sirallap.fulla.core.importers.ImportNames
import io.github.sirallap.fulla.core.importers.ImportProfile
import io.github.sirallap.fulla.core.importers.OfxImporter
import io.github.sirallap.fulla.core.importers.QifImporter
import io.github.sirallap.fulla.core.model.AppliesTo
import io.github.sirallap.fulla.core.model.Category
import io.github.sirallap.fulla.core.model.Recurrence
import io.github.sirallap.fulla.core.model.Split
import io.github.sirallap.fulla.core.model.TransactionKind
import io.github.sirallap.fulla.core.money.Currency
import io.github.sirallap.fulla.core.money.DecimalStyle
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Invented statements only: generic merchants, a fictitious year, made-up amounts. */
class ImportersTest {
    private val eur = Currency.require("EUR")

    private val csv = """
        Statement export
        Date;Description;Detail;Amount;Balance
        15/01/2030;GROCERY STORE 01;card 1234;-42,10;"1.037,20"
        16/01/2030;"COFFEE SHOP; TERRACE";;-3,50;1.033,70
        31/01/2030;ACME CORP;salary;2.500,00;3.533,70
        not a date;x;;1,00;0
        01/02/2030;CASH MACHINE;;-100,00;3.433,70
    """.trimIndent()

    private val profile = ImportProfile(
        delimiter = ';', skipRows = 1, hasHeader = true, dateColumn = 0, dateFormat = "dd/MM/yyyy",
        amount = AmountColumns.Single(3), decimal = DecimalStyle.COMMA, descriptionColumns = listOf(1, 2),
    )

    @Test
    fun `csv with a mapping`() {
        val r = CsvImporter.read(csv.toByteArray(), profile, eur)
        assertEquals(listOf(-4210L, -350L, 250000L, -10000L), r.lines.map { it.amountMinor })
        assertEquals("GROCERY STORE 01 · card 1234", r.lines[0].description)
        assertEquals("COFFEE SHOP; TERRACE", r.lines[1].description)
        assertEquals(listOf(6), r.skipped.map { it.line })
        assertEquals(';', Csv.guessDelimiter(csv.lines().drop(1).joinToString("\n")))
    }

    @Test
    fun `csv with debit and credit columns, in Windows-1252`() {
        val text = "Date,Payee,Out,In\n2030-01-05,CAFÉ,12.00,\n2030-01-06,REFUND,,5.25\n"
        val bytes = text.toByteArray(charset("windows-1252"))
        val r = CsvImporter.read(bytes, ImportProfile(dateColumn = 0, dateFormat = "yyyy-MM-dd",
            amount = AmountColumns.Split(2, 3), descriptionColumns = listOf(1)), eur)
        assertEquals(listOf(-1200L, 525L), r.lines.map { it.amountMinor })
        assertEquals("CAFÉ", r.lines[0].description)
    }

    @Test
    fun `ofx, sgml and xml`() {
        val sgml = """
            OFXHEADER:100
            <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20300115120000<TRNAMT>-42.10<FITID>A1<NAME>GROCERY STORE 01
            <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20300131<TRNAMT>2500.00<FITID>A2<NAME>ACME CORP<MEMO>salary
            </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
        """.trimIndent().toByteArray()
        assertEquals(1.0, OfxImporter.detect(sgml))
        val r = OfxImporter.read(sgml, eur)
        assertEquals(listOf(-4210L, 250000L), r.lines.map { it.amountMinor })
        assertEquals("ACME CORP · salary", r.lines[1].description)
        assertEquals("A1", r.lines[0].bankId)
        val xml = "<OFX><BANKTRANLIST><STMTTRN><DTPOSTED>20300110</DTPOSTED><TRNAMT>-7.49</TRNAMT><NAME>STREAMING SERVICE</NAME></STMTTRN></BANKTRANLIST></OFX>"
        assertEquals(-749L, OfxImporter.read(xml.toByteArray(), eur).lines.single().amountMinor)
    }

    @Test
    fun `qif`() {
        val qif = "!Type:Bank\nD01/15/2030\nT-42.10\nPGROCERY STORE 01\n^\nD01/31'30\nT2,500.00\nPACME CORP\n^\n"
        assertEquals(1.0, QifImporter.detect(qif.toByteArray()))
        val r = QifImporter.read(qif.toByteArray(), eur, "MM/dd/yyyy")
        assertEquals(listOf(-4210L), r.lines.map { it.amountMinor })
        assertEquals(1, r.skipped.size, "a two-digit year does not fit a four-digit format")
    }

    @Test
    fun `importing the same statement twice produces the same ids, and rules categorise`() {
        val lines = CsvImporter.read(csv.toByteArray(), profile, eur).lines
        val rules = listOf(
            CategorizationRule("r1", "grocery store", categoryId = Fixtures.GROCERIES),
            CategorizationRule("r2", "cash machine", kind = TransactionKind.TRANSFER, toAccountId = Fixtures.CASH),
        )
        fun plan(existing: Set<String>) = ImportPlan.propose(
            householdId = "00000000-0000-4000-8000-000000000001", accountId = Fixtures.MAIN, lines = lines, rules = rules,
            uncategorizedExpenseId = Fixtures.LEISURE, uncategorizedIncomeId = Fixtures.SALARY,
            payerMemberId = Fixtures.ALICE, defaultSplit = Split.Equal(listOf(Fixtures.ALICE, Fixtures.BOB)),
            existingIds = existing, now = "2030-02-02T10:00:00.000Z",
        )
        val first = plan(emptySet())
        assertEquals(Fixtures.GROCERIES, first[0].transaction.categoryId)
        assertEquals(TransactionKind.INCOME, first[2].transaction.kind)
        assertEquals(TransactionKind.TRANSFER, first[3].transaction.kind)
        assertEquals(Fixtures.CASH, first[3].transaction.toAccountId)
        assertEquals(Fixtures.LEISURE, first[1].transaction.categoryId)
        val again = plan(first.map { it.transaction.id }.toSet())
        assertEquals(first.map { it.transaction.id }, again.map { it.transaction.id })
        assertTrue(again.all { it.alreadyImported })
    }

    @Test
    fun `two identical lines in one file stay two transactions`() {
        val twice = "d,a\n01/01/2030,-1.00\n01/01/2030,-1.00\n"
        val lines = CsvImporter.read(twice.toByteArray(), ImportProfile(dateColumn = 0, dateFormat = "dd/MM/yyyy",
            amount = AmountColumns.Single(1)), eur).lines
        val ids = ImportPlan.propose("00000000-0000-4000-8000-000000000001", Fixtures.MAIN, lines, emptyList(),
            Fixtures.LEISURE, Fixtures.SALARY, Fixtures.ALICE, null, emptySet(), "2030-01-02T00:00:00.000Z")
            .map { it.transaction.id }
        assertEquals(2, ids.toSet().size)
        assertEquals(LocalDate.of(2030, 1, 1), lines[0].date)
    }

    /** A ledger exported by another app: every amount positive, a column says which way it went. */
    private val ledger = """
        date;type;amount;category;who;account;note
        2030-03-01;in;1.800,00;Salary;Bob;Main account;March pay
        2030-03-02;out;42,10;groceries;alice;Main account;weekly shop
        2030-03-03;OUT;12,00;Pets;B;Cash;vet treats
        2030-03-04;out;8,00;pets;Zed;Somewhere else;more treats
        2030-03-05;In;50,00;Gifts;Alice;Cash;birthday
    """.trimIndent()

    private val ledgerProfile = ImportProfile(
        delimiter = ';', dateColumn = 0, dateFormat = "yyyy-MM-dd", amount = AmountColumns.Single(2),
        decimal = DecimalStyle.COMMA, descriptionColumns = listOf(6), categoryColumn = 3,
        kindColumn = 1, incomeValues = listOf("in"), payerColumn = 4, accountColumn = 5,
    )

    @Test
    fun `a type column decides the sign when every amount is positive`() {
        val lines = CsvImporter.read(ledger.toByteArray(), ledgerProfile, eur).lines
        assertEquals(listOf(180000L, -4210L, -1200L, -800L, 5000L), lines.map { it.amountMinor })
        assertEquals(listOf("Bob", "alice", "B", "Zed", "Alice"), lines.map { it.payer })
        assertEquals("Cash", lines[2].account)
        assertEquals(listOf("out", "in"), CsvImporter.values(ledger.toByteArray(), ledgerProfile, 1).map { it.lowercase() })
    }

    @Test
    fun `the file's own categories, payers and accounts land on the household's`() {
        val config = Fixtures.config()
        val household = "00000000-0000-4000-8000-000000000001"
        val lines = CsvImporter.read(ledger.toByteArray(), ledgerProfile, eur).lines
        fun plan() = ImportPlan.propose(
            householdId = household, accountId = Fixtures.MAIN, lines = lines, rules = emptyList(),
            uncategorizedExpenseId = Fixtures.LEISURE, uncategorizedIncomeId = Fixtures.SALARY,
            payerMemberId = Fixtures.ALICE, defaultSplit = null, existingIds = emptySet(), now = "2030-03-06T00:00:00.000Z",
            names = ImportNames(config.categories, config.members, config.accounts),
        )
        val p = plan().map { it.transaction }
        assertEquals(Fixtures.SALARY, p[0].categoryId)
        assertEquals(TransactionKind.INCOME, p[0].kind)
        assertEquals(Fixtures.GROCERIES, p[1].categoryId)
        assertEquals(Fixtures.ALICE, p[1].paidByMemberId)
        // Initials answer too; an unknown name falls back to the chosen payer.
        assertEquals(Fixtures.BOB, p[2].paidByMemberId)
        assertEquals(Fixtures.ALICE, p[3].paidByMemberId)
        assertEquals(Fixtures.CASH, p[2].accountId)
        // An account the household lacks is created, once, with an id from its name.
        val made = ImportPlan.newAccounts(plan())
        assertEquals(listOf("Somewhere else"), made.map { it.name })
        assertEquals(made[0].id, p[3].accountId)
        assertEquals(ImportPlan.accountIdFor(household, "SOMEWHERE ELSE"), made[0].id)
        // "Pets" twice, spelled differently: one new category. "Gifts" is income, so an income category.
        val created = ImportPlan.newCategories(plan())
        assertEquals(listOf("Pets" to AppliesTo.EXPENSE, "Gifts" to AppliesTo.INCOME), created.map { it.name to it.appliesTo })
        assertEquals(p[2].categoryId, p[3].categoryId)
        assertEquals(created[0].id, p[2].categoryId)
        // The same file on another phone creates the same category and the same rows.
        assertEquals(p.map { it.id to it.categoryId }, plan().map { it.transaction.id to it.transaction.categoryId })
        assertEquals(ImportPlan.categoryId(household, "PETS"), created[0].id)
    }

    @Test
    fun `a rule still beats the file's category`() {
        val lines = CsvImporter.read(ledger.toByteArray(), ledgerProfile, eur).lines
        val config = Fixtures.config()
        val p = ImportPlan.propose("00000000-0000-4000-8000-000000000001", Fixtures.MAIN, lines,
            listOf(CategorizationRule("r", "treats", categoryId = Fixtures.SNACKS)),
            Fixtures.LEISURE, Fixtures.SALARY, Fixtures.ALICE, null, emptySet(), "2030-03-06T00:00:00.000Z",
            ImportNames(config.categories, config.members, config.accounts))
        assertEquals(Fixtures.SNACKS, p[2].transaction.categoryId)
        assertEquals(listOf("Gifts"), ImportPlan.newCategories(p).map { it.name })
    }

    @Test
    fun `an ordinary header maps itself, in any of the six languages`() {
        val en = ColumnGuess.columns(listOf("Date", "Type", "Amount", "Category", "Paid by", "Account", "Note"))
        assertEquals(mapOf(ColumnRole.DATE to 0, ColumnRole.KIND to 1, ColumnRole.AMOUNT to 2, ColumnRole.CATEGORY to 3,
            ColumnRole.PAYER to 4, ColumnRole.ACCOUNT to 5, ColumnRole.DESCRIPTION to 6), en)
        val es = ColumnGuess.columns(listOf("Fecha", "Concepto", "Importe", "Categoría", "Quién", "Cuenta"))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), listOf(ColumnRole.DATE, ColumnRole.DESCRIPTION, ColumnRole.AMOUNT,
            ColumnRole.CATEGORY, ColumnRole.PAYER, ColumnRole.ACCOUNT).map { es[it] })
        val de = ColumnGuess.columns(listOf("Buchungstag", "Verwendungszweck", "Betrag", "Saldo"))
        assertEquals(mapOf(ColumnRole.DATE to 0, ColumnRole.AMOUNT to 2, ColumnRole.DESCRIPTION to 1), de)
        // Two dates: the first wins, and "Value date" is not mistaken for the amount.
        val bank = ColumnGuess.columns(listOf("Booking date", "Value date", "Details", "Amount", "Balance"))
        assertEquals(0, bank[ColumnRole.DATE]); assertEquals(3, bank[ColumnRole.AMOUNT])
        assertEquals(emptyMap(), ColumnGuess.columns(listOf("a", "b")))
    }

    @Test
    fun `date format, decimal separator and income values are guessed from the values`() {
        val formats = listOf("dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd")
        assertEquals("MM/dd/yyyy", ColumnGuess.dateFormat(listOf("01/31/2030", "02/01/2030"), formats))
        assertEquals("dd/MM/yyyy", ColumnGuess.dateFormat(listOf("01/02/2030", ""), formats))
        assertEquals("yyyy-MM-dd", ColumnGuess.dateFormat(listOf("2030-03-01"), formats))
        assertEquals(null, ColumnGuess.dateFormat(listOf("soon"), formats))
        assertEquals(DecimalStyle.COMMA, ColumnGuess.decimal(listOf("1.234,56", "-3,5")))
        assertEquals(DecimalStyle.DOT, ColumnGuess.decimal(listOf("1,234.56")))
        assertEquals(null, ColumnGuess.decimal(listOf("12")))
        assertEquals(listOf("Ingreso", "in"), ColumnGuess.incomeValues(listOf("Gasto", "Ingreso", "in", "out")))
    }

    @Test
    fun `a category named after one of the household's accounts is money changing pockets`() {
        val file = """
            date;type;amount;category;account;note;repeats
            2030-04-01;out;200,00;cash;Main account;cash machine;variable
            2030-04-02;out;4,25;Groceries;Cash;market stall;variable
            2030-04-03;in;50,00;Cash;Main account;paid back in;variable
            2030-04-04;out;650,00;Rent;Main account;flat;fixed
        """.trimIndent()
        val header = file.lineSequence().first().split(';')
        val found = ColumnGuess.columns(header)
        assertEquals(6, found[ColumnRole.RECURRENCE])
        val profile = ImportProfile(delimiter = ';', dateColumn = 0, dateFormat = "yyyy-MM-dd", amount = AmountColumns.Single(2),
            decimal = DecimalStyle.COMMA, descriptionColumns = listOf(5), categoryColumn = 3, kindColumn = 1,
            incomeValues = listOf("in"), accountColumn = 4, fixedColumn = 6,
            fixedValues = ColumnGuess.fixedValues(listOf("variable", "fixed")))
        val config = Fixtures.config()
        val p = ImportPlan.propose("00000000-0000-4000-8000-000000000001", Fixtures.MAIN,
            CsvImporter.read(file.toByteArray(), profile, eur).lines, emptyList(),
            Fixtures.LEISURE, Fixtures.SALARY, Fixtures.ALICE, null, emptySet(), "2030-04-05T00:00:00.000Z",
            ImportNames(config.categories, config.members, config.accounts))
        val t = p.map { it.transaction }
        assertEquals(TransactionKind.TRANSFER, t[0].kind)
        assertEquals(Fixtures.MAIN to Fixtures.CASH, t[0].accountId to t[0].toAccountId)
        assertEquals(null, t[0].categoryId)
        assertEquals(TransactionKind.EXPENSE, t[1].kind)
        assertEquals(Fixtures.CASH, t[1].accountId)
        assertEquals(TransactionKind.TRANSFER, t[2].kind)
        assertEquals(Fixtures.CASH to Fixtures.MAIN, t[2].accountId to t[2].toAccountId)
        assertEquals(Recurrence.FIXED, t[3].recurrence)
        assertEquals(Recurrence.VARIABLE, t[1].recurrence)
        // Only "Rent" is new: the pocket moves create no category.
        assertEquals(listOf("Rent"), ImportPlan.newCategories(p).map { it.name })
    }

    @Test
    fun `money in stays income, and one name makes one category for both kinds`() {
        val file = """
            date;type;amount;category;note
            2030-05-01;out;45,95;Shopping;plugs
            2030-05-02;in;100,00;Shopping;sold the old screen
            2030-05-03;in;8,88;Groceries;refund
            2030-05-04;out;12,00;Salary;payroll fee
            2030-05-05;in;1.200,00;Bonus;
        """.trimIndent()
        val profile = ImportProfile(delimiter = ';', dateColumn = 0, dateFormat = "yyyy-MM-dd", amount = AmountColumns.Single(2),
            decimal = DecimalStyle.COMMA, descriptionColumns = listOf(4), categoryColumn = 3, kindColumn = 1, incomeValues = listOf("in"))
        val config = Fixtures.config()
        val p = ImportPlan.propose("00000000-0000-4000-8000-000000000001", Fixtures.MAIN,
            CsvImporter.read(file.toByteArray(), profile, eur).lines, emptyList(),
            Fixtures.LEISURE, Fixtures.SALARY, Fixtures.ALICE, Split.Equal(listOf(Fixtures.ALICE, Fixtures.BOB)), emptySet(),
            "2030-05-06T00:00:00.000Z", ImportNames(config.categories, config.members, config.accounts))
        val t = p.map { it.transaction }
        // The file's word is kept, so totals match the ledger it came from.
        assertEquals(listOf(TransactionKind.EXPENSE, TransactionKind.INCOME, TransactionKind.INCOME, TransactionKind.EXPENSE, TransactionKind.INCOME),
            t.map { it.kind })
        assertEquals(t[0].categoryId, t[1].categoryId)
        assertEquals(Fixtures.GROCERIES, t[2].categoryId)
        assertEquals(Fixtures.SALARY, t[3].categoryId)
        val written = ImportPlan.newCategories(p)
        assertEquals(listOf("Shopping" to AppliesTo.BOTH, "Groceries" to AppliesTo.BOTH, "Salary" to AppliesTo.BOTH, "Bonus" to AppliesTo.INCOME),
            written.map { it.name to it.appliesTo })
        // Existing ones keep their ids, only widened.
        assertEquals(listOf(Fixtures.GROCERIES, Fixtures.SALARY), written.filter { it.name in setOf("Groceries", "Salary") }.map { it.id })
    }

    @Test
    fun `a file's names land on Fulla's own categories, in any language, and new ones get an icon`() {
        assertEquals(listOf("groceries"), CategoryHints.defaultKeys("Supermercado"))
        assertEquals(listOf("salary"), CategoryHints.defaultKeys("Nómina"))
        assertEquals(listOf("eating_out"), CategoryHints.defaultKeys("restaurantes"))
        assertEquals(listOf("other", "other_income"), CategoryHints.defaultKeys("Otros"))
        assertEquals(emptyList(), CategoryHints.defaultKeys("Carnicería"))
        assertEquals("local_hospital", CategoryHints.iconFor("Farmacia"))
        assertEquals("local_gas_station", CategoryHints.iconFor("Combustible"))
        assertEquals("directions_bus", CategoryHints.iconFor("Taxi"))
        assertEquals("content_cut", CategoryHints.iconFor("peluquería"))
        assertEquals("shopping_cart", CategoryHints.iconFor("Carnicería"))
        assertEquals("shopping_cart", CategoryHints.iconFor("Supermercado"))
        assertEquals(null, CategoryHints.iconFor("Zzyzx"))

        // A household created in English, a file written in Spanish.
        val file = """
            date;type;amount;category;note
            2030-06-01;out;30,00;Supermercado;
            2030-06-02;out;12,00;Restaurantes;
            2030-06-03;in;2.000,00;Nómina;
            2030-06-04;in;20,00;Otros;
            2030-06-05;out;9,00;Farmacia;
        """.trimIndent()
        val profile = ImportProfile(delimiter = ';', dateColumn = 0, dateFormat = "yyyy-MM-dd", amount = AmountColumns.Single(2),
            decimal = DecimalStyle.COMMA, descriptionColumns = listOf(4), categoryColumn = 3, kindColumn = 1, incomeValues = listOf("in"))
        val eatingOut = Category("00000000-0000-4000-8000-000000000105", "Eating out", AppliesTo.EXPENSE, icon = "restaurant")
        val otherIncome = Category("00000000-0000-4000-8000-000000000106", "Other income", AppliesTo.INCOME, icon = "savings")
        val other = Category("00000000-0000-4000-8000-000000000107", "Other", AppliesTo.EXPENSE, icon = "more_horiz")
        val cats = Fixtures.config().categories + listOf(eatingOut, other, otherIncome)
        val p = ImportPlan.propose("00000000-0000-4000-8000-000000000001", Fixtures.MAIN,
            CsvImporter.read(file.toByteArray(), profile, eur).lines, emptyList(), Fixtures.LEISURE, Fixtures.SALARY,
            Fixtures.ALICE, null, emptySet(), "2030-06-06T00:00:00.000Z", ImportNames(cats, Fixtures.config().members, Fixtures.config().accounts))
        assertEquals(listOf(Fixtures.GROCERIES, eatingOut.id, Fixtures.SALARY, otherIncome.id), p.take(4).map { it.transaction.categoryId })
        val made = ImportPlan.newCategories(p)
        assertEquals(listOf("Farmacia" to "local_hospital"), made.map { it.name to it.icon })
    }
}
