package id.nine.assistant

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
import id.nine.assistant.databinding.ActivityFinanceBinding
import id.nine.assistant.nlu.FinPeriod
import id.nine.assistant.nlu.FinType
import id.nine.assistant.nlu.FinanceCats
import id.nine.assistant.nlu.FinanceMath
import id.nine.assistant.nlu.Txn
import id.nine.assistant.skills.FinanceStore
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

/** Layar catatan pemasukan & pengeluaran (dicatat lewat suara/ketikan ke Nine). */
class FinanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFinanceBinding
    private lateinit var store: FinanceStore
    private var period = FinPeriod.MONTH
    private var items: List<Txn> = emptyList()

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
            val lang = (application as NineApp).prefs.lang
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
        binding.btnPToday.setOnClickListener { select(FinPeriod.TODAY) }
        binding.btnPWeek.setOnClickListener { select(FinPeriod.WEEK) }
        binding.btnPMonth.setOnClickListener { select(FinPeriod.MONTH) }
        binding.btnPAll.setOnClickListener { select(FinPeriod.ALL) }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun select(p: FinPeriod) {
        period = p
        reload()
    }

    private fun confirmDelete(t: Txn) {
        val lang = (application as NineApp).prefs.lang
        AlertDialog.Builder(this)
            .setMessage(lang.t("Hapus catatan ${FinanceMath.money(t.amount, lang)} ini?", "Delete this ${FinanceMath.money(t.amount, lang)} entry?"))
            .setPositiveButton(R.string.fin_delete) { _, _ ->
                store.remove(t.id)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Suppress("NotifyDataSetChanged")
    private fun reload() {
        val lang = (application as NineApp).prefs.lang
        val (from, to) = FinanceMath.range(period, LocalDate.now())
        val all = store.all()
        val s = FinanceMath.summarize(all, from, to)
        items = all.filter { it.time >= from && it.time < to }.sortedByDescending { it.time }
        binding.txtBalance.text = lang.t("Saldo ", "Balance ") + FinanceMath.money(s.balance, lang) + " · " + FinanceMath.label(period, lang)
        binding.txtIncome.text = lang.t("Pemasukan ", "Income ") + FinanceMath.money(s.income, lang)
        binding.txtExpense.text = lang.t("Pengeluaran ", "Spending ") + FinanceMath.money(s.expense, lang)
        val top = s.expenseByCat.firstOrNull()
        binding.txtTop.text = if (top != null) {
            lang.t("Terbesar: ", "Biggest: ") + FinanceCats.name(top.first, lang) + " " + FinanceMath.money(top.second, lang)
        } else ""
        binding.txtTop.visibility = if (top != null) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        binding.txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
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
