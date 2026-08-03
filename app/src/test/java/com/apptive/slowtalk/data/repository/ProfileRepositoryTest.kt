package com.apptive.slowtalk.data.repository

import com.apptive.slowtalk.data.remote.ApiEnvelope
import com.apptive.slowtalk.data.remote.InterestApi
import com.apptive.slowtalk.data.remote.ProfileApi
import com.apptive.slowtalk.data.remote.ProfileUpdateRequest
import com.apptive.slowtalk.data.remote.RegionApi
import com.apptive.slowtalk.data.remote.RegionPatchDto
import com.apptive.slowtalk.data.remote.UserProfileDto
import com.apptive.slowtalk.data.remote.apiJson
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class ProfileRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var profileRepository: ProfileRepository
    private lateinit var regionRepository: RegionRepository
    private lateinit var interestRepository: InterestRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("api/v1/"))
            .addConverterFactory(apiJson.asConverterFactory("application/json".toMediaType()))
            .build()
        profileRepository = ProfileRepository(retrofit.create(ProfileApi::class.java))
        regionRepository = RegionRepository(retrofit.create(RegionApi::class.java))
        interestRepository = InterestRepository(retrofit.create(InterestApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `profile fixture decodes interest UUIDs and region codes`() {
        val decoded = apiJson.decodeFromString<ApiEnvelope<UserProfileDto>>(PROFILE_RESPONSE)
        val profile = requireNotNull(decoded.data)

        assertEquals(INTEREST_ID, profile.interests.single().id)
        assertEquals("11", profile.region?.province?.code)
        assertEquals("11440", profile.region?.district?.code)
        assertEquals("1144066000", profile.region?.subDistrict?.code)
    }

    @Test
    fun `profile repository loads server profile without fixture branch`() = runBlocking {
        server.enqueue(jsonResponse(PROFILE_RESPONSE))

        val result = profileRepository.getMyProfile()

        assertEquals("demo-user", result.getOrThrow().nickname)
        assertEquals("/api/v1/users/me", server.takeRequest().path)
    }

    @Test
    fun `profile update sends region codes and returns persisted profile`() = runBlocking {
        server.enqueue(jsonResponse(PROFILE_RESPONSE))

        val result = profileRepository.updateProfile(
            ProfileUpdateRequest(
                nickname = "demo-user",
                bio = "local demo",
                region = RegionPatchDto("11", "11440", "1144066000"),
            ),
        )

        assertEquals("demo-user", result.getOrThrow().nickname)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/users/me", request.path)
        assertEquals(
            "{\"nickname\":\"demo-user\",\"bio\":\"local demo\",\"region\":{\"provinceCode\":\"11\",\"districtCode\":\"11440\",\"subDistrictCode\":\"1144066000\"}}",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `region repository uses province and district code paths`() = runBlocking {
        server.enqueue(jsonResponse(REGION_OPTIONS_RESPONSE))
        server.enqueue(jsonResponse(REGION_OPTIONS_RESPONSE))

        regionRepository.getDistricts("11").getOrThrow()
        regionRepository.getSubDistricts("11440").getOrThrow()

        assertEquals("/api/v1/regions/provinces/11/districts", server.takeRequest().path)
        assertEquals("/api/v1/regions/districts/11440/sub-districts", server.takeRequest().path)
    }

    @Test
    fun `interest replacement sends UUID list with PUT`() = runBlocking {
        server.enqueue(jsonResponse(PROFILE_RESPONSE))

        val result = interestRepository.updateMyInterests(listOf(INTEREST_ID))

        assertEquals(INTEREST_ID, result.getOrThrow().interests.single().id)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/users/me/interests", request.path)
        assertEquals("{\"interestIds\":[\"$INTEREST_ID\"]}", request.body.readUtf8())
    }

    @Test
    fun `failed profile update remains a failure`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"ok":false,"data":null,"error":{"code":"VALIDATION_ERROR","message":"invalid region"},"meta":null}""",
                status = 400,
            ),
        )

        val result = profileRepository.updateProfile(
            ProfileUpdateRequest(region = RegionPatchDto("invalid", "invalid")),
        )

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("VALIDATION_ERROR"))
    }

    private fun jsonResponse(body: String, status: Int = 200) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val INTEREST_ID = "11111111-1111-4111-8111-111111111111"
        const val PROFILE_RESPONSE = """{"ok":true,"data":{"id":"22222222-2222-4222-8222-222222222222","nickname":"demo-user","bio":"local demo","interests":[{"id":"$INTEREST_ID","name":"walking"}],"region":{"province":{"code":"11","name":"Seoul"},"district":{"code":"11440","name":"Mapo"},"subDistrict":{"code":"1144066000","name":"Seogyo"}},"statistics":{"sentLetters":1,"receivedLetters":2,"matchCount":3}},"error":null,"meta":null}"""
        const val REGION_OPTIONS_RESPONSE = """{"ok":true,"data":[{"code":"11","name":"Seoul"}],"error":null,"meta":null}"""
    }
}
