package id.nine.assistant

import android.content.res.Configuration
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import id.nine.assistant.databinding.ActivityTodoBinding
import id.nine.assistant.skills.TodoStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Layar daftar tugas harian (bisa juga dikelola lewat suara). */
class TodoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTodoBinding
    private lateinit var store: TodoStore
    private var items: List<TodoStore.Item> = emptyList()

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: MaterialCheckBox = v.findViewById(R.id.check)
        val text: TextView = v.findViewById(R.id.text)
        val delete: ImageButton = v.findViewById(R.id.btnDelete)
    }

    private val adapter = object : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_todo, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = item.done
            holder.text.text = item.text
            holder.text.paintFlags = if (item.done) holder.text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else holder.text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.text.setTextColor(
                ContextCompat.getColor(this@TodoActivity, if (item.done) R.color.nine_muted else R.color.nine_on_bg)
            )
            holder.check.setOnCheckedChangeListener { _, checked ->
                store.setDone(item.id, checked)
                reload()
            }
            holder.text.setOnClickListener { holder.check.toggle() }
            holder.delete.setOnClickListener {
                store.remove(item.id)
                reload()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTodoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = TodoStore(this)
        setupInsets()

        val lang = (application as NineApp).prefs.lang
        binding.txtDate.text = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", lang.locale))
        binding.todoList.layoutManager = LinearLayoutManager(this)
        binding.todoList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearDone.setOnClickListener {
            store.clearDone()
            reload()
        }
        binding.btnAddTodo.setOnClickListener { addTyped() }
        binding.inputTodo.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addTyped()
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun addTyped() {
        val t = binding.inputTodo.text?.toString().orEmpty().trim()
        if (t.isEmpty()) return
        store.add(t)
        binding.inputTodo.setText("")
        reload()
        binding.todoList.scrollToPosition(items.size - 1)
    }

    @Suppress("NotifyDataSetChanged")
    private fun reload() {
        items = store.today()
        adapter.notifyDataSetChanged()
        binding.txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.btnClearDone.visibility = if (items.any { it.done }) View.VISIBLE else View.GONE
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.todoRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
