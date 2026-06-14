
package com.openrouter.chat

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val dao = db.chatDao()
    private val gson = Gson()
    private val apiService = OpenRouterService(gson)
    private val prefs = application.getSharedPreferences("open_router_prefs", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _isKeyAuthorized = MutableStateFlow(false)
    val isKeyAuthorized: StateFlow<Boolean> = _isKeyAuthorized.asStateFlow()

    val availablePricing: StateFlow<List<ModelPricing>> = dao.getAllPricing()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedModel = MutableStateFlow(prefs.getString("selected_model", "google/gemini-2.5-pro") ?: "google/gemini-2.5-pro")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showArchived = MutableStateFlow(false)
    val showArchived: StateFlow<Boolean> = _showArchived.asStateFlow()

    val chatSessions: StateFlow<List<ChatSession>> = combine(
        _searchQuery, _showArchived
    ) { query, showArchived ->
        Pair(query, showArchived)
    }.flatMapLatest { (query, archived) ->
        if (query.isEmpty()) {
            if (archived) dao.getArchivedSessions() else dao.getActiveSessions()
        } else {
            val matchTitle = "%$query%"
            combine(
                dao.searchSessions(matchTitle),
                dao.searchSessionsByMessageContent(matchTitle)
            ) { listA, listB ->
                (listA + listB).distinctBy { it.id }.filter { it.isArchived == archived }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentSession = MutableStateFlow<ChatSession?>(null)
    val currentSession: StateFlow<ChatSession?> = _currentSession.asStateFlow()

    private val _currentMessages = MutableStateFlow<List<MessageWithVersions>>(emptyList())
    val currentMessages: StateFlow<List<MessageWithVersions>> = _currentMessages.asStateFlow()

    val apiLogs: StateFlow<List<ApiLog>> = dao.getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _bgOverrideUri = MutableStateFlow(prefs.getString("bg_override_uri", "") ?: "")
    val bgOverrideUri: StateFlow<String> = _bgOverrideUri.asStateFlow()

    private val _soundOverrideUri = MutableStateFlow(prefs.getString("sound_override_uri", "") ?: "")
    val soundOverrideUri: StateFlow<String> = _soundOverrideUri.asStateFlow()

    init {
        checkAndSyncPricing(force = false)
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val encrypted = SecurityManager.encrypt(key)
            prefs.edit().putString("encrypted_api_key", encrypted).apply()
            _apiKey.value = key
            _isKeyAuthorized.value = true
        }
    }

    fun authorizeWithBiometrics() {
        viewModelScope.launch(Dispatchers.IO) {
            val encrypted = prefs.getString("encrypted_api_key", "") ?: ""
            if (encrypted.isNotEmpty()) {
                val decrypted = SecurityManager.decrypt(encrypted)
                _apiKey.value = decrypted
                _isKeyAuthorized.value = true
            } else {
                _apiKey.value = ""
                _isKeyAuthorized.value = true
            }
        }
    }

    fun logout() {
        _apiKey.value = ""
        _isKeyAuthorized.value = false
    }

    fun checkAndSyncPricing(force: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val lastSync = prefs.getLong("last_pricing_sync", 0L)
            val current = System.currentTimeMillis()
            val twentyFourHours = 24 * 60 * 60 * 1000L
            if (force || (current - lastSync > twentyFourHours)) {
                try {
                    val prices = apiService.fetchModels()
                    if (prices.isNotEmpty()) {
                        dao.insertPricing(prices)
                        prefs.edit().putLong("last_pricing_sync", current).apply()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun selectModel(modelId: String) {
        _selectedModel.value = modelId
        prefs.edit().putString("selected_model", modelId).apply()
    }

    fun createSession(title: String, systemPrompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = ChatSession(title = title, systemPrompt = systemPrompt)
            val id = dao.insertSession(session)
            val created = session.copy(id = id)
            _currentSession.value = created
            observeSessionMessages(id)
        }
    }

    fun selectSession(session: ChatSession?) {
        _currentSession.value = session
        if (session != null) {
            observeSessionMessages(session.id)
        } else {
            _currentMessages.value = emptyList()
        }
    }

    fun toggleArchiveSession(session: ChatSession) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = session.copy(isArchived = !session.isArchived)
            dao.updateSession(updated)
            if (_currentSession.value?.id == session.id) {
                _currentSession.value = null
                _currentMessages.value = emptyList()
            }
        }
    }

    fun updateSessionSystemPrompt(session: ChatSession, newPrompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = session.copy(systemPrompt = newPrompt)
            dao.updateSession(updated)
            if (_currentSession.value?.id == session.id) {
                _currentSession.value = updated
            }
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteSession(sessionId)
            if (_currentSession.value?.id == sessionId) {
                _currentSession.value = null
                _currentMessages.value = emptyList()
            }
        }
    }

    private var messageJob: kotlinx.coroutines.Job? = null
    private fun observeSessionMessages(sessionId: Long) {
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            dao.getMessagesForSession(sessionId).collect { list ->
                _currentMessages.value = list
            }
        }
    }

    fun sendMessage(content: String) {
        val session = _currentSession.value ?: return
        if (content.trim().isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val userMsg = ChatMessage(sessionId = session.id, role = "user")
            val userMsgId = dao.insertMessage(userMsg)
            val userVer = MessageVersion(messageId = userMsgId, content = content, versionNumber = 1)
            val userVerId = dao.insertVersion(userVer)
            dao.updateMessage(userMsg.copy(id = userMsgId, currentVersionId = userVerId))

            triggerAiStreaming(session.id)
        }
    }

    fun editMessage(messageId: Long, newContent: String, contextOption: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val previousVersions = dao.getVersionsForMessage(messageId)
            val newVerNum = previousVersions.size + 1

            val newVersion = MessageVersion(
                messageId = messageId,
                content = newContent,
                versionNumber = newVerNum,
                contextOption = contextOption
            )
            val newVersionId = dao.insertVersion(newVersion)

            val messages = _currentMessages.value
            val match = messages.firstOrNull { it.message.id == messageId }
            if (match != null) {
                dao.updateMessage(match.message.copy(currentVersionId = newVersionId))
            }

            val session = _currentSession.value
            if (session != null) {
                val matchRole = match?.message?.role ?: "user"
                if (matchRole == "user") {
                    if (contextOption == "BRANCH") {
                        val list = dao.getMessagesForSession(session.id).first()
                        list.forEach { msg ->
                            if (msg.message.timestamp > (match?.message?.timestamp ?: Long.MAX_VALUE)) {
                                dao.deleteMessage(msg.message.id)
                            }
                        }
                    }
                    triggerAiStreaming(session.id)
                }
            }
        }
    }

    fun switchMessageVersion(messageId: Long, versionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val messages = _currentMessages.value
            val match = messages.firstOrNull { it.message.id == messageId }
            if (match != null) {
                dao.updateMessage(match.message.copy(currentVersionId = versionId))
            }
        }
    }

    private fun triggerAiStreaming(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_isStreaming.value) return@launch
            _isStreaming.value = true
            _streamingContent.value = ""

            val session = dao.getActiveSessions().first().firstOrNull { it.id == sessionId }
                ?: dao.getArchivedSessions().first().firstOrNull { it.id == sessionId }
                ?: return@launch

            val requestMessages = buildRequestHistory(sessionId, session.systemPrompt)

            val assistantMsg = ChatMessage(sessionId = sessionId, role = "assistant")
            val assistantMsgId = dao.insertMessage(assistantMsg)

            var accumulatedText = ""
            var promptTokens = 0
            var completionTokens = 0
            val activeModel = _selectedModel.value

            apiService.streamChatCompletion(_apiKey.value, activeModel, requestMessages).collect { state ->
                when (state) {
                    is StreamResponseState.Content -> {
                        accumulatedText += state.text
                        _streamingContent.value = accumulatedText
                    }
                    is StreamResponseState.Usage -> {
                        promptTokens = state.promptTokens
                        completionTokens = state.completionTokens
                    }
                    is StreamResponseState.Error -> {
                        _streamingContent.value = "Error [${state.code}]: ${state.message}"
                        accumulatedText = _streamingContent.value
                        _isStreaming.value = false
                    }
                    StreamResponseState.Completed -> {
                        val assistantVer = MessageVersion(
                            messageId = assistantMsgId,
                            content = accumulatedText,
                            versionNumber = 1
                        )
                        val verId = dao.insertVersion(assistantVer)
                        dao.updateMessage(assistantMsg.copy(id = assistantMsgId, currentVersionId = verId))

                        val calculatedPrompt = if (promptTokens > 0) promptTokens else calculatePromptTokensApproximation(requestMessages)
                        val calculatedCompletion = if (completionTokens > 0) completionTokens else (accumulatedText.length / 4).coerceAtLeast(1)

                        val pricing = dao.getPricingForModel(activeModel)
                        val cost = (calculatedPrompt * (pricing?.promptCost ?: 0.0)) +
                                (calculatedCompletion * (pricing?.completionCost ?: 0.0))

                        dao.insertLog(
                            ApiLog(
                                endpoint = "chat/completions ($activeModel)",
                                requestTokens = calculatedPrompt,
                                responseTokens = calculatedCompletion,
                                cost = cost,
                                status = 200
                            )
                        )

                        triggerSoundNotification()
                        _streamingContent.value = ""
                        _isStreaming.value = false
                    }
                }
            }
        }
    }

    private suspend fun buildRequestHistory(sessionId: Long, systemPrompt: String): List<ChatCompletionMessage> {
        val allMsgWithVersions = dao.getMessagesForSession(sessionId).first()
        val formattedList = mutableListOf<ChatCompletionMessage>()

        if (systemPrompt.isNotEmpty()) {
            formattedList.add(ChatCompletionMessage("system", systemPrompt))
        }

        for (msgWithVer in allMsgWithVersions) {
            val msg = msgWithVer.message
            val versions = msgWithVer.versions
            val activeVersion = versions.firstOrNull { it.id == msg.currentVersionId } ?: versions.lastOrNull()

            if (activeVersion != null) {
                formattedList.add(ChatCompletionMessage(msg.role, activeVersion.content))

                if (msg.role == "user" && activeVersion.contextOption == "BRANCH") {
                    break
                }
            }
        }
        return formattedList
    }

    private fun calculatePromptTokensApproximation(messages: List<ChatCompletionMessage>): Int {
        var totalChars = 0
        messages.forEach { totalChars += it.content.length }
        return (totalChars / 4).coerceAtLeast(1)
    }

    fun exportSystemPromptToJson(promptText: String): String {
        val map = mapOf("systemPrompt" to promptText, "timestamp" to System.currentTimeMillis())
        return gson.toJson(map)
    }

    fun importSystemPromptFromJson(json: String): String? {
        return try {
            val map = gson.fromJson(json, Map::class.java)
            map["systemPrompt"]?.toString()
        } catch (e: Exception) {
            null
        }
    }

    fun setBgOverrideUri(uri: String) {
        _bgOverrideUri.value = uri
        prefs.edit().putString("bg_override_uri", uri).apply()
    }

    fun setSoundOverrideUri(uri: String) {
        _soundOverrideUri.value = uri
        prefs.edit().putString("sound_override_uri", uri).apply()
    }

    fun triggerSoundNotification() {
        runCatching {
            val soundUri = _soundOverrideUri.value
            if (soundUri.isNotEmpty()) {
                val player = android.media.MediaPlayer().apply {
                    setDataSource(getApplication(), android.net.Uri.parse(soundUri))
                    prepare()
                    start()
                }
                player.setOnCompletionListener { it.release() }
            } else {
                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100)
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearLogs()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setShowArchived(show: Boolean) {
        _showArchived.value = show
    }
}
