package ru.oritas.repricer.platform;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** KV v2 immutable revisions. Secret values never enter exceptions or object diagnostics. */
@Component
public final class VaultSecretStore {
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private final URI address;
  private final Path tokenFile;
  private final JsonCodec json;

  public VaultSecretStore(@Value("${repricer.vault.address}") URI address,
      @Value("${repricer.vault.token-file}") Path tokenFile, JsonCodec json) {
    this.address = address;
    this.tokenFile = tokenFile;
    this.json = json;
  }

  public long put(String path, Map<String, String> values) {
    if (values.isEmpty() || values.size() > 20
        || values.values().stream().anyMatch(value -> value.length() > 16384)) {
      throw new IllegalArgumentException("Secret exceeds bounded envelope");
    }
    String body = json.encode(new Write(new Options(0), Map.copyOf(values)));
    Envelope response = request(path, "POST", body, Envelope.class);
    if (response.data() == null || response.data().version() < 1) {
      throw new IllegalStateException("Vault did not confirm stored revision");
    }
    return response.data().version();
  }

  public Map<String, String> read(String path, long version) {
    if (version < 1) {
      throw new IllegalArgumentException("An exact secret version is required");
    }
    ReadEnvelope response = request(path + "?version=" + version, "GET", null, ReadEnvelope.class);
    if (response.data() == null || response.data().data() == null
        || response.data().metadata().version() != version) {
      throw new IllegalStateException("Vault returned an unexpected revision");
    }
    return Map.copyOf(response.data().data());
  }

  /** CAS=0 makes concurrent preparation share the first immutable secret. */
  public Map<String, String> putIfAbsent(String path, Map<String, String> values) {
    try {
      put(path, values);
      return Map.copyOf(values);
    } catch (BusinessException exception) {
      if (!exception.code().equals("VAULT_WRITE_REJECTED")) {
        throw exception;
      }
      return read(path, 1);
    }
  }

  private <T> T request(String path, String method, String body, Class<T> responseType) {
    if (!path.matches("repricer/[a-zA-Z0-9/_-]{1,240}(\\?version=[1-9][0-9]*)?")) {
      throw new IllegalArgumentException("Invalid secret path");
    }
    try {
      if (Files.size(tokenFile) > 4096) {
        throw new IOException("Invalid Vault token file");
      }
      String token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
      HttpRequest request = HttpRequest.newBuilder(address.resolve("/v1/secret/data/" + path))
          .timeout(Duration.ofSeconds(15)).header("X-Vault-Token", token)
          .header("Content-Type", "application/json")
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
      HttpResponse<java.io.InputStream> response = http.send(request,
          HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          if (response.statusCode() == 404) {
            throw new BusinessException("SECRET_NOT_FOUND", 404, "Секрет не найден");
          }
          if (response.statusCode() == 400 && method.equals("POST")) {
            throw new BusinessException("VAULT_WRITE_REJECTED", 409, "Vault отклонил запись секрета");
          }
          throw new IllegalStateException("Vault operation was not confirmed: " + response.statusCode());
        }
        byte[] bytes = input.readNBytes(JsonCodec.MAX_MESSAGE_BYTES + 1);
        if (bytes.length > JsonCodec.MAX_MESSAGE_BYTES) {
          throw new IOException("Vault response exceeded its limit");
        }
        return json.decode(new String(bytes, StandardCharsets.UTF_8), responseType);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Vault operation interrupted", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("Vault unavailable", exception);
    }
  }

  private record Options(int cas) {}

  private record Write(Options options, Map<String, String> data) {
    @Override
    public String toString() {
      return "VaultWrite[redacted]";
    }
  }

  private record Version(long version) {}

  private record Envelope(Version data) {}

  private record ReadData(Map<String, String> data, Version metadata) {
    @Override
    public String toString() {
      return "VaultRead[redacted]";
    }
  }

  private record ReadEnvelope(ReadData data) {}
}
