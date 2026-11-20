package id.nine.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import id.nine.assistant.R
import id.nine.assistant.core.ConversationLog

/** Menampilkan riwayat dari [ConversationLog]: ucapan pengguna kecil di kanan, jawaban Nine besar di kiri. */
class MessageAdapter(private val log: ConversationLog) : RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(root: View) : RecyclerView.ViewHolder(root) {
        val text: TextView = root.findViewById(R.id.text)
    }

    override fun getItemViewType(position: Int): Int = if (log.entries[position].fromUser) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 1) R.layout.item_message_user else R.layout.item_message_nine
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    /** Layar asisten (transparan di atas aplikasi lain): jawaban Nine diberi alas kartu agar terbaca. */
    var bubbles = false

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = log.entries[position]
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

    override fun getItemCount(): Int = log.entries.size
}
