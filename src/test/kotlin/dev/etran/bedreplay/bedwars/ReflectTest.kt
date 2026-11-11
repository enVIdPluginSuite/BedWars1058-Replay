package dev.etran.bedreplay.bedwars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReflectTest {
    class Sample {
        fun name() = "x"
        fun twice(n: Int) = n * 2
        fun pick(s: String) = "string"
        fun pick(n: Number) = "number"
    }

    @Test
    fun missingMethodReturnsNullInsteadOfThrowing() {
        // the miss is cached on the first call; both calls must stay quiet
        assertNull(Reflect.call(Sample(), "doesNotExist"))
        assertNull(Reflect.call(Sample(), "doesNotExist"))
    }

    @Test
    fun callsMethodsAndTellsOverloadsApartByArgumentType() {
        val s = Sample()
        assertEquals("x", Reflect.call(s, "name"))
        assertEquals(10, Reflect.call(s, "twice", 5))
        assertEquals("string", Reflect.call(s, "pick", "a"))
        assertEquals("number", Reflect.call(s, "pick", 3))
    }

    @Test
    fun nullTargetIsHarmless() {
        assertNull(Reflect.call(null, "anything"))
    }
}
