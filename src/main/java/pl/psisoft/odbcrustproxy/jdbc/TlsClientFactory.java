// Copyright (C) 2026 PSI SOFT Sp. z o.o.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//         http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package pl.psisoft.odbcrustproxy.jdbc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Fabryka izolowanych klientów {@link HttpClient} realizująca przypinanie certyfikatu
 * serwera (Certificate Pinning / PSK-like TLS).
 *
 * <p>Każdy klient TLS używa prywatnego, lokalnego obiektu {@link SSLContext} z pustym
 * magazynem {@link KeyStore} w pamięci RAM (ignorującym systemowe {@code cacerts}),
 * dzięki czemu <b>nie ingeruje</b> w globalny stan TLS maszyny JVM ani ruch HTTPS
 * pozostałych komponentów aplikacji hostującej.</p>
 */
public final class TlsClientFactory {

    public static final String SYS_PROP_TLS_CERT = "rustodbc.tls.cert";
    public static final String ENV_TLS_CERT = "RUSTODBC_TLS_CERT";

    private static final String[] CLASSPATH_CANDIDATES = {"/cert.pem", "/certs/cert.pem"};

    public static final HttpClient PLAIN_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ConcurrentHashMap<String, HttpClient> PINNED_CLIENT_CACHE = new ConcurrentHashMap<>();

    private TlsClientFactory() {
    }

    /**
     * Zwraca współdzielonego klienta HTTP bez szyfrowania TLS (wyłącznie przy jawnym {@code tls=false}).
     */
    public static HttpClient getPlainClient() {
        return PLAIN_HTTP_CLIENT;
    }

    /**
     * Zwraca lub buduje (z pamięcią podręczną) izolowanego klienta HTTPS przypiętego wyłącznie do
     * wskazanego certyfikatu {@code cert.pem}.
     *
     * <p>Kaskada wyszukiwania certyfikatu (Opcja A – Fail-Closed):
     * <ol>
     *   <li>Jawna ścieżka z URL / {@code Properties} ({@code tlsCert} / {@code certPath})</li>
     *   <li>Właściwość systemowa JVM {@code -Drustodbc.tls.cert=...}</li>
     *   <li>Zmienna środowiskowa {@code RUSTODBC_TLS_CERT}</li>
     *   <li>Zasób na classpath ({@code /cert.pem} lub {@code /certs/cert.pem})</li>
     *   <li>Plik {@code cert.pem} w bieżącym katalogu roboczym</li>
     * </ol>
     * </p>
     */
    public static HttpClient getPinnedTlsClient(String explicitCertPath) throws SQLException {
        ResolvedCert resolved = resolveCertificateSource(explicitCertPath);

        HttpClient cached = PINNED_CLIENT_CACHE.get(resolved.cacheKey());
        if (cached != null) {
            return cached;
        }

        HttpClient created = buildIsolatedPinnedClient(resolved.pemBytes(), resolved.description());
        HttpClient existing = PINNED_CLIENT_CACHE.putIfAbsent(resolved.cacheKey(), created);
        return existing != null ? existing : created;
    }

    /**
     * Buduje izolowany {@link HttpClient} z pustym {@link KeyStore} w RAM i wyłącznie podanymi bajtami PEM.
     */
    public static HttpClient buildIsolatedPinnedClient(byte[] pemBytes, String sourceDescription) throws SQLException {
        try (InputStream is = new ByteArrayInputStream(pemBytes)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(is);
            if (certs == null || certs.isEmpty()) {
                throw new SQLException("Plik certyfikatu TLS (" + sourceDescription + ") nie zawiera poprawnego certyfikatu X.509.");
            }

            // Pusty magazyn w pamięci RAM – celowo ignorujemy systemowy magazyn cacerts
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);

            int idx = 0;
            for (Certificate cert : certs) {
                trustStore.setCertificateEntry("pinned-odbc-rust-proxy-" + idx++, cert);
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            // Lokalny SSLContext – NIGDY nie wywołujemy SSLContext.setDefault() ani HttpsURLConnection.setDefault...
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);

            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .sslContext(sslContext)
                    .build();
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(
                    "Błąd inicjalizacji szyfrowania TLS (Certificate Pinning) dla certyfikatu '"
                            + sourceDescription + "': " + e.getMessage(),
                    e
            );
        }
    }

    private static ResolvedCert resolveCertificateSource(String explicitCertPath) throws SQLException {
        if (explicitCertPath != null && !explicitCertPath.isBlank()) {
            return loadFromExplicitFile(explicitCertPath.trim(), "parametru tlsCert/certPath");
        }

        String sysProp = System.getProperty(SYS_PROP_TLS_CERT);
        if (sysProp != null && !sysProp.isBlank()) {
            return loadFromExplicitFile(sysProp.trim(), "właściwości JVM -D" + SYS_PROP_TLS_CERT);
        }

        String envVar = System.getenv(ENV_TLS_CERT);
        if (envVar != null && !envVar.isBlank()) {
            return loadFromExplicitFile(envVar.trim(), "zmiennej środowiskowej " + ENV_TLS_CERT);
        }

        List<String> checkedLocations = new ArrayList<>();

        for (String resourcePath : CLASSPATH_CANDIDATES) {
            ResolvedCert cpCert = tryLoadFromClasspath(resourcePath);
            if (cpCert != null) {
                return cpCert;
            }
            checkedLocations.add("classpath:" + resourcePath);
        }

        Path localFile = Path.of("cert.pem").toAbsolutePath().normalize();
        if (Files.isRegularFile(localFile)) {
            return readLocalFile(localFile);
        }
        checkedLocations.add("file:" + localFile);

        throw new SQLException(
                "BŁĄD TLS (Fail-Closed): Nie odnaleziono wymaganego certyfikatu serwera ('cert.pem') do weryfikacji Certificate Pinning.\n"
                        + "Sprawdzono lokalizacje: " + String.join(", ", checkedLocations) + "\n"
                        + "Przekaż ścieżkę certyfikatu parametrem URL (?tlsCert=...), właściwością JVM (-D" + SYS_PROP_TLS_CERT + "=...), "
                        + "umieść 'cert.pem' w zasobach classpath (/cert.pem), lub w razie testów wyłącz TLS jawnie przez '?tls=false'."
        );
    }

    private static ResolvedCert loadFromExplicitFile(String rawPath, String sourceLabel) throws SQLException {
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new SQLException(
                    "BŁĄD TLS (Fail-Closed): Nie znaleziono pliku certyfikatu wskazanego przez "
                            + sourceLabel + ": '" + path + "'"
            );
        }
        return readLocalFile(path);
    }

    private static ResolvedCert readLocalFile(Path normalizedPath) throws SQLException {
        try {
            byte[] bytes = Files.readAllBytes(normalizedPath);
            long mtime = Files.getLastModifiedTime(normalizedPath).toMillis();
            String key = "file:" + normalizedPath + "@" + mtime;
            return new ResolvedCert(key, normalizedPath.toString(), bytes);
        } catch (IOException e) {
            throw new SQLException("Nie udało się odczytać pliku certyfikatu '" + normalizedPath + "': " + e.getMessage(), e);
        }
    }

    private static ResolvedCert tryLoadFromClasspath(String resourcePath) throws SQLException {
        String withoutLeadingSlash = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();

        try (InputStream is = openClasspathStream(resourcePath, withoutLeadingSlash, tccl)) {
            if (is == null) {
                return null;
            }
            byte[] bytes = is.readAllBytes();
            return new ResolvedCert("classpath:" + resourcePath, "classpath:" + resourcePath, bytes);
        } catch (IOException e) {
            throw new SQLException("Nie udało się odczytać certyfikatu z classpath '" + resourcePath + "': " + e.getMessage(), e);
        }
    }

    private static InputStream openClasspathStream(String absPath, String relPath, ClassLoader tccl) {
        if (tccl != null) {
            InputStream fromTccl = tccl.getResourceAsStream(relPath);
            if (fromTccl != null) {
                return fromTccl;
            }
        }
        return TlsClientFactory.class.getResourceAsStream(absPath);
    }

    private record ResolvedCert(String cacheKey, String description, byte[] pemBytes) {
    }
}
