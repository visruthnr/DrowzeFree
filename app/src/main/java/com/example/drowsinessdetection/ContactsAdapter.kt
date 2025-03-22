package com.example.drowsinessdetection

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactsAdapter(
    private val contacts: List<Contact>,
    private val onDeleteClicked: (Int) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        holder.bind(contact, position)
    }

    override fun getItemCount(): Int = contacts.size

    inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.contact_name)
        private val phoneTextView: TextView = itemView.findViewById(R.id.contact_phone)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.delete_contact_button)

        fun bind(contact: Contact, position: Int) {
            nameTextView.text = contact.name
            phoneTextView.text = contact.phoneNumber
            
            deleteButton.setOnClickListener {
                onDeleteClicked(position)
            }
        }
    }
}
