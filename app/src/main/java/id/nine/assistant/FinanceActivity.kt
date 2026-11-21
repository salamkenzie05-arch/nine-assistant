package id.nine.assistant

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import id.nine.assistant.databinding.ActivityFinanceBinding
import id.nine.assistant.nlu.FinPeriod
import id.nine.assistant.nlu.FinType
import id.nine.assistant.nlu.FinanceCats
import id.nine.assistant.nlu.FinanceMath
import id.nine.assistant.nlu.Txn
import id.nine.assistant.skills.FinanceStore
import id.nine.assistant.ui.FinBarsView
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/** Layar catatan pemasukan & pengeluaran (dicatat lewat suara/ketikan ke Nine). */
class FinanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFinanceBinding
    private lateinit var store: FinanceStore
    private var period = FinPeriod.MONTH
    private var type = FinType.EXPENSE
    private var all: List<Txn> = emptyList()
    private var items: List<Txn> = emptyList()
    private val lang get() = (application as NineApp).prefs.lang

    private companion object {
        /** Warna bergilir untuk baris kategori (titik dan batang progres). */
        val CAT_COLORS = intArrayOf(R.color.cat_1, R.color.cat_2, R.color.cat_3, R.color.cat_4, R.color.cat_5)
    }

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.txtTitle)
        val sub: TextView = v.findViewById(R.id.txtSub)
        val amount: TextView = v.findViewById(R.id.txtAmount)
        val delete: ImageButton = v.findViewById(R.id.btnDelete)
    }

    private val adapter = object : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_finance, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            val income = t.type == FinType.INCOME
            holder.title.text = t.note.ifBlank { FinanceCats.name(t.cat, lang) }
            val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, lang.locale).format(Date(t.time))
            holder.sub.text = FinanceCats.name(t.cat, lang) + " · " + whenText
            holder.amount.text = (if (income) "+" else "-") + FinanceMath.money(t.amount, lang)
            holder.amount.setTextColor(ContextCompat.getColor(this@FinanceActivity, if (income) R.color.fin_income else R.color.fin_expense))
            holder.delete.setOnClickListener { confirmDelete(t) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFinanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = FinanceStore(this)
        setupInsets()
        binding.finList.layoutManager = LinearLayoutManager(this)
        binding.finList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.finPeriod.check(R.id.btnPMonth)
        binding.finType.check(R.id.btnTExpense)
        binding.finPeriod.addOnButtonCheckedListener { _, btn, checked ->
            if (!checked) return@addOnButtonCheckedListener
            period = when (btn) {
                R.id.btnPToday -> FinPeriod.TODAY
                R.id.btnPWeek -> FinPeriod.WEEK
                R.id.btnPAll -> FinPeriod.ALL
                else -> FinPeriod.MONTH
            }
            render()
        }
        binding.finType.addOnButtonCheckedListener { _, btn, checked ->
            if (!checked) return@addOnButtonCheckedListener
            type = if (btn == R.id.btnTIncome) FinType.INCOME else FinType.EXPENSE
            renderChart()
        }
        binding.finTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab()
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    override fun onResume() {
        super.onResume()
        all = store.all()
        render()
    }

    private fun recapShown(): Boolean = binding.finTabs.selectedTabPosition != 1

    private fun showTab() {
        val recap = recapShown()
        binding.finRecap.visibility = if (recap) View.VISIBLE else View.GONE
        binding.finList.visibility = if (recap || items.isEmpty()) View.GONE else View.VISIBLE
        binding.txtEmpty.visibility = if (!recap && items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(t: Txn) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.fin_delete_msg, FinanceMath.money(t.amount, lang)))
            .setPositiveButton(R.string.fin_delete) { _, _ ->
                store.remove(t.id)
                all = store.all()
                render()
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }

    private fun periodTitle(today: LocalDate): String = when (period) {
        FinPeriod.TODAY -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(lang.locale).format(today)
        FinPeriod.WEEK -> {
            val f = DateTimeFormatter.ofPattern("d MMM", lang.locale)
            val start = FinanceMath.weekStart(today)
            f.format(start) + " – " + f.format(start.plusDays(6))
        }
        FinPeriod.MONTH -> DateTimeFormatter.ofPattern("LLLL yyyy", lang.locale).format(today)
        else -> getString(R.string.fin_all_time)
    }

    @Suppress("NotifyDataSetChanged")
    private fun render() {
        val today = LocalDate.now()
        val (from, to) = FinanceMath.range(period, today)
        val s = FinanceMath.summarize(all, from, to)
        items = all.filter { it.time >= from && it.time < to }.sortedByDescending { it.time }
        binding.txtPeriod.text = periodTitle(today)
        binding.txtIncome.text = FinanceMath.money(s.income, lang)
        binding.txtExpense.text = FinanceMath.money(s.expense, lang)
        val diff = s.balance
        binding.txtDiff.text = (if (diff > 0) "+" else "") + FinanceMath.money(diff, lang)
        binding.txtDiff.setTextColor(ContextCompat.getColor(this, if (diff >= 0) R.color.fin_income else R.color.fin_expense))
        renderChart()
        adapter.notifyDataSetChanged()
        showTab()
    }

    /**
     * Grafik perbandingan pemasukan vs pengeluaran untuk periode ini, dan daftar kategori untuk jenis yang
     * dipilih (pengeluaran/pemasukan).
     */
    private fun renderChart() {
        val today = LocalDate.now()
        val (from, to) = FinanceMath.range(period, today)
        val s = FinanceMath.summarize(all, from, to)
        binding.finBars.setBars(
            listOf(
                FinBarsView.Bar(
                    getString(R.string.fin_income), s.income, FinanceMath.money(s.income, lang),
                    ContextCompat.getColor(this, R.color.fin_income_bar)
                ),
                FinBarsView.Bar(
                    getString(R.string.fin_expense), s.expense, FinanceMath.money(s.expense, lang),
                    ContextCompat.getColor(this, R.color.fin_expense_bar)
                )
            )
        )
        val cats = if (type == FinType.EXPENSE) s.expenseByCat else s.incomeByCat
        val pct = FinanceMath.shares(cats.map { it.second })
        binding.finCats.removeAllViews()
        for ((i, c) in cats.withIndex()) {
            val row = layoutInflater.inflate(R.layout.item_fin_cat, binding.finCats, false)
            row.findViewById<TextView>(R.id.catName).text = FinanceCats.name(c.first, lang)
            row.findViewById<TextView>(R.id.catAmount).text = FinanceMath.money(c.second, lang)
            row.findViewById<TextView>(R.id.catPct).text = if (pct[i] == 0) "<1%" else pct[i].toString() + "%"
            val color = ContextCompat.getColor(this, CAT_COLORS[i % CAT_COLORS.size])
            row.findViewById<View>(R.id.catDot).backgroundTintList = ColorStateList.valueOf(color)
            row.findViewById<LinearProgressIndicator>(R.id.catBar).apply {
                setIndicatorColor(color)
                setProgressCompat(pct[i], false)
            }
            binding.finCats.addView(row)
        }
        binding.txtCatEmpty.visibility = if (cats.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.financeRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
