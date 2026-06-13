# Chat History Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add missing chat-history management actions to the existing sidebar: create new chat, rename a thread, delete a thread, show last-message preview + updated time, and search/filter threads.

**Architecture:** Introduce a small `chat_sessions` Room table to store editable thread titles and timestamps. Keep messages in `chat_messages`. The sidebar becomes the history-management surface, backed by `ChatViewModel` which aggregates session metadata with the last message of each session.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Kotlin Coroutines/Flow, Material3.

---

## File Structure

| File | Responsibility |
|------|----------------|
| `app/src/main/java/com/mamba/picme/data/local/ChatSessionEntity.kt` | Room entity for session metadata (id, title, timestamps). |
| `app/src/main/java/com/mamba/picme/data/local/ChatSessionDao.kt` | DAO for session CRUD. |
| `app/src/main/java/com/mamba/picme/data/local/ChatMessageDao.kt` | Add `getLastMessageForSession`. |
| `app/src/main/java/com/mamba/picme/data/local/ChatDatabaseMigrations.kt` | `Migration(4, 5)` creating `chat_sessions` and back-filling from existing messages. |
| `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt` | Add `ChatSessionEntity`, bump version to 5, register migration. |
| `app/src/main/java/com/mamba/picme/di/AppContainer.kt` | Provide `ChatSessionDao` to `ChatViewModelDependencies`. |
| `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt` | Add `chatSessionDao` field. |
| `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt` | Load threads from sessions + last message; add `newSession`, `renameSession`, `deleteSession`, `searchQuery`. |
| `app/src/main/java/com/mamba/picme/features/chat/ChatThreadSidebar.kt` | Add search field, New Chat button, per-thread overflow menu (rename/delete), dialogs. |
| `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt` | Wire new sidebar callbacks. |
| `app/src/main/res/values/strings.xml` | New chat / rename / delete / search strings. |
| `app/src/androidTest/java/com/mamba/picme/data/local/ChatSessionDaoTest.kt` | Instrumented DAO tests. |

---

## Assumptions

- The existing sidebar (`ChatThreadSidebar`) is the current "Chat History page". We enhance it rather than adding a new route, because there is no `Screen.ChatHistory` route today and the user referred to the history page being empty of functionality.
- `AppDatabase` currently uses `fallbackToDestructiveMigration()`. We will replace that with an explicit `Migration(4, 5)` so existing messages survive the schema change.
- Thread titles default to `"New Chat"` for the legacy `"default"` session and `"Chat {shortUuid}"` for new sessions.

---

### Task 1: Create `ChatSessionEntity` and `ChatSessionDao`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/local/ChatSessionEntity.kt`
- Create: `app/src/main/java/com/mamba/picme/data/local/ChatSessionDao.kt`

- [ ] **Step 1.1: Create the session entity**

```kotlin
package com.mamba.picme.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity：聊天会话元数据
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val sessionId: String,

    /**
     * 用户可编辑的会话标题
     */
    val title: String,

    /**
     * 创建时间
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * 最后更新时间（用于排序）
     */
    val updatedAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 1.2: Create the session DAO**

```kotlin
package com.mamba.picme.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO：聊天会话元数据
 */
@Dao
interface ChatSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :updatedAt WHERE sessionId = :sessionId")
    suspend fun updateTitle(
        sessionId: String,
        title: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("SELECT * FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>
}
```

- [ ] **Step 1.3: Compile to verify new files**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 2: Extend `ChatMessageDao` with last-message lookup

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/local/ChatMessageDao.kt`

- [ ] **Step 2.1: Add last-message query**

Add these two methods to `ChatMessageDao`:

```kotlin
    /**
     * 获取指定会话的最后一条消息
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageForSession(sessionId: String): ChatMessageEntity?
```

- [ ] **Step 2.2: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 3: Write Room migration 4 → 5

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/local/ChatDatabaseMigrations.kt`

- [ ] **Step 3.1: Create migration object**

```kotlin
package com.mamba.picme.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ChatDatabaseMigrations {

    /**
     * 4 → 5：新增 chat_sessions 表，并从已有消息反填充会话标题和时间戳
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_sessions (
                    sessionId TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // 用已有 chat_messages 中的 sessionId 初始化 chat_sessions
            db.execSQL(
                """
                INSERT OR IGNORE INTO chat_sessions (sessionId, title, createdAt, updatedAt)
                SELECT DISTINCT sessionId, sessionId, MIN(timestamp), MAX(timestamp)
                FROM chat_messages
                GROUP BY sessionId
                """.trimIndent()
            )
        }
    }
}
```

- [ ] **Step 3.2: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 4: Register the new entity and migration in `AppDatabase`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`

- [ ] **Step 4.1: Update database definition**

```kotlin
package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mamba.picme.data.model.MediaEntity

@Database(
    entities = [MediaEntity::class, ChatMessageEntity::class, ChatSessionEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatSessionDao(): ChatSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "picme_database"
                )
                    .addMigrations(ChatDatabaseMigrations.MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

Note: The explicit `MIGRATION_4_5` preserves version-4 user data. `fallbackToDestructiveMigration()` is kept as a safety net for any older version paths without an explicit migration, preventing app crashes on launch.

- [ ] **Step 4.2: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 5: Provide `ChatSessionDao` to the ViewModel

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt`
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`

- [ ] **Step 5.1: Add DAO to dependencies class**

```kotlin
package com.mamba.picme.features.chat

import android.content.Context
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao

class ChatViewModelDependencies(
    val context: Context,
    val chatMessageDao: ChatMessageDao,
    val chatSessionDao: ChatSessionDao
)
```

- [ ] **Step 5.2: Wire it in `AppContainer`**

In `AppContainer.kt`, update `chatViewModelDependencies`:

```kotlin
    private val chatViewModelDependencies: ChatViewModelDependencies by lazy {
        ChatViewModelDependencies(
            context = context,
            chatMessageDao = database.chatMessageDao(),
            chatSessionDao = database.chatSessionDao()
        )
    }
```

- [ ] **Step 5.3: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 6: Refactor `ChatViewModel` to manage sessions

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`

- [ ] **Step 6.1: Update constructor signature and fields**

Change the constructor to accept `ChatViewModelDependencies` so it can access both DAOs:

```kotlin
class ChatViewModel(
    dependencies: ChatViewModelDependencies
) : ViewModel() {

    private val context = dependencies.context.applicationContext
    private val chatMessageDao = dependencies.chatMessageDao
    private val chatSessionDao = dependencies.chatSessionDao

    private val orchestrator = AgentOrchestrator.getInstance(context)
    // ... existing StateFlows
```

Also add a search query state:

```kotlin
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
```

- [ ] **Step 6.2: Replace `loadThreads()` with session-aware loading**

```kotlin
    private fun loadThreads() {
        viewModelScope.launch {
            try {
                chatSessionDao.getAllSessions()
                    .collect { sessions ->
                        val threads = sessions.map { session ->
                            val lastMessage = chatMessageDao.getLastMessageForSession(session.sessionId)
                            ChatThreadUi(
                                sessionId = session.sessionId,
                                title = resolveThreadTitle(session),
                                lastMessagePreview = lastMessage?.content?.take(60) ?: "",
                                updatedAt = session.updatedAt,
                                isSelected = session.sessionId == _currentSessionId.value
                            )
                        }
                        _threads.value = threads
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load threads", e)
            }
        }
    }

    private fun resolveThreadTitle(session: ChatSessionEntity): String {
        return when {
            session.sessionId == "default" && session.title == "default" -> "New Chat"
            session.title.isBlank() -> "Chat"
            else -> session.title
        }
    }
```

- [ ] **Step 6.3: Add session-management methods**

```kotlin
    /**
     * 创建新会话并切换过去
     */
    fun newSession() {
        val sessionId = UUID.randomUUID().toString()
        viewModelScope.launch {
            try {
                chatSessionDao.insertSession(
                    ChatSessionEntity(
                        sessionId = sessionId,
                        title = "New Chat"
                    )
                )
                _currentSessionId.value = sessionId
                Logger.i(TAG, "Created new session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to create session", e)
            }
        }
    }

    /**
     * 重命名会话
     */
    fun renameSession(sessionId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            try {
                chatSessionDao.updateTitle(sessionId, newTitle.trim())
                Logger.i(TAG, "Renamed session $sessionId to: $newTitle")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to rename session", e)
            }
        }
    }

    /**
     * 删除会话及其消息；如果删除的是当前会话，切回 default
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                chatMessageDao.deleteAllMessagesBySession(sessionId)
                chatSessionDao.deleteSession(sessionId)
                if (_currentSessionId.value == sessionId) {
                    _currentSessionId.value = "default"
                }
                Logger.i(TAG, "Deleted session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete session", e)
            }
        }
    }

    /**
     * 更新搜索关键字（在内存中过滤线程列表）
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * 过滤后的线程列表
     */
    val filteredThreads: StateFlow<List<ChatThreadUi>> = combine(
        _threads,
        _searchQuery
    ) { threads, query ->
        if (query.isBlank()) threads
        else threads.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.lastMessagePreview.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

- [ ] **Step 6.4: Ensure a session row exists before saving messages**

When sending the first message in a never-seen session, the session row might not exist. Update `sendMessage` after building `sessionId`:

```kotlin
                // 0. 确保会话元数据存在（兼容旧数据或 default）
                ensureSessionExists(sessionId)
```

And add:

```kotlin
    private suspend fun ensureSessionExists(sessionId: String) {
        val existing = chatSessionDao.getSession(sessionId)
        if (existing == null) {
            chatSessionDao.insertSession(
                ChatSessionEntity(
                    sessionId = sessionId,
                    title = if (sessionId == "default") "New Chat" else "Chat"
                )
            )
        }
    }
```

- [ ] **Step 6.5: Update `clearChat()` to reset session title**

After clearing, set the title back to `"New Chat"`:

```kotlin
    fun clearChat() {
        viewModelScope.launch {
            try {
                val sessionId = _currentSessionId.value
                chatMessageDao.deleteAllMessagesBySession(sessionId)
                chatSessionDao.updateTitle(sessionId, "New Chat")
                _messages.value = emptyList()
                Logger.i(TAG, "Chat cleared for session: $sessionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to clear chat", e)
            }
        }
    }
```

- [ ] **Step 6.6: Update `ChatThreadUi` to include `updatedAt`**

Change `ChatThreadUi` in `ChatThreadSidebar.kt` (or move it to a standalone file) to:

```kotlin
data class ChatThreadUi(
    val sessionId: String,
    val title: String,
    val lastMessagePreview: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val isSelected: Boolean = false
)
```

- [ ] **Step 6.7: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 7: Add management UI to `ChatThreadSidebar`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatThreadSidebar.kt`

- [ ] **Step 7.1: Update sidebar signature and add search + new chat header**

Replace the current `ChatThreadSidebar` signature with:

```kotlin
@Composable
fun ChatThreadSidebar(
    visible: Boolean,
    threads: List<ChatThreadUi>,
    currentSessionId: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onThreadSelected: (String) -> Unit,
    onNewChat: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
)
```

Add imports:

```kotlin
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
```

Replace the `Column` content inside `Surface` with:

```kotlin
            Column {
                // 标题栏 + New Chat
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.sidebar_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row {
                        IconButton(onClick = onNewChat) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.new_chat)
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close)
                            )
                        }
                    }
                }

                // 搜索框
                SearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 线程列表
                LazyColumn {
                    items(threads, key = { it.sessionId }) { thread ->
                        ChatThreadItem(
                            thread = thread,
                            isSelected = thread.sessionId == currentSessionId,
                            onClick = { onThreadSelected(thread.sessionId) },
                            onRename = { onRename(thread.sessionId, thread.title) },
                            onDelete = { onDelete(thread.sessionId) }
                        )
                    }
                }
            }
```

- [ ] **Step 7.2: Add `SearchField` composable**

Add this composable in the same file:

```kotlin
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(end = 8.dp)
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            ),
            decorationBox = { innerTextField ->
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_history),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        fontSize = 14.sp
                    )
                }
                innerTextField()
            },
            modifier = Modifier.weight(1f)
        )
    }
}
```

- [ ] **Step 7.3: Update `ChatThreadItem` with overflow menu and dialogs**

Replace `ChatThreadItem` with:

```kotlin
@Composable
private fun ChatThreadItem(
    thread: ChatThreadUi,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        RenameDialog(
            currentTitle = thread.title,
            onConfirm = { newTitle ->
                onRename()
                // Note: the actual rename is handled by the ViewModel using thread.sessionId.
                // This dialog receives the title via the caller; see Step 7.4 for the wrapper.
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_chat_title)) },
            text = { Text(stringResource(R.string.delete_chat_confirm, thread.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.title,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            if (thread.lastMessagePreview.isNotBlank()) {
                Text(
                    text = thread.lastMessagePreview,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.chat_options),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename_chat)) },
                    onClick = {
                        showMenu = false
                        showRenameDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete_chat)) },
                    onClick = {
                        showMenu = false
                        showDeleteDialog = true
                    }
                )
            }
        }
    }
}
```

- [ ] **Step 7.4: Add `RenameDialog`**

```kotlin
@Composable
private fun RenameDialog(
    currentTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(currentTitle) { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_chat)) },
        text = {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.trim().isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
```

Note: The `onRename` lambda in Step 7.3 does not pass the new title. The caller in `ChatScreen` will capture the current thread title; when the dialog closes with a new title, `ChatScreen` will call `viewModel.renameSession(sessionId, newTitle)`. To make this cleaner, change `ChatThreadItem` to receive `onRenameRequest: () -> Unit` and let `ChatScreen` show its own dialog, but that complicates state. A simpler implementation: change `onRename` signature to `(String) -> Unit` and pass the new title from `RenameDialog`:

```kotlin
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename_chat)) },
                    onClick = {
                        showMenu = false
                        showRenameDialog = true
                    }
                )
```

And `RenameDialog(onConfirm = { newTitle -> onRename(newTitle); showRenameDialog = false })`.

Adjust `ChatThreadItem` signature to `onRename: (String) -> Unit` and `ChatThreadSidebar` to `onRename: (String, String) -> Unit` where the first string is `sessionId` and the second is the new title. In the `items` block:

```kotlin
                        onRename = { newTitle -> onRename(thread.sessionId, newTitle) },
```

- [ ] **Step 7.5: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 8: Wire the sidebar in `ChatScreen`

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 8.1: Observe new states and pass callbacks**

In `ChatScreen`, observe:

```kotlin
    val threads by viewModel.filteredThreads.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
```

Then update `ChatThreadSidebar` invocation:

```kotlin
            ChatThreadSidebar(
                visible = isSidebarOpen,
                threads = threads,
                currentSessionId = currentSessionId,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                onThreadSelected = { sessionId ->
                    viewModel.switchSession(sessionId)
                    isSidebarOpen = false
                },
                onNewChat = {
                    viewModel.newSession()
                    isSidebarOpen = false
                },
                onRename = { sessionId, newTitle ->
                    viewModel.renameSession(sessionId, newTitle)
                },
                onDelete = { sessionId ->
                    viewModel.deleteSession(sessionId)
                },
                onDismiss = { isSidebarOpen = false }
            )
```

- [ ] **Step 8.2: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 9: Add strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 9.1: Append new strings**

```xml
    <string name="new_chat">New Chat</string>
    <string name="search_history">Search history</string>
    <string name="chat_options">Chat options</string>
    <string name="rename_chat">Rename</string>
    <string name="delete_chat">Delete chat</string>
    <string name="delete_chat_title">Delete Chat</string>
    <string name="delete_chat_confirm">Delete "%1$s"? This cannot be undone.</string>
    <string name="save">Save</string>
    <string name="cancel">Cancel</string>
```

`cancel` and `delete` already exist; include them only if missing.

- [ ] **Step 9.2: Sync project / compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

---

### Task 10: Write instrumented DAO tests

**Files:**
- Create: `app/src/androidTest/java/com/mamba/picme/data/local/ChatSessionDaoTest.kt`

- [ ] **Step 10.1: Create the test**

```kotlin
package com.mamba.picme.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatSessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var sessionDao: ChatSessionDao
    private lateinit var messageDao: ChatMessageDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(ChatDatabaseMigrations.MIGRATION_4_5)
            .build()
        sessionDao = db.chatSessionDao()
        messageDao = db.chatMessageDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndRetrieveSession() = runBlocking {
        val session = ChatSessionEntity(sessionId = "s1", title = "Test Chat")
        sessionDao.insertSession(session)

        val loaded = sessionDao.getSession("s1")
        assertEquals("Test Chat", loaded?.title)
    }

    @Test
    fun updateTitleReflectedInFlow() = runBlocking {
        sessionDao.insertSession(ChatSessionEntity(sessionId = "s1", title = "Old"))
        sessionDao.updateTitle("s1", "New")

        val sessions = sessionDao.getAllSessions().first()
        assertEquals("New", sessions.first().title)
    }

    @Test
    fun deleteSessionRemovesOnlyTargetSession() = runBlocking {
        sessionDao.insertSession(ChatSessionEntity(sessionId = "s1", title = "A"))
        sessionDao.insertSession(ChatSessionEntity(sessionId = "s2", title = "B"))
        messageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = "s1",
                type = "user_text",
                content = "hello"
            )
        )

        messageDao.deleteAllMessagesBySession("s1")
        sessionDao.deleteSession("s1")

        assertNull(sessionDao.getSession("s1"))
        assertEquals("B", sessionDao.getSession("s2")?.title)
    }

    @Test
    fun getLastMessageForSessionReturnsMostRecent() = runBlocking {
        val sessionId = "s1"
        messageDao.insertMessage(
            ChatMessageEntity(
                id = "m1",
                sessionId = sessionId,
                type = "user_text",
                content = "first",
                timestamp = 1000
            )
        )
        messageDao.insertMessage(
            ChatMessageEntity(
                id = "m2",
                sessionId = sessionId,
                type = "agent_text",
                content = "last",
                timestamp = 2000
            )
        )

        val last = messageDao.getLastMessageForSession(sessionId)
        assertEquals("last", last?.content)
    }
}
```

- [ ] **Step 10.2: Run tests**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.mamba.picme.data.local.ChatSessionDaoTest" --no-daemon`
Expected: All 4 tests PASS.

---

### Task 11: Final compile and manual smoke test

- [ ] **Step 11.1: Full compile**

Run: `./gradlew :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 11.2: Manual smoke test checklist**

1. Install debug APK.
2. Open sidebar (top-left menu or swipe).
3. Tap **+** → a new session is created and the UI switches to it.
4. Send a message → the thread shows the last-message preview.
5. Long-press/overflow → **Rename** → title updates.
6. Overflow → **Delete** → confirmation dialog appears; after confirm, thread is removed.
7. Type in search box → threads filter by title or last-message preview.
8. Kill and reopen the app → sessions, titles, and messages persist.

---

## Self-Review

| Requirement | Task |
|-------------|------|
| New chat | Task 6.3 + Task 7.1 |
| Rename thread | Task 6.3 + Task 7.4 |
| Delete thread | Task 6.3 + Task 7.3 |
| Last-message preview | Task 6.2 |
| Search/filter | Task 6.3 + Task 7.1 |
| Persist across launches | Tasks 1–4 |
| No placeholders | All steps contain exact file paths and code |

No placeholders found. Type consistency: `ChatThreadUi.updatedAt` added in Task 6.6 and used in Task 6.2. `onRename` signature resolved in Task 7.4/7.5.
