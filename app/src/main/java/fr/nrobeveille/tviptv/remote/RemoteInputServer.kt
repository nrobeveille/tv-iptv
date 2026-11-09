package fr.nrobeveille.tviptv.remote

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Mini serveur web local : depuis le téléphone (même Wi-Fi), on ouvre l'adresse affichée
 * sur la TV et on colle l'URL de la playlist au lieu de la taper à la télécommande.
 */
class RemoteInputServer(
    private val onPlaylist: (name: String, url: String, epg: String) -> Unit,
) {
    private var socket: ServerSocket? = null

    /** Démarre le serveur ; renvoie l'adresse à ouvrir, ou null si impossible. */
    fun start(): String? {
        val ip = localIpAddress() ?: return null
        for (port in PORTS) {
            val server = try {
                ServerSocket(port)
            } catch (e: Exception) {
                continue
            }
            socket = server
            thread(name = "remote-input", isDaemon = true) { acceptLoop(server) }
            return "http://$ip:$port"
        }
        return null
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                continue
            }
            thread(isDaemon = true) { runCatching { handle(client) }; runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val method = requestLine.substringBefore(' ')
        val path = requestLine.substringAfter(' ').substringBefore(' ')

        var contentLength = 0
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        if (method == "POST" && path.startsWith("/add")) {
            val body = CharArray(contentLength.coerceIn(0, 64 * 1024))
            var read = 0
            while (read < body.size) {
                val n = reader.read(body, read, body.size - read)
                if (n < 0) break
                read += n
            }
            val form = parseForm(String(body, 0, read))
            val url = form["url"].orEmpty().trim()
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                onPlaylist(form["name"].orEmpty().trim(), url, form["epg"].orEmpty().trim())
                respond(client, page("✅ Playlist envoyée à la TV", "<p>Tu peux fermer cette page.</p>"))
            } else {
                respond(client, page("Adresse invalide", "<p>L'URL doit commencer par http:// ou https://</p>$FORM"))
            }
        } else {
            respond(client, page("Ajouter une playlist IPTV", FORM))
        }
    }

    private fun respond(client: Socket, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.contains('=') }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('='))
        }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun page(title: String, content: String) = """
        <!DOCTYPE html><html lang="fr"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>IPTV</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#0a0e1a;color:#fff;margin:0;padding:24px;max-width:560px;margin:auto}
          h1{font-size:22px} label{display:block;margin:16px 0 6px;color:#c9cfe0}
          input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #333a55;background:#141a2e;color:#fff;font-size:16px}
          button{margin-top:22px;width:100%;padding:14px;border:0;border-radius:10px;background:#ff5c8a;color:#fff;font-size:17px;font-weight:600}
          small{color:#8d94ab}
        </style></head><body><h1>$title</h1>$content</body></html>
    """.trimIndent()

    private companion object {
        val PORTS = listOf(8765, 8766, 8767, 8768, 8769)

        const val FORM = """
            <form method="post" action="/add">
              <label>Nom</label><input name="name" placeholder="Mon IPTV">
              <label>URL de la playlist M3U</label>
              <input name="url" type="url" required placeholder="http://serveur:8080/get.php?username=…&amp;password=…&amp;type=m3u_plus">
              <label>Guide TV (XMLTV) <small>— facultatif</small></label>
              <input name="epg" type="url" placeholder="Laisser vide = automatique">
              <button type="submit">Envoyer à la TV</button>
            </form>"""

        fun localIpAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
