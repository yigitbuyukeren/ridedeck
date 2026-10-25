package com.ridedeck.app

import com.ridedeck.app.data.BluetoothMicRoutePolicy
import com.ridedeck.app.data.MicRouteTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothMicRoutePolicyTest {
    @Test
    fun bluetoothIsSelectedOnlyWhenEnabledAndAvailable() {
        assertEquals(MicRouteTarget.PHONE, BluetoothMicRoutePolicy.select(enabled = false, bluetoothInputAvailable = true))
        assertEquals(MicRouteTarget.PHONE, BluetoothMicRoutePolicy.select(enabled = true, bluetoothInputAvailable = false))
        assertEquals(MicRouteTarget.PHONE, BluetoothMicRoutePolicy.select(enabled = false, bluetoothInputAvailable = false))
        assertEquals(MicRouteTarget.BLUETOOTH, BluetoothMicRoutePolicy.select(enabled = true, bluetoothInputAvailable = true))
    }
}
