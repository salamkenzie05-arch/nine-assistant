package id.nine.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import id.nine.assistant.R
import id.nine.assistant.core.ConversationLog
import id.nine.assistant.core.HistoryWindow

/** Menampilkan riwayat dari [ConversationLog]: ucapan pengguna kecil di kanan, jawaban Nine besar di kiri. */
class MessageAdapter(private val log: ConversationLog) : RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(root: View) : RecyclerView.ViewHolder(root) {
        val text: TextView = root.findViewById(R.id.text)
    }

    private val window = HistoryWindow(log)

    /** Layar asisten: tampilkan hanya tanya-jawab terakhir (lihat [HistoryWindow]). */
    fun setWindowed(on: Boolean) {
        window.enable(on)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun onLogAdded(index: Int) {
        if (window.onAdded()) {
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        } else if (index >= window.start) notifyItemInserted(index - window.start)
    }

    fun onLogChanged(index: Int) {
        if (index >= window.start) notifyItemChanged(index - window.start)
    }

    fun onLogRemoved(index: Int) {
        if (window.onRemoved(index)) {
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        } else if (index >= window.start) notifyItemRemoved(index - window.start)
    }

    fun onLogCleared() {
        window.onCleared()
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    private fun entry(position: Int) = log.entries[window.start + position]

    override fun getItemViewType(position: Int): Int = if (entry(position).fromUser) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 1) R.layout.item_message_user else R.layout.item_message_nine
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    /** Layar asisten (transparan di atas aplikasi lain): jawaban Nine diberi alas kartu agar terbaca. */
    var bubbles = false

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = entry(position)
        holder.text.text = if (e.text.isEmpty() && !e.fromUser) "…" else e.text
        if (!e.fromUser) {
            val d = holder.text.resources.displayMetrics.density
            if (bubbles) {
                holder.text.setBackgroundResource(R.drawable.bg_assist_bubble)
                holder.text.setPadding((32 * d).toInt(), (12 * d).toInt(), (32 * d).toInt(), (12 * d).toInt())
            } else {
                holder.text.background = null
                holder.text.setPadding((28 * d).toInt(), 0, (56 * d).toInt(), 0)
            }
        }
    }

    override fun getItemCount(): Int = window.count
}
