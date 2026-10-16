package com.pushizo.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HsPermissionObserversTest {
    @Test
    fun `ilk olcum temel degerdir gozlemci cagrilmaz`() {
        val observers = HsPermissionObservers()
        val calls = mutableListOf<Boolean>()
        observers.add { granted: Boolean -> calls.add(granted) }
        assertNull(observers.lastKnown)
        assertFalse(observers.update(false))
        assertEquals(false, observers.lastKnown)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `yalnizca durum degisince bildirilir`() {
        val observers = HsPermissionObservers()
        val calls = mutableListOf<Boolean>()
        observers.add { granted: Boolean -> calls.add(granted) }
        observers.update(false)
        assertFalse(observers.update(false))
        assertTrue(observers.update(true))
        assertFalse(observers.update(true))
        assertTrue(observers.update(false))
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun `Java arayuzu ve lambda gozlemcileri birlikte calisir ve kaldirilabilir`() {
        val observers = HsPermissionObservers()
        val lambdaCalls = mutableListOf<Boolean>()
        val javaCalls = mutableListOf<Boolean>()
        val lambda: (Boolean) -> Unit = { lambdaCalls.add(it) }
        val java = Pushizo.Notifications.PermissionObserver { javaCalls.add(it) }
        observers.add(lambda)
        observers.add(java)
        observers.update(false)
        observers.update(true)
        observers.remove(lambda)
        observers.update(false)
        observers.remove(java)
        observers.update(true)
        assertEquals(listOf(true), lambdaCalls)
        assertEquals(listOf(true, false), javaCalls)
        assertTrue(observers.isEmpty)
    }

    @Test
    fun `hata veren gozlemci digerlerini engellemez`() {
        val errors = mutableListOf<Throwable>()
        val observers = HsPermissionObservers { errors.add(it) }
        val calls = mutableListOf<Boolean>()
        observers.add { _: Boolean -> throw IllegalStateException("gözlemci hatası") }
        observers.add(Pushizo.Notifications.PermissionObserver { calls.add(it) })
        observers.update(true)
        observers.update(false)
        assertEquals(listOf(false), calls)
        assertEquals(1, errors.size)
    }

    @Test
    fun `ayni gozlemci iki kez eklenirse bir kez cagrilir`() {
        val observers = HsPermissionObservers()
        var count = 0
        val observer = Pushizo.Notifications.PermissionObserver { count++ }
        observers.add(observer)
        observers.add(observer)
        observers.update(false)
        observers.update(true)
        assertEquals(1, count)
    }
}
