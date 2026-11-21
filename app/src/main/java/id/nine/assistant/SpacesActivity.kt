package id.nine.assistant

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import id.nine.assistant.core.SpaceRules
import id.nine.assistant.core.SpaceStore
import id.nine.assistant.databinding.ActivitySpacesBinding

/**
 * Daftar ruang obrolan khusus (seperti "projects"): memilih ruang membuka obrolan terpisah dengan
 * instruksinya sendiri; baris pertama kembali ke obrolan utama.
 */
class SpacesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpacesBinding
    private lateinit var store: SpaceStore
    private var items: List<SpaceStore.Space> = emptyList()

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val body: View = v.findViewById(R.id.spaceBody)
        val name: TextView = v.findViewById(R.id.spaceName)
        val note: TextView = v.findViewById(R.id.spaceNote)
        val edit: ImageButton = v.findViewById(R.id.spaceEdit)
    }

    /** Posisi 0 = obrolan utama; sisanya = [items]. */
    private val adapter = object : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_space, parent, false))

        override fun getItemCount(): Int = items.size + 1

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position == 0) {
                holder.name.text = getString(R.string.spaces_main)
                holder.note.visibility = View.GONE
                holder.edit.visibility = View.GONE
                holder.body.setOnClickListener { open("") }
                return
            }
            val space = items[position - 1]
            holder.name.text = space.name
            holder.note.text = space.note
            holder.note.visibility = if (space.note.isBlank()) View.GONE else View.VISIBLE
            holder.edit.visibility = View.VISIBLE
            holder.body.setOnClickListener { open(space.id) }
            holder.edit.setOnClickListener { showEditor(space) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpacesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = (application as NineApp).spaces
        setupInsets()
        binding.spaceList.layoutManager = LinearLayoutManager(this)
        binding.spaceList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnNewSpace.setOnClickListener {
            if (items.size >= SpaceRules.MAX_SPACES) {
                Toast.makeText(this, getString(R.string.spaces_full, SpaceRules.MAX_SPACES), Toast.LENGTH_SHORT).show()
            } else showEditor(null)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    @Suppress("NotifyDataSetChanged")
    private fun reload() {
        items = store.list()
        adapter.notifyDataSetChanged()
    }

    /** Pilih ruang (atau obrolan utama bila [id] kosong); layar utama menukar isinya saat tampil kembali. */
    private fun open(id: String) {
        store.activeId = id
        finish()
    }

    /** Dialog ruang baru ([space] null) atau ubah/hapus ruang yang ada. */
    private fun showEditor(space: SpaceStore.Space?) {
        val v = layoutInflater.inflate(R.layout.dialog_space, null)
        val name = v.findViewById<EditText>(R.id.inputSpaceName)
        val note = v.findViewById<EditText>(R.id.inputSpaceNote)
        name.setText(space?.name.orEmpty())
        note.setText(space?.note.orEmpty())
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (space == null) R.string.spaces_new else R.string.spaces_edit)
            .setView(v)
            .setNegativeButton(R.string.act_cancel, null)
            .setPositiveButton(R.string.act_save) { _, _ ->
                val n = name.text?.toString().orEmpty()
                val t = note.text?.toString().orEmpty()
                if (space == null) {
                    val created = store.create(n, t)
                    if (created != null) open(created.id) // ruang baru langsung dibuka
                } else {
                    store.update(space.id, n, t)
                    reload()
                }
            }
        if (space != null) builder.setNeutralButton(R.string.act_delete) { _, _ -> confirmDelete(space) }
        builder.show()
    }

    private fun confirmDelete(space: SpaceStore.Space) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.spaces_delete_q, space.name))
            .setNegativeButton(R.string.act_cancel, null)
            .setPositiveButton(R.string.act_delete) { _, _ ->
                store.delete(space.id)
                reload()
            }
            .show()
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.spacesRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
