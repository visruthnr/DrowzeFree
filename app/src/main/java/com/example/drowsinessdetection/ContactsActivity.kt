package com.example.drowsinessdetection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

class ContactsActivity : AppCompatActivity() {

    private lateinit var contactsRecyclerView: RecyclerView
    private lateinit var contactsAdapter: ContactsAdapter
    private lateinit var addContactButton: FloatingActionButton
    private lateinit var backButton: MaterialButton

    private val contacts = mutableListOf<Contact>()
    private val PICK_CONTACT_REQUEST_CODE = 100
    private val READ_CONTACTS_PERMISSION_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        contactsRecyclerView = findViewById(R.id.contacts_recycler_view)
        addContactButton = findViewById(R.id.add_contact_fab)
        backButton = findViewById(R.id.back_button)

        setupRecyclerView()
        loadSavedContacts()

        addContactButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                    arrayOf(Manifest.permission.READ_CONTACTS), 
                    READ_CONTACTS_PERMISSION_CODE)
            } else {
                pickContact()
            }
        }

        backButton.setOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        contactsAdapter = ContactsAdapter(contacts) { position ->
            // Delete contact callback
            contacts.removeAt(position)
            contactsAdapter.notifyItemRemoved(position)
            Utils.saveContacts(this, contacts)
        }
        
        contactsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ContactsActivity)
            adapter = contactsAdapter
        }
    }

    private fun loadSavedContacts() {
        contacts.clear()
        contacts.addAll(Utils.getContacts(this))
        contactsAdapter.notifyDataSetChanged()
    }

    private fun pickContact() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        startActivityForResult(intent, PICK_CONTACT_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == PICK_CONTACT_REQUEST_CODE && resultCode == RESULT_OK) {
            val contactUri = data?.data ?: return
            
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            
            contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    
                    val phoneNumber = cursor.getString(numberIndex).replace("\\s".toRegex(), "")
                    val name = cursor.getString(nameIndex)
                    
                    // Check if contact already exists
                    val exists = contacts.any { it.phoneNumber == phoneNumber }
                    if (!exists) {
                        val newContact = Contact(name, phoneNumber)
                        contacts.add(newContact)
                        contactsAdapter.notifyItemInserted(contacts.size - 1)
                        Utils.saveContacts(this, contacts)
                        Toast.makeText(this, "Added $name to emergency contacts", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Contact already in emergency list", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == READ_CONTACTS_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickContact()
            } else {
                Toast.makeText(
                    this,
                    "Permission required to access contacts",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
