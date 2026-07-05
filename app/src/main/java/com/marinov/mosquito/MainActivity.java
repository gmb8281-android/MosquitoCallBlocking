package com.marinov.mosquito;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.view.View;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------
    private RecyclerView                    recyclerView;
    private ContactsAdapter                 adapter;
    private View                            rootView;

    // -------------------------------------------------------------------------
    // Permissões em tempo de execução
    // -------------------------------------------------------------------------
    private static final String[] RUNTIME_PERMISSIONS = {
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
    };

    private ActivityResultLauncher<String[]> permissionsLauncher;
    private ActivityResultLauncher<Intent>   contactPickerLauncher;
    private ActivityResultLauncher<Intent>   batteryLauncher;
    private ActivityResultLauncher<Intent>   adminLauncher;
    private ActivityResultLauncher<Intent>   screeningLauncher;
    private ActivityResultLauncher<Intent>   overlayLauncher;

    // -------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Configura a Toolbar como ActionBar
        setSupportActionBar(findViewById(R.id.toolbar));

        // Configura o título colapsável
        CollapsingToolbarLayout collapsingToolbar = findViewById(R.id.collapsingToolbar);
        collapsingToolbar.setTitle(getString(R.string.app_name));

        rootView     = findViewById(android.R.id.content);
        recyclerView = findViewById(R.id.recyclerView);
        ExtendedFloatingActionButton fabAdd = findViewById(R.id.fabAddContact);

        setupRecyclerView();
        registerLaunchers();

        fabAdd.setOnClickListener(v -> openContactPicker());
        startPermissionFlow();
    }

    // -------------------------------------------------------------------------
    // RecyclerView
    // -------------------------------------------------------------------------

    private void setupRecyclerView() {
        List<ContactRule> rules = RulesManager.getRules(this);
        adapter = new ContactsAdapter(this, rules);
        adapter.setOnRemoveListener(this::onRemoveContact);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void onRemoveContact(int position, ContactRule rule) {
        adapter.removeAt(position);
        RulesManager.removeRule(this, rule.contactId);

        // Remove também os logs internos do contato (banco SQLite)
        DatabaseHelper db = new DatabaseHelper(this);
        db.deleteCallsForContact(rule.contactId, "incoming");
        db.deleteCallsForContact(rule.contactId, "outgoing");
        db.close();

        // Snackbar com desfazer
        String msg = getString(R.string.contact_removed, rule.contactName);
        Snackbar.make(rootView, msg, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo, v -> {
                    adapter.restoreRule(position, rule);
                    RulesManager.addRule(this, rule);
                })
                .show();
    }

    // -------------------------------------------------------------------------
    // Seleção de contato
    // -------------------------------------------------------------------------

    private void openContactPicker() {
        Intent intent = new Intent(Intent.ACTION_PICK,
                ContactsContract.Contacts.CONTENT_URI);
        contactPickerLauncher.launch(intent);
    }

    private void handleContactSelected(ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;

        Uri contactUri = result.getData().getData();
        if (contactUri == null) return;

        String contactId;
        String contactName;
        List<String> phones = new ArrayList<>();

        // Ler ID e nome
        Cursor c = getContentResolver().query(contactUri,
                new String[]{
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                }, null, null, null);

        if (c != null && c.moveToFirst()) {
            contactId   = c.getString(0);
            contactName = c.getString(1);
            c.close();
        } else {
            if (c != null) c.close();
            return;
        }

        // Ler números de telefone
        Cursor phoneCursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ ContactsContract.CommonDataKinds.Phone.NUMBER },
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                new String[]{ contactId },
                null);

        if (phoneCursor != null) {
            while (phoneCursor.moveToNext()) {
                String num = phoneCursor.getString(0);
                if (num != null && !num.isEmpty()) phones.add(num.trim());
            }
            phoneCursor.close();
        }

        if (phones.isEmpty()) {
            Snackbar.make(rootView,
                    getString(R.string.contact_no_phone), Snackbar.LENGTH_SHORT).show();
            return;
        }

        // Criar regra com valores padrão
        ContactRule rule = new ContactRule();
        rule.contactId    = contactId;
        rule.contactName  = contactName;
        rule.phoneNumbers = phones;
        rule.maxCalls     = 3;
        rule.penaltyMinutes = 60;

        RulesManager.addRule(this, rule);
        adapter.addRule(rule);
    }

    // -------------------------------------------------------------------------
    // Fluxo de permissões (sequencial)
    // -------------------------------------------------------------------------

    private void registerLaunchers() {
        permissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                grants -> checkAndRequestBatteryOptimization());

        contactPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::handleContactSelected);

        batteryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                r -> checkAndRequestScreeningRole());

        screeningLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                r -> checkAndRequestDeviceAdmin());

        adminLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                r -> checkAndRequestOverlayPermission());   // <-- próximo passo: overlay

        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                r -> { /* fim do fluxo */ });
    }

    private void startPermissionFlow() {
        boolean allGranted = true;
        for (String perm : RUNTIME_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.perm_title)
                    .setMessage(R.string.perm_message)
                    .setPositiveButton(R.string.ok, (d, w) ->
                            permissionsLauncher.launch(RUNTIME_PERMISSIONS))
                    .setCancelable(false)
                    .show();
        } else {
            checkAndRequestBatteryOptimization();
        }
    }

    private void checkAndRequestBatteryOptimization() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.battery_title)
                    .setMessage(R.string.battery_message)
                    .setPositiveButton(R.string.ok, (d, w) -> {
                        @SuppressLint("BatteryLife") Intent intent = new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()));
                        batteryLauncher.launch(intent);
                    })
                    .setNegativeButton(R.string.cancel, (d, w) ->
                            checkAndRequestScreeningRole())
                    .setCancelable(false)
                    .show();
        } else {
            checkAndRequestScreeningRole();
        }
    }

    private void checkAndRequestScreeningRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.app.role.RoleManager rm =
                    (android.app.role.RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (rm != null && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.screening_title)
                        .setMessage(R.string.screening_message)
                        .setPositiveButton(R.string.ok, (d, w) -> {
                            Intent intent = rm.createRequestRoleIntent(
                                    android.app.role.RoleManager.ROLE_CALL_SCREENING);
                            screeningLauncher.launch(intent);
                        })
                        .setNegativeButton(R.string.cancel, (d, w) ->
                                checkAndRequestDeviceAdmin())
                        .setCancelable(false)
                        .show();
                return;
            }
        }
        checkAndRequestDeviceAdmin();
    }

    private void checkAndRequestDeviceAdmin() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin =
                new ComponentName(this, DeviceAdminReceiverImpl.class);

        if (dpm != null && !dpm.isAdminActive(admin)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.admin_title)
                    .setMessage(R.string.admin_message)
                    .setPositiveButton(R.string.ok, (d, w) -> {
                        Intent intent = new Intent(
                                DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                        adminLauncher.launch(intent);
                    })
                    .setNegativeButton(R.string.cancel, (d, w) ->
                            checkAndRequestOverlayPermission())
                    .setCancelable(false)
                    .show();
        } else {
            checkAndRequestOverlayPermission();
        }
    }

    private void checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("Permissão de sobreposição")
                        .setMessage("Para mostrar o aviso de bloqueio quando uma chamada for efetuada, permita que o app exiba sobre outros aplicativos.")
                        .setPositiveButton(R.string.ok, (d, w) -> {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            overlayLauncher.launch(intent);
                        })
                        .setCancelable(false)
                        .show();
            }
            // Se já tiver permissão, não faz nada
        }
    }
}