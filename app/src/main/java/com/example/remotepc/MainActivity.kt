package com.example.remotepc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.remotepc.ui.theme.RemotePcTheme
import com.freerdp.freerdpcore.domain.BookmarkBase
import com.freerdp.freerdpcore.presentation.SessionActivity
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemotePcTheme { RemotePcApp(applicationContext) }
        }
    }
}

private data class RemoteHost(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val port: Int = 3389,
    val username: String,
    val password: String,
    val colorDepth: Int = 32,
    val desktopWidth: Int = 0,
    val desktopHeight: Int = 0,
)

private sealed interface Destination {
    data object Hosts : Destination
    data class Edit(val host: RemoteHost?) : Destination
    data class Session(val host: RemoteHost) : Destination
}

@Composable
private fun RemotePcApp(context: Context) {
    val store = remember { RemoteHostStore(context) }
    val hosts = remember { mutableStateListOf<RemoteHost>() }
    var destination by remember { mutableStateOf<Destination>(Destination.Hosts) }
    var hostToDelete by remember { mutableStateOf<RemoteHost?>(null) }

    LaunchedEffect(store) { hosts.addAll(store.readAll()) }

    when (val screen = destination) {
        Destination.Hosts -> HostListScreen(
            hosts = hosts,
            onAdd = { destination = Destination.Edit(null) },
            onConnect = { destination = Destination.Session(it) },
            onEdit = { destination = Destination.Edit(it) },
            onDelete = { hostToDelete = it },
        )
        is Destination.Edit -> HostEditorScreen(
            host = screen.host,
            onBack = { destination = Destination.Hosts },
            onSave = { saved ->
                val oldIndex = hosts.indexOfFirst { it.id == saved.id }
                if (oldIndex >= 0) hosts[oldIndex] = saved else hosts.add(saved)
                store.save(saved)
                destination = Destination.Hosts
            },
        )
        is Destination.Session -> SessionScreen(
            host = screen.host,
            onClose = { destination = Destination.Hosts },
        )
    }

    hostToDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { hostToDelete = null },
            title = { Text("Xoá máy đã lưu?") },
            text = { Text("Thông tin đăng nhập của ${host.name} cũng sẽ bị xoá khỏi thiết bị này.") },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(host.id)
                    hosts.remove(host)
                    hostToDelete = null
                }) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { hostToDelete = null }) { Text("Huỷ") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostListScreen(
    hosts: List<RemoteHost>,
    onAdd: () -> Unit,
    onConnect: (RemoteHost) -> Unit,
    onEdit: (RemoteHost) -> Unit,
    onDelete: (RemoteHost) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Remote PC", fontWeight = FontWeight.SemiBold) },
                actions = { TextButton(onClick = onAdd) { Text("Thêm máy") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            EmptyHosts(modifier = Modifier.padding(padding), onAdd = onAdd)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(hosts, key = { it.id }) { host ->
                    HostCard(host, onConnect = { onConnect(host) }, onEdit = { onEdit(host) }, onDelete = { onDelete(host) })
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyHosts(modifier: Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(78.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("▣", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(20.dp))
        Text("Chưa có máy nào", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Thêm địa chỉ Tailscale và tài khoản GNOME Remote Desktop của Ubuntu để bắt đầu.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        Button(onClick = onAdd) { Text("Thêm máy Ubuntu") }
    }
}

@Composable
private fun HostCard(host: RemoteHost, onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("PC", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(host.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${host.address}:${host.port}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, modifier = Modifier.weight(1f)) { Text("Kết nối") }
                OutlinedButton(onClick = onEdit) { Text("Sửa") }
                TextButton(onClick = onDelete) { Text("Xoá") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEditorScreen(host: RemoteHost?, onBack: () -> Unit, onSave: (RemoteHost) -> Unit) {
    var name by remember(host) { mutableStateOf(host?.name.orEmpty()) }
    var address by remember(host) { mutableStateOf(host?.address.orEmpty()) }
    var port by remember(host) { mutableStateOf(host?.port?.toString() ?: "3389") }
    var username by remember(host) { mutableStateOf(host?.username.orEmpty()) }
    var password by remember(host) { mutableStateOf(host?.password.orEmpty()) }
    var colorDepth by remember(host) { mutableStateOf(host?.colorDepth ?: 32) }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = { Text(if (host == null) "Thêm máy Ubuntu" else "Sửa máy") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Huỷ") } },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            item { Text("Kết nối RDP qua Tailscale", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item { Text("Dùng IP 100.x.x.x hoặc MagicDNS của Ubuntu. Tailscale phải đang được bật trên điện thoại.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { EditorField("Tên hiển thị", name, { name = it }, "Ví dụ: Ubuntu ở nhà") }
            item { EditorField("Địa chỉ", address, { address = it }, "100.x.x.x hoặc ubuntu.tailnet.ts.net") }
            item { EditorField("Cổng RDP", port, { port = it }, "3389", KeyboardOptions(keyboardType = KeyboardType.Number)) }
            item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
            item { Text("Đăng nhập", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item { EditorField("Tên người dùng", username, { username = it }, "Tài khoản trong GNOME Remote Desktop") }
            item {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Mật khẩu") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) "Ẩn" else "Hiện") } },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
            item { Text("Chất lượng", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(16 to "Tiết kiệm", 24 to "Cân bằng", 32 to "Nét").forEach { (depth, label) ->
                        if (depth == colorDepth) Button(onClick = { colorDepth = depth }) { Text(label) }
                        else OutlinedButton(onClick = { colorDepth = depth }) { Text(label) }
                    }
                }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val parsedPort = port.toIntOrNull()
                        error = when {
                            name.isBlank() -> "Hãy nhập tên hiển thị."
                            address.trim().isBlank() -> "Hãy nhập địa chỉ Tailscale."
                            parsedPort == null || parsedPort !in 1..65535 -> "Cổng phải nằm trong khoảng 1–65535."
                            username.isBlank() -> "Hãy nhập tên người dùng RDP."
                            password.isBlank() -> "Hãy nhập mật khẩu RDP."
                            else -> null
                        }
                        if (error == null) onSave(RemoteHost(host?.id ?: UUID.randomUUID().toString(), name.trim(), address.trim(), parsedPort!!, username.trim(), password, colorDepth))
                    },
                ) { Text(if (host == null) "Lưu máy" else "Lưu thay đổi") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun EditorField(label: String, value: String, onChange: (String) -> Unit, hint: String, keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, placeholder = { Text(hint) }, keyboardOptions = keyboardOptions,
    )
}

@Composable
private fun SessionScreen(host: RemoteHost, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(host.id) {
        context.startActivity(freeRdpIntent(context, host))
        onClose()
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF111318)),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator() }
}

/** Opens FreeRDP's maintained Android session activity using an explicit in-app intent. */
private fun freeRdpIntent(context: Context, host: RemoteHost): Intent {
    val bookmark = BookmarkBase().apply {
        setLabel(host.name)
        setHostname(host.address)
        setPort(host.port)
        setUsername(host.username)
        setPassword(host.password)
        activeScreenSettings.setColors(host.colorDepth)
    }
    return Intent(context, SessionActivity::class.java)
        .putExtra(SessionActivity.PARAM_BOOKMARK, bookmark)
}

/** Stores every host record (including the password) encrypted with a non-exportable Android Keystore key. */
private class RemoteHostStore(context: Context) {
    private val preferences = context.getSharedPreferences("remote_hosts", Context.MODE_PRIVATE)
    private val crypto = KeystoreCrypto()

    fun readAll(): List<RemoteHost> = preferences.getStringSet("ids", emptySet()).orEmpty().mapNotNull { id ->
        runCatching { preferences.getString("host_$id", null)?.let(crypto::decrypt)?.let(::hostFromJson) }.getOrNull()
    }.sortedBy { it.name.lowercase() }

    fun save(host: RemoteHost) {
        val ids = preferences.getStringSet("ids", emptySet()).orEmpty().toMutableSet().apply { add(host.id) }
        preferences.edit().putStringSet("ids", ids).putString("host_${host.id}", crypto.encrypt(hostToJson(host))).apply()
    }

    fun delete(id: String) {
        val ids = preferences.getStringSet("ids", emptySet()).orEmpty().toMutableSet().apply { remove(id) }
        preferences.edit().putStringSet("ids", ids).remove("host_$id").apply()
    }

    private fun hostToJson(host: RemoteHost): String = JSONObject().apply {
        put("id", host.id); put("name", host.name); put("address", host.address); put("port", host.port)
        put("username", host.username); put("password", host.password); put("colorDepth", host.colorDepth)
        put("desktopWidth", host.desktopWidth); put("desktopHeight", host.desktopHeight)
    }.toString()

    private fun hostFromJson(json: String): RemoteHost = JSONObject(json).let {
        RemoteHost(it.getString("id"), it.getString("name"), it.getString("address"), it.optInt("port", 3389), it.getString("username"), it.getString("password"), it.optInt("colorDepth", 32), it.optInt("desktopWidth"), it.optInt("desktopHeight"))
    }
}

private class KeystoreCrypto {
    private val alias = "remote_pc_hosts_v1"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val payload = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2) { "Invalid encrypted host record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
}
