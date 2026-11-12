package com.pulsevoicephone

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthRepository(private val client: HealthConnectClient) {
    suspend fun latestSamsungPulse(now: Instant): PulseSample? {
        var token: String? = null
        var latest: PulseSample? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(1, ChronoUnit.DAYS), now),
                    dataOriginFilter = setOf(DataOrigin("com.sec.android.app.shealth")),
                    pageSize = 1000,
                    pageToken = token
                )
            )
            // Record order is based on its start, not the timestamp of each sample.
            val candidate = PulsePolicy.latest(page.records.flatMap { record ->
                record.samples.map { PulseSample(it.beatsPerMinute, it.time) }
            }, now)
            latest = PulsePolicy.latest(listOfNotNull(latest, candidate), now)
            token = page.pageToken
        } while (token != null)
        return latest
    }
}
