// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.widget

import androidx.test.core.app.ApplicationProvider
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.demo.DemoData
import io.github.sirallap.fulla.core.sync.LocalTransaction
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.core.widget.WidgetData
import io.github.sirallap.fulla.core.widget.WidgetFigures
import io.github.sirallap.fulla.core.widget.WidgetMode
import io.github.sirallap.fulla.data.repo.HouseholdState
import io.github.sirallap.fulla.ui.HouseholdView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The widget's words, for a phone-only household: the overview's own figures, or none at all when hidden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullaWidgetTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val today = LocalDate.now()

    private val view: HouseholdView by lazy {
        val demo = DemoData.build(today, "en")
        val bundle = Wire.bundle(demo.config)
        HouseholdView(
            HouseholdState(demo.config.household.id, false, bundle, Wire.config(bundle), emptyList(), null, null),
            demo.transactions.map { LocalTransaction(it, SyncState.LOCAL_ONLY) },
        )
    }

    @Test
    fun `with no household yet it only offers to begin`() {
        val t = FullaWidget.describe(res, null, hidden = false, today = today)
        assertEquals(res.getString(R.string.app_name), t.label)
        assertNull(t.amount)
    }

    @Test
    fun `hidden, it names the household and shows no amount`() {
        val t = FullaWidget.describe(res, view, hidden = true, today = today)
        assertEquals(view.config.household.name, t.label)
        assertEquals("•••", t.amount)
    }

    @Test
    fun `it shows the overview's own figure, as left or as over, never as a negative left`() {
        val f = view.formats
        val w = WidgetFigures.of(view.analytics, view.active, f.currentPeriod(today), today, view.deletedIds)
        val t = FullaWidget.describe(res, view, hidden = false, today = today)
        when {
            w.overMinor != null -> { assertEquals(res.getString(R.string.widget_over), t.label); assertEquals(f.money(w.overMinor!!), t.amount) }
            w.leftMinor != null -> { assertEquals(res.getString(R.string.widget_left), t.label); assertEquals(f.money(w.leftMinor!!), t.amount) }
            else -> { assertEquals(res.getString(R.string.widget_spent_label), t.label); assertEquals(f.money(w.spentMinor), t.amount) }
        }
        assertEquals(true, t.detail!!.contains(f.money(w.spentMinor)) || w.leftMinor == null)
    }

    @Test
    fun `every kind of widget has something to say, and none shows an amount when hidden`() {
        for (mode in WidgetMode.entries) {
            val t = FullaWidget.describe(res, view, hidden = false, today = today, mode = mode)
            assertEquals("$mode has a label", true, t.label.isNotBlank())
            assertEquals("$mode says something", true, t.amount != null || !t.detail.isNullOrBlank())
            val hidden = FullaWidget.describe(res, view, hidden = true, today = today, mode = mode)
            assertEquals("$mode hides its amounts", "•••", hidden.amount)
        }
    }

    @Test
    fun `latest movements are History's last three rows, one a line`() {
        val rows = WidgetData.recent(view.active, today)
        val t = FullaWidget.describe(res, view, hidden = false, today = today, mode = WidgetMode.RECENT)
        assertEquals(rows.size, t.detail!!.lines().size)
        assertEquals(3, t.detailLines)
    }

    @Test
    fun `today shows today's spending as the amount`() {
        val f = view.formats
        val t = FullaWidget.describe(res, view, hidden = false, today = today, mode = WidgetMode.TODAY)
        assertEquals(f.money(WidgetData.today(view.analytics, view.active, f.currentPeriod(today), today, view.deletedIds).spentMinor), t.amount)
    }
}
