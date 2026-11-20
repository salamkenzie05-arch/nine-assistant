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

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = log.entries[position]
        holder.text.text = if (e.text.isEmpty() && !e.fromUser) "…" else e.text
    }

    override fun getItemCount(): Int = log.entries.size
}
