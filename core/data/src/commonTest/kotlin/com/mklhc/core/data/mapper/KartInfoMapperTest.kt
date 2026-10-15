package com.mklhc.core.data.mapper

import com.mklhc.core.domain.KartInfo
import com.mklhc.core.kart_protocol.fuji.FujiProductCode
import com.mklhc.core.kart_protocol.fuji.FujiSystemInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** [kartInfoOf]: assembling the display model from the two decoded kart answers. */
class KartInfoMapperTest {

    @Test
    fun mapsProductCodeAndSystemInfoToKartInfo() {
        val product = FujiProductCode(unk1 = 0x1234, character = 2, unk2 = 9, serial = "SN-0042")
        val system = FujiSystemInfo(bootVersion = 3 to 4, systemVersion = 1 to 7, sha1 = "abcdef0123")

        val info = kartInfoOf(product, system)

        assertEquals(
            KartInfo(
                serial = "SN-0042",
                character = 2,
                firmwareSystem = 1 to 7,
                firmwareBoot = 3 to 4,
                sha1 = "abcdef0123",
            ),
            info,
        )
    }

    @Test
    fun bootAndSystemVersionsAreNotSwapped() {
        val info = kartInfoOf(
            FujiProductCode(0, 1, 0, "X"),
            FujiSystemInfo(bootVersion = 10 to 11, systemVersion = 20 to 21, sha1 = ""),
        )
        assertEquals(20 to 21, info.firmwareSystem)
        assertEquals(10 to 11, info.firmwareBoot)
    }
}
