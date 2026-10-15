package com.mklhc.core.data.mapper

import com.mklhc.core.domain.KartInfo
import com.mklhc.core.kart_protocol.fuji.FujiProductCode
import com.mklhc.core.kart_protocol.fuji.FujiSystemInfo

/**
 * Assembles the [KartInfo] shown to the user from the two answers the kart gives when it is identified.
 *
 * Business logic: the serial and character id come from the kart's product code, the firmware
 * versions and SHA-1 from its system information. Note the wire types name the pair `bootVersion` /
 * `systemVersion` while [KartInfo] lists the system firmware first; the mapping is by name, never by
 * position. The two unknown product-code fields are dropped: nothing in the app depends on them.
 *
 * @param productCode the decoded `product_code` parameter.
 * @param systemInfo the decoded `GetSystemInfo` answer.
 */
fun kartInfoOf(productCode: FujiProductCode, systemInfo: FujiSystemInfo): KartInfo = KartInfo(
    serial = productCode.serial,
    character = productCode.character,
    firmwareSystem = systemInfo.systemVersion,
    firmwareBoot = systemInfo.bootVersion,
    sha1 = systemInfo.sha1,
)
