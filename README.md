# Rust ODBC Proxy JDBC Driver (`pl.psisoft.odbc-rust-proxy`)

Lekki sterownik JDBC (dla Java 21+) umożliwiający 64-bitowym aplikacjom Java łączenie się z **32-bitowymi źródłami danych ODBC** (np. dBase, FoxPro, stare bazy eFKa) za pośrednictwem lokalnego lub sieciowego mikroserwisu [`odbc-rust-proxy`](https://github.com/PLPsiSoft/odbc-rust-proxy).

Stanowi bezpośredni, nowoczesny zamiennik dla wycofanego w Java 8 systemowego mostka `sun.jdbc.odbc.JdbcOdbcDriver`.

---

## Główne cechy

* **Brak konieczności używania 32-bitowej maszyny JVM**: Aplikacja Java działa w nowoczesnej 64-bitowej JVM, a 32-bitowe biblioteki DLL sterowników ODBC są obsługiwane w osobnym procesie proxy.
* **Kompatybilność z legacy JDBC**: Obsługuje zarówno nowy schemat URL `jdbc:rustodbc://`, jak i tradycyjne adresy `jdbc:odbc:`.
* **Automatyczna rejestracja (Java SPI)**: Gotowy do użycia przez `java.sql.DriverManager` bez konieczności ręcznego ładowania klasy.
* **Pełne wsparcie transakcji**: Obsługa `setAutoCommit(false)`, `commit()` i `rollback()` z gwarancją spójności na dedykowanym wątku sesji.
* **Zapytania sparametryzowane**: `PreparedStatement` z obsługą typów `setString`, `setInt`, `setDouble`, `setBigDecimal`, `setDate`, `setTimestamp` oraz poprawnym wiązaniem `NULL` (`setNull`).
* **Stronicowanie kursorów (`fetchSize`)**: Bezpieczne pobieranie dużych zbiorów danych partiami bez ryzyka przepełnienia pamięci RAM.
* **Polskie strony kodowe**: Transparentne dekodowanie CP852 (DOS Latin-2 / `EE8PC852`), Windows-1250 (`EE8MSWIN1250`), ISO-8859-2 oraz UTF-8.
* **Bezpieczeństwo TLS i Certificate Pinning**: Domyślna komunikacja po **HTTPS** (port `8097`) z przypiętym certyfikatem serwera (`cert.pem`) w pustym magazynie `KeyStore` w pamięci RAM (z pełną izolacją — bez ingerencji w globalny magazyn `cacerts` aplikacji hostującej).

---

## Bezpieczeństwo TLS (Certificate Pinning)

Sterownik realizuje koncepcję szyfrowania zbliżoną do **PSK (Pre-Shared Key)** na bazie TLS:

1. Tworzy w pamięci RAM pusty `KeyStore` i wczytuje wyłącznie publiczny certyfikat serwera proxy (`cert.pem`). Systemowe urzędy certyfikacji (`cacerts`) są przez tego klienta ignorowane — każdy inny certyfikat zostaje natychmiast odrzucony (`SSLHandshakeException`).
2. **Pełna izolacja JVM**: Prywatny `SSLContext` jest przekazywany wyłącznie do wewnętrznego klienta `java.net.http.HttpClient` sterownika JDBC. Sterownik **nigdy nie wywołuje** `SSLContext.setDefault(...)` ani nie zmienia `javax.net.ssl.trustStore`, dzięki czemu pozostały ruch HTTPS aplikacji (np. KSeF, banki, WebService'y) działa bez zmian.
3. **Polityka Fail-Closed**: Domyślnie połączenie wymaga TLS (`tls=true`). Brak `cert.pem` skutkuje czytelnym wyjątkiem `SQLException` (brak cichego przełączenia na nieszyfrowany HTTP).

### Kolejność wyszukiwania pliku `cert.pem`:
1. Parametr połączenia w URL lub `Properties`: `tlsCert` / `certPath` (np. `?tlsCert=C:/certs/cert.pem`)
2. Właściwość systemowa JVM: `-Drustodbc.tls.cert=C:\certs\cert.pem`
3. Zmienna środowiskowa: `RUSTODBC_TLS_CERT`
4. Zasób na classpath aplikacji / w JAR: `/cert.pem` lub `/certs/cert.pem`
5. Plik `cert.pem` w bieżącym katalogu roboczym.

Aby jawnie wyłączyć szyfrowanie wyłącznie do lokalnych testów, dodaj parametr `?tls=false` (lub ustaw `-Drustodbc.tls.enabled=false`).

---

## Formaty połączenia (JDBC URL)

### 1. Dedykowany format `jdbc:rustodbc://`
```text
jdbc:rustodbc://[host:port]/DSN[?parametry]
```
Przykłady:
```java
// Domyślny port proxy 8097 (HTTPS z Certificate Pinning), źródło DSN "Test"
String url = "jdbc:rustodbc://127.0.0.1:8097/Test";

// Z podaniem kodowania DOS CP852 oraz jawnej ścieżki do cert.pem
String url = "jdbc:rustodbc://127.0.0.1:8097/Test?charset=cp852&tlsCert=C:/certs/cert.pem";

// Wyłącznie do testów bez TLS (wymaga flagi --no-tls na serwerze proxy)
String url = "jdbc:rustodbc://127.0.0.1:8097/Test?tls=false";
```

### 2. Tradycyjny format `jdbc:odbc:` (Drop-in Replacement)
Sterownik przejmuje obsługę standardowych adresów URL:
```text
jdbc:odbc:DSN[?parametry]
```
Przykłady:
```java
String url = "jdbc:odbc:Test";
String url = "jdbc:odbc:Test?charset=cp852";
```
*Domyślnie sterownik kieruje ruch na `https://127.0.0.1:8097`. Adres serwera proxy można zmienić właściwością JVM: `-Drustodbc.proxy.url=https://host:port`.*

---

## Przykład użycia

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

public class PrzykladUzycia {
    public static void main(String[] args) throws Exception {
        // Parametry połączenia i kodowania
        Properties props = new Properties();
        props.put("charset", "cp852"); // lub EE8PC852
        props.put("user", "admin");
        props.put("password", "tajne");
        // Certyfikat do przypięcia (można też umieścić jako /cert.pem na classpath lub przez -Drustodbc.tls.cert)
        props.put("tlsCert", "C:/certs/cert.pem");

        String url = "jdbc:rustodbc://127.0.0.1:8097/Test";

        try (Connection conn = DriverManager.getConnection(url, props)) {
            // Kontrola transakcji
            conn.setAutoCommit(false);

            String sql = "SELECT DOKUMENT, TRESC, WARTOSC, MIASTO FROM EFKA WHERE DOKUMENT = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, 1);
                stmt.setFetchSize(50); // pobieranie paczkami po 50 wierszy

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        int nrDokumentu = rs.getInt("DOKUMENT");
                        String tresc = rs.getString("TRESC");
                        double wartosc = rs.getDouble("WARTOSC");
                        String miasto = rs.getString("MIASTO"); // automatycznie poprawne polskie znaki

                        System.out.printf("Dokument: %d | %s | %.2f zł | %s\n",
                                nrDokumentu, tresc, wartosc, miasto);
                    }
                }
            }

            conn.commit();
        }
    }
}
```

---

## Kompilacja i budowanie

Wymagania: **JDK 21+** oraz **Maven 3.8+**.

```cmd
# Uruchomienie testów jednostkowych i integracyjnych:
mvn test

# Zbudowanie pliku JAR sterownika:
mvn clean package
```

Wynikowy plik JAR:
`target/pl.psisoft.odbc-rust-proxy-0.0.1.jar`

---

## Licencja

Projekt objęty jest licencją **Apache License 2.0**. Szczegóły znajdują się w pliku [`LICENSE`](./LICENSE).
