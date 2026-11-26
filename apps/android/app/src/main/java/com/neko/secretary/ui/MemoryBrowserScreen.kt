package com.neko.secretary.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neko.secretary.perception.HubEndpointStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class MemoryItemUi(
    val id: String,
    val category: String,
    val title: String,
    val content: String,
    val tags: List<String>
)

data class ProspectiveItemUi(
    val id: String,
    val kind: String,
    val title: String,
    val priority: Int,
    val status: String
)

object MemoryHubClient {
    suspend fun fetchMemories(context: Context, query: String): Result<List<MemoryItemUi>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val hub = HubEndpointStore.load(context)
                require(hub.baseUrl.isNotBlank()) { "请先在控制台配置 Core Hub 地址" }
                val encodedQ = URLEncoder.encode(query.trim(), "UTF-8")
                val url = if (query.isBlank()) "${hub.baseUrl}/api/memories" else "${hub.baseUrl}/api/memories?q=$encodedQ"
                val client = HttpClient(OkHttp)
                try {
                    val resp = client.get(url) {
                        if (hub.bearerToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${hub.bearerToken}")
                    }
                    check(resp.status.isSuccess()) { "HTTP ${resp.status.value}" }
                    val arr = JSONObject(resp.bodyAsText()).optJSONArray("items") ?: JSONArray()
                    buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val tagsArr = o.optJSONArray("tags") ?: JSONArray()
                            val tags = (0 until tagsArr.length()).mapNotNull { tagsArr.optString(it).ifBlank { null } }
                            add(
                                MemoryItemUi(
                                    id = o.optString("id"),
                                    category = o.optString("category", "episodic"),
                                    title = o.optString("title", "").ifBlank { "无标题记忆" },
                                    content = o.optString("content", ""),
                                    tags = tags
                                )
                            )
                        }
                    }
                } finally {
                    client.close()
                }
            }
        }

    suspend fun updateMemoryContent(context: Context, id: String, newContent: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val hub = HubEndpointStore.load(context)
                val client = HttpClient(OkHttp)
                try {
                    val resp = client.patch("${hub.baseUrl}/api/memories/$id") {
                        contentType(ContentType.Application.Json)
                        if (hub.bearerToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${hub.bearerToken}")
                        setBody(JSONObject().put("content", newContent).toString())
                    }
                    check(resp.status.isSuccess()) { "HTTP ${resp.status.value}" }
                } finally {
                    client.close()
                }
            }
        }

    suspend fun softDeleteMemory(context: Context, id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val hub = HubEndpointStore.load(context)
                val client = HttpClient(OkHttp)
                try {
                    val resp = client.delete("${hub.baseUrl}/api/memories/$id") {
                        if (hub.bearerToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${hub.bearerToken}")
                    }
                    check(resp.status.isSuccess()) { "HTTP ${resp.status.value}" }
                } finally {
                    client.close()
                }
            }
        }

    suspend fun fetchProspective(context: Context): Result<List<ProspectiveItemUi>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val hub = HubEndpointStore.load(context)
                require(hub.baseUrl.isNotBlank()) { "请先在控制台配置 Core Hub 地址" }
                val client = HttpClient(OkHttp)
                try {
                    val resp = client.get("${hub.baseUrl}/api/prospective") {
                        if (hub.bearerToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${hub.bearerToken}")
                    }
                    check(resp.status.isSuccess()) { "HTTP ${resp.status.value}" }
                    val arr = JSONObject(resp.bodyAsText()).optJSONArray("items") ?: JSONArray()
                    buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            add(
                                ProspectiveItemUi(
                                    id = o.optString("id"),
                                    kind = o.optString("kind", "deadline"),
                                    title = o.optString("title", ""),
                                    priority = o.optInt("priority", 50),
                                    status = o.optString("status", "active")
                                )
                            )
                        }
                    }
                } finally {
                    client.close()
                }
            }
        }

    suspend fun markProspectiveDone(context: Context, id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val hub = HubEndpointStore.load(context)
                val client = HttpClient(OkHttp)
                try {
                    val resp = client.patch("${hub.baseUrl}/api/prospective/$id") {
                        contentType(ContentType.Application.Json)
                        if (hub.bearerToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${hub.bearerToken}")
                        setBody(JSONObject().put("status", "done").toString())
                    }
                    check(resp.status.isSuccess()) { "HTTP ${resp.status.value}" }
                } finally {
                    client.close()
                }
            }
        }
}

/**
 * Week 2 Day 13: Six-Dimensional Memory & Prospective Items Browser (FTS5 search, edit, soft-delete).
 */
@Composable
fun MemoryBrowserSection(context: Context) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var memories by remember { mutableStateOf<List<MemoryItemUi>>(emptyList()) }
    var prospective by remember { mutableStateOf<List<ProspectiveItemUi>>(emptyList()) }
    var statusText by remember { mutableStateOf("点击刷新以从 Core Hub 加载六维记忆与死线") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }

    fun refreshAll() {
        scope.launch {
            statusText = "正在查询 Core Hub FTS5…"
            val memRes = MemoryHubClient.fetchMemories(context, query)
            val proRes = MemoryHubClient.fetchProspective(context)
            memRes.onSuccess { memories = it }
            proRes.onSuccess { prospective = it }
            statusText = memRes.exceptionOrNull()?.message
                ?: "已加载 ${memories.size} 条记忆 · ${prospective.size} 条前瞻事项"
        }
    }

    LaunchedEffect(Unit) { refreshAll() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("六维记忆 & 前瞻死线库 (FTS5)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("FTS5 关键词检索") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { refreshAll() }) { Text("搜索") }
        }
        Text(statusText, style = MaterialTheme.typography.bodySmall)

        if (prospective.isNotEmpty()) {
            Text("活跃前瞻事项 / 死线", style = MaterialTheme.typography.titleSmall)
            prospective.forEach { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("[${item.kind}] ${item.title}", style = MaterialTheme.typography.bodyMedium)
                            Text("优先级: ${item.priority}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                MemoryHubClient.markProspectiveDone(context, item.id)
                                refreshAll()
                            }
                        }) { Text("完成") }
                    }
                }
            }
        }

        Text("长期记忆列表", style = MaterialTheme.typography.titleSmall)
        memories.forEach { mem ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("[${mem.category}] ${mem.title}", style = MaterialTheme.typography.titleSmall)
                    if (editingId == mem.id) {
                        OutlinedTextField(
                            value = editingText,
                            onValueChange = { editingText = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    MemoryHubClient.updateMemoryContent(context, mem.id, editingText)
                                    editingId = null
                                    refreshAll()
                                }
                            }) { Text("保存") }
                            OutlinedButton(onClick = { editingId = null }) { Text("取消") }
                        }
                    } else {
                        Text(mem.content, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                editingId = mem.id
                                editingText = mem.content
                            }) { Text("编辑") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    MemoryHubClient.softDeleteMemory(context, mem.id)
                                    refreshAll()
                                }
                            }) { Text("软删除") }
                        }
                    }
                }
            }
        }
    }
}
