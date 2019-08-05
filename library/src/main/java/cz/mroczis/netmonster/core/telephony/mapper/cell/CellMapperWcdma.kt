package cz.mroczis.netmonster.core.telephony.mapper.cell

import android.annotation.TargetApi
import android.os.Build
import android.telephony.CellIdentityWcdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SignalStrength
import android.telephony.gsm.GsmCellLocation
import cz.mroczis.netmonster.core.db.BandTableWcdma
import cz.mroczis.netmonster.core.model.Network
import cz.mroczis.netmonster.core.model.band.BandWcdma
import cz.mroczis.netmonster.core.model.cell.CellWcdma
import cz.mroczis.netmonster.core.model.cell.ICell
import cz.mroczis.netmonster.core.model.connection.IConnection
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import cz.mroczis.netmonster.core.model.signal.SignalGsm
import cz.mroczis.netmonster.core.model.signal.SignalWcdma
import cz.mroczis.netmonster.core.util.Reflection
import cz.mroczis.netmonster.core.util.inRangeOrNull

private val REGEX_BIT_ERROR = "ber=([^ ]*)".toRegex()
private val REGEX_RSCP = "rscp=([^ ]*)".toRegex()
private val REGEX_RSSI = "ss=([^ ]*)".toRegex()
private val REGEX_ECNO = "ecno=([^ ]*)".toRegex()

/**
 * [CellSignalStrengthWcdma] -> [SignalWcdma]
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
internal fun CellSignalStrengthWcdma.mapSignal(): SignalWcdma {
    val string = toString()

    val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Android Q changes how 'CellSignalStrengthWcdma.getDbm()' works and
        // returns RSCP if available. In other cases RSSI is returned.
        // The only working way to get RSSI is again from string cause methods have @hide annotation
        REGEX_RSSI.find(string)?.groupValues?.getOrNull(1)?.toInt()
            ?.inRangeOrNull(SignalWcdma.RSSI_RANGE)
    } else {
        // Some older phones reported inadequate values when it came to ASU and DBM sources
        // We must decide what happens if values do not fit
        val rssiFromAsu = (-113 + 2 * asuLevel).inRangeOrNull(SignalWcdma.RSSI_RANGE) // ASU -> DBM
        val rssiFromDbm = dbm.inRangeOrNull(SignalWcdma.RSSI_RANGE)
        // In real world those two values must be equal
        if (rssiFromAsu != rssiFromAsu) {
            rssiFromDbm ?: rssiFromAsu
        } else rssiFromDbm
    }


    val rscp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        REGEX_RSCP.find(string)?.groupValues?.getOrNull(1)?.toInt()
            ?.inRangeOrNull(SignalWcdma.RSCP_RANGE)
    } else null

    val bitError = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        REGEX_BIT_ERROR.find(string)?.groupValues?.getOrNull(1)?.toInt()
            ?.inRangeOrNull(SignalWcdma.BIT_ERROR_RATE_RANGE)
    } else null

    val ecno = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        REGEX_ECNO.find(string)?.groupValues?.getOrNull(1)?.toInt()
            ?.inRangeOrNull(SignalWcdma.ECNO_RANGE)
    } else null

    return SignalWcdma(
        rssi = rssi,
        bitErrorRate = bitError,
        ecno = ecno,
        rscp = rscp,
        ecio = null
    )
}

/**
 * [CellIdentityWcdma] -> [CellWcdma]
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
internal fun CellIdentityWcdma.mapCell(connection: IConnection, signal: SignalWcdma): CellWcdma? {
    val network = mapNetwork()
    val ci = cid.inRangeOrNull(CellWcdma.CID_RANGE)
    val lac = lac.inRangeOrNull(CellWcdma.LAC_RANGE)
    val psc = psc.inRangeOrNull(CellWcdma.PSC_RANGE)

    val uarfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        uarfcn.inRangeOrNull(BandWcdma.DOWNLINK_UARFCN_RANGE)
    } else null

    val band = if (uarfcn != null) {
        BandTableWcdma.map(uarfcn)
    } else null

    return if (lac == null && ci != null && ci < 100) {
        // Samsung phones (SM-G960F) tend to report LAC = 0 and sequence of CIs starting with 1 (step 1) for
        // neighbouring cells. This check assumes there's less than 100 neighbouring cells
        null
    } else if (ci == null && psc == null && uarfcn == null) {
        // Generally invalid data that cannot be used
        null
    } else {
        CellWcdma(
            network = network,
            ci = ci,
            lac = lac,
            psc = psc,
            connectionStatus = connection,
            signal = signal,
            band = band
        )
    }
}

/**
 * [CellIdentityWcdma] -> [Network]
 */
@Suppress("DEPRECATION")
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
internal fun CellIdentityWcdma.mapNetwork(): Network? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Network.map(mccString, mncString)
    } else {
        Network.map(mcc, mnc)
    }

@Suppress("DEPRECATION")
internal fun GsmCellLocation.mapWcdma(signalStrength: SignalStrength?, network: Network?): ICell? {
    val cid = cid.inRangeOrNull(CellWcdma.CID_RANGE)
    val lac = lac.inRangeOrNull(CellWcdma.LAC_RANGE)
    val psc = psc.inRangeOrNull(CellWcdma.PSC_RANGE)

    val rssi = signalStrength?.gsmSignalStrength?.inRangeOrNull(SignalGsm.RSSI_RANGE)
    val ber = signalStrength?.gsmBitErrorRate?.inRangeOrNull(SignalGsm.BIT_ERROR_RATE_RANGE)
    val ecio = Reflection.intFieldOrNull(Reflection.UMTS_ECIO, signalStrength)?.inRangeOrNull(SignalWcdma.ECIO_RANGE)
    val rscp = Reflection.intFieldOrNull(Reflection.UMTS_RSCP, signalStrength)?.inRangeOrNull(SignalWcdma.RSCP_RANGE)

    return if (cid != null && lac != null) {
        CellWcdma(
            ci = cid,
            lac = lac,
            psc = psc,
            band = null,
            signal = SignalWcdma(
                rssi = rssi,
                bitErrorRate = ber,
                ecio = ecio,
                rscp = rscp,
                ecno = null
            ),
            network = network,
            connectionStatus = PrimaryConnection()
        )
    } else null
}