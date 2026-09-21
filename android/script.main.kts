#!/usr/bin/env kotlin
import java.net.URL
import java.net.HttpURLConnection

val queries = listOf("EPILEPTIC TECHNO", "Toby Fox", "Despacito", "Skrillex")
for (q in queries) {
    println("=== QUERY: $q ===")
    val urlStr = "https://api.audius.co/v1/tracks/search?query=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=20"
    println("URL: $urlStr")
    val url = URL(urlStr)
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    try {
        val response = conn.inputStream.bufferedReader().readText()
        println("HTTP: ${conn.responseCode}")
        val count = response.split("\"id\":").size - 1
        println("Raw results count roughly: $count")
        
        // Let's do a quick regex to see titles and is_streamable etc
        val regex = "\"title\":\"([^\"]+)\"|\"is_streamable\":(true|false)|\"is_delete\":(true|false)".toRegex()
        val matches = regex.findAll(response).toList()
        for (m in matches.take(15)) {
            println(m.value)
        }
    } catch (e: Exception) {
        println("ERROR: ${e.message}")
    }
}
