package kr.juhyu.licensenote

import org.junit.Assert.*
import org.junit.Test

class VehicleLessonTest {
    @Test fun exerciseRequiresANewObservedOffToOnTransitionFromTheVehicle() {
        val opened = 2000L
        val turn = Signal("2050,LEFT", "VALID", "Vehicle/TurnSignal", 2100, "CALLBACK")
        val wiper = Signal("2050", "VALID", "Vehicle/Wiper", 2100, "CALLBACK")
        assertTrue(observedTurnActivation(turn, opened, 2200))
        assertTrue(observedTurnActivation(turn.copy(value = "2050,RIGHT"), opened, 2200))
        assertTrue(observedWiperActivation(wiper, opened, 2200))
        assertFalse(observedTurnActivation(turn.copy(value = "LEFT"), opened, 2200))
        assertFalse(observedTurnActivation(turn.copy(value = "1900,LEFT"), opened, 2200))
        assertFalse(observedWiperActivation(wiper.copy(value = "1900"), opened, 2200))
        for (invalid in listOf(turn.copy(quality = "UNSUPPORTED"), turn.copy(source = "DEMO"),
            turn.copy(value = "2050,BOTH"), turn.copy(receivedMs = 2300), turn.copy(receivedMs = 100))) {
            assertFalse(observedTurnActivation(invalid, opened, 2200))
        }
        assertFalse(observedTurnActivation(turn, opened, 4101))
        assertFalse(observedWiperActivation(wiper.copy(quality = "ERROR"), opened, 2200))
        assertFalse(observedTurnActivation(turn.copy(value = "2050,LEFT", receivedMs = 8000), opened, 8000))
        assertFalse(observedWiperActivation(wiper.copy(receivedMs = 8000), opened, 8000))
    }

    @Test fun overviewUsesRealUnitsAndDoesNotInventMissingEnergyReadings() {
        val current = Signal("10", "VALID", receivedMs = 2000)
        assertEquals("36 km/h", vehicleOverviewValue("speed", current, 2100))
        assertEquals("P", vehicleOverviewValue("gear", current.copy(value = "4"), 2100))
        assertEquals("D", vehicleOverviewValue("gear", current.copy(value = "8"), 2100))
        assertEquals("48%", vehicleOverviewValue("fuel_percent", current.copy(value = "48"), 2100))
        for (input in listOf(null, current.copy(value = "101"), current.copy(value = "-1"),
            current.copy(value = "NaN"), current.copy(quality = "ERROR"), current.copy(receivedMs = 2200))) {
            assertEquals("확인 중", vehicleOverviewValue("battery_percent", input, 2100))
        }
        assertEquals("확인 중", vehicleOverviewValue("fuel_percent", current, 7001))
        assertEquals("차량 미지원", vehicleOverviewValue("fuel_percent", current.copy(quality = "UNSUPPORTED"), 2100))
    }
}
