package com.example.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

private const val UPDATE_PREFS = "uchat_update_prefs"
private const val KEY_CUSTOM_REPO = "custom_github_repo"
private val REPO_REGEX = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
private val BUILD_NUM_REGEX = Regex("(?:debug-apk-build-|build[-_#]?|v\\d+\\.)([0-9]+)", RegexOption.IGNORE_CASE)

data class AppReleaseInfo(
    val latestBuildNumber: Int,
    val tagName: String,
    val title: String,
    val notes: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val apkSizeBytes: Long?,
    val releasePageUrl: String,
    val publishedAt: String
) {
    val preferredUpdateUrl: String
        get() = apkDownloadUrl ?: releasePageUrl
}

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class NeedsRepoConfig(val reason: String) : AppUpdateState
    data class UpToDate(
        val currentBuild: Int,
        val latestBuild: Int,
        val checkedAtMillis: Long = System.currentTimeMillis()
    ) : AppUpdateState
    data class UpdateAvailable(val release: AppReleaseInfo) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

/**
 * Checks GitHub Releases over HTTPS for newer UChat builds and hands off verified
 * HTTPS release links to Android's system browser/downloader (zero restricted install
 * permissions and no dynamic code loading).
 */
class AppUpdateChecker(
    private val appContext: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    private val prefs by lazy {
        appContext.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
    }

    val currentBuildNumber: Int
        get() = BuildConfig.APP_BUILD_NUMBER.coerceAtLeast(1)

    val currentVersionName: String
        get() = BuildConfig.VERSION_NAME

    fun getConfiguredRepo(): String {
        val custom = prefs.getString(KEY_CUSTOM_REPO, "").orEmpty().trim()
        if (isValidRepoSlug(custom)) return custom
        val baked = BuildConfig.GITHUB_REPO.trim()
        if (isValidRepoSlug(baked)) return baked
        return ""
    }

    fun isUsingBakedRepo(): Boolean =
        prefs.getString(KEY_CUSTOM_REPO, "").orEmpty().isBlank() && isValidRepoSlug(BuildConfig.GITHUB_REPO.trim())

    fun saveCustomRepo(rawInput: String): Result<String> {
        val normalized = normalizeRepoInput(rawInput)
        if (normalized.isBlank()) {
            prefs.edit().remove(KEY_CUSTOM_REPO).apply()
            return Result.success(getConfiguredRepo())
        }
        if (!isValidRepoSlug(normalized)) {
            return Result.failure(
                IllegalArgumentException("Enter a valid GitHub repository as owner/repo (for example: myname/uchat).")
            )
        }
        prefs.edit().putString(KEY_CUSTOM_REPO, normalized).apply()
        return Result.success(normalized)
    }

    suspend fun checkForUpdate(): AppUpdateState = withContext(Dispatchers.IO) {
        val repo = getConfiguredRepo()
        if (!isValidRepoSlug(repo)) {
            return@withContext AppUpdateState.NeedsRepoConfig(
                "Connect your GitHub repository (owner/repo) once, or install a GitHub-built APK to check updates automatically."
            )
        }

        try {
            val latestObj = fetchLatestReleaseJson(repo)
                ?: return@withContext AppUpdateState.UpToDate(
                    currentBuild = currentBuildNumber,
                    latestBuild = currentBuildNumber
                )

            val parsed = parseRelease(latestObj, repo)
                ?: return@withContext AppUpdateState.Error("Latest release did not contain a valid GitHub link.")

            val isNewer = when {
                parsed.latestBuildNumber > currentBuildNumber -> true
                // Local/AI Studio build (#1) with no baked repo vs first GitHub release build #1
                currentBuildNumber == 1 && !isUsingBakedRepo() && parsed.latestBuildNumber >= 1 && parsed.apkDownloadUrl != null -> true
                else -> false
            }

            if (isNewer) {
                AppUpdateState.UpdateAvailable(parsed)
            } else {
                AppUpdateState.UpToDate(
                    currentBuild = currentBuildNumber,
                    latestBuild = parsed.latestBuildNumber.coerceAtLeast(currentBuildNumber)
                )
            }
        } catch (e: Exception) {
            AppUpdateState.Error(e.message ?: "Unable to check for updates right now.")
        }
    }

    fun openUpdateUrl(context: Context, url: String): Result<Unit> {
        if (!isTrustedGitHubHttpsUrl(url)) {
            return Result.failure(IllegalArgumentException("Blocked untrusted update URL."))
        }
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun fetchLatestReleaseJson(repo: String): JSONObject? {
        val latestReq = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "UChat-Android/$currentVersionName")
            .get()
            .build()

        httpClient.newCall(latestReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when (resp.code) {
                in 200..299 -> if (body.isNotBlank()) return JSONObject(body)
                404 -> {
                    // Fallback to releases list in case only pre-releases or non-latest releases exist
                    return fetchFirstReleaseFromList(repo)
                }
                403 -> throw IllegalStateException("GitHub API rate limit reached or repository is private.")
                else -> throw IllegalStateException("GitHub returned HTTP ${resp.code} while checking updates.")
            }
        }
        return null
    }

    private fun fetchFirstReleaseFromList(repo: String): JSONObject? {
        val listReq = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases?per_page=5")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "UChat-Android/$currentVersionName")
            .get()
            .build()

        httpClient.newCall(listReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 404) {
                throw IllegalStateException("Repository $repo not found or is private.")
            }
            if (resp.code == 403) {
                throw IllegalStateException("GitHub API rate limit reached or repository is private.")
            }
            if (!resp.isSuccessful || body.isBlank()) return null
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (!obj.optBoolean("draft", false)) return obj
            }
        }
        return null
    }

    private fun parseRelease(obj: JSONObject, repo: String): AppReleaseInfo? {
        val tagName = obj.optString("tag_name", "").trim()
        val title = obj.optString("name", "").trim().ifBlank { tagName.ifBlank { "New UChat Update" } }
        val notes = obj.optString("body", "").trim()
        val publishedAt = obj.optString("published_at", "").trim()
        val rawHtmlUrl = obj.optString("html_url", "").trim()
            .ifBlank { "https://github.com/$repo/releases" }

        if (!isTrustedGitHubHttpsUrl(rawHtmlUrl)) return null

        var apkUrl: String? = null
        var apkName: String? = null
        var apkSize: Long? = null
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "").trim()
                val dlUrl = asset.optString("browser_download_url", "").trim()
                if (name.endsWith(".apk", ignoreCase = true) && isTrustedGitHubHttpsUrl(dlUrl)) {
                    apkUrl = dlUrl
                    apkName = name
                    apkSize = asset.optLong("size", 0L).takeIf { it > 0L }
                    break
                }
            }
        }

        val buildNum = extractBuildNumber(tagName)
            ?: extractBuildNumber(title)
            ?: extractBuildNumber(apkName.orEmpty())
            ?: (currentBuildNumber + 1)

        return AppReleaseInfo(
            latestBuildNumber = buildNum,
            tagName = tagName,
            title = title,
            notes = notes,
            apkDownloadUrl = apkUrl,
            apkFileName = apkName,
            apkSizeBytes = apkSize,
            releasePageUrl = rawHtmlUrl,
            publishedAt = publishedAt
        )
    }

    companion object {
        fun normalizeRepoInput(raw: String): String {
            val trimmed = raw.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("github.com/")
                .removeSuffix(".git")
                .trim('/')
            val parts = trimmed.split("/").filter { it.isNotBlank() }
            return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else trimmed
        }

        fun isValidRepoSlug(slug: String): Boolean = REPO_REGEX.matches(slug)

        fun extractBuildNumber(text: String): Int? {
            if (text.isBlank()) return null
            val match = BUILD_NUM_REGEX.find(text) ?: return null
            return match.groupValues.getOrNull(1)?.toIntOrNull()
        }

        fun isTrustedGitHubHttpsUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            val host = uri.host?.lowercase() ?: return false
            return host == "github.com" ||
                host == "api.github.com" ||
                host.endsWith(".githubusercontent.com")
        }
    }
}
