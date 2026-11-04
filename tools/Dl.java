import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Minimal HTTPS downloader used because the machine's schannel TLS stack is failing. */
public final class Dl {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: java Dl.java <url> <output>");
            System.exit(2);
        }
        URI uri = URI.create(args[0]);
        Path out = Path.of(args[1]);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(15))
                .header("User-Agent", "rail-restock-build")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            System.err.println("HTTP " + response.statusCode() + " for " + uri);
            System.exit(1);
        }
        long total;
        try (InputStream in = response.body()) {
            Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        total = Files.size(out);
        System.out.println("downloaded " + total + " bytes to " + out);
    }
}
