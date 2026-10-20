package com.pushizo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HsChatTest {
    private class MemoryChatState : HsChatState {
        override var chatVisitorId: String? = null
        override var chatVisitorToken: String? = null
        override var chatEnabled: Boolean = false
        override var chatIdentifyKey: String? = null
        override var chatLastSeq: Long = 0L
        override var chatDeliveredSeq: Long = 0L
        override var chatUnreadCount: Int = 0
        override fun clearChatVisitor() {
            chatVisitorId = null
            chatVisitorToken = null
            chatIdentifyKey = null
            chatLastSeq = 0L
            chatDeliveredSeq = 0L
            chatUnreadCount = 0
        }
    }

    private val visitor = HsChatVisitor("v".repeat(32), "t".repeat(64))

    private fun message(seq: Long, id: String? = "msg_$seq", cmid: String? = null, sender: String = HsChatMessage.SENDER_AGENT, status: String = "sent") =
        HsChatMessage(id, seq, cmid, sender, null, "m$seq", emptyList(), null, status, null)

    // --- Ziyaretçi kimliği ---

    @Test
    fun `ziyaretci kimligi sunucu desenine uyar ve kalici saklanir`() {
        val state = MemoryChatState()
        val first = HsChatVisitor.ensure(state)
        assertTrue(HsChatLimits.VISITOR_ID.matches(first.id))
        assertTrue(HsChatLimits.VISITOR_TOKEN.matches(first.token))
        assertEquals(32, first.id.length)
        assertEquals(64, first.token.length)
        assertEquals(first.id, state.chatVisitorId)
        assertEquals(first.token, state.chatVisitorToken)

        val again = HsChatVisitor.ensure(state)
        assertEquals("ikinci çağrı aynı kimliği döndürmeli", first.id, again.id)
        assertEquals(first.token, again.token)
    }

    @Test
    fun `bozuk kayitli kimlik yenisiyle degistirilir ve eski sohbet durumu silinir`() {
        val state = MemoryChatState().apply {
            chatVisitorId = "kisa"
            chatVisitorToken = "x".repeat(64)
            chatIdentifyKey = "eski"
            chatLastSeq = 9
        }
        val v = HsChatVisitor.ensure(state)
        assertNotEquals("kisa", v.id)
        assertNull(state.chatIdentifyKey)
        assertEquals(0L, state.chatLastSeq)
    }

    @Test
    fun `farkli ziyaretciler farkli kimlik ve belirtec alir`() {
        val a = HsChatVisitor.ensure(MemoryChatState())
        val b = HsChatVisitor.ensure(MemoryChatState())
        assertNotEquals(a.id, b.id)
        assertNotEquals(a.token, b.token)
    }

    @Test
    fun `istemci mesaj kimligi sunucu desenine uyar ve rastgeledir`() {
        val ids = (1..50).map { HsChatVisitor.newClientMessageId() }.toSet()
        assertEquals(50, ids.size)
        ids.forEach { assertTrue(it, HsChatLimits.CLIENT_MESSAGE_ID.matches(it)) }
        // Aynı tohumla aynı çıktı (deterministik üretici enjekte edilebilir).
        assertEquals(HsChatVisitor.newClientMessageId(Random(1)), HsChatVisitor.newClientMessageId(Random(1)))
    }

    // --- İstek gövdeleri ---

    @Test
    fun `identify govdesi abonelik baglamayi yalnizca token kanitiyla ekler`() {
        val body = HsChatRequests.identifyBody(visitor, "user_42", "hash1234567890abcdef", "sub_1", "fcm-token", " Ali ", null, "tr")
        assertEquals(visitor.id, body.getString("visitor_id"))
        assertEquals(visitor.token, body.getString("visitor_token"))
        assertEquals("user_42", body.getString("external_id"))
        assertEquals("hash1234567890abcdef", body.getString("identity_hash"))
        assertEquals("sub_1", body.getString("subscription_id"))
        assertEquals(HsOwnership.tokenHash("fcm-token"), body.getString("token_hash"))
        assertEquals(64, body.getString("token_hash").length)
        assertEquals("Ali", body.getString("name"))
        assertEquals("tr", body.getString("language"))
        assertFalse(body.has("email"))

        val noToken = HsChatRequests.identifyBody(visitor, null, "hash-yok-sayilir", "sub_1", null, null, null, null)
        assertFalse("kanıt yoksa abonelik gönderilmez", noToken.has("subscription_id"))
        assertFalse(noToken.has("token_hash"))
        assertFalse("external_id yoksa identity_hash gönderilmez", noToken.has("identity_hash"))
        assertFalse(noToken.has("external_id"))
    }

    @Test
    fun `identify anahtari girdi degisince degisir, belirtec icermez`() {
        val a = HsChatRequests.identifyKey(HsChatRequests.identifyBody(visitor, "u1", null, "sub", "tok", null, null, null))
        val same = HsChatRequests.identifyKey(HsChatRequests.identifyBody(visitor, "u1", null, "sub", "tok", null, null, null))
        val otherToken = HsChatRequests.identifyKey(HsChatRequests.identifyBody(visitor, "u1", null, "sub", "tok2", null, null, null))
        val otherUser = HsChatRequests.identifyKey(HsChatRequests.identifyBody(visitor, "u2", null, "sub", "tok", null, null, null))
        assertEquals(a, same)
        assertNotEquals(a, otherToken)
        assertNotEquals(a, otherUser)
        assertFalse(a.contains(visitor.token))
    }

    @Test
    fun `mesaj, cevrimdisi, yaziyor ve ack govdeleri`() {
        val send = HsChatRequests.sendBody(visitor, "m_abcdefgh", "Merhaba", "opt_1", null, "ali@example.com", null)
        assertEquals("m_abcdefgh", send.getString("client_message_id"))
        assertEquals("Merhaba", send.getString("text"))
        assertEquals("opt_1", send.getString("option_id"))
        assertEquals("ali@example.com", send.getString("email"))
        assertFalse(send.has("name"))

        val offline = HsChatRequests.offlineBody(visitor, "m_12345678", "ali@example.com", "Arayın", "Ali", null)
        assertEquals("ali@example.com", offline.getString("email"))
        assertEquals("Ali", offline.getString("name"))
        assertEquals(visitor.token, offline.getString("visitor_token"))

        assertTrue(HsChatRequests.typingBody(visitor, true).getBoolean("typing"))

        val ack = HsChatRequests.ackBody(visitor, 7, null)
        assertEquals(7, ack.getLong("read_seq"))
        assertFalse(ack.has("delivered_seq"))
        assertEquals(9, HsChatRequests.ackBody(visitor, null, 9).getLong("delivered_seq"))
        assertThrows { HsChatRequests.ackBody(visitor, null, null) }
    }

    @Test
    fun `mesaj sorgusu ve dogrulamalar`() {
        val q = HsChatRequests.messagesQuery(visitor, 5, null, 50)
        assertEquals("visitor_id=${visitor.id}&token=${visitor.token}&after_seq=5&limit=50", q)
        assertTrue(HsChatRequests.messagesQuery(visitor, null, 10, 20).contains("before_seq=10&limit=20"))
        assertThrows { HsChatRequests.messagesQuery(visitor, null, null, 0) }
        assertThrows { HsChatRequests.messagesQuery(visitor, null, null, 101) }
        assertThrows { HsChatRequests.messagesQuery(visitor, null, 0, 10) }
        assertEquals("selam", HsChatRequests.requireText("  selam "))
        assertThrows { HsChatRequests.requireText("   ") }
        assertThrows { HsChatRequests.requireText("a".repeat(4001)) }
    }

    @Test
    fun `multipart govdesi alanlari ve dosyayi sinirlarla tasir`() {
        val mp = HsMultipart("BOUNDARY")
            .field("visitor_id", visitor.id)
            .field("client_message_id", "m_12345678")
            .file("file", "rapor \"son\".pdf", "application/pdf", byteArrayOf(1, 2, 3))
        val text = String(mp.build(), Charsets.ISO_8859_1)
        assertEquals("multipart/form-data; boundary=BOUNDARY", mp.contentType)
        assertTrue(text.startsWith("--BOUNDARY\r\nContent-Disposition: form-data; name=\"visitor_id\"\r\n\r\n${visitor.id}\r\n"))
        assertTrue(text.contains("filename=\"rapor %22son%22.pdf\"\r\nContent-Type: application/pdf\r\n\r\n\u0001\u0002\u0003\r\n"))
        assertTrue(text.endsWith("--BOUNDARY--\r\n"))
    }

    // --- SSE ---

    @Test
    fun `sse ayristirici olay, kimlik, cok satirli veri ve yorumlari isler`() {
        val parser = HsSseParser()
        val lines = listOf(
            "retry: 3000", ": connected", "",
            "id: 4", "event: message", "data: {\"seq\":4}", "",
            ": hb", "",
            "event: typing", "data: {\"typing\":true,", "data: \"name\":\"Ayşe\"}", "",
            "data:no-space", "",
        )
        val events = lines.mapNotNull { parser.feed(it) }
        assertEquals(3, events.size)
        assertEquals(HsSseEvent("message", "{\"seq\":4}", "4"), events[0])
        assertEquals("typing", events[1].event)
        assertEquals("{\"typing\":true,\n\"name\":\"Ayşe\"}", events[1].data)
        assertEquals("son kimlik sonraki olaylarda korunur", "4", events[1].id)
        assertEquals("message", events[2].event)
        assertEquals("no-space", events[2].data)
        assertEquals(3000L, parser.retryMs)
        assertEquals("4", parser.lastEventId)
    }

    @Test
    fun `sse satir sonu CR temizlenir ve NUL iceren kimlik yok sayilir`() {
        val parser = HsSseParser()
        parser.feed("id: 7\r")
        parser.feed("id: 8\u0000")
        parser.feed("data: x\r")
        val event = parser.feed("\r")
        assertEquals(HsSseEvent("message", "x", "7"), event)
    }

    // --- Sıra ve tekilleştirme ---

    @Test
    fun `zaman cizelgesi sira numarasina gore siralar ve tekillestirir`() {
        val merged = HsChatTimeline.merge(listOf(message(3), message(1)), listOf(message(2), message(3, status = "read")))
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.seq })
        assertEquals("aynı sıradaki yeni kopya eskisinin yerine geçer", "read", merged.last().status)
    }

    @Test
    fun `bekleyen yerel mesaj sunucu kopyasi gelince kalkar`() {
        val pending = message(0, id = null, cmid = "m_local001", sender = HsChatMessage.SENDER_VISITOR, status = HsChatMessage.STATUS_PENDING)
        val other = message(0, id = null, cmid = "m_local002", sender = HsChatMessage.SENDER_VISITOR, status = HsChatMessage.STATUS_PENDING)
        val before = HsChatTimeline.merge(listOf(message(1)), listOf(pending, other))
        assertEquals(listOf(1L, 0L, 0L), before.map { it.seq })
        val after = HsChatTimeline.merge(before, listOf(message(2, cmid = "m_local001", sender = HsChatMessage.SENDER_VISITOR)))
        assertEquals(listOf(1L, 2L, 0L), after.map { it.seq })
        assertEquals("m_local002", after.last().clientMessageId)
    }

    @Test
    fun `goruldu kumesi ayni mesaji bir kez bildirir ve sinirlidir`() {
        val seen = HsChatSeen(capacity = 3)
        assertTrue(seen.mark("a"))
        assertFalse(seen.mark("a"))
        assertTrue("kimliksiz (yerel) mesaj her zaman bildirilir", seen.mark(null))
        seen.mark("b"); seen.mark("c"); seen.mark("d")
        assertTrue("kapasite aşılınca en eski unutulur", seen.mark("a"))
    }

    @Test
    fun `geri cekilme ikiye katlanir ve 30 saniyede durur`() {
        assertEquals(1_000L, HsChatBackoff.delayMs(1, 0.5))
        assertEquals(2_000L, HsChatBackoff.delayMs(2, 0.5))
        assertEquals(16_000L, HsChatBackoff.delayMs(5, 0.5))
        assertEquals(30_000L, HsChatBackoff.delayMs(6, 0.5))
        assertEquals(30_000L, HsChatBackoff.delayMs(40, 0.5))
        assertEquals(800L, HsChatBackoff.delayMs(1, 0.0))
        assertEquals(36_000L, HsChatBackoff.delayMs(9, 1.0))
    }

    // --- Ayrıştırma ---

    @Test
    fun `mesaj ve konusma yaniti ayristirilir`() {
        val json = JSONObject(
            """{"message":{"id":"cmsg_1","seq":12,"client_message_id":null,"sender":"bot","author_name":"Asistan","text":"Seçin",
               "attachments":[{"id":"att_1","name":"a.png","type":"image/png","size":2048}],
               "quick_replies":[{"id":"o1","label":"Satış"},{"id":"o2","label":"Destek"}],
               "status":"delivered","created_at":"2026-10-20T10:00:00.000Z"},
               "conversation":{"id":"conv_1","status":"open","last_seq":12,"agent_last_read_seq":10,"visitor_last_read_seq":11,
               "unread_count":1,"bot_active":true,"assignee_name":null,"created_at":"2026-10-20T09:59:00Z"},"duplicate":false}""",
        )
        val m = HsChatParser.message(json.getJSONObject("message"))
        assertEquals(12L, m.seq)
        assertNull(m.clientMessageId)
        assertEquals(HsChatMessage.SENDER_BOT, m.sender)
        assertEquals(listOf(HsChatAttachment("att_1", "a.png", "image/png", 2048)), m.attachments)
        assertEquals(listOf("o1", "o2"), m.quickReplies?.map { it.id })
        assertEquals(1_792_490_400_000L, m.createdAt)
        val c = HsChatParser.conversation(json.getJSONObject("conversation"))!!
        assertTrue(c.botActive)
        assertEquals(1, c.unreadCount)
        assertNull(c.assigneeName)
        assertEquals(12L, m.toMap()["seq"])
        assertEquals("Satış", (m.toJson().getJSONArray("quickReplies").getJSONObject(0)).getString("label"))
    }

    @Test
    fun `sayfa sira numarasina gore siralanir, konusmasiz durum sifirdir`() {
        val page = HsChatParser.page(JSONObject("""{"data":[{"id":"b","seq":5,"sender":"agent","attachments":[],"status":"sent"},{"id":"a","seq":4,"sender":"visitor","attachments":[],"status":"read"}],"has_more":true}"""))
        assertEquals(listOf(4L, 5L), page.messages.map { it.seq })
        assertTrue(page.hasMore)
        val empty = HsChatParser.conversationState(JSONObject("""{"conversation":null,"unread_count":0}"""))
        assertNull(empty.conversation)
        assertEquals(0, empty.unreadCount)
    }

    @Test
    fun `config ayristirilir`() {
        val cfg = HsChatParser.config(
            JSONObject(
                """{"enabled":true,"online":false,"within_business_hours":false,"color":"#112233","text_color":null,"position":"left",
                   "theme":"dark","title":"Destek","greeting":null,"offline_message":"Şu an çevrimdışıyız","collect_email_offline":true,
                   "require_email":false,"attachments":{"enabled":true,"max_bytes":10485760,"types":["image/png","application/pdf"]},"language":"tr"}""",
            ),
        )
        assertFalse(cfg.online)
        assertEquals("Şu an çevrimdışıyız", cfg.offlineMessage)
        assertEquals(10_485_760L, cfg.attachmentMaxBytes)
        assertEquals(listOf("image/png", "application/pdf"), cfg.attachmentTypes)
        assertEquals("left", cfg.position)
    }

    // --- Push ---

    @Test
    fun `push payloadindaki sohbet isareti ayristirilir`() {
        assertEquals(HsChatPushInfo("conv_1", 7), HsChatPush.parse(mapOf("pushizo_chat" to """{"conversation_id":"conv_1","seq":7}""", "hs" to "{}")))
        assertEquals(
            HsChatPushInfo("conv_2", 3),
            HsChatPush.parse(mapOf("custom_data" to """{"pushizo_chat":{"conversation_id":"conv_2","seq":3}}""")),
        )
        assertNull(HsChatPush.parse(mapOf("hs_title" to "Merhaba")))
        assertNull(HsChatPush.parse(mapOf("pushizo_chat" to "bozuk")))
        assertNull(HsChatPush.parse(mapOf("pushizo_chat" to """{"seq":3}""")))
        // HsNotification özel verisinde de anahtar korunur (tıklamada ayrıştırılır).
        val n = HsNotification.fromFcm(mapOf("hs_title" to "Yeni mesaj", "pushizo_chat" to """{"conversation_id":"conv_9","seq":1}"""), null, null, null, null, null)
        assertEquals(HsChatPushInfo("conv_9", 1), HsChatPush.parse(n.data))
    }

    @Test
    fun `olay uyarlayicisi kopru turlerini uretir`() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val adapter = HsChatEventAdapter { type, payload -> events.add(type to payload) }
        adapter.onMessage(message(1))
        adapter.onTyping(true, "Ayşe")
        adapter.onAgentRead(4)
        adapter.onUnreadCountChanged(2)
        adapter.onConversationUpdated("conv_1", "resolved", false)
        adapter.onConnectionChanged(true)
        adapter.onError("HS_PLAN_FEATURE_REQUIRED", null)
        assertEquals(listOf("message", "typing", "read", "unread", "conversation", "connection", "error"), events.map { it.first })
        assertEquals(1L, (events[0].second["message"] as Map<*, *>)["seq"])
        assertEquals(2, events[3].second["count"])
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("IllegalArgumentException bekleniyordu")
    }
}
