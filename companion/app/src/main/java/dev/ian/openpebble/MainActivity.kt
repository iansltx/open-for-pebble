package dev.ian.openpebble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import dev.ian.openpebble.alta.AltaTrigger
import dev.ian.openpebble.cloud.OpenCloud
import dev.ian.openpebble.pebble.BridgeService
import dev.ian.openpebble.pebble.PebbleBridge

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var doorList: ListView
    private lateinit var adapter: DoorAdapter

    private var doors: MutableList<Door> = mutableListOf()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        doors = DoorStore.load(this)

        statusView = findViewById(R.id.status_view)
        doorList = findViewById(R.id.doors)

        adapter = DoorAdapter()
        doorList.adapter = adapter
        doorList.setOnItemClickListener { _, _, position, _ -> showEditDialog(doors.getOrNull(position)) }
        doorList.setOnItemLongClickListener { _, _, position, _ ->
            doors.getOrNull(position)?.let { door ->
                AlertDialog.Builder(this)
                    .setTitle("Delete \"${door.name}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        doors.remove(door)
                        DoorStore.save(this, doors)
                        adapter.notifyDataSetChanged()
                        refreshStatus()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }

        findViewById<Button>(R.id.btn_add).setOnClickListener { showEditDialog(null) }
        findViewById<Button>(R.id.btn_discover).setOnClickListener { showSignInDialog() }
        findViewById<Button>(R.id.btn_push).setOnClickListener {
            BridgeService.start(this, BridgeService.ACTION_SYNC)
            Toast.makeText(this, "Pushed ${doors.size} doors to the watch", Toast.LENGTH_SHORT).show()
        }

        // Android 13+ runtime notifications permission (bridge service + fallback notices).
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        doors = DoorStore.load(this)
        adapter.notifyDataSetChanged()
        refreshStatus()
    }

    // ----------------------------------------------------------------- UI

    private fun refreshStatus() {
        val alta = if (AltaTrigger.isAltaInstalled(this)) "✓ Alta Open installed" else "✗ Alta Open not installed"
        val pebble = if (PebbleBridge.isPebbleAppInstalled(this)) "✓ Pebble app installed" else "✗ Pebble app not installed"
        val overlay = if (AltaTrigger.canTriggerFromBackground(this)) {
            "✓ Can unlock with screen off"
        } else {
            "✗ Grant 'Display over other apps' for screen-off unlocks (tap here)"
        }
        statusView.text = buildString {
            append(alta); append('\n')
            append(pebble); append('\n')
            append(overlay); append('\n')
            append("Watch app UUID: "); append(DoorStore.pebbleUuid(this@MainActivity))
            append(" (long-press to edit)")
        }
        statusView.setOnClickListener {
            if (!AltaTrigger.canTriggerFromBackground(this)) {
                startActivity(AltaTrigger.overlaySettingsIntent(this))
            }
        }
        statusView.setOnLongClickListener {
            showUuidEditDialog()
            true
        }
    }

    /** Editor for an existing door, or a new one when [existing] is null. */
    @SuppressLint("SetTextI18n")
    private fun showEditDialog(existing: Door?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Door name (e.g. Main Lobby)"
            setText(existing?.name ?: "")
        }
        val idInput = EditText(this).apply {
            hint = "Entry ID (number)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(existing?.id?.toString() ?: "")
        }
        val typeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val entryRadio = RadioButton(this).apply {
            text = "Entry (door — most common)"
            id = View.generateViewId()
        }
        val readerRadio = RadioButton(this).apply {
            text = "Reader (individual door reader)"
            id = View.generateViewId()
        }
        typeGroup.addView(entryRadio)
        typeGroup.addView(readerRadio)
        if (existing?.type == Door.TYPE_READER) readerRadio.isChecked = true else entryRadio.isChecked = true

        val unlockNow = Button(this).apply { text = "Unlock now (test)" }

        layout.addView(nameInput)
        layout.addView(idInput)
        layout.addView(typeGroup)
        layout.addView(unlockNow)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add door" else "Edit door")
            .setView(layout)
            .setPositiveButton("Save") { _, _ -> }
            .setNegativeButton("Cancel", null)
            .apply {
                if (existing != null) setNeutralButton("Delete") { _, _ -> }
            }
            .create()

        fun parseDoor(): Door? {
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(this@MainActivity, "Name required", Toast.LENGTH_SHORT).show()
                return null
            }
            val id = idInput.text.toString().trim().toIntOrNull()
            if (id == null) {
                Toast.makeText(this@MainActivity, "Numeric ID required", Toast.LENGTH_SHORT).show()
                return null
            }
            val type = if (readerRadio.isChecked) Door.TYPE_READER else Door.TYPE_ENTRY
            return Door(name, id, type)
        }

        unlockNow.setOnClickListener {
            val door = parseDoor() ?: return@setOnClickListener
            val status = AltaTrigger.trigger(this, door, requestId = 0)
            val msg = when (status) {
                AltaTrigger.STATUS_OK -> "Unlock requested ✓"
                AltaTrigger.STATUS_BLOCKED -> "Blocked from background — notification posted"
                else -> "Failed — is Alta Open installed?"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val door = parseDoor() ?: return@setOnClickListener
            existing?.let { doors.remove(it) }
            doors.add(door)
            doors.sortBy { it.name.lowercase() }
            DoorStore.save(this, doors)
            adapter.notifyDataSetChanged()
            refreshStatus()
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            existing?.let { doors.remove(it) }
            DoorStore.save(this, doors)
            adapter.notifyDataSetChanged()
            dialog.dismiss()
        }
    }

    // ------------------------------------------------------------- cloud

    @SuppressLint("SetTextI18n")
    private fun showSignInDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 0)
        }
        val email = EditText(this).apply { hint = "Open account email" }
        val password = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val totp = EditText(this).apply {
            hint = "TOTP code (optional)"
        }
        layout.addView(email); layout.addView(password); layout.addView(totp)

        AlertDialog.Builder(this)
            .setTitle("Sign in to Open")
            .setMessage("Used once to discover your doors' names and IDs. " +
                "Credentials are not stored; the api token stays on this device.")
            .setView(layout)
            .setPositiveButton("Sign in") { _, _ ->
                startDiscovery(
                    email.text.toString().trim(),
                    password.text.toString(),
                    totp.text.toString().trim()
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDiscovery(email: String, password: String, totp: String) {
        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Email and password required", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Signing in…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val namespaces = OpenCloud.determineNamespaces(email)
                val first = namespaces.optJSONObject(0)
                val namespaceId = first?.optInt("id")

                val login = OpenCloud.login(email, password, namespaceId, totp)
                DoorStore.setCloudApiToken(this, login.apiToken)
                val found = OpenCloud.discoverDoors(login)

                mainHandler.post { showDiscoveryResults(found) }
            } catch (e: Exception) {
                log(e.message ?: e.toString())
                mainHandler.post {
                    AlertDialog.Builder(this)
                        .setTitle("Sign-in failed")
                        .setMessage(e.message ?: "See logcat")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Show debug log") { _, _ -> showDebugLog() }
                        .show()
                }
            }
        }.start()
    }

    private fun showDiscoveryResults(found: List<Door>) {
        if (found.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No doors found")
                .setMessage("The API returned no recognizable entries/readers. " +
                    "Check the debug log against the reverse-engineering notes, then " +
                    "add doors manually.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Show debug log") { _, _ -> showDebugLog() }
                .show()
            return
        }
        val names = found.map { "${it.name} (${it.typeLabel.lowercase()} #${it.id})" }.toTypedArray()
        val checked = found.map { !doors.any { d -> d.id == it.id && d.type == it.type } }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Select doors for the watch")
            .setMultiChoiceItems(names, checked) { _, _, _ -> }
            .setPositiveButton("Add selected") { dialog, _ ->
                val lv = (dialog as AlertDialog).listView
                for (i in found.indices) {
                    if (lv.isItemChecked(i)) {
                        val door = found[i]
                        doors.removeAll { it.id == door.id && it.type == door.type }
                        doors.add(door)
                    }
                }
                doors.sortBy { it.name.lowercase() }
                DoorStore.save(this, doors)
                adapter.notifyDataSetChanged()
                Toast.makeText(this, "Added; open the watch app to sync", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Raw log") { _, _ -> showDebugLog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDebugLog() {
        AlertDialog.Builder(this)
            .setTitle("Open API debug log")
            .setMessage(OpenCloud.debugLog.toString().take(20_000))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showUuidEditDialog() {
        val input = EditText(this).apply { setText(DoorStore.pebbleUuid(this@MainActivity)) }
        AlertDialog.Builder(this)
            .setTitle("Pebble watch app UUID")
            .setMessage("Must match the UUID of the watch app you sideloaded.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                DoorStore.setPebbleUuid(this, input.text.toString())
                refreshStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun log(msg: String) {
        android.util.Log.d("MainActivity", msg)
    }

    // ----------------------------------------------------------------- adapter

    private inner class DoorAdapter : BaseAdapter() {
        override fun getCount(): Int = doors.size
        override fun getItem(position: Int): Any = doors[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_door, parent, false)
            val door = doors[position]
            view.findViewById<TextView>(R.id.door_name).text = door.name
            view.findViewById<TextView>(R.id.door_detail).text =
                "${door.typeLabel} #${door.id} · key ${door.itemKey()}"
            return view
        }
    }
}
